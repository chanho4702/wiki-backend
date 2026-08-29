-- W23 페이지 템플릿.
--
-- 새 문서가 언제나 빈 화면이라, 회의록·기술 결정처럼 형식이 반복되는 문서를 매번 처음부터
-- 다시 짰다. 템플릿은 "그 스페이스가 합의한 문서 형태"다.
--
-- 스페이스 스코프다(space_id NOT NULL). 전역 템플릿은 넣지 않았다 — 어느 스페이스에서나
-- 보이는 목록은 관리 주체가 모호해지고, 지금 필요한 것은 팀별 형식이다. 필요해지면 그때
-- space_id를 nullable로 여는 마이그레이션을 더한다.
CREATE TABLE page_template (
    id          BIGSERIAL    PRIMARY KEY,
    space_id    BIGINT       NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    icon        VARCHAR(16),
    -- 본문은 페이지와 같은 마크다운 문자열이다(저장 포맷은 하나다).
    content     TEXT         NOT NULL,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 같은 스페이스에 같은 이름이 둘이면 고르는 화면에서 구분할 수 없다.
    CONSTRAINT uq_page_template_name UNIQUE (space_id, name)
);
CREATE INDEX idx_page_template_space ON page_template (space_id, name);
