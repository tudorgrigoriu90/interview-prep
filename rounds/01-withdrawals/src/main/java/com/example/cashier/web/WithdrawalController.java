package com.example.cashier.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.cashier.service.WithdrawalService;

@RestController
@RequestMapping("/api/v1/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse create(@RequestHeader("X-Player-Id") Long playerId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestBody WithdrawalRequest request) {
        return WithdrawalResponse.from(
                withdrawalService.requestWithdrawal(playerId, idempotencyKey, request.toCommand()));
    }
}
