package com.example.cashier.fx;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpFxRateClient implements FxRateClient {

    private final RestClient fxRestClient;

    public HttpFxRateClient(RestClient fxRestClient) {
        this.fxRestClient = fxRestClient;
    }

    @Override
    public double quote(String base, String quote) {
        FxQuote response = fxRestClient.get()
                .uri("/v1/quotes?base={base}&quote={quote}", base, quote)
                .retrieve()
                .body(FxQuote.class);
        if (response == null) {
            throw new IllegalStateException("Empty FX quote for " + base + "/" + quote);
        }
        return response.rate();
    }

    record FxQuote(String base, String quote, double rate) {
    }
}
