-- Milestone 6: EMS and hospital (master plan §6.4, §9.8).

CREATE TABLE injuries (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    player_uuid CHAR(36)     NOT NULL,
    type        VARCHAR(24)  NOT NULL,
    severity    INT          NOT NULL DEFAULT 1,
    cause       VARCHAR(48)  NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    step        INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    healed_at   DATETIME(3)  NULL,
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_injuries_player (player_uuid, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Append-only treatment log: every step, medic, and tool (RP narration source).
CREATE TABLE treatments (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    injury_id   BIGINT      NOT NULL,
    medic_uuid  CHAR(36)    NOT NULL,
    patient_uuid CHAR(36)   NOT NULL,
    step        INT         NOT NULL,
    tool_type   VARCHAR(32) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_treatments_injury (injury_id),
    CONSTRAINT fk_treatments_injury FOREIGN KEY (injury_id) REFERENCES injuries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Every crafted medicine belongs to a traceable batch (e.g. DrRossi-45, §9.8).
CREATE TABLE medicine_batches (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(48)  NOT NULL,
    producer_uuid CHAR(36)     NOT NULL,
    medicine_type VARCHAR(32)  NOT NULL,
    legality      VARCHAR(16)  NOT NULL DEFAULT 'LEGAL',
    workstation   CHAR(36)     NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PRODUCED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batches_code (code),
    KEY idx_batches_producer (producer_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE medical_certificates (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    patient_uuid CHAR(36)   NOT NULL,
    medic_uuid  CHAR(36)    NOT NULL,
    item_serial CHAR(36)    NOT NULL,
    issued_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3) NOT NULL,
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_certificates_patient (patient_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Hospital treasury (§9.8): patient payments land here, medics earn commission.
INSERT INTO accounts (id, owner_type, owner_ref, code, iban, balance, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000005', 'SYSTEM', NULL, 'hospital_treasury', NULL, 0, 1);
