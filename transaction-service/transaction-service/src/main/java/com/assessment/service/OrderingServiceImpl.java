package com.assessment.service;

import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class OrderingServiceImpl implements OrderingService {

    private final TransactionValidator validator;

    public OrderingServiceImpl(TransactionValidator validator) {
        this.validator = validator;
    }

    @Override
    public List<TransactionEntity> orderBatch(List<TransactionEntity> transactions) {
        return orderBatch(transactions, null);
    }

    @Override
    public List<TransactionEntity> orderBatch(List<TransactionEntity> transactions, Long previousSequence) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        List<TransactionEntity> validTransactions = validator.filterInvalid(transactions);
        validTransactions.sort(Comparator.comparing(TransactionEntity::getSequenceNumber));

        Long lastSeenSequence = previousSequence;
        Long firstMissingSequence = null;

        for (TransactionEntity transaction : validTransactions) {
            Long currentSequence = transaction.getSequenceNumber();

            if (lastSeenSequence == null) {
                transaction.setStatus(TransactionStatus.PROCESSED);
                lastSeenSequence = currentSequence;
                continue;
            }

            if (currentSequence <= lastSeenSequence) {
                transaction.setStatus(TransactionStatus.REJECTED);
                continue;
            }

            if (firstMissingSequence == null) {
                if (currentSequence.equals(lastSeenSequence + 1L)) {
                    transaction.setStatus(TransactionStatus.PROCESSED);
                    lastSeenSequence = currentSequence;
                    continue;
                }

                transaction.setStatus(TransactionStatus.PENDING);
                firstMissingSequence = lastSeenSequence + 1L;
                lastSeenSequence = currentSequence;
                continue;
            }

            if (currentSequence.equals(firstMissingSequence)) {
                transaction.setStatus(TransactionStatus.PROCESSED);
                firstMissingSequence = null;
                lastSeenSequence = currentSequence;
                continue;
            }

            transaction.setStatus(TransactionStatus.PENDING);
            lastSeenSequence = currentSequence;
        }

        return validTransactions;
    }

    @Override
    public List<TransactionEntity> releasePendingTransactions(List<TransactionEntity> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        List<TransactionEntity> workingSet = new ArrayList<>(transactions);
        return orderBatch(workingSet);
    }

    @Override
    public TransactionStatus resolveStatusForSequenceGap(Long previousSequence, Long currentSequence) {
        if (previousSequence == null || currentSequence == null) {
            return TransactionStatus.REJECTED;
        }

        if (currentSequence <= previousSequence) {
            return TransactionStatus.REJECTED;
        }

        if (currentSequence > previousSequence + 1L) {
            return TransactionStatus.PENDING;
        }

        return TransactionStatus.PROCESSED;
    }
}
