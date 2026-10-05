# Round 02 answer key: card deposits (transactions, idempotency, webhooks), database-agnostic

Brief given: PSP confirms by webhook (at-least-once, possibly out of order), supports an Idempotency-Key header and lookup by merchant reference; 3 instances; RDBMS vendor undecided (PostgreSQL, MySQL, Oracle or SQL Server; default isolation differs: READ COMMITTED on PG/Oracle/SQL Server, REPEATABLE READ on MySQL InnoDB).
Verified with a throwaway probe against PostgreSQL: A (wallet 600, deposit stays PENDING), B (2 ledger rows), M (600 instead of 700), J (credited 100 for a webhook that said 1.00).

| # | Sev | Where | Issue and failure scenario | Portable fix (vendor notes in the next section) |
|---|-----|-------|----------------------------|---------------------------------------------|
| 1 | Blocker | `DepositService.resolvePending` calls `this.completeDeposit(...)` | Self-invocation: the proxy is bypassed, so `@Transactional` is ignored when the reconciliation job runs. Wallet save, ledger insert and deposit update are three separate transactions. If the ledger insert fails: wallet credited 100, deposit still PENDING, the next run credits again. Probe: balance 600.00, status PENDING. The same method is correct when called from the webhook path (outer tx exists), which is why tests pass. | Put the settlement in another bean (`DepositSettler`) called through the proxy, or use `TransactionTemplate`. |
| 2 | Blocker | `settleFromWebhook` (read status, then act) | Duplicate webhook delivered concurrently (PSP retries after a slow 200): both threads read PENDING, both credit and insert a ledger row. Probe: 2 ledger entries (balance shows only +100 because of #5, so books and balance disagree). The sequential duplicate test passes. | Make the transition atomic: `UPDATE Deposit SET status='COMPLETED' WHERE id=:id AND status='PENDING'` and continue only if 1 row. Alternatives: `@Version` on Deposit, unique `(deposit_id, type)` on ledger, `@Lock(PESSIMISTIC_WRITE)`. |
| 3 | Blocker | `initiate` catch `PspUnavailableException` | A timeout is an unknown outcome, not a failure. Deposit set FAILED while the PSP may have charged the player. The webhook cannot be matched (psp_reference never stored), and the reconciliation job only looks at PENDING. Player charged 100, never credited; if they retry (new key) they are charged again. The unit test asserts FAILED, baking the bug in. | Leave PENDING (store the merchant reference), let reconciliation or the webhook resolve it. |
| 4 | Blocker | `HttpPspClient.charge` | Retries up to 3 times on read timeouts without sending an Idempotency-Key. First attempt may have succeeded: player charged 3 times. | Send `Idempotency-Key` (the deposit id) on every attempt; or do not retry a charge, resolve via lookup. |
| 5 | Blocker | `Wallet.credit` (read, add, write on an entity) | Lost update. Two deposits for the same player complete at the same time: both read 500, both write 600. Probe: 600.00 instead of 700.00. | Atomic JPQL `UPDATE Wallet SET balance = balance + :a WHERE id = :id`, or `@Version` with retry outside the transaction, or `@Lock(PESSIMISTIC_WRITE)`. |
| 6 | Major | `ReconciliationJob` + `resolvePending` (NOT_FOUND mapped to FAILED, no age filter) | A deposit created 2 seconds ago, whose PSP call is still in flight, is looked up, the PSP says NOT_FOUND, and it is marked FAILED while the charge then succeeds. | Only consider deposits older than a threshold (for example 5 minutes); NOT_FOUND on an old deposit means FAILED, on a young one means look again. |
| 7 | Major | `ReconciliationJob.run` | Runs on every instance, no lock, loads every PENDING row without paging. Three instances resolve the same rows (and #1 makes each run unsafe). | ShedLock (JDBC based, vendor neutral), or claim rows with a conditional `UPDATE ... SET claimed_until` and a limit, plus paging. |
| 8 | Major | `DepositRepository.findByIdempotencyKey` | Lookup by key only. The unique constraint is `(player_id, key)`, so player B who sends player A's key gets A's deposit back and no deposit is created for B (data leak and a lost deposit). | `findByPlayerIdAndIdempotencyKey`. |
| 9 | Major | `settleFromWebhook` | Amount and currency are verified after crediting, and the failure is a checked exception (`DepositMismatchException`), which does not roll back by default. Probe: webhook for 1.00 credited the deposit's 100.00 and the transaction committed. | Verify before acting; use an unchecked exception or `rollbackFor`. Credit what was verified. |
| 10 | Minor | `WebhookSignatureVerifier.isValid` | `String.equals` is not constant time (timing attack on the signature). | `MessageDigest.isEqual`. |
| 11 | Major | `application.yml` | Webhook secret committed in the repository (it also stays in git history). | Secret manager or environment variable; rotate the committed value. |
| 12 | Minor | `PspWebhookController` | `log.info("... {}", webhook)` prints the record: payer email and card holder name in logs. | Log the reference and status only. |
| 13 | Major | `DepositController` | `Idempotency-Key` is optional and a random UUID is generated when missing, so a client retry creates a second deposit and a second charge. | Make it required (400 if missing). |
| 14 | Minor | Tests | Unit test with all repositories mocked asserts the wrong behaviour of #3; the duplicate-webhook test is sequential; no concurrent credit test, no failure-injection test for the reconciliation path, the webhook test signs with production code (circular), `isEqualTo(new BigDecimal("600.00"))` relies on scale. | Concurrent tests, fault injection (spy that throws on the ledger), expected values from the spec. |

## Decoys (correct)
- D1 `initiate` catches `DataIntegrityViolationException` and re-reads. Correct because the method is not transactional (the failed insert rolled back its own tiny transaction) and `open-in-view` is false. It would be wrong inside a `@Transactional` method (the transaction is marked rollback-only) or with OSIV on.
- D2 Returning 200 for a webhook whose deposit is already COMPLETED. That is the right acknowledgement; a 409 would make the PSP retry. (The bug is the race around it, #2.)
- D3 `event.amount().compareTo(...) == 0`. Correct value comparison.
- D4 `markFailed` is `@Transactional` and called with `this` from `settleFromWebhook`. Fine, an outer transaction already exists.

## Bonus observations (not scored)
Replay returns the stored deposit without comparing the payload; `initiate` ends with `deposits.save(deposit)` on a possibly stale entity (it can overwrite a status set meanwhile); `ddl-auto: update`.

## Database-agnostic notes
| Topic | Portable | Vendor specific |
|---|---|---|
| Atomic guard | Conditional `UPDATE ... WHERE status = :expected` and check the row count. UPDATE re-reads the latest committed row after a lock wait on PG, Oracle, SQL Server and MySQL InnoDB (even at REPEATABLE READ) | Plain SELECT at REPEATABLE READ (MySQL default) is a snapshot: a stale pre-check is more likely |
| Row lock | JPA `@Lock(PESSIMISTIC_WRITE)` (the dialect emits FOR UPDATE, `WITH (UPDLOCK)` and so on) | `SKIP LOCKED` exists on PG, MySQL 8, Oracle; SQL Server uses `READPAST` |
| Optimistic | `@Version` (JPA) and retry outside the transaction | none |
| Duplicate insert | Unique constraint and catch `DataIntegrityViolationException` | `ON CONFLICT` (PG), `INSERT IGNORE` (MySQL), `MERGE` |
| Return updated value | Re-read in the same transaction | `RETURNING` (PG, Oracle), `OUTPUT` (SQL Server), none in MySQL |
| Partial index | Plain composite index `(status, id)` | `WHERE status = 'NEW'` partial/filtered indexes (PG, SQL Server) |
| Job locking | ShedLock JDBC | advisory locks, `GET_LOCK` |

## Ideal 10-minute review (outline)
1. Headline: this can credit twice, charge twice, credit without a ledger row, and fail a deposit that actually succeeded.
2. Transaction boundary: #1 self-invocation (reconciliation path), #9 checked exception and verify-after-act.
3. Concurrency and idempotency: #2 duplicate webhook, #5 lost update on the wallet, #13 optional key, #8 key not scoped to player.
4. Unknown outcome: #3 timeout means FAILED, #4 retries without idempotency key, #6 and #7 reconciliation (age threshold, replicas).
5. Security and config: #11 secret in the repo, #10 constant-time compare, #12 PII in logs.
6. Tests: #14, then say what you would fix first (1 to 5) and in what order.
