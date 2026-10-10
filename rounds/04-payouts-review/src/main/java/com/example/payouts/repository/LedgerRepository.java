package com.example.payouts.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payouts.domain.LedgerEntry;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByWithdrawalId(Long withdrawalId);
}
