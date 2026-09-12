-- 전역 감사 피드(관리자 대시보드 "최근 활동")가 읽는 순서에 인덱스를 준다.
--
-- 지금까지 audit_log의 인덱스는 idx_audit_log_space (space_id, created_at DESC, id DESC) 하나였다.
-- 스페이스 스코프 조회에는 맞지만, 스페이스를 가로지르는 "최신순 20건"에는 쓰이지 않는다 —
-- 전건을 읽어 정렬한다. 감사 행은 스페이스 수만큼 늘어나므로 대시보드가 뜰 때마다
-- 전체 정렬이 도는 셈이 된다.
CREATE INDEX IF NOT EXISTS idx_audit_log_recent ON audit_log (created_at DESC, id DESC);

-- 유형 필터(GET /api/wiki/audit?type=…)와 기존 스페이스 삭제 기록 조회(action = 'SPACE_DELETED')가
-- 같은 모양이다. 둘 다 지금은 action으로 전건을 훑는다.
CREATE INDEX IF NOT EXISTS idx_audit_log_action_recent ON audit_log (action, created_at DESC, id DESC);
