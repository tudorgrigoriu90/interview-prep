package com.example.deposits.service;

public class DepositMismatchException extends Exception {

    public DepositMismatchException(String message) {
        super(message);
    }
}
