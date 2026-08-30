-- W23 알림 설정 — 이메일 채널.
--
-- 알림은 벨 아이콘 안에만 있었다. 위키를 열어 두지 않은 사람은 멘션을 몇 시간 뒤에야 봤다.
-- 사용자별로 "어떤 알림을 이메일로도 받을지"를 남긴다. 주소는 토큰(email 클레임)에서 온 것을
-- 마지막으로 본 값으로 스냅샷한다 — org 디렉터리를 매 발송마다 부르지 않기 위해서다.
--
-- 행이 없는 사용자 = 기본값(모두 켜짐)이지만 주소를 모르므로 실제 발송은 없다. 주소는 알림함을
-- 열거나 설정을 저장할 때 채워진다.
CREATE TABLE notification_pref (
    user_id         BIGINT       PRIMARY KEY,
    email           VARCHAR(255),
    email_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    on_mentioned    BOOLEAN      NOT NULL DEFAULT TRUE,
    on_page_updated BOOLEAN      NOT NULL DEFAULT TRUE,
    on_comment      BOOLEAN      NOT NULL DEFAULT TRUE,
    on_shared       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
