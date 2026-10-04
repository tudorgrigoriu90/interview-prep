package com.example.cashier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cashier.domain.Wallet;
import com.example.cashier.domain.Withdrawal;
import com.example.cashier.messaging.WithdrawalRequestedEvent;
import com.example.cashier.repository.WalletRepository;
import com.example.cashier.repository.WithdrawalRepository;

import static com.example.cashier.service.WithdrawalException.Reason.IDEMPOTENCY_CONFLICT;
import static com.example.cashier.service.WithdrawalException.Reason.INSUFFICIENT_FUNDS;
import static com.example.cashier.service.WithdrawalException.Reason.INVALID_AMOUNT;
import static com.example.cashier.service.WithdrawalException.Reason.WALLET_NOT_FOUND;

@Service
public class WithdrawalService {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final FeeCalculator feeCalculator;
    private final FxService fxService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WithdrawalService(WalletRepository wallets,
                             WithdrawalRepository withdrawals,
                             FeeCalculator feeCalculator,
                             FxService fxService,
                             ApplicationEventPublisher events,
                             Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feeCalculator = feeCalculator;
        this.fxService = fxService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public WithdrawalResult requestWithdrawal(Long playerId, String idempotencyKey, WithdrawalCommand command) {
        Optional<Withdrawal> previous = withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), command);
        }

        Wallet wallet = wallets.findByPlayerId(playerId)
                .orElseThrow(() -> new WithdrawalException(WALLET_NOT_FOUND, "No wallet for player " + playerId));

        Currency currency = Currency.getInstance(command.currency());
        Currency payoutCurrency = Currency.getInstance(command.payoutCurrency());
        BigDecimal amount = command.amount();
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new WithdrawalException(INVALID_AMOUNT, "Too many decimals for " + currency);
        }

        BigDecimal fee = feeCalculator.feeFor(amount);
        BigDecimal total = amount.add(fee);
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }
        wallets.debit(wallet.getId(), total);

        BigDecimal payoutAmount = fxService.convert(amount, currency, payoutCurrency);
        Withdrawal withdrawal = withdrawals.save(new Withdrawal(
                playerId, wallet.getId(), idempotencyKey,
                amount, currency.getCurrencyCode(), fee,
                payoutAmount, payoutCurrency.getCurrencyCode(), command.payoutMethodId(),
                clock.instant()));

        events.publishEvent(WithdrawalRequestedEvent.from(withdrawal));
        return new WithdrawalResult(withdrawal, wallet.getBalance());
    }

    private WithdrawalResult replay(Withdrawal existing, WithdrawalCommand command) {
        boolean sameRequest = existing.getAmount().equals(command.amount())
                && existing.getCurrency().equals(command.currency())
                && existing.getPayoutCurrency().equals(command.payoutCurrency());
        if (!sameRequest) {
            throw new WithdrawalException(IDEMPOTENCY_CONFLICT,
                    "Idempotency key reused with a different request: " + existing.getIdempotencyKey());
        }
        BigDecimal balance = wallets.findById(existing.getWalletId())
                .map(Wallet::getBalance)
                .orElseThrow();
        return new WithdrawalResult(existing, balance);
    }
}
