package com.assessment.service;

public interface IdempotencyService {

    boolean isDuplicate(String requestId);

    boolean claimTransaction(String requestId, String transactionId, Long sequenceNumber, String rawPayload);

    void recordDuplicateAttempt(String requestId, String transactionId, Long sequenceNumber, String rawPayload);

    void registerProcessedRequest(String externalReference, String requestHash, String transactionId, Long sequenceNumber, String rawPayload);

    boolean isOutOfOrder(String externalReference, Long sequenceNumber);
}
