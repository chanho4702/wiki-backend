-- 블로그(W24) 타입을 page_type_check에 넣는다.
--
-- V2의 CHECK는 PAGE·FOLDER만 허용했고, 엔티티(PageType.BLOG)가 생긴 뒤 이 제약을 고친 마이그레이션이
-- 없었다. H2 테스트(create-drop)는 마이그레이션을 타지 않아 CI가 통과했고, 운영 PostgreSQL에서는
-- 블로그 글 저장이 전부 제약 위반(500)이었다(2026-10-16 확인, 2026-10-18 감사 W-02).
--
-- 기존 행은 PAGE/FOLDER뿐이라(BLOG는 들어갈 수 없었다) 백필이 필요 없다.
ALTER TABLE page DROP CONSTRAINT page_type_check;
ALTER TABLE page ADD CONSTRAINT page_type_check CHECK (type IN ('PAGE', 'FOLDER', 'BLOG'));
