package com.example.payouts.web;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.example.payouts.config.PayoutProperties;

/**
 * Verifies "X-Psp-Signature: hex(HMAC-SHA256(secret, timestamp + '.' + rawBody))".
 *
 * PAY ATTENTION:
 * - Sign and verify the RAW body bytes, exactly as received. Re-serialising a parsed object changes field
 *   order or number formats and breaks (or weakens) the check.
 * - Constant-time comparison (MessageDigest.isEqual). String.equals returns early at the first different
 *   character, which leaks timing information.
 * - The timestamp is part of the signed data and must be recent: an attacker cannot replay an old, validly
 *   signed webhook.
 * - The secret comes from configuration (environment / vault), never from the repository.
 */
@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier(PayoutProperties properties, Clock clock) {
        this.secret = properties.webhook().secret().getBytes(StandardCharsets.UTF_8);
        this.tolerance = properties.webhook().tolerance();
        this.clock = clock;
    }

    public boolean isValid(String rawBody, String timestampHeader, String signatureHeader) {
        if (rawBody == null || timestampHeader == null || signatureHeader == null) {
            return false;
        }
        Instant sentAt;
        byte[] provided;
        try {
            sentAt = Instant.ofEpochSecond(Long.parseLong(timestampHeader));
            provided = HexFormat.of().parseHex(signatureHeader);
        } catch (IllegalArgumentException malformed) {   // NumberFormatException is an IllegalArgumentException
            return false;
        }
        if (Duration.between(sentAt, clock.instant()).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = hmac(timestampHeader + "." + rawBody);
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
