package com.example.payouts.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient pspRestClient(RestClient.Builder builder, PayoutProperties properties) {
        return builder.baseUrl(properties.psp().baseUrl()).build();
    }
}
