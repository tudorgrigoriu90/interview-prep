package com.example.payouts.service;

import java.math.BigDecimal;

public record RequestWithdrawal(Long playerId, BigDecimal amount, String currency, String payoutMethodId) {
}
