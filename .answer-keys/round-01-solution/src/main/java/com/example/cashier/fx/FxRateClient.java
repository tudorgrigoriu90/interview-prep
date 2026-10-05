package com.example.cashier.fx;

import java.math.BigDecimal;

public interface FxRateClient {

    /**
     * Price of one unit of {@code base} expressed in {@code quote}.
     */
    BigDecimal quote(String base, String quote);
}
