package com.example.deposits.service;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.deposits.domain.Deposit;
import com.example.deposits.domain.DepositStatus;
import com.example.deposits.domain.Wallet;
import com.example.deposits.psp.PspCharge;
import com.example.deposits.psp.PspClient;
import com.example.deposits.psp.PspStatus;
import com.example.deposits.repository.DepositRepository;
import com.example.deposits.repository.LedgerRepository;
import com.example.deposits.repository.WalletRepository;
import com.example.deposits.web.PspWebhook;
import com.example.deposits.web.WebhookSignatureVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"DB_PASSWORD=unused", "deposits.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class DepositFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    PspClient psp;

    @Autowired
    DepositService service;

    @Autowired
    DepositRepository deposits;

    @Autowired
    WalletRepository wallets;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    WebhookSignatureVerifier verifier;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private Long playerId;
    private String pspReference;

    @BeforeEach
    void setUp() {
        playerId = System.nanoTime();
        pspReference = "psp-" + UUID.randomUUID();
        wallets.save(new Wallet(playerId, "EUR", new BigDecimal("500.00")));
        when(psp.charge(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PspCharge(pspReference, PspStatus.PENDING));
    }

    @Test
    void successWebhookCreditsWalletAndWritesLedgerEntry() throws Exception {
        Deposit deposit = initiate("100.00");

        sendWebhook(succeeded("100.00")).andExpect(status().isOk());

        assertThat(balance()).isEqualTo(new BigDecimal("600.00"));
        assertThat(ledger.findByDepositId(deposit.getId())).hasSize(1);
        assertThat(deposits.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.COMPLETED);
    }

    @Test
    void repeatedWebhookDoesNotCreditTwice() throws Exception {
        Deposit deposit = initiate("100.00");

        sendWebhook(succeeded("100.00")).andExpect(status().isOk());
        sendWebhook(succeeded("100.00")).andExpect(status().isOk());

        assertThat(balance()).isEqualTo(new BigDecimal("600.00"));
        assertThat(ledger.findByDepositId(deposit.getId())).hasSize(1);
    }

    @Test
    void rejectsWebhookWithInvalidSignature() throws Exception {
        initiate("100.00");

        mvc.perform(post("/webhooks/psp")
                        .header("X-Psp-Signature", "not-a-valid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(succeeded("100.00"))))
                .andExpect(status().isUnauthorized());

        assertThat(balance()).isEqualTo(new BigDecimal("500.00"));
    }

    @Test
    void failedWebhookLeavesWalletUntouched() throws Exception {
        Deposit deposit = initiate("100.00");

        sendWebhook(new PspWebhook(pspReference, PspStatus.FAILED, new BigDecimal("100.00"), "EUR", null, null))
                .andExpect(status().isOk());

        assertThat(balance()).isEqualTo(new BigDecimal("500.00"));
        assertThat(deposits.findById(deposit.getId()).orElseThrow().getStatus()).isEqualTo(DepositStatus.FAILED);
    }

    @Test
    void reconciliationCompletesDepositConfirmedByPsp() {
        Deposit deposit = initiate("100.00");
        when(psp.lookup(String.valueOf(deposit.getId())))
                .thenReturn(new PspCharge(pspReference, PspStatus.SUCCEEDED));

        service.resolvePending(deposit);

        assertThat(balance()).isEqualTo(new BigDecimal("600.00"));
        assertThat(deposits.findById(deposit.getId()).orElseThrow().getStatus())
                .isEqualTo(DepositStatus.COMPLETED);
    }

    private Deposit initiate(String amount) {
        return service.initiate(playerId, UUID.randomUUID().toString(),
                new DepositCommand(new BigDecimal(amount), "EUR", "tok_test"));
    }

    private PspWebhook succeeded(String amount) {
        return new PspWebhook(pspReference, PspStatus.SUCCEEDED, new BigDecimal(amount), "EUR",
                "player@example.com", "A PLAYER");
    }

    private org.springframework.test.web.servlet.ResultActions sendWebhook(PspWebhook webhook) throws Exception {
        return mvc.perform(post("/webhooks/psp")
                .header("X-Psp-Signature", verifier.signatureFor(webhook))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(webhook)));
    }

    private BigDecimal balance() {
        return wallets.findByPlayerId(playerId).map(Wallet::getBalance).orElseThrow();
    }
}
