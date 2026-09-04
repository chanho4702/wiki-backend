-- W27-4 스페이스 구독.
--
-- 구독은 페이지 단위뿐이었다(V15). 그래서 "이 스페이스에 새 문서가 올라오면 알려줘"를 표현할
-- 방법이 없었고, 팀 스페이스를 지켜보려면 문서가 생길 때마다 하나씩 구독해야 했다 —
-- 새 문서는 정의상 아직 구독할 수 없으니 애초에 불가능한 요구였다.
--
-- 스페이스 구독은 페이지 구독을 대체하지 않고 **합집합**으로 더한다(같은 사람은 한 번).
-- 자동 구독은 두지 않는다: 스페이스를 만든 사람이 곧 그 스페이스의 모든 문서를 지켜보고 싶다는
-- 규칙은 없다. 페이지 구독의 자동 구독(만들기·편집·댓글)은 "그 문서에 대한 관심"이라는 근거가
-- 있지만 스페이스에는 그런 사건이 없다.
CREATE TABLE space_watch (
    space_id   BIGINT      NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX idx_space_watch_user ON space_watch (user_id);

-- 새 문서 게시(PAGE_PUBLISHED) — 스페이스 구독이 생기면서 처음으로 의미가 생긴 사건이다.
-- PAGE_UPDATED로 뭉뚱그리면 알림함에서 "새 문서가 생겼다"와 "있던 문서가 바뀌었다"가 구분되지
-- 않는다. 이메일 설정은 문서 업데이트 스위치를 같이 쓴다(별도 스위치를 늘리지 않는다).
ALTER TABLE notification DROP CONSTRAINT chk_notification_type;
ALTER TABLE notification ADD CONSTRAINT chk_notification_type
    CHECK (type IN ('MENTIONED', 'PAGE_UPDATED', 'COMMENT', 'SHARED', 'PAGE_PUBLISHED'));
