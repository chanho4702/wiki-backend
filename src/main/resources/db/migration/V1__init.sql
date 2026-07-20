-- wiki 코어 스키마. 삭제는 hard delete + cascade (휴지통은 후속 백로그).
CREATE TABLE space (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE page (
    id         BIGSERIAL PRIMARY KEY,
    space_id   BIGINT       NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    parent_id  BIGINT       REFERENCES page (id) ON DELETE CASCADE,   -- NULL = 루트
    title      VARCHAR(255) NOT NULL,
    content    TEXT         NOT NULL,
    version    INT          NOT NULL DEFAULT 1,
    created_by BIGINT       NOT NULL,
    updated_by BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_page_space ON page (space_id);
CREATE INDEX idx_page_parent ON page (parent_id);

CREATE TABLE page_revision (
    id         BIGSERIAL PRIMARY KEY,
    page_id    BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    version    INT          NOT NULL,
    title      VARCHAR(255) NOT NULL,
    content    TEXT         NOT NULL,
    edited_by  BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (page_id, version)
);

CREATE TABLE attachment (
    id           BIGSERIAL PRIMARY KEY,
    page_id      BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    storage_key  VARCHAR(64)  NOT NULL UNIQUE,
    uploaded_by  BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachment_page ON attachment (page_id);
