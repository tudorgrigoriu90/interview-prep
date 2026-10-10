package com.example.payouts.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The withdrawal state machine. Every transition is applied in the database with
 * "UPDATE ... WHERE id = :id AND status IN (:allowedPredecessors)" and the row count is checked.
 *
 * <pre>
 *  RESERVED ──approve──▶ APPROVED ──claim──▶ SENDING ──PSP accepted──▶ SENT ──webhook/lookup──▶ COMPLETED
 *     │                                        │  └───────────── webhook/lookup completed ──────────▶ COMPLETED
 *     └──reject──▶ REJECTED (funds released)    └── declined / failed ──▶ FAILED (funds released) ◀── SENT
 * </pre>
 *
 * WHY in the database: three instances, Kafka redeliveries, PSP webhook retries and the resolver job can all
 * try the same transition at the same time. The conditional UPDATE lets exactly one of them win; the others
 * see 0 rows and do nothing. That single fact gives "refund exactly once" and "complete exactly once".
 *
 * PAY ATTENTION: final states never move again, so a late "FAILED" webhook cannot undo a COMPLETED payout.
 */
public enum WithdrawalStatus {

    /** Funds are debited from the wallet (reserved) and the risk team has not decided yet. */
    RESERVED,
    /** Risk approved; waiting for the payout job. */
    APPROVED,
    /** Claimed by the payout job; the PSP call is in flight or its outcome is unknown. */
    SENDING,
    /** The PSP accepted the payout; the final result comes by webhook (or by lookup). */
    SENT,
    COMPLETED,
    /** Risk rejected; the reserved funds were released. */
    REJECTED,
    /** The PSP declined or the payout failed; the reserved funds were released. */
    FAILED;

    public Set<WithdrawalStatus> allowedPredecessors() {
        return switch (this) {
            case RESERVED -> EnumSet.noneOf(WithdrawalStatus.class);
            case APPROVED, REJECTED -> EnumSet.of(RESERVED);
            case SENDING -> EnumSet.of(APPROVED);
            case SENT -> EnumSet.of(SENDING);
            case COMPLETED, FAILED -> EnumSet.of(SENDING, SENT);
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED || this == FAILED;
    }
}
