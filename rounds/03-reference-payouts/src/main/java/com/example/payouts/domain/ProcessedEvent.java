package com.example.payouts.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * "This consumer already handled event X." The primary key is the event id.
 *
 * PAY ATTENTION (a classic Spring Data trap): with an id YOU assign, repository.save() calls merge(), which
 * does SELECT-then-INSERT-or-UPDATE and silently "succeeds" for a duplicate. Implementing Persistable with
 * isNew() = true forces persist(), so a duplicate id hits the primary key and fails loudly. That failure is
 * exactly how the consumer detects a redelivery.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
