package com.example.cashier.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import com.example.cashier.service.WithdrawalCommand;

public record WithdrawalRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 3) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String payoutCurrency,
        @NotBlank String payoutMethodId) {

    WithdrawalCommand toCommand() {
        return new WithdrawalCommand(amount, currency, payoutCurrency, payoutMethodId);
    }
}
