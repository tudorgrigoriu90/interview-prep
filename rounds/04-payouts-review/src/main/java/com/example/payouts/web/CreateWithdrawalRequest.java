package com.example.payouts.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.payouts.service.RequestWithdrawal;

public record CreateWithdrawalRequest(
        @NotNull Long playerId,
        @NotNull @Min(0) BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String payoutMethodId) {

    RequestWithdrawal toCommand() {
        return new RequestWithdrawal(playerId, amount, currency, payoutMethodId);
    }
}
