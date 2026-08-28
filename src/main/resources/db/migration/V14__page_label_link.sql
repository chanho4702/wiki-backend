-- W21-2 라벨과 백링크.
--
-- 라벨: 컨플루언스 라벨과 같은 개념. 이름은 정규화(trim + 소문자)해 저장한다 —
-- "Design"과 "design"이 다른 라벨이 되면 목록이 금세 쓸모없어진다.
CREATE TABLE page_label (
    id         BIGSERIAL   PRIMARY KEY,
    page_id    BIGINT      NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    name       VARCHAR(64) NOT NULL,
    created_by BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_page_label UNIQUE (page_id, name)
);
CREATE INDEX idx_page_label_name ON page_label (name);

-- 백링크: 본문의 `[[제목]]` 내부 링크를 역방향으로 저장한다.
--
-- 왜 target_page_id가 아니라 target_title인가: 이 위키의 내부 링크는 **제목으로** 해석된다
-- (wiki-front resolveWikiLinks — 같은 스페이스에서 제목 정확 일치). id로 굳혀두면 대상 페이지를
-- 개명했을 때 저장된 그래프와 화면에 보이는 링크가 어긋난다. 저장 형식이 제목이므로 그래프도
-- 제목으로 두고 조회 시점에 맞춘다.
CREATE TABLE page_link (
    id             BIGSERIAL    PRIMARY KEY,
    source_page_id BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    space_id       BIGINT       NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    target_title   VARCHAR(255) NOT NULL,  -- 정규화(trim + 소문자)
    CONSTRAINT uq_page_link UNIQUE (source_page_id, target_title)
);
CREATE INDEX idx_page_link_target ON page_link (space_id, target_title);
