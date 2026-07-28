-- Milestone 7: nightclub (master plan §6.4, §9.11).

CREATE TABLE business_orders (
    id            CHAR(36)     NOT NULL,
    business      VARCHAR(32)  NOT NULL DEFAULT 'nightclub',
    customer_uuid CHAR(36)     NOT NULL,
    employee_uuid CHAR(36)     NOT NULL,
    order_lines   TEXT         NOT NULL,
    total_cents   BIGINT       NOT NULL,
    state         VARCHAR(16)  NOT NULL DEFAULT 'PROPOSED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_orders_customer (customer_uuid, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE receipts (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_id       CHAR(36)    NOT NULL,
    transaction_id CHAR(36)    NOT NULL,
    item_serial    CHAR(36)    NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_receipts_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-product stock; wholesale/retail prices are cents (rule: integer money).
CREATE TABLE business_stock (
    business     VARCHAR(32) NOT NULL,
    product      VARCHAR(32) NOT NULL,
    stock        INT         NOT NULL DEFAULT 0,
    retail_cents BIGINT      NOT NULL,
    version      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (business, product)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_orders (
    id         CHAR(36)    NOT NULL,
    business   VARCHAR(32) NOT NULL,
    product    VARCHAR(32) NOT NULL,
    quantity   INT         NOT NULL,
    cost_cents BIGINT      NOT NULL,
    ordered_by CHAR(36)    NOT NULL,
    state      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ordered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deliver_at DATETIME(3) NOT NULL,
    version    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_invorders_state (state, deliver_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Two-sided criminal escrow (§9.11): goods vs dirty money, bartender-mediated.
CREATE TABLE escrow_deals (
    id               CHAR(36)    NOT NULL,
    bartender_uuid   CHAR(36)    NOT NULL,
    party_a_uuid     CHAR(36)    NOT NULL,
    party_b_uuid     CHAR(36)    NOT NULL,
    agreed_cents     BIGINT      NOT NULL,
    a_item_serials   TEXT        NULL,
    b_collected_cents BIGINT     NOT NULL DEFAULT 0,
    a_locked         TINYINT(1)  NOT NULL DEFAULT 0,
    b_locked         TINYINT(1)  NOT NULL DEFAULT 0,
    commission_cents BIGINT      NOT NULL DEFAULT 0,
    state            VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version          INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_escrow_state (state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Anonymous bounties: sponsor hidden from players, retained for audit (§9.11).
CREATE TABLE bounties (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    sponsor_uuid CHAR(36)    NOT NULL,
    target_uuid  CHAR(36)    NOT NULL,
    amount_cents BIGINT      NOT NULL,
    state        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    paid_to      CHAR(36)    NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_bounties_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE nightclub_blacklist (
    player_uuid CHAR(36)     NOT NULL,
    reason      VARCHAR(190) NOT NULL,
    added_by    CHAR(36)     NOT NULL,
    added_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE nightclub_employees (
    player_uuid        CHAR(36)    NOT NULL,
    role               VARCHAR(24) NOT NULL,
    commission_percent INT         NOT NULL DEFAULT 10,
    hired_by           CHAR(36)    NOT NULL,
    state              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    hired_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version            INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO accounts (id, owner_type, owner_ref, code, iban, balance, allow_negative)
VALUES
    ('00000000-0000-0000-0000-000000000006', 'SYSTEM', NULL, 'nightclub_treasury', NULL, 0, 1),
    ('00000000-0000-0000-0000-000000000007', 'SYSTEM', NULL, 'bounty_escrow', NULL, 0, 0);
