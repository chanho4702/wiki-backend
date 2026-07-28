-- 콘텐츠 타입(폴더)과 게시 상태(초안) — 프론트 기획 P1/P3의 백엔드 계약.
-- 지금까지 두 개념은 프론트 목업에만 있었고, 백엔드 모드에서는 폴더를 만들 수도 게시할 수도 없었다.
--
-- DEFAULT로 기존 행을 백필한다: 이 컬럼이 없던 시절의 페이지는 전부 "일반 페이지"이고 "게시됨"이다.
-- 값은 대문자(JPA @Enumerated(STRING) 저장형), JSON 계약은 소문자다.
ALTER TABLE page
    ADD COLUMN type   VARCHAR(16) NOT NULL DEFAULT 'PAGE',
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED';

-- 오타 값이 조용히 들어가면 조회 시 IllegalArgumentException으로 터진다 — DB에서 먼저 막는다.
ALTER TABLE page
    ADD CONSTRAINT page_type_check CHECK (type IN ('PAGE', 'FOLDER')),
    ADD CONSTRAINT page_status_check CHECK (status IN ('DRAFT', 'PUBLISHED'));

-- 트리 조회는 스페이스 단위 전체 스캔이라 타입/상태 필터는 인덱스 없이도 충분하다(M 규모).
