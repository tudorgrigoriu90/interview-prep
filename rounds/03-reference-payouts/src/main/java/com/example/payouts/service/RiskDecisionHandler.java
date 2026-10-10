package com.example.payouts.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.ProcessedEvent;
import com.example.payouts.messaging.RiskDecision;
import com.example.payouts.repository.ProcessedEventRepository;

/**
 * Idempotent consumer logic for risk decisions.
 *
 * PAY ATTENTION:
 * - Kafka delivers at least once (rebalances, crashes before the offset commit). The event id is inserted in
 *   the SAME transaction as the effect: either both happen or neither, and a redelivery fails on the primary
 *   key, rolls back, and is skipped by the listener.
 * - Second line of defence: the guarded transition itself (RESERVED -> APPROVED) is a no-op the second time.
 * - Calling transactions.approve()/reject() goes through another bean's proxy and joins THIS transaction.
 */
@Component
public class RiskDecisionHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionHandler.class);

    private final ProcessedEventRepository processedEvents;
    private final WithdrawalTransactions transactions;
    private final Clock clock;

    public RiskDecisionHandler(ProcessedEventRepository processedEvents, WithdrawalTransactions transactions, Clock clock) {
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public void handle(RiskDecision decision) {
        processedEvents.saveAndFlush(new ProcessedEvent(decision.eventId(), clock.instant()));
        boolean applied = switch (decision.decision()) {
            case APPROVED -> transactions.approve(decision.withdrawalId());
            case REJECTED -> transactions.reject(decision.withdrawalId());
        };
        if (!applied) {
            log.warn("Risk decision {} ({}) did not apply to withdrawal {}: it is no longer RESERVED",
                    decision.eventId(), decision.decision(), decision.withdrawalId());
        }
    }
}
