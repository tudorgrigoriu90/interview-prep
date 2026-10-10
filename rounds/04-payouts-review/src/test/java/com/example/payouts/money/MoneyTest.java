package com.example.payouts.money;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void calculatesPercentage() {
        assertThat(Money.of("100.00", "EUR").percentage(new BigDecimal("2.5"))).isEqualTo(Money.of("2.50", "EUR"));
    }

    @Test
    void roundsToCurrencyScale() {
        assertThat(Money.of("10.005", "EUR").amount()).isEqualByComparingTo("10.01");
    }

    @Test
    void addsAmounts() {
        assertThat(Money.of("1.10", "EUR").plus(Money.of("2.20", "EUR"))).isEqualTo(Money.of("3.30", "EUR"));
    }
}
