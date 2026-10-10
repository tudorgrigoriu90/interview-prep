package com.example.payouts.messaging;

import java.time.Instant;

/** Event published by the risk / AML service (topic risk.withdrawal-decisions.v1). */
public record RiskDecision(String eventId, Long withdrawalId, Decision decision, Instant decidedAt) {

    public enum Decision { APPROVED, REJECTED }
}
