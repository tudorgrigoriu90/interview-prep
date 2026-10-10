package com.example.payouts.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All tunables in one typed, validated place.
 *
 * WHY: a typo or a missing value fails at startup instead of at 3 a.m. on the first payout.
 * PAY ATTENTION: money-related config (fee minimums) is keyed by currency. "minimum: 1.00" without a
 * currency is a bug in a multi-currency product (1 JPY is not 1 EUR).
 */
@Validated
@ConfigurationProperties(prefix = "payouts")
public record PayoutProperties(
        @Valid @NotNull Fee fee,
        @Valid @NotNull Psp psp,
        @Valid @NotNull Webhook webhook,
        @Valid @NotNull Topics topics,
        @Valid @NotNull Jobs jobs) {

    /** Percentage fee (2.5 means 2.5%) and a minimum fee per ISO currency code. */
    public record Fee(@NotNull @DecimalMin("0") BigDecimal percent, @NotEmpty Map<String, BigDecimal> minimum) {
    }

    /** Every remote call has a connect and a read timeout. There is no "infinite" default. */
    public record Psp(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    /** The secret comes from the environment; the tolerance bounds replayed (old) webhooks. */
    public record Webhook(@NotBlank String secret, @NotNull Duration tolerance) {
    }

    public record Topics(@NotBlank String withdrawalEvents,
                         @NotBlank String riskDecisions,
                         @Positive int partitions,
                         @Positive int replicas) {
    }

    /**
     * @param enabled          turns the schedulers on (off in tests, which call the jobs directly)
     * @param batchSize        rows per run, so one run is bounded
     * @param stuckAfter       how long a payout may stay SENDING/SENT before we ask the PSP
     * @param outboxRunBudget  a relay run stops after this long, well inside the ShedLock lease
     */
    public record Jobs(boolean enabled,
                       @Positive int batchSize,
                       @NotNull Duration stuckAfter,
                       @NotNull Duration outboxRunBudget) {
    }
}
