-- W23 첨부 버전.
--
-- 같은 이름의 파일을 다시 올리면 새 행이 생겨 **id가 바뀌었다**. 본문의 인라인 참조는 id로
-- 걸려 있으므로, 이미지를 고쳐 올려도 문서에는 옛 파일이 계속 보였다. 사용자가 할 수 있는
-- 일이라고는 본문의 참조를 손으로 갈아끼우는 것뿐이었다.
--
-- 이제 같은 이름 재업로드는 **같은 행을 갱신**하고, 직전 내용은 여기에 쌓는다. id가 유지되니
-- 인라인 참조가 저절로 새 파일을 가리킨다.
ALTER TABLE attachment ADD COLUMN version INT NOT NULL DEFAULT 1;

CREATE TABLE attachment_version (
    id              BIGSERIAL    PRIMARY KEY,
    attachment_id   BIGINT       NOT NULL REFERENCES attachment (id) ON DELETE CASCADE,
    version         INT          NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    -- 저장 좌표는 attachment와 같은 모양으로 둔다 — 복원이 이 값을 그대로 현재로 올린다.
    storage_backend VARCHAR(16)  NOT NULL,
    storage_bucket  VARCHAR(255),
    storage_key     VARCHAR(64)  NOT NULL,
    storage_version VARCHAR(255),
    checksum_sha256 VARCHAR(64),
    uploaded_by     BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_attachment_version UNIQUE (attachment_id, version)
);
CREATE INDEX idx_attachment_version_attachment ON attachment_version (attachment_id, version DESC);
