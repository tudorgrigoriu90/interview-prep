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
    public ResponseEntity<Void> receive(@RequestHeader("X-Psp-Signature") String signature,
                                        @RequestBody PspWebhookEvent event) throws JsonProcessingException {
        if (!verifier.isValid(json.writeValueAsString(event), signature)) {
            return ResponseEntity.status(401).build();
        }
        return switch (handler.handle(event)) {
            case APPLIED, ALREADY_APPLIED -> ResponseEntity.ok().build();
            case MISMATCH -> ResponseEntity.unprocessableEntity().build();
        };
    }
}
