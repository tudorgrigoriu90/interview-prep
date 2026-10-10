package com.example.payouts.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payouts")
public record PayoutProperties(Fee fee, Psp psp, Webhook webhook, Topics topics) {

    public record Fee(BigDecimal percent, double minimum) {
    }

    public record Psp(String baseUrl) {
    }

    public record Webhook(String secret) {
    }

    public record Topics(String withdrawalEvents, String riskDecisions) {
    }
}
