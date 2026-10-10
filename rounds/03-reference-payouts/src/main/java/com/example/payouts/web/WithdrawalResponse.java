package com.example.payouts.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.example.payouts.domain.Withdrawal;

/**
 * API output.
 *
 * PAY ATTENTION: money is serialised as a string ("100.00") at the currency's scale, with its currency.
 * JavaScript clients and many JSON libraries parse numbers as binary doubles; strings keep the exact value.
 * No internal fields (wallet id, idempotency key, PSP reference) are exposed.
 */
public record WithdrawalResponse(
        Long id,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee,
        String currency,
        Instant createdAt) {

    static WithdrawalResponse from(Withdrawal w) {
        return new WithdrawalResponse(w.getId(), w.getStatus().name(), w.amountMoney().amount(),
                w.feeMoney().amount(), w.amountMoney().currencyCode(), w.getCreatedAt());
    }
}
