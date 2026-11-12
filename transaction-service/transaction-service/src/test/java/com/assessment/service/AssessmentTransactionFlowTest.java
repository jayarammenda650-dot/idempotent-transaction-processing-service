package com.assessment.service;

import com.assessment.dto.ProcessingSummary;
import com.assessment.dto.TransactionBatchRequest;
import com.assessment.dto.TransactionRequest;
import com.assessment.dto.TransactionResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AssessmentTransactionFlowTest {

    private static final String ACCOUNT_NUMBER = "ASSESSMENT-ACCOUNT";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");

    @Autowired
    private TransactionProcessingService transactionProcessingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionEntityRepository transactionEntityRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ProcessedRequestRepository processedRequestRepository;

    private Account account;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        processedRequestRepository.deleteAll();
        transactionEntityRepository.deleteAll();
        accountRepository.deleteAll();
        account = accountRepository.save(Account.builder()
                .accountNumber(ACCOUNT_NUMBER)
                .accountHolderName("Assessment User")
                .balance(INITIAL_BALANCE)
                .currency("USD")
                .build());
    }

    @Test
    void creditUpdatesBalanceAndCreatesOneLedgerEntry() {
        TransactionResponse response = transactionProcessingService.processTransaction(
                request("CREDIT-1", "CREDIT-1", TransactionType.CREDIT, "25.00", 1L));

        assertOutcome(response, TransactionStatus.PROCESSED, "125.00", 1);
    }

    @Test
    void debitUpdatesBalanceAndCreatesOneLedgerEntry() {
        TransactionResponse response = transactionProcessingService.processTransaction(
                request("DEBIT-1", "DEBIT-1", TransactionType.DEBIT, "30.00", 1L));

        assertOutcome(response, TransactionStatus.PROCESSED, "70.00", 1);
    }

    @Test
    void duplicateTransactionIdHasNoDuplicateEffect() {
        TransactionResponse first = transactionProcessingService.processTransaction(
                request("TXN-DUPLICATE", "REQ-DUPLICATE-1", TransactionType.CREDIT, "25.00", 1L));
        TransactionResponse duplicate = transactionProcessingService.processTransaction(
                request("TXN-DUPLICATE", "REQ-DUPLICATE-2", TransactionType.CREDIT, "80.00", 2L));

        assertThat(first.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertOutcome(duplicate, TransactionStatus.DUPLICATE, "125.00", 1);
    }

        @Test
        void processingSummaryCountsDuplicateAttempts() {
                transactionProcessingService.processTransaction(
                                request("TXN-SUMMARY-DUPLICATE", "REQ-SUMMARY-FIRST", TransactionType.CREDIT, "25.00", 1L));
                TransactionResponse duplicate = transactionProcessingService.processTransaction(
                                request("TXN-SUMMARY-DUPLICATE", "REQ-SUMMARY-DUPLICATE", TransactionType.CREDIT, "80.00", 2L));

                assertOutcome(duplicate, TransactionStatus.DUPLICATE, "125.00", 1);
                ProcessingSummary summary = transactionProcessingService.getProcessingSummary();
                assertThat(summary.getTotalRequests()).isEqualTo(2);
                assertThat(summary.getProcessedRequests()).isEqualTo(1);
                assertThat(summary.getDuplicateRequests()).isEqualTo(1);
        }

    @Test
    void duplicateRequestIdIsRejectedWithoutApplyingSecondAmount() {
        TransactionResponse first = transactionProcessingService.processTransaction(
                request("TXN-REQUEST-1", "REQ-SAME", TransactionType.CREDIT, "25.00", 1L));
        TransactionResponse duplicate = transactionProcessingService.processTransaction(
                request("TXN-REQUEST-2", "REQ-SAME", TransactionType.CREDIT, "80.00", 2L));

        assertThat(first.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertOutcome(duplicate, TransactionStatus.DUPLICATE, "125.00", 1);
        assertThat(transactionProcessingService.getProcessingSummary().getDuplicateRequests()).isEqualTo(1);
    }

        @Test
        void duplicateIdempotencyKeyIsRejectedWithoutApplyingSecondAmount() {
                TransactionRequest firstRequest = request(
                                "TXN-KEY-1", "REQ-KEY-1", TransactionType.CREDIT, "25.00", 1L);
                firstRequest.setIdempotencyKey("IDEMPOTENCY-KEY");
                TransactionRequest duplicateRequest = request(
                                "TXN-KEY-2", "REQ-KEY-2", TransactionType.CREDIT, "80.00", 2L);
                duplicateRequest.setIdempotencyKey("IDEMPOTENCY-KEY");

                TransactionResponse first = transactionProcessingService.processTransaction(firstRequest);
                TransactionResponse duplicate = transactionProcessingService.processTransaction(duplicateRequest);

                assertThat(first.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
                assertOutcome(duplicate, TransactionStatus.DUPLICATE, "125.00", 1);
        }

    @Test
    void negativeAmountFailsValidationWithoutLedgerOrBalanceChange() {
        TransactionResponse response = transactionProcessingService.processTransaction(
                request("TXN-NEGATIVE", "REQ-NEGATIVE", TransactionType.CREDIT, "-5.00", 1L));

        assertOutcome(response, TransactionStatus.FAILED, "100.00", 0);
    }

        @Test
        void accountCurrencyMismatchFailsWithoutChangingBalanceOrLedger() {
                TransactionRequest request = request(
                                "TXN-CURRENCY-MISMATCH", "REQ-CURRENCY-MISMATCH", TransactionType.CREDIT, "25.00", 1L);
                request.setCurrency("EUR");

                TransactionResponse response = transactionProcessingService.processTransaction(request);

                assertOutcome(response, TransactionStatus.FAILED, "100.00", 0);
        }

    @Test
    void outOfOrderBatchIsProcessedBySequenceNumber() {
        TransactionRequest sequenceTwo = request("TXN-ORDER-2", "REQ-ORDER-2", TransactionType.CREDIT, "20.00", 2L);
        TransactionRequest sequenceOne = request("TXN-ORDER-1", "REQ-ORDER-1", TransactionType.CREDIT, "10.00", 1L);
        var response = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(sequenceTwo, sequenceOne))
                .build());

        assertThat(response.getTransactions()).extracting(TransactionResponse::getTransactionId)
                .containsExactly("TXN-ORDER-1", "TXN-ORDER-2");
        assertThat(response.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsExactly(TransactionStatus.PROCESSED, TransactionStatus.PROCESSED);
        assertOutcome(response.getTransactions().get(1), TransactionStatus.PROCESSED, "130.00", 2);
    }

    @Test
    void missingSequenceKeepsFutureTransactionPending() {
        var response = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-GAP-3", "REQ-GAP-3", TransactionType.CREDIT, "30.00", 3L),
                        request("TXN-GAP-1", "REQ-GAP-1", TransactionType.CREDIT, "10.00", 1L)))
                .build());

        assertThat(response.getTransactions()).extracting(TransactionResponse::getTransactionId)
                .containsExactly("TXN-GAP-1", "TXN-GAP-3");
        assertThat(response.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsExactly(TransactionStatus.PROCESSED, TransactionStatus.PENDING);
        assertOutcome(response.getTransactions().get(1), TransactionStatus.PENDING, "110.00", 1);
        assertThat(transactionEntityRepository.findByTransactionId("TXN-GAP-3")).get()
                .extracting(TransactionEntity::getStatus).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void duplicatePendingTransactionInLaterBatchIsReportedWithoutEffects() {
        transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-PENDING-DUP-1", "REQ-PENDING-DUP-1", TransactionType.CREDIT, "10.00", 1L),
                        request("TXN-PENDING-DUP-3", "REQ-PENDING-DUP-3", TransactionType.CREDIT, "30.00", 3L)))
                .build());

        var duplicate = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(request(
                        "TXN-PENDING-DUP-3", "REQ-PENDING-DUP-3-REPLAY", TransactionType.CREDIT, "30.00", 3L)))
                .build());

        assertThat(duplicate.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsExactly(TransactionStatus.DUPLICATE);
        assertOutcome(duplicate.getTransactions().get(0), TransactionStatus.DUPLICATE, "110.00", 1);
    }

    @Test
    void gapFillingBatchCanIncludeAndReleaseAnExistingPendingTransaction() {
        transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-INCLUDE-PENDING-1", "REQ-INCLUDE-PENDING-1", TransactionType.CREDIT, "10.00", 1L),
                        request("TXN-INCLUDE-PENDING-3", "REQ-INCLUDE-PENDING-3", TransactionType.CREDIT, "30.00", 3L)))
                .build());

        var result = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-INCLUDE-PENDING-2", "REQ-INCLUDE-PENDING-2", TransactionType.CREDIT, "20.00", 2L),
                        request("TXN-INCLUDE-PENDING-3", "REQ-INCLUDE-PENDING-3", TransactionType.CREDIT, "30.00", 3L)))
                .build());

        assertThat(result.getTransactions()).extracting(TransactionResponse::getTransactionId)
                .containsExactly("TXN-INCLUDE-PENDING-2", "TXN-INCLUDE-PENDING-3");
        assertThat(result.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsOnly(TransactionStatus.PROCESSED);
        assertOutcome(result.getTransactions().get(1), TransactionStatus.PROCESSED, "160.00", 3);
    }

    @Test
    void laterBatchReleasesPreviouslyPendingTransaction() {
        var firstBatch = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-RELEASE-1", "REQ-RELEASE-1", TransactionType.CREDIT, "10.00", 1L),
                        request("TXN-RELEASE-3", "REQ-RELEASE-3", TransactionType.CREDIT, "30.00", 3L)))
                .build());
        var stillBlocked = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(request(
                        "TXN-RELEASE-4", "REQ-RELEASE-4", TransactionType.CREDIT, "40.00", 4L)))
                .build());

        assertThat(firstBatch.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsExactly(TransactionStatus.PROCESSED, TransactionStatus.PENDING);
        assertThat(stillBlocked.getTransactions()).extracting(TransactionResponse::getTransactionId)
                .containsExactly("TXN-RELEASE-3", "TXN-RELEASE-4");
        assertThat(stillBlocked.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsOnly(TransactionStatus.PENDING);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(ledgerEntryRepository.findByAccountId(account.getId())).hasSize(1);

        TransactionRequest missingSequence = request(
                "TXN-RELEASE-2", "REQ-RELEASE-2", TransactionType.CREDIT, "20.00", 2L);

        var released = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(missingSequence))
                .build());

        assertThat(released.getTransactions()).extracting(TransactionResponse::getTransactionId)
                .containsExactly("TXN-RELEASE-2", "TXN-RELEASE-3", "TXN-RELEASE-4");
        assertThat(released.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsOnly(TransactionStatus.PROCESSED);
        assertOutcome(released.getTransactions().get(2), TransactionStatus.PROCESSED, "200.00", 4);
    }

    @Test
    void retrySucceedsAndAppliesBalanceOnlyOnce() {
        TransactionResponse response = transactionProcessingService.processTransaction(
                request("TXN-2005", "REQ-2005", TransactionType.CREDIT, "25.00", 1L));

        assertOutcome(response, TransactionStatus.PROCESSED, "125.00", 1);
        assertThat(transactionEntityRepository.findByTransactionId("TXN-2005")).get()
                .extracting(TransactionEntity::getRetryCount).isEqualTo(1);
    }

    @Test
    void retryExhaustionPersistsFailedStatusWithoutSideEffects() {
        TransactionResponse response = transactionProcessingService.processTransaction(
                request("TXN-RETRY-EXHAUSTED", "REQ-RETRY-EXHAUSTED", TransactionType.CREDIT, "25.00", 1L));

        assertOutcome(response, TransactionStatus.FAILED, "100.00", 0);
        assertThat(transactionEntityRepository.findByTransactionId("TXN-RETRY-EXHAUSTED")).get()
                .extracting(TransactionEntity::getRetryCount).isEqualTo(3);
    }

    @Test
    void concurrentDuplicateRequestsHaveOnlyOneEffect() throws Exception {
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<TransactionResponse>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return transactionProcessingService.processTransaction(
                            request("TXN-CONCURRENT", "REQ-CONCURRENT", TransactionType.CREDIT, "25.00", 1L));
                }));
            }
            start.countDown();
            List<TransactionResponse> responses = new ArrayList<>();
            for (Future<TransactionResponse> future : futures) {
                responses.add(future.get());
            }

            assertThat(responses).extracting(TransactionResponse::getStatus)
                    .filteredOn(status -> status == TransactionStatus.PROCESSED).hasSize(1);
            assertThat(responses).extracting(TransactionResponse::getStatus)
                    .filteredOn(status -> status == TransactionStatus.DUPLICATE).hasSize(callers - 1);
            assertOutcome(responses.stream()
                    .filter(response -> response.getStatus() == TransactionStatus.PROCESSED)
                    .findFirst().orElseThrow(), TransactionStatus.PROCESSED, "125.00", 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void processingSummaryReflectsProcessedPendingFailedAndRetryCounts() {
        TransactionResponse retried = transactionProcessingService.processTransaction(
                request("TXN-2005", "REQ-SUMMARY-RETRY", TransactionType.CREDIT, "25.00", 1L));
        var gapBatch = transactionProcessingService.processBatch(TransactionBatchRequest.builder()
                .transactions(List.of(
                        request("TXN-SUMMARY-GAP-2", "REQ-SUMMARY-GAP-2", TransactionType.CREDIT, "10.00", 2L),
                        request("TXN-SUMMARY-GAP-4", "REQ-SUMMARY-GAP-4", TransactionType.CREDIT, "30.00", 4L)))
                .build());
        TransactionResponse failed = transactionProcessingService.processTransaction(
                request("TXN-SUMMARY-FAILED", "REQ-SUMMARY-FAILED", TransactionType.CREDIT, "-5.00", 4L));

        assertThat(retried.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(gapBatch.getTransactions()).extracting(TransactionResponse::getStatus)
                .containsExactly(TransactionStatus.PROCESSED, TransactionStatus.PENDING);
        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertOutcome(failed, TransactionStatus.FAILED, "135.00", 2);

        ProcessingSummary summary = transactionProcessingService.getProcessingSummary();
        assertThat(summary.getTotalRequests()).isEqualTo(4);
        assertThat(summary.getProcessedRequests()).isEqualTo(2);
        assertThat(summary.getPendingRequests()).isEqualTo(1);
        assertThat(summary.getFailedRequests()).isEqualTo(1);
        assertThat(summary.getOutOfOrderRequests()).isZero();
        assertThat(summary.getRetryAttempts()).isEqualTo(1);
    }

    private TransactionRequest request(
            String transactionId,
            String requestId,
            TransactionType type,
            String amount,
            Long sequence) {
        return TransactionRequest.builder()
                .transactionId(transactionId)
                .externalRequestId(requestId)
                .accountNumber(ACCOUNT_NUMBER)
                .currency("USD")
                .transactionType(type)
                .amount(new BigDecimal(amount))
                .sequenceNumber(sequence)
                .build();
    }

    private void assertOutcome(
            TransactionResponse response,
            TransactionStatus expectedStatus,
            String expectedBalance,
            long expectedLedgerCount) {
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal(expectedBalance));
        assertThat(ledgerEntryRepository.findByAccountId(account.getId())).hasSize((int) expectedLedgerCount);
    }
}
