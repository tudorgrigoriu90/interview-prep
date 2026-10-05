package com.example.cashier.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.cashier.domain.Withdrawal;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    Optional<Withdrawal> findByPlayerIdAndIdempotencyKey(Long playerId, String idempotencyKey);
}
