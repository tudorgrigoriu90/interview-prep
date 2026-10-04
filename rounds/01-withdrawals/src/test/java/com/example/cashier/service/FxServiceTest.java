package com.example.cashier.service;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import com.example.cashier.fx.FxRateClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FxServiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency GBP = Currency.getInstance("GBP");

    private final FxRateClient rates = mock(FxRateClient.class);
    private final FxService fxService = new FxService(rates);

    @Test
    void sameCurrencyNeedsNoQuote() {
        assertThat(fxService.convert(new BigDecimal("42.10"), EUR, EUR)).isEqualByComparingTo("42.10");
        verifyNoInteractions(rates);
    }

    @Test
    void convertsUsingQuote() {
        when(rates.quote("GBP", "EUR")).thenReturn(1.25);

        assertThat(fxService.convert(new BigDecimal("100.00"), EUR, GBP)).isEqualByComparingTo("80.00");
    }
}
