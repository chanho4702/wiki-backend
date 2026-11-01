-- 홈 "최근 업데이트"(스페이스를 가로지르는 최근 수정순, 2026-11-01)의 인덱스.
-- 질의: archived_at IS NULL [AND space_id IN (...)] ORDER BY updated_at DESC, id DESC LIMIT n
-- (deleted_at은 @SQLRestriction이 붙인다). 초안·폴더도 담으므로 V44의 게시 전용 인덱스로는 정렬을 못 받는다.
-- updated_at은 조회수 갱신으로 바뀌지 않아 이 인덱스가 조회마다 새 항목을 쌓지 않는다(V44 주석 참조).
CREATE INDEX idx_page_live_recent ON page (updated_at DESC, id DESC)
    WHERE deleted_at IS NULL AND archived_at IS NULL;
