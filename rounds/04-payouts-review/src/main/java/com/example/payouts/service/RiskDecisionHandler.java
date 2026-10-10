package com.example.payouts.service;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.example.payouts.domain.ProcessedEvent;
import com.example.payouts.messaging.RiskDecision;
import com.example.payouts.repository.ProcessedEventRepository;

@Component
public class RiskDecisionHandler {

    private final ProcessedEventRepository processedEvents;
    private final WithdrawalTransactions transactions;
    private final Clock clock;

    public RiskDecisionHandler(ProcessedEventRepository processedEvents, WithdrawalTransactions transactions, Clock clock) {
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void handle(RiskDecision decision) {
        if (processedEvents.existsById(decision.eventId())) {
            return;
        }
        switch (decision.decision()) {
            case APPROVED -> transactions.approve(decision.withdrawalId());
            case REJECTED -> transactions.reject(decision.withdrawalId());
        }
        processedEvents.save(new ProcessedEvent(decision.eventId(), clock.instant()));
    }
}
