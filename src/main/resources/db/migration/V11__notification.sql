-- 알림(W18 선행분) — 트리거: 새 멘션 / 내가 만든·수정한·멘션된 페이지의 업데이트 / 댓글.
-- 행위자 자신은 수신하지 않는다. 같은 (수신자, 페이지, 타입)의 미읽음 업데이트 알림은
-- 새 행 대신 시각만 당겨 1건으로 합친다(폭주 방지) — 앱 레이어 규칙.
CREATE TABLE notification (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,  -- 수신자(org member id)
    type       VARCHAR(16)  NOT NULL,  -- MENTIONED | PAGE_UPDATED | COMMENT
    page_id    BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    actor_id   BIGINT       NOT NULL,  -- 행위자
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    CONSTRAINT chk_notification_type CHECK (type IN ('MENTIONED', 'PAGE_UPDATED', 'COMMENT'))
);

CREATE INDEX idx_notification_user ON notification (user_id, read_at, id);
CREATE INDEX idx_notification_collapse ON notification (user_id, page_id, type, read_at);
