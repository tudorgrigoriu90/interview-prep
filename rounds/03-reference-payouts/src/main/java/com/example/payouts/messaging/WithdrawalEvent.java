package com.example.payouts.messaging;

import java.time.Instant;

import com.example.payouts.domain.Withdrawal;

/**
 * The public event contract (topic payouts.withdrawal-events.v1).
 *
 * PAY ATTENTION:
 * - Money as plain strings plus currency. Never double.
 * - A schema version, so consumers can evolve. Only add optional fields within a version.
 * - No personal data. Ids and amounts are enough; consumers that need more ask the owning service.
 */
public record WithdrawalEvent(
        int schemaVersion,
        String eventType,
        Long withdrawalId,
        Long playerId,
        String amount,
        String fee,
        String currency,
        String status,
        Instant occurredAt) {

    public static WithdrawalEvent of(String eventType, Withdrawal w, Instant now) {
        return new WithdrawalEvent(1, eventType, w.getId(), w.getPlayerId(),
                w.amountMoney().toPlainString(), w.feeMoney().toPlainString(), w.amountMoney().currencyCode(),
                w.getStatus().name(), now);
    }
}
