# Payments Code Review Prep — Java, Spring Boot, Kafka, Concurrency

Sep 30, 2026 · @Tudor

A payments code review is scored on three things: whether you find the bugs that lose or duplicate money, whether you rank them, and whether you explain each one with a concrete failure and a fix. This course gives you the knowledge for each area, then a practice review to test yourself. Money and concurrency findings come first; naming and style come last.

## 1. How to run the review out loud

**Start with two minutes of orientation, and say it aloud.** What does this service do, where does a request enter (controller, Kafka listener), what state does it change (database rows, events), and what external systems does it call? Then ask the interviewer two or three questions: does this run as one instance or many, which delivery guarantee does the Kafka setup give, and what is the default transaction isolation? Asking shows you know these change the answer.

**Then review in a fixed order, most dangerous first:**

1. **Money correctness:** `double` or `float`, missing currency, rounding, negative or zero amounts.
2. **Concurrency:** read-modify-write on balances or status without a lock, version or atomic update.
3. **Transactions:** what is inside the transaction boundary and what should be, remote calls inside it, swallowed exceptions.
4. **Idempotency and messaging:** can this request or event be processed twice? Is an event published before the commit?
5. **Error handling and security:** swallowed errors, sensitive data in logs, missing validation and authorization.
6. **Design and tests:** entities exposed in the API, missing tests for the money paths, naming.

**Rank every finding.** Say the severity so the interviewer hears your priorities:

| Severity | Meaning | Payments example |
| --- | --- | --- |
| Blocker | Loses, duplicates or corrupts money or data | Balance updated with a read-then-write and no lock |
| Major | Wrong under failure or load | Event published inside the transaction before commit |
| Minor | Correct but fragile or unclear | Magic string for a payment status |
| Nit | Style | Naming, formatting |

**Use one formula for every comment:** where, what, why it hurts (a concrete scenario with numbers), and the fix. For example: "In `PaymentService.pay`, the balance is read, changed and saved without a lock. Two concurrent payments of 60 on a balance of 100 can both succeed and leave the account at 40 instead of rejecting one. I'd add a `@Version` column and retry on conflict, or use a conditional update."

**Don't rewrite the code.** Point at the problem, show the smallest fix, and move on. If you run short of time, list the remaining areas you would check and say so.

## 2. Money and precision

**The rule: never `double` or `float` for money.** Binary floating point cannot represent most decimal fractions exactly, so `0.1 + 0.2` gives `0.30000000000000004`. Over millions of payments those errors become real discrepancies. Use `BigDecimal`, or store integer minor units (cents) in a `long`.

**`BigDecimal` traps to spot in a review:**

