package com.platform.wikibackend.notification.dto;

import java.util.List;

/**
 * nextBefore(F-05) — 다음 쪽 커서(이번 쪽 마지막 알림 id). 더 없으면 null.
 * 벨(파라미터 없는 호출)에도 실린다 — 기존 두 필드는 그대로다.
 */
public record NotificationListResponse(long unreadCount, List<NotificationResponse> items, Long nextBefore) {

    public NotificationListResponse(long unreadCount, List<NotificationResponse> items) {
        this(unreadCount, items, null);
    }
}
