package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderingServiceImplTest {

    @Autowired
    private OrderingService orderingService;

    @Test
    void shouldSortBatchAndHoldFutureTransactionsWhenSequenceGapExists() {
        TransactionEntity tx1 = transaction("txn-1", "req-1", 10L, 1L);
        TransactionEntity tx3 = transaction("txn-3", "req-3", 10L, 3L);
        TransactionEntity tx4 = transaction("txn-4", "req-4", 10L, 4L);

        List<TransactionEntity> ordered = orderingService.orderBatch(List.of(tx3, tx1, tx4));

        assertThat(ordered.stream().map(TransactionEntity::getSequenceNumber).toList())
                .containsExactly(1L, 3L, 4L);
        assertThat(tx1.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(tx3.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx4.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void shouldReleasePendingTransactionsWhenGapIsResolved() {
        TransactionEntity tx1 = transaction("txn-1", "req-1", 10L, 1L);
        TransactionEntity tx3 = transaction("txn-3", "req-3", 10L, 3L);
        TransactionEntity tx4 = transaction("txn-4", "req-4", 10L, 4L);

        orderingService.orderBatch(List.of(tx1, tx3, tx4));

        TransactionEntity tx2 = transaction("txn-2", "req-2", 10L, 2L);
        List<TransactionEntity> released = orderingService.releasePendingTransactions(List.of(tx1, tx2, tx3, tx4));

        assertThat(released).hasSize(4);
        assertThat(tx1.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(tx2.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(tx3.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(tx4.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
    }

    @Test
    void shouldIgnoreInvalidRecordsWithoutBlockingTheBatch() {
        TransactionEntity invalid = transaction("", "req-invalid", 10L, 100L);
        TransactionEntity valid1 = transaction("txn-10", "req-10", 10L, 1L);
        TransactionEntity valid2 = transaction("txn-11", "req-11", 10L, 2L);

        List<TransactionEntity> ordered = orderingService.orderBatch(List.of(invalid, valid2, valid1));

        assertThat(ordered).hasSize(2);
        assertThat(ordered.stream().map(TransactionEntity::getTransactionId)).containsExactly("txn-10", "txn-11");
        assertThat(valid1.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(valid2.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
    }

    private TransactionEntity transaction(String transactionId, String requestId, Long accountId, Long sequenceNumber) {
        Account account = Account.builder()
                .id(accountId)
                .accountNumber("ACC-" + accountId)
                .accountHolderName("Holder")
                .balance(BigDecimal.ZERO)
                .currency("USD")
                .build();

        return TransactionEntity.builder()
                .transactionId(transactionId)
                .externalRequestId(requestId)
                .account(account)
                .transactionType(TransactionType.CREDIT)
                .amount(BigDecimal.valueOf(100.00))
                .currency("USD")
                .sequenceNumber(sequenceNumber)
                .status(TransactionStatus.PENDING)
                .build();
    }
}
