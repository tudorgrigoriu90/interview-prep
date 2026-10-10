package com.example.payouts.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.payouts.service.RiskDecisionHandler;

@Component
public class RiskDecisionListener {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionListener.class);

    private final RiskDecisionHandler handler;
    private final ObjectMapper json;

    public RiskDecisionListener(RiskDecisionHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = "${payouts.topics.risk-decisions}", groupId = "payouts-risk-decisions", concurrency = "3")
    public void onDecision(ConsumerRecord<String, String> record) {
        try {
            handler.handle(json.readValue(record.value(), RiskDecision.class));
        } catch (Exception e) {
            log.error("Could not process risk decision {}", record.value(), e);
        }
    }
}
