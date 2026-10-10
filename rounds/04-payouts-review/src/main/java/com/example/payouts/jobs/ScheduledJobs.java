package com.example.payouts.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.payouts.messaging.OutboxRelay;
import com.example.payouts.service.PayoutProcessor;

@Component
@ConditionalOnProperty(prefix = "payouts.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private final PayoutProcessor payoutProcessor;
    private final OutboxRelay outboxRelay;

    public ScheduledJobs(PayoutProcessor payoutProcessor, OutboxRelay outboxRelay) {
        this.payoutProcessor = payoutProcessor;
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedRate = 5_000)
    public void payouts() {
        payoutProcessor.runOnce();
    }

    @Scheduled(fixedRate = 1_000)
    public void outbox() {
        outboxRelay.publishPending();
    }
}
