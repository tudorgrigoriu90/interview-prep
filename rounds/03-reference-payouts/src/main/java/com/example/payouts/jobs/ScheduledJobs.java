package com.example.payouts.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.payouts.messaging.OutboxRelay;
import com.example.payouts.service.PayoutProcessor;

/**
 * Only the timers live here. The work (and its ShedLock) lives in the job beans, so tests call
 * runOnce()/publishPending() directly with the schedulers switched off.
 */
@Component
@ConditionalOnProperty(prefix = "payouts.jobs", name = "enabled", havingValue = "true")
public class ScheduledJobs {

    private final PayoutProcessor payoutProcessor;
    private final OutboxRelay outboxRelay;

    public ScheduledJobs(PayoutProcessor payoutProcessor, OutboxRelay outboxRelay) {
        this.payoutProcessor = payoutProcessor;
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelayString = "${payouts.jobs.payout-interval-ms:5000}", initialDelayString = "${payouts.jobs.initial-delay-ms:10000}")
    public void payouts() {
        payoutProcessor.runOnce();
    }

    @Scheduled(fixedDelayString = "${payouts.jobs.outbox-interval-ms:500}", initialDelayString = "${payouts.jobs.initial-delay-ms:10000}")
    public void outbox() {
        outboxRelay.publishPending();
    }
}
