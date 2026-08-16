-- Notion/Confluence DC import는 재실행 가능한 job과 item checkpoint로 관리한다.
-- 원본 payload 본문과 credentials는 DB에 넣지 않고 immutable object의 ref/checksum만 보관한다.
CREATE TABLE migration_job (
    id                  BIGSERIAL PRIMARY KEY,
    provider            VARCHAR(32)  NOT NULL,
    source_instance_id  VARCHAR(255) NOT NULL CHECK (source_instance_id <> ''),
    target_space_id     BIGINT REFERENCES space (id) ON DELETE SET NULL,
    requested_by        BIGINT       NOT NULL,
    mode                VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_migration_job_provider
        CHECK (provider IN ('NOTION', 'CONFLUENCE_DC')),
    CONSTRAINT chk_migration_job_mode
        CHECK (mode IN ('DRY_RUN', 'IMPORT')),
    CONSTRAINT chk_migration_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_migration_job_timestamps
        CHECK ((status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL)
            OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX idx_migration_job_poll
    ON migration_job (status, created_at, id);

CREATE TABLE migration_item (
    id                  BIGSERIAL PRIMARY KEY,
    job_id              BIGINT        NOT NULL REFERENCES migration_job (id) ON DELETE CASCADE,
    source_key          VARCHAR(64)    NOT NULL CHECK (source_key ~ '^[a-f0-9]{64}$'),
    external_object_id  VARCHAR(512)   NOT NULL CHECK (external_object_id <> ''),
    source_version      VARCHAR(100),
    source_checksum     VARCHAR(64)    NOT NULL CHECK (source_checksum ~ '^[a-f0-9]{64}$'),
    payload_ref         VARCHAR(1024)  NOT NULL CHECK (payload_ref <> ''),
    stage               VARCHAR(16)    NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    retry_count         INTEGER        NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_attempt_at     TIMESTAMPTZ,
    target_page_id      BIGINT REFERENCES page (id) ON DELETE SET NULL,
    last_error_code     VARCHAR(128),
    dead_lettered_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    lock_version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_migration_item_source UNIQUE (job_id, source_key),
    CONSTRAINT uk_migration_item_job_id UNIQUE (job_id, id),
    CONSTRAINT chk_migration_item_stage
        CHECK (stage IN ('EXTRACT', 'NORMALIZE', 'MEDIA_COPY', 'RESOLVE', 'VERIFY', 'DONE')),
    CONSTRAINT chk_migration_item_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'DEAD_LETTER')),
    CONSTRAINT chk_migration_item_done
        CHECK ((stage = 'DONE') = (status = 'COMPLETED')),
    CONSTRAINT chk_migration_item_retry
        CHECK ((status = 'RETRY_WAIT') = (next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_migration_item_dead_letter
        CHECK ((status = 'DEAD_LETTER') = (dead_lettered_at IS NOT NULL)
            AND (status <> 'DEAD_LETTER' OR last_error_code IS NOT NULL))
);
CREATE INDEX idx_migration_item_poll
    ON migration_item (job_id, status, next_attempt_at, id);

CREATE TABLE migration_issue (
    id                BIGSERIAL PRIMARY KEY,
    job_id            BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    issue_key         VARCHAR(64)   NOT NULL CHECK (issue_key ~ '^[a-f0-9]{64}$'),
    severity          VARCHAR(16)   NOT NULL,
    code              VARCHAR(128)  NOT NULL CHECK (code <> ''),
    source_path       VARCHAR(1024) NOT NULL CHECK (source_path <> ''),
    occurrence_count  INTEGER       NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_migration_issue_key UNIQUE (item_id, issue_key),
    CONSTRAINT fk_migration_issue_item
        FOREIGN KEY (job_id, item_id) REFERENCES migration_item (job_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_migration_issue_severity
        CHECK (severity IN ('INFO', 'WARNING', 'ERROR'))
);
CREATE INDEX idx_migration_issue_job
    ON migration_issue (job_id, severity, id);

-- source_key는 provider + instance + external ID의 SHA-256이다. 길이가 긴 외부 ID를 그대로
-- unique index에 넣어 PostgreSQL btree row size 제한을 넘기지 않으면서 재실행 멱등성을 유지한다.
CREATE TABLE migration_object_map (
    id                  BIGSERIAL PRIMARY KEY,
    source_key          VARCHAR(64)   NOT NULL UNIQUE CHECK (source_key ~ '^[a-f0-9]{64}$'),
    provider            VARCHAR(32)   NOT NULL,
    source_instance_id  VARCHAR(255)  NOT NULL CHECK (source_instance_id <> ''),
    external_object_id  VARCHAR(512)  NOT NULL CHECK (external_object_id <> ''),
    source_version      VARCHAR(100),
    source_checksum     VARCHAR(64)   NOT NULL CHECK (source_checksum ~ '^[a-f0-9]{64}$'),
    target_page_id      BIGINT REFERENCES page (id) ON DELETE SET NULL,
    last_job_id         BIGINT REFERENCES migration_job (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_migration_object_provider
        CHECK (provider IN ('NOTION', 'CONFLUENCE_DC'))
);
