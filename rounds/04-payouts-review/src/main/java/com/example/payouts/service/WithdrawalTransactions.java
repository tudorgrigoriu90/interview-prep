package com.example.payouts.service;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.LedgerEntry;
import com.example.payouts.domain.Wallet;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;
import com.example.payouts.messaging.OutboxWriter;
import com.example.payouts.repository.LedgerRepository;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;

import static com.example.payouts.domain.WithdrawalStatus.APPROVED;
import static com.example.payouts.domain.WithdrawalStatus.COMPLETED;
import static com.example.payouts.domain.WithdrawalStatus.FAILED;
import static com.example.payouts.domain.WithdrawalStatus.REJECTED;
import static com.example.payouts.domain.WithdrawalStatus.SENT;

@Component
public class WithdrawalTransactions {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final LedgerRepository ledger;
    private final OutboxWriter outbox;
    private final Clock clock;

    public WithdrawalTransactions(WalletRepository wallets, WithdrawalRepository withdrawals,
                                  LedgerRepository ledger, OutboxWriter outbox, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.ledger = ledger;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public Withdrawal reserve(Withdrawal draft) throws PayoutException {
        Withdrawal saved = withdrawals.save(draft);
        ledger.save(LedgerEntry.reservation(saved, clock.instant()));
        outbox.append("WithdrawalRequested", saved);

        Wallet wallet = wallets.findById(saved.getWalletId()).orElseThrow();
        wallet.debit(saved.total());
        return saved;
    }

    @Transactional
    public boolean approve(Long withdrawalId) {
        return move(withdrawalId, APPROVED);
    }

    @Transactional
    public void reject(Long withdrawalId) {
        move(withdrawalId, REJECTED);
        Withdrawal withdrawal = load(withdrawalId);
        release(withdrawal);
        outbox.append("WithdrawalRejected", withdrawal);
    }

    @Transactional
    public boolean markSent(Long withdrawalId, String pspReference) {
        return withdrawals.transitionWithReference(withdrawalId, SENT.allowedPredecessors(), SENT,
                pspReference, clock.instant()) == 1;
    }

    @Transactional
    public boolean complete(Long withdrawalId) {
        if (!move(withdrawalId, COMPLETED)) {
            return false;
        }
        outbox.append("WithdrawalCompleted", load(withdrawalId));
        return true;
    }

    @Transactional
    public boolean failAndRelease(Long withdrawalId) {
        if (!move(withdrawalId, FAILED)) {
            return false;
        }
        Withdrawal withdrawal = load(withdrawalId);
        release(withdrawal);
        outbox.append("WithdrawalFailed", withdrawal);
        return true;
    }

    private boolean move(Long withdrawalId, WithdrawalStatus to) {
        return withdrawals.transition(withdrawalId, to.allowedPredecessors(), to, clock.instant()) == 1;
    }

    private Withdrawal load(Long withdrawalId) {
        return withdrawals.findById(withdrawalId).orElseThrow();
    }

    private void release(Withdrawal withdrawal) {
        Wallet wallet = wallets.findById(withdrawal.getWalletId()).orElseThrow();
        wallet.credit(withdrawal.total());
        ledger.save(LedgerEntry.release(withdrawal, clock.instant()));
    }
}
