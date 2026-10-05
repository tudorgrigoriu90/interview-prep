package com.example.cashier.service;

import java.math.BigDecimal;

public record WithdrawalCommand(BigDecimal amount, String currency, String payoutCurrency, String payoutMethodId) {
}
