package com.example.cashier.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.cashier.config.CashierProperties;

@Component
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CashierProperties.Fee fee;

    public FeeCalculator(CashierProperties properties) {
        this.fee = properties.fee();
    }

    public BigDecimal feeFor(BigDecimal amount) {
        BigDecimal rate = fee.percent().divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal calculated = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return calculated.max(fee.minimum());
    }
}
