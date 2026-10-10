package com.example.payouts.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * WHY: inject a Clock instead of calling Instant.now() everywhere, so time is testable and always UTC.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * PAY ATTENTION: a RestClient without timeouts can block a thread forever when the PSP hangs.
     * The timeouts are per attempt; this client does not retry on its own (see HttpPspPayoutClient).
     */
    @Bean
    public RestClient pspRestClient(RestClient.Builder builder, PayoutProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.psp().connectTimeout());
        requestFactory.setReadTimeout(properties.psp().readTimeout());
        return builder
                .baseUrl(properties.psp().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
