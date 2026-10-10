package com.example.payouts.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.payouts.money.Money;
import com.example.payouts.service.PayoutException;

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

    public Wallet(Long playerId, Money openingBalance) {
        this.playerId = playerId;
        this.currency = openingBalance.currencyCode();
        this.balance = openingBalance.amount();
    }

    public void debit(Money amount) throws PayoutException {
        if (balance.compareTo(amount.amount()) < 0) {
            throw new PayoutException(PayoutException.Code.INSUFFICIENT_FUNDS, "Balance too low");
        }
        balance = balance.subtract(amount.amount());
    }

    public void credit(Money amount) {
        balance = balance.add(amount.amount());
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
