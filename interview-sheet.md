# Java Backend Screening — Questions and Model Answers

Sep 29, 2026 · @Tudor

## How to use this sheet

Read each answer aloud once tomorrow morning, in your own words; the goal is the structure, not memorising the sentences. Every answer runs 60 to 90 seconds and follows the same shape: situation, what you did, the trade-off, the result.

Five rules that cost you points in the mock round:

1. Say "we did" or "I did" when it's true. Say plainly "I haven't used that, but I know..." when it isn't. Never hedge with "I would" about your own past work.
2. Name the mechanism and the tool: outbox, idempotent consumer, Testcontainers, Spring Cloud Contract, Resilience4j.
3. Keep terms precise: streams versus reactive streams, outbox versus inbox, virtual threads help I/O not CPU.
4. Replace every \[bracketed placeholder\] with a real detail from your project (a topic name, a number, an incident).
5. Only claim what you really did. If you didn't use an outbox or a dead-letter topic, say what you did and what you'd add; that scores well too.

## Q1. Tell me about yourself and your technical background

**Model answer (about 60 seconds):**

"I'm a senior Java and Spring Boot engineer with more than 12 years of experience, including time as a technical team lead. I've built backend systems in banking, automotive, healthcare and retail, so I've worked both in regulated domains where correctness comes first and in high-volume commercial ones. \[Add one line on the betting-domain role if you want to link it to this company.\]

My most relevant recent project was refactoring a retail company's monolith into microservices with Java, Spring Boot and Kafka. The services communicated asynchronously through Kafka events. The hardest problems were keeping data consistent across services without distributed transactions, and keeping API and event contracts reliable between different teams. I solved those with a saga approach, idempotent consumers and consumer-driven contract tests.

I also use AI-assisted tools like Claude Code and Cursor to speed up delivery, but I stay responsible for design and review. I'm now looking for a role where I can keep working on event-driven, high-reliability backend systems."

**Watch out:** stop after about a minute. The screener will ask follow-ups on the project, so leave them the saga, idempotency and contracts as hooks.

## Q2. A customer places an order. Payment fails after the stock was reserved. What happens, and how did you make it reliable?

**Model answer (about 80 seconds):**

"In our system an order went through a choreography-based saga over Kafka. The order service saved the order as PENDING and published an OrderCreated event. Inventory reserved the stock and published StockReserved. Payment then tried to charge the customer. If it failed, it published a PaymentFailed event to its own topic. Inventory consumed it and released the reserved stock as a compensating action, and the order service consumed it too and moved the order to CANCELLED, so the customer saw a clear failure state.

We chose a saga over a distributed transaction because each service owns its database and we didn't want tight coupling or two-phase commit across teams. The price is eventual consistency, so every step was designed to be compensatable.

The hard part was reliability. Kafka delivers at least once, so consumers had to be idempotent: we recorded processed event IDs in the same transaction as the business change, so a redelivered PaymentFailed didn't release stock twice. To avoid losing an event when a service crashed between the DB commit and the publish, we \[used a transactional outbox / would use a transactional outbox\]. Failed messages went to a dead-letter topic with alerting.

The biggest lesson was that event contracts matter as much as the code. Different teams owned the services, so we versioned the schemas and agreed on them up front."

**Saga flow in one line:** OrderCreated, then StockReserved (or StockRejected), then PaymentCompleted (or PaymentFailed). On PaymentFailed: inventory releases stock, order becomes CANCELLED.

**Watch out:** inventory releases the stock, not the order service. The order service only changes the order status. Confusing who owns what was the slip in the mock round.

## Q3. How did you make sure messages weren't lost or processed twice? What if a consumer crashes halfway?

**Model answer (about 75 seconds):**

"We designed for at-least-once delivery and made the processing idempotent, because exactly-once across services isn't realistic.

On the producer side we used a transactional outbox: the service wrote the business change and an outbox row in one DB transaction, and a relay published it to Kafka. Producers ran with `acks=all` and the idempotent producer enabled, so a broker retry couldn't lose or duplicate a message.

