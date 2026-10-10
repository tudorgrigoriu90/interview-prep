package com.example.payouts.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    Optional<Withdrawal> findByIdempotencyKey(String idempotencyKey);

    List<Withdrawal> findByStatus(WithdrawalStatus status);

    @Modifying
    @Query("""
            update Withdrawal w set w.status = :to, w.updatedAt = :now
             where w.id = :id and w.status in :from
            """)
    int transition(@Param("id") Long id,
                   @Param("from") Collection<WithdrawalStatus> from,
                   @Param("to") WithdrawalStatus to,
                   @Param("now") Instant now);

    @Modifying
    @Query("""
            update Withdrawal w set w.status = :to, w.pspReference = :pspReference, w.updatedAt = :now
             where w.id = :id and w.status in :from
            """)
    int transitionWithReference(@Param("id") Long id,
                                @Param("from") Collection<WithdrawalStatus> from,
                                @Param("to") WithdrawalStatus to,
                                @Param("pspReference") String pspReference,
                                @Param("now") Instant now);
}
