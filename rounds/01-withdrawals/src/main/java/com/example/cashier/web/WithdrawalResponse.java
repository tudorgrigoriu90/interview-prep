package com.example.cashier.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.cashier.service.WithdrawalResult;

public record WithdrawalResponse(
        Long id,
        String status,
        BigDecimal amount,
        String currency,
        BigDecimal fee,
        double payoutAmount,
        String payoutCurrency,
        BigDecimal balanceAfter,
        Instant createdAt) {

    static WithdrawalResponse from(WithdrawalResult result) {
        var w = result.withdrawal();
        return new WithdrawalResponse(
                w.getId(),
                w.getStatus().name(),
                w.getAmount(),
                w.getCurrency(),
                w.getFee(),
                w.getPayoutAmount().doubleValue(),
                w.getPayoutCurrency(),
                result.balanceAfter(),
                w.getCreatedAt());
    }
}
