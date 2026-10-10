package com.example.payouts.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Oldest unpublished events first. Backed by the index (status, id).
     *
     * PAY ATTENTION: no "created_at > last seen" cursor. Rows become visible in COMMIT order, not insert
     * order, so a cursor skips a row whose transaction committed late. "status = NEW order by id" never does.
     */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxEvent.Status status, Pageable page);

    /**
     * Called by the relay, which has no transaction of its own (it must not hold one while talking to Kafka),
     * so this single statement is its own short transaction.
     */
    @Transactional
    @Modifying
    @Query("update OutboxEvent o set o.status = :status, o.publishedAt = :now where o.id = :id")
    int markAs(@Param("id") Long id, @Param("status") OutboxEvent.Status status, @Param("now") Instant now);
}
