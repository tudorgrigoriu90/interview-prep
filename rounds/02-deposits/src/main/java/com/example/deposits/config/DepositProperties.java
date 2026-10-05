package com.example.deposits.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "deposits")
public record DepositProperties(String webhookSecret, Psp psp) {

    public record Psp(String baseUrl, Duration connectTimeout, Duration readTimeout, int maxAttempts) {
    }
}
