package com.example.payouts.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public static Money of(double amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money percentage(BigDecimal percent) {
        return new Money(amount.multiply(percent).divide(HUNDRED, RoundingMode.UP), currency);
    }

    public Money max(Money other) {
        return amount.compareTo(other.amount) >= 0 ? this : other;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }
}
