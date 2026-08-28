-- 리비전 본문 압축(2026-08-28 규모 결정: 이력은 전부 보관하고 압축만 한다).
--
-- 저장할 때마다 본문 전체가 새 리비전으로 복사된다 — 1MB 문서를 백 번 고치면 100MB다.
-- 이력을 지우지 않기로 했으므로 남은 수단은 저장 크기를 줄이는 것뿐이다.
--
-- Postgres는 큰 TEXT를 TOAST에서 이미 한 번 압축하지만 비율이 낮고 2KB 미만은 아예 압축하지
-- 않는다. 그래서 앱에서 gzip으로 눌러 BYTEA에 넣고, TOAST가 다시 누르지 않도록 STORAGE
-- EXTERNAL로 둔다(이중 압축은 CPU만 쓰고 크기는 거의 그대로다).
ALTER TABLE page_revision
    ADD COLUMN content_gzip BYTEA;

-- 기존 행은 content(TEXT)에 그대로 남는다. 엔티티가 둘 중 있는 쪽을 읽으므로 일괄 변환이 필요 없다.
-- 새 행은 압축본만 채우므로 NOT NULL을 풀어야 한다.
ALTER TABLE page_revision
    ALTER COLUMN content DROP NOT NULL;

-- 둘 다 비어 있는 행은 본문을 잃은 리비전이다 — DB에서 먼저 막는다.
ALTER TABLE page_revision
    ADD CONSTRAINT chk_page_revision_content
        CHECK (content IS NOT NULL OR content_gzip IS NOT NULL);

ALTER TABLE page_revision
    ALTER COLUMN content_gzip SET STORAGE EXTERNAL;
