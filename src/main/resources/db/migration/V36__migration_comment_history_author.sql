-- 컨플루언스 DC 마이그레이션 M3 — 댓글·버전 이력·원본 작성자 표시.
--
-- 1) payload kind 두 가지가 는다.
--    COMMENTS: 원본 댓글을 우리 마크다운으로 눕힌 목록이다. 댓글은 페이지가 만들어진 뒤에야
--              쓸 수 있어(page_comment.page_id) EXTRACT와 RESOLVE 사이에 둘 자리가 필요하다.
--    HISTORY : 최신 N개 이전 버전의 본문이다. 리비전은 페이지가 있어야 매달 수 있고, 재시도가
--              남의 서버를 N번 더 긁지 않으려면 받아 둔 자리가 있어야 한다.
--
-- 2) target_comment_id: 이관한 댓글의 멱등 판정 자리다. target_page_id를 재활용하지 않는 이유는
--    그 컬럼이 page(id) FK이기 때문이다 — 댓글 id를 넣으면 제약이 거부한다. 링크 정리 pass는
--    target_page_id가 NULL인 행을 이미 건너뛰므로, 댓글 행은 그 순회에 섞이지 않는다.
--
-- 3) page.imported_*: 원본 작성자를 우리 계정으로 대조하지 못했을 때만 채운다(기획 P2).
--    대조된 문서는 NULL이고, 화면은 NULL이면 평소대로 우리 사용자 이름을 보여준다. created_by를
--    덮어쓰지 않는 이유는 그 컬럼이 "우리 쪽에서 누구 책임인가"이기 때문이다 — 이관 담당자가 맞다.

ALTER TABLE migration_payload DROP CONSTRAINT chk_migration_payload_kind;
ALTER TABLE migration_payload ADD CONSTRAINT chk_migration_payload_kind
    CHECK (kind IN ('SNAPSHOT', 'IR', 'MARKDOWN', 'MEDIA_MANIFEST', 'COMMENTS', 'HISTORY'));

ALTER TABLE migration_object_map
    ADD COLUMN target_comment_id BIGINT REFERENCES page_comment (id) ON DELETE SET NULL;

ALTER TABLE page ADD COLUMN imported_author_name VARCHAR(255);
ALTER TABLE page ADD COLUMN imported_source_url VARCHAR(1024);
