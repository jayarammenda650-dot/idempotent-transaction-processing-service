package com.assessment.service;

import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;

import java.util.List;

public interface OrderingService {

    List<TransactionEntity> orderBatch(List<TransactionEntity> transactions);

    List<TransactionEntity> orderBatch(List<TransactionEntity> transactions, Long previousSequence);

    List<TransactionEntity> releasePendingTransactions(List<TransactionEntity> transactions);

    TransactionStatus resolveStatusForSequenceGap(Long previousSequence, Long currentSequence);
}
