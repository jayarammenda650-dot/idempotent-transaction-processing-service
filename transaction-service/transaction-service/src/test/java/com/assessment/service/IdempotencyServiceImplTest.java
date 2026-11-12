package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.ProcessedRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdempotencyServiceImplTest {

    @Autowired
    private IdempotencyService service;

    @Autowired
    private ProcessedRequestRepository processedRequestRepository;

    @Autowired
    private TransactionEntityRepository transactionEntityRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        processedRequestRepository.deleteAll();
        transactionEntityRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldDetectDuplicateRequestAttempt() {
        boolean firstClaim = service.claimTransaction("request-1", "txn-1", 10L, "{\"amount\":100}");

        assertThat(firstClaim).isTrue();
        assertThat(service.isDuplicate("request-1")).isTrue();

        boolean secondClaim = service.claimTransaction("request-1", "txn-2", 11L, "{\"amount\":100}");

        assertThat(secondClaim).isFalse();
        assertThat(processedRequestRepository.findByExternalReference("request-1")).isPresent();
    }

    @Test
    void shouldRejectDuplicateBusinessTransactionId() {
        Account account = accountRepository.save(account("ACC-1001", "Alice"));
        TransactionEntity transaction = transactionEntityRepository.save(
                TransactionEntity.builder()
                        .transactionId("txn-duplicate")
                        .account(account)
                        .transactionType(TransactionType.CREDIT)
                        .status(TransactionStatus.PROCESSED)
                        .amount(BigDecimal.valueOf(100.00))
                        .currency("USD")
                        .externalRequestId("request-initial")
                        .sequenceNumber(5L)
                        .build());

        boolean claim = service.claimTransaction("request-new", transaction.getTransactionId(), 6L, "{\"amount\":250}");

        assertThat(claim).isFalse();
        assertThat(transactionEntityRepository.count()).isEqualTo(1L);
        assertThat(transactionEntityRepository.findByTransactionId("txn-duplicate")).isPresent();
    }

    @Test
    void shouldDetectOutOfOrderSequenceNumbers() {
        processedRequestRepository.save(
                ProcessedRequest.builder()
                        .requestHash("hash-100")
                        .externalReference("request-100")
                        .transactionId("txn-100")
                        .status(TransactionStatus.PROCESSED)
                        .sequenceNumber(20L)
                        .rawPayload("payload")
                        .build());

        assertThat(service.isOutOfOrder("request-100", 15L)).isTrue();
        assertThat(service.isOutOfOrder("request-100", 25L)).isFalse();
    }

    @Test
    void shouldAllowOnlyOneConcurrentClaimForSameRequest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return service.claimTransaction("request-concurrent", "txn-concurrent", 99L, "{\"amount\":50}");
                }));
            }

            startLatch.countDown();

            long successCount = futures.stream().map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(Boolean.TRUE::equals)
                    .count();

            assertThat(successCount).isEqualTo(1L);
            assertThat(processedRequestRepository.findByTransactionId("txn-concurrent")).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    private Account account(String accountNumber, String accountHolderName) {
        return Account.builder()
                .accountNumber(accountNumber)
                .accountHolderName(accountHolderName)
                .balance(BigDecimal.ZERO)
                .currency("USD")
                .build();
    }
}
