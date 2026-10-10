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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    public enum EntryType { RESERVATION, RELEASE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "withdrawal_id", nullable = false)
    private Long withdrawalId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Withdrawal withdrawal, EntryType type, Instant now) {
        this.walletId = withdrawal.getWalletId();
        this.withdrawalId = withdrawal.getId();
        this.entryType = type;
        this.amount = withdrawal.getAmount();
        this.currency = withdrawal.getCurrency();
        this.createdAt = now;
    }

    public static LedgerEntry reservation(Withdrawal withdrawal, Instant now) {
        return new LedgerEntry(withdrawal, EntryType.RESERVATION, now);
    }

    public static LedgerEntry release(Withdrawal withdrawal, Instant now) {
        return new LedgerEntry(withdrawal, EntryType.RELEASE, now);
    }

    public Long getId() {
        return id;
    }

    public Long getWithdrawalId() {
        return withdrawalId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