On the consumer side we committed offsets only after processing succeeded. If a consumer crashed halfway through, the offset wasn't committed, so Kafka redelivered the message after the rebalance. That's why every consumer was idempotent: we stored the event ID in a processed-events table in the same transaction as the business update. A redelivered event hit the unique constraint, was skipped, and we just committed the offset.

Poison messages went through a retry with backoff and then to a dead-letter topic, so one bad message couldn't block the partition. We alerted on dead-letter volume and consumer lag.

The trade-off is extra DB writes and a bit of latency, but for orders and payments correctness matters more than throughput."

**Distinctions to keep straight:**

- **Outbox** is the producer side: business change plus event written atomically, then published.
- **Inbox / processed-events table** is the consumer side: the event ID is recorded in the same transaction as the business change.
- Recording the event ID on arrival, in a separate step from the business logic, is a trap: a crash in between makes the redelivery look like a duplicate and the work is lost.

**Watch out:** only claim the outbox and dead-letter topic if you used them. Otherwise say what you relied on and what you would add.

## Q4. When would you choose RabbitMQ over Kafka, and Kafka over RabbitMQ?

**Model answer (about 70 seconds):**

"I've worked hands-on with Kafka, not RabbitMQ, so I'll answer from the architectural differences I know.

Kafka is a distributed, durable log. Messages stay for the retention period, and each consumer group tracks its own offset. That means many independent consumers can read the same stream, a new service can replay history, and ordering is guaranteed per partition. It scales to very high throughput. I'd choose it for event streaming, event-driven integration between many services, and audit trails, like our order events, which inventory, payment and notifications all consumed.

RabbitMQ is a traditional message broker. Producers publish to exchanges, which route to queues by rules like direct, topic or fanout. Consumers acknowledge each message, and then it's removed. It supports fan-out through multiple queues, but there's no replay. It shines for task queues and work distribution, flexible routing, per-message acknowledgements, retries, TTLs and dead-letter exchanges. I'd pick it for background jobs like sending emails or processing uploads across a pool of workers, where each task is done once and you don't need history.

So: Kafka when the events are the source of truth and need replay and scale; RabbitMQ when you need smart routing and job processing with simple semantics."

**Side by side:**

|  | Kafka | RabbitMQ |
| --- | --- | --- |
| Model | Durable, replayable log | Broker with exchanges and queues |
| After consumption | Message stays until retention expires | Removed after acknowledgement |
| Multiple consumers | Each consumer group reads the full stream | Each queue gets its own copy; consumers on one queue compete |
| Ordering | Per partition | Per queue (weaker with several consumers) |
| Best for | Event streaming, integration, replay, high throughput | Task queues, routing, request/reply, simple work distribution |

**Watch out:** don't say RabbitMQ "can't have multiple consumers". It can, through multiple queues on an exchange. The real difference is the model: replayable log versus routed queues.

## Q5. You said contracts between teams and APIs were a hard problem. What went wrong, and how did you solve it?

**Model answer (about 80 seconds):**

"One team changed a response type in an API our service consumed. Our service could no longer deserialize the response, and customers hit failures, which showed up as support tickets. The root cause was that a breaking change could reach production without any automated check between the two teams.

We fixed it at two levels. First, we introduced contract testing with Spring Cloud Contract. The contracts generate tests on the producer side, so if the producer changes something that breaks a consumer's contract, their build fails before release. The generated stubs are published to Artifactory, and consumers test against those stubs instead of hand-written mocks, so the stubs are the source of truth.

Second, we used an OpenAPI specification. We generated our client from the schema the other team published, so type changes showed up at compile time, not in production.

We also agreed on ground rules: additive changes only, versioned endpoints for breaking changes, a deprecation period, and consumers ignore unknown fields.

After that, breaking changes were caught in CI rather than by customers, and we had no more incidents of that kind."

**Watch out:** tell it as a story in order: situation, impact, root cause, fix, result. If asked about events, the equivalent is a schema registry with Avro or Protobuf and backward-compatibility checks, but only mention it if you used it.

