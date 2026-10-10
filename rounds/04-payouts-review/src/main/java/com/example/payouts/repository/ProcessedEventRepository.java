package com.example.payouts.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payouts.domain.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
