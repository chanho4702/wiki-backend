-- 형제 순서(P1-001) — 트리 정렬을 서버가 저장한다. 순서열은 (space_id, parent_id) 그룹당
-- 하나이며 move 연산이 1..n으로 조밀하게 재부여한다(ALM issue.sort_order와 같은 모델).
ALTER TABLE page
    ADD COLUMN sort_order BIGINT NOT NULL DEFAULT 0;

-- 기존 행 백필: 그룹 안에서 id 순서(= 생성 순서)를 그대로 순번으로 삼는다.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY space_id, COALESCE(parent_id, 0) ORDER BY id
    ) AS position
    FROM page
)
UPDATE page
SET sort_order = ranked.position
FROM ranked
WHERE page.id = ranked.id;

CREATE INDEX idx_page_group_sort ON page (space_id, parent_id, sort_order, id);
