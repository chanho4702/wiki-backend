-- 컨플루언스 DC 마이그레이션 M1 — 원본 접속 정보와 단계별 산출물.
--
-- V6는 "원본 payload 본문은 DB에 넣지 않고 ref/checksum만 보관한다"는 전제로 만들었다. 그 전제는
-- 오프라인 export 파일이 object storage에 있을 때의 이야기고, DC 온라인 추출에는 그런 파일이 없다 —
-- 원본은 남의 서버에 있고 우리가 재실행할 때마다 사라지거나 바뀔 수 있다. 단계 사이에 넘길 산출물
-- (스냅샷·IR·마크다운)을 어딘가 두어야 재개가 성립하므로 여기에 둔다. item.payload_ref는 이제
-- `dc:content/{id}` 같은 원본 참조 문자열이고, 실제 본문은 migration_payload가 든다.

CREATE TABLE migration_source (
    job_id             BIGINT       PRIMARY KEY REFERENCES migration_job (id) ON DELETE CASCADE,
    base_url           VARCHAR(512) NOT NULL CHECK (base_url <> ''),
    space_key          VARCHAR(255) NOT NULL CHECK (space_key <> ''),
    -- 평문 저장이다. 지금은 DB 접근 통제가 유일한 보호막이고, 암호화 키 관리(어디에 두고 어떻게
    -- 교체하는가)는 후속 ADR에서 정한다. 어떤 응답 DTO에도 이 값을 싣지 않는다(기획 P8).
    auth_token         TEXT         NOT NULL CHECK (auth_token <> ''),
    discovered_count   INTEGER      NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    discovered_at      TIMESTAMPTZ,
    source_space_name  VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version       BIGINT       NOT NULL DEFAULT 0
);

-- 단계 산출물. 한 item당 종류별로 최대 한 행이고, 재실행하면 같은 행을 덮어쓴다 —
-- 이력을 쌓으면 500페이지 스페이스를 두 번만 돌려도 본문이 세 벌씩 눌러앉는다.
CREATE TABLE migration_payload (
    id          BIGSERIAL   PRIMARY KEY,
    item_id     BIGINT      NOT NULL REFERENCES migration_item (id) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL,
    body        TEXT        NOT NULL,
    -- VARCHAR(64)다. CHAR(64)로 두면 Postgres가 bpchar로 잡아 Hibernate의 ddl-auto=validate가
    -- 부팅을 거부한다(varchar를 기대). V6의 다른 checksum 컬럼도 전부 VARCHAR(64)다.
    checksum    VARCHAR(64) NOT NULL CHECK (checksum ~ '^[a-f0-9]{64}$'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_migration_payload UNIQUE (item_id, kind),
    CONSTRAINT chk_migration_payload_kind
        CHECK (kind IN ('SNAPSHOT', 'IR', 'MARKDOWN'))
);
