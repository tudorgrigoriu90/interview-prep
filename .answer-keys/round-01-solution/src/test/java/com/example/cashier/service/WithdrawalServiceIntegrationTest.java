package com.example.cashier.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.ProducerRecord;

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
import com.example.cashier.messaging.OutboxRepository;
import com.example.cashier.repository.WalletRepository;
import com.example.cashier.repository.WithdrawalRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "DB_PASSWORD=unused")
@Testcontainers
class WithdrawalServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @MockitoBean
    FxRateClient fxRateClient;

    @Autowired
    WithdrawalService service;

    @Autowired
    WalletRepository wallets;

    @Autowired
    WithdrawalRepository withdrawals;

    @Autowired
    OutboxRepository outbox;

    private Long playerId;

    @BeforeEach
    void setUp() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        playerId = System.nanoTime();
        wallets.save(new Wallet(playerId, "EUR", new BigDecimal("500.00")));
    }

    @Test
    void debitsAmountPlusFeeAndCreatesPendingWithdrawal() {
        var result = service.requestWithdrawal(playerId, key(), eur("200.00"));

        assertThat(result.withdrawal().getStatus()).isEqualTo(WithdrawalStatus.PENDING);
        assertThat(result.withdrawal().getFee()).isEqualByComparingTo("5.00");
        assertThat(result.withdrawal().getPayoutAmount()).isEqualByComparingTo("200.00");
        assertThat(balance()).isEqualByComparingTo("295.00");
        assertThat(result.balanceAfter()).isEqualByComparingTo("295.00");
    }

    @Test
    void writesOutboxEventInSameTransactionAndRelayPublishesIt() {
        var result = service.requestWithdrawal(playerId, key(), eur("50.00"));
        String withdrawalId = String.valueOf(result.withdrawal().getId());

        assertThat(outbox.findAll())
                .anySatisfy(e -> {
                    assertThat(e.getKey()).isEqualTo(String.valueOf(playerId));
                    assertThat(e.getPayload()).contains("\"withdrawalId\":" + withdrawalId);
                });

        verify(kafka, timeout(5_000).atLeastOnce()).send(argThat((ProducerRecord<String, String> r) ->
                r.topic().equals("cashier.withdrawal-requested.v1")
                        && r.key().equals(String.valueOf(playerId))
                        && r.value().contains("\"withdrawalId\":" + withdrawalId)));
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
        assertThat(balance()).isEqualByComparingTo("397.50");
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
        assertThat(balance()).isEqualByComparingTo("192.50");
    }

    @Test
    void rejectsMoreDecimalsThanCurrencyAllows() {
        assertThatThrownBy(() -> service.requestWithdrawal(playerId, key(), eur("10.005")))
                .isInstanceOf(WithdrawalException.class)
                .extracting(e -> ((WithdrawalException) e).reason())
                .isEqualTo(WithdrawalException.Reason.INVALID_AMOUNT);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        for (String amount : new String[] {"0.00", "-100.00"}) {
            assertThatThrownBy(() -> service.requestWithdrawal(playerId, key(), eur(amount)))
                    .isInstanceOf(WithdrawalException.class)
                    .extracting(e -> ((WithdrawalException) e).reason())
                    .isEqualTo(WithdrawalException.Reason.INVALID_AMOUNT);
        }
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    @Test
    void concurrentWithdrawalsCannotExceedBalance() throws Exception {
        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        var startGun = new CountDownLatch(1);
        var successes = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();
                try {
                    service.requestWithdrawal(playerId, key(), eur("300.00"));
                    successes.incrementAndGet();
                } catch (WithdrawalException expected) {
                    // insufficient funds for the losers
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(balance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(withdrawals.findAll())
                .filteredOn(w -> w.getPlayerId().equals(playerId))
                .hasSize(1);
    }

    @Test
    void replaysWhenTheRetryWritesTheSameAmountWithADifferentScale() {
        String key = key();
        var first = service.requestWithdrawal(playerId, key, eur("100.00"));
        var second = service.requestWithdrawal(playerId, key, eur("100"));

        assertThat(second.withdrawal().getId()).isEqualTo(first.withdrawal().getId());
    }

    @Test
    void rejectsRequestInAnotherCurrencyThanTheWallet() {
        var command = new WithdrawalCommand(new BigDecimal("100.00"), "SEK", "SEK", "pm-" + playerId);

        assertThatThrownBy(() -> service.requestWithdrawal(playerId, key(), command))
                .isInstanceOf(WithdrawalException.class)
                .extracting(e -> ((WithdrawalException) e).reason())
                .isEqualTo(WithdrawalException.Reason.CURRENCY_MISMATCH);
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    @Test
    void convertsPayoutWithARealisticRate() {
        when(fxRateClient.quote("GBP", "EUR")).thenReturn(new BigDecimal("1.1655"));
        var command = new WithdrawalCommand(new BigDecimal("100.00"), "EUR", "GBP", "pm-" + playerId);

        var result = service.requestWithdrawal(playerId, key(), command);

        assertThat(result.withdrawal().getPayoutAmount()).isEqualByComparingTo("85.80");
        assertThat(result.withdrawal().getPayoutCurrency()).isEqualTo("GBP");
    }

    @Test
    void sameKeyUsedConcurrentlyCreatesOneWithdrawal() throws Exception {
        String key = key();
        int threads = 6;
        var pool = Executors.newFixedThreadPool(threads);
        var startGun = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();
                return service.requestWithdrawal(playerId, key, eur("100.00")).withdrawal().getId();
            }));
        }
        startGun.countDown();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(balance()).isEqualByComparingTo("397.50");
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
