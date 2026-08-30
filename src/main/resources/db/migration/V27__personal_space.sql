-- W23 개인 스페이스.
--
-- 팀 스페이스에 넣기 애매한 메모·초안을 둘 곳이 없어서 사람들이 팀 스페이스 한구석에 "OO 작업
-- 메모" 폴더를 만들었다 — 남의 트리를 어지럽히고, 본인은 그것이 어디 있는지 매번 찾는다.
--
-- 별도 표가 아니라 space에 owner_id를 둔다: 개인 스페이스도 스페이스다(권한·트리·검색이 전부
-- 같다). 다른 점은 "누구의 것인가"뿐이고, 한 사람에 하나다.
ALTER TABLE space ADD COLUMN owner_id BIGINT;
CREATE UNIQUE INDEX uq_space_owner ON space (owner_id) WHERE owner_id IS NOT NULL;
