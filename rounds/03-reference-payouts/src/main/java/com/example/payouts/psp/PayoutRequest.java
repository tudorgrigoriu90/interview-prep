package com.example.payouts.psp;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;

/**
 * @param merchantReference our stable reference ("wd-42"). It is ALSO the PSP idempotency key, so every
 *                          retry of the same payout is recognised by the PSP as the same payout.
 */
public record PayoutRequest(String merchantReference, Money amount, String payoutMethodId) {

    public static PayoutRequest of(Withdrawal withdrawal) {
        return new PayoutRequest(withdrawal.merchantReference(), withdrawal.amountMoney(), withdrawal.getPayoutMethodId());
    }
}
