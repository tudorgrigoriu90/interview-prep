package com.example.payouts.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.payouts.service.PayoutException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PayoutException.class)
    public ProblemDetail handle(PayoutException e) {
        HttpStatus status = switch (e.code()) {
            case WALLET_NOT_FOUND, WITHDRAWAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code().name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAll(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.toString());
    }
}
