package com.example.payouts.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.psp.PayoutRequest;
import com.example.payouts.psp.PayoutResult;
import com.example.payouts.psp.PspPayoutClient;
import com.example.payouts.psp.PspPayoutStatus;
import com.example.payouts.repository.WithdrawalRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import static com.example.payouts.domain.WithdrawalStatus.APPROVED;
import static com.example.payouts.domain.WithdrawalStatus.SENDING;
import static com.example.payouts.domain.WithdrawalStatus.SENT;

/**
 * Sends approved payouts to the PSP and resolves the ones whose outcome we do not know.
 *
 * The flow for one payout: claim (tx) -> PSP call (NO tx) -> record the result (tx).
 *
 * PAY ATTENTION:
 * - The PSP call is between two short transactions, never inside one.
 * - Unknown outcome (timeout, 5xx) leaves the row SENDING. We neither refund nor resend blindly. After
 *   "stuckAfter" the resolver asks the PSP by merchant reference, and only resends (same idempotency key)
 *   when the PSP says it never saw the payout.
 * - ShedLock keeps one instance running this at a time, but the claim (APPROVED -> SENDING) is what makes a
 *   double send impossible even if two runs overlap.
 * - A failure on one row is logged and counted, and the loop continues. That is fine HERE because the state
 *   is in the database and the next run retries it. (Contrast: catch-and-log in a Kafka listener loses the
 *   message because the offset is committed.)
 * - Metrics: unknown outcomes and errors are counted so an alert can fire on stuck payouts.
 */
@Component
public class PayoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(PayoutProcessor.class);

    private final WithdrawalRepository withdrawals;
    private final WithdrawalTransactions transactions;
    private final PspPayoutClient psp;
    private final PayoutProperties.Jobs jobs;
    private final Clock clock;
    private final Counter unknownOutcomes;
    private final Counter errors;

    public PayoutProcessor(WithdrawalRepository withdrawals, WithdrawalTransactions transactions, PspPayoutClient psp,
                           PayoutProperties properties, Clock clock, MeterRegistry meters) {
        this.withdrawals = withdrawals;
        this.transactions = transactions;
        this.psp = psp;
        this.jobs = properties.jobs();
        this.clock = clock;
        this.unknownOutcomes = meters.counter("payouts.psp.unknown_outcome");
        this.errors = meters.counter("payouts.processor.errors");
    }

    @SchedulerLock(name = "payoutProcessor", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void runOnce() {
        var batch = PageRequest.of(0, jobs.batchSize());
        for (Long id : withdrawals.findIdsByStatus(APPROVED, batch)) {
            safely(id, () -> send(id));
        }
        Instant stuckBefore = clock.instant().minus(jobs.stuckAfter());
        for (Long id : withdrawals.findIdsByStatusInAndUpdatedBefore(EnumSet.of(SENDING, SENT), stuckBefore, batch)) {
            safely(id, () -> resolve(id));
        }
    }

    private void send(Long id) {
        if (!transactions.claimForSending(id)) {
            return;   // another run or instance claimed it first
        }
        Withdrawal withdrawal = withdrawals.findById(id).orElseThrow();
        apply(id, psp.payout(PayoutRequest.of(withdrawal)));
    }

    private void resolve(Long id) {
        Withdrawal withdrawal = withdrawals.findById(id).orElseThrow();
        PspPayoutStatus status = psp.lookup(withdrawal.merchantReference());
        switch (status) {
            case COMPLETED -> transactions.complete(id, null);
            case FAILED -> transactions.failAndRelease(id);
            case PENDING -> transactions.recordResolutionAttempt(id);
            case NOT_FOUND -> {
                if (withdrawal.getStatus() == SENDING) {
                    // The PSP never received it: resending is safe because the idempotency key is the same.
                    transactions.recordResolutionAttempt(id);
                    apply(id, psp.payout(PayoutRequest.of(withdrawal)));
                } else {
                    log.error("PSP does not know SENT withdrawal {}; needs manual reconciliation", id);
                    transactions.recordResolutionAttempt(id);
                }
            }
        }
    }

    private void apply(Long id, PayoutResult result) {
        switch (result) {
            case PayoutResult.Accepted accepted -> transactions.markSent(id, accepted.pspReference());
            case PayoutResult.Declined declined -> {
                log.info("Payout for withdrawal {} declined: {}", id, declined.reason());
                transactions.failAndRelease(id);
            }
            case PayoutResult.Unknown unknown -> {
                unknownOutcomes.increment();
                log.warn("Payout outcome unknown for withdrawal {} ({}); will look it up later", id, unknown.detail());
            }
        }
    }

    private void safely(Long id, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            errors.increment();
            log.error("Payout processing failed for withdrawal {}; it will be retried on the next run", id, e);
        }
    }
}
