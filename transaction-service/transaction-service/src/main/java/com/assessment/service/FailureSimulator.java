package com.assessment.service;

import com.assessment.entity.TransactionEntity;
import com.assessment.exception.TransientProcessingException;
import org.springframework.stereotype.Component;

@Component
public class FailureSimulator {

    public void beforeAttempt(TransactionEntity transaction) {
        if (transaction == null) {
            return;
        }

        boolean retryAlwaysFails = "TXN-RETRY-EXHAUSTED".equals(transaction.getTransactionId());
        boolean retryOnceFails = "TXN-2005".equals(transaction.getTransactionId())
                && (transaction.getRetryCount() == null || transaction.getRetryCount() == 0);
        if (retryAlwaysFails || retryOnceFails) {
            throw new TransientProcessingException("Simulated transient processing failure.");
        }
    }
}
