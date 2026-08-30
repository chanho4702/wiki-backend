-- 이메일 알림 "하루 한 번 요약" 모드.
--
-- 건당 발송은 멘션이 잦은 사람에게 메일 폭주다. 사용자가 IMMEDIATE(바로) / DAILY(하루 한 번 모아서)를
-- 고른다. 요약은 "아직 메일로 나가지 않은 알림"을 모으므로 알림 행에 발송 시각을 남긴다 —
-- 바로 보낸 것도 찍어 두어야 모드를 바꿨을 때 옛 알림이 다시 나가지 않는다.
ALTER TABLE notification_pref ADD COLUMN email_mode VARCHAR(16) NOT NULL DEFAULT 'IMMEDIATE';
ALTER TABLE notification ADD COLUMN emailed_at TIMESTAMPTZ;
CREATE INDEX idx_notification_unmailed ON notification (user_id, created_at) WHERE emailed_at IS NULL;
