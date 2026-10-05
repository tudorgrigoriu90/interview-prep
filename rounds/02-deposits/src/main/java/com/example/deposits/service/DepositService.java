package com.example.deposits.service;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.deposits.domain.Deposit;
import com.example.deposits.domain.DepositStatus;
import com.example.deposits.domain.LedgerEntry;
import com.example.deposits.domain.Wallet;
import com.example.deposits.psp.PspCharge;
import com.example.deposits.psp.PspClient;
import com.example.deposits.psp.PspStatus;
import com.example.deposits.psp.PspUnavailableException;
import com.example.deposits.repository.DepositRepository;
import com.example.deposits.repository.LedgerRepository;
import com.example.deposits.repository.WalletRepository;
import com.example.deposits.web.PspWebhook;

@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final DepositRepository deposits;
    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final PspClient psp;
    private final Clock clock;

    public DepositService(DepositRepository deposits,
                          WalletRepository wallets,
                          LedgerRepository ledger,
                          PspClient psp,
                          Clock clock) {
        this.deposits = deposits;
        this.wallets = wallets;
        this.ledger = ledger;
        this.psp = psp;
        this.clock = clock;
    }

    public Deposit initiate(Long playerId, String idempotencyKey, DepositCommand command) {
        Optional<Deposit> existing = deposits.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Deposit deposit;
        try {
            deposit = deposits.saveAndFlush(new Deposit(
                    playerId, idempotencyKey, command.amount(), command.currency(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            return deposits.findByIdempotencyKey(idempotencyKey).orElseThrow();
        }

        try {
            PspCharge charge = psp.charge(command.paymentToken(), command.amount(), command.currency(),
                    String.valueOf(deposit.getId()));
            deposit.setPspReference(charge.pspReference());
            if (charge.status() == PspStatus.FAILED) {
                deposit.setStatus(DepositStatus.FAILED);
            }
        } catch (PspUnavailableException e) {
            log.warn("PSP unavailable for deposit {}", deposit.getId());
            deposit.setStatus(DepositStatus.FAILED);
        }
        deposit.touch(clock.instant());
        return deposits.save(deposit);
    }

    @Transactional
    public void settleFromWebhook(PspWebhook event) throws DepositMismatchException {
        Deposit deposit = deposits.findByPspReference(event.pspReference()).orElse(null);
        if (deposit == null) {
            log.warn("Webhook for unknown PSP reference {}", event.pspReference());
            return;
        }
        if (deposit.getStatus() == DepositStatus.COMPLETED) {
            return;
        }
        if (event.status() == PspStatus.FAILED) {
            markFailed(deposit.getId());
            return;
        }
        if (event.status() != PspStatus.SUCCEEDED) {
            return;
        }

        completeDeposit(deposit.getId(), event.pspReference());

        boolean sameAmount = event.amount().compareTo(deposit.getAmount()) == 0;
        if (!sameAmount || !event.currency().equals(deposit.getCurrency())) {
            throw new DepositMismatchException("Webhook " + event.pspReference()
                    + " does not match deposit " + deposit.getId());
        }
    }

    public void resolvePending(Deposit deposit) {
        PspCharge charge = psp.lookup(String.valueOf(deposit.getId()));
        switch (charge.status()) {
            case SUCCEEDED -> completeDeposit(deposit.getId(), charge.pspReference());
            case FAILED, NOT_FOUND -> markFailed(deposit.getId());
            case PENDING -> {
                // still in flight at the PSP, look again on the next run
            }
        }
    }

    @Transactional
    public void completeDeposit(Long depositId, String pspReference) {
        Deposit deposit = deposits.findById(depositId).orElseThrow();
        if (deposit.getStatus() == DepositStatus.COMPLETED) {
            return;
        }
        Wallet wallet = wallets.findByPlayerId(deposit.getPlayerId()).orElseThrow();
        wallet.credit(deposit.getAmount());
        wallets.save(wallet);

        ledger.save(new LedgerEntry(wallet.getId(), deposit.getId(), deposit.getAmount(),
                deposit.getCurrency(), clock.instant()));

        deposit.setPspReference(pspReference);
        deposit.setStatus(DepositStatus.COMPLETED);
        deposit.touch(clock.instant());
        deposits.save(deposit);
    }

    @Transactional
    public void markFailed(Long depositId) {
        Deposit deposit = deposits.findById(depositId).orElseThrow();
        deposit.setStatus(DepositStatus.FAILED);
        deposit.touch(clock.instant());
        deposits.save(deposit);
    }
}
