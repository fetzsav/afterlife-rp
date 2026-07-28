-- Milestone 3: licenses, contracts, criminal records, detention, evidence,
-- property (master plan §6.2, §6.3, §9.2, §9.7).

CREATE TABLE licenses (
    id          CHAR(36)     NOT NULL,
    player_uuid CHAR(36)     NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    issued_by   CHAR(36)     NULL,
    issued_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3)  NULL,
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_licenses_player (player_uuid, type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- content is an immutable snapshot; content_hash is SHA-256 over it.
CREATE TABLE contracts (
    id           CHAR(36)     NOT NULL,
    type         VARCHAR(32)  NOT NULL DEFAULT 'GENERIC',
    content      TEXT         NOT NULL,
    content_hash CHAR(64)     NOT NULL,
    state        VARCHAR(16)  NOT NULL DEFAULT 'SIGNED',
    lawyer_uuid  CHAR(36)     NULL,
    validated_at DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_parties (
    contract_id CHAR(36)    NOT NULL,
    player_uuid CHAR(36)    NOT NULL,
    role        VARCHAR(24) NOT NULL DEFAULT 'PARTY',
    signed_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (contract_id, player_uuid),
    CONSTRAINT fk_parties_contract FOREIGN KEY (contract_id) REFERENCES contracts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Expungement archives (status EXPUNGED + archived_at); rows are never deleted.
CREATE TABLE criminal_records (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    player_uuid CHAR(36)     NOT NULL,
    charge      VARCHAR(190) NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    sentence    VARCHAR(190) NULL,
    created_by  CHAR(36)     NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    served_at   DATETIME(3)  NULL,
    archived_at DATETIME(3)  NULL,
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_records_player (player_uuid, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE detentions (
    id            CHAR(36)    NOT NULL,
    player_uuid   CHAR(36)    NOT NULL,
    officer_uuid  CHAR(36)    NOT NULL,
    max_minutes   INT         NOT NULL,
    lawyer_called TINYINT(1)  NOT NULL DEFAULT 0,
    state         VARCHAR(16) NOT NULL DEFAULT 'DETAINED',
    release_cause VARCHAR(24) NULL,
    started_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    released_at   DATETIME(3) NULL,
    version       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_detentions_player (player_uuid, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Evidence is never deleted; every read/write adds a custody entry (rule 9).
CREATE TABLE evidence (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    description VARCHAR(190) NOT NULL,
    item_serial CHAR(36)     NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'STORED',
    created_by  CHAR(36)     NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_custody (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    evidence_id BIGINT      NOT NULL,
    actor_uuid  CHAR(36)    NOT NULL,
    action      VARCHAR(24) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_custody_evidence (evidence_id),
    CONSTRAINT fk_custody_evidence FOREIGN KEY (evidence_id) REFERENCES evidence (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE properties (
    id           CHAR(36)     NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    type         VARCHAR(24)  NOT NULL,
    world        VARCHAR(64)  NOT NULL,
    x            DOUBLE       NOT NULL,
    y            DOUBLE       NOT NULL,
    z            DOUBLE       NOT NULL,
    region_id    VARCHAR(64)  NULL,
    price        BIGINT       NOT NULL,
    dirty        TINYINT(1)   NOT NULL DEFAULT 0,
    state        VARCHAR(24)  NOT NULL DEFAULT 'AVAILABLE',
    lock_version INT          NOT NULL DEFAULT 1,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_properties_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE property_ownership (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    property_id CHAR(36)    NOT NULL,
    player_uuid CHAR(36)    NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    started_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at    DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_ownership_property (property_id, ended_at),
    KEY idx_ownership_player (player_uuid, ended_at),
    CONSTRAINT fk_ownership_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Confidential files for illegal rentals (§9.7). Destruction keeps the row.
CREATE TABLE black_property_files (
    id          CHAR(36)    NOT NULL,
    property_id CHAR(36)    NOT NULL,
    item_serial CHAR(36)    NOT NULL,
    state       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    destroyed_at DATETIME(3) NULL,
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_blackfiles_property (property_id),
    CONSTRAINT fk_blackfiles_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Physical dirty-money store of the agency; only the director withdraws.
CREATE TABLE black_safes (
    id          VARCHAR(32) NOT NULL,
    dirty_cents BIGINT      NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO black_safes (id, dirty_cents) VALUES ('realestate', 0);

CREATE TABLE power_anomalies (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    property_id CHAR(36)    NOT NULL,
    consumption INT         NOT NULL DEFAULT 0,
    state       VARCHAR(16) NOT NULL DEFAULT 'ACCUMULATING',
    alerted_at  DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_anomaly_property (property_id),
    CONSTRAINT fk_anomaly_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