## Q6. A downstream service becomes slow or fails. What happens to your Spring Boot service, and how do you protect it?

**Model answer (about 75 seconds):**

"Without protection, a slow downstream service ties up my request threads and connections. The thread pool exhausts, my service becomes slow too, and the failure cascades to whoever calls me.

First, I'd set explicit connect and read timeouts, so no call waits forever. Then I'd add a Resilience4j circuit breaker. It tracks the failure and slow-call rate over a sliding window. When it crosses a threshold, for example 50%, the circuit opens and calls fail immediately instead of waiting. After a wait period it goes half-open, lets a few trial calls through, and closes again if they succeed.

On top of that I'd define a fallback, such as cached data, a sensible default or a clear degraded response, so the user gets something useful. I'd add retries with exponential backoff and jitter, only for idempotent calls, and I'd consider a bulkhead to cap concurrency to that dependency.

Finally, I'd expose the breaker state through Actuator and Micrometer and alert when it opens, so the team knows about the problem before customers report it."

**Honest framing if asked whether you've done it:** "I haven't run this in production myself, but this is how I'd set it up, and I've worked with the failure modes it protects against."

**Circuit breaker states:** closed (calls flow), open (fail fast), half-open (a few trial calls test recovery).

**Simple example.** Dependencies: the `resilience4j-spring-boot3` starter (match your Boot version) and `spring-boot-starter-aop`.

`application.yml`:

```yaml
spring:
  http:
    client:
      connect-timeout: 1s     # Boot 3.4+
      read-timeout: 2s

resilience4j:
  circuitbreaker:
    instances:
      inventory:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException   # 4xx is not an outage
  retry:
    instances:
      inventory:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        enable-randomized-wait: true          # jitter
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException

management:
  endpoints.web.exposure.include: health,circuitbreakers,metrics
  health.circuitbreakers.enabled: true
```

The client:

```java
@Service
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("http://inventory-service").build();
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory", fallbackMethod = "stockFallback")
    public StockResponse getStock(String sku) {
        return restClient.get()
                .uri("/stock/{sku}", sku)
                .retrieve()
                .body(StockResponse.class);
    }

    // same parameters + Throwable; also catches CallNotPermittedException when the circuit is open
    private StockResponse stockFallback(String sku, Throwable t) {
        log.warn("Inventory unavailable for {}: {}", sku, t.toString());
        return StockResponse.unknown(sku);   // degraded response instead of an error
    }
}
```

**Worth knowing if asked:** by default the retry runs outside the circuit breaker, so each retry attempt counts toward the breaker's statistics, and the fallback fires only after retries are exhausted or the circuit is open.

## Q7. How did you test your microservices, from unit tests to Kafka and other services?

**Model answer (about 75 seconds):**

"We followed a test pyramid. The bulk were fast unit tests with JUnit and Mockito for business logic. Above that, slice tests like `@WebMvcTest` and `@DataJpaTest` checked that controllers, serialization and repositories were wired correctly without starting the whole app.

For integration, we used Testcontainers to run a real Kafka broker and database, so the tests matched production behavior. Because consumers are asynchronous, we used Awaitility to assert on the outcome instead of sleeping. We specifically tested duplicate delivery, sending the same event twice and asserting the effect happened once, and the failure path, checking that a bad message ended up in the dead-letter topic.

Between teams, the Spring Cloud Contract tests guarded the API boundaries, so a breaking change failed the producer's build.

At the top, a small Playwright regression pack ran before UAT releases and covered the critical user journeys. We kept it small on purpose because end-to-end tests are slow and brittle, and we pushed most of the coverage down the pyramid."

**Watch out:** confirm you really used Testcontainers (rather than another container setup) before naming it, and tie the answer back to your contract-testing story from Q5 so it sounds like one coherent strategy.

## Q8. Which Java version do you use, and which newer features have you actually used?

**Model answer (about 80 seconds):**

"We ran on Java \[17/21\], and I used the modern features where they gave real value.

Records are my default for DTOs and event payloads. They're concise and immutable, though only shallowly so, since a list inside a record can still be mutated. I don't use them for JPA entities because entities need to be mutable with a no-arg constructor for proxying.

