package com.platform.wikibackend.notification;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.NotificationPref;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 하루 한 번 요약 메일(V31) — DAILY 모드 사용자마다 "아직 메일로 나가지 않은" 알림을 한 통에 모은다.
 *
 * 오래된 알림까지 끌어오지 않는다(7일 상한): 요약 모드를 켠 지 한참 된 사람의 첫 요약이 몇 달치가
 * 되면 아무도 읽지 않는다. 발송 시각은 같은 트랜잭션에서 찍고 메일은 커밋 뒤에 나간다 —
 * 발송이 실패해도 같은 알림이 매일 다시 나가지는 않는다(사본이라서, 알림함이 원본이다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDigestService {

    static final Duration LOOKBACK = Duration.ofDays(7);

    private final NotificationPrefService prefs;
    private final NotificationRepository notifications;
    private final PageRepository pages;
    private final EmailNotifier email;
    private final NotificationVisibility visibility;

    /** 보낸 요약 통 수. */
    @Transactional
    public int run() {
        if (!email.enabled()) return 0;
        Instant since = Instant.now().minus(LOOKBACK);
        int sent = 0;
        for (NotificationPref pref : prefs.dailyRecipients()) {
            List<Notification> rows = notifications
                    .findByUserIdAndEmailedAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(pref.getUserId(), since)
                    .stream().filter(n -> pref.wants(n.getType())).toList();
            if (rows.isEmpty()) continue;
            Set<Long> pageIds = rows.stream().map(Notification::getPageId).collect(Collectors.toCollection(HashSet::new));
            Map<Long, Page> byId = pages.findAllById(pageIds).stream()
                    .collect(Collectors.toMap(Page::getId, Function.identity(), (a, b) -> a));
            // 발송 시점 권한으로 다시 거른다 — 알림이 쌓인 뒤 걸린 제한·회수된 스페이스 권한의
            // 제목이 메일로 새지 않게(알림함과 같은 판정, 감사 W-04).
            Set<Long> visible;
            try {
                visible = visibility.visiblePageIds(pref.getUserId(), byId.values());
            } catch (ServiceUnavailableException e) {
                // 판정할 수 없으면 이번 회차는 건너뛴다. 처리됨으로 찍지 않아 다음 회차에 다시 모인다.
                log.warn("요약 메일 권한 확인 불가 — 이번 회차 생략: user={}", pref.getUserId());
                continue;
            }
            List<EmailNotifier.DigestLine> lines = new ArrayList<>();
            Instant now = Instant.now();
            for (Notification n : rows) {
                n.markEmailed(now); // 페이지가 사라진 알림도 "처리됨"으로 — 매일 다시 모이지 않게
                Page page = byId.get(n.getPageId());
                if (page == null || page.isArchived() || !visible.contains(page.getId())) continue;
                lines.add(new EmailNotifier.DigestLine(EmailNotifier.describe(n.getType()), page.getTitle(),
                        page.getSpaceId(), page.getId(), n.getNote()));
            }
            if (lines.isEmpty()) continue;
            email.notifyDigest(pref.getUserId(), pref.getEmail(), lines);
            sent++;
        }
        if (sent > 0) log.info("알림 요약 메일 발송: {}통", sent);
        return sent;
    }
}
