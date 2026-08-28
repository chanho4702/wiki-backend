-- W21-1 휴지통 — 페이지 삭제를 소프트 삭제로 바꾼다(V1 주석의 "휴지통은 후속 백로그" 해소).
-- 지금까지 DELETE는 본문·리비전·댓글·첨부 객체를 즉시 지웠고 되돌릴 방법이 없었다.
--
-- deleted_root: 사용자가 직접 버린 페이지만 true. cascade로 함께 딸려간 자손은 false다.
-- 휴지통 목록은 root만 행으로 보여주고, 복원은 root + (root가 아닌) 자손을 한 묶음으로 되살린다.
-- 이래야 하위를 먼저 따로 버려둔 묶음이 상위 복원에 휩쓸려 되살아나지 않는다.
ALTER TABLE page
    ADD COLUMN deleted_at   TIMESTAMPTZ,
    ADD COLUMN deleted_by   BIGINT,
    ADD COLUMN deleted_root BOOLEAN NOT NULL DEFAULT FALSE;

-- 살아 있는 페이지 조회가 압도적 다수라 부분 인덱스로 휴지통 행을 본 인덱스에서 뺀다.
CREATE INDEX idx_page_trash ON page (space_id, deleted_at) WHERE deleted_at IS NOT NULL;

-- 버려진 상태인데 언제·누가 버렸는지 모르는 행은 보존 기간 계산이 불가능하다 — DB에서 막는다.
ALTER TABLE page
    ADD CONSTRAINT chk_page_deleted_pair
        CHECK ((deleted_at IS NULL AND deleted_by IS NULL)
            OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL));
