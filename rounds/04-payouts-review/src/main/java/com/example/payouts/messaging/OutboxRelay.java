package com.example.payouts.messaging;

import java.time.Clock;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.payouts.domain.OutboxEvent;
import com.example.payouts.repository.OutboxRepository;

@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    public void publishPending() {
        for (OutboxEvent event : outbox.findByStatus(OutboxEvent.Status.NEW)) {
            kafka.send(event.getTopic(), event.getKey(), event.getPayload());
            event.markPublished(clock.instant());
            outbox.save(event);
        }
    }
}
