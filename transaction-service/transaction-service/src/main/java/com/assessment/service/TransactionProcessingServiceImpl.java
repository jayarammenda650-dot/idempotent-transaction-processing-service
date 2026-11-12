package com.assessment.service;

import com.assessment.dto.ProcessingSummary;
import com.assessment.dto.TransactionBatchRequest;
import com.assessment.dto.TransactionBatchResponse;
import com.assessment.dto.TransactionRequest;
import com.assessment.dto.TransactionResponse;
import com.assessment.entity.Account;
import com.assessment.entity.LedgerEntry;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.exception.ResourceNotFoundException;
import com.assessment.repository.AccountRepository;
import com.assessment.repository.LedgerEntryRepository;
import com.assessment.repository.ProcessedRequestRepository;
import com.assessment.repository.TransactionEntityRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class TransactionProcessingServiceImpl implements TransactionProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionProcessingServiceImpl.class);

    private final RetryService retryService;
    private final OrderingService orderingService;
    private final TransactionValidator transactionValidator;
    private final Validator requestValidator;
    private final AccountRepository accountRepository;
    private final TransactionEntityRepository transactionEntityRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ProcessedRequestRepository processedRequestRepository;

    public TransactionProcessingServiceImpl(
            RetryService retryService,
            OrderingService orderingService,
            TransactionValidator transactionValidator,
            Validator requestValidator,
            AccountRepository accountRepository,
            TransactionEntityRepository transactionEntityRepository,
            LedgerEntryRepository ledgerEntryRepository,
            ProcessedRequestRepository processedRequestRepository) {
        this.retryService = retryService;
        this.orderingService = orderingService;
        this.transactionValidator = transactionValidator;
        this.requestValidator = requestValidator;
        this.accountRepository = accountRepository;
        this.transactionEntityRepository = transactionEntityRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.processedRequestRepository = processedRequestRepository;
    }

    @Override
    public TransactionResponse processTransaction(TransactionRequest request) {
        return toResponse(retryService.process(toEntity(request)));
    }

    @Override
    public TransactionBatchResponse processBatch(TransactionBatchRequest request) {
        Map<Long, List<TransactionEntity>> transactionsByAccount = new TreeMap<>();
        List<TransactionResponse> responses = new ArrayList<>();
        List<TransactionResponse> invalidResponses = new ArrayList<>();
        for (TransactionRequest transactionRequest : request.getTransactions()) {
            if (transactionRequest == null) {
                invalidResponses.add(failedResponse(null, "Transaction item must not be null."));
                continue;
            }

            Set<ConstraintViolation<TransactionRequest>> violations = requestValidator.validate(transactionRequest);
            if (!violations.isEmpty()) {
                String validationMessage = violations.stream()
                        .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                        .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                        .collect(Collectors.joining("; "));
                invalidResponses.add(failedResponse(transactionRequest, validationMessage));
                continue;
            }

            TransactionEntity transaction;
            try {
                transaction = toEntity(transactionRequest);
            } catch (ResourceNotFoundException exception) {
                invalidResponses.add(failedResponse(transactionRequest, exception.getMessage()));
                continue;
            }

            TransactionValidator.ValidationResult validationResult = transactionValidator.validate(transaction);
            if (!validationResult.valid()) {
                invalidResponses.add(failedResponse(transactionRequest, validationResult.message()));
                continue;
            }

            var existing = transactionEntityRepository.findByTransactionId(transaction.getTransactionId());
            if (existing.isPresent()) {
                TransactionEntity stored = existing.get();
                if (stored.getStatus() == TransactionStatus.PENDING
                        && stored.getExternalRequestId().equals(transaction.getExternalRequestId())) {
                    transaction = stored;
                } else {
                    responses.add(toResponse(retryService.process(transaction)));
                    continue;
                }
            }

            transactionsByAccount.computeIfAbsent(transaction.getAccount().getId(), ignored -> new ArrayList<>())
                    .add(transaction);
        }

        for (Map.Entry<Long, List<TransactionEntity>> accountBatch : transactionsByAccount.entrySet()) {
            List<TransactionEntity> accountTransactions = accountBatch.getValue();
            Set<String> incomingTransactionIds = new HashSet<>();
            accountTransactions.forEach(transaction -> incomingTransactionIds.add(transaction.getTransactionId()));
            transactionEntityRepository.findByAccount_IdAndStatusOrderBySequenceNumberAsc(
                            accountBatch.getKey(), TransactionStatus.PENDING).stream()
                    .filter(pending -> !incomingTransactionIds.contains(pending.getTransactionId()))
                    .forEach(accountTransactions::add);
                Long previousSequence = transactionEntityRepository
                    .findTopByAccount_IdAndStatusOrderBySequenceNumberDesc(
                        accountBatch.getKey(), TransactionStatus.PROCESSED)
                    .map(TransactionEntity::getSequenceNumber)
                    .orElse(null);
            accountTransactions.sort(Comparator.comparing(TransactionEntity::getSequenceNumber)
                    .thenComparing(TransactionEntity::getTransactionId));

                for (TransactionEntity transaction : orderingService.orderBatch(accountTransactions, previousSequence)) {
                TransactionEntity result = transaction.getStatus() == TransactionStatus.PROCESSED
                    ? retryService.process(transaction)
                    : transactionEntityRepository.save(transaction);
                responses.add(toResponse(result));
            }
        }
        responses.addAll(invalidResponses);
        return TransactionBatchResponse.builder()
                .transactions(responses)
                .totalCount(responses.size())
                .processedCount(countResponsesByStatus(responses, TransactionStatus.PROCESSED))
                .duplicateCount(countResponsesByStatus(responses, TransactionStatus.DUPLICATE))
                .failedCount(countResponsesByStatus(responses, TransactionStatus.FAILED))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String transactionId) {
        TransactionEntity transaction = transactionEntityRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
        return toResponse(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessingSummary getProcessingSummary() {
        List<TransactionEntity> transactions = transactionEntityRepository.findAll();
        var processedRequests = processedRequestRepository.findAll();
        long duplicateRequests = processedRequests.stream()
            .filter(request -> request.getStatus() == TransactionStatus.DUPLICATE)
            .count()
            + processedRequests.stream()
            .mapToLong(request -> request.getDuplicateAttempts() == null ? 0L : request.getDuplicateAttempts())
            .sum();
        return ProcessingSummary.builder()
            .totalRequests(transactions.size() + duplicateRequests)
            .receivedRequests(countByStatus(transactions, TransactionStatus.RECEIVED))
            .processingRequests(countByStatus(transactions, TransactionStatus.PROCESSING))
                .processedRequests(countByStatus(transactions, TransactionStatus.PROCESSED))
            .duplicateRequests(duplicateRequests)
            .pendingRequests(countByStatus(transactions, TransactionStatus.PENDING))
            .retryPendingRequests(countByStatus(transactions, TransactionStatus.RETRY_PENDING))
            .retryScheduledRequests(countByStatus(transactions, TransactionStatus.RETRY_SCHEDULED))
            .rejectedRequests(countByStatus(transactions, TransactionStatus.REJECTED))
                .retryAttempts(transactions.stream().mapToLong(transaction -> transaction.getRetryCount() == null
                        ? 0L : transaction.getRetryCount()).sum())
            .outOfOrderRequests(countByStatus(transactions, TransactionStatus.OUT_OF_ORDER))
            .failedRequests(countByStatus(transactions, TransactionStatus.FAILED))
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private TransactionEntity toEntity(TransactionRequest request) {
        Account account = accountRepository.findByAccountNumber(request.getAccountNumber().trim())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.getAccountNumber()));
        String transactionId = firstNonBlank(request.getTransactionId(), request.getIdempotencyKey(), request.getExternalRequestId());

        return TransactionEntity.builder()
                .transactionId(transactionId)
                .externalRequestId(request.getExternalRequestId().trim())
                .account(account)
                .counterpartyAccountNumber(request.getCounterpartyAccountNumber())
                .transactionType(request.getTransactionType())
                .amount(request.getAmount())
                .currency(request.getCurrency().trim().toUpperCase())
                .sequenceNumber(request.getSequenceNumber())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .retryCount(0)
                .status(TransactionStatus.RECEIVED)
                .build();
    }

    private TransactionResponse toResponse(TransactionEntity transaction) {
        List<LedgerEntry> entries = transaction.getId() == null
                ? List.of()
                : ledgerEntryRepository.findByTransactionId(transaction.getId());
        LedgerEntry latestEntry = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        LocalDateTime processedAt = transaction.getUpdatedAt() != null
                ? transaction.getUpdatedAt() : transaction.getCreatedAt();
        return TransactionResponse.builder()
                .transactionId(transaction.getTransactionId())
                .requestId(transaction.getExternalRequestId())
                .status(transaction.getStatus())
                .message(messageFor(transaction))
                .balanceAfterProcessing(latestEntry == null ? null : latestEntry.getBalanceAfter())
                .sequenceNumber(transaction.getSequenceNumber())
                .processedAt(processedAt)
                .build();
    }

            private TransactionResponse failedResponse(TransactionRequest request, String message) {
            String transactionId = request == null ? null
                : firstNonBlankOrNull(request.getTransactionId(), request.getIdempotencyKey(), request.getExternalRequestId());
                LOGGER.warn("batch_item_rejected transactionId={}", transactionId);
            return TransactionResponse.builder()
                .transactionId(transactionId)
                .requestId(request == null ? null : request.getExternalRequestId())
                .status(TransactionStatus.FAILED)
                .message(message)
                .sequenceNumber(request == null ? null : request.getSequenceNumber())
                .processedAt(LocalDateTime.now())
                .build();
            }

    private String messageFor(TransactionEntity transaction) {
        return switch (transaction.getStatus()) {
            case PROCESSED -> "Transaction processed successfully.";
            case DUPLICATE -> "Duplicate transaction request.";
            case FAILED -> transaction.getErrorMessage() == null ? "Transaction failed." : transaction.getErrorMessage();
            case RETRY_PENDING, RETRY_SCHEDULED -> "Transaction is pending retry.";
            default -> "Transaction status: " + transaction.getStatus() + ".";
        };
    }

    private int countResponsesByStatus(List<TransactionResponse> responses, TransactionStatus status) {
        return (int) responses.stream().filter(response -> response.getStatus() == status).count();
    }

    private long countByStatus(List<TransactionEntity> transactions, TransactionStatus status) {
        return transactions.stream().filter(transaction -> transaction.getStatus() == status).count();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("A transaction identifier is required.");
    }

    private String firstNonBlankOrNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
