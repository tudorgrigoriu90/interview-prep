package com.example.payouts.web;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payouts.service.WithdrawalOutcome;
import com.example.payouts.service.WithdrawalService;

/**
 * PAY ATTENTION:
 * - The player id comes from the API gateway (it authenticated the token), never from the body or the URL.
 * - Idempotency-Key is REQUIRED. A missing key is a 400; never invent one server-side (a random key makes
 *   every retry a new withdrawal).
 * - 201 Created + Location for a new withdrawal, 200 with the same body for an idempotent replay.
 * - GET only returns the caller's own withdrawal (ownership in the query); someone else's id is a 404.
 * - The controller only translates HTTP to a use case call. No repositories, no business rules here.
 */
@RestController
@RequestMapping("/api/v1/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    public ResponseEntity<WithdrawalResponse> create(
            @RequestHeader("X-Player-Id") Long playerId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        WithdrawalOutcome outcome = withdrawalService.request(playerId, idempotencyKey, request.toCommand());
        WithdrawalResponse body = WithdrawalResponse.from(outcome.withdrawal());
        if (!outcome.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/withdrawals/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public WithdrawalResponse get(@RequestHeader("X-Player-Id") Long playerId, @PathVariable Long id) {
        return WithdrawalResponse.from(withdrawalService.findForPlayer(id, playerId));
    }
}
