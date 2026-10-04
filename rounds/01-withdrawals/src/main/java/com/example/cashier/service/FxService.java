package com.example.cashier.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import org.springframework.stereotype.Service;

import com.example.cashier.fx.FxRateClient;

@Service
public class FxService {

    private final FxRateClient rates;

    public FxService(FxRateClient rates) {
        this.rates = rates;
    }

    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal rate = new BigDecimal(rates.quote(to.getCurrencyCode(), from.getCurrencyCode()));
        return amount.divide(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
