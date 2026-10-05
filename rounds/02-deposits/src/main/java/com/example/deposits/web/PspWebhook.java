package com.example.deposits.web;

import java.math.BigDecimal;

import com.example.deposits.psp.PspStatus;

public record PspWebhook(
        String pspReference,
        PspStatus status,
        BigDecimal amount,
        String currency,
        String payerEmail,
        String cardHolder) {
}
