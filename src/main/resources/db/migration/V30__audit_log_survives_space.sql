-- 스페이스 삭제 기록.
--
-- audit_log는 space에 ON DELETE CASCADE로 매달려 있어 스페이스를 지우면 그 기록도 함께 사라졌다.
-- "누가 언제 그 스페이스를 지웠나"를 남길 자리가 없었다. FK를 푼다 — 감사 기록은 대상보다
-- 오래 살아야 의미가 있다(target_id에 FK를 안 건 것과 같은 이유).
-- 스페이스 스코프 조회(findBySpace)는 그대로 동작하고, SPACE_DELETED 행은 전역 관리자가
-- 별도 목록으로 본다.
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_space_id_fkey;
