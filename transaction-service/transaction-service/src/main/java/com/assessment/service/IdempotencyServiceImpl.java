package com.assessment.service;

import com.assessment.entity.ProcessedRequest;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.repository.ProcessedRequestRepository;
import com.assessment.repository.TransactionEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private final ProcessedRequestRepository processedRequestRepository;
    private final TransactionEntityRepository transactionEntityRepository;
    private final KeyedLockRegistry lockRegistry;

    public IdempotencyServiceImpl(
            ProcessedRequestRepository processedRequestRepository,
            TransactionEntityRepository transactionEntityRepository,
            KeyedLockRegistry lockRegistry) {
        this.processedRequestRepository = processedRequestRepository;
        this.transactionEntityRepository = transactionEntityRepository;
        this.lockRegistry = lockRegistry;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDuplicate(String requestId) {
        String normalizedRequestId = normalizeRequestId(requestId);
        String requestHash = hashRequest(normalizedRequestId);
        return processedRequestRepository.findByRequestHash(requestHash).isPresent();
    }

    @Override
    @Transactional
    public boolean claimTransaction(String requestId, String transactionId, Long sequenceNumber, String rawPayload) {
        String normalizedRequestId = normalizeRequestId(requestId);
        String normalizedTransactionId = normalizeTransactionId(transactionId);
        String requestHash = hashRequest(normalizedRequestId);

        List<String> lockKeys = new ArrayList<>();
        lockKeys.add("request:" + requestHash);
        lockKeys.add("transaction:" + normalizedTransactionId);
        lockKeys.sort(Comparator.naturalOrder());

        return lockRegistry.withLocks(lockKeys, () -> {
            Optional<ProcessedRequest> existingRequest = processedRequestRepository.findByRequestHashForUpdate(requestHash);
            if (existingRequest.isPresent()) {
                incrementDuplicateAttempts(existingRequest.get());
                return false;
            }

            Optional<TransactionEntity> existingTransaction = transactionEntityRepository.findByTransactionIdForUpdate(normalizedTransactionId);
            if (existingTransaction.isPresent()) {
                TransactionEntity stored = existingTransaction.get();
                boolean sameUnclaimedRequest = normalizedRequestId.equals(stored.getExternalRequestId());
                if (!sameUnclaimedRequest) {
                    saveDuplicateRequest(normalizedRequestId, requestHash, normalizedTransactionId, sequenceNumber, rawPayload);
                    return false;
                }
            }

            ProcessedRequest processedRequest = ProcessedRequest.builder()
                    .requestHash(requestHash)
                    .externalReference(normalizedRequestId)
                    .transactionId(normalizedTransactionId)
                    .status(TransactionStatus.PROCESSED)
                    .sequenceNumber(sequenceNumber)
                    .retryCount(0)
                    .rawPayload(rawPayload)
                    .build();

            persistClaimedRequest(processedRequest);
            return true;
        });
    }

    @Override
    @Transactional
    public void recordDuplicateAttempt(String requestId, String transactionId, Long sequenceNumber, String rawPayload) {
        String normalizedRequestId = normalizeRequestId(requestId);
        String normalizedTransactionId = normalizeTransactionId(transactionId);
        String requestHash = hashRequest(normalizedRequestId);
        List<String> lockKeys = List.of("request:" + requestHash, "transaction:" + normalizedTransactionId);

        lockRegistry.withLocks(lockKeys, () -> {
            Optional<ProcessedRequest> existingRequest = processedRequestRepository.findByRequestHashForUpdate(requestHash);
            if (existingRequest.isPresent()) {
                incrementDuplicateAttempts(existingRequest.get());
            } else {
                saveDuplicateRequest(normalizedRequestId, requestHash, normalizedTransactionId, sequenceNumber, rawPayload);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public void registerProcessedRequest(String externalReference, String requestHash, String transactionId, Long sequenceNumber, String rawPayload) {
        String normalizedExternalReference = normalizeRequestId(externalReference);
        String normalizedHash = requestHash == null || requestHash.isBlank() ? hashRequest(normalizedExternalReference) : requestHash;

        String normalizedTransactionId = normalizeTransactionId(transactionId);
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add("request:" + normalizedHash);
        lockKeys.add("transaction:" + normalizedTransactionId);
        lockKeys.sort(Comparator.naturalOrder());

        lockRegistry.withLocks(lockKeys, () -> {
            Optional<ProcessedRequest> existingRequest = processedRequestRepository.findByRequestHashForUpdate(normalizedHash);
            if (existingRequest.isPresent()) {
                return null;
            }

            ProcessedRequest request = ProcessedRequest.builder()
                    .requestHash(normalizedHash)
                    .externalReference(normalizedExternalReference)
                    .transactionId(normalizedTransactionId)
                    .status(TransactionStatus.PROCESSED)
                    .sequenceNumber(sequenceNumber)
                    .retryCount(0)
                    .rawPayload(rawPayload)
                    .build();

            persistClaimedRequest(request);
            return null;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOutOfOrder(String externalReference, Long sequenceNumber) {
        if (externalReference == null || externalReference.isBlank() || sequenceNumber == null) {
            return false;
        }

        Optional<ProcessedRequest> latest = processedRequestRepository.findTopByExternalReferenceOrderBySequenceNumberDesc(externalReference);
        if (latest.isEmpty()) {
            return false;
        }

        Long storedSequence = latest.get().getSequenceNumber();
        return storedSequence != null && sequenceNumber < storedSequence;
    }

    protected void persistClaimedRequest(ProcessedRequest request) {
        processedRequestRepository.saveAndFlush(request);
    }

    protected void saveDuplicateRequest(String externalReference, String requestHash, String transactionId, Long sequenceNumber, String rawPayload) {
        ProcessedRequest duplicate = ProcessedRequest.builder()
                .requestHash(requestHash)
                .externalReference(externalReference)
                .transactionId(transactionId)
                .status(TransactionStatus.DUPLICATE)
                .sequenceNumber(sequenceNumber)
                .retryCount(0)
                .duplicateAttempts(0)
                .rawPayload(rawPayload)
                .build();

        processedRequestRepository.saveAndFlush(duplicate);
    }

    private void incrementDuplicateAttempts(ProcessedRequest request) {
        int duplicateAttempts = request.getDuplicateAttempts() == null ? 0 : request.getDuplicateAttempts();
        request.setDuplicateAttempts(duplicateAttempts + 1);
        processedRequestRepository.save(request);
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return requestId.trim();
    }

    private String normalizeTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        return transactionId.trim();
    }

    private String hashRequest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available in the runtime environment", exception);
        }
    }
}
