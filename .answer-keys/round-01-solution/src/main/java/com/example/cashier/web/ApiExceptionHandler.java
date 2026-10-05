package com.example.cashier.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.cashier.service.WithdrawalException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WithdrawalException.class)
    public ProblemDetail handle(WithdrawalException e) {
        HttpStatus status = switch (e.reason()) {
            case WALLET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case CURRENCY_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handle(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
