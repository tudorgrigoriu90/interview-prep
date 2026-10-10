# Payments Interview Handbook: Java, Spring Boot, Microservices, MySQL, Kafka

A rereading guide for a payments-focused code review and design interview (iGaming / betting, Sweden).
Each section has: the concepts, the traps, the fix, and **what to say in the interview**.
Section 0 is the one to reread the morning of the interview.

---

## 0. Top mandatory notions (read this first)

If you only remember twenty things, remember these.

1. **Money is `BigDecimal` + `Currency`** (or `long` minor units). Never `double`. Compare with `compareTo`, never `equals`. Every `divide` has a scale and a `RoundingMode`. Round **once, at the end**, to the currency's digits.
2. **Check the result of every conditional write.** `UPDATE ... WHERE balance >= :amount` is atomic, but if you ignore the returned row count you have no guard at all.
3. **A read followed by a write is a race** unless something makes it atomic: a conditional update, a lock (`SELECT ... FOR UPDATE`), `@Version`, or a unique constraint.
4. **Name the row where two writers collide.** "Two concurrent withdrawals on the same wallet row" beats "add optimistic locking everywhere".
5. **`@Transactional` works only through the Spring proxy.** Self-invocation (`this.method()`) skips it. Checked exceptions do **not** roll back by default.
6. **No remote call inside a transaction that holds locks.** Split into short transactions around the call.
7. **A timeout is an unknown outcome, not a failure.** Keep the payment `PENDING` and resolve it (lookup, webhook, reconciliation). Never mark it failed and let the client retry with a new key.
8. **Idempotency keys:** required, scoped per client (player/merchant), stored under a unique constraint, the stored outcome is replayed, the payload is compared. Pass an idempotency key to the PSP too.
9. **Reserve first, settle later.** Debit the wallet and record `PENDING` in one transaction, then pay out. A debit can be refunded; a sent payout can't be recalled.
10. **State machine with guarded transitions:** `UPDATE ... SET status = 'COMPLETED' WHERE id = :id AND status = 'PENDING'`, continue only if 1 row. This gives exactly-once effects across instances and retries.
11. **Kafka is at-least-once.** Consumers must be idempotent (processed-event table or natural key with a unique constraint, in the same transaction as the effect).
12. **DB write + Kafka send is never atomic.** Use the transactional outbox. Kafka transactions do not include your database.
13. **Ordering in Kafka is per partition only.** The producer's **key** picks the partition. The key defines the ordering domain (per player, per payment). The publisher must also send in order.
14. **Producer:** `acks=all`, `enable.idempotence=true`. **Consumer:** commit offsets after processing, `DefaultErrorHandler` with backoff and a dead-letter topic, never catch-and-log.
15. **`@Scheduled` runs on every replica.** Use ShedLock or claim rows atomically.
16. **In-JVM locks (`synchronized`, `ReentrantLock`, `ConcurrentHashMap`) protect one JVM**, not three instances.
17. **Isolation is not a fix by itself.** Know the defaults: MySQL InnoDB REPEATABLE READ, PostgreSQL / Oracle / SQL Server READ COMMITTED. Plain `SELECT` under MySQL RR is a snapshot; `UPDATE` and locking reads see the latest committed row.
18. **Never trust the client for money:** validate (`@Valid`), recompute amounts server-side, check ownership (IDOR), check currency against the account.
19. **PII and card data:** never log request objects (records print every field), never store CVV, tokenise cards, mask PAN, secrets out of the repo.
20. **Tests:** concurrency tests with a start latch on a real database (Testcontainers), expected values from the business rule, `isEqualByComparingTo` for money, Awaitility instead of `Thread.sleep`.

### Interview behaviour
- **Orient for 1 to 2 minutes:** what the service does, entry points, state it changes, external calls. Then ask: how many instances? which isolation level / DB vendor? Kafka delivery guarantee? is the PSP idempotent?
- **Review in order of damage:** money correctness, concurrency, transactions, idempotency and messaging, security, design, tests.
- **For each finding say:** where, what, a concrete failure with numbers, severity, smallest fix. Example: "In `WithdrawalService.request`, the result of `debit` is ignored. Balance 500, two concurrent requests of 300: both pass the early check, one debit matches 0 rows, both withdrawals are saved, 600 is paid for 300 debited. Blocker. Throw when the update returns 0."
- **Verify before you claim.** Read the annotation, follow the injected bean, check the config. A false alarm costs credibility.
- **Rounding is not a nit** when it changes what every customer pays or makes common requests fail.
- **When short on time,** list what you would check next.

---

## 1. Severity scale and how to phrase findings

| Severity | Meaning | Example |
|---|---|---|
| Blocker | Loses, creates or duplicates money; leaks card data | Lost update on balance, double charge on retry, CVV in logs |
| Major | Wrong under failure or load; wrong amounts for many | Kafka send before commit, remote call under a row lock, fee rate rounded |
| Minor | Correct but fragile, unclear, or partial | Stale value in a response, magic strings, config smell |
| Nit | Style | Naming, formatting |

