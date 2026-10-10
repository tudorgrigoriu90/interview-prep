# Round 04 answer key: payouts service, "everything wrong" edition

Same domain as Round 03 (the reference). Compare any finding with the matching file in `rounds/03-reference-payouts`.
Assumptions given in the brief: 3 instances, MySQL 8 InnoDB (REPEATABLE READ), Kafka at-least-once, PSP supports an
Idempotency-Key header and a status lookup, PSP webhooks are at-least-once and may arrive out of order, gateway
authenticates the player and passes X-Player-Id.

## Ranked findings, grouped by file

### Blockers
| # | File | Finding | Failure scenario | Fix |
|---|---|---|---|---|
| B1 | `service/PayoutException` + `WithdrawalTransactions.reserve` | `PayoutException` is **checked**; `reserve` saves withdrawal, ledger row and outbox event **before** debiting, and the debit throws the checked exception. `@Transactional` commits on checked exceptions. | Balance 50, request 100: withdrawal RESERVED + ledger + event committed, wallet not debited. After approval the PSP pays 100 that was never taken. | Unchecked business exception (or `rollbackFor`), and debit FIRST (guarded), then insert. |
| B2 | `Wallet.debit/credit` + `WithdrawalTransactions` | Read-modify-write on the entity, no `@Version`, no lock, no conditional update. | Two withdrawals of 300 on 500 (two tabs, two instances): both read 500, both pass the check, last write wins: 600 reserved, balance 200 (or 194.xx). | Conditional `UPDATE ... WHERE balance >= :total` + row count, or `@Version` + retry, or `PESSIMISTIC_WRITE`. |
| B3 | `V1__init.sql` vs `Withdrawal @Table(uniqueConstraints)` | The entity declares unique `(player_id, idempotency_key)` but the migration only creates a plain index. `ddl-auto: validate` does not check constraints. | Two concurrent requests with the same key both miss the lookup and both insert: two withdrawals, debited twice. | `UNIQUE (player_id, idempotency_key)` in the migration; catch the violation outside the transaction and replay. |
| B4 | `WithdrawalController` + `CreateWithdrawalRequest` | `playerId` comes from the request body. | Any authenticated user withdraws from another player's wallet by sending their id. | Player id from the authenticated principal / gateway header, never from the body. |
| B5 | `WithdrawalController.get` + `WithdrawalService.find` | No ownership check (IDOR); returns the entity. | `GET /api/v1/withdrawals/{any id}` shows other players' amounts, payout method, PSP reference, idempotency key. | `findByIdAndPlayerId`, DTO response, 404 for not-yours. |
| B6 | `PayoutResult` (no Unknown) + `HttpPspPayoutClient` + `PayoutProcessor` | Timeouts, 5xx and connection errors become `Declined("PSP unavailable")`; the processor then calls `failAndRelease` (refund). | PSP is slow, processes the payout, our read times out: we refund the player AND the PSP pays out. Player gets the money twice. | Third result type Unknown: leave the row in a SENDING/UNKNOWN state, resolve by PSP lookup, never refund on unknown. |
| B7 | `HttpPspPayoutClient` | Retries 3x with no Idempotency-Key header (and no timeouts, see M1). | First attempt timed out but succeeded at the PSP; retries create 2 more payouts. | Send `Idempotency-Key` (stable per withdrawal) on every attempt; better, no blind retry, resolve by lookup. |
| B8 | `WithdrawalStatus` (FAILED allowed from COMPLETED) + `PspWebhookHandler` | A late or duplicate FAILED webhook after COMPLETED is accepted and **releases funds**. | Payout completed (money left), then an out-of-order FAILED event refunds the player: paid twice. | Final states have no successors; FAILED only from SENDING/SENT. |
| B9 | `PayoutProcessor.runOnce` | `@Transactional` around the whole batch with the PSP HTTP call inside; no claim; not locked across instances (B10). | Batch of 50: payouts 1-10 succeed at the PSP, payout 11 throws (e.g. NPE on a null PSP body): the whole transaction rolls back, rows 1-10 go back to APPROVED and are paid AGAIN next run. Also holds a DB connection and row locks for the whole HTTP batch. | Per row: claim in a short tx (APPROVED -> SENDING), call the PSP with no tx, record the result in another short tx. |
| B10 | `ScheduledJobs` + `SchedulingConfig` | `@Scheduled` on every replica, no ShedLock, no row claim. | 3 instances pick the same APPROVED rows: 3 payouts per withdrawal. | ShedLock AND an atomic claim (conditional update) so overlap is harmless. |
| B11 | `WithdrawalTransactions.reject` | The result of the guarded transition is ignored; release always happens. | Risk decision REJECTED delivered twice (or REJECTED after APPROVED/SENT): funds released each time, even for a withdrawal being paid out. | `if (!move(...)) return;` before any side effect. |
| B12 | `Withdrawal.merchantReference` | Built from the client's idempotency key, which is only unique per player (and not even that, see B3). | Two players both use key "abc": same merchant reference at the PSP. With PSP idempotency the second payout returns the first one's result (player 2 never paid, marked SENT); the webhook lookup also resolves to the wrong withdrawal (see M8). | Use our own stable id (e.g. "wd-" + withdrawal id). |
| B13 | `RiskDecisionListener` | catch (Exception) and log. | Any failure (DB down, deadlock) is logged, the offset is committed, the decision is lost: the withdrawal stays RESERVED forever, funds locked. | Let it throw; `DefaultErrorHandler` with bounded backoff + DLT. |
| B14 | `application.yml` Kafka consumer `enable-auto-commit: true` | The Kafka client commits offsets on a timer, independent of processing. | Crash after an auto-commit but before processing: decision lost. | `enable-auto-commit: false` (Spring commits after the listener returns), ack-mode record/batch. |

