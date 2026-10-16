-- 스페이스 라벨(스페이스 디렉토리의 "라벨" 열·필터).
--
-- 컨플루언스의 스페이스 카테고리에 해당한다 — 문서 라벨(page_label)과는 다른 것이다. 문서 라벨은
-- 문서를 편집할 수 있는 사람이 붙이고 검색·매크로가 읽지만, 스페이스 라벨은 스페이스 관리자(ADMIN)가
-- 붙이고 디렉토리가 스페이스를 묶어 보는 데만 쓴다. 한 표로 합치면 "이 라벨이 문서 것인가 스페이스
-- 것인가"를 모든 질의가 구분해야 한다.
--
-- 이름은 문서 라벨과 같은 규칙으로 정규화한다(trim + 소문자 + 내부 공백은 하이픈) — 사람에게는
-- 같은 "라벨"이라 규칙이 다르면 혼란스럽다.
CREATE TABLE space_label (
    id         BIGSERIAL   PRIMARY KEY,
    space_id   BIGINT      NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    name       VARCHAR(64) NOT NULL,
    created_by BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_space_label UNIQUE (space_id, name)
);
CREATE INDEX idx_space_label_name ON space_label (name);
