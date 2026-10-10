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
import com.example.payouts.service.RequestWithdrawal;
import com.example.payouts.service.WithdrawalService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WithdrawalController.class)
class WithdrawalControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WithdrawalService service;

    @Test
    void createsWithdrawal() throws Exception {
        Withdrawal w = Withdrawal.reserve(7L, 1L, "key-1", Money.of("100.00", "EUR"), Money.of("2.50", "EUR"),
                "pm-1", Instant.parse("2026-10-10T12:00:00Z"));
        ReflectionTestUtils.setField(w, "id", 42L);
        when(service.request(eq("key-1"), any(RequestWithdrawal.class))).thenReturn(w);

        mvc.perform(post("/api/v1/withdrawals").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerId": 7, "amount": 100.00, "currency": "EUR", "payoutMethodId": "pm-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }
}
