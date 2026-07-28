-- Milestone 0/1 core foundation.
-- Conventions: UUIDs as CHAR(36); money in integer minor units (BIGINT);
-- mutable rows carry an optimistic `version` column (master plan §6.5).

CREATE TABLE players (
    uuid        CHAR(36)     NOT NULL,
    public_id   BIGINT       NOT NULL AUTO_INCREMENT,
    last_name   VARCHAR(16)  NOT NULL,
    nickname    VARCHAR(32)  NULL,
    locale      VARCHAR(8)   NULL,
    first_seen  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid),
    UNIQUE KEY uk_players_public_id (public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Append-only: application code must never UPDATE or DELETE rows here (rule 9).
CREATE TABLE audit_events (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    actor_uuid  CHAR(36)     NULL,
    actor_name  VARCHAR(48)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(96)  NULL,
    context     TEXT         NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_actor (actor_uuid),
    KEY idx_audit_action (action),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE points_of_interest (
    id          CHAR(36)     NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    world       VARCHAR(64)  NOT NULL,
    x           DOUBLE       NOT NULL,
    y           DOUBLE       NOT NULL,
    z           DOUBLE       NOT NULL,
    yaw         FLOAT        NOT NULL DEFAULT 0,
    pitch       FLOAT        NOT NULL DEFAULT 0,
    region_id   VARCHAR(64)  NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by  CHAR(36)     NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_poi_name (name),
    KEY idx_poi_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE serialized_items (
    serial       CHAR(36)     NOT NULL,
    item_type    VARCHAR(32)  NOT NULL,
    owner_uuid   CHAR(36)     NULL,
    denomination BIGINT       NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ISSUED',
    issued_by    CHAR(36)     NULL,
    issued_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    metadata     TEXT         NULL,
    version      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (serial),
    KEY idx_items_type (item_type),
    KEY idx_items_owner (owner_uuid),
    KEY idx_items_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
