-- W23 리비전 편집자 이름 스냅샷.
--
-- 리비전은 편집자 id만 들고 있어 화면이 org 디렉터리에서 이름을 찾았다. 퇴사·비활성 사용자는
-- 디렉터리(ACTIVE만)에 없어 "사용자 #12"로 남았다 — 6개월 전 누가 고쳤는지가 정작 필요할 때
-- 숫자만 보인다. 댓글(author_name)과 같은 규칙으로 저장 시점 이름을 함께 남긴다.
ALTER TABLE page_revision ADD COLUMN edited_by_name VARCHAR(120);
