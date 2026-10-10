package com.example.payouts.service;

public class PayoutException extends Exception {

    public enum Code {
        WALLET_NOT_FOUND,
        WITHDRAWAL_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        INVALID_AMOUNT,
        IDEMPOTENCY_CONFLICT
    }

    private final Code code;

    public PayoutException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
