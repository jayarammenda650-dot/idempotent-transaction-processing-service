package com.assessment.exception;

public class DuplicateTransactionException extends BusinessException {

    public DuplicateTransactionException(String message) {
        super(message);
    }
}
