package com.example.deposits.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.deposits.domain.Deposit;
import com.example.deposits.domain.DepositStatus;
import com.example.deposits.repository.DepositRepository;

@Component
@ConditionalOnProperty(name = "deposits.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final DepositRepository deposits;
    private final DepositService depositService;

    public ReconciliationJob(DepositRepository deposits, DepositService depositService) {
        this.deposits = deposits;
        this.depositService = depositService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        for (Deposit deposit : deposits.findByStatus(DepositStatus.PENDING)) {
            try {
                depositService.resolvePending(deposit);
            } catch (Exception e) {
                log.warn("Could not resolve deposit {}", deposit.getId(), e);
            }
        }
    }
}
