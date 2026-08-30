package com.platform.wikibackend.notification.dto;

import com.platform.wikibackend.domain.NotificationPref;

/** emailConfigured가 false면 스위치를 켜도 아무것도 가지 않는다 — 화면이 그 사실을 먼저 말해야 한다. */
public record NotificationPrefResponse(boolean emailConfigured, String email, boolean emailEnabled,
                                       boolean mentioned, boolean pageUpdated, boolean comment, boolean shared) {
    public static NotificationPrefResponse from(NotificationPref p, boolean configured) {
        return new NotificationPrefResponse(configured, p.getEmail(), p.isEmailEnabled(),
                p.isOnMentioned(), p.isOnPageUpdated(), p.isOnComment(), p.isOnShared());
    }
}
