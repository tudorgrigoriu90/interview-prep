package com.example.deposits.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.deposits.domain.Deposit;
import com.example.deposits.domain.DepositStatus;
import com.example.deposits.psp.PspCharge;
import com.example.deposits.psp.PspClient;
import com.example.deposits.psp.PspStatus;
import com.example.deposits.psp.PspUnavailableException;
import com.example.deposits.repository.DepositRepository;
import com.example.deposits.repository.LedgerRepository;
import com.example.deposits.repository.WalletRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepositServiceTest {

    private final DepositRepository deposits = mock(DepositRepository.class);
    private final PspClient psp = mock(PspClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
    private final DepositService service = new DepositService(
            deposits, mock(WalletRepository.class), mock(LedgerRepository.class), psp, clock);

    private final DepositCommand command = new DepositCommand(new BigDecimal("100.00"), "EUR", "tok_1");

    DepositServiceTest() {
        when(deposits.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(deposits.saveAndFlush(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deposits.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void storesPspReferenceAndStaysPendingUntilConfirmed() {
        when(psp.charge(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PspCharge("psp_1", PspStatus.PENDING));

        Deposit deposit = service.initiate(7L, "key-1", command);

        assertThat(deposit.getStatus()).isEqualTo(DepositStatus.PENDING);
        assertThat(deposit.getPspReference()).isEqualTo("psp_1");
    }

    @Test
    void marksDepositFailedWhenPspDeclines() {
        when(psp.charge(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PspCharge("psp_2", PspStatus.FAILED));

        assertThat(service.initiate(7L, "key-2", command).getStatus()).isEqualTo(DepositStatus.FAILED);
    }

    @Test
    void marksDepositFailedWhenPspIsUnavailable() {
        when(psp.charge(anyString(), any(), anyString(), anyString()))
                .thenThrow(new PspUnavailableException("timeout", null));

        assertThat(service.initiate(7L, "key-3", command).getStatus()).isEqualTo(DepositStatus.FAILED);
    }

    @Test
    void returnsExistingDepositForSameIdempotencyKey() {
        Deposit existing = new Deposit(7L, "key-4", new BigDecimal("100.00"), "EUR", clock.instant());
        when(deposits.findByIdempotencyKey("key-4")).thenReturn(Optional.of(existing));

        assertThat(service.initiate(7L, "key-4", command)).isSameAs(existing);
        verify(psp, never()).charge(anyString(), any(), anyString(), anyString());
    }
}
