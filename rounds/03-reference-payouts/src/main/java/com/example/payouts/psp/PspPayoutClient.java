package com.example.payouts.psp;

/** Port to the payment service provider. The HTTP details stay in the adapter (anti-corruption layer). */
public interface PspPayoutClient {

    PayoutResult payout(PayoutRequest request);

    PspPayoutStatus lookup(String merchantReference);
}
