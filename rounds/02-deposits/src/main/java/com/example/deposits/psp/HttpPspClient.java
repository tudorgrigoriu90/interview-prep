package com.example.deposits.psp;

import java.math.BigDecimal;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.example.deposits.config.DepositProperties;

@Component
public class HttpPspClient implements PspClient {

    private final RestClient restClient;
    private final int maxAttempts;

    public HttpPspClient(RestClient pspRestClient, DepositProperties properties) {
        this.restClient = pspRestClient;
        this.maxAttempts = properties.psp().maxAttempts();
    }

    @Override
    public PspCharge charge(String paymentToken, BigDecimal amount, String currency, String reference) {
        ResourceAccessException failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.post()
                        .uri("/v1/charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ChargeRequest(paymentToken, amount.toPlainString(), currency, reference))
                        .retrieve()
                        .body(PspCharge.class);
            } catch (ResourceAccessException e) {
                failure = e;
            }
        }
        throw new PspUnavailableException("PSP did not answer after " + maxAttempts + " attempts", failure);
    }

    @Override
    public PspCharge lookup(String reference) {
        try {
            return restClient.get()
                    .uri("/v1/charges?reference={reference}", reference)
                    .retrieve()
                    .body(PspCharge.class);
        } catch (HttpClientErrorException.NotFound e) {
            return new PspCharge(null, PspStatus.NOT_FOUND);
        }
    }

    record ChargeRequest(String token, String amount, String currency, String reference) {
    }
}
