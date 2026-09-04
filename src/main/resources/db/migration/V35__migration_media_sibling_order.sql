-- 컨플루언스 DC 마이그레이션 M2 — 첨부 스테이징 목록과 원본 형제 순서.
--
-- 1) MEDIA_MANIFEST: MEDIA_COPY가 받아 둔 첨부 바이트의 좌표(파일명·크기·checksum·저장 키)를 담는
--    JSON이다. RESOLVE가 페이지를 만든 뒤 이 목록으로 첨부 레코드를 만든다. 단계 사이에 파일을
--    한 번 더 옮기지 않으려면(재업로드 금지) 어딘가에 "이미 받아 뒀다"를 적어야 하고, 그 자리가
--    payload다 — 재실행이 같은 파일을 다시 내려받지 않는 근거도 여기 있다.
--
-- 2) sibling_order: 같은 부모 아래에서 원본이 정한 순서다. DC의 목록 API(`/content?spaceKey=`)는
--    형제 순서를 알려주지 않아 M1은 id 오름차순으로 세웠다. 발견이 부모마다 `child/page`를 한 번
--    더 부르면 원본 순서를 알 수 있고, 그 값을 여기 적어 두면 다시 이관해도 순서가 유지된다.
--    NULL은 "원본 순서를 모른다"이고, 그때는 M1 규칙(발견 순서)을 그대로 쓴다.

ALTER TABLE migration_payload DROP CONSTRAINT chk_migration_payload_kind;
ALTER TABLE migration_payload ADD CONSTRAINT chk_migration_payload_kind
    CHECK (kind IN ('SNAPSHOT', 'IR', 'MARKDOWN', 'MEDIA_MANIFEST'));

ALTER TABLE migration_item ADD COLUMN sibling_order INTEGER;
