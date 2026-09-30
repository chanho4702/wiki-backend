-- 다른 경로 저장(일반 저장·복원·이관·작업 토글)으로 공동 초안이 무효가 됐음을 표시한다.
-- 행을 지우지 않고 제자리 리셋하는 이유: collaboration-service가 메모리의 옛 Y.Doc을 upsert로 되살리기 때문.
-- reset_pending: true인 행은 WS 로드가 거부되고, 같은 base의 bootstrap만 새 기준으로 다시 채운 뒤 false로 되돌린다.
-- reset_epoch: 리셋할 때만 +1 되는 단조 카운터 — collaboration-service의 store 세대 가드가 이것을 본다.
--   generation은 게시에서도 +1 되므로 가드로 쓰면 게시 한 번에 남은 편집자가 쫓겨난다(리뷰 C-1).
-- collaboration-service initialize()도 같은 컬럼을 ADD COLUMN IF NOT EXISTS로 가진다(두 서비스 공동 DDL).
ALTER TABLE collaboration_document
    ADD COLUMN IF NOT EXISTS reset_pending boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS reset_epoch bigint NOT NULL DEFAULT 0;
