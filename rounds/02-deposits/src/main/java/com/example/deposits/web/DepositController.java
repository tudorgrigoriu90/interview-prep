package com.example.deposits.web;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.deposits.service.DepositService;

@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse create(@RequestHeader("X-Player-Id") Long playerId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody DepositRequest request) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        return DepositResponse.from(depositService.initiate(playerId, key, request.toCommand()));
    }
}
