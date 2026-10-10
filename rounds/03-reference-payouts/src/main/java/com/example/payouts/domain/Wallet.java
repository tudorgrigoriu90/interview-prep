package com.example.payouts.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.payouts.money.Money;

/**
 * The player's wallet. In this service it is only READ through the entity; every change goes through the
 * conditional updates in WalletRepository.
 *
 * PAY ATTENTION: there is deliberately no setBalance(). "Load, add in Java, save" is a lost update waiting
 * to happen when two requests touch the same wallet.
 */
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

    /** DECIMAL(19,4): every ISO currency fits; the code normalises to the currency's digits. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    protected Wallet() {
    }

    public Wallet(Long playerId, Money openingBalance) {
        this.playerId = playerId;
        this.currency = openingBalance.currencyCode();
        this.balance = openingBalance.amount();
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

    public Money balance() {
        return Money.of(balance, currency);
    }
}
