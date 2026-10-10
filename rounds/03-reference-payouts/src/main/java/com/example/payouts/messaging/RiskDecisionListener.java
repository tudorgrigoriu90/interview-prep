package com.example.payouts.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.payouts.service.RiskDecisionHandler;

/**
 * Consumes risk decisions.
 *
 * PAY ATTENTION:
 * - No catch-all. Anything unexpected propagates to the DefaultErrorHandler (retry with backoff, then DLT).
 *   Catching and logging here would commit the offset and lose the decision.
 * - The ONLY exception swallowed is the duplicate-event signal, and only after the handler's transaction has
 *   rolled back (it is thrown out of the @Transactional method, so the catch is outside the transaction).
 * - Offsets are committed per record after this method returns (ack-mode: record in application.yml).
 * - Processing is sequential per partition. Handing records to a thread pool would break per-key ordering.
 */
@Component
public class RiskDecisionListener {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionListener.class);

    private final RiskDecisionHandler handler;
    private final ObjectMapper json;

    public RiskDecisionListener(RiskDecisionHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = "${payouts.topics.risk-decisions}", groupId = "payouts-risk-decisions")
    public void onDecision(ConsumerRecord<String, String> record) {
        RiskDecision decision = parse(record);
        try {
            handler.handle(decision);
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Risk decision {} already processed; skipping redelivery", decision.eventId());
        }
    }

    private RiskDecision parse(ConsumerRecord<String, String> record) {
        try {
            RiskDecision decision = json.readValue(record.value(), RiskDecision.class);
            if (decision.eventId() == null || decision.withdrawalId() == null || decision.decision() == null) {
                throw new InvalidMessageException("Risk decision without eventId, withdrawalId or decision", null);
            }
            return decision;
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("Unreadable risk decision at offset " + record.offset(), e);
        }
    }
}
