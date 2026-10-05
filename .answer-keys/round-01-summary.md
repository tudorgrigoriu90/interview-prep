# Round 01 summary: withdrawals (money and precision, plus concurrency)

Plain-language study sheet. Each topic has: what it means, the wrong code, the fix, and one sentence to say out loud.
The original exercise (all problems present) is commit `32a568e`. The current code has the outbox, the debit check, `@Valid` and the positive-amount check fixed.

## How to rank what you find
| Level | Meaning | Examples from this round |
|---|---|---|
| Blocker | Money leaves with no matching debit, or money is created | Ignored debit result, negative amount, currency mismatch |
| Major | Wrong amounts for many customers, or whole features fail | Fee rounding, FX divide throws, remote call under a row lock |
| Minor | Correct most of the time, fragile or unclear | Stale balance in the response, lost event, config |
| Nit | Style | Rounding policy wording |

Rounding is not automatically a nit. If it changes what every customer pays, or makes a common request throw, it is a Major.

---

## 1. The debit result is ignored (Blocker)
**Plain terms:** the database refused to take the money (0 rows changed), but the code carried on and created the withdrawal anyway.

**Wrong**
```java
wallets.debit(wallet.getId(), total);          // returns 0 when funds are insufficient. Nobody looks.
withdrawals.save(new Withdrawal(...));          // saved anyway
```
Balance 500, two requests of 300 at the same time: one debit succeeds, the other returns 0, **both** withdrawals are saved. 600 is paid out and 300 is debited.

**Fix**
```java
if (wallets.debit(wallet.getId(), total) == 0) {
    throw new WithdrawalException(INSUFFICIENT_FUNDS, "Balance too low");   // rolls back the transaction
}
```
**Say it:** "The conditional update is atomic, but its result is ignored, so a request that lost the race still creates a withdrawal."

**Why the test missed it:** `cannotOverdrawWallet` runs the two requests one after the other. The second one fails at the early Java balance check and never reaches the debit. A concurrent test (8 threads, start latch, expect exactly one success) catches it.

## 2. No `@Valid`, so negative amounts work (Blocker)
**Plain terms:** the rules on the request class are only labels. Spring ignores them unless the controller asks it to check.

**Wrong**
```java
public WithdrawalResponse create(..., @RequestBody WithdrawalRequest request)   // no @Valid
```
Amount `-100`: fee floors at the 1.00 minimum, total is `-99`, the balance check passes, and the debit `balance - (-99)` **adds** money to the wallet.

**Fix**
```java
public WithdrawalResponse create(..., @Valid @RequestBody WithdrawalRequest request)
// and in the service, for every caller:
if (amount == null || amount.signum() <= 0) throw new WithdrawalException(INVALID_AMOUNT, ...);
// and in the database: CHECK (amount > 0)
```
**Say it:** "Validation annotations do nothing without `@Valid`. I'd also enforce the invariant in the service and with a check constraint."

## 3. Request currency is never compared with the wallet currency (Blocker)
**Plain terms:** 100 SEK and 100 EUR are very different amounts, and the code subtracts the bare number.

**Wrong:** SEK wallet, request `100 EUR`: wallet loses 100 SEK, the payout is 100 EUR (about 1,150 SEK).

**Fix**
```java
if (!wallet.getCurrency().equals(command.currency())) {
    throw new WithdrawalException(CURRENCY_MISMATCH, "Wallet is in " + wallet.getCurrency());
}
```
Rejecting is clearer than converting the debit. If you convert instead, you must store the rate used and decide the rounding and the fee currency.

## 4. Fee rounding: the rate itself is rounded (Major)
**Plain terms:** rounding the percentage before using it changes the percentage. 2.5% became 3%.

**Wrong**
```java
BigDecimal rate = fee.percent().divide(HUNDRED, 2, RoundingMode.HALF_UP);   // 2.5 / 100 = 0.025 -> 0.03
BigDecimal calculated = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
```
Fee on 200.00: **6.00** instead of **5.00**. Every customer pays 20% too much fee. 1.25% would become 1%, which undercharges.

**Fix:** keep full precision and round **once, at the end**.
```java
BigDecimal calculated = amount.multiply(fee.percent())
        .divide(HUNDRED, currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
```
**Say it:** "I round once at the end, to the currency's digits. Rounding an intermediate value changes the business rule."

**Tests that hid it:** the unit test used an integer percent (2%), and the integration tests expected the wrong fee (6.00). Tests built from the wrong output prove nothing. Take expected values from the business rule: 2.5% of 200.00 is 5.00.

## 5. FX conversion: `double` rate and `divide` without a scale (Major)
**Plain terms:** a double can't hold most decimal rates exactly, and dividing money without saying how many decimals to keep makes Java throw.

**Wrong**
```java
BigDecimal rate = new BigDecimal(rates.quote(...));     // new BigDecimal(0.0874) = 0.08739999999999999...
return amount.divide(rate).setScale(2, HALF_UP);        // no scale: ArithmeticException "Non-terminating decimal expansion"
```
It works for 1.25 (exact in binary, which is what the test uses) and throws for most real rates, so cross-currency withdrawals fail with a 500.

**Fix**
```java
BigDecimal rate = new BigDecimal(response.rateAsString());      // parse the decimal text, never a double
return amount.divide(rate, currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
```
**Say it:** "Rates are decimals from the start. Every `divide` states its scale and rounding mode."

