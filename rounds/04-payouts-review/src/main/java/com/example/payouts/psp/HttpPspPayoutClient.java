package com.example.payouts.psp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpPspPayoutClient implements PspPayoutClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPspPayoutClient.class);

    private final RestClient pspRestClient;

    public HttpPspPayoutClient(RestClient pspRestClient) {
        this.pspRestClient = pspRestClient;
    }

    @Override
    public PayoutResult payout(PayoutRequest request) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                PayoutResponse response = pspRestClient.post()
                        .uri("/v1/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new PayoutBody(request.merchantReference(), request.amount().amount().doubleValue(),
                                request.amount().currencyCode(), request.payoutMethodId()))
                        .retrieve()
                        .body(PayoutResponse.class);
                return new PayoutResult.Accepted(response.pspReference());
            } catch (HttpClientErrorException e) {
                return new PayoutResult.Declined(e.getStatusText());
            } catch (RestClientException e) {
                log.warn("PSP call failed (attempt {})", attempt, e);
            }
        }
        return new PayoutResult.Declined("PSP unavailable");
    }

    record PayoutBody(String merchantReference, double amount, String currency, String payoutMethodId) {
    }

    record PayoutResponse(String pspReference) {
    }
}
