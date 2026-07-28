-- Milestone 5: shared mission framework and job sessions (master plan §6.1, §17 M5).

-- Every long-running job action is a persisted state machine (rule 8).
-- States: OFFERED -> ACTIVE -> COMPLETED | FAILED | EXPIRED | CANCELLED.
CREATE TABLE missions (
    id              CHAR(36)     NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    owner_uuid      CHAR(36)     NOT NULL,
    state           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    target_poi_id   CHAR(36)     NULL,
    origin_poi_id   CHAR(36)     NULL,
    deadline        DATETIME(3)  NOT NULL,
    reward_snapshot BIGINT       NOT NULL DEFAULT 0,
    data            TEXT         NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at    DATETIME(3)  NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_missions_owner (owner_uuid, state),
    KEY idx_missions_state (state, deadline),
    KEY idx_missions_poi (target_poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE job_sessions (
    id          CHAR(36)    NOT NULL,
    player_uuid CHAR(36)    NOT NULL,
    job         VARCHAR(24) NOT NULL,
    state       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    started_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at    DATETIME(3) NULL,
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_sessions_player (player_uuid, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
