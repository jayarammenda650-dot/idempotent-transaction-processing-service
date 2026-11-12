package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.enums.TransactionType;
import com.assessment.repository.AccountRepository;
import com.assessment.repository.LedgerEntryRepository;
import com.assessment.repository.ProcessedRequestRepository;
import com.assessment.repository.TransactionEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransactionProcessorTest {

    @Autowired
    private TransactionProcessor transactionProcessor;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionEntityRepository transactionEntityRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ProcessedRequestRepository processedRequestRepository;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        processedRequestRepository.deleteAll();
        transactionEntityRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldProcessValidTransactionAndCreateLedgerEntry() {
        Account account = accountRepository.save(account("ACC-100", "Alice", new BigDecimal("1000.00"), "USD"));
        TransactionEntity request = transaction("txn-1", "req-1", account, TransactionType.CREDIT, new BigDecimal("250.00"), 1L);

        TransactionEntity processed = transactionProcessor.process(request);

        assertThat(processed.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(processed.getErrorMessage()).isNull();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1250.00"));
        assertThat(ledgerEntryRepository.findByTransactionId(processed.getId())).hasSize(1);
        assertThat(processedRequestRepository.findByExternalReference("req-1")).isPresent();
    }

    @Test
    void shouldRejectDuplicateTransactionId() {
        Account account = accountRepository.save(account("ACC-200", "Bob", new BigDecimal("1000.00"), "USD"));
        TransactionEntity first = transaction("txn-dup", "req-1", account, TransactionType.CREDIT, new BigDecimal("250.00"), 1L);
        transactionProcessor.process(first);

        TransactionEntity duplicate = transaction("txn-dup", "req-2", account, TransactionType.CREDIT, new BigDecimal("400.00"), 2L);
        TransactionEntity result = transactionProcessor.process(duplicate);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(ledgerEntryRepository.findByAccountId(account.getId())).hasSize(1);
        assertThat(transactionEntityRepository.findByTransactionId("txn-dup")).isPresent();
    }

    @Test
    void shouldProcessBatchInSequenceOrder() {
        Account account = accountRepository.save(account("ACC-300", "Charlie", new BigDecimal("0.00"), "USD"));
        TransactionEntity tx3 = transaction("txn-3", "req-3", account, TransactionType.CREDIT, new BigDecimal("30.00"), 3L);
        TransactionEntity tx1 = transaction("txn-1", "req-1", account, TransactionType.CREDIT, new BigDecimal("10.00"), 1L);
        TransactionEntity tx2 = transaction("txn-2", "req-2", account, TransactionType.CREDIT, new BigDecimal("20.00"), 2L);

        List<TransactionEntity> processed = transactionProcessor.processBatch(List.of(tx3, tx1, tx2));

        assertThat(processed).extracting(TransactionEntity::getTransactionId)
                .containsExactly("txn-1", "txn-2", "txn-3");
        assertThat(processed).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo(TransactionStatus.PROCESSED));
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void shouldFailInvalidTransactionWithoutUpdatingBalance() {
        Account account = accountRepository.save(account("ACC-400", "Dana", new BigDecimal("1000.00"), "USD"));
        TransactionEntity invalid = transaction("txn-invalid", "req-invalid", account, TransactionType.CREDIT, new BigDecimal("-5.00"), 1L);

        TransactionEntity result = transactionProcessor.process(invalid);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(ledgerEntryRepository.findByAccountId(account.getId())).isEmpty();
    }

    private Account account(String accountNumber, String accountHolderName, BigDecimal balance, String currency) {
        return Account.builder()
                .accountNumber(accountNumber)
                .accountHolderName(accountHolderName)
                .balance(balance)
                .currency(currency)
                .build();
    }

    private TransactionEntity transaction(String transactionId, String requestId, Account account, TransactionType type,
                                        BigDecimal amount, Long sequenceNumber) {
        return TransactionEntity.builder()
                .transactionId(transactionId)
                .externalRequestId(requestId)
                .account(account)
                .transactionType(type)
                .amount(amount)
                .currency("USD")
                .sequenceNumber(sequenceNumber)
                .status(TransactionStatus.RECEIVED)
                .build();
    }
}
