package com.platform.wikibackend.notification;

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

    /** 보낸 요약 통 수. */
    @Transactional
    public int run() {
        if (!email.configured()) return 0;
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
            List<EmailNotifier.DigestLine> lines = new ArrayList<>();
            Instant now = Instant.now();
            for (Notification n : rows) {
                n.markEmailed(now); // 페이지가 사라진 알림도 "처리됨"으로 — 매일 다시 모이지 않게
                Page page = byId.get(n.getPageId());
                if (page == null || page.isArchived()) continue;
                lines.add(new EmailNotifier.DigestLine(EmailNotifier.describe(n.getType()), page.getTitle(),
                        page.getSpaceId(), page.getId(), n.getNote()));
            }
            if (lines.isEmpty()) continue;
            email.sendAfterCommit(email.composeDigest(pref.getEmail(), lines));
            sent++;
        }
        if (sent > 0) log.info("알림 요약 메일 발송: {}통", sent);
        return sent;
    }
}
