package com.example.payouts.messaging;

public record RiskDecision(String eventId, Long withdrawalId, Decision decision) {

    public enum Decision { APPROVED, REJECTED }
}
