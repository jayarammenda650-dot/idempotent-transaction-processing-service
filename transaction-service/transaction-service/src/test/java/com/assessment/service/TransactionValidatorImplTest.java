package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransactionValidatorImplTest {

    @Autowired
    private TransactionValidator validator;

    @Test
    void shouldPassForValidTransaction() {
        TransactionEntity transaction = transaction("txn-100", "req-100", 1L, "USD", 100.00, 10L);

        TransactionValidator.ValidationResult result = validator.validate(transaction);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    void shouldRejectMissingRequiredField() {
        TransactionEntity transaction = transaction("", "req-200", 1L, "USD", 100.00, 10L);

        TransactionValidator.ValidationResult result = validator.validate(transaction);

        assertThat(result.valid()).isFalse();
        assertThat(result.field()).isEqualTo("transactionId");
    }

    @Test
    void shouldRejectNonPositiveAmountAndInvalidCurrency() {
        TransactionEntity invalidAmount = transaction("txn-300", "req-300", 1L, "USD", 0.00, 10L);
        TransactionEntity invalidCurrency = transaction("txn-301", "req-301", 1L, "ZZZ", 10.00, 11L);

        assertThat(validator.validate(invalidAmount).valid()).isFalse();
        assertThat(validator.validate(invalidCurrency).valid()).isFalse();
    }

    private TransactionEntity transaction(String transactionId, String requestId, Long accountId, String currency, double amount, Long sequenceNumber) {
        Account account = Account.builder()
                .id(accountId)
                .accountNumber("ACC-" + accountId)
                .accountHolderName("Account Holder")
                .balance(BigDecimal.ZERO)
                .currency(currency)
                .build();

        return TransactionEntity.builder()
                .transactionId(transactionId)
                .externalRequestId(requestId)
                .account(account)
                .transactionType(TransactionType.CREDIT)
                .amount(BigDecimal.valueOf(amount))
                .currency(currency)
                .sequenceNumber(sequenceNumber)
                .build();
    }
}
