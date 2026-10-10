package com.example.payouts.service;

import java.math.BigDecimal;

public record PspWebhookEvent(
        String merchantReference,
        String pspReference,
        Status status,
        BigDecimal amount,
        String currency,
        String accountHolder,
        String iban) {

    public enum Status { COMPLETED, FAILED }
}
