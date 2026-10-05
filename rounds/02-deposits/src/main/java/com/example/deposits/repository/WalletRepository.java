package com.example.deposits.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.deposits.domain.Wallet;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByPlayerId(Long playerId);
}
