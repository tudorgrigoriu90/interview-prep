package com.example.payouts.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.payouts.service.PayoutException;

/**
 * One error format for the whole API: RFC 9457 ProblemDetail with a stable "code".
 *
 * Extending ResponseEntityExceptionHandler gives ProblemDetail 400s for the framework's own errors
 * (missing header, invalid body, failed bean validation).
 *
 * PAY ATTENTION:
 * - Map YOUR exceptions explicitly. Do not map IllegalArgumentException or Exception to 400: a bug would then
 *   look like a client error and nobody would get paged.
 * - 422 for "valid request, business rule says no", 409 for idempotency conflicts, 404 for "not yours".
 * - Never put stack traces or other players' data in the response.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PayoutException.class)
    public ProblemDetail handle(PayoutException e) {
        HttpStatus status = switch (e.code()) {
            case WALLET_NOT_FOUND, WITHDRAWAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, UNSUPPORTED_CURRENCY -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code().name());
        return problem;
    }
}
