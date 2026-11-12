package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import com.assessment.enums.TransactionType;
import com.assessment.exception.TransientProcessingException;
import com.assessment.repository.AccountRepository;
import com.assessment.repository.LedgerEntryRepository;
import com.assessment.repository.ProcessedRequestRepository;
import com.assessment.repository.TransactionEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class RetryServiceTest {

    @Autowired
    private RetryService retryService;

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
    void shouldRetryTxn2005AndApplyBalanceOnlyOnce() {
        Account account = accountRepository.save(Account.builder()
                .accountNumber("ACC-2005")
                .accountHolderName("Test Holder")
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build());
        TransactionEntity transaction = TransactionEntity.builder()
                .transactionId("TXN-2005")
                .externalRequestId("REQ-2005")
                .account(account)
                .transactionType(TransactionType.CREDIT)
                .amount(new BigDecimal("25.00"))
                .currency("USD")
                .sequenceNumber(1L)
                .status(TransactionStatus.RECEIVED)
                .retryCount(0)
                .build();

        TransactionEntity result = retryService.process(transaction);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(ledgerEntryRepository.findByTransactionId(result.getId())).hasSize(1);
        assertThat(transactionEntityRepository.findByTransactionId("TXN-2005")).get()
                .extracting(TransactionEntity::getStatus).isEqualTo(TransactionStatus.PROCESSED);
    }

    @Test
    void shouldPersistFailureWhenTransactionIsNotRetryable() {
        Account account = accountRepository.save(Account.builder()
                .accountNumber("ACC-FAIL")
                .accountHolderName("Test Holder")
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build());
        TransactionEntity transaction = TransactionEntity.builder()
                .transactionId("TXN-INVALID")
                .externalRequestId("REQ-INVALID")
                .account(account)
                .transactionType(TransactionType.CREDIT)
                .amount(new BigDecimal("-25.00"))
                .currency("USD")
                .sequenceNumber(1L)
                .status(TransactionStatus.RECEIVED)
                .retryCount(0)
                .build();

        TransactionEntity result = retryService.process(transaction);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.getRetryCount()).isZero();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ledgerEntryRepository.findByAccountId(account.getId())).isEmpty();
    }

        @Test
        void shouldMarkTransactionFailedWhenMaximumRetriesAreExhausted() {
                TransactionProcessor processor = mock(TransactionProcessor.class);
                TransactionEntityRepository repository = mock(TransactionEntityRepository.class);
                FailureSimulator simulator = mock(FailureSimulator.class);
                doThrow(new TransientProcessingException("temporary outage"))
                                .when(simulator).beforeAttempt(any(TransactionEntity.class));
                when(repository.save(any(TransactionEntity.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                RetryService service = new RetryService(processor, repository, simulator, 2);
                TransactionEntity transaction = TransactionEntity.builder()
                                .transactionId("TXN-RETRY-EXHAUSTED")
                                .externalRequestId("REQ-RETRY-EXHAUSTED")
                                .retryCount(0)
                                .status(TransactionStatus.RECEIVED)
                                .build();

                TransactionEntity result = service.process(transaction);

                assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
                assertThat(result.getRetryCount()).isEqualTo(2);
                assertThat(result.getErrorMessage()).isEqualTo("temporary outage");
                verify(processor, never()).process(any(TransactionEntity.class));
                verify(repository, times(3)).save(transaction);
        }
}
