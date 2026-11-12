package com.assessment.service;

import com.assessment.entity.Account;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountService {

    Account save(Account account);

    Optional<Account> findByAccountNumber(String accountNumber);

    BigDecimal getBalance(String accountNumber);

    Account reserveOrCreate(String accountNumber, String accountHolderName, String currency);
}
