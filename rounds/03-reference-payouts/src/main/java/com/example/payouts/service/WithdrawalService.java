package com.example.payouts.service;

import java.time.Clock;
import java.util.Currency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.payouts.domain.Wallet;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;

import static com.example.payouts.service.PayoutException.Code.CURRENCY_MISMATCH;
import static com.example.payouts.service.PayoutException.Code.IDEMPOTENCY_CONFLICT;
import static com.example.payouts.service.PayoutException.Code.INSUFFICIENT_FUNDS;
import static com.example.payouts.service.PayoutException.Code.INVALID_AMOUNT;
import static com.example.payouts.service.PayoutException.Code.UNSUPPORTED_CURRENCY;
import static com.example.payouts.service.PayoutException.Code.WALLET_NOT_FOUND;
import static com.example.payouts.service.PayoutException.Code.WITHDRAWAL_NOT_FOUND;

/**
 * Use case: a player requests a withdrawal.
 *
 * WHY this class is NOT @Transactional: it validates and decides, then hands the database work to ONE short
 * transaction (WithdrawalTransactions.reserve). Nothing slow happens while a row lock is held.
 *
 * PAY ATTENTION, in order:
 * 1. Validate input in the domain too (not only with @Valid): other callers do not go through the controller.
 * 2. Idempotency: same (player, key) returns the stored result; a different payload with the same key is a 409.
 * 3. The wallet currency must match the requested currency. Subtracting "100" without a currency is how a
 *    SEK wallet ends up paying 100 EUR.
 * 4. The early balance check is only a fast path for a friendly error. The real guard is the conditional
 *    debit inside the transaction, because the balance can change right after we read it.
 * 5. A concurrent duplicate request loses on the unique constraint; we catch that OUTSIDE the failed
 *    transaction and replay the winner. (Inside the transaction it would already be marked rollback-only.)
 */
@Service
public class WithdrawalService {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final FeePolicy feePolicy;
    private final WithdrawalTransactions transactions;
    private final Clock clock;
    private final Counter requested;

    public WithdrawalService(WalletRepository wallets, WithdrawalRepository withdrawals, FeePolicy feePolicy,
                             WithdrawalTransactions transactions, Clock clock, MeterRegistry meters) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feePolicy = feePolicy;
        this.transactions = transactions;
        this.clock = clock;
        this.requested = meters.counter("payouts.withdrawals.requested");
    }

    public WithdrawalOutcome request(Long playerId, String idempotencyKey, RequestWithdrawal command) {
        Money amount = toMoney(command);

        var previous = withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), amount, command.payoutMethodId());
        }

        Wallet wallet = wallets.findByPlayerId(playerId)
                .orElseThrow(() -> new PayoutException(WALLET_NOT_FOUND, "No wallet for player " + playerId));
        if (!wallet.getCurrency().equals(amount.currencyCode())) {
            throw new PayoutException(CURRENCY_MISMATCH, "The wallet is in " + wallet.getCurrency());
        }

        Money fee = feePolicy.feeFor(amount);
        Money total = amount.plus(fee);
        if (wallet.balance().amount().compareTo(total.amount()) < 0) {
            throw new PayoutException(INSUFFICIENT_FUNDS, "Balance too low for a withdrawal of " + total);
        }

        Withdrawal draft = Withdrawal.reserve(playerId, wallet.getId(), idempotencyKey, amount, fee,
                command.payoutMethodId(), clock.instant());
        try {
            Withdrawal reserved = transactions.reserve(draft);
            requested.increment();
            return new WithdrawalOutcome(reserved, true);
        } catch (DataIntegrityViolationException duplicateKey) {
            return withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey)
                    .map(winner -> replay(winner, amount, command.payoutMethodId()))
                    .orElseThrow(() -> duplicateKey);
        }
    }

    public Withdrawal findForPlayer(Long withdrawalId, Long playerId) {
        return withdrawals.findByIdAndPlayerId(withdrawalId, playerId)
                .orElseThrow(() -> new PayoutException(WITHDRAWAL_NOT_FOUND, "Withdrawal " + withdrawalId + " not found"));
    }

    private WithdrawalOutcome replay(Withdrawal existing, Money amount, String payoutMethodId) {
        if (!existing.isSameRequestAs(amount, payoutMethodId)) {
            throw new PayoutException(IDEMPOTENCY_CONFLICT,
                    "Idempotency key already used for a different withdrawal request");
        }
        return new WithdrawalOutcome(existing, false);
    }

    private static Money toMoney(RequestWithdrawal command) {
        Currency currency;
        try {
            currency = Currency.getInstance(command.currency());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PayoutException(UNSUPPORTED_CURRENCY, "Unknown currency " + command.currency());
        }
        Money amount;
        try {
            amount = new Money(command.amount(), currency);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PayoutException(INVALID_AMOUNT, e.getMessage() == null ? "Amount is required" : e.getMessage());
        }
        if (!amount.isPositive()) {
            throw new PayoutException(INVALID_AMOUNT, "Amount must be positive");
        }
        return amount;
    }
}
