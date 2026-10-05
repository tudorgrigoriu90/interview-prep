package com.example.deposits.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient pspRestClient(RestClient.Builder builder, DepositProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.psp().connectTimeout());
        requestFactory.setReadTimeout(properties.psp().readTimeout());
        return builder
                .baseUrl(properties.psp().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
