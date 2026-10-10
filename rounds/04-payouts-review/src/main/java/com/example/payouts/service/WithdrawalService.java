package com.example.payouts.service;

import java.time.Clock;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payouts.domain.Wallet;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;

import static com.example.payouts.service.PayoutException.Code.IDEMPOTENCY_CONFLICT;
import static com.example.payouts.service.PayoutException.Code.WALLET_NOT_FOUND;
import static com.example.payouts.service.PayoutException.Code.WITHDRAWAL_NOT_FOUND;

@Service
@Transactional
public class WithdrawalService {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final FeePolicy feePolicy;
    private final WithdrawalTransactions transactions;
    private final Clock clock;

    public WithdrawalService(WalletRepository wallets, WithdrawalRepository withdrawals, FeePolicy feePolicy,
                             WithdrawalTransactions transactions, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feePolicy = feePolicy;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Withdrawal request(String idempotencyKey, RequestWithdrawal command) throws PayoutException {
        Optional<Withdrawal> previous = withdrawals.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            Withdrawal existing = previous.get();
            if (!existing.isSameRequestAs(command.amount(), command.payoutMethodId())) {
                throw new PayoutException(IDEMPOTENCY_CONFLICT, "Idempotency key already used");
            }
            return existing;
        }

        Wallet wallet = wallets.findByPlayerId(command.playerId())
                .orElseThrow(() -> new PayoutException(WALLET_NOT_FOUND, "No wallet for player " + command.playerId()));

        Money amount = Money.of(command.amount(), command.currency());
        Money fee = feePolicy.feeFor(amount);
        Withdrawal draft = Withdrawal.reserve(command.playerId(), wallet.getId(), idempotencyKey, amount, fee,
                command.payoutMethodId(), clock.instant());
        try {
            return transactions.reserve(draft);
        } catch (DataIntegrityViolationException duplicate) {
            return withdrawals.findByIdempotencyKey(idempotencyKey).orElseThrow();
        }
    }

    public Withdrawal find(Long withdrawalId) throws PayoutException {
        return withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new PayoutException(WITHDRAWAL_NOT_FOUND, "Withdrawal " + withdrawalId + " not found"));
    }
}
