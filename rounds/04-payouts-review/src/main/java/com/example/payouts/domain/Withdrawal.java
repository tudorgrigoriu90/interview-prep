package com.example.payouts.domain;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.payouts.money.Money;

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

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payout_method_id", nullable = false, length = 64)
    private String payoutMethodId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private WithdrawalStatus status;

    @Column(name = "psp_reference", length = 64)
    private String pspReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Withdrawal() {
    }

    public static Withdrawal reserve(Long playerId, Long walletId, String idempotencyKey,
                                     Money amount, Money fee, String payoutMethodId, Instant now) {
        var w = new Withdrawal();
        w.playerId = playerId;
        w.walletId = walletId;
        w.idempotencyKey = idempotencyKey;
        w.amount = amount.amount();
        w.fee = fee.amount();
        w.currency = amount.currencyCode();
        w.payoutMethodId = payoutMethodId;
        w.status = WithdrawalStatus.RESERVED;
        w.createdAt = now;
        w.updatedAt = now;
        return w;
    }

    public Money amountMoney() {
        return Money.of(amount, currency);
    }

    public Money total() {
        return Money.of(amount.add(fee), currency);
    }

    public boolean isSameRequestAs(BigDecimal requestedAmount, String requestedPayoutMethodId) {
        return amount.equals(requestedAmount) && payoutMethodId.equals(requestedPayoutMethodId);
    }

    public String merchantReference() {
        return "wd-" + idempotencyKey;
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

    public BigDecimal getFee() {
        return fee;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPayoutMethodId() {
        return payoutMethodId;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public String getPspReference() {
        return pspReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
