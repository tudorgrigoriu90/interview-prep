package com.example.payouts.service;

import java.math.BigDecimal;

/** Parsed body of a PSP payout notification. Only what we need; ignore everything else the PSP sends. */
public record PspWebhookEvent(
        String eventId,
        String merchantReference,
        String pspReference,
        Status status,
        BigDecimal amount,
        String currency) {

    public enum Status { COMPLETED, FAILED }
}
