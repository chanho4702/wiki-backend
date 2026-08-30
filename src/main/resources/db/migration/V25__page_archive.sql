-- W23 페이지 보관(archive).
--
-- 휴지통은 "지웠다"이고 보관은 "끝났지만 남겨 둔다"다. 지난 분기 회고·폐기된 설계처럼 더는
-- 트리에 있을 이유가 없지만 링크로는 계속 읽혀야 하는 문서가 트리를 채우고 있었다 — 그래서
-- 트리는 갈수록 읽기 어려워지고, 지우자니 링크가 끊긴다.
--
-- 휴지통(V13)과 같은 모양이다: 루트 표시 + 하위 cascade. 다른 점은 @SQLRestriction에 걸지
-- 않는다는 것 — 보관된 문서는 직접 링크로 열려야 하므로 조회에서 빠지면 안 되고, 트리·검색에서만
-- 빠진다.
ALTER TABLE page ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE page ADD COLUMN archived_by BIGINT;
ALTER TABLE page ADD COLUMN archived_root BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE page ADD CONSTRAINT chk_page_archived
    CHECK ((archived_at IS NULL) = (archived_by IS NULL));
CREATE INDEX idx_page_archived ON page (space_id) WHERE archived_at IS NOT NULL;
