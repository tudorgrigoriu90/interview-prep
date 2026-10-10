package com.example.payouts.messaging;

import java.time.Instant;

import com.example.payouts.domain.Withdrawal;

public record WithdrawalEvent(
        String eventType,
        Long withdrawalId,
        Long playerId,
        double amount,
        String currency,
        String status,
        Instant occurredAt) {

    public static WithdrawalEvent of(String eventType, Withdrawal w, Instant now) {
        return new WithdrawalEvent(eventType, w.getId(), w.getPlayerId(), w.getAmount().doubleValue(),
                w.getCurrency(), w.getStatus().name(), now);
    }
}
