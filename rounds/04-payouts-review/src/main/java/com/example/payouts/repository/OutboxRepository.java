package com.example.payouts.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payouts.domain.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatus(OutboxEvent.Status status);
}
