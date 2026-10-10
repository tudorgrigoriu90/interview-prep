package com.example.payouts.domain;

import java.util.EnumSet;
import java.util.Set;

public enum WithdrawalStatus {
    RESERVED,
    APPROVED,
    SENT,
    COMPLETED,
    REJECTED,
    FAILED;

    public Set<WithdrawalStatus> allowedPredecessors() {
        return switch (this) {
            case RESERVED -> EnumSet.noneOf(WithdrawalStatus.class);
            case APPROVED, REJECTED -> EnumSet.of(RESERVED);
            case SENT -> EnumSet.of(APPROVED);
            case COMPLETED -> EnumSet.of(SENT);
            case FAILED -> EnumSet.of(APPROVED, SENT, COMPLETED);
        };
    }
}
