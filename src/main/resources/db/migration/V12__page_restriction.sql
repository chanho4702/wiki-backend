-- W18 페이지 제한(ADR-W14-06) — space 권한보다 더 허용적일 수 없다(좁히기만).
-- (page, type)에 행이 하나라도 있으면 그 목록의 주체만 통과, 행이 없으면 "제한 없음".
-- VIEW 제한은 조상에서 자손으로 상속(교집합), EDIT 제한은 해당 페이지에만 적용된다.
-- 설계서: wiki-front docs/superpowers/specs/2026-08-23-w18-page-restriction-design.md
-- (설계서의 V11 번호는 알림(V11__notification)이 선점해 V12로 조정)
CREATE TABLE page_restriction (
    id             BIGSERIAL    PRIMARY KEY,
    page_id        BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    type           VARCHAR(8)   NOT NULL,  -- VIEW | EDIT
    principal_type VARCHAR(8)   NOT NULL,  -- USER | TEAM (원장은 org-service)
    principal_id   BIGINT       NOT NULL,
    created_by     BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_page_restriction_type CHECK (type IN ('VIEW', 'EDIT')),
    CONSTRAINT chk_page_restriction_principal CHECK (principal_type IN ('USER', 'TEAM')),
    CONSTRAINT uq_page_restriction UNIQUE (page_id, type, principal_type, principal_id)
);

CREATE INDEX idx_page_restriction_page ON page_restriction (page_id);