- `new BigDecimal(0.1)` captures the binary error of the double. Use `new BigDecimal("0.1")` or `BigDecimal.valueOf(0.1)`.
- `equals` compares scale too: `new BigDecimal("2.0").equals(new BigDecimal("2.00"))` is `false`. Compare values with `compareTo`, and remember this also breaks `HashMap` keys and `Set` membership.
- `divide` without a scale and rounding mode throws `ArithmeticException` for non-terminating results like 1/3. Always pass a scale and a `RoundingMode`.
- Rounding mode is a business decision. `HALF_UP` is the school rule; `HALF_EVEN` (banker's rounding) avoids statistical bias and is common in finance. Ask which one applies, and use one mode consistently.
- Round once, at the end of a calculation, not after every step.
- `stripTrailingZeros()` can produce scientific notation (`1E+2`); use `toPlainString()` when formatting.

**An amount without a currency is meaningless.** Currencies have different numbers of decimals (JPY 0, USD 2, KWD 3), so use `java.util.Currency` and its `getDefaultFractionDigits()`. Never add or compare two amounts in different currencies without an explicit conversion.

**Splitting money must not lose cents.** Splitting 100.00 three ways gives 33.33 + 33.33 + 33.33 = 99.99. Allocate the remainder deliberately (give the extra cent to the first parts) so the parts always sum to the total.

**Model it as a value object.** A small immutable `Money` record removes a whole class of bugs, because invalid amounts can't exist:

```java
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        // UNNECESSARY throws if the input has more decimals than the currency allows
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(BigDecimal factor) {          // e.g. a fee percentage
        var scaled = amount.multiply(factor)
                .setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
        return new Money(scaled, currency);
    }

    public boolean isPositive() { return amount.signum() > 0; }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
```

A record's generated `equals` uses `BigDecimal.equals`, which is fine here only because the compact constructor normalises the scale.

**At the boundaries:**

- **Database:** `NUMERIC(19,4)` (or an integer minor-units column) plus a `CHAR(3)` currency column. Never `FLOAT` or `DOUBLE PRECISION`.
- **JSON:** a JSON number is often parsed as a double by clients and by Jackson defaults. Prefer sending amounts as strings (`"12.50"`) or as integer minor units, and configure Jackson to read decimals as `BigDecimal` (`DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`).
- **Validation:** reject null, zero and negative amounts where they make no sense, and cap the maximum, at the API edge.

**Red flags to call out:** `double` or `float` anywhere near an amount; `BigDecimal` compared with `equals`; `divide` with no rounding mode; an amount field with no currency; rounding inside a loop; money that travels as a JSON number.

## 3. Transactions

**A database transaction is atomic for one database only.** It cannot roll back an HTTP call to a payment provider or a Kafka message that was already sent. Most transaction bugs in payment code come from forgetting that.

**Where the boundary belongs:** on the service method that performs one business operation, not on the controller and not on every repository call. Keep it short: a transaction holds a database connection, and often row locks, until it ends.

**The review checklist for `@Transactional`:**

- **Remote calls inside a transaction.** Calling a payment provider or another service while holding a connection and locks means slow calls exhaust the connection pool. Worse, if the remote call succeeds and the transaction then rolls back, the money moved but your database says it didn't.
- **Events sent before commit.** A Kafka `send` inside the transaction can reach consumers before the row is committed, or be sent and then rolled back. Use a transactional outbox (section 6) or `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- **Checked exceptions don't roll back.** By default only `RuntimeException` and `Error` do. Look for `rollbackFor`, and for `catch` blocks that swallow an exception so the transaction commits half-done work.
- **Self-invocation and visibility.** A `@Transactional` method called from another method in the same class runs with no transaction (the proxy is bypassed). See Q9 in the interview sheet.
- **`readOnly = true`** on pure queries is a cheap hint; missing it on a write path is a bug, not a style issue.
- **`REQUIRES_NEW`** starts an independent transaction. It is right for an audit record that must survive a rollback, and dangerous when used to "fix" a locking problem, because it can deadlock against its own outer transaction.
- **Lazy loading** outside the transaction (`LazyInitializationException`) is often "fixed" by open-in-view, which quietly keeps connections open across the whole request.

**Isolation levels in one table.** Most databases default to READ COMMITTED, which is not enough to protect a read-modify-write on a balance (see section 4).

| Level | Prevents | Payments note |
| --- | --- | --- |
| READ COMMITTED | Dirty reads | Default in PostgreSQL. Lost updates are still possible. |
| REPEATABLE READ | Dirty and non-repeatable reads | In PostgreSQL a conflicting concurrent update fails and must be retried. |
| SERIALIZABLE | All anomalies, including write skew | Correct but expensive; the application must retry serialization failures. |

**Bad versus good: paying through an external provider.**

```java
// BAD: remote call and message inside one transaction
@Transactional
public void pay(PayCommand cmd) {
    Payment p = repo.save(Payment.pending(cmd));
    pspClient.charge(p);                              // holds the DB connection, cannot be rolled back
    p.markCaptured();
    kafka.send("payments.captured", p.getId());       // may go out before commit, or after a rollback
}
```

```java
// GOOD: two short transactions around the remote call, event via the outbox
public void pay(PayCommand cmd) {
    Payment p = tx.execute(s -> repo.save(Payment.pending(cmd)));            // tx 1: record intent

    PspResult result = pspClient.charge(p.getId(), p.money(), cmd.idempotencyKey()); // outside any tx

    tx.executeWithoutResult(s -> {                                           // tx 2: record outcome
        Payment fresh = repo.findById(p.getId()).orElseThrow();
        fresh.apply(result);                                                 // guarded state transition
        outbox.save(OutboxEvent.of("payments.captured", fresh));             // same transaction as the state change
    });
}
```

The good version has its own question to answer: what if the process crashes after the provider charged but before transaction 2? The payment stays PENDING, and section 5 covers how to recover it safely. Naming that gap yourself in a review is a strong signal.

## 4. Concurrency and locking

**The bug to recognise: the lost update.** An account has a balance of 100. Two requests each withdraw 60 at the same moment. Both read 100, both check that 100 >= 60, both write 40. One withdrawal vanished and the customer withdrew 120 from an account holding 100. In code it is always the same shape: read, decide in Java, write, with nothing making the three steps atomic.

**Your options, from simplest to heaviest:**

| Approach | How it works | Use when | Watch out |
| --- | --- | --- | --- |
| Atomic conditional update | `UPDATE account SET balance = balance - :a WHERE id = :id AND balance >= :a`, then check that one row changed | A single-row change with a simple rule | Rule must fit in SQL; no entity involved |
| Optimistic locking | `@Version` column; a stale write fails and you retry | Conflicts are rare | Retry must wrap the whole transaction; bounded retries |
| Pessimistic locking | `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) | Conflicts are frequent, retries are costly | Blocks other work; deadlocks if lock order differs |
| Serializable isolation | The database rejects anomalous interleavings | Complex rules across rows | Expensive; you must retry serialization failures |
| Single writer per key | Kafka partition key = account id, one consumer per partition | Event-driven flows | Only orders events for the same key |
| Unique constraint | The database rejects the second insert | "At most one" rules: one payment per idempotency key | Handle the violation as a normal outcome |

**Optimistic locking, the way you'd write it in an interview.** The entity carries a version, and the domain method protects its own invariants:

```java
@Entity
class Account {
    @Id private UUID id;
    @Version private long version;                       // Hibernate adds "and version = ?" to every update
    @Column(precision = 19, scale = 4) private BigDecimal balance;

    void debit(BigDecimal amount) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (balance.compareTo(amount) < 0) throw new InsufficientFundsException(id);
        balance = balance.subtract(amount);
    }
}
```

If two transactions load version 7, the first commit writes version 8 and the second update matches zero rows, so it fails with `ObjectOptimisticLockingFailureException`. Nothing is lost, but the loser must retry, and the retry has to sit **outside** the transaction, because a failed transaction cannot be continued:

