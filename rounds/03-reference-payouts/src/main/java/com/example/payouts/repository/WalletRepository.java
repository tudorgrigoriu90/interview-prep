package com.example.payouts.repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.Wallet;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByPlayerId(Long playerId);

    /**
     * Debit only if the money is there. Returns 1 if debited, 0 if not.
     *
     * WHY: the check and the write are ONE statement. The database locks the row, waits for any concurrent
     * writer, then evaluates "balance >= :amount" against the latest committed value. This is true on MySQL
     * InnoDB (even at its default REPEATABLE READ: UPDATE always reads the latest committed row), PostgreSQL,
     * Oracle and SQL Server, so it is a portable guard.
     *
     * PAY ATTENTION:
     * - The caller MUST check the returned count. Ignoring it is the bug that pays out money never debited.
     * - Bulk JPQL updates bypass @Version and the persistence context. clearAutomatically drops stale copies
     *   so a later read in the same transaction sees the new balance; flushAutomatically writes pending
     *   changes first.
     * - MANDATORY: this must run inside the caller's transaction, together with the withdrawal row,
     *   the ledger row and the outbox row. Called without one, it fails instead of committing alone.
     * - Index the WHERE columns (here the primary key). On MySQL an UPDATE that cannot use an index
     *   locks every row it scans.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Wallet w set w.balance = w.balance - :amount
             where w.id = :walletId and w.currency = :currency and w.balance >= :amount
            """)
    int debitIfSufficient(@Param("walletId") Long walletId,
                          @Param("amount") BigDecimal amount,
                          @Param("currency") String currency);

    /** Credit back reserved funds. Returns the row count; 0 means the wallet is gone or the currency differs. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Wallet w set w.balance = w.balance + :amount
             where w.id = :walletId and w.currency = :currency
            """)
    int credit(@Param("walletId") Long walletId,
               @Param("amount") BigDecimal amount,
               @Param("currency") String currency);
}
