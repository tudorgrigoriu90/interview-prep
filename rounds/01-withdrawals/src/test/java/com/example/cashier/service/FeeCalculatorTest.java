package com.example.cashier.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.cashier.config.CashierProperties;

import static org.assertj.core.api.Assertions.assertThat;

class FeeCalculatorTest {

    private final FeeCalculator calculator = new FeeCalculator(new CashierProperties(
            new CashierProperties.Fee(new BigDecimal("2"), new BigDecimal("1.00")),
            null,
            null));

    @Test
    void chargesPercentageOfAmount() {
        assertThat(calculator.feeFor(new BigDecimal("100.00"))).isEqualByComparingTo("2.00");
    }

    @Test
    void roundsToCents() {
        assertThat(calculator.feeFor(new BigDecimal("333.33"))).isEqualByComparingTo("6.67");
    }

    @Test
    void appliesMinimumFee() {
        assertThat(calculator.feeFor(new BigDecimal("20.00"))).isEqualByComparingTo("1.00");
    }
}
