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

/**
 * Append-only record of every money movement on a wallet.
 *
 * WHY: the balance alone cannot explain itself. The ledger can: sum(credits) - sum(debits) must always
 * equal the balance movement, and finance reconciles against it.
 *
 * PAY ATTENTION:
 * - Never update or delete a ledger row. A correction is a new row.
 * - UNIQUE (withdrawal_id, entry_type): the database itself refuses a second RELEASE for the same withdrawal.
 *   That is defence in depth behind the guarded status transition.
 * - The ledger row is written in the SAME transaction as the balance change, or the two will drift.
 */
@Entity
@Table(name = "ledger_entry",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_withdrawal_type", columnNames = {"withdrawal_id", "entry_type"}))
public class LedgerEntry {

    public enum EntryType { RESERVATION, RELEASE }

    public enum Direction { DEBIT, CREDIT }

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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Withdrawal withdrawal, EntryType type, Direction direction, Instant now) {
        Money total = withdrawal.total();
        this.walletId = withdrawal.getWalletId();
        this.withdrawalId = withdrawal.getId();
        this.entryType = type;
        this.direction = direction;
        this.amount = total.amount();
        this.currency = total.currencyCode();
        this.createdAt = now;
    }

    public static LedgerEntry reservation(Withdrawal withdrawal, Instant now) {
        return new LedgerEntry(withdrawal, EntryType.RESERVATION, Direction.DEBIT, now);
    }

    public static LedgerEntry release(Withdrawal withdrawal, Instant now) {
        return new LedgerEntry(withdrawal, EntryType.RELEASE, Direction.CREDIT, now);
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

    public Direction getDirection() {
        return direction;
    }

    public Money money() {
        return Money.of(amount, currency);
    }
}
