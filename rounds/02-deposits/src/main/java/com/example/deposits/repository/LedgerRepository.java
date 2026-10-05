package com.example.deposits.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.deposits.domain.LedgerEntry;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByDepositId(Long depositId);
}
