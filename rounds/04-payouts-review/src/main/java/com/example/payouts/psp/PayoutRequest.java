package com.example.payouts.psp;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;

public record PayoutRequest(String merchantReference, Money amount, String payoutMethodId) {

    public static PayoutRequest of(Withdrawal withdrawal) {
        return new PayoutRequest(withdrawal.merchantReference(), withdrawal.amountMoney(), withdrawal.getPayoutMethodId());
    }
}
