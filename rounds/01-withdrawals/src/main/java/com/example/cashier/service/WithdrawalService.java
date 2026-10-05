package com.example.cashier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cashier.domain.Wallet;
import com.example.cashier.domain.Withdrawal;
import com.example.cashier.config.CashierProperties;
import com.example.cashier.messaging.OutboxEvent;
import com.example.cashier.messaging.OutboxRepository;
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
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String withdrawalsTopic;
    private final Clock clock;

    public WithdrawalService(WalletRepository wallets,
                             WithdrawalRepository withdrawals,
                             FeeCalculator feeCalculator,
                             FxService fxService,
                             OutboxRepository outbox,
                             ObjectMapper json,
                             CashierProperties properties,
                             Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feeCalculator = feeCalculator;
        this.fxService = fxService;
        this.outbox = outbox;
        this.json = json;
        this.withdrawalsTopic = properties.topics().withdrawals();
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

        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new WithdrawalException(INVALID_AMOUNT, "Amount must be positive");
        }
        Currency currency = Currency.getInstance(command.currency());
        Currency payoutCurrency = Currency.getInstance(command.payoutCurrency());
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new WithdrawalException(INVALID_AMOUNT, "Too many decimals for " + currency);
        }

        BigDecimal fee = feeCalculator.feeFor(amount);
        BigDecimal total = amount.add(fee);
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }
        if (wallets.debit(wallet.getId(), total) == 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }

        BigDecimal payoutAmount = fxService.convert(amount, currency, payoutCurrency);
        Withdrawal withdrawal = withdrawals.save(new Withdrawal(
                playerId, wallet.getId(), idempotencyKey,
                amount, currency.getCurrencyCode(), fee,
                payoutAmount, payoutCurrency.getCurrencyCode(), command.payoutMethodId(),
                clock.instant()));

        outbox.save(toOutboxEvent(withdrawal));
        return new WithdrawalResult(withdrawal, wallet.getBalance());
    }

    private OutboxEvent toOutboxEvent(Withdrawal withdrawal) {
        try {
            String payload = json.writeValueAsString(WithdrawalRequestedEvent.from(withdrawal));
            return new OutboxEvent(withdrawalsTopic, String.valueOf(withdrawal.getPlayerId()), payload, clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise withdrawal event " + withdrawal.getId(), e);
        }
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
