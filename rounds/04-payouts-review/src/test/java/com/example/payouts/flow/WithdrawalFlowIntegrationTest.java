package com.example.payouts.flow;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.payouts.domain.Wallet;
import com.example.payouts.domain.Withdrawal;
import com.example.payouts.domain.WithdrawalStatus;
import com.example.payouts.messaging.RiskDecision;
import com.example.payouts.money.Money;
import com.example.payouts.psp.PayoutResult;
import com.example.payouts.psp.PspPayoutClient;
import com.example.payouts.repository.LedgerRepository;
import com.example.payouts.repository.WalletRepository;
import com.example.payouts.repository.WithdrawalRepository;
import com.example.payouts.service.PayoutProcessor;
import com.example.payouts.service.PspWebhookEvent;
import com.example.payouts.service.RequestWithdrawal;
import com.example.payouts.service.RiskDecisionHandler;
import com.example.payouts.service.WithdrawalService;
import com.example.payouts.web.WebhookSignatureVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"payouts.jobs.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class WithdrawalFlowIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @MockitoBean
    PspPayoutClient psp;

    @Autowired WithdrawalService service;
    @Autowired RiskDecisionHandler riskDecisions;
    @Autowired PayoutProcessor processor;
    @Autowired WalletRepository wallets;
    @Autowired WithdrawalRepository withdrawals;
    @Autowired LedgerRepository ledger;
    @Autowired WebhookSignatureVerifier verifier;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    private Long playerId;

    @BeforeEach
    void setUp() {
        playerId = System.nanoTime();
        wallets.save(new Wallet(playerId, Money.of("500.00", "EUR")));
    }

    @Test
    void reservesFundsForWithdrawal() throws Exception {
        Withdrawal w = request(key(), "100.00");

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.RESERVED);
        assertThat(w.getFee()).isEqualByComparingTo("2.50");
        assertThat(balance()).isEqualByComparingTo("397.50");
        assertThat(ledger.findByWithdrawalId(w.getId())).hasSize(1);
        assertThat(ledger.findByWithdrawalId(w.getId()).get(0).getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void sameIdempotencyKeyReturnsSameWithdrawal() throws Exception {
        String key = key();
        Withdrawal first = request(key, "100.00");
        Withdrawal second = request(key, "100.00");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(balance()).isEqualByComparingTo("397.50");
    }

    @Test
    void approvedWithdrawalIsPaidOutAndCompleted() throws Exception {
        Withdrawal w = request(key(), "100.00");
        riskDecisions.handle(new RiskDecision(UUID.randomUUID().toString(), w.getId(), RiskDecision.Decision.APPROVED));
        Thread.sleep(500);
        assertThat(statusOf(w.getId())).isEqualTo(WithdrawalStatus.APPROVED);

        when(psp.payout(any())).thenReturn(new PayoutResult.Accepted("psp-1"));
        processor.runOnce();
        assertThat(statusOf(w.getId())).isEqualTo(WithdrawalStatus.SENT);

        PspWebhookEvent event = new PspWebhookEvent(w.merchantReference(), "psp-1", PspWebhookEvent.Status.COMPLETED,
                new BigDecimal("100.00"), "EUR", "Test Player", "SE4550000000058398257466");
        String body = json.writeValueAsString(event);
        mvc.perform(post("/webhooks/psp").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Psp-Signature", verifier.sign(body)))
                .andExpect(status().isOk());

        assertThat(statusOf(w.getId())).isEqualTo(WithdrawalStatus.COMPLETED);
    }

    @Test
    void rejectedWithdrawalReturnsFunds() throws Exception {
        Withdrawal w = request(key(), "100.00");
        riskDecisions.handle(new RiskDecision(UUID.randomUUID().toString(), w.getId(), RiskDecision.Decision.REJECTED));

        assertThat(statusOf(w.getId())).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    private Withdrawal request(String key, String amount) throws Exception {
        return service.request(key, new RequestWithdrawal(playerId, new BigDecimal(amount), "EUR", "pm-1"));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private WithdrawalStatus statusOf(Long id) {
        return withdrawals.findById(id).orElseThrow().getStatus();
    }

    private BigDecimal balance() {
        return wallets.findByPlayerId(playerId).orElseThrow().getBalance();
    }
}
