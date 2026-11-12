package com.assessment.service;

import com.assessment.entity.TransactionEntity;

import java.util.List;

public interface TransactionValidator {

    ValidationResult validate(TransactionEntity transaction);

    List<TransactionEntity> filterInvalid(List<TransactionEntity> transactions);

    record ValidationResult(boolean valid, String field, String message) {
        public static ValidationResult validResult() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult invalidResult(String field, String message) {
            return new ValidationResult(false, field, message);
        }
    }
}
