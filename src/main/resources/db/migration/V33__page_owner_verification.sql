-- W27-5 페이지 소유자·검증.
--
-- "이 문서 아직 맞는 얘기인가"를 확인할 방법이 문서 안에 없었다. 마지막 수정일은 답이 되지
-- 못한다 — 오타 하나 고쳐도 날짜가 새로 찍히기 때문이다. 컨플루언스·노션이 쓰는 방식은
-- **사람이 직접 누르는 검증**이고, 그 판단에 유효기간을 붙인다.
--
-- owner_id는 기본값을 두지 않는다. created_by를 복사해 넣으면 "아무도 정하지 않았다"와
-- "만든 사람이 책임자다"가 구분되지 않는데, 실제로는 만든 사람이 이미 팀을 떠난 문서가 많다.
-- 소유자는 명시적으로 정하는 것이고, 권한과는 무관하다(권한은 V12 제한이 담당).
--
-- 만료 판정은 저장하지 않는다. verified_until이 지났는지는 읽는 시점에 계산되며, 만료되어도
-- 문서가 숨거나 잠기지 않는다 — 배지 문구만 바뀐다.
ALTER TABLE page
    ADD COLUMN owner_id       BIGINT,
    ADD COLUMN verified_at    TIMESTAMPTZ,
    ADD COLUMN verified_by    BIGINT,
    ADD COLUMN verified_until TIMESTAMPTZ;

-- "소유자가 나인 문서" 목록은 아직 화면이 없지만, 부분 인덱스는 값이 있는 소수 행만 담아 싸다.
CREATE INDEX idx_page_owner ON page (owner_id) WHERE owner_id IS NOT NULL;
