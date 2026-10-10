-- Schema owned by Flyway (versioned, reviewed). Hibernate runs with ddl-auto=validate.
-- MySQL 8 / InnoDB. Portable notes: DECIMAL, VARCHAR, BIGINT and DATETIME exist everywhere with small
-- spelling differences; AUTO_INCREMENT is MySQL's identity column; CHECK is enforced from MySQL 8.0.16.

CREATE TABLE wallet (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id  BIGINT        NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    balance    DECIMAL(19,4) NOT NULL,
    CONSTRAINT uq_wallet_player UNIQUE (player_id),
    CONSTRAINT chk_wallet_balance_not_negative CHECK (balance >= 0)
) ENGINE = InnoDB;

CREATE TABLE withdrawal (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id        BIGINT        NOT NULL,
    wallet_id        BIGINT        NOT NULL,
    idempotency_key  VARCHAR(64)   NOT NULL,
    amount           DECIMAL(19,4) NOT NULL,
    fee              DECIMAL(19,4) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    payout_method_id VARCHAR(64)   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    psp_reference    VARCHAR(64)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT uq_withdrawal_idem UNIQUE (player_id, idempotency_key),
    CONSTRAINT fk_withdrawal_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT chk_withdrawal_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_withdrawal_fee_not_negative CHECK (fee >= 0),
    INDEX idx_withdrawal_status_updated (status, updated_at, id)
) ENGINE = InnoDB;

CREATE TABLE ledger_entry (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    wallet_id     BIGINT        NOT NULL,
    withdrawal_id BIGINT        NOT NULL,
    entry_type    VARCHAR(16)   NOT NULL,
    direction     VARCHAR(6)    NOT NULL,
    amount        DECIMAL(19,4) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT uq_ledger_withdrawal_type UNIQUE (withdrawal_id, entry_type),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT fk_ledger_withdrawal FOREIGN KEY (withdrawal_id) REFERENCES withdrawal (id),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)
) ENGINE = InnoDB;

CREATE TABLE outbox_event (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    aggregate_id BIGINT        NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    topic        VARCHAR(128)  NOT NULL,
    event_key    VARCHAR(64)   NOT NULL,
    payload      VARCHAR(4000) NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    published_at DATETIME(6)   NULL,
    INDEX idx_outbox_status_id (status, id)
) ENGINE = InnoDB;

CREATE TABLE processed_event (
    event_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL
) ENGINE = InnoDB;

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
) ENGINE = InnoDB;