```java
@Service
class DebitFacade {                                       // NOT transactional
    private final DebitService service;                   // its debit() is @Transactional

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3,
               backoff = @Backoff(delay = 50, multiplier = 2, random = true))   // jitter
    public void debit(UUID accountId, BigDecimal amount) {
        service.debit(accountId, amount);                 // each attempt = a fresh transaction
    }
}
```

If retries run out, fail clearly (a 409 or a retryable error) rather than looping forever. The retry only works if the operation is safe to repeat, which leads straight to idempotency in section 5.

**Deadlock with pessimistic locks.** A transfer from A to B locks A then B. A simultaneous transfer from B to A locks B then A. Each holds one lock and waits for the other. The fix is a global lock order, for example always lock the lower account id first:

```java
@Transactional
public void transfer(UUID from, UUID to, BigDecimal amount) {
    // one query, locked in a fixed order, so opposite transfers can't deadlock
    Map<UUID, Account> locked = accounts.lockAllOrderedById(List.of(from, to))   // ... ORDER BY id FOR UPDATE
            .stream().collect(toMap(Account::getId, identity()));
    locked.get(from).debit(amount);
    locked.get(to).credit(amount);
}
```

Also treat a database deadlock error as retryable.

**Concurrency traps that only show up with several instances:**

- `synchronized`, `ReentrantLock`, or a `ConcurrentHashMap` inside a service protect one JVM only. With three replicas behind a load balancer they protect nothing across instances.
- `@Scheduled` jobs run on every instance and will process the same rows several times. Use a database-backed scheduler lock (ShedLock), or claim rows with `SELECT ... FOR UPDATE SKIP LOCKED`.
- A distributed lock in Redis is a last resort in payments: without a fencing token, a paused process can act after its lock expired. Prefer database guarantees.
- Check-then-act on existence (`if (!repo.existsByKey(k)) repo.save(...)`) is a race. Let a unique constraint decide and handle the violation.

**Red flags to call out:** a balance or status read, changed in Java and saved with no version, lock or atomic update; a retry loop inside the transaction; locks taken in an arbitrary order; in-memory locks used as cross-instance protection; unbounded retries with no backoff.

## 5. Idempotency and payment flows

**Idempotent means doing it twice has the same effect as doing it once.** In payments, duplicates are guaranteed: clients retry after a timeout, load balancers retry, Kafka redelivers, providers resend webhooks. A design that is only correct when nothing is repeated will charge someone twice.

**Idempotency at the API.** The client sends an `Idempotency-Key` header for a create-payment call. The server stores the key with a hash of the request and, once done, the response. The rules:

- Same key and same request: return the stored result, don't run it again.
- Same key and a different request: reject it (409 or 422). This is a client bug.
- Two identical requests at the same instant: a unique constraint on the key lets exactly one win; the other waits or gets "in progress".
- Keys are scoped per client and expire after a retention period.

```java
@Transactional
public PaymentResponse create(String key, CreatePaymentRequest req) {
    String hash = hasher.sha256(req);

    int claimed = jdbc.sql("""
            insert into idempotency_key(key, request_hash, status)
            values (:k, :h, 'IN_PROGRESS')
            on conflict do nothing
            """).param("k", key).param("h", hash).update();

    if (claimed == 0) {                                   // seen before
        IdempotencyRecord rec = idempotency.get(key);
        if (!rec.requestHash().equals(hash)) throw new IdempotencyConflictException(key);
        return rec.responseOrInProgress();                // replay the stored answer
    }

    Payment p = payments.save(Payment.create(req));
    PaymentResponse response = PaymentResponse.from(p);
    idempotency.complete(key, response);                  // same transaction as the payment
    return response;
}
```

**Unknown outcomes are the hard case.** If the call to the payment provider times out, you don't know whether the customer was charged. The wrong answers are "assume it failed" (risk: money taken, order cancelled) and "just retry" (risk: charged twice). The right approach:

- Send the provider **your own idempotency key** on every attempt, so a retry can't create a second charge.
- Leave the payment in a `PENDING` state and resolve it later by asking the provider for its status, or from its webhook.
- Run a **reconciliation job** that compares your payments with the provider's settlement report and flags differences. It is the safety net when everything else fails.

**Webhooks from the provider.** Verify the signature, deduplicate by event id, and expect events out of order or delayed. Treat the webhook as a hint to fetch the current state, not as the only source of truth.

**Model the payment as a state machine in the domain.** Statuses must not be a free-text field that any code can overwrite. Allowed transitions live in one place, terminal states can't be left, and receiving the same event twice is a no-op:

```java
enum PaymentStatus {
    CREATED, PENDING, AUTHORIZED, CAPTURED, FAILED, CANCELLED, REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED,    Set.of(PENDING, CANCELLED),
            PENDING,    Set.of(AUTHORIZED, FAILED),
            AUTHORIZED, Set.of(CAPTURED, CANCELLED),
            CAPTURED,   Set.of(REFUNDED),
            FAILED,     Set.of(),
            CANCELLED,  Set.of(),
            REFUNDED,   Set.of());

    boolean canMoveTo(PaymentStatus next) { return ALLOWED.get(this).contains(next); }
}

// inside Payment (an entity with @Version)
void moveTo(PaymentStatus next) {
    if (status == next) return;                            // duplicate event: idempotent no-op
    if (!status.canMoveTo(next)) {
        throw new IllegalStateException("Illegal transition " + status + " -> " + next);
    }
    this.status = next;
}
```

