package com.example.payouts.psp;

/**
 * The three possible answers to "please pay this out".
 *
 * WHY a sealed type: the compiler forces every caller to handle Unknown. The most expensive payment bug is
 * treating "I don't know" (timeout, 5xx, connection reset) as "it failed" and paying again, or refunding a
 * payout that actually went through.
 */
public sealed interface PayoutResult {

    /** The PSP took the payout. Final success/failure comes later (webhook or lookup). */
    record Accepted(String pspReference) implements PayoutResult {
    }

    /** The PSP looked at it and said no. Safe to release the reserved funds. */
    record Declined(String reason) implements PayoutResult {
    }

    /** We do not know whether the PSP processed it. Never release funds, never treat as failed. */
    record Unknown(String detail) implements PayoutResult {
    }
}
