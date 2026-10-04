package com.example.cashier.service;

import java.math.BigDecimal;

import com.example.cashier.domain.Withdrawal;

public record WithdrawalResult(Withdrawal withdrawal, BigDecimal balanceAfter) {
}
