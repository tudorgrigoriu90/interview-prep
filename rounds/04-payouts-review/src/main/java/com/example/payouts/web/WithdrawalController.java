package com.example.payouts.web;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.service.PayoutException;
import com.example.payouts.service.WithdrawalService;

@RestController
@RequestMapping("/api/v1/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    public ResponseEntity<Withdrawal> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @Valid @RequestBody CreateWithdrawalRequest request) throws PayoutException {
        Withdrawal withdrawal = withdrawalService.request(idempotencyKey, request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/withdrawals/" + withdrawal.getId())).body(withdrawal);
    }

    @GetMapping("/{id}")
    public Withdrawal get(@PathVariable Long id) throws PayoutException {
        return withdrawalService.find(id);
    }
}
