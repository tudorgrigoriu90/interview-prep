package com.example.payouts.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row: an event waiting to be published to Kafka.
 *
 * WHY: "write to the database" and "send to Kafka" can never be one atomic step. Writing the event as a row in
 * the SAME transaction as the state change makes them succeed or fail together; the relay publishes later.
 *
 * PAY ATTENTION:
 * - The payload is a snapshot taken at the moment of the change, not just an id to re-read later.
 * - The row id becomes the "event-id" header, so consumers can de-duplicate (the relay is at-least-once).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    public enum Status { NEW, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 64)
    private String key;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(Long aggregateId, String eventType, String topic, String key, String payload, Instant now) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.key = key;
        this.payload = payload;
        this.status = Status.NEW;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }
}
