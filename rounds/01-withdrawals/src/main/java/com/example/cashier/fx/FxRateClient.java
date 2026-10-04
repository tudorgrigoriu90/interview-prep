package com.example.cashier.fx;

public interface FxRateClient {

    /**
     * Price of one unit of {@code base} expressed in {@code quote}.
     */
    double quote(String base, String quote);
}
