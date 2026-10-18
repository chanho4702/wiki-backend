-- "새 문서 게시" 메일 수신 설정을 "문서 업데이트"에서 분리한다(F-05, 2026-10-18).
-- 지금까지 PAGE_PUBLISHED는 on_page_updated를 함께 썼다 — 기존 값을 그대로 이어받아 아무의 설정도 바뀌지 않는다.
ALTER TABLE notification_pref ADD COLUMN on_page_published BOOLEAN;
UPDATE notification_pref SET on_page_published = on_page_updated;
ALTER TABLE notification_pref ALTER COLUMN on_page_published SET NOT NULL;
ALTER TABLE notification_pref ALTER COLUMN on_page_published SET DEFAULT TRUE;
