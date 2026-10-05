package com.example.deposits.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.deposits.domain.Deposit;

public record DepositResponse(Long id, String status, BigDecimal amount, String currency, Instant createdAt) {

    static DepositResponse from(Deposit deposit) {
        return new DepositResponse(deposit.getId(), deposit.getStatus().name(),
                deposit.getAmount(), deposit.getCurrency(), deposit.getCreatedAt());
    }
}