I used streams for in-memory data transformation, like grouping and mapping collections, as long as they stay readable. Separately, for reactive pipelines I've worked with WebFlux and Reactor's `Flux` and `Mono`, which are a different, non-blocking model.

`Optional` I use for return types to make absence explicit, with `orElseThrow` rather than `get`, and never for fields or parameters.

For virtual threads, the benefit is scalability on blocking I/O. In Spring Boot you can enable them with one property, `spring.threads.virtual.enabled=true`, and each request gets a cheap thread, so a service that waits on many downstream calls handles far more concurrency without a huge thread pool. It doesn't help CPU-bound work like heavy Excel generation, and you still need to watch limits like the database connection pool and older `synchronized` pinning issues."

**Watch out:**

- Streams (`java.util.stream`) and reactive streams (`Flux`, `Mono`) are different things. Never say "streams with WebFlux" as if they were one.
- Virtual threads help when threads mostly wait on I/O (HTTP, JDBC), not when they burn CPU. Pick an I/O example.
- State your Java version at the start.

## Q9. What happens when a @Transactional method calls another @Transactional method in the same class? When does it roll back?

**Model answer (about 60 seconds):**

"Spring applies `@Transactional` through a proxy. A call from another bean goes through the proxy and gets a transaction. A call from inside the same class bypasses the proxy, so the annotation on the inner method is ignored and no new transaction starts. I avoid this by putting the transactional logic in a separate bean.

For rollback, by default Spring rolls back on unchecked exceptions and errors, not on checked exceptions, unless you set `rollbackFor`. And if you catch the exception yourself, the transaction commits, because the proxy never sees the failure. In our order flow, I keep the order save and the outbox write in one transactional method so they commit or roll back together."

**Self-invocation example:**

```java
@Service
class OrderService {

    public void placeOrder(Order o) {        // not transactional
        save(o);                             // this.save(...) bypasses the proxy
    }

    @Transactional
    public void save(Order o) { ... }        // annotation has NO effect here
}
```

**Details to have ready:**

- If the outer method is not transactional, the inner `@Transactional` does nothing: no transaction at all.
- If the outer method is transactional, the inner one just runs inside it, and even `REQUIRES_NEW` on the inner method is ignored.
- The same proxy limitation affects `@Async`, `@Cacheable` and `@Retryable`.
- Fixes: move the method to another bean (cleanest), use a `TransactionTemplate`, or use AspectJ weaving.
- Rollback traps: a checked exception commits by default; a swallowed exception commits; an inner `REQUIRED` method that throws marks the shared transaction rollback-only, so an outer method that catches it and carries on gets an `UnexpectedRollbackException` at commit.

**Why it matters for your project:** if the order save and the outbox write were split across two methods in the same class, or an exception were swallowed in a `try/catch`, you could commit the order without the outbox event and the saga would silently stall.

## Q10. Two customers try to reserve the last item in stock at the same moment. What can go wrong, and how do you prevent it?

**Model answer (about 80 seconds):**

"That's a classic race condition, a check-then-act problem. Both requests read stock = 1, both see it's available, both decrement, and we oversell.

Inside one JVM I'd make the check and the update atomic: a `synchronized` block or lock, an `AtomicInteger` with compare-and-set, or `ConcurrentHashMap.compute`. But in a microservice with several instances behind a load balancer, a JVM lock only protects one instance. The guarantee has to live in the shared resource, which is the database.

There are three options. First, an atomic conditional update: `UPDATE stock SET qty = qty - :n WHERE sku = :sku AND qty >= :n`, then check that exactly one row was updated. It's the simplest and fastest, and no lock is held across the request. Second, optimistic locking with a JPA `@Version` column, retrying on an optimistic-lock failure; that's good when conflicts are rare. Third, pessimistic locking with `SELECT ... FOR UPDATE`, which fits when conflicts are frequent and retries are expensive, at the cost of blocking and deadlock risk, so I'd always lock rows in a consistent order.

