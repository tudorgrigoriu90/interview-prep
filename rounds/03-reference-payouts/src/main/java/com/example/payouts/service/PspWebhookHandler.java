package com.example.payouts.service;

import java.util.Optional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;
import com.example.payouts.repository.WithdrawalRepository;

/**
 * Applies a verified PSP notification.
 *
 * PAY ATTENTION:
 * - Webhooks are delivered at least once and possibly out of order. The guarded transitions make duplicates
 *   and late events harmless: a second COMPLETED, or a FAILED after COMPLETED, changes nothing.
 * - Verify amount and currency BEFORE acting. Acting first and checking afterwards (or throwing a checked
 *   exception that does not roll back) credits or completes the wrong amount.
 * - Duplicates get a 2xx, so the PSP stops retrying. Only a mismatch is an error response.
 * - Log ids and statuses only, never the raw payload (it can carry names, emails, account data).
 */
@Component
public class PspWebhookHandler {

    public enum Outcome { APPLIED, ALREADY_APPLIED, UNKNOWN_REFERENCE, MISMATCH }

    private static final Logger log = LoggerFactory.getLogger(PspWebhookHandler.class);

    private final WithdrawalRepository withdrawals;
    private final WithdrawalTransactions transactions;
    private final Counter mismatches;

    public PspWebhookHandler(WithdrawalRepository withdrawals, WithdrawalTransactions transactions, MeterRegistry meters) {
        this.withdrawals = withdrawals;
        this.transactions = transactions;
        this.mismatches = meters.counter("payouts.webhook.mismatch");
    }

    public Outcome handle(PspWebhookEvent event) {
        Optional<Withdrawal> found = Withdrawal.idFromMerchantReference(event.merchantReference())
                .flatMap(withdrawals::findById);
        if (found.isEmpty()) {
            log.warn("Webhook {} for unknown merchant reference {}", event.eventId(), event.merchantReference());
            return Outcome.UNKNOWN_REFERENCE;
        }
        Withdrawal withdrawal = found.get();

        if (!matchesAmount(withdrawal, event)) {
            mismatches.increment();
            log.error("Webhook {} amount/currency does not match withdrawal {}", event.eventId(), withdrawal.getId());
            return Outcome.MISMATCH;
        }

        boolean applied = switch (event.status()) {
            case COMPLETED -> transactions.complete(withdrawal.getId(), event.pspReference());
            case FAILED -> transactions.failAndRelease(withdrawal.getId());
        };
        log.info("Webhook {} for withdrawal {}: {} ({})", event.eventId(), withdrawal.getId(), event.status(),
                applied ? "applied" : "already applied or not applicable");
        return applied ? Outcome.APPLIED : Outcome.ALREADY_APPLIED;
    }

    private static boolean matchesAmount(Withdrawal withdrawal, PspWebhookEvent event) {
        try {
            return withdrawal.amountMoney().equals(Money.of(event.amount(), event.currency()));
        } catch (RuntimeException invalidAmountOrCurrency) {
            return false;
        }
    }
}
