package com.platform.wikibackend.notification.dto;

import com.platform.wikibackend.domain.Notification;

import java.time.Instant;

public record NotificationResponse(Long id, String type, Long pageId, String pageTitle,
                                   Long actorId, Instant createdAt, boolean read) {
    public static NotificationResponse from(Notification n, String pageTitle) {
        return new NotificationResponse(n.getId(), n.getType().name(), n.getPageId(), pageTitle,
                n.getActorId(), n.getCreatedAt(), n.isRead());
    }
}
