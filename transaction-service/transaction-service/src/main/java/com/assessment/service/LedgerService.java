package com.assessment.service;

import com.assessment.entity.Account;
import com.assessment.entity.LedgerEntry;
import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionType;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerService {

    LedgerEntry createLedgerEntry(
            Account account,
            TransactionEntity transaction,
            TransactionType entryType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String description);

    List<LedgerEntry> findEntriesByAccount(Long accountId);

    List<LedgerEntry> findEntriesByTransaction(Long transactionId);
}
