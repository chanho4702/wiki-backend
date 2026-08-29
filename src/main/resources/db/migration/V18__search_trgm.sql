-- 라이트 검색(OpenSearch 없는 배포)의 부분 일치 인덱스.
--
-- 검색은 `lower(col) like '%q%'`로 돈다. 선행 와일드카드라 B-tree가 쓰이지 않으므로
-- pg_trgm GIN이 필요하다. 없으면 순차 스캔이고, 소규모 설치에서는 그래도 동작한다.
--
-- CREATE EXTENSION은 권한이 없는 DB에서 실패할 수 있다. 여기서 마이그레이션이 죽으면 검색과
-- 무관한 앱 전체가 못 뜨므로 최선 노력으로 돌린다 — 못 만들면 인덱스 없이 계속 간다.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;

    CREATE INDEX IF NOT EXISTS idx_page_title_trgm
        ON page USING gin (lower(title) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS idx_page_content_trgm
        ON page USING gin (lower(content) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS idx_attachment_filename_trgm
        ON attachment USING gin (lower(filename) gin_trgm_ops);
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm 인덱스를 만들지 못했습니다(%). 라이트 검색은 순차 스캔으로 동작합니다.', SQLERRM;
END $$;
