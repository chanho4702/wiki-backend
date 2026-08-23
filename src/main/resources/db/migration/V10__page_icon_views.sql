-- V10: 페이지 이모지 아이콘 + 조회수 (프론트 계약: wiki-front docs/backend §4 setPageIcon/recordPageView)
-- icon: 노션/컨플식 제목·트리 이모지. NULL = 기본 문서 아이콘. 이모지 1개(서로게이트+ZWJ 조합 여유분 16자).
ALTER TABLE page
    ADD COLUMN icon VARCHAR(16);

-- view_count: 누적 조회수 — POST /views가 원자 UPDATE로 증가시킨다(엔티티 dirty-check 증가는
-- 동시 조회에서 lost update가 나므로 쓰지 않는다).
ALTER TABLE page
    ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
