package com.example.payouts.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.example.payouts.service.RequestWithdrawal;

/**
 * API input. A DTO, never the entity (no mass assignment, no leaking internal fields).
 *
 * PAY ATTENTION: these annotations do NOTHING unless the controller parameter has @Valid.
 * The service validates the amount again, because not every caller comes through HTTP.
 */
public record CreateWithdrawalRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 64) String payoutMethodId) {

    RequestWithdrawal toCommand() {
        return new RequestWithdrawal(amount, currency, payoutMethodId);
    }
}
