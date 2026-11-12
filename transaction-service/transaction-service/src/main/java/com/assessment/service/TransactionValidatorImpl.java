package com.assessment.service;

import com.assessment.entity.TransactionEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class TransactionValidatorImpl implements TransactionValidator {

    private static final Set<String> VALID_CURRENCIES = Set.of("USD", "EUR", "GBP", "INR", "JPY");

    @Override
    public ValidationResult validate(TransactionEntity transaction) {
        if (transaction == null) {
            return ValidationResult.invalidResult("transaction", "Transaction is required.");
        }

        if (transaction.getTransactionId() == null || transaction.getTransactionId().isBlank()) {
            return ValidationResult.invalidResult("transactionId", "Transaction id is mandatory.");
        }

        if (transaction.getExternalRequestId() == null || transaction.getExternalRequestId().isBlank()) {
            return ValidationResult.invalidResult("requestId", "Request id is mandatory.");
        }

        if (transaction.getAccount() == null || transaction.getAccount().getId() == null) {
            return ValidationResult.invalidResult("accountId", "Account id is mandatory.");
        }

        if (transaction.getAmount() == null || transaction.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationResult.invalidResult("amount", "Amount must be positive.");
        }

        String currency = transaction.getCurrency();
        if (currency == null || currency.isBlank() || !VALID_CURRENCIES.contains(currency.trim().toUpperCase())) {
            return ValidationResult.invalidResult("currency", "Currency must be a supported ISO code.");
        }

        String accountCurrency = transaction.getAccount().getCurrency();
        if (accountCurrency == null || !currency.trim().equalsIgnoreCase(accountCurrency.trim())) {
            return ValidationResult.invalidResult("currency", "Transaction currency must match the account currency.");
        }

        if (transaction.getSequenceNumber() == null || transaction.getSequenceNumber() <= 0) {
            return ValidationResult.invalidResult("sequenceNumber", "Sequence number must be positive.");
        }

        return ValidationResult.validResult();
    }

    @Override
    public List<TransactionEntity> filterInvalid(List<TransactionEntity> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        List<TransactionEntity> validTransactions = new ArrayList<>();
        for (TransactionEntity transaction : transactions) {
            ValidationResult result = validate(transaction);
            if (result.valid()) {
                validTransactions.add(transaction);
            }
        }
        return validTransactions;
    }
}
