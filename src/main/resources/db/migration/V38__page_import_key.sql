-- 이관 멱등 키 — 같은 원본 객체가 두 번 들어오는 것을 DB에서 막는 최종 방어선(W30).
--
-- 멱등의 1차 방어는 엔진 쪽 object map이다(원본 id → 위키 page id). 그런데 그 원장은 엔진
-- 프로세스 안에서만 유효하다: 잡을 두 번 돌리거나, 재시도가 응답을 못 받고 다시 쏘거나, 두
-- 워커가 같은 항목을 집으면 위키는 "새 문서를 만들어 달라"는 요청을 두 번 받는다. 그때 위키가
-- 판단할 근거가 없으면 같은 원본이 문서 두 벌로 남고, 사람이 손으로 지우기 전까지 검색·트리·
-- 백링크가 전부 두 벌이 된다. 되돌리기 가장 비싼 종류의 사고다.
--
-- 키는 엔진이 정하는 불투명한 문자열이다(예: `confluence-dc:{instanceId}:{objectId}`). 위키는
-- 형식을 해석하지 않는다 — 해석하면 provider가 늘 때마다 위키가 따라 바뀐다.
ALTER TABLE page ADD COLUMN import_key VARCHAR(200);

-- 부분 유니크 인덱스인 이유 두 가지.
--
-- 1) `import_key IS NOT NULL`: 사람이 만든 문서는 키가 없고, 그런 행이 수십만 개다. NULL을
--    인덱스에 넣지 않으면 인덱스가 이관 문서만큼만 커진다(V27 uq_space_owner와 같은 방식).
--
-- 2) `deleted_at IS NULL`: 휴지통 행은 유니크 판정에서 뺀다. Page 엔티티에 걸린
--    @SQLRestriction("deleted_at is null") 때문에 조회 경로는 버려진 문서를 **못 본다** —
--    인덱스가 그 행까지 잡으면 서비스는 "같은 키 없음"으로 보고 INSERT를 시도하는데 DB가
--    거부하는, 코드로는 빠져나갈 수 없는 상태가 된다. 버린 문서를 다시 이관해 되살리는 것이
--    맞는 동작이기도 하다.
CREATE UNIQUE INDEX uq_page_import_key ON page (import_key)
    WHERE import_key IS NOT NULL AND deleted_at IS NULL;
