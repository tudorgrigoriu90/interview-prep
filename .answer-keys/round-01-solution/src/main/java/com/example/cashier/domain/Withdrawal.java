package com.example.cashier.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "withdrawal",
        uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "idempotency_key"}))
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fee;

    @Column(name = "payout_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal payoutAmount;

    @Column(name = "payout_currency", nullable = false, length = 3)
    private String payoutCurrency;

    @Column(name = "payout_method_id", nullable = false)
    private String payoutMethodId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WithdrawalStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Withdrawal() {
    }

    public Withdrawal(Long playerId, Long walletId, String idempotencyKey,
                      BigDecimal amount, String currency, BigDecimal fee,
                      BigDecimal payoutAmount, String payoutCurrency, String payoutMethodId,
                      Instant createdAt) {
        this.playerId = playerId;
        this.walletId = walletId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.fee = fee;
        this.payoutAmount = payoutAmount;
        this.payoutCurrency = payoutCurrency;
        this.payoutMethodId = payoutMethodId;
        this.status = WithdrawalStatus.PENDING;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public Long getWalletId() {
        return walletId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public BigDecimal getPayoutAmount() {
        return payoutAmount;
    }

    public String getPayoutCurrency() {
        return payoutCurrency;
    }

    public String getPayoutMethodId() {
        return payoutMethodId;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
