package com.assessment.service;

import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.exception.TransientProcessingException;
import com.assessment.repository.TransactionEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
public class RetryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryService.class);

    private final TransactionProcessor transactionProcessor;
    private final TransactionEntityRepository transactionEntityRepository;
    private final FailureSimulator failureSimulator;
        private final KeyedLockRegistry lockRegistry;
    private final IdempotencyService idempotencyService;
    private final int maximumRetries;

        @Autowired
    public RetryService(
            TransactionProcessor transactionProcessor,
            TransactionEntityRepository transactionEntityRepository,
            FailureSimulator failureSimulator,
            KeyedLockRegistry lockRegistry,
            IdempotencyService idempotencyService,
            @Value("${transaction.retry.max-retries:3}") int maximumRetries) {
        if (maximumRetries < 0) {
            throw new IllegalArgumentException("maximumRetries must not be negative");
        }
        this.transactionProcessor = transactionProcessor;
        this.transactionEntityRepository = transactionEntityRepository;
        this.failureSimulator = failureSimulator;
        this.lockRegistry = lockRegistry;
        this.idempotencyService = idempotencyService;
        this.maximumRetries = maximumRetries;
    }

    public RetryService(
            TransactionProcessor transactionProcessor,
            TransactionEntityRepository transactionEntityRepository,
            FailureSimulator failureSimulator,
            int maximumRetries) {
        this(transactionProcessor, transactionEntityRepository, failureSimulator, new KeyedLockRegistry(), null, maximumRetries);
    }

    public TransactionEntity process(TransactionEntity transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction is required.");
        }

        List<String> lockKeys = List.of(
                "request:" + normalizedLockValue(transaction.getExternalRequestId()),
                "transaction:" + normalizedLockValue(transaction.getTransactionId()));
        return lockRegistry.withLocks(lockKeys, () -> processWithLock(transaction));
    }

    private TransactionEntity processWithLock(TransactionEntity transaction) {
        if (transaction.getRetryCount() == null) {
            transaction.setRetryCount(0);
        }

        while (true) {
            try {
                failureSimulator.beforeAttempt(transaction);
                TransactionEntity result = transactionProcessor.process(transaction);
                LOGGER.info("transaction_result transactionId={} status={}",
                        result.getTransactionId(), result.getStatus());
                return result;
            } catch (TransientProcessingException | TransientDataAccessException exception) {
                int completedRetries = transaction.getRetryCount();
                transaction.setErrorMessage(message(exception));
                if (completedRetries >= maximumRetries) {
                    transaction.setStatus(TransactionStatus.FAILED);
                    LOGGER.error("transaction_retry_exhausted transactionId={} retryCount={}",
                            transaction.getTransactionId(), completedRetries);
                    return saveRetryState(transaction);
                }

                transaction.setRetryCount(completedRetries + 1);
                transaction.setStatus(TransactionStatus.RETRY_PENDING);
                LOGGER.warn("transaction_retry_scheduled transactionId={} retryCount={} maximumRetries={}",
                        transaction.getTransactionId(), transaction.getRetryCount(), maximumRetries);
                transaction = saveRetryState(transaction);
            } catch (DataIntegrityViolationException exception) {
                if (isDuplicate(transaction)) {
                    if (idempotencyService != null) {
                        try {
                            idempotencyService.recordDuplicateAttempt(
                                    transaction.getExternalRequestId(),
                                    transaction.getTransactionId(),
                                    transaction.getSequenceNumber(),
                                    null);
                        } catch (DataIntegrityViolationException ignored) {
                            // A concurrent duplicate recorder may have committed the same attempt first.
                        }
                    }
                    transaction.setStatus(TransactionStatus.DUPLICATE);
                    transaction.setErrorMessage("A matching transaction has already been accepted.");
                    LOGGER.warn("transaction_duplicate transactionId={}", transaction.getTransactionId());
                    return transaction;
                }
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setErrorMessage(message(exception));
                LOGGER.error("transaction_failed transactionId={} exceptionType={}",
                        transaction.getTransactionId(), exception.getClass().getSimpleName());
                return saveRetryState(transaction);
            } catch (RuntimeException exception) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setErrorMessage(message(exception));
                LOGGER.error("transaction_failed transactionId={} exceptionType={}",
                        transaction.getTransactionId(), exception.getClass().getSimpleName());
                return saveRetryState(transaction);
            }
        }
    }

    private String normalizedLockValue(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isDuplicate(TransactionEntity transaction) {
        if (transaction.getTransactionId() != null
                && transactionEntityRepository.findByTransactionId(transaction.getTransactionId()).isPresent()) {
            return true;
        }
        if (transaction.getExternalRequestId() != null
                && transactionEntityRepository.findByExternalRequestId(transaction.getExternalRequestId()).isPresent()) {
            return true;
        }
        return transaction.getIdempotencyKey() != null
                && transactionEntityRepository.findByIdempotencyKey(transaction.getIdempotencyKey()).isPresent();
    }

    private TransactionEntity saveRetryState(TransactionEntity transaction) {
        if (transaction.getId() != null) {
            var stored = transactionEntityRepository.findById(transaction.getId());
            if (stored.isPresent()) {
                TransactionEntity persisted = stored.get();
                persisted.setStatus(transaction.getStatus());
                persisted.setRetryCount(transaction.getRetryCount());
                persisted.setErrorMessage(transaction.getErrorMessage());
                return transactionEntityRepository.save(persisted);
            }
            transaction.setId(null);
        }
        return transactionEntityRepository.save(transaction);
    }

    private String message(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
