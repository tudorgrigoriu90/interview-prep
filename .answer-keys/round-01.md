# Round 01 — Withdrawals (money & precision + transaction/concurrency)

Scenario: player withdrawal from wallet; fee (2.5%, min 1.00); optional FX to payout currency; withdrawal PENDING; event to Kafka after commit.
Assumptions given: multiple instances, PostgreSQL READ COMMITTED, Kafka at-least-once, gateway authenticates and sets X-Player-Id, payout processor (out of scope) reads PENDING withdrawals from the DB.

| # | Sev | Where | Issue / failure scenario | Fix |
|---|-----|-------|--------------------------|-----|
| 1 | Blocker | `WithdrawalService.requestWithdrawal` + `WalletRepository.debit` | Return value of the atomic conditional `debit` is ignored; the real guard is the in-memory check-then-act on a non-locked read. Balance 500, two concurrent 300 withdrawals (two tabs / two instances): both read 500, both pass the check, first UPDATE → 191, second UPDATE matches 0 rows, but a PENDING withdrawal for 300 is still saved and committed → 300 paid out with no debit. | `if (wallets.debit(id, total) == 0) throw INSUFFICIENT_FUNDS;` and drop or keep the pre-check only as a fast path. Concurrency test with two threads + latch. |
| 2 | Blocker | `WithdrawalController.create` (missing `@Valid`) + service | `@Positive/@Digits` on `WithdrawalRequest` are never evaluated. Amount −100: scale check passes (scale −2), fee = max(−3.00, 1.00) = 1.00, total = −99, `balance >= -99` true, `balance - (-99)` → wallet credited 99, a withdrawal with payout −100 stored. Free money. | `@Valid @RequestBody`, plus domain invariant (positive) in the service/Money type. |
| 3 | Blocker | `WithdrawalService` | Request `currency` never checked against `wallet.getCurrency()`. SEK wallet, request 100 EUR → wallet debited 100 SEK (+fee) while payout is 100 EUR converted from EUR (~1,150 SEK value). | Reject if `!wallet.currency.equals(command.currency)` or derive currency from the wallet and drop it from the request. |
| 4 | Blocker (Major acceptable) | `FeeCalculator.feeFor` | `percent.divide(100, 2, HALF_UP)` rounds the *rate*: 2.5% → 0.025 → 0.03. Every customer pays 3% instead of 2.5% (20% overcharge; 200.00 → fee 6.00 instead of 5.00). 1.25% would become 1%. Rounding at an intermediate step. | `amount.multiply(percent).divide(HUNDRED, digits, HALF_EVEN)` — keep full precision, round once at the end to the currency's digits. |
| 5 | Major | `WithdrawalServiceIntegrationTest` / `FeeCalculatorTest` | Tests bake the fee bug in: expects fee 6.00 on 200.00 with 2.5% config (and balances 294.00 / 397.00 / 191.00). Unit test only uses an integer percent (2%), which hides the scale-2 rounding. `cannotOverdrawWallet` is sequential, so it gives false confidence about #1. No negative/zero amount test, no cross-currency test. | Expected values from the business rule, fractional-percent case, concurrent test (ExecutorService + CountDownLatch) asserting exactly one success and balance ≥ 0. |
| 6 | Major | `FxService.convert` + `HttpFxRateClient` | Rate carried as `double` and turned into `new BigDecimal(double)` (0.0874 → 0.08739999999999999...). Then `divide` with no scale/MathContext → `ArithmeticException: Non-terminating decimal expansion` for practically every real rate → every cross-currency withdrawal 500s (unmapped exception). Test passes only because 1.25 is exact in binary and terminates. | Rate as `BigDecimal` from the JSON string (`BigDecimal.valueOf` at minimum); `divide(rate, MathContext.DECIMAL128)` or `divide(rate, target digits, HALF_EVEN)`; better: store/quote rate in the multiply direction. |
| 7 | Major | `FxService`, `FeeCalculator`, entity columns `scale = 2`, yml `minimum: 1.00` | Scale 2 hard-coded for every currency, while validation uses `Currency.getDefaultFractionDigits()`. JPY payout → 12345.67 JPY (invalid, PSP rejects); KWD/BHD (3 decimals) pass validation (10.005) then Postgres `numeric(19,2)` silently rounds to 10.01 on insert — stored amount ≠ debited amount. Minimum fee 1.00 is currency-less: 1 JPY ≈ 0.006 EUR, 1 KWD ≈ 2.8 EUR. | Scale from `Currency`, columns `numeric(19,4)` or minor units, per-currency fee config (Money type). |
| 8 | Major | `WithdrawalService` (tx boundary) | HTTP call to the FX service (up to 0.5s + 2s) happens *after* the `UPDATE wallet` inside the same transaction → wallet row lock + DB connection held during a remote call. FX slow → every withdrawal/bet/deposit on that wallet blocks; Hikari pool (10) exhausted under load. | Quote FX before the debit (outside the tx), or a short tx for the debit + withdrawal insert only. |
| 9 | Major | `WithdrawalService.replay` | `BigDecimal.equals` compares scale. DB returns `100.00`; client retried with JSON `100` or `100.5` → considered a different request → 409. Client that times out and retries gets a conflict, may treat it as failed and retry with a new key → second withdrawal. | `compareTo(...) == 0` (or normalise scale in a Money type). |
| 10 | Major | `WithdrawalResponse.payoutAmount` | Money exposed as `double` in the API (inconsistent with the other BigDecimal fields). Precision loss for large amounts, `1.0E7`-style rendering, clients do float math. | `BigDecimal` (and consider string serialisation). |
| 11 | Minor | `WalletRepository.debit` + `WithdrawalResult` | `@Modifying` without `clearAutomatically`; the `Wallet` entity loaded earlier stays in the persistence context, so `balanceAfter = wallet.getBalance()` returns the *pre-debit* balance (500 instead of 294). Wrong balance shown to the player. | `@Modifying(clearAutomatically = true)` + re-read, or `UPDATE ... RETURNING balance` (native), or compute from the update. |
| 12 | Minor | `WithdrawalEventPublisher` | AFTER_COMMIT send is fire-and-forget: crash between commit and send, or a broker error (future never checked) loses the event silently. Acceptable only because the payout processor reads the DB; otherwise outbox. | Transactional outbox, or at least handle the send future and alert. |
| 13 | Minor | Idempotency | Concurrent duplicate with the same key: both miss `findBy...`, one hits the unique constraint → `DataIntegrityViolationException` → 500 instead of replay. (Debit rolled back, so no money lost.) | Insert-first (claim the key) or catch the constraint violation and replay. |
| 14 | Minor | `application.yml` | `ddl-auto: update` in a payments service (schema drift, no review of DDL, `numeric` changes never applied); `open-in-view` left at default `true`. | Flyway/Liquibase + `validate`; `spring.jpa.open-in-view: false`. |
| 15 | Nit | `FxService` | `HALF_UP` on payout conversion rounds in the player's favour; rounding mode is a business decision (HALF_EVEN or DOWN for payouts). Ask. | Agree a policy, document, one mode. |

