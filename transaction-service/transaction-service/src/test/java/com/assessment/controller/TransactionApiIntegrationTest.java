package com.assessment.controller;

import com.assessment.entity.Account;
import com.assessment.repository.AccountRepository;
import com.assessment.repository.LedgerEntryRepository;
import com.assessment.repository.ProcessedRequestRepository;
import com.assessment.repository.TransactionEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class TransactionApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionEntityRepository transactionEntityRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ProcessedRequestRepository processedRequestRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        ledgerEntryRepository.deleteAll();
        processedRequestRepository.deleteAll();
        transactionEntityRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(Account.builder()
                .accountNumber("API-ACCOUNT")
                .accountHolderName("API User")
                .balance(new BigDecimal("100.00"))
                .currency("USD")
                .build());
    }

    @Test
    void shouldProcessTransactionAndReturnConsistentTransactionResponse() throws Exception {
        mockMvc.perform(post("/api/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("REQ-API-1", 1L, "10.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("REQ-API-1"))
                .andExpect(jsonPath("$.requestId").value("REQ-API-1"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.balanceAfterProcessing").value(110.0));
    }

    @Test
    void shouldValidateRequestAndReturnStructuredBadRequest() throws Exception {
        mockMvc.perform(post("/api/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("REQ-INVALID", 1L, "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void shouldReturnConflictForDuplicateTransactionWithoutRepeatingBalanceUpdate() throws Exception {
        String body = requestJson("REQ-DUPLICATE", 1L, "10.00");
        mockMvc.perform(post("/api/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(accountRepository.findByAccountNumber("API-ACCOUNT").orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(ledgerEntryRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldValidateEachTransactionInBatch() throws Exception {
        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactions\":[" + requestJson("", 1L, "10.00") + "]}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.totalCount").value(1))
                                .andExpect(jsonPath("$.failedCount").value(1))
                                .andExpect(jsonPath("$.transactions[0].status").value("FAILED"));
    }

    @Test
    void shouldIsolateInvalidTransactionsAndProcessValidBatchItems() throws Exception {
        String valid = requestJson("REQ-BATCH-VALID", 1L, "10.00");
        String negativeAmount = requestJson("REQ-BATCH-NEGATIVE", 2L, "-5.00");
        String unsupportedCurrency = requestJson("REQ-BATCH-CURRENCY", 3L, "15.00")
                .replace("\"currency\":\"USD\"", "\"currency\":\"EUR\"");
        String missingAccount = requestJson("REQ-BATCH-ACCOUNT", 4L, "20.00")
                .replace("API-ACCOUNT", "MISSING-ACCOUNT");
        String batch = "{\"transactions\":[" + valid + "," + negativeAmount + ","
                + unsupportedCurrency + "," + missingAccount + "]}";

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(3))
                .andExpect(jsonPath("$.transactions[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$.transactions[1].status").value("FAILED"))
                .andExpect(jsonPath("$.transactions[2].status").value("FAILED"))
                .andExpect(jsonPath("$.transactions[3].status").value("FAILED"));

        assertThat(accountRepository.findByAccountNumber("API-ACCOUNT").orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(ledgerEntryRepository.count()).isEqualTo(1L);
    }

    @Test
    void shouldProcessBatchInSequenceOrder() throws Exception {
        String batch = "{\"transactions\":["
                + requestJson("REQ-BATCH-2", 2L, "20.00") + ","
                + requestJson("REQ-BATCH-1", 1L, "10.00") + "]}";

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions", hasSize(2)))
                .andExpect(jsonPath("$.transactions[0].transactionId").value("REQ-BATCH-1"))
                .andExpect(jsonPath("$.transactions[1].transactionId").value("REQ-BATCH-2"))
                .andExpect(jsonPath("$.processedCount").value(2));
    }

    @Test
    void shouldGetTransactionAndProcessingSummary() throws Exception {
        mockMvc.perform(post("/api/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("REQ-GET", 1L, "10.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transactions/REQ-GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("REQ-GET"))
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        mockMvc.perform(get("/api/transactions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.processedRequests").value(1));
    }

    @Test
    void shouldReturnNotFoundForUnknownTransaction() throws Exception {
        mockMvc.perform(get("/api/transactions/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

        private String requestJson(String requestId, Long sequence, String amount) {
                return "{\"externalRequestId\":\"" + requestId + "\","
                                + "\"accountNumber\":\"API-ACCOUNT\",\"currency\":\"USD\","
                                + "\"transactionType\":\"CREDIT\",\"amount\":" + amount + ","
                                + "\"sequenceNumber\":" + sequence + "}";
        }
}