For stock reservation I'd pick the atomic conditional update. With Kafka I can also key events by SKU, so all events for one item land on the same partition and are processed one at a time by a single consumer. And I keep transactions short so locks are held as briefly as possible."

**The conditional update in Spring Data JPA:**

```java
@Modifying
@Query("update Stock s set s.quantity = s.quantity - :n "
     + "where s.sku = :sku and s.quantity >= :n")
int reserve(@Param("sku") String sku, @Param("n") int n);

// in the service, inside @Transactional
if (stockRepository.reserve(sku, qty) == 0) {
    throw new OutOfStockException(sku);      // nothing changed, nobody oversold
}
```

**Watch out:**

- A `synchronized` block or `ReentrantLock` does not protect you across multiple service instances. Say this explicitly, it is the senior insight in this answer.
- `volatile` gives visibility, not atomicity: `count--` on a volatile field is still a race.
- Tie it back to your saga: the reservation step in inventory is exactly this problem.

### Java concurrency in one page

Everything behind Q10, so you can answer any follow-up. Read this once, then move on.

**The three problems.** Every concurrency bug is one of these:

1. **Atomicity:** a compound action (`i++`, check-then-act) can be interleaved by another thread.
2. **Visibility:** one thread's write may not be seen by another, because of CPU caches and compiler reordering.
3. **Ordering:** the compiler and CPU may reorder instructions unless the Java Memory Model forbids it.

The Java Memory Model answers with **happens-before** rules: an unlock happens-before the next lock of the same monitor; a `volatile` write happens-before a later read of it; `Thread.start()` and `join()` create ordering; and `final` fields are safely visible after construction.

**The tools, from lowest to highest level:**

| Tool | What it gives you | Use it when / gotcha |
| --- | --- | --- |
| `volatile` | Visibility and ordering, not atomicity | Stop flags and safely published references. `count++` is still unsafe. |
| `synchronized` | Mutual exclusion plus visibility; reentrant | Simple critical sections. Before Java 24 it pins virtual threads. |
| `Atomic*`, `LongAdder` | Lock-free atomic updates via compare-and-set | Counters and flags. `LongAdder` scales better under heavy contention. |
| `ReentrantLock`, `ReadWriteLock`, `StampedLock` | `tryLock`, timeouts, interruptibility, fairness | When `synchronized` is too rigid. Always `unlock()` in `finally`. |
| Concurrent collections | `ConcurrentHashMap` (CAS plus per-bin locking, atomic `compute`/`merge`, no null keys or values), `CopyOnWriteArrayList` (read-heavy), `BlockingQueue` (producer-consumer) | Never do check-then-act across two calls; use `putIfAbsent` or `compute`. |
| Synchronizers | `CountDownLatch`, `CyclicBarrier`, `Semaphore`, `Phaser` | Coordinating threads: wait for N tasks, limit concurrent access, meet at a barrier. |
| `ExecutorService` | Thread pools that separate submitting work from running it | See the pool flow below. Always shut it down and name your threads. |
| `CompletableFuture` | Async pipelines: `thenApply`, `thenCompose`, `thenCombine`, `allOf`, `exceptionally` | Pass your own executor; avoid blocking `join()` on the common pool. |
| Virtual threads (Java 21) | Cheap threads for blocking I/O; one per task, no pooling | Not for CPU-bound work. Watch pinning (`synchronized`, fixed in Java 24) and connection-pool limits. |
| `ThreadLocal` | Per-thread state | Leaks in thread pools unless you call `remove()`. Consider `ScopedValue` on newer Java. |

**Thread pool flow (`ThreadPoolExecutor`).** A new task goes to a core thread if one is free; otherwise it goes into the queue; only when the queue is full does the pool grow up to the maximum size; beyond that the rejection policy applies. Consequence: with an unbounded queue the pool never grows past its core size, so `Executors.newFixedThreadPool` can hide unbounded memory growth. Sizing rule of thumb: CPU-bound work about the number of cores; I/O-bound work more, roughly cores × (1 + wait time / compute time).

**Design rules that matter more than any API:**

