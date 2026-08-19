-- 다중 노드 migration worker는 item을 lease로 점유한다. 노드가 죽으면 lease 만료 시각이 지나
-- 다른 노드가 같은 item을 다시 집을 수 있어야 하므로, RUNNING 상태에 소유자·점유 토큰·만료를 함께 묶는다.
ALTER TABLE migration_item
    ADD COLUMN claimed_by       VARCHAR(64),
    ADD COLUMN claim_token      VARCHAR(36),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- V6까지는 소유자 없는 RUNNING이 가능했다. 아래 CHECK를 붙이기 전에 기존 행을 다시 대기로 돌린다.
-- 그 행을 처리하던 프로세스는 이미 없으므로 재시도가 유일하게 안전한 상태다.
UPDATE migration_item
   SET status = 'PENDING', next_attempt_at = NULL
 WHERE status = 'RUNNING';

ALTER TABLE migration_item
    ADD CONSTRAINT chk_migration_item_lease
        CHECK ((status = 'RUNNING')
            = (claimed_by IS NOT NULL AND claimed_by <> ''
                AND claim_token IS NOT NULL AND claim_token <> ''
                AND lease_expires_at IS NOT NULL));

-- 만료된 lease 회수는 status/만료 시각으로만 훑는다(job 범위와 무관한 전역 스윕).
CREATE INDEX idx_migration_item_lease
    ON migration_item (status, lease_expires_at);
