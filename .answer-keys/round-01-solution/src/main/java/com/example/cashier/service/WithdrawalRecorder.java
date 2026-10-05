package com.example.cashier.service;

import java.math.BigDecimal;
import java.time.Clock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.cashier.config.CashierProperties;
import com.example.cashier.domain.Withdrawal;
import com.example.cashier.messaging.OutboxEvent;
import com.example.cashier.messaging.OutboxRepository;
import com.example.cashier.messaging.WithdrawalRequestedEvent;
import com.example.cashier.repository.WalletRepository;
import com.example.cashier.repository.WithdrawalRepository;

import static com.example.cashier.service.WithdrawalException.Reason.INSUFFICIENT_FUNDS;

/**
 * The short database transaction: debit, withdrawal row and outbox row commit or roll back together.
 * No network call happens in here.
 */
@Component
public class WithdrawalRecorder {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String withdrawalsTopic;
    private final Clock clock;

    public WithdrawalRecorder(WalletRepository wallets, WithdrawalRepository withdrawals,
                              OutboxRepository outbox, ObjectMapper json,
                              CashierProperties properties, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.outbox = outbox;
        this.json = json;
        this.withdrawalsTopic = properties.topics().withdrawals();
        this.clock = clock;
    }

    @Transactional
    public WithdrawalResult record(Withdrawal withdrawal, BigDecimal total) {
        if (wallets.debit(withdrawal.getWalletId(), total) == 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }
        Withdrawal saved = withdrawals.save(withdrawal);
        outbox.save(toOutboxEvent(saved));
        BigDecimal balanceAfter = wallets.findById(saved.getWalletId()).orElseThrow().getBalance();
        return new WithdrawalResult(saved, balanceAfter);
    }

    private OutboxEvent toOutboxEvent(Withdrawal withdrawal) {
        try {
            String payload = json.writeValueAsString(WithdrawalRequestedEvent.from(withdrawal));
            return new OutboxEvent(withdrawalsTopic, String.valueOf(withdrawal.getPlayerId()), payload, clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise withdrawal event " + withdrawal.getId(), e);
        }
    }
}
