package com.assessment.exception;

public class OutOfOrderTransactionException extends BusinessException {

    public OutOfOrderTransactionException(String message) {
        super(message);
    }
}
