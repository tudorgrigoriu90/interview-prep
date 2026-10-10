package com.example.payouts.service;

import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.money.Money;

@Component
public class FeePolicy {

    private final PayoutProperties.Fee fee;

    public FeePolicy(PayoutProperties properties) {
        this.fee = properties.fee();
    }

    public Money feeFor(Money amount) {
        Money minimum = Money.of(fee.minimum(), amount.currencyCode());
        return amount.percentage(fee.percent()).max(minimum);
    }
}
