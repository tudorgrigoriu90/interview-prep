package com.example.deposits.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.deposits.domain.Deposit;
import com.example.deposits.domain.DepositStatus;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    Optional<Deposit> findByIdempotencyKey(String idempotencyKey);

    Optional<Deposit> findByPspReference(String pspReference);

    List<Deposit> findByStatus(DepositStatus status);
}
