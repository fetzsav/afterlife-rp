-- Milestone 2: double-entry economy and banking (master plan §7).
-- Money is integer minor units (cents, BIGINT). No floating point, ever.

CREATE TABLE organizations (
    id          CHAR(36)     NOT NULL,
    name        VARCHAR(48)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE organization_members (
    organization_id CHAR(36)    NOT NULL,
    player_uuid     CHAR(36)    NOT NULL,
    role            VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    joined_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    left_at         DATETIME(3) NULL,
    PRIMARY KEY (organization_id, player_uuid),
    KEY idx_member_player (player_uuid),
    CONSTRAINT fk_member_org FOREIGN KEY (organization_id) REFERENCES organizations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- owner_type PLAYER: owner_ref = player uuid, iban set.
-- owner_type ORGANIZATION: owner_ref = organization id, iban set.
-- owner_type SYSTEM: owner_ref NULL, code set (e.g. cash_issuance, seizure).
CREATE TABLE accounts (
    id            CHAR(36)     NOT NULL,
    owner_type    VARCHAR(16)  NOT NULL,
    owner_ref     CHAR(36)     NULL,
    code          VARCHAR(32)  NULL,
    iban          VARCHAR(34)  NULL,
    balance       BIGINT       NOT NULL DEFAULT 0,
    allow_negative TINYINT(1)  NOT NULL DEFAULT 0,
    frozen        TINYINT(1)   NOT NULL DEFAULT 0,
    frozen_reason VARCHAR(190) NULL,
    frozen_by     CHAR(36)     NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_owner (owner_type, owner_ref),
    UNIQUE KEY uk_accounts_code (code),
    UNIQUE KEY uk_accounts_iban (iban)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ledger_transactions (
    id              CHAR(36)     NOT NULL,
    idempotency_key VARCHAR(80)  NOT NULL,
    reason          VARCHAR(48)  NOT NULL,
    actor_uuid      CHAR(36)     NULL,
    description     VARCHAR(190) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_idem (idempotency_key),
    KEY idx_ledger_reason (reason),
    KEY idx_ledger_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- amount > 0 credits the account, amount < 0 debits it.
-- Every transaction's entries sum to zero (enforced in code + reconciliation).
CREATE TABLE ledger_entries (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    transaction_id CHAR(36)    NOT NULL,
    account_id     CHAR(36)    NOT NULL,
    amount         BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_entries_tx (transaction_id),
    KEY idx_entries_account (account_id, id),
    CONSTRAINT fk_entries_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Durable recovery for item handouts that could not complete (§7.4 step 8).
CREATE TABLE pending_deliveries (
    id             CHAR(36)    NOT NULL,
    player_uuid    CHAR(36)    NOT NULL,
    item_type      VARCHAR(32) NOT NULL,
    denomination   BIGINT      NULL,
    quantity       INT         NOT NULL DEFAULT 1,
    reason         VARCHAR(48) NOT NULL,
    transaction_id CHAR(36)    NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at   DATETIME(3) NULL,
    version        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_pending_player (player_uuid, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- System clearing accounts (fixed UUIDs so config/docs can reference them).
INSERT INTO accounts (id, owner_type, owner_ref, code, iban, balance, allow_negative)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM', NULL, 'cash_issuance', NULL, 0, 1),
    ('00000000-0000-0000-0000-000000000002', 'SYSTEM', NULL, 'seizure', NULL, 0, 0),
    ('00000000-0000-0000-0000-000000000003', 'SYSTEM', NULL, 'government_budget', NULL, 0, 1),
    ('00000000-0000-0000-0000-000000000004', 'SYSTEM', NULL, 'check_clearing', NULL, 0, 0);
