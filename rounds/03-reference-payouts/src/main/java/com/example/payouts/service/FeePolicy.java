package com.example.payouts.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.money.Money;

import static com.example.payouts.service.PayoutException.Code.UNSUPPORTED_CURRENCY;

/**
 * Withdrawal fee = max(amount × percent, minimum for that currency).
 *
 * PAY ATTENTION:
 * - HALF_EVEN (banker's rounding) is a business decision; whichever mode you pick, pick ONE and document it.
 * - The fee is rounded once, inside Money.percentage, to the currency's digits.
 * - A currency without a configured minimum is rejected instead of silently using some default.
 */
@Component
public class FeePolicy {

    static final RoundingMode FEE_ROUNDING = RoundingMode.HALF_EVEN;

    private final PayoutProperties.Fee fee;

    public FeePolicy(PayoutProperties properties) {
        this.fee = properties.fee();
    }

    public Money feeFor(Money amount) {
        BigDecimal configuredMinimum = fee.minimum().get(amount.currencyCode());
        if (configuredMinimum == null) {
            throw new PayoutException(UNSUPPORTED_CURRENCY, "Withdrawals in " + amount.currencyCode() + " are not supported");
        }
        Money minimum = Money.of(configuredMinimum, amount.currencyCode());
        return amount.percentage(fee.percent(), FEE_ROUNDING).max(minimum);
    }
}
