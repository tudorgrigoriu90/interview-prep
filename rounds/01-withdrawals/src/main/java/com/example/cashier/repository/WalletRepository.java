package com.example.cashier.repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.cashier.domain.Wallet;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByPlayerId(Long playerId);

    @Modifying
    @Query("update Wallet w set w.balance = w.balance - :amount where w.id = :id and w.balance >= :amount")
    int debit(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
