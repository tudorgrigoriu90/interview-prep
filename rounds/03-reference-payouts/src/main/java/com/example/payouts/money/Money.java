package com.example.payouts.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount and its currency, always at the currency's scale (EUR 2 decimals, JPY 0, KWD 3).
 *
 * WHY a value object: every money rule lives in one place, and an invalid amount cannot exist.
 *
 * PAY ATTENTION:
 * - BigDecimal, never double. Create it from a String or from a BigDecimal, never from a double literal.
 * - The constructor REJECTS extra decimals instead of rounding them away silently.
 * - Because the scale is normalised here, the record's generated equals() is safe. On raw BigDecimal,
 *   equals() compares scale (2.0 != 2.00); use compareTo() there.
 * - Operations refuse to mix currencies. Converting currencies is an explicit FX step with a stored rate.
 */
public record Money(BigDecimal amount, Currency currency) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Not a payable currency: " + currency);
        }
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException(amount.toPlainString() + " has more decimals than " + currency + " allows");
        }
        amount = amount.setScale(digits, RoundingMode.UNNECESSARY);   // exact, the check above guarantees it
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * percent = 2.5 means 2.5%.
     *
     * PAY ATTENTION: multiply first, divide once, round once to the currency's digits.
     * Rounding the rate first (2.5 / 100 rounded to 2 decimals = 0.03) silently turns 2.5% into 3%.
     */
    public Money percentage(BigDecimal percent, RoundingMode roundingMode) {
        BigDecimal result = amount.multiply(percent)
                .divide(HUNDRED, currency.getDefaultFractionDigits(), roundingMode);
        return new Money(result, currency);
    }

    public Money max(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0 ? this : other;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    /** For JSON and events: plain digits, never scientific notation. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
