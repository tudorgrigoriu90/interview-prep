package com.example.deposits.service;

import java.math.BigDecimal;

public record DepositCommand(BigDecimal amount, String currency, String paymentToken) {
}
