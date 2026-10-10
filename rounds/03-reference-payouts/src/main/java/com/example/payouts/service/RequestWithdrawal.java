package com.example.payouts.service;

import java.math.BigDecimal;

/** What the player asks for, already through bean validation in the controller. */
public record RequestWithdrawal(BigDecimal amount, String currency, String payoutMethodId) {
}
