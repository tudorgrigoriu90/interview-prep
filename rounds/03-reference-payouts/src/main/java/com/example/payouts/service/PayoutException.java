package com.example.payouts.service;

/**
 * Business errors with a stable machine-readable code. The web layer maps codes to HTTP statuses.
 *
 * WHY unchecked: Spring rolls back a @Transactional method on RuntimeException by default. A checked
 * exception would COMMIT the transaction unless every method declared rollbackFor.
 */
public class PayoutException extends RuntimeException {

    public enum Code {
        WALLET_NOT_FOUND,
        WITHDRAWAL_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        CURRENCY_MISMATCH,
        UNSUPPORTED_CURRENCY,
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
