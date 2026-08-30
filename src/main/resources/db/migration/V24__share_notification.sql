-- W23 페이지 공유.
--
-- "이 문서 봐주세요"를 전할 방법이 없었다. 링크를 복사해 메신저에 붙이거나, 본문에 멘션을 억지로
-- 넣어야 했다 — 후자는 문서를 더럽힌다.
--
-- 공유는 알림의 한 종류(SHARED)다. 별도 표를 두지 않는 이유: 수신자 입장에서는 "누가 나를
-- 이 문서로 불렀다"이고, 그것이 알림함이 하는 일이다. 메모는 공유에만 있어 nullable이다.
ALTER TABLE notification DROP CONSTRAINT chk_notification_type;
ALTER TABLE notification ADD CONSTRAINT chk_notification_type
    CHECK (type IN ('MENTIONED', 'PAGE_UPDATED', 'COMMENT', 'SHARED'));
ALTER TABLE notification ADD COLUMN note VARCHAR(300);
