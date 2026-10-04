package com.example.cashier.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallet")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false, unique = true)
    private Long playerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    protected Wallet() {
    }

    public Wallet(Long playerId, String currency, BigDecimal balance) {
        this.playerId = playerId;
        this.currency = currency;
        this.balance = balance;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
