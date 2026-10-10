package com.example.payouts.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;
import com.example.payouts.domain.OutboxEvent;
import com.example.payouts.repository.OutboxRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Publishes outbox rows to Kafka.
 *
 * PAY ATTENTION:
 * - One relay at a time (ShedLock) and rows in id order, so events of one key enter Kafka in order.
 * - Wait for the broker ack before marking the row PUBLISHED and before sending the next one; stop at the
 *   first failure instead of skipping ahead (skipping would reorder events).
 * - The producer's delivery.timeout.ms (application.yml) is SHORTER than our wait here, so when we stop
 *   waiting the client has really given up: no stray copy can arrive later, out of order.
 * - At-least-once: a crash between the ack and markAs re-sends the row. The "event-id" header lets
 *   consumers de-duplicate.
 * - A time budget keeps one run well inside the ShedLock lease (lockAtMostFor).
 * - No database transaction is held while talking to Kafka.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_WAIT_SECONDS = 10;   // > producer delivery.timeout.ms (5s)
    private static final int BATCH = 100;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final PayoutProperties.Jobs jobs;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       PayoutProperties properties, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.jobs = properties.jobs();
        this.clock = clock;
    }

    @SchedulerLock(name = "outboxRelay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    public void publishPending() {
        Instant deadline = clock.instant().plus(jobs.outboxRunBudget());
        for (OutboxEvent event : outbox.findByStatusOrderByIdAsc(OutboxEvent.Status.NEW, PageRequest.of(0, BATCH))) {
            if (clock.instant().isAfter(deadline)) {
                return;   // the rest waits for the next run
            }
            var record = new ProducerRecord<>(event.getTopic(), event.getKey(), event.getPayload());
            record.headers().add("event-id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
            record.headers().add("event-type", event.getEventType().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(SEND_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Publishing outbox event {} failed; will retry on the next run", event.getId(), e);
                return;
            }
            outbox.markAs(event.getId(), OutboxEvent.Status.PUBLISHED, clock.instant());
        }
    }
}
