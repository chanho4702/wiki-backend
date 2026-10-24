-- 스페이스를 가로지르는 문서 피드의 인덱스(F-07 사람별 최근 문서 · 홈 인기, 2026-10-24).
-- 셋 다 살아 있고(휴지통 아님) 보관되지 않은 문서만 담는 부분 인덱스다 — 피드 질의가 언제나 그 조건을 건다
-- (deleted_at은 @SQLRestriction이, archived_at은 질의가 붙인다).

-- 사람별 최근 문서: (created_by = ? OR updated_by = ?) ORDER BY updated_at DESC, id DESC
-- 두 인덱스를 BitmapOr로 합친다. created_by 쪽은 "내 초안"(findDraftIdsByAuthor)도 함께 쓴다.
CREATE INDEX idx_page_created_by_recent ON page (created_by, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
CREATE INDEX idx_page_updated_by_recent ON page (updated_by, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL AND archived_at IS NULL;

-- 인기: status = 'PUBLISHED' AND updated_at >= ? ORDER BY view_count DESC, updated_at DESC, id DESC
-- 정렬 순서대로 읽다가 limit을 채우면 멈춘다(전체 정렬 없음).
CREATE INDEX idx_page_popular ON page (view_count DESC, updated_at DESC, id DESC)
    WHERE status = 'PUBLISHED' AND deleted_at IS NULL AND archived_at IS NULL;
