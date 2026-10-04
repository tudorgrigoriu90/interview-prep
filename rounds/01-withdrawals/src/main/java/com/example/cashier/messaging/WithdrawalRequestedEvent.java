package com.example.cashier.messaging;

import java.time.Instant;

import com.example.cashier.domain.Withdrawal;

public record WithdrawalRequestedEvent(
        Long withdrawalId,
        Long playerId,
        String amount,
        String currency,
        String fee,
        String payoutAmount,
        String payoutCurrency,
        Instant requestedAt) {

    public static WithdrawalRequestedEvent from(Withdrawal w) {
        return new WithdrawalRequestedEvent(
                w.getId(),
                w.getPlayerId(),
                w.getAmount().toPlainString(),
                w.getCurrency(),
                w.getFee().toPlainString(),
                w.getPayoutAmount().toPlainString(),
                w.getPayoutCurrency(),
                w.getCreatedAt());
    }
}
