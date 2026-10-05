package com.example.cashier.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import org.springframework.stereotype.Component;

import com.example.cashier.config.CashierProperties;

@Component
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CashierProperties.Fee fee;

    public FeeCalculator(CashierProperties properties) {
        this.fee = properties.fee();
    }

    public BigDecimal feeFor(BigDecimal amount, Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        BigDecimal calculated = amount.multiply(fee.percent())
                .divide(HUNDRED, digits, RoundingMode.HALF_EVEN);
        return calculated.max(minimumFor(currency, digits));
    }

    private BigDecimal minimumFor(Currency currency, int digits) {
        BigDecimal minimum = fee.minimum().get(currency.getCurrencyCode());
        if (minimum == null) {
            throw new IllegalStateException("No minimum fee configured for " + currency.getCurrencyCode());
        }
        return minimum.setScale(digits, RoundingMode.HALF_EVEN);
    }
}
