package com.example.payouts.messaging;

import java.time.Clock;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.domain.OutboxEvent;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.repository.OutboxRepository;

@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String topic;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json, PayoutProperties properties, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.topic = properties.topics().withdrawalEvents();
        this.clock = clock;
    }

    public void append(String eventType, Withdrawal withdrawal) {
        try {
            String payload = json.writeValueAsString(WithdrawalEvent.of(eventType, withdrawal, clock.instant()));
            outbox.save(new OutboxEvent(eventType, topic, UUID.randomUUID().toString(), payload, clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
