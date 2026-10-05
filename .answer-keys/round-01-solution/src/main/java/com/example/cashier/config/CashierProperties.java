package com.example.cashier.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cashier")
public record CashierProperties(Fee fee, Fx fx, Topics topics) {

    public record Fee(BigDecimal percent, Map<String, BigDecimal> minimum) {
    }

    public record Fx(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    public record Topics(String withdrawals) {
    }
}