Phrase it as a scenario, not an opinion:
- Weak: "This isn't thread-safe."
- Strong: "Two requests for the same wallet both read 500, both pass the check, both write. The second overwrites the first, so one debit is lost. Blocker. Conditional `UPDATE` with `balance >= :amount`, check the row count."

---

## 2. Money and precision

### Rules
- `BigDecimal` from **strings** (`new BigDecimal("0.10")`) or `BigDecimal.valueOf(double)`. `new BigDecimal(0.1)` captures binary error.
- `equals` compares scale: `2.0` vs `2.00` is false. Use `compareTo`. This also breaks `HashSet`/`HashMap` keys and replay checks.
- `divide(x)` without scale throws `ArithmeticException` for non-terminating results (1/3, most FX rates). Always `divide(x, scale, RoundingMode)`.
- Round **once**, at the end. Rounding an intermediate value changes the business rule (2.5 / 100 rounded to 2 decimals is 0.03, so a 2.5% fee becomes 3%).
- Scale from the currency: `Currency.getDefaultFractionDigits()` (JPY 0, EUR 2, KWD 3). Do not hard-code 2.
- Rounding mode is a business decision: `HALF_EVEN` (banker's), `HALF_UP`, `DOWN` for payouts. Pick one per rule, document it.
- Minimums and limits are **per currency** (1.00 EUR is not 1 JPY).
- Splitting money: allocate the remainder so the parts sum to the total (100.00 / 3 = 33.34 + 33.33 + 33.33).
- `stripTrailingZeros().scale() > digits` is the portable check for "too many decimals" (`100` has scale -2 after stripping).
- `toPlainString()` for text; `stripTrailingZeros()` alone can give `1E+2`.

### At the boundaries
- **DB:** `DECIMAL(19,4)` / `NUMERIC(19,4)` or `BIGINT` minor units. Never rely on the database to round (behaviour differs by vendor and `sql_mode`).
- **JSON:** `BigDecimal` fields, or amounts as strings, always with a currency. Never `double` in DTOs.
- **Events:** amount as a string (`toPlainString()`), currency, event id, schema version.

### Value object
```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY); // throws if too many decimals
    }
    public Money plus(Money o) { same(o); return new Money(amount.add(o.amount), currency); }
    public Money minus(Money o) { same(o); return new Money(amount.subtract(o.amount), currency); }
    public Money percent(BigDecimal pct, RoundingMode mode) {
        return new Money(amount.multiply(pct).divide(BigDecimal.valueOf(100),
                currency.getDefaultFractionDigits(), mode), currency);
    }
    private void same(Money o) {
        if (!currency.equals(o.currency)) throw new IllegalArgumentException("Currency mismatch");
    }
}
```

**Say:** "Amounts are BigDecimal with a currency, scale from the currency, compared with compareTo, every division states its scale and rounding mode, and I round once at the end."

---

## 3. Transactions in Spring

### How `@Transactional` works
- Spring wraps the bean in a **proxy**. The transaction starts when a call **enters through the proxy**.
- **Self-invocation** (`this.save()` inside the same class) skips the proxy: the annotation is ignored. Fix: move the method to another bean, inject a `TransactionTemplate`, or restructure.
- Methods must be reachable through the proxy (public; Spring 6 also handles protected/package-private on class-based proxies, never private).
- **Rollback rules:** by default rolls back on `RuntimeException` and `Error`, **commits on checked exceptions**. Use `rollbackFor = Exception.class` or throw unchecked.
- **Swallowed exceptions:** catching inside the transaction and continuing commits partial work. If a JPA exception was thrown, the transaction is already marked rollback-only, and you get `UnexpectedRollbackException` at commit.
- `readOnly = true`: a hint (flush mode manual, read-only connection on some drivers). Spring Data repository query methods default to read-only, so a `@Modifying` query called with no outer transaction needs `@Transactional`.
- `timeout`: set it on long operations; default is none.

### Propagation
| Propagation | Behaviour | Use / trap |
|---|---|---|
| `REQUIRED` (default) | Join or create | Normal case |
| `REQUIRES_NEW` | Suspend outer, new transaction, **new connection** | Audit logs that must survive rollback. Trap: needs a second pool connection per thread, which can exhaust the pool and deadlock under load |
| `NESTED` | Savepoint in the same transaction | JDBC only, not with JPA in most setups |
| `MANDATORY` | Fail if none | Guard methods that must run inside a transaction |
| `SUPPORTS` / `NOT_SUPPORTED` / `NEVER` | Rare | |

### Boundaries
- Keep transactions short. **No HTTP/PSP/FX calls inside** a transaction that has written (it holds row locks and a pool connection until commit).
- Pattern: tx 1 (create `PENDING`, commit) → remote call (no tx) → tx 2 (guarded transition, commit).
- **Kafka send inside the transaction** can publish an event for a transaction that then rolls back. Use the outbox.
- `@TransactionalEventListener(phase = AFTER_COMMIT)`: runs after commit, synchronously, outside the transaction. No ghost events, but a crash between commit and send loses the event.

### JPA and Hibernate specifics
- **Persistence context:** entities loaded in a transaction are cached; repeated `findById` returns the same instance.
- **Dirty checking:** changes to managed entities are flushed at commit, without calling `save`.
- **Bulk JPQL updates (`@Modifying`)** bypass the persistence context and `@Version`. Use `clearAutomatically` / `flushAutomatically`, or re-read.
- **Open Session in View** (`spring.jpa.open-in-view`, default true): keeps the EntityManager open for the whole HTTP request, hides lazy-loading problems, holds connections. Set it to `false`.
- **`ddl-auto: update`** is for local use. Production: Flyway/Liquibase and `validate`.
- **IDENTITY ids** disable Hibernate JDBC batch inserts. Use sequences where available, or application-generated ids (UUIDv7, TSID).

### Isolation levels
| Level | Dirty read | Non-repeatable read | Phantom | Lost update | Write skew |
|---|---|---|---|---|---|
| READ UNCOMMITTED | possible | possible | possible | possible | possible |
| READ COMMITTED | no | possible | possible | possible | possible |
| REPEATABLE READ | no | no | standard: possible; InnoDB: prevented for locking reads (next-key locks) | **MySQL: possible (no error)**; PostgreSQL: detected, throws serialization error | possible |
| SERIALIZABLE | no | no | no | no | no |

Definitions in one line each:
- **Dirty read:** you see another transaction's uncommitted change.
- **Non-repeatable read:** reading the same row twice gives different values.
- **Phantom:** re-running a range query returns new rows.
- **Lost update:** two read-modify-write cycles; the second overwrites the first.
- **Write skew:** two transactions read overlapping data, each writes a different row, together they break an invariant (two doctors both go off call).

Defaults: **MySQL InnoDB REPEATABLE READ**; PostgreSQL, Oracle, SQL Server READ COMMITTED (SQL Server locking RC unless RCSI is on).

**Say:** "Raising the isolation level is rarely my first fix. I prefer an explicit guard: a conditional update or a lock on the row that matters. Serializable behaves differently per vendor and needs retries."

---

## 4. Concurrency and locking

### The decision table
| Technique | How | Good for | Watch out |
|---|---|---|---|
| **Atomic conditional update** | `UPDATE wallet SET balance = balance - :a WHERE id = :id AND balance >= :a` → check row count | Hot rows (balances, counters, status transitions) | Must check the returned count; bulk updates bypass `@Version` and the persistence context |
| **Optimistic locking** | `@Version` column; update fails if the version changed | Low contention, long user think time, aggregate updates | Throws `ObjectOptimisticLockingFailureException`; **retry outside the transaction** (a new transaction re-reads); many retries under contention |
| **Pessimistic locking** | `@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE` | High contention, complex checks across several rows | Lock waits, deadlocks; set a lock timeout; lock rows in a fixed order |
| **Unique constraint** | DB rejects the duplicate | Idempotency keys, one refund per payment, one ledger entry per event | Catch the violation outside the failed transaction |
| **Distributed lock** | ShedLock (DB), Redis (Redisson) | Scheduled jobs, single-runner tasks | Leases expire (GC pause, slow run); use fencing tokens for correctness |
| **Serializable isolation** | DB-level | Rare, complex invariants | Vendor-specific behaviour, needs retries |

### Optimistic locking in Spring
```java
@Entity class Wallet { @Version Long version; ... }

// retry OUTSIDE the transaction: each attempt is a fresh transaction that re-reads
@Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3,
           backoff = @Backoff(delay = 20, multiplier = 2, random = true))
public void debit(...) { txTemplate.executeWithoutResult(s -> walletService.debitInTx(...)); }
```
- The retry must wrap the whole transaction. A retry inside the transaction sees the same stale state.
- Entity updates include `WHERE version = ?`. JPQL bulk updates do not, unless written `update versioned`.
- At the API level the same idea is `ETag` + `If-Match` → `412 Precondition Failed`.

### Pessimistic locking in Spring
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("select w from Wallet w where w.id = :id")
Optional<Wallet> findForUpdate(@Param("id") Long id);
```
- The lock lives until commit, so keep the transaction short.
- `NOWAIT` (fail immediately) and `SKIP LOCKED` (skip rows other transactions hold) exist on MySQL 8+, PostgreSQL, Oracle; SQL Server uses `READPAST`.
- **Deadlock:** transaction A locks wallet 1 then 2, B locks 2 then 1. Fix: lock in a fixed order (by id), keep transactions short, retry on deadlock errors (MySQL 1213).

### Check-then-act
Any "if not exists then insert" or "if status == X then change" across two statements is a race. Make it one atomic statement, or rely on a unique constraint.

### Several instances
- `synchronized`, `ReentrantLock`, `ConcurrentHashMap`, in-memory caches: one JVM only. With 3 instances they protect nothing global.
- `@Scheduled` runs on **every** replica. Use ShedLock, or claim rows with an atomic update, or `FOR UPDATE SKIP LOCKED`.
- ShedLock gives a lease, not a guarantee: a run longer than `lockAtMostFor` lets a second instance in. Bound the run time.

### In-JVM concurrency notes (Java 21)
- Shared mutable state in singletons (a `HashMap` field in a `@Service`) is a data race. Make it immutable, thread-confined, or concurrent, and ask why it isn't in the database.
- `CompletableFuture.supplyAsync(...)` without an executor uses the common ForkJoinPool. Blocking I/O there starves other work. Pass a dedicated executor, and set timeouts (`orTimeout`).
- Exceptions in async tasks are lost unless you handle the future.
- `@Async` has the same proxy/self-invocation rules as `@Transactional`, and the transaction does **not** propagate to the async thread.
- **ThreadLocal** context (MDC, security, transaction) does not cross threads unless you copy it (Micrometer context propagation).
- **Virtual threads** (`spring.threads.virtual.enabled=true`, Boot 3.2+): great for blocking I/O, but the connection pool still limits DB concurrency; on Java 21, `synchronized` around blocking calls **pins** the carrier thread (fixed in JDK 24); avoid ThreadLocal-heavy caching.
- `volatile` gives visibility, not atomicity; `AtomicLong`/`LongAdder` for counters; `ConcurrentHashMap.compute` is atomic per key.

**Say:** "For a balance I'd use an atomic conditional update and check the row count. If the check needs several rows, a pessimistic lock in a fixed order. Optimistic locking for low-contention aggregates, retried outside the transaction. None of the in-JVM tools help with three instances."

---

## 5. MySQL (InnoDB) essentials

### Reads and locks
- **Consistent (non-locking) read:** a plain `SELECT` reads an MVCC snapshot. At REPEATABLE READ the snapshot is taken at the **first read** of the transaction and reused, so values can be very stale.
- **Locking reads / writes** (`SELECT ... FOR UPDATE`, `FOR SHARE`, `UPDATE`, `DELETE`) read the **latest committed** row and lock it ("current read"). So `UPDATE ... WHERE balance >= :a` is safe even at RR.
- **Lost update at RR:** plain `SELECT balance` → compute in Java → `UPDATE ... SET balance = :newValue` silently overwrites a concurrent change. MySQL does not raise an error by default (PostgreSQL RR would).
- **Next-key and gap locks** (RR): locking reads on ranges lock the gaps too, to prevent phantoms. Side effects: inserts into the gap block, more deadlocks. At READ COMMITTED gap locking is mostly off.
- **Index your WHERE clauses for updates.** `UPDATE ... WHERE player_id = ?` without an index on `player_id` scans and locks every row it reads.
- **Deadlocks:** InnoDB detects them and rolls back one transaction with error **1213**. Retry the whole transaction. **Lock wait timeout:** error **1205** after `innodb_lock_wait_timeout` (default 50s); usually set lower for OLTP.
- `SHOW ENGINE INNODB STATUS` shows the latest deadlock.

### Types and schema
- Money: `DECIMAL(19,4)` (exact). Never `FLOAT`/`DOUBLE`.
- Time: `DATETIME(6)` stores what you give it (store UTC); `TIMESTAMP` converts with the session time zone and ends in 2038. Use `Instant` in Java and UTC everywhere.
- Text: `utf8mb4` (real UTF-8; `utf8` in MySQL is 3-byte).
- `CHECK` constraints are enforced only from **8.0.16**.
- `SKIP LOCKED` / `NOWAIT`: **8.0+**.
- No sequences: Hibernate falls back to a table generator for `SEQUENCE`; prefer `IDENTITY` (accept no insert batching) or app-generated ids.
- Upsert: `INSERT ... ON DUPLICATE KEY UPDATE` (MySQL only); portable alternative is a unique constraint and catching the violation.
- `sql_mode` should include `STRICT_TRANS_TABLES` so bad data errors instead of being silently truncated.

### Operations
- **Read replicas lag.** A write followed by a read from a replica may not see the write (read-your-writes). Read critical money state from the primary.
- Online schema changes for big tables (gh-ost, pt-online-schema-change, or MySQL's online DDL when it applies).
- Connector/J `rewriteBatchedStatements=true` for real JDBC batching.
- Pool sizing (HikariCP): small pools (often 10 to 20) beat big ones; a pool smaller than threads is fine, waiting is cheaper than DB contention.

**Say:** "On MySQL the default is REPEATABLE READ, so a plain SELECT is a snapshot and a read-modify-write loses updates silently. I'd use a conditional update or SELECT FOR UPDATE, retry on 1213, and index the columns in the WHERE."

---

## 6. Idempotency and payment flows

### Idempotency keys (API)
- Client generates a key per intended action and reuses it on retries.
- **Required** (400 if missing). Never generate one on the server when missing.
- **Scoped** to the caller: unique `(player_id, idempotency_key)`; look it up with both.
- Stored in the **same table/transaction** as the effect, or in an idempotency table with the stored response.
- Replay returns the stored outcome (same status and body). Different payload with the same key → `409`/`422`. Compare amounts with `compareTo` (or a payload hash).
- Concurrent duplicates: unique constraint decides; the loser reads and replays the winner (catch the violation **outside** the failed transaction).
- Expiry (for example 24h to 7 days), documented.

### Calling the PSP
- Send **your** idempotency key to the PSP (`Idempotency-Key` header) on every attempt, so a retry can't charge twice.
- Retry only with that key, with exponential backoff and jitter, bounded by a total deadline.
- **Timeout = unknown outcome.** Keep `PENDING`, store the merchant reference, resolve via PSP lookup, webhook, or reconciliation.
- Timeouts: connect short (1s), read bounded (a few seconds), total deadline across retries. A per-attempt read timeout multiplies with the attempts.

### State machine
`CREATED → PENDING → AUTHORISED → CAPTURED / COMPLETED`, `PENDING → FAILED`, `COMPLETED → REFUNDED`, etc.
- Transitions are guarded in the database: `UPDATE ... SET status = :to WHERE id = :id AND status IN (:allowedFrom)`.
- Zero rows means someone else already moved it: skip the effect, acknowledge, log/alert if unexpected.
- Late or out-of-order events must not move a final state backwards (`COMPLETED` must not become `FAILED`).

### Money direction
- **Money out (withdrawals, payouts):** debit/reserve first, then pay; refund the reservation if the payout definitively fails.
- **Money in (deposits):** credit only after a confirmed success; the credit and the status change in one transaction with a guarded transition.
- **Refund exactly once:** guarded transition `FAILED → REFUNDED` plus the credit, in one transaction; or a unique `(payment_id, 'REFUND')` ledger row.

### Webhooks
- Verify the HMAC signature over the **raw body**, compare with `MessageDigest.isEqual` (constant time), check a timestamp window (replay protection).
- At-least-once and possibly out of order: deduplicate (event id or guarded transition), return `2xx` for duplicates so the PSP stops retrying.
- Acknowledge fast, process reliably (store then process, or process in a short transaction).
- Verify amount and currency **before** acting.
- Secret from a vault/env, rotatable; never in the repo.

### Ledger and reconciliation
- Append-only, double-entry ledger: every movement has a debit and a credit entry; balances are derived or checked against it.
- Never update or delete ledger rows; corrections are new entries.
- Daily reconciliation against PSP settlement files; alerts on mismatches and on payments stuck in `PENDING` longer than N minutes.
- Audit trail: who, what, when (`Instant`, UTC), correlation id.

### iGaming domain awareness (Sweden)
- Regulated market: licensing by Spelinspektionen, mandatory deposit limits, self-exclusion via Spelpaus checked before play/deposits, AML/KYC checks on deposits and withdrawals, responsible-gambling limits.
- Bonus money vs real money wallets; withdrawals may need source-of-funds checks.
- Peak loads around big sports events: design for spikes (queues, back-pressure, idempotent retries).

**Say:** "A payment is a state machine in the database. Every transition is a conditional update, every external call has an idempotency key, a timeout leaves it PENDING for reconciliation, and every money movement writes ledger entries in the same transaction."

---

## 7. Kafka

### Producer
| Setting | Value | Why |
|---|---|---|
| `acks` | `all` | Survives leader failover (with `min.insync.replicas=2` on the topic, replication factor 3) |
| `enable.idempotence` | `true` (default since Kafka 3.0) | Broker de-duplicates retries and keeps order per partition |
| `max.in.flight.requests.per.connection` | ≤ 5 | Required for idempotence to keep ordering |
| `retries` | large (default) | Bounded by `delivery.timeout.ms` |
| `delivery.timeout.ms` | e.g. 5 to 30s | Total time the client keeps retrying; keep it shorter than any wait in your code (so a timeout means "given up") |
| `linger.ms`, `batch.size`, `compression.type` | tune | Throughput |

- `kafkaTemplate.send(...)` is asynchronous; handle the returned future (or `.get(timeout)` in a relay).
- The **key** chooses the partition (`hash(key) % partitions`). Same key → same partition → order kept. Changing the partition count remaps keys.
- No key → spread across partitions, no ordering.

### Consumer
- Consumer group: each partition is consumed by one member; max parallelism = partitions.
- **Offsets:** commit after processing. Spring Kafka disables auto-commit and commits per batch/record after the listener returns (`AckMode.BATCH`/`RECORD`); `MANUAL` for explicit acks.
- `auto.offset.reset`: `latest` (default) skips existing messages for a new group; `earliest` for most business consumers.
- `max.poll.interval.ms` (default 5 min): exceed it and the member is kicked, the partition is rebalanced, and messages are re-delivered → duplicates.
- Rebalances: cooperative sticky assignment reduces stop-the-world; process idempotently anyway.
- Don't hand records to a thread pool if order matters; that breaks per-partition order.

### Error handling (Spring Kafka)
```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template);       // -> topic.DLT
    var backoff = new ExponentialBackOffWithMaxRetries(5);
    backoff.setInitialInterval(500); backoff.setMultiplier(2); backoff.setMaxInterval(10_000);
    var handler = new DefaultErrorHandler(recoverer, backoff);
    handler.addNotRetryableExceptions(ValidationException.class);       // straight to DLT
    return handler;
}
```
- **Never catch-and-log** in a listener: the offset is committed and the message is lost.
- **Poison pills** (undeserialisable): use `ErrorHandlingDeserializer` so they go to the DLT instead of looping.
- Non-blocking retries: `@RetryableTopic` (retry topics with delays) when blocking the partition is unacceptable; it gives up ordering.
- Monitor DLT size and consumer lag; have a replay procedure.

### Delivery semantics
- **At-most-once:** commit before processing (lose on crash).
- **At-least-once:** process then commit (duplicates on crash). The normal choice.
- **Exactly-once effects:** at-least-once + idempotent consumer.
- **Kafka transactions** (`transactional.id`, consumers with `isolation.level=read_committed`) give exactly-once for consume → process → produce **within Kafka**. They do **not** include your database.

### Idempotent consumer
```sql
INSERT INTO processed_event (event_id) VALUES (:eventId);   -- unique constraint
-- duplicate key -> already processed, skip; do it in the same transaction as the effect
```
Or a natural guard (guarded state transition, unique ledger row).

### Transactional outbox
- Write the event row in the **same DB transaction** as the state change.
- A relay publishes it: polling with ShedLock (in id order) or claiming rows (`SKIP LOCKED` / conditional update), or CDC (Debezium) reading the binlog.
- Store the payload snapshot, not just an id (an event is a fact about a moment).
- No "last seen timestamp" cursor: rows become visible in commit order. Select unpublished rows by status, ordered by id.
- Relay: wait for the ack, mark published, stop at the first failure if order matters. A crash between ack and mark re-sends → consumers dedupe on an event id header.
- **Inbox** pattern on the consumer side: store incoming event ids before processing.

### Events and schemas
- Event: `eventId`, `type`, `occurredAt` (UTC), aggregate id, payload, `schemaVersion`.
- Schema Registry (Avro/Protobuf/JSON Schema) with backward-compatible evolution: add optional fields, never rename/remove without a new version.
- Compacted topics for "latest state per key"; retention for event streams.

**Say:** "The DB write and the Kafka send aren't atomic, so I'd use an outbox. Producer with acks=all and idempotence, keyed by the aggregate whose order matters. Consumers commit after processing, are idempotent on an event id, and failures go through DefaultErrorHandler with backoff to a DLT."

---

## 8. REST API design

### Resources and methods
- Nouns: `POST /payments`, `GET /payments/{id}`, `POST /payments/{id}/refunds`.
- `GET`, `PUT`, `DELETE` are idempotent by definition; `POST` is not, so it needs an `Idempotency-Key`.
- Long-running operations: `202 Accepted` + a status resource (`Location: /payments/{id}`) instead of holding the request open.

### Status codes
| Code | Use |
|---|---|
| 200 / 201 | OK / created (with `Location`) |
| 202 | Accepted, processing asynchronously |
| 400 | Malformed or invalid input |
| 401 / 403 | Not authenticated / not allowed |
| 404 | Not found (also for "exists but not yours", to avoid leaking existence) |
| 409 | Conflict: idempotency key reused with another payload, state conflict |
| 412 | `If-Match` version mismatch (optimistic concurrency) |
| 422 | Valid syntax but business rule fails (insufficient funds, limit exceeded) |
| 429 | Rate limited (with `Retry-After`) |
| 500 / 502 / 503 / 504 | Server error / upstream failure / unavailable / upstream timeout |

### Errors
- One error model: `ProblemDetail` (RFC 9457) with `type`, `title`, `status`, `detail`, plus a machine-readable `code`/`reason`.
- Map domain exceptions in `@RestControllerAdvice`. Don't map broad `IllegalArgumentException`/`Exception` to 400; it hides bugs.
- Never return stack traces or internal ids of other users.

### Contracts
- **DTOs, never entities** in the API (lazy loading, leaking fields, coupling).
- `@Valid` on `@RequestBody`; constraints on DTO fields; invariants again in the domain.
- Money: amount + currency, `BigDecimal` or string. Time: ISO-8601 UTC (`Instant`).
- Versioning: URI (`/v1`) or header; additive changes only within a version.
- Pagination: cursor-based for large/moving data sets; `limit` capped.
- Correlation id header (`X-Request-Id` / W3C `traceparent`), returned and logged.

### Security
- Authenticate at the gateway, **authorise in the service** (ownership checks, no IDOR: the player id comes from the token, not from the URL or body).
- Recompute amounts server-side (prices, fees, bonuses); never trust client totals.
- Rate limiting per user/IP; input size limits.

**Say:** "POST with a required idempotency key, 201 or 202 with a status resource, errors as ProblemDetail with a stable code, DTOs with @Valid, and authorisation by ownership in the service."

---

## 9. Microservices design patterns

### Data
- **Database per service:** no shared tables. Other services get data through APIs or events.
- **Eventual consistency** between services; strong consistency inside one service's transaction.
- **2PC/XA** across services is avoided (blocking, availability, poor support with Kafka/HTTP).

### Saga
- A business transaction across services as a sequence of local transactions with **compensations**.
- **Choreography:** services react to each other's events. Simple, but the flow is implicit and hard to follow.
- **Orchestration:** an orchestrator tells each service what to do and tracks state. Explicit, easier to monitor, a central component.
- Each step idempotent; each compensation idempotent; compensations are business actions (refund), not rollbacks.
- Pivot step: after it, only forward recovery (retry) makes sense.

Example (deposit with bonus): reserve → charge PSP → credit wallet → grant bonus; if bonus grant fails permanently, compensate or flag for manual review, never leave money untracked.

### Messaging patterns
- Transactional outbox / inbox, idempotent receiver, competing consumers, dead-letter channel, claim check (large payloads in storage, reference in the message).

### Query side
- **CQRS:** separate write model and read models (projections fed by events). Read models are eventually consistent.
- **Event sourcing:** state = fold of events. Powerful audit, but complex (snapshots, schema evolution, replay). Use where the audit trail is the product (ledgers), not everywhere.

### Edge and integration
- API gateway (auth, rate limiting, routing), BFF per client type.
- Anti-corruption layer around PSPs and legacy systems: map their model to yours, isolate their quirks.
- Strangler fig for migrating from a monolith.

### Resilience
| Pattern | Purpose | Notes |
|---|---|---|
| Timeouts | Bound every remote call | Connect + read + total deadline; never infinite |
| Retry | Transient failures | Only idempotent operations (or with an idempotency key), exponential backoff with jitter, max attempts, total deadline |
| Circuit breaker | Stop hammering a failing dependency | Resilience4j; fallback must be safe (for payments: fail fast or queue, never "assume success") |
| Bulkhead | Isolate resources per dependency | Separate pools/semaphores so one slow PSP doesn't take all threads |
| Rate limiter | Protect yourself and downstreams | |
| Fallback | Degraded behaviour | For money: prefer fail-closed |

- Retries multiply across layers (3 × 3 × 3 = 27 calls). Retry at one layer.
- Order in Resilience4j decorators matters: usually Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(call))))).

### Observability
- Metrics (Micrometer): request rate/latency/errors, payments by status, `PENDING` age, PSP latency and error rate, consumer lag, DLT size, pool usage.
- Alerts: stuck `PENDING` beyond N minutes, reconciliation mismatches, DLT growth, error rate spikes.
- Tracing (OpenTelemetry), correlation id in logs (MDC), structured logs without PII.
- Health: liveness vs readiness (don't fail liveness because a dependency is down).

**Say:** "Across services I use sagas with idempotent steps and compensations, an outbox for events, timeouts on every call, retries only with idempotency keys, a circuit breaker that fails closed for money, and alerts on stuck payments."

---

## 10. Security, PII and logging

- **PCI DSS:** never store CVV/CVC after authorisation; store a PSP token, not the PAN; mask PAN (first 6 / last 4) when shown.
- **Records and Lombok `@Data`/`@ToString` print every field.** Never log requests, events or entities containing card data, emails, names. Log ids and status.
- **Secrets:** environment or a vault, rotated; a secret committed to git stays in history, so rotate it.
- **Webhooks:** HMAC over the raw body, constant-time compare, timestamp window, IP allow-list as an extra.
- **IDOR:** load by `(id, owner)`, not by `id`.
- **Mass assignment:** DTOs with explicit fields, never bind requests to entities.
- **SQL injection:** parameters only; never concatenate into JPQL/SQL (including `ORDER BY` from user input).
- **Actuator:** expose only `health`/`info` publicly; protect `env`, `heapdump`, `loggers`.
- Server-side recomputation of amounts; limits checked server-side.

---

## 11. Testing

- **Real database** with Testcontainers (MySQL/PostgreSQL) for repositories, locking and transactions. H2 differs in locking, isolation, SQL and types; tests can pass on H2 and fail in production.
- **Concurrency tests:** N threads, a `CountDownLatch` start gun, assert invariants (exactly one success, balance ≥ 0, one row). They are probabilistic; run with enough threads.
- **Fault injection:** a spy that throws on the second write proves atomicity (the first write must roll back).
- **No `Thread.sleep`:** use Awaitility (`await().atMost(5, SECONDS).untilAsserted(...)`) or Mockito `timeout()`.
- **Kafka:** Testcontainers Kafka or `@EmbeddedKafka`; test duplicates and poison pills.
- **Contract tests** (Spring Cloud Contract, Pact) between services.
- **Money assertions:** `isEqualByComparingTo("5.00")`; expected values from the business rule, never copied from the output.
- **Test the unhappy paths:** timeout, duplicate request, duplicate webhook, out-of-order events, negative/zero amounts, other currency, other user's resource.
- **What tests miss** is a review point: sequential tests can't catch races; fully mocked unit tests can't catch transaction or proxy problems.

---

## 12. Java 21 and Spring Boot 3 quick notes

- Records for DTOs, events, value objects, projections, configuration; **not for JPA entities** (final, immutable, value equality).
- Sealed interfaces + pattern matching `switch` for result types (`Success | Declined | Unknown`) make the unknown outcome explicit.
- `jakarta.*` namespace (Boot 3). `RestClient` (sync) and `WebClient` (reactive); set timeouts on the request factory.
- `ProblemDetail` built in (`spring.mvc.problemdetails.enabled=true`).
- `@ConfigurationProperties` records with validation (`@Validated`).
- `Clock` bean injected for testable time; `Instant` for timestamps.
- Virtual threads: see section 4.
- Observability: Micrometer Observation API, OpenTelemetry tracing.

---

## 13. Review checklist (print this)

**Money:** BigDecimal + currency? scale from currency? compareTo? divide with scale and mode? rounded once? per-currency limits? amounts recomputed server-side?
**Concurrency:** every read-then-write guarded? row count checked? which row do two writers share? anything in-JVM pretending to be global? `@Scheduled` on every replica?
**Transactions:** where does each transaction start and end? self-invocation? checked exceptions? remote calls inside? swallowed exceptions? `REQUIRES_NEW` pool risk? OSIV?
**Idempotency:** key required, scoped, unique, replayed? PSP called with a key? timeouts treated as unknown? state transitions guarded?
**Messaging:** send before commit? outbox? key and ordering? producer acks/idempotence? consumer commits after processing? idempotent consumer? DLT, no catch-and-log? `auto.offset.reset`?
**Security:** validation, ownership, PII in logs, card data, secrets, webhook signature, actuator?
**API:** DTOs, status codes, ProblemDetail, idempotency, money format?
**Resilience/ops:** timeouts, retries with keys and jitter, circuit breaker, metrics, alerts on stuck payments, reconciliation?
**Tests:** real DB, concurrency, failure paths, expected values from the spec?

---

## 14. Ready-made sentences

- "This is a check-then-act race: two requests read the same balance before either writes. I'd make it one conditional update and check the row count."
- "This `@Transactional` is bypassed because the method is called with `this`; the three writes commit separately."
- "This checked exception won't roll back the transaction, so the credit commits even though we reject the webhook."
- "A timeout here means we don't know whether the card was charged. Marking it failed can lose the customer's money; I'd keep it pending and reconcile."
- "The retry doesn't carry an idempotency key, so a slow first attempt plus a retry can charge twice."
- "The event is sent inside the transaction; if the commit fails, consumers act on something that never happened. Outbox."
- "The listener catches and logs, so the offset is committed and the message is gone. Let it throw to a DefaultErrorHandler with a DLT."
- "`synchronized` doesn't help with three instances; the lock has to live in the database."
- "The test is sequential, so it can't see the race; I'd add a latch-based concurrent test on a real database."
- "I'd rank this as a Blocker because it creates money; the naming comment is a Nit and can wait."

## 15. Questions to ask the interviewer

- How many instances run, and is there any single-runner component?
- Which database and isolation level? Read replicas?
- Kafka delivery guarantees, partitioning key, and how DLTs are handled?
- Does the PSP support idempotency keys and status lookup? How are webhooks delivered?
- How is reconciliation done today, and what alerts exist for stuck payments?
- What does the team's definition of done include for money paths (tests, reviews, monitoring)?

---

## 16. Lessons from the mock rounds (your personal list)

- Verify before claiming: read the annotations, follow injected beans, check the YAML (Round 2: "REST call inside a transaction" and "no timeout" were both false alarms).
- `@Version` does nothing for JPQL bulk updates; a conditional update already has its guard in the SQL.
- An `UPDATE` locks the row automatically until commit; under READ COMMITTED a waiting update re-checks its `WHERE` on the new value.
- The Kafka key chooses the partition; order within it is **arrival** order, so the publisher must send in order (single relay, wait for ack, stop on failure).
- Outbox relay: no timestamp cursor; store a payload snapshot; at-least-once means consumers dedupe on an event id.
- Exactly-once refunds come from a guarded status transition in the same transaction as the credit, not from partitioning or row locks.
- `PENDING` has two meanings (not yet charged / charged, awaiting confirmation); resuming it needs an atomic claim and a PSP idempotency key.
- Rounding and precision problems are Majors when they affect every transaction.
