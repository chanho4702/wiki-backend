package com.platform.wikibackend.notification;

import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.notification.dto.NotificationListResponse;
import com.platform.wikibackend.notification.dto.NotificationResponse;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
@Slf4j
public class NotificationService {

    /** 본문 멘션 링크 [@이름](user:123)의 id 추출 — 프론트 저장 문법(userMention)과 계약. */
    private static final Pattern MENTION = Pattern.compile("\\]\\(user:(\\d+)\\)");
    private static final int PAGE_SIZE = 30;

    private final NotificationRepository notifications;
    private final PageRevisionRepository revisions;
    private final PageRepository pages;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;
    private final com.platform.wikibackend.watch.WatchService watches;
    private final com.platform.wikibackend.watch.SpaceWatchService spaceWatches;
    private final EmailNotifier email;

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
            deliver(userId, Notification.Type.MENTIONED, page, actorId);
        }

        Set<Long> interested = interestedIn(page, now);
        interested.remove(actorId);
        interested.removeAll(newlyMentioned); // 멘션 알림과 업데이트 알림을 겹쳐 보내지 않는다
        for (long userId : interested) {
            deliver(userId, Notification.Type.PAGE_UPDATED, page, actorId);
        }
    }

    /** 댓글 작성 후 호출 — 댓글 본문 멘션은 MENTIONED, 그 외 관심 사용자는 COMMENT. */
    public void onCommentAdded(long actorId, Page page, String commentBody) {
        Set<Long> mentioned = mentionIds(commentBody);
        mentioned.remove(actorId);
        for (long userId : mentioned) {
            deliver(userId, Notification.Type.MENTIONED, page, actorId);
        }

        Set<Long> interested = interestedIn(page, mentionIds(page.getContent()));
        interested.remove(actorId);
        interested.removeAll(mentioned);
        for (long userId : interested) {
            deliver(userId, Notification.Type.COMMENT, page, actorId);
        }
    }

    /**
     * 알림 대상 = 페이지 구독자(V15) ∪ 스페이스 구독자(V32) ∪ 본문에 멘션된 사용자.
     *
     * W21-4 이전에는 "작성자 + 리비전을 남긴 편집자"를 코드로 계산했다. 그 방식은 끌 수가 없어
     * (한 번 고친 문서의 알림을 영영 받는다) 구독 표로 옮겼다. 기존 사용자는 V15 백필로 승계된다.
     * 멘션은 구독과 무관하게 항상 받는다 — 나를 부른 것은 문서 구독 여부와 다른 사건이다.
     *
     * 스페이스 구독자(W27-4)는 합집합으로 더한다 — 두 원장에 다 있어도 Set이라 한 번만 간다.
     * 페이지 제한(V12)에 걸려 못 보는 사람은 deliver의 수신자별 VIEW 판정에서 빠진다.
     */
    private Set<Long> interestedIn(Page page, Set<Long> currentMentions) {
        Set<Long> users = subscribers(page);
        users.addAll(currentMentions);
        return users;
    }

    /** 구독자만 — 페이지 구독자 ∪ 스페이스 구독자. 멘션은 포함하지 않는다. */
    private Set<Long> subscribers(Page page) {
        Set<Long> users = new HashSet<>(watches.watcherIds(page.getId()));
        users.addAll(spaceWatches.watcherIds(page.getSpaceId()));
        return users;
    }

    /**
     * 새 문서 게시(W27-4) — 스페이스를 구독한 사람이 기다리던 사건이다. 초안이 게시로 넘어갈 때와
     * 처음부터 게시 상태로 만들어질 때 모두 한 번 온다(같은 문서에 두 번 가지 않는다 —
     * 게시는 멱등하고 호출부가 상태 전이에서만 부른다).
     *
     * 페이지 구독자도 함께 받는다. 방금 만들어진 문서의 구독자는 사실상 작성자뿐이고 그는
     * 행위자로 제외되지만, 복제·이동으로 이미 구독자가 붙은 초안이 게시되는 경우가 있다.
     *
     * 대상은 **구독자만**이다 — 본문에 멘션된 사람은 넣지 않는다. 문서 생성은 예전부터 멘션
     * 알림의 트리거가 아니었고(첫 저장의 멘션은 MENTIONED를 만들지 않는다), 여기서 게시로
     * 대신 보내면 그 결정을 뒷문으로 뒤집는 셈이 된다.
     */
    public void onPagePublished(long actorId, Page page) {
        Set<Long> interested = subscribers(page);
        interested.remove(actorId); // 자기가 게시한 문서를 자기 알림함에서 다시 볼 이유가 없다
        for (long userId : interested) {
            deliver(userId, Notification.Type.PAGE_PUBLISHED, page, actorId);
        }
    }

    /**
     * 페이지 공유(W23) — 수신자마다 SHARED 알림 한 건. 합치지 않는다: 두 사람이 같은 문서를
     * 각자 다른 메모로 보냈으면 둘 다 의미 있다(MENTIONED와 같은 규칙).
     *
     * 볼 수 없는 수신자는 조용히 건너뛴다(deliver의 fail-closed). 공유했다고 권한이 생기지는
     * 않는다 — 권한은 org-service 원장이고, 공유는 알림이다. 돌려주는 값은 실제로 전달된 수다.
     */
    public int share(long actorId, Page page, Collection<Long> recipientIds, String note) {
        if (note != null && note.length() > Notification.MAX_NOTE) {
            throw new IllegalArgumentException("메모는 " + Notification.MAX_NOTE + "자를 넘을 수 없습니다");
        }
        int delivered = 0;
        for (Long userId : new LinkedHashSet<>(recipientIds)) {
            if (userId == null || userId == actorId) continue; // 자신에게 보내는 공유는 의미가 없다
            if (deliverShared(userId, page, actorId, note)) delivered++;
        }
        return delivered;
    }

    private boolean deliverShared(long userId, Page page, long actorId, String note) {
        try {
            if (!isVisible(userId, page)) return false;
        } catch (ServiceUnavailableException e) {
            log.warn("공유 수신 권한 확인 불가 — 발송 생략: user={} page={}", userId, page.getId());
            return false;
        }
        Notification saved = notifications.save(Notification.of(userId, Notification.Type.SHARED, page.getId(), actorId, note));
        email.notify(saved, page, note);
        return true;
    }

    private void deliver(long userId, Notification.Type type, Page page, long actorId) {
        // 제한 변경 직후의 알림도 제목·경로를 노출하므로 저장 전에 수신자별 space+page VIEW를 확인한다.
        // org가 일시 불능이면 본문 업데이트까지 롤백하지 않고 그 알림만 fail-closed로 건너뛴다.
        try {
            if (!isVisible(userId, page)) return;
        } catch (ServiceUnavailableException e) {
            log.warn("알림 수신 권한 확인 불가 — 발송 생략: user={} page={}", userId, page.getId());
            return;
        }
        long pageId = page.getId();
        if (type != Notification.Type.MENTIONED) {
            var unread = notifications.findFirstByUserIdAndPageIdAndTypeAndReadAtIsNull(userId, pageId, type);
            if (unread.isPresent()) {
                unread.get().refresh(actorId, Instant.now());
                return;
            }
        }
        Notification saved = notifications.save(Notification.of(userId, type, pageId, actorId));
        email.notify(saved, page, null);
    }

    /* ── 조회·읽음 처리 ─────────────────────────────────── */

    @Transactional(readOnly = true)
    public NotificationListResponse list(long userId) {
        List<Notification> rows = notifications.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, PAGE_SIZE));
        List<Notification> unreadRows = notifications.findByUserIdAndReadAtIsNull(userId);
        // 페이지 제목·스페이스는 표시/라우팅용 — 휴지통에 있는 페이지는 findAllById가 걸러(@SQLRestriction)
        // page == null이 되고 아래 visible 필터에서 빠진다. 영구 삭제 시엔 FK cascade로 행 자체가 사라진다.
        Set<Long> pageIds = new HashSet<>();
        rows.forEach(n -> pageIds.add(n.getPageId()));
        unreadRows.forEach(n -> pageIds.add(n.getPageId()));
        Map<Long, Page> byId = pages.findAllById(pageIds)
                .stream().collect(Collectors.toMap(Page::getId, p -> p, (a, b) -> a));
        // 제한 인덱스와 space 권한은 각각 스페이스당 한 번만 계산한다. 알림 수만큼 전체
        // 페이지 제한 인덱스를 다시 만드는 N+1을 피하면서 제목·경로 노출은 동일하게 닫는다.
        Set<Long> effectiveVisible = effective.viewablePageIds(userId, byId.values());
        Map<Long, Boolean> visibleSpaces = byId.values().stream()
                .map(Page::getSpaceId)
                .distinct()
                .collect(Collectors.toMap(
                        spaceId -> spaceId,
                        spaceId -> permissions.isAllowed(userId, spaceId, WikiAction.VIEW)));
        java.util.function.Predicate<Notification> visible = n -> {
            Page page = byId.get(n.getPageId());
            return page != null
                    && visibleSpaces.getOrDefault(page.getSpaceId(), false)
                    && effectiveVisible.contains(page.getId());
        };
        List<NotificationResponse> items = rows.stream()
                .filter(visible)
                .map(n -> {
                    Page page = byId.get(n.getPageId());
                    return NotificationResponse.from(n,
                            page == null ? null : page.getSpaceId(),
                            page == null ? "" : page.getTitle());
                })
                .toList();
        long unreadCount = unreadRows.stream()
                .filter(visible)
                .count();
        return new NotificationListResponse(unreadCount, items);
    }

    private boolean isVisible(long userId, Page page) {
        return permissions.isAllowed(userId, page.getSpaceId(), WikiAction.VIEW)
                && effective.canView(userId, page);
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
