package com.example.cashier.messaging;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxStatus status);

    @Transactional
    @Modifying
    @Query("update OutboxEvent o set o.status = :status, o.publishedAt = :now where o.id = :id")
    int markAs(@Param("id") Long id, @Param("status") OutboxStatus status, @Param("now") Instant now);
}
