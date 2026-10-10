package com.example.payouts.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

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
 * One player request to take money out.
 *
 * PAY ATTENTION:
 * - (player_id, idempotency_key) is UNIQUE in the database. That constraint, not a Java check, is what stops
 *   two concurrent identical requests from creating two withdrawals.
 * - The status is never changed through a setter. Transitions are conditional updates (WithdrawalRepository)
 *   so that concurrent callers cannot both apply the same transition.
 * - The merchant reference sent to the PSP is derived from our id, so it is stable across retries and can
 *   be used as the PSP idempotency key and for lookups.
 */
@Entity
@Table(name = "withdrawal",
        uniqueConstraints = @UniqueConstraint(name = "uq_withdrawal_idem", columnNames = {"player_id", "idempotency_key"}))
public class Withdrawal {

    private static final String MERCHANT_REFERENCE_PREFIX = "wd-";

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

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fee;

    @Column(nullable = false, length = 3)
    private String currency;

    /** A token for the player's saved bank account or e-wallet. Never the account number itself. */
    @Column(name = "payout_method_id", nullable = false, length = 64)
    private String payoutMethodId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // plain VARCHAR, not a MySQL ENUM: adding a state is then a code change only
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

    /** What the player receives. */
    public Money amountMoney() {
        return Money.of(amount, currency);
    }

    public Money feeMoney() {
        return Money.of(fee, currency);
    }

    /** What is taken from (and, on failure, returned to) the wallet. */
    public Money total() {
        return amountMoney().plus(feeMoney());
    }

    /**
     * Idempotent replay check. Money equality is safe because Money normalises the scale:
     * a retry that sends "100" matches a stored 100.0000.
     */
    public boolean isSameRequestAs(Money requestedAmount, String requestedPayoutMethodId) {
        return amountMoney().equals(requestedAmount) && payoutMethodId.equals(requestedPayoutMethodId);
    }

    public String merchantReference() {
        return MERCHANT_REFERENCE_PREFIX + id;
    }

    public static Optional<Long> idFromMerchantReference(String merchantReference) {
        if (merchantReference == null || !merchantReference.startsWith(MERCHANT_REFERENCE_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(merchantReference.substring(MERCHANT_REFERENCE_PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