- Prefer immutability and thread confinement: data nobody can change needs no lock.
- Use high-level utilities (executors, concurrent collections) instead of raw `Thread`, `wait` and `notify`.
- Keep critical sections small, and never call unknown code or do I/O while holding a lock.
- Prevent deadlock by breaking one of its four conditions (mutual exclusion, hold-and-wait, no preemption, circular wait); in practice, always acquire locks in the same global order.
- Know the liveness failures: deadlock (everyone waits forever), livelock (everyone keeps reacting and makes no progress), starvation (one thread never gets its turn).
- In microservices, in-JVM locks don't cross instances: use database constraints, atomic updates, optimistic or pessimistic locking, or key-based ordering in Kafka.

**Likely follow-up questions, one line each:**

| Question | Short answer |
| --- | --- |
| `synchronized` vs `ReentrantLock`? | `ReentrantLock` adds `tryLock`, timeouts, interruptible and fair locking, and multiple conditions; `synchronized` is simpler and releases automatically. |
| `volatile` vs atomic? | `volatile` guarantees visibility only; atomics also make read-modify-write operations atomic. |
| Why isn't `HashMap` thread-safe? | Concurrent writes can lose updates or corrupt its internal structure; use `ConcurrentHashMap`. |
| How does `ConcurrentHashMap` work? | CAS for empty buckets, locks only the head of a bucket on collision, so reads don't block and writes to different buckets run in parallel. |
| `wait()` vs `sleep()`? | `wait()` releases the monitor and must be called inside `synchronized`, in a loop; `sleep()` keeps any locks it holds. |
| `submit()` vs `execute()`? | `submit()` returns a `Future` and captures exceptions inside it; `execute()` returns nothing and lets exceptions reach the thread's handler. |
| What if the pool's queue is full? | The rejection policy runs: abort (throws `RejectedExecutionException`), caller-runs, discard, or discard-oldest. |
| Race condition vs data race? | A data race is unsynchronized access to shared memory where one access is a write; a race condition is any timing-dependent bug, even with fully synchronized code. |
| Platform threads vs virtual threads? | Platform threads map one-to-one to OS threads and are expensive; virtual threads are scheduled by the JVM on a few carrier threads and suit blocking I/O. |

## Q11. Why are you looking for a new role, and what are you looking for?

**Model answer (about 40 seconds):**

"I've built my career around event-driven Java and Spring Boot systems, and what draws me to this role is working at the scale and reliability level your platform needs. I've worked in betting before, so I know how demanding real-time, high-volume, regulated systems are, and that's the kind of problem I enjoy. I'm looking for a team where I can contribute on architecture and reliability and keep growing over the long term.

On a personal level, I've settled in Sweden with my family, and I want to be part of a Swedish team and workplace culture, which is one reason this role appeals to me."

**Watch out:**

- Lead with the work, then the personal reason. "The tech stack is impressive" is a compliment, not a reason.
- Don't lead with "job security". Say you want a long-term role where you can grow and have impact.
- Spend a few minutes before the call reading about the company's products and engineering, so the domain link is concrete.

## Questions to ask them, and the morning checklist

Pick two or three of these at the end of the call:

1. What does the architecture look like today, and how much of it is event-driven versus request/response?
2. What are the biggest technical challenges the team is working on over the next year?
3. How do teams handle contracts and changes between services?
4. What does the second technical interview cover, so I can prepare properly?

Last-minute checklist for tomorrow morning:

- [ ] Read Q2, Q3, Q5 and Q10 aloud once, and skim the concurrency one-pager; these are your strongest stories and the ones most likely to be probed.
- [ ] Fill every \[bracketed placeholder\] with a real detail (Java version, topic names, one incident, one number).
- [ ] Decide honestly what you did and didn't use (outbox, dead-letter topic, Testcontainers) and answer accordingly.
- [ ] Prepare your three reusable stories: the monolith migration, the contract incident, a saga failure.
- [ ] Read a few minutes about the company's products and engineering.
- [ ] Keep every answer to 60 to 90 seconds: situation, what you did, the trade-off, the result.
