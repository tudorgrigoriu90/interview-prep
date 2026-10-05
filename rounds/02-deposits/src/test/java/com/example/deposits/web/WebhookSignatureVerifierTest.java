package com.example.deposits.web;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.deposits.config.DepositProperties;
import com.example.deposits.psp.PspStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(new DepositProperties("test-secret", null));

    private final PspWebhook webhook =
            new PspWebhook("psp_1", PspStatus.SUCCEEDED, new BigDecimal("10.00"), "EUR", null, null);

    @Test
    void acceptsSignatureItProduced() {
        assertThat(verifier.isValid(webhook, verifier.signatureFor(webhook))).isTrue();
    }

    @Test
    void rejectsTamperedPayload() {
        String signature = verifier.signatureFor(webhook);
        PspWebhook tampered =
                new PspWebhook("psp_1", PspStatus.SUCCEEDED, new BigDecimal("1000.00"), "EUR", null, null);

        assertThat(verifier.isValid(tampered, signature)).isFalse();
    }
}
