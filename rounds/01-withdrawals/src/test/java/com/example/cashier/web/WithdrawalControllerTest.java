package com.example.cashier.web;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.cashier.domain.Withdrawal;
import com.example.cashier.service.WithdrawalCommand;
import com.example.cashier.service.WithdrawalException;
import com.example.cashier.service.WithdrawalResult;
import com.example.cashier.service.WithdrawalService;

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
        var withdrawal = new Withdrawal(7L, 1L, "k-1",
                new BigDecimal("100.00"), "EUR", new BigDecimal("2.50"),
                new BigDecimal("100.00"), "EUR", "pm-1", Instant.parse("2026-01-01T10:00:00Z"));
        when(service.requestWithdrawal(eq(7L), eq("k-1"), any(WithdrawalCommand.class)))
                .thenReturn(new WithdrawalResult(withdrawal, new BigDecimal("397.50")));

        mvc.perform(post("/api/v1/withdrawals")
                        .header("X-Player-Id", "7")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100.00, "currency": "EUR", "payoutCurrency": "EUR", "payoutMethodId": "pm-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.balanceAfter").value(397.50));
    }

    @Test
    void mapsInsufficientFundsTo422() throws Exception {
        when(service.requestWithdrawal(any(), any(), any()))
                .thenThrow(new WithdrawalException(WithdrawalException.Reason.INSUFFICIENT_FUNDS, "Balance too low"));

        mvc.perform(post("/api/v1/withdrawals")
                        .header("X-Player-Id", "7")
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 900.00, "currency": "EUR", "payoutCurrency": "EUR", "payoutMethodId": "pm-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
    }
}
