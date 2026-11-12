package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.LedgerEntry;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.enums.TransactionType;
import com.assessment.repository.AccountRepository;
import com.assessment.repository.LedgerEntryRepository;
import com.assessment.repository.TransactionEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TransactionProcessor {

    private final TransactionValidator validator;
    private final IdempotencyService idempotencyService;
    private final OrderingService orderingService;
    private final AccountRepository accountRepository;
    private final TransactionEntityRepository transactionEntityRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransactionProcessor(
            TransactionValidator validator,
            IdempotencyService idempotencyService,
            OrderingService orderingService,
            AccountRepository accountRepository,
            TransactionEntityRepository transactionEntityRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.validator = validator;
        this.idempotencyService = idempotencyService;
        this.orderingService = orderingService;
        this.accountRepository = accountRepository;
        this.transactionEntityRepository = transactionEntityRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public TransactionEntity process(TransactionEntity transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction is required.");
        }

        TransactionEntity transactionToProcess = transaction;
        boolean resumingRetry = false;
        if (transaction.getTransactionId() != null) {
            var existing = transactionEntityRepository.findByTransactionId(transaction.getTransactionId());
            if (existing.isPresent()) {
                TransactionEntity stored = existing.get();
                if (stored.getId().equals(transaction.getId())) {
                    transactionToProcess = stored;
                    resumingRetry = stored.getStatus() == TransactionStatus.RETRY_PENDING
                            || stored.getStatus() == TransactionStatus.PENDING
                            || transaction.getStatus() == TransactionStatus.PROCESSED;
                }
            }
        }

        String idempotencyKey = transactionToProcess.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingKey = transactionEntityRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existingKey.isPresent()
                    && !existingKey.get().getId().equals(transactionToProcess.getId())) {
                transactionToProcess.setStatus(TransactionStatus.DUPLICATE);
                transactionToProcess.setErrorMessage("Duplicate idempotency key.");
                idempotencyService.recordDuplicateAttempt(
                        transactionToProcess.getExternalRequestId(),
                        transactionToProcess.getTransactionId(),
                        transactionToProcess.getSequenceNumber(),
                        serializeTransaction(transactionToProcess));
                return transactionToProcess;
            }
        }

        if (!resumingRetry) {
            transactionToProcess.setStatus(TransactionStatus.RECEIVED);
        }
        transactionToProcess.setErrorMessage(null);

        TransactionValidator.ValidationResult validationResult = validator.validate(transactionToProcess);
        if (!validationResult.valid()) {
            transactionToProcess.setStatus(TransactionStatus.FAILED);
            transactionToProcess.setErrorMessage(validationResult.message());
            return transactionEntityRepository.save(transactionToProcess);
        }

        String serializedTransaction = serializeTransaction(transactionToProcess);
        boolean accepted = idempotencyService.claimTransaction(
                transactionToProcess.getExternalRequestId(),
                transactionToProcess.getTransactionId(),
                transactionToProcess.getSequenceNumber(),
                serializedTransaction);

        if (!accepted) {
            transactionToProcess.setStatus(TransactionStatus.DUPLICATE);
            transactionToProcess.setErrorMessage("Duplicate external request detected.");
            return transactionToProcess;
        }

        transactionToProcess.setStatus(TransactionStatus.PROCESSING);
        TransactionEntity persisted = transactionEntityRepository.saveAndFlush(transactionToProcess);
        Account account = accountRepository.findByIdForUpdate(persisted.getAccount().getId())
                .orElseThrow(() -> new IllegalStateException("Account not found for transaction: " + persisted.getTransactionId()));

        BigDecimal startingBalance = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
        BigDecimal balanceAfter = calculateBalance(startingBalance, persisted.getTransactionType(), persisted.getAmount());
        account.setBalance(balanceAfter);
        accountRepository.save(account);

        LedgerEntry ledgerEntry = LedgerEntry.builder()
                .account(account)
                .transaction(persisted)
                .entryType(persisted.getTransactionType())
                .amount(persisted.getAmount())
                .balanceAfter(balanceAfter)
                .description("Processed " + persisted.getTransactionType() + " transaction " + persisted.getTransactionId())
                .build();
        ledgerEntryRepository.save(ledgerEntry);

        persisted.setStatus(TransactionStatus.PROCESSED);
        persisted.setErrorMessage(null);
        return transactionEntityRepository.saveAndFlush(persisted);
    }

    @Transactional
    public List<TransactionEntity> processBatch(List<TransactionEntity> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        Map<Long, List<TransactionEntity>> transactionsByAccount = new LinkedHashMap<>();
        for (TransactionEntity transaction : transactions) {
            if (transaction != null) {
                Long accountId = transaction.getAccount() == null ? null : transaction.getAccount().getId();
                transactionsByAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(transaction);
            }
        }

        List<TransactionEntity> processed = new ArrayList<>();
        for (Map.Entry<Long, List<TransactionEntity>> accountBatch : transactionsByAccount.entrySet()) {
            List<TransactionEntity> accountTransactions = accountBatch.getValue();
            Set<String> incomingTransactionIds = new HashSet<>();
            accountTransactions.forEach(transaction -> incomingTransactionIds.add(transaction.getTransactionId()));
            if (accountBatch.getKey() != null) {
                transactionEntityRepository.findByAccount_IdAndStatusOrderBySequenceNumberAsc(
                                accountBatch.getKey(), TransactionStatus.PENDING).stream()
                        .filter(pending -> !incomingTransactionIds.contains(pending.getTransactionId()))
                        .forEach(accountTransactions::add);
            }

            Long previousSequence = accountBatch.getKey() == null ? null : transactionEntityRepository
                    .findTopByAccount_IdAndStatusOrderBySequenceNumberDesc(
                            accountBatch.getKey(), TransactionStatus.PROCESSED)
                    .map(TransactionEntity::getSequenceNumber)
                    .orElse(null);
            accountTransactions.sort(Comparator.comparing(TransactionEntity::getSequenceNumber,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TransactionEntity::getTransactionId,
                            Comparator.nullsLast(Comparator.naturalOrder())));

            for (TransactionEntity transaction : orderingService.orderBatch(accountTransactions, previousSequence)) {
                if (transaction.getStatus() == TransactionStatus.PROCESSED) {
                    processed.add(process(transaction));
                } else {
                    processed.add(transactionEntityRepository.save(transaction));
                }
            }
        }

        return processed;
    }

    private BigDecimal calculateBalance(BigDecimal currentBalance, TransactionType transactionType, BigDecimal amount) {
        if (transactionType == null || amount == null) {
            return currentBalance;
        }

        return switch (transactionType) {
            case CREDIT, ADJUSTMENT -> currentBalance.add(amount);
            case DEBIT, TRANSFER -> currentBalance.subtract(amount);
        };
    }

    private String serializeTransaction(TransactionEntity transaction) {
        return transaction.getTransactionId() + "|" + transaction.getExternalRequestId() + "|"
                + transaction.getAccount().getAccountNumber() + "|" + transaction.getSequenceNumber() + "|"
                + transaction.getAmount();
    }
}
