package com.example.payouts.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.example.payouts.messaging.InvalidMessageException;

@Configuration
public class KafkaConfig {

    public static final String DLT_SUFFIX = ".DLT";

    /**
     * Error handling for every @KafkaListener (Spring Boot attaches a CommonErrorHandler bean to the
     * default listener container factory).
     *
     * WHY: the listener must never catch-and-log. It throws; this handler retries with backoff, then
     * publishes the record to "<topic>.DLT" so nothing is silently lost and the partition is not blocked forever.
     *
     * PAY ATTENTION:
     * - Messages that can never succeed (malformed JSON, missing fields) are NOT retried: straight to the DLT.
     * - Retries block the partition while they run. Keep them short; for long waits use @RetryableTopic.
     * - The DLT needs at least as many partitions as the source topic (the record keeps its partition).
     * - Someone must watch the DLT (metric + alert) and have a replay procedure.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> template) {
        // Explicit destination: same topic name + ".DLT", same partition. Do not rely on the default suffix:
        // it differs between Spring Kafka versions ("-dlt" in recent ones), and a silent change sends dead
        // letters to an auto-created topic nobody monitors.
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidMessageException.class);
        return handler;
    }

    // Topics are declared here so the example is self-contained. In a real platform they are usually
    // owned by infrastructure-as-code or by the producing team (risk decisions belong to the risk team).

    @Bean
    public NewTopic withdrawalEventsTopic(PayoutProperties properties) {
        return topic(properties.topics().withdrawalEvents(), properties);
    }

    @Bean
    public NewTopic riskDecisionsTopic(PayoutProperties properties) {
        return topic(properties.topics().riskDecisions(), properties);
    }

    @Bean
    public NewTopic riskDecisionsDeadLetterTopic(PayoutProperties properties) {
        return topic(properties.topics().riskDecisions() + DLT_SUFFIX, properties);
    }

    private static NewTopic topic(String name, PayoutProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.topics().partitions())
                .replicas(properties.topics().replicas())
                .build();
    }
}
