package com.assessment.enums;

public enum TransactionStatus {
    RECEIVED,
    PENDING,
    RETRY_PENDING,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    RETRY_SCHEDULED,
    OUT_OF_ORDER,
    REJECTED,
    FAILED
}
