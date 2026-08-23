-- 페이지 댓글/답글. 중첩은 1단(답글의 답글 금지 — 앱 검증)이고, 최상위 삭제 시 답글은
-- FK cascade로 함께 사라진다. author_name은 작성 시점 표시 이름 스냅샷이다 — 사용자
-- 디렉터리가 없는 동안에도 화면이 이름을 보여줄 수 있어야 한다.
-- anchor_type은 후속 인라인 댓글(블록/범위 앵커) 확장 자리다. 지금은 PAGE 하나만 쓴다.
CREATE TABLE page_comment (
    id           BIGSERIAL PRIMARY KEY,
    page_id      BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    parent_id    BIGINT       REFERENCES page_comment (id) ON DELETE CASCADE,
    author_id    BIGINT       NOT NULL,
    author_name  VARCHAR(120) NOT NULL,
    body         TEXT         NOT NULL CHECK (body <> ''),
    anchor_type  VARCHAR(16)  NOT NULL DEFAULT 'PAGE',
    edited_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_page_comment_anchor CHECK (anchor_type IN ('PAGE'))
);

CREATE INDEX idx_page_comment_page ON page_comment (page_id, created_at, id);
CREATE INDEX idx_page_comment_parent ON page_comment (parent_id);
