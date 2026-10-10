package com.example.payouts.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Expected values come from the business rule, worked out by hand, never copied from the code's output. */
class MoneyTest {

    @Test
    void normalisesToTheCurrencyScaleSoEqualityIsByValue() {
        assertThat(Money.of("100", "EUR")).isEqualTo(Money.of("100.0000", "EUR"));
        assertThat(Money.of("100", "EUR").toPlainString()).isEqualTo("100.00");
        assertThat(Money.of("1500", "JPY").amount().scale()).isZero();
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllowsInsteadOfRoundingSilently() {
        assertThatThrownBy(() -> Money.of("10.005", "EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("10.5", "JPY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentageMultipliesFirstAndRoundsOnce() {
        // 2.5 % of 200.00 is 5.00. Rounding the rate first (0.025 -> 0.03) would give 6.00.
        assertThat(Money.of("200.00", "EUR").percentage(new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isEqualTo(Money.of("5.00", "EUR"));
        // 333.33 * 2.5 / 100 = 8.333250 -> 8.33
        assertThat(Money.of("333.33", "EUR").percentage(new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isEqualTo(Money.of("8.33", "EUR"));
        // half-even: 0.125 -> 0.12, 0.135 -> 0.14
        assertThat(Money.of("5.00", "EUR").percentage(new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isEqualTo(Money.of("0.12", "EUR"));
        assertThat(Money.of("5.40", "EUR").percentage(new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isEqualTo(Money.of("0.14", "EUR"));
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of("1.00", "EUR").plus(Money.of("1.00", "SEK")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printsPlainDigitsNeverScientificNotation() {
        assertThat(new Money(new BigDecimal("1E+2"), java.util.Currency.getInstance("EUR")).toPlainString())
                .isEqualTo("100.00");
    }
}
