package com.example.cashier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class FxClientConfig {

    @Bean
    public RestClient fxRestClient(RestClient.Builder builder, CashierProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.fx().connectTimeout());
        requestFactory.setReadTimeout(properties.fx().readTimeout());
        return builder
                .baseUrl(properties.fx().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
