package com.example.cashier.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cashier.outbox.poll-interval:500ms}")
    @SchedulerLock(name = "outboxRelay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    public void publishPending() {
        for (OutboxEvent event : outbox.findTop100ByStatusOrderByIdAsc(OutboxStatus.NEW)) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.getTopic(), event.getKey(), event.getPayload());
            record.headers().add("event-id", String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Publishing outbox event {} failed, will retry on next run", event.getId(), e);
                return;
            }
            outbox.markAs(event.getId(), OutboxStatus.PUBLISHED, clock.instant());
        }
    }
}
