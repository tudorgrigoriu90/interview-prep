package com.example.cashier.service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.cashier.config.CashierProperties;

import static org.assertj.core.api.Assertions.assertThat;

class FeeCalculatorTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    private final FeeCalculator calculator = new FeeCalculator(new CashierProperties(
            new CashierProperties.Fee(new BigDecimal("2.5"),
                    Map.of("EUR", new BigDecimal("1.00"), "JPY", new BigDecimal("150"))),
            null,
            null));

    @Test
    void chargesConfiguredPercentageWithoutRoundingTheRate() {
        assertThat(calculator.feeFor(new BigDecimal("200.00"), EUR)).isEqualByComparingTo("5.00");
    }

    @Test
    void roundsOnceAtTheEndToCents() {
        assertThat(calculator.feeFor(new BigDecimal("333.33"), EUR)).isEqualByComparingTo("8.33");
    }

    @Test
    void appliesMinimumFeeInTheCurrencyOfTheWithdrawal() {
        assertThat(calculator.feeFor(new BigDecimal("20.00"), EUR)).isEqualByComparingTo("1.00");
        assertThat(calculator.feeFor(new BigDecimal("1000"), JPY)).isEqualByComparingTo("150");
    }

    @Test
    void usesZeroDecimalsForJpy() {
        assertThat(calculator.feeFor(new BigDecimal("10000"), JPY).scale()).isZero();
        assertThat(calculator.feeFor(new BigDecimal("10000"), JPY)).isEqualByComparingTo("250");
    }
}
