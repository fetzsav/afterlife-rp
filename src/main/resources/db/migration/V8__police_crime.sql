-- Milestone 8: police, crime, cross-module RP (master plan §6.2, §6.4, §9.3, §9.4).

CREATE TABLE warrants (
    id          CHAR(36)     NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    target_uuid CHAR(36)     NOT NULL,
    issuer_uuid CHAR(36)     NOT NULL,
    scope       VARCHAR(190) NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3)  NOT NULL,
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_warrants_target (target_uuid, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Approximate location only unless exact coordinates are justified (§9.3).
CREATE TABLE police_alerts (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(32)  NOT NULL,
    district    VARCHAR(64)  NOT NULL,
    world       VARCHAR(64)  NULL,
    source      VARCHAR(32)  NOT NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_alerts_state (state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
