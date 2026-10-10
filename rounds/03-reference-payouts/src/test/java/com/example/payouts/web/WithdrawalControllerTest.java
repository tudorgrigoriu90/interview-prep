package com.example.payouts.web;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.example.payouts.domain.Withdrawal;
import com.example.payouts.money.Money;
import com.example.payouts.service.PayoutException;
import com.example.payouts.service.RequestWithdrawal;
import com.example.payouts.service.WithdrawalOutcome;
import com.example.payouts.service.WithdrawalService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract only: status codes, headers, error format, validation. Business logic is mocked. */
@WebMvcTest(controllers = WithdrawalController.class,
        properties = {"PSP_WEBHOOK_SECRET=unused", "DB_PASSWORD=unused"})
class WithdrawalControllerTest {

    private static final String BODY = """
            {"amount": "100.00", "currency": "EUR", "payoutMethodId": "pm-1"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WithdrawalService service;

    @Test
    void createsAWithdrawalWith201LocationAndMoneyAsStrings() throws Exception {
        when(service.request(eq(7L), eq("key-1"), any(RequestWithdrawal.class)))
                .thenReturn(new WithdrawalOutcome(withdrawal(42L), true));

        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/withdrawals/42"))
                .andExpect(jsonPath("$.amount").value("100.00"))
                .andExpect(jsonPath("$.fee").value("2.50"))
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void replayReturns200WithTheSameBody() throws Exception {
        when(service.request(eq(7L), eq("key-1"), any(RequestWithdrawal.class)))
                .thenReturn(new WithdrawalOutcome(withdrawal(42L), false));

        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void missingIdempotencyKeyIsA400ProblemAndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void negativeAmountIsRejectedByValidation() throws Exception {
        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7").header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "-100.00", "currency": "EUR", "payoutMethodId": "pm-1"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void businessErrorsBecomeProblemDetailsWithAStableCode() throws Exception {
        when(service.request(eq(7L), eq("key-3"), any(RequestWithdrawal.class)))
                .thenThrow(new PayoutException(PayoutException.Code.INSUFFICIENT_FUNDS, "Balance too low"));
        when(service.request(eq(7L), eq("key-4"), any(RequestWithdrawal.class)))
                .thenThrow(new PayoutException(PayoutException.Code.IDEMPOTENCY_CONFLICT, "Key reused"));

        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7").header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post("/api/v1/withdrawals").header("X-Player-Id", "7").header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void anotherPlayersWithdrawalIsNotFound() throws Exception {
        when(service.findForPlayer(42L, 8L))
                .thenThrow(new PayoutException(PayoutException.Code.WITHDRAWAL_NOT_FOUND, "not found"));

        mvc.perform(get("/api/v1/withdrawals/42").header("X-Player-Id", "8"))
                .andExpect(status().isNotFound());
    }

    private static Withdrawal withdrawal(Long id) {
        Withdrawal w = Withdrawal.reserve(7L, 1L, "key-1", Money.of("100.00", "EUR"), Money.of("2.50", "EUR"),
                "pm-1", Instant.parse("2026-10-10T12:00:00Z"));
        ReflectionTestUtils.setField(w, "id", id);
        return w;
    }
}
