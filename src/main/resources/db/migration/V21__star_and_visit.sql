-- W23 즐겨찾기·최근 방문의 서버 이전.
--
-- 둘 다 브라우저 localStorage에만 있었다. 회사 노트북에서 별표한 문서가 집 컴퓨터에는 없고,
-- 브라우저 데이터를 한 번 지우면 그동안 모아 둔 즐겨찾기가 통째로 사라졌다.
--
-- "이 사용자가 무엇을 아껴 보는가"는 UI 프리퍼런스(사이드바 폭 같은)가 아니라 사용자 데이터다.
CREATE TABLE page_star (
    user_id    BIGINT      NOT NULL,
    page_id    BIGINT      NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, page_id)
);
CREATE INDEX idx_page_star_user ON page_star (user_id, created_at DESC);

CREATE TABLE space_star (
    user_id    BIGINT      NOT NULL,
    space_id   BIGINT      NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, space_id)
);
CREATE INDEX idx_space_star_user ON space_star (user_id, created_at DESC);

-- 최근 방문은 사용자·페이지당 한 줄이고 다시 보면 시각만 갱신된다.
-- 방문 로그를 쌓지 않는 이유: 필요한 것은 "마지막으로 언제 봤나"뿐이고, 매 방문을 남기면
-- 활동 이력이 되어 보존 정책이 따라붙는다(지금 그 논의를 열 이유가 없다).
CREATE TABLE page_visit (
    user_id    BIGINT      NOT NULL,
    page_id    BIGINT      NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, page_id)
);
CREATE INDEX idx_page_visit_user ON page_visit (user_id, visited_at DESC);
