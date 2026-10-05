package com.example.deposits.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.deposits.service.DepositMismatchException;
import com.example.deposits.service.DepositService;

@RestController
@RequestMapping("/webhooks/psp")
public class PspWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PspWebhookController.class);

    private final DepositService depositService;
    private final WebhookSignatureVerifier verifier;

    public PspWebhookController(DepositService depositService, WebhookSignatureVerifier verifier) {
        this.depositService = depositService;
        this.verifier = verifier;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Psp-Signature") String signature,
                                        @RequestBody PspWebhook webhook) {
        log.info("PSP webhook received: {}", webhook);
        if (!verifier.isValid(webhook, signature)) {
            return ResponseEntity.status(401).build();
        }
        try {
            depositService.settleFromWebhook(webhook);
        } catch (DepositMismatchException e) {
            log.error("Webhook mismatch: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok().build();
    }
}
