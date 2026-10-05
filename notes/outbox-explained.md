# Transactional outbox: how the pieces fit together

This describes the **proposed** design. The relay and the outbox table do not exist in the code yet.

## 1. Components and who owns them

```mermaid
flowchart LR
    subgraph OurCode["Our code"]
        SVC["WithdrawalService<br/>one DB transaction"]
        RELAY["Relay (outbox poller)<br/>to be written, runs on every instance"]
        TPL["KafkaTemplate<br/>field named kafka"]
    end

    subgraph DB["PostgreSQL"]
        W[("wallet")]
        WD[("withdrawal")]
        OB[("outbox")]
    end

    subgraph Spring["Spring Boot and Spring Kafka"]
        FACT["DefaultKafkaProducerFactory<br/>built from application.yml"]
    end

    subgraph Lib["kafka-clients library"]
        PROD["KafkaProducer<br/>adds sequence numbers, batches, retries"]
    end

    subgraph Broker["Kafka broker"]
        TOPIC["topic cashier.withdrawal-requested.v1<br/>partition chosen by hash of key"]
    end

    SVC -->|debit| W
    SVC -->|insert PENDING| WD
    SVC -->|insert row NEW| OB
    RELAY -->|"SELECT ... FOR UPDATE SKIP LOCKED"| OB
    RELAY -->|"send(topic, key, event)"| TPL
    TPL --> FACT
    FACT -->|creates| PROD
    PROD -->|batches with sequence numbers| TOPIC
    RELAY -->|"mark PUBLISHED"| OB
```

## 2. Happy path

```mermaid
sequenceDiagram
    participant C as Client
    participant S as WithdrawalService
    participant DB as PostgreSQL
    participant R as Relay
    participant K as KafkaProducer
    participant B as Kafka broker

    C->>S: POST /withdrawals
    Note over S,DB: One transaction
    S->>DB: debit wallet
    S->>DB: insert withdrawal PENDING
    S->>DB: insert outbox row 42 NEW
    S->>DB: COMMIT
    S-->>C: 201 Created

    loop every poll interval
        R->>DB: SELECT NEW rows FOR UPDATE SKIP LOCKED
        DB-->>R: row 42
        R->>K: send(key=withdrawalId, payload)
        K->>B: batch, sequence number 1
        B-->>K: ack from all in-sync replicas
        K-->>R: future completes OK
        R->>DB: UPDATE row 42 to PUBLISHED, COMMIT
    end
```

## 3. Out-of-order batches: handled inside the Kafka client, the relay never sees it

```mermaid
sequenceDiagram
    participant R as Relay
    participant K as KafkaProducer
    participant B as Kafka broker

    R->>K: send event A, then event B (same key)
    K-xB: batch 1 (seq 1) lost in the network
    K->>B: batch 2 (seq 2)
    B-->>K: error, expected seq 1 but got 2
    Note over K: Client retries on its own, in order
    K->>B: batch 1 (seq 1)
    B-->>K: stored
    K->>B: batch 2 (seq 2)
    B-->>K: stored
    K-->>R: both futures complete OK
    Note over B: Log order is A then B
```

## 4. When the relay does see a failure

```mermaid
sequenceDiagram
    participant R as Relay
    participant K as KafkaProducer
    participant B as Kafka broker
    participant DB as PostgreSQL

    R->>K: send row 42
    K-xB: retries keep failing, broker unreachable
    Note over K: Gives up after delivery.timeout.ms
    K-->>R: future FAILS
    R->>DB: ROLLBACK, row 42 stays NEW
    Note over R,DB: Next poll picks row 42 up again
```

## 5. The duplicate: crash after Kafka acknowledged

```mermaid
sequenceDiagram
    participant A as Relay on instance A
    participant DB as PostgreSQL
    participant K as Kafka
    participant Bi as Relay on instance B
    participant Con as Consumer

    A->>DB: lock row 42 FOR UPDATE SKIP LOCKED
    A->>K: send row 42
    K-->>A: ack, the event is in the topic
    Note over A: Instance A crashes before UPDATE and COMMIT
    DB-->>DB: transaction rolled back, row 42 is NEW again
    Bi->>DB: lock row 42
    Bi->>K: send row 42 again
    K-->>Bi: ack
    Note over K: Topic now has the same event twice
    K->>Con: event 42, first copy
    Con->>Con: INSERT processed_event 42, 1 row, so process it
    K->>Con: event 42, second copy
    Con->>Con: INSERT processed_event 42, 0 rows, so skip it
```

## 6. Three instances sharing the work with SKIP LOCKED

```mermaid
flowchart TB
    OB[("outbox: rows 1 to 6 NEW")]
    A["Relay on instance A"]
    B["Relay on instance B"]
    C["Relay on instance C"]

    OB -->|"locks rows 1, 2"| A
    OB -->|"skips 1, 2 and locks 3, 4"| B
    OB -->|"skips 1 to 4 and locks 5, 6"| C
```

## Where each setting lives

| Thing | Where | Who acts on it |
|---|---|---|
| `acks: all`, `enable.idempotence: true` | `application.yml` | Spring Boot passes them to KafkaProducer |
| Message key (withdrawal id) | second argument of `kafka.send(...)` | Kafka picks the partition from its hash |
| Sequence numbers, in-order retries | inside `kafka-clients` | KafkaProducer and the broker, not our code |
| Unique event id for deduplication | we put it in the message, for example the outbox row id | Consumer checks it |
