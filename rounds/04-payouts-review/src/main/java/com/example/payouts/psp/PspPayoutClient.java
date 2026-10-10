package com.example.payouts.psp;

public interface PspPayoutClient {

    PayoutResult payout(PayoutRequest request);
}
