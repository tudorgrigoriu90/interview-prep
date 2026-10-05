package com.example.deposits.psp;

public class PspUnavailableException extends RuntimeException {

    public PspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
