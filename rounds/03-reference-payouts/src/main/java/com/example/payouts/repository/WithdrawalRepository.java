package com.example.payouts.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    /** PAY ATTENTION: idempotency keys are scoped to the caller. Looking up by key alone leaks other players' data. */
    Optional<Withdrawal> findByPlayerIdAndIdempotencyKey(Long playerId, String idempotencyKey);

    /** Ownership check built into the query: another player's id simply is "not found" (no IDOR). */
    Optional<Withdrawal> findByIdAndPlayerId(Long id, Long playerId);

    /** Bounded batch (Pageable becomes LIMIT / FETCH FIRST / TOP depending on the database). */
    @Query("select w.id from Withdrawal w where w.status = :status order by w.id")
    List<Long> findIdsByStatus(@Param("status") WithdrawalStatus status, Pageable page);

    /** Rows that have been in a waiting state for too long: candidates for a PSP status lookup. */
    @Query("""
            select w.id from Withdrawal w
             where w.status in :statuses and w.updatedAt < :before
             order by w.id
            """)
    List<Long> findIdsByStatusInAndUpdatedBefore(@Param("statuses") Collection<WithdrawalStatus> statuses,
                                                 @Param("before") Instant before,
                                                 Pageable page);

    /**
     * The state machine's only door. Returns 1 if this caller performed the transition, 0 if the withdrawal
     * was not in an allowed predecessor state (someone else already moved it, or the move is illegal).
     *
     * PAY ATTENTION: callers run the side effects (refund, event) ONLY when this returns 1.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Withdrawal w set w.status = :to, w.updatedAt = :now
             where w.id = :id and w.status in :from
            """)
    int transition(@Param("id") Long id,
                   @Param("from") Collection<WithdrawalStatus> from,
                   @Param("to") WithdrawalStatus to,
                   @Param("now") Instant now);

    /** Same as {@link #transition} and also stores the PSP's reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Withdrawal w set w.status = :to, w.pspReference = :pspReference, w.updatedAt = :now
             where w.id = :id and w.status in :from
            """)
    int transitionWithReference(@Param("id") Long id,
                                @Param("from") Collection<WithdrawalStatus> from,
                                @Param("to") WithdrawalStatus to,
                                @Param("pspReference") String pspReference,
                                @Param("now") Instant now);

    /** Pushes the next resolution attempt back by "stuckAfter" (a simple backoff for the resolver). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Withdrawal w set w.updatedAt = :now where w.id = :id and w.status in :statuses")
    int touch(@Param("id") Long id,
              @Param("statuses") Collection<WithdrawalStatus> statuses,
              @Param("now") Instant now);
}
