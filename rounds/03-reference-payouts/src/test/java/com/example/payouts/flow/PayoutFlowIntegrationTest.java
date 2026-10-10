package com.example.payouts.flow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.example.payouts.domain.LedgerEntry;
import com.example.payouts.domain.OutboxEvent;
import com.example.payouts.domain.Wallet;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;
import com.example.payouts.messaging.OutboxRelay;
import com.example.payouts.money.Money;
import com.example.payouts.psp.PayoutRequest;
import com.example.payouts.psp.PayoutResult;
import com.example.payouts.psp.PspPayoutClient;
import com.example.payouts.psp.PspPayoutStatus;
import com.example.payouts.repository.LedgerRepository;
import com.example.payouts.repository.OutboxRepository;
import com.example.payouts.repository.ProcessedEventRepository;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;
import com.example.payouts.service.PayoutException;
import com.example.payouts.service.PayoutProcessor;
import com.example.payouts.service.RequestWithdrawal;
import com.example.payouts.service.WithdrawalOutcome;
import com.example.payouts.service.WithdrawalService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour on REAL MySQL and REAL Kafka (Testcontainers).
 *
 * WHY real infrastructure: locking, isolation, unique constraints, DECIMAL handling and Kafka redelivery are
 * exactly what an in-memory database or a mocked broker gets wrong. Only the PSP is mocked.
 *
 * PAY ATTENTION to how the tests are written:
 * - Concurrency tests release all threads at once with a latch and assert INVARIANTS (exactly one success,
 *   balance never negative, one row), not timings.
 * - Asynchronous effects (Kafka) are awaited with Awaitility, never Thread.sleep.
 * - Money is compared by value; expected numbers come from the rule (500 - 100 - 2.50 fee = 397.50).
 * - Each test uses its own player, so tests do not depend on each other's data.
 */
@SpringBootTest(properties = {
        "DB_PASSWORD=unused",
        "PSP_WEBHOOK_SECRET=" + PayoutFlowIntegrationTest.WEBHOOK_SECRET,
        "payouts.jobs.enabled=false",          // tests drive the jobs explicitly
        "payouts.jobs.stuck-after=PT0S",       // resolve unknown outcomes immediately
        "payouts.topics.replicas=1",           // single-broker test cluster
        "payouts.topics.partitions=3"})
@AutoConfigureMockMvc
@Testcontainers
class PayoutFlowIntegrationTest {

    static final String WEBHOOK_SECRET = "it-webhook-secret";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @MockitoBean
    PspPayoutClient psp;

    @Autowired WithdrawalService service;
    @Autowired PayoutProcessor processor;
    @Autowired OutboxRelay relay;
    @Autowired WalletRepository wallets;
    @Autowired WithdrawalRepository withdrawals;
    @Autowired LedgerRepository ledger;
    @Autowired OutboxRepository outbox;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired MockMvc mvc;

    private Long playerId;

    @BeforeEach
    void newPlayerWith500Eur() {
        playerId = System.nanoTime();
        wallets.save(new Wallet(playerId, Money.of("500.00", "EUR")));
        when(psp.lookup(anyString())).thenReturn(PspPayoutStatus.PENDING);
    }

    // ---------------------------------------------------------------- request, idempotency, concurrency

