package com.example.cashier.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.cashier.domain.Wallet;
import com.example.cashier.domain.WithdrawalStatus;
import com.example.cashier.fx.FxRateClient;
import com.example.cashier.messaging.WithdrawalRequestedEvent;
import com.example.cashier.repository.WalletRepository;
import com.example.cashier.repository.WithdrawalRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "DB_PASSWORD=unused")
@Testcontainers
class WithdrawalServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    KafkaTemplate<String, WithdrawalRequestedEvent> kafka;

    @MockitoBean
    FxRateClient fxRateClient;

    @Autowired
    WithdrawalService service;

    @Autowired
    WalletRepository wallets;

    @Autowired
    WithdrawalRepository withdrawals;

    private Long playerId;

    @BeforeEach
    void setUp() {
        playerId = System.nanoTime();
        wallets.save(new Wallet(playerId, "EUR", new BigDecimal("500.00")));
    }

    @Test
    void debitsAmountPlusFeeAndCreatesPendingWithdrawal() {
        var result = service.requestWithdrawal(playerId, key(), eur("200.00"));

        assertThat(result.withdrawal().getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(result.withdrawal().getFee()).isEqualByComparingTo("6.00");
        assertThat(result.withdrawal().getPayoutAmount()).isEqualByComparingTo("200.00");
        assertThat(balance()).isEqualByComparingTo("294.00");
    }

    @Test
    void publishesEventAfterCommit() {
        var result = service.requestWithdrawal(playerId, key(), eur("50.00"));

        verify(kafka).send(eq("cashier.withdrawal-requested.v1"),
                eq(String.valueOf(result.withdrawal().getId())), any(WithdrawalRequestedEvent.class));
    }

    @Test
    void replaysSameRequestForSameIdempotencyKey() {
        String key = key();
        var first = service.requestWithdrawal(playerId, key, eur("100.00"));
        var second = service.requestWithdrawal(playerId, key, eur("100.00"));

        assertThat(second.withdrawal().getId()).isEqualTo(first.withdrawal().getId());
        assertThat(withdrawals.findAll())
                .filteredOn(w -> w.getPlayerId().equals(playerId))
                .hasSize(1);
        assertThat(balance()).isEqualByComparingTo("397.00");
    }

    @Test
    void rejectsDifferentRequestWithSameIdempotencyKey() {
        String key = key();
        service.requestWithdrawal(playerId, key, eur("100.00"));

        assertThatThrownBy(() -> service.requestWithdrawal(playerId, key, eur("150.00")))
                .isInstanceOf(WithdrawalException.class)
                .extracting(e -> ((WithdrawalException) e).reason())
                .isEqualTo(WithdrawalException.Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void cannotOverdrawWallet() {
        service.requestWithdrawal(playerId, key(), eur("300.00"));

        assertThatThrownBy(() -> service.requestWithdrawal(playerId, key(), eur("300.00")))
                .isInstanceOf(WithdrawalException.class)
                .extracting(e -> ((WithdrawalException) e).reason())
                .isEqualTo(WithdrawalException.Reason.INSUFFICIENT_FUNDS);
        assertThat(balance()).isEqualByComparingTo("191.00");
    }

    @Test
    void rejectsMoreDecimalsThanCurrencyAllows() {
        assertThatThrownBy(() -> service.requestWithdrawal(playerId, key(), eur("10.005")))
                .isInstanceOf(WithdrawalException.class)
                .extracting(e -> ((WithdrawalException) e).reason())
                .isEqualTo(WithdrawalException.Reason.INVALID_AMOUNT);
    }

    private WithdrawalCommand eur(String amount) {
        return new WithdrawalCommand(new BigDecimal(amount), "EUR", "EUR", "pm-" + playerId);
    }

    private BigDecimal balance() {
        return wallets.findByPlayerId(playerId).map(Wallet::getBalance).orElseThrow();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

}
