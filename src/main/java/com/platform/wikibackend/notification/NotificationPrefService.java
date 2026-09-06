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
 * 스냅샷 주소는 요청 토큰(email 클레임)에서 온다. 발송 시점(다른 사용자의 저장 트랜잭션 안)에는
 * 수신자의 토큰이 없기 때문이다. <b>정본은 org-service 디렉터리이고 이 값은 폴백이다</b> —
 * {@link EmailNotifier}가 커밋 뒤 `GetMembers`로 주소를 읽고, 못 읽었을 때만 여기 남은 값을 쓴다.
 * org에서 이메일을 바꾸면 이 스냅샷은 다음 방문까지 낡은 채로 남는다.
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

    /**
     * 이 타입의 메일을 **바로** 받을 사람인가, 그리고 알던 주소는 무엇인가.
     *
     * <p>스냅샷이 없어도 empty가 아니다 — 주소는 발송 직전 디렉터리에서 읽으므로, 여기서 없다고
     * 끊으면 org가 아는 주소로도 못 보낸다. 설정 행 자체가 없으면 empty다: 한 번도 다녀가지 않은
     * 사람에게는 채널을 켜 준 적이 없다.
     */
    @Transactional(readOnly = true)
    public Optional<MailTarget> immediateTarget(long userId, Notification.Type type) {
        return prefs.findById(userId)
                .filter(p -> p.getEmailMode() == NotificationPref.EmailMode.IMMEDIATE && p.wants(type))
                .map(p -> new MailTarget(p.getEmail()));
    }

    /**
     * 하루 한 번 요약을 받는 사람들 — 채널이 켜진 행 전부다. 스냅샷 주소가 없는 행도 포함한다:
     * 주소는 발송 직전 디렉터리에서 읽고, 거기서도 못 찾으면 그때 건너뛴다.
     */
    @Transactional(readOnly = true)
    public List<NotificationPref> dailyRecipients() {
        return prefs.findByEmailModeAndEmailEnabledTrue(NotificationPref.EmailMode.DAILY);
    }

    /** 발송 대상 — 주소는 정본(디렉터리)이 답을 못 줄 때 쓸 스냅샷이고, 없으면 null이다. */
    public record MailTarget(String snapshotEmail) {
    }

    private NotificationPref ensure(long userId, String jwtEmail) {
        return prefs.findById(userId)
                .map(p -> { p.rememberEmail(jwtEmail); return p; })
                .orElseGet(() -> prefs.save(NotificationPref.defaultsFor(userId, jwtEmail)));
    }
}
