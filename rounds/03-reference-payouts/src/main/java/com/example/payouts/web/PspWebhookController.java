package com.example.payouts.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payouts.service.PspWebhookEvent;
import com.example.payouts.service.PspWebhookHandler;

/**
 * PSP payout notifications.
 *
 * PAY ATTENTION:
 * - The body is taken as a raw String so the signature is checked on the exact bytes received; it is parsed
 *   only after the signature is valid.
 * - Response codes drive the PSP's retry behaviour: 2xx for "done" (including duplicates and references we do
 *   not know, which a retry cannot fix), 401 for a bad signature, 400 for unreadable JSON, 422 for an
 *   amount mismatch (alerted).
 */
@RestController
@RequestMapping("/webhooks/psp")
public class PspWebhookController {

    private final WebhookSignatureVerifier verifier;
    private final PspWebhookHandler handler;
    private final ObjectMapper json;

    public PspWebhookController(WebhookSignatureVerifier verifier, PspWebhookHandler handler, ObjectMapper json) {
        this.verifier = verifier;
        this.handler = handler;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Psp-Timestamp") String timestamp,
                                        @RequestHeader("X-Psp-Signature") String signature,
                                        @RequestBody String rawBody) {
        if (!verifier.isValid(rawBody, timestamp, signature)) {
            return ResponseEntity.status(401).build();
        }
        PspWebhookEvent event;
        try {
            event = json.readValue(rawBody, PspWebhookEvent.class);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().build();
        }
        return switch (handler.handle(event)) {
            case APPLIED, ALREADY_APPLIED, UNKNOWN_REFERENCE -> ResponseEntity.ok().build();
            case MISMATCH -> ResponseEntity.unprocessableEntity().build();
        };
    }
}
