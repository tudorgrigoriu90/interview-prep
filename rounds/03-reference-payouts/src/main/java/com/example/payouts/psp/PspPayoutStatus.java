package com.example.payouts.psp;

/** Answer of the PSP status lookup by merchant reference. */
public enum PspPayoutStatus {
    PENDING,
    COMPLETED,
    FAILED,
    /** The PSP never received this reference: safe to send it again (with the same idempotency key). */
    NOT_FOUND
}
