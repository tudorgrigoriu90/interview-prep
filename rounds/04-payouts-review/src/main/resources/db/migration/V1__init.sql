CREATE TABLE wallet (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id  BIGINT        NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    balance    DECIMAL(19,2) NOT NULL,
    CONSTRAINT uq_wallet_player UNIQUE (player_id)
) ENGINE = InnoDB;

CREATE TABLE withdrawal (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id        BIGINT        NOT NULL,
    wallet_id        BIGINT        NOT NULL,
    idempotency_key  VARCHAR(64)   NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    fee              DECIMAL(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    payout_method_id VARCHAR(64)   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    psp_reference    VARCHAR(64)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    INDEX idx_withdrawal_key (idempotency_key)
) ENGINE = InnoDB;

CREATE TABLE ledger_entry (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    wallet_id     BIGINT        NOT NULL,
    withdrawal_id BIGINT        NOT NULL,
    entry_type    VARCHAR(16)   NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    created_at    DATETIME(6)   NOT NULL
) ENGINE = InnoDB;

CREATE TABLE outbox_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    event_key    VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL
) ENGINE = InnoDB;

CREATE TABLE processed_event (
    event_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL
) ENGINE = InnoDB;