**Red flags to call out:** a create-payment endpoint with no idempotency key; retrying a charge with a new key each time; treating a timeout as a failure; `setStatus(...)` callable from anywhere; a webhook handler that trusts its payload without verification or deduplication; no reconciliation path.

&#91;embedded content: payment lifecycle · 7 transitions, 3 final states\]

A timeout leaves the payment in PENDING, the one state that can still resolve either way. FAILED, CANCELLED and REFUNDED accept no further events, and receiving the same event twice is a no-op.

## 6. Kafka in payments

**Assume at-least-once delivery.** A message can be delivered twice, and order is guaranteed only inside one partition. Everything below follows from those two facts: producers must not lose messages, consumers must tolerate duplicates, and events that must stay in order must share a key.

**The producer side.**

- `acks=all` with `min.insync.replicas=2` (replication factor 3) so an acknowledged message survives a broker failure. `acks=1` is a red flag for payments.
- `enable.idempotence=true` so a producer retry can't write a duplicate to the partition.
- **Key by the aggregate id** (payment id or account id). All events of one payment then land on one partition, in order. No key means round-robin partitioning and no ordering.
- Events carry an `eventId`, `occurredAt`, a schema version and a correlation id. The `eventId` is what consumers use to deduplicate.
- Money in a payload is a decimal string (or a schema decimal type) plus a currency, never a `double`.

**The dual-write problem, and the outbox.** Saving to the database and sending to Kafka are two separate systems, so one can succeed while the other fails. If you commit and then crash before sending, the event is lost forever. If you send and then the commit fails, you announced something that never happened. The fix is the **transactional outbox**: write the event to an `outbox` table in the same transaction as the state change, and let a separate relay publish it.

```java
@Transactional
public void capture(UUID paymentId) {
    Payment p = payments.findById(paymentId).orElseThrow();
    p.moveTo(PaymentStatus.CAPTURED);                     // state change
    outbox.save(OutboxEvent.of(                           // event row, SAME transaction
            UUID.randomUUID(), "payment", p.getId().toString(),
            "PaymentCaptured", json.write(PaymentCaptured.from(p))));
}
```

The relay is either a scheduled poller (`SELECT ... WHERE published = false ORDER BY id FOR UPDATE SKIP LOCKED`, send, mark published) or change-data-capture with Debezium reading the outbox table. Either way the relay can crash after sending and before marking, so it is at-least-once, which is why consumers must deduplicate.

**The consumer side.**

- **Idempotent processing.** Record the `eventId` in a `processed_event` table in the same transaction as the business effect. A duplicate hits the primary key and is skipped.
- **Commit the offset only after the work is done.** `enable.auto.commit=false`, and no asynchronous hand-off that lets the listener return before the work finishes. A crash then means redelivery, never loss.
- **A listener that catches and logs every exception is a data-loss bug.** The offset advances and the message is gone. Let the exception propagate to the error handler.
- **Rebalances cause duplicates.** If processing outlasts `max.poll.interval.ms`, the consumer is kicked out and its batch is redelivered elsewhere. Keep processing short and tune the interval to it.
- **`auto.offset.reset=latest`** on a new consumer group silently skips everything published before it started. For most payment consumers you want `earliest`.

```java
@KafkaListener(topics = "payments.captured", groupId = "ledger")
@Transactional
public void on(PaymentCaptured e) {
    int inserted = jdbc.sql("""
            insert into processed_event(event_id) values (:id)
            on conflict do nothing
            """).param("id", e.eventId()).update();
    if (inserted == 0) return;                            // duplicate: skip
    ledger.record(e.paymentId(), e.money());              // same transaction as the insert
}
```

**Failures, retries and dead-letter topics.**

