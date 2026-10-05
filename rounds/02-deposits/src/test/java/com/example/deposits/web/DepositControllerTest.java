package com.example.deposits.web;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.deposits.domain.Deposit;
import com.example.deposits.service.DepositCommand;
import com.example.deposits.service.DepositService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DepositController.class)
class DepositControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DepositService service;

    @Test
    void createsDeposit() throws Exception {
        var deposit = new Deposit(7L, "k-1", new BigDecimal("50.00"), "EUR", Instant.parse("2026-01-01T10:00:00Z"));
        when(service.initiate(eq(7L), eq("k-1"), any(DepositCommand.class))).thenReturn(deposit);

        mvc.perform(post("/api/v1/deposits")
                        .header("X-Player-Id", "7")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 50.00, "currency": "EUR", "paymentToken": "tok_1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(50.00));
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        mvc.perform(post("/api/v1/deposits")
                        .header("X-Player-Id", "7")
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": -5.00, "currency": "EUR", "paymentToken": "tok_1"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
