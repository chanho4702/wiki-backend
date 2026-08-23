package com.platform.wikibackend.notification.dto;

import com.platform.wikibackend.domain.Notification;

import java.time.Instant;

public record NotificationResponse(Long id, String type, Long pageId, Long spaceId, String pageTitle,
                                   Long actorId, Instant createdAt, boolean read) {
    /** spaceId는 프론트 라우팅용(/spaces/{s}/pages/{p}) — 페이지가 지워진 알림은 cascade로 함께 사라진다. */
    public static NotificationResponse from(Notification n, Long spaceId, String pageTitle) {
        return new NotificationResponse(n.getId(), n.getType().name(), n.getPageId(), spaceId, pageTitle,
                n.getActorId(), n.getCreatedAt(), n.isRead());
    }
}
