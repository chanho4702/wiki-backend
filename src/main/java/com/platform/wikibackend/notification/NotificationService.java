package com.platform.wikibackend.notification;

import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.notification.dto.NotificationListResponse;
import com.platform.wikibackend.notification.dto.NotificationResponse;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 알림 규칙(2026-08-23, 사용자 스펙 + 재량):
 * - 트리거: ①본문에 새로 멘션됨(MENTIONED) ②내가 만든/수정한/멘션된 페이지의 업데이트
 *   (PAGE_UPDATED) ③그 페이지들에 달린 댓글(COMMENT, 댓글 멘션은 MENTIONED 우선).
 * - 행위자 자신에게는 보내지 않는다(자기 저장·자기 댓글 소음 방지 — 재량 결정).
 * - 같은 (수신자, 페이지, 타입) 미읽음이 있으면 새 행 대신 시각·행위자만 당긴다(폭주 합침).
 *   단 MENTIONED는 합치지 않는다 — 누가 언제 멘션했는지가 각각 의미 있다.
 * - 페이지 저장 트랜잭션 안에서 동기 기록한다 — M 규모에서 큐 분리는 과잉(규모 검토와 정합).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    /** 본문 멘션 링크 [@이름](user:123)의 id 추출 — 프론트 저장 문법(userMention)과 계약. */
    private static final Pattern MENTION = Pattern.compile("\\]\\(user:(\\d+)\\)");
    private static final int PAGE_SIZE = 30;

    private final NotificationRepository notifications;
    private final PageRevisionRepository revisions;
    private final PageRepository pages;

    static Set<Long> mentionIds(String body) {
        Set<Long> ids = new HashSet<>();
        if (body == null) return ids;
        Matcher m = MENTION.matcher(body);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    /** 페이지 내용 업데이트 후 호출 — oldBody는 수정 전 본문(새 멘션 판별용). */
    public void onPageUpdated(long actorId, Page page, String oldBody, String newBody) {
        Set<Long> before = mentionIds(oldBody);
        Set<Long> now = mentionIds(newBody);

        Set<Long> newlyMentioned = new HashSet<>(now);
        newlyMentioned.removeAll(before);
        newlyMentioned.remove(actorId);
        for (long userId : newlyMentioned) {
            deliver(userId, Notification.Type.MENTIONED, page.getId(), actorId);
        }

        Set<Long> interested = interestedIn(page, now);
        interested.remove(actorId);
        interested.removeAll(newlyMentioned); // 멘션 알림과 업데이트 알림을 겹쳐 보내지 않는다
        for (long userId : interested) {
            deliver(userId, Notification.Type.PAGE_UPDATED, page.getId(), actorId);
        }
    }

    /** 댓글 작성 후 호출 — 댓글 본문 멘션은 MENTIONED, 그 외 관심 사용자는 COMMENT. */
    public void onCommentAdded(long actorId, Page page, String commentBody) {
        Set<Long> mentioned = mentionIds(commentBody);
        mentioned.remove(actorId);
        for (long userId : mentioned) {
            deliver(userId, Notification.Type.MENTIONED, page.getId(), actorId);
        }

        Set<Long> interested = interestedIn(page, mentionIds(page.getContent()));
        interested.remove(actorId);
        interested.removeAll(mentioned);
        for (long userId : interested) {
            deliver(userId, Notification.Type.COMMENT, page.getId(), actorId);
        }
    }

    /** 관심 사용자 = 페이지 작성자 + 리비전을 남긴 편집자들 + 본문에 멘션된 사용자들. */
    private Set<Long> interestedIn(Page page, Set<Long> currentMentions) {
        Set<Long> users = new HashSet<>(currentMentions);
        users.add(page.getCreatedBy());
        users.addAll(revisions.findDistinctEditors(page.getId()));
        return users;
    }

    private void deliver(long userId, Notification.Type type, long pageId, long actorId) {
        if (type != Notification.Type.MENTIONED) {
            var unread = notifications.findFirstByUserIdAndPageIdAndTypeAndReadAtIsNull(userId, pageId, type);
            if (unread.isPresent()) {
                unread.get().refresh(actorId, Instant.now());
                return;
            }
        }
        notifications.save(Notification.of(userId, type, pageId, actorId));
    }

    /* ── 조회·읽음 처리 ─────────────────────────────────── */

    @Transactional(readOnly = true)
    public NotificationListResponse list(long userId) {
        List<Notification> rows = notifications.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, PAGE_SIZE));
        // 페이지 제목은 표시용 — 삭제된 페이지의 알림은 FK cascade로 함께 사라지므로 대부분 존재한다
        Map<Long, String> titles = pages.findAllById(rows.stream().map(Notification::getPageId).distinct().toList())
                .stream().collect(Collectors.toMap(Page::getId, Page::getTitle, (a, b) -> a));
        List<NotificationResponse> items = rows.stream()
                .map(n -> NotificationResponse.from(n, titles.getOrDefault(n.getPageId(), "")))
                .toList();
        return new NotificationListResponse(notifications.countByUserIdAndReadAtIsNull(userId), items);
    }

    /** ids가 비면 전체 읽음 — 본인 소유 행만 만진다. */
    public void markRead(long userId, List<Long> ids) {
        Instant now = Instant.now();
        List<Notification> targets = (ids == null || ids.isEmpty())
                ? notifications.findByUserIdAndReadAtIsNull(userId)
                : notifications.findByIdInAndUserId(ids, userId);
        targets.forEach(n -> n.markRead(now));
    }
}
