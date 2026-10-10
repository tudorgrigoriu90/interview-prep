package com.example.payouts.messaging;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.domain.OutboxEvent;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.repository.OutboxRepository;

/**
 * Writes an event into the outbox table.
 *
 * PAY ATTENTION: Propagation.MANDATORY. Writing an outbox row OUTSIDE the business transaction defeats the
 * whole pattern, so calling this without a transaction is a programming error and fails immediately.
 *
 * Key = player id: Kafka keeps order per partition, and the key picks the partition, so all events of one
 * player arrive in the order they were published.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String topic;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json, PayoutProperties properties) {
        this.outbox = outbox;
        this.json = json;
        this.topic = properties.topics().withdrawalEvents();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String eventType, Withdrawal withdrawal, Instant now) {
        WithdrawalEvent event = WithdrawalEvent.of(eventType, withdrawal, now);
        try {
            outbox.save(new OutboxEvent(withdrawal.getId(), eventType, topic,
                    String.valueOf(withdrawal.getPlayerId()), json.writeValueAsString(event), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + eventType + " for withdrawal " + withdrawal.getId(), e);
        }
    }
}