### Majors
| # | File | Finding | Failure / impact | Fix |
|---|---|---|---|---|
| M1 | `AppConfig` | RestClient without connect/read timeouts. | A hung PSP blocks the payout thread (and, with B9, a DB connection and locks) indefinitely. | Connect + read timeouts; total deadline. |
| M2 | `PspWebhookHandler` | Amount/currency checked AFTER completing/failing; currency never checked. | Webhook for 1.00 still completes a 100.00 withdrawal (then returns 422 so the PSP retries, while the state already changed). | Verify first, act only on a match. |
| M3 | `PspWebhookController` + `WebhookSignatureVerifier` | Signature verified over a re-serialised parsed object, not the raw body; `String.equals` (not constant time); no timestamp / replay window. | Field order or number format differences break real PSP signatures, or let a modified body pass; an old captured webhook can be replayed forever. | `@RequestBody String raw`, HMAC over raw bytes + timestamp, `MessageDigest.isEqual`, tolerance window. |
| M4 | `application.yml` webhook secret `${PSP_WEBHOOK_SECRET:changeme}` | Default secret: if the variable is missing, production accepts webhooks signed with "changeme". | Anyone can forge "COMPLETED"/"FAILED" webhooks (FAILED releases funds). | No default; fail at startup; vault/env. |
| M5 | `RiskDecisionHandler` | Not transactional; `existsById` then act then save (check-then-act); effect and dedupe record in separate transactions. | Redelivery during a rebalance processes the same decision twice; crash after the effect but before the save repeats it. | `@Transactional`, insert the event id first (unique PK, `Persistable`/`saveAndFlush`), effect in the same transaction. |
| M6 | `ProcessedEvent` | Assigned id without `Persistable`: `save()` merges, a duplicate never fails. | Even with M5 fixed by "insert first", a duplicate id silently "succeeds" (merge = update). | Implement `Persistable` (`isNew` true) or insert via a query. |
| M7 | `OutboxRelay` | Fire-and-forget `send()` then marks PUBLISHED; no wait for the ack; no lock across instances; unbounded `findByStatus`. | Broker down: events marked published but never delivered (lost). 3 instances publish the same rows. | Wait for the ack (`.get(timeout)`), mark after; ShedLock; batch with limit; stop on failure. |
| M8 | `PspWebhookHandler` + `WithdrawalRepository.findByIdempotencyKey` | Lookup by idempotency key only (not unique, not scoped). | Webhook for player A's "abc" can load player B's "abc" withdrawal (`orElseThrow`/`IncorrectResultSize` when several), wrong withdrawal completed or refunded. | Look up by our own id from the merchant reference. |
| M9 | `WithdrawalService` (replay) + `WithdrawalRepository.findByIdempotencyKey` | Idempotency lookup not scoped to the player. | Player B sending player A's key gets A's withdrawal back (data leak) and B's request is never processed. | `findByPlayerIdAndIdempotencyKey`. |
| M10 | `Withdrawal.isSameRequestAs` | Raw `BigDecimal.equals` against the request value. | Retry sends `100` instead of `100.00`: 409 conflict on a legitimate retry; the client may retry with a new key -> second withdrawal. | Compare normalised `Money` or `compareTo`. |
| M11 | `WithdrawalService` `@Transactional` at class level + `catch (DataIntegrityViolationException)` | The catch is INSIDE the outer transaction (reserve joins it). After a constraint violation the transaction is rollback-only: the "replay" ends in `UnexpectedRollbackException` (500). | Concurrent duplicate (once B3 is fixed) returns 500 instead of the original result. | Orchestrator without a transaction; catch outside the failed transaction. |
| M12 | `Money` constructor | Silently rounds extra decimals (`setScale(HALF_UP)`) instead of rejecting. Test `roundsToCurrencyScale` bakes it in. | Request 10.005 EUR becomes 10.01: the player is paid a different amount than requested; JPY amounts with decimals round silently. | Reject amounts with more decimals than the currency allows. |
| M13 | `Money.of(double)` + `PayoutProperties.Fee.minimum double` + `FeePolicy` | Minimum fee configured as `double` and converted with `new BigDecimal(double)`; single minimum for all currencies. | 1.00 minimum applied to SEK, JPY, NOK alike; binary noise in config values (0.1 -> 0.1000000000000000055...) before rounding. | `BigDecimal` config, per-currency minimums. |
| M14 | `Money.plus/minus` | No currency check. With no wallet-currency check in the service (M15) nothing stops mixing currencies. | | `requireSameCurrency`. |
| M15 | `WithdrawalService` | Request currency never compared with the wallet currency; no positive-amount check in the domain (`@Min(0)` allows 0). | SEK wallet debited "100" for a 100 EUR payout; zero withdrawals pay the minimum fee. | Currency check; `amount > 0` in the domain and `@Positive` in the DTO. |
| M16 | `Money.percentage` | `RoundingMode.UP` and `divide(divisor, mode)` (keeps the dividend's scale) then the constructor rounds again: double rounding, always against the player. | Rounding policy not a business decision; values like x.xx5 round twice. | One explicit rounding to the currency's digits, agreed mode (HALF_EVEN). |
| M17 | `LedgerEntry` | Ledger amount is the withdrawal amount, not amount + fee; no unique (withdrawal_id, entry_type). | Ledger and balance drift by the fee on every withdrawal; a double release (B11) writes two RELEASE rows unnoticed. | Record the total moved; unique constraint per withdrawal and type. |
| M18 | `OutboxWriter` | Random UUID as Kafka key; not `MANDATORY` propagation. | No ordering per player/withdrawal (RequestedEvent may arrive after CompletedEvent); outbox writes outside a transaction possible. | Key = player id (or withdrawal id); `@Transactional(propagation = MANDATORY)`. |
| M19 | `application.yml` producer `acks: 1`, `enable.idempotence: false` | Leader-only ack; retries can duplicate/reorder. | Leader fails after ack before replication: event lost. | `acks: all`, idempotence on. |
| M20 | `application.yml` consumer `auto-offset-reset: latest` | New consumer group skips everything already in the topic. | First deployment / new group id: all pending risk decisions ignored, withdrawals stuck. | `earliest`. |
| M21 | `KafkaConfig` | `FixedBackOff(1s, UNLIMITED_ATTEMPTS)`, no recoverer/DLT. (Currently hidden by B13's catch-all.) | Once B13 is fixed, a poison message blocks its partition forever. | Bounded exponential backoff + `DeadLetterPublishingRecoverer` with explicit `.DLT`; non-retryable exceptions. |
| M22 | No resolver for SENT | Nothing asks the PSP when a webhook never arrives. | Lost webhook: withdrawal stuck in SENT forever, nobody alerted. | Resolver job with age threshold + lookup; alert on stuck rows. |
| M23 | `ApiExceptionHandler` | `Exception` -> 400 with `e.toString()`. | Bugs reported as client errors (no alert); class names, SQL fragments, internal ids leak to clients. | 500 with a generic message + correlation id; log the details. |
| M24 | `WithdrawalController.create` | Always 201, also for replays; returns the JPA entity. | Clients can't tell replay from creation; internal fields exposed; lazy-loading/serialisation coupling. | 201 vs 200; DTO response. |
| M25 | `PspWebhookHandler` | Logs the full event (`accountHolder`, `iban`). | PII in logs (GDPR), searchable by everyone with log access. | Log ids and status only. |
| M26 | `WithdrawalEvent` | `double amount`, no fee, no schema version. | Consumers do float math with money; can't evolve the schema safely. | String amount + currency + fee, `schemaVersion`. |
| M27 | `application.yml` `management...include: "*"` | Exposes env, heapdump, loggers, configprops. | Heap dump contains secrets and PII; env shows credentials. | `health,info,metrics` only; secure the rest. |

### Minors
| # | File | Finding | Fix |
|---|---|---|---|
| m1 | `V1__init.sql` | No CHECK (balance >= 0, amount > 0), no FKs, no index on `(status, updated_at)` or outbox `(status, id)`; balance `DECIMAL(19,2)` cannot hold 3-decimal currencies. | Constraints, FKs, indexes, `DECIMAL(19,4)`. |
| m2 | `WithdrawalRepository` | `@Modifying` without `clearAutomatically`: in `PayoutProcessor` the batch's entities stay cached, so `failAndRelease` reloads a stale entity and the outbox event says status APPROVED for a FAILED withdrawal. | `clearAutomatically = true`, re-read. |
| m3 | `WithdrawalRepository.findByStatus` | Unbounded result. | `Pageable` / limit. |
| m4 | `application.yml` | `open-in-view` left at default true; DB password default in the repo; no `connectionTimeZone=UTC`. | `open-in-view: false`; no secret defaults; UTC. |
| m5 | `PayoutProperties` | No `@Validated`; missing config fails late (NPE at first use). | Validated properties. |
| m6 | `HttpPspPayoutClient` | `double` amount in the PSP request; NPE if the PSP returns an empty body. | String amount; null check -> Unknown. |
| m7 | `RiskDecisionListener` | `concurrency = "3"` hard-coded (should match partitions; config). | Configurable. |
| m8 | `OutboxEvent` | `columnDefinition = "text"` (vendor-specific). | Length or `@Lob` mapping. |

### Tests (also findings)
| # | Finding |
|---|---|
| T1 | `MoneyTest.roundsToCurrencyScale` asserts the silent rounding bug (M12). |
| T2 | `reservesFundsForWithdrawal` asserts ledger amount 100.00 (bakes in M17; total moved is 102.50). |
| T3 | No concurrency test (B2, B3), no insufficient-funds test (would expose B1), no duplicate-event or out-of-order webhook test (B8, B11, M5). |
| T4 | The webhook test signs with the production `verifier.sign` over re-serialised JSON: circular, it cannot detect M3. |
| T5 | `Thread.sleep(500)` on a synchronous call: useless delay; for async use Awaitility. |
| T6 | Kafka is mocked and the listener never runs: B13, B14, M20, M21 are untested. The risk decision is passed straight to the handler. |
| T7 | Controller test only checks the happy path; nothing about authorisation (B4, B5) or status codes for replays. |

### Decoys (correct)
- `Money.max` uses `compareTo`.
- The transition queries (`... where w.status in :from`) are proper guarded updates; the bugs are the enum rules (B8) and ignoring the result (B11).
- `HttpPspPayoutClient` URI building with `.uri("/v1/payouts")` and a typed body is fine; no injection.
- `@Valid` is present on the request body (the DTO's constraints are the problem, M15).

## Ideal 10-minute review order
1. Headline: "This service can pay out money it never debited, pay twice, refund after paying, and lets any user withdraw from any wallet."
2. Money integrity: B1 (checked exception commits reservation without debit), B2 (lost update), B3 (missing unique constraint).
3. Payout flow: B6 + B7 (timeout treated as decline, retries without key), B9 + B10 (batch transaction with HTTP inside, every replica), B8 + B11 (state machine and ignored transition), B12.
4. Security: B4, B5, M3, M4, M25, M27.
5. Messaging: B13, B14, M5, M6, M7, M18-M21.
6. Money details: M10, M12-M17.
7. Tests and ops: T1-T7, M1, M22, M23.
