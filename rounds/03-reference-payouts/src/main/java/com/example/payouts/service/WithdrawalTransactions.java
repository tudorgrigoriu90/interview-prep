package com.example.payouts.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.LedgerEntry;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;
import com.example.payouts.messaging.OutboxWriter;
import com.example.payouts.money.Money;
import com.example.payouts.repository.LedgerRepository;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;

import static com.example.payouts.domain.WithdrawalStatus.APPROVED;
import static com.example.payouts.domain.WithdrawalStatus.COMPLETED;
import static com.example.payouts.domain.WithdrawalStatus.FAILED;
import static com.example.payouts.domain.WithdrawalStatus.REJECTED;
import static com.example.payouts.domain.WithdrawalStatus.SENDING;
import static com.example.payouts.domain.WithdrawalStatus.SENT;
import static com.example.payouts.service.PayoutException.Code.INSUFFICIENT_FUNDS;

/**
 * Every database transaction of the withdrawal lifecycle, and nothing else.
 *
 * WHY a separate bean: @Transactional works only when the call goes through the Spring proxy. The callers
 * (WithdrawalService, PayoutProcessor, the webhook handler, the Kafka handler) are other beans, so each call
 * here really starts (or joins) a transaction. A "this.someTransactionalMethod()" call inside one class would
 * silently run without a transaction.
 *
 * PAY ATTENTION, the shape of every method:
 * 1. Guarded transition FIRST (conditional UPDATE). If it returns 0 rows, stop: someone else already did it.
 * 2. Side effects (balance, ledger, outbox) only after the transition succeeded, in the SAME transaction.
 * 3. No network calls in here. Remote calls happen between these short transactions, never inside them.
 */
@Component
public class WithdrawalTransactions {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final LedgerRepository ledger;
    private final OutboxWriter outbox;
    private final Clock clock;

    public WithdrawalTransactions(WalletRepository wallets, WithdrawalRepository withdrawals,
                                  LedgerRepository ledger, OutboxWriter outbox, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.ledger = ledger;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Debit the wallet (amount + fee), store the withdrawal, its ledger row and its event: all or nothing.
     *
     * A duplicate idempotency key surfaces here as DataIntegrityViolationException from saveAndFlush; the
     * whole transaction (including the debit) rolls back and the caller replays the winner.
     */
    @Transactional
    public Withdrawal reserve(Withdrawal draft) {
        Instant now = clock.instant();
        Money total = draft.total();
        if (wallets.debitIfSufficient(draft.getWalletId(), total.amount(), total.currencyCode()) == 0) {
            throw new PayoutException(INSUFFICIENT_FUNDS, "Balance too low for a withdrawal of " + total);
        }
        Withdrawal saved = withdrawals.saveAndFlush(draft);   // flush now so a key conflict fails HERE, in this transaction
        ledger.save(LedgerEntry.reservation(saved, now));
        outbox.append("WithdrawalRequested", saved, now);
        return saved;
    }

    @Transactional
    public boolean approve(Long withdrawalId) {
        return move(withdrawalId, APPROVED);
    }

    @Transactional
    public boolean reject(Long withdrawalId) {
        if (!move(withdrawalId, REJECTED)) {
            return false;
        }
        Withdrawal withdrawal = load(withdrawalId);
        release(withdrawal);
        outbox.append("WithdrawalRejected", withdrawal, clock.instant());
        return true;
    }

    /**
     * APPROVED -> SENDING, committed BEFORE the PSP call.
     *
     * WHY: if two instances (or two runs) pick the same row, only one claim succeeds, so only one of them
     * calls the PSP. If we crash right after the call, the row stays SENDING and the resolver asks the PSP.
     */
    @Transactional
    public boolean claimForSending(Long withdrawalId) {
        return move(withdrawalId, SENDING);
    }

    @Transactional
    public boolean markSent(Long withdrawalId, String pspReference) {
        return withdrawals.transitionWithReference(withdrawalId, SENT.allowedPredecessors(), SENT,
                pspReference, clock.instant()) == 1;
    }

    @Transactional
    public boolean complete(Long withdrawalId, String pspReference) {
        int moved = pspReference == null
                ? withdrawals.transition(withdrawalId, COMPLETED.allowedPredecessors(), COMPLETED, clock.instant())
                : withdrawals.transitionWithReference(withdrawalId, COMPLETED.allowedPredecessors(), COMPLETED,
                        pspReference, clock.instant());
        if (moved == 0) {
            return false;
        }
        outbox.append("WithdrawalCompleted", load(withdrawalId), clock.instant());
        return true;
    }

    /** SENDING/SENT -> FAILED and give the reserved funds back, exactly once. */
    @Transactional
    public boolean failAndRelease(Long withdrawalId) {
        if (!move(withdrawalId, FAILED)) {
            return false;
        }
        Withdrawal withdrawal = load(withdrawalId);
        release(withdrawal);
        outbox.append("WithdrawalFailed", withdrawal, clock.instant());
        return true;
    }

    /** Resolver backoff: the next lookup for this row waits another "stuckAfter". */
    @Transactional
    public void recordResolutionAttempt(Long withdrawalId) {
        withdrawals.touch(withdrawalId, COMPLETED.allowedPredecessors(), clock.instant());
    }

    private boolean move(Long withdrawalId, WithdrawalStatus to) {
        return withdrawals.transition(withdrawalId, to.allowedPredecessors(), to, clock.instant()) == 1;
    }

    private Withdrawal load(Long withdrawalId) {
        return withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new IllegalStateException("Withdrawal " + withdrawalId + " disappeared"));
    }

    /**
     * Credit amount + fee back and record it. Only ever called after a successful guarded transition, and the
     * ledger's UNIQUE (withdrawal_id, entry_type) rejects a second RELEASE even if that rule were broken.
     */
    private void release(Withdrawal withdrawal) {
        Money total = withdrawal.total();
        if (wallets.credit(withdrawal.getWalletId(), total.amount(), total.currencyCode()) != 1) {
            throw new IllegalStateException("Could not release funds of withdrawal " + withdrawal.getId());
        }
        ledger.save(LedgerEntry.release(withdrawal, clock.instant()));
    }
}
