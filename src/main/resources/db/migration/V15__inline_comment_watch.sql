-- W21-4 인라인 댓글과 구독(watch).
--
-- 인라인 댓글: V8이 남겨둔 anchor_type 확장 자리를 실제로 쓴다.
-- 앵커는 블록 id가 아니라 **인용한 본문 텍스트 + 몇 번째 등장인지**로 잡는다. 이 위키의 저장
-- 형식은 마크다운 문자열이라 안정적인 블록 식별자가 없기 때문이다(갭 분석 §4.2). 본문이 바뀌어
-- 인용을 못 찾으면 스레드를 지우지 않고 "위치 없음"으로 남긴다 — 대화가 편집 한 번에 사라지면 안 된다.
ALTER TABLE page_comment
    ADD COLUMN anchor_quote      TEXT,
    ADD COLUMN anchor_occurrence INT,
    ADD COLUMN resolved_at       TIMESTAMPTZ,
    ADD COLUMN resolved_by       BIGINT;

ALTER TABLE page_comment DROP CONSTRAINT chk_page_comment_anchor;
ALTER TABLE page_comment
    ADD CONSTRAINT chk_page_comment_anchor CHECK (anchor_type IN ('PAGE', 'INLINE'));

-- INLINE이면 인용문이 반드시 있어야 한다. 없으면 어디에도 붙지 못하는 유령 스레드가 된다.
ALTER TABLE page_comment
    ADD CONSTRAINT chk_page_comment_inline_quote
        CHECK (anchor_type <> 'INLINE' OR (anchor_quote IS NOT NULL AND anchor_occurrence IS NOT NULL));

-- 구독(watch) — 알림을 받을 사람의 원장.
--
-- 지금까지 알림 대상은 "작성자 + 리비전을 남긴 편집자"로 코드에 박혀 있어, 받기 싫어도 끌 수 없고
-- 관심 있는 문서를 골라 켤 수도 없었다. 이 표가 그 판단의 단일 원장이 된다.
CREATE TABLE page_watch (
    page_id    BIGINT      NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, user_id)
);
CREATE INDEX idx_page_watch_user ON page_watch (user_id);

-- 백필: 지금까지 암묵적으로 알림을 받던 사람들(작성자 + 리비전을 남긴 편집자)을 구독자로 옮긴다.
-- 이 단계가 없으면 배포 순간 모든 사용자가 조용히 알림을 잃는다.
INSERT INTO page_watch (page_id, user_id)
SELECT id, created_by FROM page
UNION
SELECT r.page_id, r.edited_by FROM page_revision r
ON CONFLICT DO NOTHING;