## Decoys (correct code)
- D1 `amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()` — correct way to check decimals (`100` → scale −2, `10.50` → 1).
- D2 The conditional `UPDATE ... WHERE balance >= :amount` itself — correct and atomic; the bug is ignoring its result (#1).
- D3 `quote(to, from)` then `amount.divide(rate)` — direction is right (price of 1 `to` in `from`). Only precision/scale is wrong (#6).
- D4 Event uses `toPlainString()` strings + currency, keyed by withdrawal id — correct money-in-JSON. (Only delivery guarantee is weak, #12.)
- D5 `calculated.max(minimum)` — `max` uses `compareTo` semantics; fine.

## Ideal 10-minute spoken review (outline)
1. Headline: "This can pay out money that was never debited, credit a wallet on a withdrawal, debit the wrong currency, and overcharges every customer's fee."
2. #1 ignored debit result + check-then-act (numbers: 500, 2×300). #2 missing @Valid → negative amount credits. #3 currency mismatch.
3. #4 fee rate rounded to 0.03; and the tests assert the wrong number (#5).
4. #6 FX double + divide without MathContext → every FX withdrawal fails; #7 scale 2 everywhere vs currency digits, numeric(19,2) rounding, minimum fee without currency.
5. #8 remote FX call while holding the wallet row lock.
6. #9 BigDecimal.equals in replay; #10 double in response; #11 stale balance.
7. Close: #12–#14, missing concurrency test; fix order 1→4 first.

## Post-round note
During the live review the candidate's finding #12 (fire-and-forget Kafka send after commit) was implemented as a transactional outbox with a ShedLock relay, in the commit "Replace after-commit Kafka send with transactional outbox". The original exercise (with #12 present) is the commit `32a568e`. All other planted issues are unchanged.
Also implemented during the live review: #1 (debit result checked) and #2 (`@Valid` plus service-level positive-amount check), with a concurrent test and negative/zero tests that fail on the original code.


---

# Detailed solutions for Round 01 (database-agnostic)

Everything below is **verified**: the complete fixed project is in `.answer-keys/round-01-solution/` and `./mvnw test` passes there (22 tests, real PostgreSQL through Testcontainers). The exercise itself (`rounds/01-withdrawals`) is untouched so you can practise on it.
Imports are omitted in the snippets; the files in the solution folder are complete.

## 0. How the fixed flow looks

```
Controller (@Valid)
   │
   ▼
WithdrawalService  (NOT transactional)
   1. replay check by (playerId, Idempotency-Key)            plain read
   2. validate amount, currency, decimals                    no database
   3. load wallet, compare currency, fast balance check      plain read
   4. fee (one rounding, per currency)                       no database
   5. FX quote and payout amount                             remote call, no lock held
   6. recorder.record(...)  ────────────►  WithdrawalRecorder (@Transactional, short)
                                              a. conditional UPDATE debit, check row count
                                              b. insert withdrawal
                                              c. insert outbox row
                                              d. re-read balance
   7. on unique violation (same key raced): replay the winner
OutboxRelay (ShedLock, scheduled) ──► Kafka   (key = playerId, in id order, wait for ack)
```

Two rules drive the layout: **no network call while a row lock is held**, and **every money decision is either a database condition with its result checked, or a comparison on a value that cannot go stale**.

## 1. Ignored debit result (Blocker)

**Fix** (`WithdrawalRecorder`, verified):
```java
/**
 * The short database transaction: debit, withdrawal row and outbox row commit or roll back together.
 * No network call happens in here.
 */
@Component
public class WithdrawalRecorder {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String withdrawalsTopic;
    private final Clock clock;

    public WithdrawalRecorder(WalletRepository wallets, WithdrawalRepository withdrawals,
                              OutboxRepository outbox, ObjectMapper json,
                              CashierProperties properties, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.outbox = outbox;
        this.json = json;
        this.withdrawalsTopic = properties.topics().withdrawals();
        this.clock = clock;
    }

    @Transactional
    public WithdrawalResult record(Withdrawal withdrawal, BigDecimal total) {
        if (wallets.debit(withdrawal.getWalletId(), total) == 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }
        Withdrawal saved = withdrawals.save(withdrawal);
        outbox.save(toOutboxEvent(saved));
        BigDecimal balanceAfter = wallets.findById(saved.getWalletId()).orElseThrow().getBalance();
        return new WithdrawalResult(saved, balanceAfter);
    }

    private OutboxEvent toOutboxEvent(Withdrawal withdrawal) {
        try {
            String payload = json.writeValueAsString(WithdrawalRequestedEvent.from(withdrawal));
            return new OutboxEvent(withdrawalsTopic, String.valueOf(withdrawal.getPlayerId()), payload, clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise withdrawal event " + withdrawal.getId(), e);
        }
    }
}
```

The `WalletRepository` query stays a plain JPQL conditional update:
```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Wallet w set w.balance = w.balance - :amount where w.id = :id and w.balance >= :amount")
    int debit(@Param("id") Long id, @Param("amount") BigDecimal amount);
```

**Why this is database-agnostic.** The `UPDATE ... WHERE balance >= :amount` guard and the row count are standard SQL semantics: the engine locks the row, waits for a competing writer, then evaluates the condition on the latest committed value. That holds on PostgreSQL, Oracle, SQL Server and MySQL InnoDB (also at REPEATABLE READ for InnoDB, because UPDATE always reads the latest committed row).

**The other portable options, and their trade-offs**

| Option | How | Notes |
|---|---|---|
| Conditional UPDATE (chosen) | JPQL above, check `== 0` | One statement, no retry loop, no lock held beyond the transaction. Best for a hot wallet |
| Pessimistic lock | `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a `findByPlayerIdForUpdate` query, then change the entity | JPA picks the syntax: `FOR UPDATE` (PG, MySQL, Oracle), `WITH (UPDLOCK, ROWLOCK)` (SQL Server). Set `jakarta.persistence.lock.timeout` so a stuck lock fails instead of waiting forever |
| Optimistic lock | `@Version Long version` on `Wallet`, load, change, save; catch `ObjectOptimisticLockingFailureException` and retry **outside** the transaction | Entity updates only. A JPQL bulk update ignores `@Version` unless written `update versioned Wallet ...`. Under contention you retry a lot |
| Serializable isolation | `@Transactional(isolation = SERIALIZABLE)` | Not portable in behaviour: PostgreSQL throws serialization failures (you must retry), MySQL takes shared locks and deadlocks, Oracle raises `ORA-08177`. Avoid as the main fix |

Sketch of the pessimistic variant (JPA only, no vendor syntax):
```java
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select w from Wallet w where w.playerId = :playerId")
    Optional<Wallet> findForUpdate(@Param("playerId") Long playerId);
}
// in a @Transactional method: wallet = findForUpdate(..); if (wallet.getBalance().compareTo(total) < 0) throw ...; wallet.debit(total);
```
**Isolation caveat to say out loud:** a plain `SELECT` balance check is only a hint. At READ COMMITTED it can be stale the instant it returns; on MySQL's default REPEATABLE READ it is a snapshot for the whole transaction. The guard must be the locked or conditional write.

**Test that proves it** (`WithdrawalServiceIntegrationTest.concurrentWithdrawalsCannotExceedBalance`): 8 threads, a start latch, assert exactly one success, balance not negative, one withdrawal row. It failed on the original code.

## 2. Missing `@Valid` and non-positive amounts (Blocker)
`@Valid @RequestBody` in the controller, plus the invariant in the service (`amount.signum() <= 0` throws `INVALID_AMOUNT`) so non-HTTP callers are covered. Add a database backstop with a **check constraint**:
```sql
ALTER TABLE withdrawal ADD CONSTRAINT chk_withdrawal_amount_positive CHECK (amount > 0);
ALTER TABLE wallet     ADD CONSTRAINT chk_wallet_balance_nonneg      CHECK (balance >= 0);
```
Portable across PostgreSQL, Oracle, SQL Server; **MySQL enforces CHECK only from 8.0.16** (older versions parse and ignore it), so on older MySQL rely on the code and the conditional update. With Hibernate you can declare it on the entity with `@org.hibernate.annotations.Check(constraints = "amount > 0")`.

## 3. Currency not compared with the wallet (Blocker)
```java
if (!wallet.getCurrency().equals(currency.getCurrencyCode())) {
    throw new WithdrawalException(CURRENCY_MISMATCH, "Wallet is in " + wallet.getCurrency());
}
```
Mapped to 422 in `ApiExceptionHandler`. No database dependence. If the business wants cross-currency debits instead, you convert the debit amount with **one stored rate** (store the rate and its timestamp on the withdrawal) and apply section 5 to that conversion.

## 4. Fee rounding (Major)
```java
@Component
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CashierProperties.Fee fee;

    public FeeCalculator(CashierProperties properties) {
        this.fee = properties.fee();
    }

    public BigDecimal feeFor(BigDecimal amount, Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        BigDecimal calculated = amount.multiply(fee.percent())
                .divide(HUNDRED, digits, RoundingMode.HALF_EVEN);
        return calculated.max(minimumFor(currency, digits));
    }

    private BigDecimal minimumFor(Currency currency, int digits) {
        BigDecimal minimum = fee.minimum().get(currency.getCurrencyCode());
        if (minimum == null) {
            throw new IllegalStateException("No minimum fee configured for " + currency.getCurrencyCode());
        }
        return minimum.setScale(digits, RoundingMode.HALF_EVEN);
    }
}
```

- The percentage is **not** rounded: `amount * 2.5 / 100` is computed first, rounded **once**, to the currency's digits.
- HALF_EVEN is a policy choice (section 14); the point is one mode, applied once.
- The minimum fee is **per currency** and the code fails loudly when a currency has none configured (`application.yml`: `minimum: {EUR: 1.00, SEK: 10.00, JPY: 150}`).
- Verified examples: 2.5% of 200.00 is 5.00; of 333.33 is 8.33; of 10000 JPY is 250 (scale 0).

## 5. FX conversion (Major)
The client returns a `BigDecimal` parsed from the JSON text, never a double:
```java
package com.example.cashier.fx;

import java.math.BigDecimal;

public interface FxRateClient {

    /**
     * Price of one unit of {@code base} expressed in {@code quote}.
     */
    BigDecimal quote(String base, String quote);
}
```

```java
@Service
public class FxService {

    private final FxRateClient rates;

    public FxService(FxRateClient rates) {
        this.rates = rates;
    }

    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal rate = rates.quote(to.getCurrencyCode(), from.getCurrencyCode());
        return amount.divide(rate, to.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }
}
```

- `new BigDecimal(double)` carries the binary error, and `divide(rate)` without a scale throws `ArithmeticException` whenever the exact quotient does not terminate (1.25 terminates, 0.0874 or 1.1655 usually do not).
- `divide(rate, digitsOfPayoutCurrency, RoundingMode)` states how many decimals you keep and how to round.
- Jackson fills a `BigDecimal` field from the number token's text exactly. If the provider can send strings (`"rate": "1.1655"`) that works too.
- Verified: 100.00 EUR at 1.1655 gives 85.80 GBP; 100.00 EUR at 0.0061 (EUR per JPY) gives 16393 JPY (scale 0).

## 6. Scale 2 for every currency (Major)
- Code takes the digits from `Currency.getDefaultFractionDigits()` (JPY 0, EUR 2, KWD 3). Columns are `precision = 19, scale = 4` so every ISO currency fits; the code normalises to the currency's digits **before** persisting.
- **Never rely on the database to round for you.** What happens when the value has more decimals than the column differs by vendor and mode (PostgreSQL rounds silently; MySQL rounds or raises an error depending on `sql_mode`; Oracle rounds). Validate in code (`stripTrailingZeros().scale() > digits` is the portable check).
- Portable alternative: store **minor units** in a `BIGINT` plus a currency code (cents, 1 for JPY, 1000 for KWD). No decimal types, no scale mismatch, but every conversion goes through the currency's factor, so wrap it in a `Money` type.
- Column type names differ (`NUMERIC` in PG, `NUMBER(19,4)` in Oracle, `DECIMAL(19,4)` in MySQL and SQL Server); JPA's `precision`/`scale` hides that.

## 7. Remote call under a row lock (Major)
The split is the fix: `WithdrawalService` (no transaction, does validation, fee and FX) and `WithdrawalRecorder` (short transaction). The orchestrator:
```java
/**
 * Not transactional on purpose: validation and the FX call happen before any row lock is taken,
 * the database work is one short transaction in {@link WithdrawalRecorder}.
 */
@Service
public class WithdrawalService {

    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final FeeCalculator feeCalculator;
    private final FxService fxService;
    private final WithdrawalRecorder recorder;
    private final Clock clock;

    public WithdrawalService(WalletRepository wallets, WithdrawalRepository withdrawals,
                             FeeCalculator feeCalculator, FxService fxService,
                             WithdrawalRecorder recorder, Clock clock) {
        this.wallets = wallets;
        this.withdrawals = withdrawals;
        this.feeCalculator = feeCalculator;
        this.fxService = fxService;
        this.recorder = recorder;
        this.clock = clock;
    }

    public WithdrawalResult requestWithdrawal(Long playerId, String idempotencyKey, WithdrawalCommand command) {
        Optional<Withdrawal> previous = withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), command);
        }

        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new WithdrawalException(INVALID_AMOUNT, "Amount must be positive");
        }
        Currency currency = Currency.getInstance(command.currency());
        Currency payoutCurrency = Currency.getInstance(command.payoutCurrency());
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new WithdrawalException(INVALID_AMOUNT, "Too many decimals for " + currency);
        }

        Wallet wallet = wallets.findByPlayerId(playerId)
                .orElseThrow(() -> new WithdrawalException(WALLET_NOT_FOUND, "No wallet for player " + playerId));
        if (!wallet.getCurrency().equals(currency.getCurrencyCode())) {
            throw new WithdrawalException(CURRENCY_MISMATCH, "Wallet is in " + wallet.getCurrency());
        }

        BigDecimal fee = feeCalculator.feeFor(amount, currency);
        BigDecimal total = amount.add(fee);
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low for withdrawal of " + total);
        }

        BigDecimal payoutAmount = fxService.convert(amount, currency, payoutCurrency);
        Withdrawal withdrawal = new Withdrawal(
                playerId, wallet.getId(), idempotencyKey,
                amount, currency.getCurrencyCode(), fee,
                payoutAmount, payoutCurrency.getCurrencyCode(), command.payoutMethodId(),
                clock.instant());
        try {
            return recorder.record(withdrawal, total);
        } catch (DataIntegrityViolationException e) {
            // the same key was inserted concurrently: our transaction rolled back, return the winner's result
            return withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey)
                    .map(existing -> replay(existing, command))
                    .orElseThrow(() -> e);
        }
    }

    private WithdrawalResult replay(Withdrawal existing, WithdrawalCommand command) {
        boolean sameRequest = existing.getAmount().compareTo(command.amount()) == 0
                && existing.getCurrency().equals(command.currency())
                && existing.getPayoutCurrency().equals(command.payoutCurrency());
        if (!sameRequest) {
            throw new WithdrawalException(IDEMPOTENCY_CONFLICT,
                    "Idempotency key reused with a different request: " + existing.getIdempotencyKey());
        }
        BigDecimal balance = wallets.findById(existing.getWalletId()).map(Wallet::getBalance).orElseThrow();
        return new WithdrawalResult(existing, balance);
    }
}
```

- Why a separate bean and not another `@Transactional` method in the same class: calling it with `this.` would bypass the proxy (the self-invocation trap). A second bean or a `TransactionTemplate` works.
- Alternative with the same effect: keep one class and use `TransactionTemplate.execute(...)` around steps a to d only.
- The idea is database-agnostic. The cost of ignoring it is vendor-specific: the debit `UPDATE` holds the wallet row lock until commit on every engine; with a 2.5 second remote call and a pool of 10 connections you stall on any of them.

## 8. `BigDecimal.equals` in the replay check (Major)
`existing.getAmount().compareTo(command.amount()) == 0`. `equals` also compares scale, and the stored value (scale 2 or 4 from the column) rarely has the scale the client typed (`100`). Verified by `replaysWhenTheRetryWritesTheSameAmountWithADifferentScale`. The same applies to `Set`/`Map` keys and to assertions (`isEqualByComparingTo` in tests).

## 9. `double` in the response (Major)
`BigDecimal payoutAmount` in `WithdrawalResponse`, same as the other money fields. If a client's JSON parser uses doubles, serialise amounts as strings (`@JsonFormat(shape = STRING)`) and document the format.

## 10. Stale balance in the response (Minor)
`@Modifying(flushAutomatically = true, clearAutomatically = true)` on the debit query, and the recorder re-reads the balance after the update (`wallets.findById(...)`). The re-read is inside the same transaction, so it sees its own update and nobody else can change that row before commit.
- Not portable: `UPDATE ... RETURNING balance` (PostgreSQL, Oracle) or `OUTPUT inserted.balance` (SQL Server); MySQL has no equivalent. The re-read works everywhere.

## 11. Fire-and-forget Kafka send (Minor, Major if the event drives payout): transactional outbox
The implementation (`OutboxEvent`, `OutboxRepository`, `OutboxRelay`) is in the project. Database-agnostic details:
- Table: `outbox(id, topic, event_key, payload VARCHAR(4000), status, created_at, published_at)` and a **composite index on `(status, id)`** (a partial index `WHERE status='NEW'` is PostgreSQL/SQL Server only). Avoid vendor column types such as `text`; use a length or a CLOB mapping.
- Id generation: `GenerationType.IDENTITY` works on PG, MySQL, SQL Server, Oracle 12c+. `SEQUENCE` is the portable choice for batching (Hibernate emulates it with a table on MySQL).

**Choosing who publishes (all portable except where marked)**

| Option | How | Notes |
|---|---|---|
| ShedLock (chosen) | JDBC lock table, one relay at a time, publish in id order | Needs the `shedlock` table; supports every major RDBMS. A run longer than `lockAtMostFor` lets a second instance in: bound the run time |
| Claim with a conditional UPDATE | `UPDATE outbox SET status='CLAIMED', claimed_by=:me, claimed_until=:t WHERE id IN (:ids) AND status='NEW'`; `ids` selected with `Pageable` (Hibernate generates the right LIMIT/FETCH FIRST/TOP) | Each instance claims different rows. You need a lease to reclaim a row whose owner died. Order across instances is lost, so key by player and shard by key if order matters |
| `SKIP LOCKED` | `@Lock(PESSIMISTIC_WRITE)` plus hint `jakarta.persistence.lock.timeout = -2` (Hibernate's "skip locked") | Needs dialect support: PostgreSQL 9.5+, MySQL 8+, Oracle, SQL Server (`READPAST`). Not available on every version, and again no cross-instance ordering |
| Change data capture (Debezium) | Reads the database log, no polling | Excellent throughput and ordering by log position, but each database needs its own connector and setup |

**Ordering rules (apply to all of them):** key the message by the entity whose order matters (player id here), publish in `id` order, wait for the broker ack before the next send, stop at the first failure, never use a "last seen timestamp" cursor (rows become visible in commit order, not insert order). Producer: `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms` (5s) shorter than the relay's wait (7s). Consumers deduplicate on the `event-id` header.

## 12. Same idempotency key twice at once (Minor)
The unique constraint `(player_id, idempotency_key)` is the guard. The orchestrator catches `DataIntegrityViolationException` **outside** the recorder's transaction and returns the winner's result:
```java
try {
    return recorder.record(withdrawal, total);
} catch (DataIntegrityViolationException e) {
    return withdrawals.findByPlayerIdAndIdempotencyKey(playerId, idempotencyKey)
            .map(existing -> replay(existing, command))
            .orElseThrow(() -> e);
}
```
Two conditions make this correct: the catch is **outside** the transaction (inside, the transaction is already marked rollback-only), and `spring.jpa.open-in-view` is `false` (with it on, the shared persistence context keeps the failed insert). Verified by `sameKeyUsedConcurrentlyCreatesOneWithdrawal` (6 threads, one withdrawal, balance debited once).
Vendor-specific alternatives: `INSERT ... ON CONFLICT DO NOTHING` (PostgreSQL), `INSERT IGNORE` / `ON DUPLICATE KEY` (MySQL), `MERGE` (Oracle, SQL Server). The unique constraint plus catching the violation needs none of them.

## 13. Schema management and config (Minor)
- Replace `ddl-auto: update` with `validate` and versioned migrations (Flyway or Liquibase). With Flyway, keep portable SQL where you can and use `db/migration/<vendor>` folders for the differences (identity columns, sequence syntax, partial indexes).
- Portable subset for the withdrawal table:
```sql
CREATE TABLE withdrawal (
    id              BIGINT        NOT NULL PRIMARY KEY,    -- from a sequence, or identity where supported
    player_id       BIGINT        NOT NULL,
    wallet_id       BIGINT        NOT NULL,
    idempotency_key VARCHAR(64)   NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        CHAR(3)       NOT NULL,
    fee             NUMERIC(19,4) NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    CONSTRAINT uq_withdrawal_idem UNIQUE (player_id, idempotency_key),
    CONSTRAINT chk_withdrawal_amount CHECK (amount > 0)
);
CREATE INDEX idx_withdrawal_wallet ON withdrawal (wallet_id);
```
- `spring.jpa.open-in-view: false`.

## 14. Rounding policy (Nit, but decide it)
HALF_UP always rounds half a cent in one direction. The solution uses HALF_EVEN for fees and payout conversion. Use one policy per business rule and document it; for payouts many operators round **down** so they never pay out more than the converted value.

## 15. Tests that prove it
In `.answer-keys/round-01-solution/src/test`:
- `FeeCalculatorTest`: fractional percent (2.5), rounding once, per-currency minimum, JPY scale 0.
- `FxServiceTest`: a rate that is not exact in binary (1.1655), JPY scale 0.
- `WithdrawalServiceIntegrationTest`: concurrent withdrawals (exactly one succeeds), concurrent same key (one withdrawal), zero and negative amounts, currency mismatch, replay with a different scale, realistic cross-currency payout, outbox row and relay publish.
- `WithdrawalControllerTest`: negative amount gives 400 and never reaches the service.
Expected values come from the business rule, not from what the code printed, and BigDecimal assertions use `isEqualByComparingTo`.
