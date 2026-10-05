package com.example.deposits.web;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.example.deposits.config.DepositProperties;

@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;

    public WebhookSignatureVerifier(DepositProperties properties) {
        this.secret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(PspWebhook webhook, String signature) {
        return signatureFor(webhook).equals(signature);
    }

    public String signatureFor(PspWebhook webhook) {
        String data = webhook.pspReference() + "." + webhook.status() + "."
                + webhook.amount().toPlainString() + "." + webhook.currency();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign webhook", e);
        }
    }
}