## 6. Scale 2 is hard-coded for every currency (Major)
**Plain terms:** currencies have different numbers of decimals. JPY has 0, EUR 2, KWD 3.

**Wrong**
```java
.setScale(2, RoundingMode.HALF_UP)          // payout of 12345.67 JPY does not exist
@Column(precision = 19, scale = 2)          // KWD 10.005 passes validation, the column stores 2 decimals
minimum: 1.00                               // 1 JPY is under 1 cent, 1 KWD is about 2.8 EUR
```
**Fix:** take the digits from `Currency.getDefaultFractionDigits()`, store enough scale (or minor units in a `long`), and configure the minimum fee **per currency**. A small `Money(amount, currency)` type removes this whole class of bug.

## 7. Remote call while holding the wallet row lock (Major)
**Plain terms:** after the `UPDATE`, the wallet row stays locked until the transaction ends. The FX HTTP call (up to 2.5 seconds) runs inside that transaction.

**Wrong:** debit, then call the FX service, then save, all in one `@Transactional`. If FX is slow, every action on that wallet waits, and the connection pool (about 10) runs out under load.

**Fix:** fetch the FX quote **before** the transaction, or keep the transaction to the short part: debit plus insert.
**Say it:** "No network call inside a transaction that holds a row lock."

## 8. Idempotency replay uses `BigDecimal.equals` (Major)
**Plain terms:** `equals` compares the scale too. `100.00` is not equal to `100`.

**Wrong**
```java
existing.getAmount().equals(command.amount())      // DB: 100.00, retry sends 100 -> "different request" -> 409
```
A client that retries after a timeout gets a conflict, may think it failed, and retry with a **new** key.

**Fix:** `existing.getAmount().compareTo(command.amount()) == 0`.

## 9. `double` in the API response (Major)
`double payoutAmount` in `WithdrawalResponse`, while the other fields are `BigDecimal`. Large amounts lose precision and clients do float math. Fix: `BigDecimal` in the response too.

## 10. Stale balance in the response (Minor)
**Plain terms:** `debit` runs directly against the database. The `Wallet` object loaded earlier still holds the old balance.

**Wrong:** `return new WithdrawalResult(withdrawal, wallet.getBalance());` shows 500 after a debit down to 294.
**Fix:** `@Modifying(clearAutomatically = true)` and re-read, or `UPDATE ... RETURNING balance`.

## 11. Kafka send after commit can be lost (Minor, Major if the event drives payout)
**Wrong:** `@TransactionalEventListener(AFTER_COMMIT)` then `kafka.send(...)`. A crash between the commit and the send loses the event.
**Fix (done in the code):** transactional outbox. Insert the event row in the same transaction. A relay publishes it under ShedLock, in id order, waits for the ack, keeps the producer's `delivery.timeout.ms` shorter than the relay's wait, and consumers deduplicate on an event id.

## 12. Two requests with the same idempotency key at once (Minor)
Both miss the lookup, one hits the unique constraint and gets a 500 instead of the saved result. The debit rolls back, so no money is lost. Fix: catch the constraint violation and replay, or claim the key first.

## 13. Config (Minor)
`ddl-auto: update` in a payments service (no reviewed migrations, no check constraints). Use Flyway or Liquibase with `validate`. Set `spring.jpa.open-in-view: false`.

## 14. Rounding policy (Nit, but ask)
`HALF_UP` rounds half a cent in the player's favour every time. The mode is a business decision. Agree one, document it, use it everywhere.

## 15. Missing tests
No concurrent withdrawal test, no negative or zero amount test, no cross-currency test, no fractional-percent fee test, and tests whose expected numbers came from the buggy output.

---

## Decoys: these are correct
- `amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()` is the right way to check the decimals.
- The conditional `UPDATE ... WHERE balance >= :amount` is atomic. The bug is ignoring its result (topic 1).
- `quote(to, from)` then `amount.divide(rate)` has the right direction. Only the precision is wrong (topic 5).
- The event uses `toPlainString()` strings plus currency, keyed by an id. Money in JSON is fine.
- `calculated.max(minimum)` uses value comparison, so it is fine.

---

## Concepts from the live review (what you asked about)
| Concept | One-line takeaway |
|---|---|
| `@Version` and bulk updates | A `@Modifying` query bypasses `@Version`. Put the guard in the SQL (`WHERE balance >= :a`) and check the row count |
| Row locks | Any `UPDATE` locks its row until commit. At READ COMMITTED a waiter re-checks its `WHERE` against the new value |
| Kafka ordering | The producer's key picks the partition. Order inside a partition is arrival order, so the relay must publish in order |
| Outbox cursor | Don't keep a "last seen" timestamp. Select `status = NEW` ordered by id, because rows become visible in commit order |
| Idempotent producer | The Kafka client numbers messages per partition and retries in order. The relay only sees a failure once the client gives up |
| Exactly-once refund | A conditional status change (`FAILED` to `REFUNDED`) committed with the credit. Partitioning and row locks do not give exactly-once |
| Records and JPA | Entities need non-final, mutable, identity-based classes. Use records for DTOs, events, projections, embeddables |

## Questions to ask yourself on every money line
1. Is it `BigDecimal` with a currency, and is the scale taken from the currency?
2. Is every `divide` given a scale and a rounding mode, and is rounding done once, at the end?
3. Is the result of every conditional update checked?
4. What if two of these run at the same moment? What if the same request arrives twice?
5. Is anything remote called while a row lock or a transaction is open?
6. Does the test's expected value come from the business rule, or from what the code printed?
