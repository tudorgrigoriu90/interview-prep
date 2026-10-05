package com.example.cashier.service;

public class WithdrawalException extends RuntimeException {

    public enum Reason {
        WALLET_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        INVALID_AMOUNT,
        IDEMPOTENCY_CONFLICT,
        CURRENCY_MISMATCH
    }

    private final Reason reason;

    public WithdrawalException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