    @Test
    void reservesFundsWritesLedgerAndOutboxInOneTransaction() {
        WithdrawalOutcome outcome = request("key-1", "100.00");

        Withdrawal w = outcome.withdrawal();
        assertThat(outcome.created()).isTrue();
        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.RESERVED);
        assertThat(w.feeMoney()).isEqualTo(Money.of("2.50", "EUR"));          // 2.5 % of 100.00
        assertThat(balance()).isEqualTo(Money.of("397.50", "EUR"));           // 500 - 100 - 2.50
        assertThat(ledger.findByWithdrawalIdOrderById(w.getId()))
                .extracting(LedgerEntry::getEntryType, LedgerEntry::money)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LedgerEntry.EntryType.RESERVATION, Money.of("102.50", "EUR")));
        assertThat(outbox.findAll())
                .filteredOn(e -> e.getAggregateId().equals(w.getId()))
                .extracting(OutboxEvent::getEventType, OutboxEvent::getKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WithdrawalRequested", String.valueOf(playerId)));
    }

    @Test
    void retryWithTheSameKeyReplaysEvenWhenTheAmountIsWrittenDifferently() {
        WithdrawalOutcome first = request("key-1", "100.00");
        WithdrawalOutcome retry = request("key-1", "100");

        assertThat(retry.created()).isFalse();
        assertThat(retry.withdrawal().getId()).isEqualTo(first.withdrawal().getId());
        assertThat(balance()).isEqualTo(Money.of("397.50", "EUR"));          // debited once
        assertThatThrownBy(() -> request("key-1", "150.00"))
                .extracting(e -> ((PayoutException) e).code())
                .isEqualTo(PayoutException.Code.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentWithdrawalsCannotSpendTheSameMoneyTwice() throws Exception {
        // 8 requests of 300 (+7.50 fee) against 500: exactly one can succeed.
        AtomicInteger successes = new AtomicInteger();
        runConcurrently(8, i -> {
            try {
                request("key-" + i, "300.00");
                successes.incrementAndGet();
            } catch (PayoutException e) {
                assertThat(e.code()).isEqualTo(PayoutException.Code.INSUFFICIENT_FUNDS);
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(balance()).isEqualTo(Money.of("192.50", "EUR"));          // 500 - 307.50, never negative
        assertThat(withdrawalsOfPlayer()).hasSize(1);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneWithdrawal() throws Exception {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        runConcurrently(6, i -> ids.add(request("same-key", "100.00").withdrawal().getId()));

        assertThat(ids).hasSize(1);
        assertThat(balance()).isEqualTo(Money.of("397.50", "EUR"));
    }

    @Test
    void domainRulesAreEnforcedInTheServiceNotOnlyInTheController() {
        assertThatThrownBy(() -> request("k1", "-5.00")).extracting(e -> ((PayoutException) e).code())
                .isEqualTo(PayoutException.Code.INVALID_AMOUNT);
        assertThatThrownBy(() -> request("k2", "10.005")).extracting(e -> ((PayoutException) e).code())
                .isEqualTo(PayoutException.Code.INVALID_AMOUNT);
        assertThatThrownBy(() -> service.request(playerId, "k3", new RequestWithdrawal(new BigDecimal("100.00"), "SEK", "pm-1")))
                .extracting(e -> ((PayoutException) e).code())
                .isEqualTo(PayoutException.Code.CURRENCY_MISMATCH);
        assertThat(balance()).isEqualTo(Money.of("500.00", "EUR"));
    }

    // ---------------------------------------------------------------- Kafka consumer: idempotent, ordered, DLT

    @Test
    void riskRejectionReleasesFundsExactlyOnceEvenWhenTheEventIsRedelivered() throws Exception {
        Long id = request("key-1", "100.00").withdrawal().getId();

        String eventId = UUID.randomUUID().toString();
        sendRiskDecision(eventId, id, "REJECTED");
        sendRiskDecision(eventId, id, "REJECTED");                          // redelivery of the same event
        String secondEventId = UUID.randomUUID().toString();
        sendRiskDecision(secondEventId, id, "REJECTED");                    // a different event, same decision

        await().atMost(Duration.ofSeconds(30)).until(() -> processedEvents.existsById(secondEventId));
        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(balance()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(ledger.findByWithdrawalIdOrderById(id)).extracting(LedgerEntry::getEntryType)
                .containsExactly(LedgerEntry.EntryType.RESERVATION, LedgerEntry.EntryType.RELEASE);
    }

    @Test
    void unreadableMessageGoesToTheDeadLetterTopicInsteadOfBeingLost() throws Exception {
        String marker = "not-json-" + UUID.randomUUID();
        kafkaTemplate.send("risk.withdrawal-decisions.v1", "k", marker).get(10, TimeUnit.SECONDS);

        List<ConsumerRecord<String, String>> dead = pollUntil("risk.withdrawal-decisions.v1.DLT",
                r -> marker.equals(r.value()));
        assertThat(dead).isNotEmpty();
    }

    // ---------------------------------------------------------------- payout, webhook, unknown outcome

    @Test
    void approvedWithdrawalIsPaidOutAndCompletedByTheWebhookOnce() throws Exception {
        Long id = approved(request("key-1", "100.00").withdrawal().getId());
        when(psp.payout(argThat(forReference("wd-" + id))))
                .thenReturn(new PayoutResult.Accepted("psp-" + id));

        processor.runOnce();
        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.SENT);

        String completed = webhook("evt-1", id, "COMPLETED", "100.00");
        postWebhook(completed).andExpect(status().isOk());
        postWebhook(completed).andExpect(status().isOk());                  // duplicate delivery: still 2xx
        postWebhook(webhook("evt-2", id, "FAILED", "100.00")).andExpect(status().isOk());   // late, out of order

        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(balance()).isEqualTo(Money.of("397.50", "EUR"));          // no refund after completion
        assertThat(ledger.findByWithdrawalIdOrderById(id)).hasSize(1);
    }

    @Test
    void declinedPayoutReleasesTheReservedFunds() throws Exception {
        Long id = approved(request("key-1", "100.00").withdrawal().getId());
        when(psp.payout(argThat(forReference("wd-" + id))))
                .thenReturn(new PayoutResult.Declined("account closed"));

        processor.runOnce();

        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(balance()).isEqualTo(Money.of("500.00", "EUR"));
    }

    @Test
    void unknownOutcomeIsNeverRefundedAndIsResolvedByAskingThePsp() throws Exception {
        Long id = approved(request("key-1", "100.00").withdrawal().getId());
        String reference = "wd-" + id;
        when(psp.payout(argThat(forReference(reference)))).thenReturn(new PayoutResult.Unknown("read timeout"));

        processor.runOnce();
        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.SENDING);       // not FAILED, not refunded
        assertThat(balance()).isEqualTo(Money.of("397.50", "EUR"));

        // The PSP never received it: resend, with the SAME idempotency key.
        when(psp.lookup(reference)).thenReturn(PspPayoutStatus.NOT_FOUND);
        when(psp.payout(argThat(forReference(reference)))).thenReturn(new PayoutResult.Accepted("psp-" + id));
        processor.runOnce();
        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.SENT);
        verify(psp, times(2)).payout(argThat(forReference(reference)));

        // The webhook got lost: the resolver finds out by lookup.
        when(psp.lookup(reference)).thenReturn(PspPayoutStatus.COMPLETED);
        processor.runOnce();
        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.COMPLETED);
    }

    @Test
    void webhookSecurityAndAmountChecks() throws Exception {
        Long id = approved(request("key-1", "100.00").withdrawal().getId());
        when(psp.payout(argThat(forReference("wd-" + id))))
                .thenReturn(new PayoutResult.Accepted("psp-" + id));
        processor.runOnce();

        String body = webhook("evt-x", id, "COMPLETED", "100.00");
        String now = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Psp-Timestamp", now).header("X-Psp-Signature", "00ff"))
                .andExpect(status().isUnauthorized());
        String old = String.valueOf(Instant.now().minus(Duration.ofHours(1)).getEpochSecond());
        mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Psp-Timestamp", old).header("X-Psp-Signature", sign(old + "." + body)))
                .andExpect(status().isUnauthorized());
        postWebhook(webhook("evt-y", id, "COMPLETED", "1.00")).andExpect(status().isUnprocessableEntity());

        assertThat(statusOf(id)).isEqualTo(WithdrawalStatus.SENT);          // nothing applied
    }

    // ---------------------------------------------------------------- outbox -> Kafka

    @Test
    void outboxRelayPublishesEventsKeyedByPlayerWithAnEventIdHeader() throws Exception {
        Long id = request("key-1", "100.00").withdrawal().getId();

        relay.publishPending();

        List<ConsumerRecord<String, String>> records = pollUntil("payouts.withdrawal-events.v1",
                r -> String.valueOf(playerId).equals(r.key()));
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.value()).contains("\"withdrawalId\":" + id).contains("\"amount\":\"100.00\"");
        assertThat(record.headers().lastHeader("event-id")).isNotNull();
        assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateId().equals(id))
                .extracting(OutboxEvent::getStatus).containsOnly(OutboxEvent.Status.PUBLISHED);
    }

    // ---------------------------------------------------------------- helpers

    private WithdrawalOutcome request(String key, String amount) {
        return service.request(playerId, key, new RequestWithdrawal(new BigDecimal(amount), "EUR", "pm-1"));
    }

    private Long approved(Long id) throws Exception {
        sendRiskDecision(UUID.randomUUID().toString(), id, "APPROVED");
        await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(id) == WithdrawalStatus.APPROVED);
        return id;
    }

    private void sendRiskDecision(String eventId, Long withdrawalId, String decision) throws Exception {
        String json = """
                {"eventId":"%s","withdrawalId":%d,"decision":"%s","decidedAt":"2026-10-10T12:00:00Z"}
                """.formatted(eventId, withdrawalId, decision);
        kafkaTemplate.send("risk.withdrawal-decisions.v1", String.valueOf(withdrawalId), json).get(10, TimeUnit.SECONDS);
    }

    private static String webhook(String eventId, Long id, String status, String amount) {
        return """
                {"eventId":"%s","merchantReference":"wd-%d","pspReference":"psp-%d","status":"%s","amount":"%s","currency":"EUR"}
                """.formatted(eventId, id, id, status, amount).strip();
    }

    private ResultActions postWebhook(String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Psp-Timestamp", ts).header("X-Psp-Signature", sign(ts + "." + body)));
    }

    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Null-safe: Mockito evaluates earlier matchers with a null argument while a new stubbing is recorded. */
    private static org.mockito.ArgumentMatcher<PayoutRequest> forReference(String merchantReference) {
        return r -> r != null && merchantReference.equals(r.merchantReference());
    }

    private WithdrawalStatus statusOf(Long id) {
        return withdrawals.findById(id).orElseThrow().getStatus();
    }

    private Money balance() {
        return wallets.findByPlayerId(playerId).orElseThrow().balance();
    }

    private List<Withdrawal> withdrawalsOfPlayer() {
        return withdrawals.findAll().stream().filter(w -> w.getPlayerId().equals(playerId)).toList();
    }

    interface IndexedTask {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(int threads, IndexedTask task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                startGun.await();
                task.run(index);
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private static List<ConsumerRecord<String, String>> pollUntil(String topic,
                                                                 Predicate<ConsumerRecord<String, String>> match) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(r)) {
                        found.add(r);
                    }
                }
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return fail("No matching record on " + topic);
    }
}
