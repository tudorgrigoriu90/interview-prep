package com.example.payouts.psp;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter for the PSP payout API.
 *
 * PAY ATTENTION:
 * - Idempotency-Key header on EVERY payout call. Without it, a retry after a timeout can pay twice.
 * - No retry loop in here. A retry happens later, from the resolver, with the SAME key, after asking the PSP
 *   what happened (lookup). Blind retries inside a request thread multiply latency and risk.
 * - Mapping errors is a money decision:
 *     422          -> Declined (the PSP evaluated and refused it)
 *     other 4xx    -> Unknown  (401/403/429 say nothing about the payout itself; alert, don't refund)
 *     5xx, timeout -> Unknown  (the PSP may have processed it before failing)
 * - Amounts travel as strings with their currency.
 */
@Component
public class HttpPspPayoutClient implements PspPayoutClient {

    private final RestClient pspRestClient;

    public HttpPspPayoutClient(RestClient pspRestClient) {
        this.pspRestClient = pspRestClient;
    }

    @Override
    public PayoutResult payout(PayoutRequest request) {
        try {
            PayoutResponse response = pspRestClient.post()
                    .uri("/v1/payouts")
                    .header("Idempotency-Key", request.merchantReference())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PayoutBody(request.merchantReference(), request.amount().toPlainString(),
                            request.amount().currencyCode(), request.payoutMethodId()))
                    .retrieve()
                    .body(PayoutResponse.class);
            if (response == null || response.pspReference() == null) {
                return new PayoutResult.Unknown("Empty PSP response");
            }
            return new PayoutResult.Accepted(response.pspReference());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
                return new PayoutResult.Declined("PSP declined: " + e.getStatusText());
            }
            return new PayoutResult.Unknown("PSP client error " + e.getStatusCode().value());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            return new PayoutResult.Unknown(e.getClass().getSimpleName());
        }
    }

    @Override
    public PspPayoutStatus lookup(String merchantReference) {
        try {
            StatusResponse response = pspRestClient.get()
                    .uri("/v1/payouts/{reference}", merchantReference)
                    .retrieve()
                    .body(StatusResponse.class);
            return response == null ? PspPayoutStatus.PENDING : response.status();
        } catch (HttpClientErrorException.NotFound e) {
            return PspPayoutStatus.NOT_FOUND;
        }
        // other failures propagate: the resolver logs them and tries again on its next run
    }

    record PayoutBody(String merchantReference, String amount, String currency, String payoutMethodId) {
    }

    record PayoutResponse(String pspReference) {
    }

    record StatusResponse(PspPayoutStatus status) {
    }
}
