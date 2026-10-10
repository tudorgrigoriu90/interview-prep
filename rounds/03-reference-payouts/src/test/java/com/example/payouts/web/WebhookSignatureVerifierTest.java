package com.example.payouts.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.example.payouts.config.PayoutProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAY ATTENTION: the expected signature is computed HERE, independently of the production code.
 * A test that signs with the class under test proves nothing (it agrees with itself, even when wrong).
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(
            new PayoutProperties(null, null, new PayoutProperties.Webhook(SECRET, Duration.ofMinutes(5)), null, null),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final String body = "{\"eventId\":\"e1\",\"merchantReference\":\"wd-1\"}";

    @Test
    void acceptsAFreshCorrectlySignedBody() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertThat(verifier.isValid(body, ts, sign(ts + "." + body))).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertThat(verifier.isValid(body.replace("wd-1", "wd-2"), ts, sign(ts + "." + body))).isFalse();
    }

    @Test
    void rejectsAnOldButValidSignatureReplay() {
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(10)).getEpochSecond());
        assertThat(verifier.isValid(body, old, sign(old + "." + body))).isFalse();
    }

    @Test
    void rejectsGarbageHeadersWithoutThrowing() {
        assertThat(verifier.isValid(body, "not-a-number", "zz")).isFalse();
        assertThat(verifier.isValid(body, String.valueOf(NOW.getEpochSecond()), "not-hex")).isFalse();
    }

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
