package com.platform.wikibackend.notification;

import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.NotificationPref;
import com.platform.wikibackend.notification.dto.NotificationPrefResponse;
import com.platform.wikibackend.notification.dto.NotificationPrefUpdate;
import com.platform.wikibackend.repository.NotificationPrefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 알림 설정(W23) — 사용자별 이메일 채널 스위치와 주소 스냅샷.
 *
 * 주소는 org 디렉터리가 아니라 요청 토큰(email 클레임)에서 온다. 발송 시점(다른 사용자의 저장
 * 트랜잭션 안)에는 수신자의 토큰이 없으므로, 수신자가 마지막으로 다녀갔을 때 본 주소를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationPrefService {

    private final NotificationPrefRepository prefs;
    private final EmailNotifier email;

    /** 설정 화면 — 없으면 기본값을 만들어 돌려준다(주소를 이때 처음 알게 된다). */
    public NotificationPrefResponse view(long userId, String jwtEmail) {
        return NotificationPrefResponse.from(ensure(userId, jwtEmail), email.configured());
    }

    public NotificationPrefResponse update(long userId, String jwtEmail, NotificationPrefUpdate req) {
        NotificationPref pref = ensure(userId, jwtEmail);
        pref.update(req.emailEnabled(), req.emailMode(), req.mentioned(), req.pageUpdated(), req.comment(), req.shared());
        return NotificationPrefResponse.from(pref, email.configured());
    }

    /**
     * 알림함을 열 때마다 주소를 갱신한다 — 설정 화면을 한 번도 열지 않은 사용자도 메일을 받아야
     * 채널이 "기본 켜짐"이라는 말이 성립한다. 바뀐 것이 없으면 쓰지 않는다.
     */
    public void remember(long userId, String jwtEmail) {
        if (jwtEmail == null || jwtEmail.isBlank()) return;
        Optional<NotificationPref> existing = prefs.findById(userId);
        if (existing.isEmpty()) {
            prefs.save(NotificationPref.defaultsFor(userId, jwtEmail));
        } else {
            existing.get().rememberEmail(jwtEmail);
        }
    }

    /** 이 타입의 메일을 **바로** 받을 주소 — 요약 모드·원하지 않음·주소 모름이면 empty. */
    @Transactional(readOnly = true)
    public Optional<String> immediateEmailFor(long userId, Notification.Type type) {
        return prefs.findById(userId)
                .filter(p -> p.getEmailMode() == NotificationPref.EmailMode.IMMEDIATE
                        && p.wants(type) && p.getEmail() != null)
                .map(NotificationPref::getEmail);
    }

    /** 하루 한 번 요약을 받는 사람들 — 채널이 켜져 있고 주소를 아는 경우만. */
    @Transactional(readOnly = true)
    public List<NotificationPref> dailyRecipients() {
        return prefs.findByEmailModeAndEmailEnabledTrueAndEmailIsNotNull(NotificationPref.EmailMode.DAILY);
    }

    private NotificationPref ensure(long userId, String jwtEmail) {
        return prefs.findById(userId)
                .map(p -> { p.rememberEmail(jwtEmail); return p; })
                .orElseGet(() -> prefs.save(NotificationPref.defaultsFor(userId, jwtEmail)));
    }
}
