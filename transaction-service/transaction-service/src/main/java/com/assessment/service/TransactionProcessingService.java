package com.assessment.service;

import com.assessment.dto.ProcessingSummary;
import com.assessment.dto.TransactionBatchRequest;
import com.assessment.dto.TransactionBatchResponse;
import com.assessment.dto.TransactionRequest;
import com.assessment.dto.TransactionResponse;

public interface TransactionProcessingService {

    TransactionResponse processTransaction(TransactionRequest request);

    TransactionBatchResponse processBatch(TransactionBatchRequest request);

    TransactionResponse getTransaction(String transactionId);

    ProcessingSummary getProcessingSummary();
}
