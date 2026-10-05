package com.example.cashier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.cashier.domain.Wallet;
import com.example.cashier.domain.Withdrawal;
import com.example.cashier.repository.WalletRepository;
import com.example.cashier.repository.WithdrawalRepository;

import static com.example.cashier.service.WithdrawalException.Reason.CURRENCY_MISMATCH;
import static com.example.cashier.service.WithdrawalException.Reason.IDEMPOTENCY_CONFLICT;
import static com.example.cashier.service.WithdrawalException.Reason.INSUFFICIENT_FUNDS;
import static com.example.cashier.service.WithdrawalException.Reason.INVALID_AMOUNT;
import static com.example.cashier.service.WithdrawalException.Reason.WALLET_NOT_FOUND;

/**
 * Not transactional on purpose: validation and the FX call happen before any row lock is taken,
 * the database work is one short transaction in {@link WithdrawalRecorder}.
 */
@Service
public class WithdrawalService {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final FeeCalculator feeCalculator;
    private final FxService fxService;
    private final WithdrawalRecorder recorder;
    private final Clock clock;

    public WithdrawalService(WalletRepository wallets, WithdrawalRepository withdrawals,
                             FeeCalculator feeCalculator, FxService fxService,
                             WithdrawalRecorder recorder, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feeCalculator = feeCalculator;
        this.fxService = fxService;
        this.recorder = recorder;
        this.clock = clock;
    }

    public WithdrawalResult requestWithdrawal(Long playerId, String idempotencyKey, WithdrawalCommand command) {
        Optional<Withdrawal> previous = withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), command);
        }

        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new WithdrawalException(INVALID_AMOUNT, "Amount must be positive");
        }
        Currency currency = Currency.getInstance(command.currency());
        Currency payoutCurrency = Currency.getInstance(command.payoutCurrency());
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new WithdrawalException(INVALID_AMOUNT, "Too many decimals for " + currency);
        }

        Wallet wallet = wallets.findByPlayerId(playerId)
                .orElseThrow(() -> new WithdrawalException(WALLET_NOT_FOUND, "No wallet for player " + playerId));
        if (!wallet.getCurrency().equals(currency.getCurrencyCode())) {
            throw new WithdrawalException(CURRENCY_MISMATCH, "Wallet is in " + wallet.getCurrency());
        }

        BigDecimal fee = feeCalculator.feeFor(amount, currency);
        BigDecimal total = amount.add(fee);
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }

        BigDecimal payoutAmount = fxService.convert(amount, currency, payoutCurrency);
        Withdrawal withdrawal = new Withdrawal(
                playerId, wallet.getId(), idempotencyKey,
                amount, currency.getCurrencyCode(), fee,
                payoutAmount, payoutCurrency.getCurrencyCode(), command.payoutMethodId(),
                clock.instant());
        try {
            return recorder.record(withdrawal, total);
        } catch (DataIntegrityViolationException e) {
            // the same key was inserted concurrently: our transaction rolled back, return the winner's result
            return withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey)
                    .map(existing -> replay(existing, command))
                    .orElseThrow(() -> e);
        }
    }

    private WithdrawalResult replay(Withdrawal existing, WithdrawalCommand command) {
        boolean sameRequest = existing.getAmount().compareTo(command.amount()) == 0
                && existing.getCurrency().equals(command.currency())
                && existing.getPayoutCurrency().equals(command.payoutCurrency());
        if (!sameRequest) {
            throw new WithdrawalException(IDEMPOTENCY_CONFLICT,
                    "Idempotency key reused with a different request: " + existing.getIdempotencyKey());
        }
        BigDecimal balance = wallets.findById(existing.getWalletId()).map(Wallet::getBalance).orElseThrow();
        return new WithdrawalResult(existing, balance);
    }
}
