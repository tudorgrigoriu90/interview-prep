package com.example.payouts.messaging;

/** A message that can never be processed (bad JSON, missing fields). The error handler sends it to the DLT without retrying. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
