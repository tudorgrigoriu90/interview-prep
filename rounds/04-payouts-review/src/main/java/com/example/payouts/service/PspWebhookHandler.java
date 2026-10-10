package com.example.payouts.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.repository.WithdrawalRepository;

@Component
public class PspWebhookHandler {

    public enum Outcome { APPLIED, ALREADY_APPLIED, MISMATCH }

    private static final Logger log = LoggerFactory.getLogger(PspWebhookHandler.class);

    private final WithdrawalRepository withdrawals;
    private final WithdrawalTransactions transactions;

    public PspWebhookHandler(WithdrawalRepository withdrawals, WithdrawalTransactions transactions) {
        this.withdrawals = withdrawals;
        this.transactions = transactions;
    }

    public Outcome handle(PspWebhookEvent event) {
        log.info("PSP webhook received: {}", event);
        String idempotencyKey = event.merchantReference().substring("wd-".length());
        Withdrawal withdrawal = withdrawals.findByIdempotencyKey(idempotencyKey).orElseThrow();

        boolean applied = switch (event.status()) {
            case COMPLETED -> transactions.complete(withdrawal.getId());
            case FAILED -> transactions.failAndRelease(withdrawal.getId());
        };

        if (event.amount().compareTo(withdrawal.getAmount()) != 0) {
            log.error("Amount mismatch for withdrawal {}", withdrawal.getId());
            return Outcome.MISMATCH;
        }
        return applied ? Outcome.APPLIED : Outcome.ALREADY_APPLIED;
    }
}
