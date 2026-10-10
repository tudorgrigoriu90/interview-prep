package com.example.payouts.psp;

public sealed interface PayoutResult {

    record Accepted(String pspReference) implements PayoutResult {
    }

    record Declined(String reason) implements PayoutResult {
    }
}