- Classify errors. **Transient** ones (a database timeout) deserve a few retries with backoff. **Permanent** ones (a payload that can't be deserialised, a validation error) should skip retries and go straight to the dead-letter topic. In Spring Kafka this is a `DefaultErrorHandler` with a `BackOff`, a `DeadLetterPublishingRecoverer`, and non-retryable exception types registered.
- Wrap deserializers in `ErrorHandlingDeserializer` so one malformed record (a poison pill) can't block a partition forever.
- Blocking retries keep order for the key but stall that partition; non-blocking retry topics (`@RetryableTopic`) keep the partition moving but break ordering for the key. For payments, prefer a few bounded blocking retries, then the dead-letter topic.
- A dead-letter topic is not a bin. Alert on it, keep the original headers, and have a tested way to replay.
- **Kafka transactions and "exactly-once"** cover consume-transform-produce between Kafka topics only. They don't include your database, so database plus Kafka still needs the outbox and idempotent consumers.

```yaml
spring:
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 120000
    consumer:
      enable-auto-commit: false
      auto-offset-reset: earliest
      properties:
        max.poll.interval.ms: 300000
    listener:
      ack-mode: record
```

**Red flags to call out:** `kafkaTemplate.send` inside a `@Transactional` method; no message key; `acks=1` or `acks=0`; auto-commit on; a `catch (Exception e) { log.error(...) }` around the listener body; unbounded retries; a `double` amount in the payload; no dead-letter handling; no `eventId` to deduplicate on.

&#91;embedded content: payment event flow · 7 guards, 1 dead-letter path\]

Read it top to bottom: steps 1 to 3 make the write and the event atomic, step 4 keeps order and durability in Kafka, and steps 5 to 7 make the consumer safe under redelivery. No single step gives exactly-once; together they give the same effect.

## 7. Architecture and API design

**Keep a ledger you can audit.** Money records are append-only. A double-entry ledger stores every movement as a pair of entries (a debit on one account, a credit on another) that are never updated or deleted. A mistake is fixed by a new reversing entry, not by editing history. The balance is the sum of the entries, or a cached value that can always be recomputed and checked against them. Every state change also records who, what and when, because auditors and disputes will ask.

**Layers with clear jobs:**

- **Controller:** thin. Validates input, calls one service method, maps the result. No business rules, no repository calls.
- **Service:** owns the transaction boundary and orchestrates. One public method per business operation.
- **Domain objects:** hold the rules (`Account.debit`, `Payment.moveTo`), so invariants can't be bypassed from elsewhere.
- **Kafka listeners:** thin adapters, like controllers. They call the same service methods, and business logic never lives inside the listener.

**DTOs, not entities, at the boundary.** Returning a JPA entity from a controller leaks internal fields, triggers lazy-loading surprises, and welds your database schema to your public API. Use separate request and response types, and validate on the way in:

```java
public record CreatePaymentRequest(
        @NotNull UUID orderId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) { }

@PostMapping("/payments")
ResponseEntity<PaymentResponse> create(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CreatePaymentRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(key, req));
}
```

Never accept a client-supplied status, fee or balance. The server computes those.

**A consistent error model.** Return RFC 9457 `ProblemDetail` responses from one `@RestControllerAdvice`, with meaningful status codes: 400 or 422 for invalid input, 404 for an unknown resource, 409 for a state or version conflict or a reused idempotency key with a different body, 402 or 422 for insufficient funds, and never a 200 with an error message inside. Don't leak stack traces or SQL errors.

**Time.** Store timestamps as `Instant` (UTC) and inject a `Clock` so tests can control it. `LocalDateTime` has no zone, and `new Date()` in business logic is untestable.

**Service boundaries.** A service owns its data; no other service reads its tables. Use a synchronous call when the caller needs the answer now, and events when others need to react to something that happened. A workflow across services (order, stock, payment) is a saga with compensations, as in Q2 of the interview sheet. Configuration such as fee rates, limits and timeouts lives in properties, not in constants scattered through the code.

**Red flags to call out:** entities returned from controllers; business rules inside listeners or controllers; a mutable `balance` column with no ledger behind it; timestamps as `String` or `LocalDateTime`; `catch (Exception)` that returns a generic 500 or 200; field injection with `@Autowired` (harder to test than constructor injection); hard-coded rates and limits; a shared database between services.

## 8. Security, PII and logging

**Logging is where payment code leaks most often.** Never log card numbers (PAN), CVV, tokens, passwords, or full request and response bodies. Log identifiers (payment id, order id, correlation id), not personal data. The subtle leak is `toString`: a Java `record` or a Lombok `@Data` class prints every field, so `log.info("Processing {}", request)` writes the card number into your logs.

```java
// BAD: the record's generated toString includes the card number
log.info("Processing payment {}", request);

// GOOD: identifiers only, plus the correlation id from MDC
log.info("Processing payment orderId={} amount={} {}", req.orderId(), req.amount(), req.currency());
```

For types that carry sensitive fields, override `toString` to mask them or exclude the field, and keep a request/response logging filter from dumping bodies.

**Card data (PCI).** Never store the CVV at all. Avoid handling raw card numbers in your own services: let the payment provider's hosted fields or SDK collect them and pass you a token. If a service ever stores a PAN, it must be encrypted and masked on display (first six and last four at most), and that alone puts the service in a much heavier compliance scope, so a review should ask why.

**Authorization, not just authentication.** The classic bug is an insecure direct object reference: `GET /payments/{id}` returns any payment to any logged-in user. Check that the payment belongs to the caller (or that the caller has the right role) in the service layer, and use method security such as `@PreAuthorize`. Validate the token's audience and scopes on a resource server.

**Trust nothing from the client:**

- Recompute prices, fees and totals on the server. Never accept an amount for an order from the request.
- Use parameterised queries only. String concatenation in SQL is an injection bug, even in a `JdbcTemplate` call.
- Verify webhook signatures using a constant-time comparison (`MessageDigest.isEqual`), and reject old timestamps to stop replays.
- Rate-limit payment and login endpoints.

**Secrets and exposure.** No keys, passwords or connection strings in source or committed config; use environment variables or a secret manager. Limit Actuator endpoints to health and metrics behind authentication; `env` and `heapdump` expose secrets. Error responses must not include stack traces, SQL or internal hostnames.

**Red flags to call out:** request or entity objects logged whole; a `cardNumber` or `cvv` field on an entity; an endpoint that fetches by id with no ownership check; SQL built with `+`; a hard-coded API key; a webhook handler with no signature check; an amount or price read from the request; `==` or `equals` used to compare signatures.

## 9. Testing and observability

**What a payments service must have tests for:**

- **Money math:** rounding, allocation (the parts sum to the total), currency mismatch. Plain unit tests, ideally with a property-style check that splits never lose a cent.
- **State machine:** every legal transition, and that illegal ones and duplicate events behave as designed.
- **Concurrency:** two or more threads against a real database, asserting the final state.
- **Idempotency:** the same request key twice creates one payment; the same event twice has one effect.
- **Failure paths:** provider timeout, provider error, consumer failure ending in the dead-letter topic, rollback leaving no partial data.
- **Contracts:** API and event schemas checked between teams (Spring Cloud Contract or a schema registry).

**Test concurrency for real.** Use a real PostgreSQL through Testcontainers, not H2: locking and isolation behave differently, and an in-memory database will happily hide the bug you're looking for. Release all threads at once with a latch so they genuinely collide:

```java
@Test
void concurrentWithdrawalsNeverOverdraw() throws Exception {
    accounts.save(new Account(id, new BigDecimal("100.00")));
    int threads = 8;
    var pool = Executors.newFixedThreadPool(threads);
    var start = new CountDownLatch(1);

    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
        results.add(pool.submit(() -> {
            start.await();                                   // all threads go at the same moment
            try {
                facade.debit(id, new BigDecimal("60.00"));
                return true;
            } catch (InsufficientFundsException | ObjectOptimisticLockingFailureException e) {
                return false;
            }
        }));
    }
    start.countDown();

    long succeeded = 0;
    for (Future<Boolean> f : results) if (f.get()) succeeded++;
    pool.shutdown();

    assertThat(succeeded).isEqualTo(1);                       // exactly one withdrawal wins
    assertThat(accounts.findById(id).orElseThrow().balance())
            .isEqualByComparingTo("40.00");                   // compareTo, not equals
}
```

For Kafka, run a real broker with Testcontainers, publish the same event twice, and use Awaitility (not `Thread.sleep`) to wait for the effect, then assert it happened once.

**Observability tells you when the guarantees fail in production:**

- **Metrics (Micrometer):** payments created, succeeded and failed by reason; provider latency; outbox backlog; consumer lag; dead-letter volume; retry counts.
- **Business alerts:** payments stuck in `PENDING` longer than a threshold; reconciliation mismatches; the circuit breaker opening.
- **Correlation:** put a correlation id in the MDC, on every log line, and in Kafka headers, so one payment can be followed across services. Add distributed tracing with OpenTelemetry.

**Red flags to call out:** no tests around the money or state-transition code; concurrency "tested" only with mocks; H2 standing in for PostgreSQL for locking behaviour; `Thread.sleep` in tests; no metric or alert for payments stuck mid-flow; assertions on `BigDecimal` with `equals`.

## 10. Practice review: a small payments service

This is a realistic multi-class service with many planted problems. Set a 20-minute timer, review it out loud using the order from section 1, write down each finding with a severity, a concrete failure scenario and a fix, and only then open the answer key below. Assume Spring Boot, JPA with PostgreSQL, Kafka, and several running instances.

**Domain classes (getters and setters omitted):**

```java
@Entity
public class Account {
    @Id @GeneratedValue private Long id;
    private String ownerId;
    private double balance;
}

@Entity
public class Payment {
    @Id @GeneratedValue private Long id;
    private Long accountId;
    private double amount;
    private String status;                 // "PENDING", "COMPLETED", "REFUNDED"
    private Date createdAt = new Date();
}

public record PaymentRequest(Long accountId, double amount, String cardNumber, String cvv) { }
```

**PaymentController:**

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;

    @PostMapping
    public Account pay(@RequestBody PaymentRequest request) {
        log.info("Payment request: {}", request);
        try {
            return paymentService.pay(request);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/{id}")
    public Payment get(@PathVariable Long id) {
        return paymentRepository.findById(id).get();
    }
}
```

**PaymentService:**

```java
@Service
public class PaymentService {

    @Autowired private AccountRepository accounts;
    @Autowired private PaymentRepository payments;
    @Autowired private PspClient psp;
    @Autowired private KafkaTemplate<String, String> kafka;

    @Transactional
    public Account pay(PaymentRequest req) {
        Account acc = accounts.findById(req.accountId()).get();
        if (acc.getBalance() >= req.amount()) {
            acc.setBalance(acc.getBalance() - req.amount());
            accounts.save(acc);

            Payment p = payments.save(new Payment(acc.getId(), req.amount(), "PENDING"));
            try {
                psp.charge(req.cardNumber(), req.cvv(), req.amount());
                p.setStatus("COMPLETED");
                kafka.send("payments.completed",
                        "{\"paymentId\":" + p.getId() + ",\"amount\":" + req.amount() + "}");
            } catch (Exception e) {
                log.warn("Charge failed, will retry later");
            }
        }
        return acc;
    }

    @Transactional
    public void refund(Long paymentId) {
        Payment p = payments.findById(paymentId).get();
        if (p.getStatus().equals("COMPLETED")) {
            Account acc = accounts.findById(p.getAccountId()).get();
            acc.setBalance(acc.getBalance() + p.getAmount());
            p.setStatus("REFUNDED");
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void retryPending() {
        for (Payment p : payments.findByStatus("PENDING")) {
            pay(new PaymentRequest(p.getAccountId(), p.getAmount(), null, null));
        }
    }
}
```

**PaymentEventListener and LedgerService:**

```java
@Component
public class PaymentEventListener {

    @Autowired private LedgerService ledger;
    private final ObjectMapper mapper = new ObjectMapper();

    @KafkaListener(topics = "payments.completed", groupId = "ledger")
    public void on(String message) {
        try {
            PaymentEvent e = mapper.readValue(message, PaymentEvent.class);
            ledger.record(e.paymentId(), e.amount());
        } catch (Exception ex) {
            log.error("Could not process message", ex);
        }
    }
}

public record PaymentEvent(Long paymentId, double amount) { }

@Service
public class LedgerService {
    private final Map<Long, Double> entries = new HashMap<>();

    public void record(Long paymentId, double amount) {
        entries.put(paymentId, amount);
    }
}
```

**application.yml (excerpt):**

```yaml
spring:
  kafka:
    producer:
      acks: 1
    consumer:
      enable-auto-commit: true
      auto-offset-reset: latest
```

### Answer key

Don't read this until you have your own list. Rows are ordered roughly by how you should present them.

| # | Severity | Where | Finding and impact | Fix |
| --- | --- | --- | --- | --- |
| 1 | Blocker | `pay` | Negative amount passes `balance >= amount`, and `balance - amount` then increases the balance: free money on demand. | Validate `@Positive`, a maximum and a currency at the API and inside the domain method. |
| 2 | Blocker | `pay`, `Account` | Lost update: read, check and write of `balance` with no `@Version`, lock or atomic update. Two concurrent payments both pass the check and overdraw the account. | Conditional `UPDATE ... WHERE balance >= :a`, or `@Version` with retry outside the transaction. |
| 3 | Blocker | All classes | `double` for money, and no currency anywhere. Rounding drift and ambiguous amounts. | `BigDecimal` plus `Currency` (a `Money` type), `NUMERIC` columns. |
| 4 | Blocker | `pay` | A provider timeout is caught and logged, the payment stays `PENDING`, yet the balance was already debited and committed. The customer may or may not have been charged, and nobody knows. | Never swallow. Send a provider idempotency key, resolve `PENDING` by status lookup or webhook, add reconciliation. |
| 5 | Blocker | `retryPending` | The retry calls `pay` again, which debits the account a second time and charges again (with null card data and no idempotency key). Double debit and possible double charge. | Retry only the provider call for the same payment, with the same key. Never re-run the debit. |
| 6 | Blocker | `PaymentRequest`, controller | Card number and CVV travel through the service, and `log.info(request)` prints them via the record's `toString`. Compliance breach. | Use provider tokens, never handle or store CVV, never log request objects. |
| 7 | Blocker | `PaymentEventListener` | The catch-all swallows every exception, so the offset is committed and the message is lost. | Let the exception propagate to a `DefaultErrorHandler` with backoff and a dead-letter topic. |
| 8 | Blocker | Controller `get` | No ownership check: any caller can read any payment by id (IDOR). It also returns the entity, and `.get()` turns a missing row into a 500. | Check the owner, return a DTO, `orElseThrow` mapped to 404. |
| 9 | Major | `pay` | Kafka send inside the transaction, before commit. The event can go out and the transaction roll back, so consumers act on a payment that doesn't exist. | Transactional outbox. |
| 10 | Major | `pay` | Remote call inside `@Transactional` holds a connection and a row lock during a slow call and can exhaust the pool. | Two short transactions around the call (section 3). |
| 11 | Major | `retryPending` | `@Scheduled` runs on every instance, so replicas retry the same rows at once. `this.pay()` also bypasses the proxy, so there is no transaction per item. | ShedLock or `FOR UPDATE SKIP LOCKED`; call a transactional method on another bean. |
| 12 | Major | Event payload | No message key (no ordering), JSON built by string concatenation, a `double` amount, no `eventId` to deduplicate on. | Key by payment id; a typed event with `eventId`, currency and schema version. |
| 13 | Major | Listener | No deduplication. Redelivery is processed again; here the map `put` hides it, but any additive effect would double. | `processed_event` insert in the same transaction as the effect. |
| 14 | Major | `LedgerService` | A `HashMap` mutated by listener threads without synchronisation; state held in memory, lost on restart, not shared across instances, and not a ledger at all. | Append-only ledger table written transactionally. |
| 15 | Major | `application.yml` | `acks: 1` can lose acknowledged messages on leader failover; auto-commit acknowledges before processing finishes; `latest` skips messages for a new consumer group. | `acks=all` with idempotence; commit after processing; `earliest`. |
| 16 | Major | Controller `pay` | `catch (Exception) { return null; }` gives a 200 with an empty body, so failures are invisible. The service also returns the unchanged account when funds are insufficient. | Throw domain exceptions; map them to 409 or 422 in a `@RestControllerAdvice`. |
| 17 | Major | Controller `pay` | No `@Valid`, no `Idempotency-Key`, so a client retry creates a second payment. Field injection and a repository inside the controller. | Bean Validation, idempotency key, constructor injection, controller calls the service only. |
| 18 | Major | `refund` | Two concurrent refunds both see `COMPLETED` and both credit the account. Status is a free string, there is no state machine, and `.get()` can throw. | `@Version` on `Payment` plus a guarded transition, atomic credit, one refund per payment enforced by a constraint. |
| 19 | Minor | `Payment`, `Account` | `Date` instead of `Instant`, string statuses, no audit trail, no ledger entries behind the balance. | `Instant`, an enum state machine, an append-only ledger. |
| 20 | Minor | Whole service | No metrics, alerts for stuck `PENDING` payments, or reconciliation; no tests are shown. | Micrometer counters, alerts, tests from section 9. |

**How to present it in ten minutes.** Open with the headline, then go through the blockers, then say what you'd check next:

1. "This service can lose money, duplicate money, and leak card data." Then give three findings that prove it: negative amounts and the lost update on the balance (rows 1, 2), the retry that debits and charges again (rows 4, 5), and card data in logs (row 6).
2. "It can also lose events or act on events that never happened": the swallowing listener, the send before commit, the Kafka settings (rows 7, 9, 15).
3. Security and API basics: the ownership check and the swallowed errors in the controller (rows 8, 16, 17).
4. Design and operations in one breath: layering, time and status types, the in-memory ledger, no metrics or tests (rows 14, 19, 20).
5. Close with what you would do first: fix rows 1 to 6 before anything else, because they can cost real money today.

**The shape of the corrected `pay` flow.** You don't need to rewrite the class in the interview, but be ready to sketch this:

```java
public PaymentResponse pay(String idempotencyKey, PayCommand cmd) {
    Payment p = intake.startOrGet(idempotencyKey, cmd);          // tx 1: unique key, status PENDING
    if (p.isFinal()) return PaymentResponse.from(p);             // duplicate request: replay the outcome

    if (accounts.debitIfSufficient(cmd.accountId(), cmd.money()) == 0) {   // atomic conditional update
        return PaymentResponse.from(intake.reject(p, "INSUFFICIENT_FUNDS"));
    }

    PspResult result = psp.charge(p.getId(), cmd.token(), cmd.money());    // outside any transaction;
                                                                           // provider key = payment id, a token, not a card
    return PaymentResponse.from(intake.complete(p, result));     // tx 2: guarded state transition,
                                                                 // release the funds if it failed,
                                                                 // outbox event in the same transaction
}
```

## 11. One-page checklist and ready-made comments

Skim this last, the morning of the interview. Work down it in this order for every class you open.

**Money**

- [ ] Any `double` or `float` near an amount? Is the currency carried with it?
- [ ] `BigDecimal` compared with `equals`, or divided without a rounding mode?
- [ ] Negative, zero or huge amounts rejected at the edge and in the domain?

**Concurrency and transactions**

- [ ] Read, decide in Java, write, with no `@Version`, lock or atomic update?
- [ ] Is a remote call or a Kafka send inside the transaction?
- [ ] Retry inside the transaction, or unbounded? Locks taken in a consistent order?
- [ ] `@Transactional` on a self-called or non-public method? Exceptions swallowed?
- [ ] In-memory locks or state used to protect data across several instances?
- [ ] Scheduled jobs that would run on every replica?

**Idempotency and Kafka**

- [ ] Can this request, retry or event be processed twice with a different result?
- [ ] Event sent before commit? Outbox missing?
- [ ] Message key, `eventId`, typed payload, decimal amount?
- [ ] Listener swallowing exceptions? Dead-letter handling? Offsets committed after the work?
- [ ] `acks`, idempotent producer, `auto-offset-reset`, auto-commit?

**Security and API**

- [ ] Card data, tokens or whole requests in logs? A `toString` that prints secrets?
- [ ] Ownership check on every read and write by id? Server-side recomputation of amounts?
- [ ] Entities exposed in the API? Validation? Sensible status codes and error model?

**Design, tests and operations**

- [ ] Status as a free string instead of a guarded state machine?
- [ ] `Date` or `LocalDateTime` where `Instant` and an injected `Clock` belong?
- [ ] Tests on the money path, concurrency and duplicates? Metrics and alerts for stuck payments?

**Comment phrasings you can reuse.** Each one names the place, the failure and the fix:

| Situation | What to say |
| --- | --- |
| Lost update | "In `pay`, the balance is read, compared and written with no lock or version. Two concurrent payments can both pass the check and overdraw the account. I'd use a conditional update, or `@Version` with a retry outside the transaction." |
| Remote call in a transaction | "The provider call runs inside the transaction, so a slow call holds a connection and a row lock, and a rollback can't undo a charge. I'd split it into two short transactions around the call." |
| Event before commit | "The event is sent before the commit, so consumers can act on data that gets rolled back. I'd write it to an outbox in the same transaction and publish from there." |
| Swallowed listener exception | "This catch-all logs and continues, so the offset advances and the message is lost. I'd let it propagate to an error handler with bounded retries and a dead-letter topic." |
| Retry without idempotency | "On a timeout we can't know if the charge happened, and the retry uses no idempotency key, so the customer can be charged twice. I'd send a key, resolve `PENDING` by asking the provider, and reconcile daily." |
| Money as `double` | "Amounts are `double`, which can't represent most decimals exactly and has no currency. I'd use `BigDecimal` with a currency, in a small `Money` type." |
| Missing ownership check | "Any authenticated user can fetch any payment by id. I'd verify the payment belongs to the caller before returning it." |
| Sensitive data in logs | "This logs the whole request, and a record's `toString` includes the card number. I'd log identifiers only and stop handling raw card data." |

When you don't know something, say it and reason to an answer: "I'm not sure how this library behaves here, but I'd verify it because the failure would be a double charge." A clear, honest review beats a confident wrong one.
