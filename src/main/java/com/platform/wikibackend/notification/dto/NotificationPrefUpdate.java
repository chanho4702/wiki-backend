package com.platform.wikibackend.notification.dto;

import com.platform.wikibackend.domain.NotificationPref;

/**
 * emailMode는 선택 — 없으면 IMMEDIATE(이 필드 도입 이전 클라이언트 호환).
 * pagePublished는 선택(V43) — 없으면 pageUpdated를 따른다(분리 이전 클라이언트의 의미 그대로).
 */
public record NotificationPrefUpdate(boolean emailEnabled, NotificationPref.EmailMode emailMode,
                                     boolean mentioned, boolean pageUpdated, boolean comment, boolean shared,
                                     Boolean pagePublished) {

    public NotificationPrefUpdate(boolean emailEnabled, NotificationPref.EmailMode emailMode,
                                  boolean mentioned, boolean pageUpdated, boolean comment, boolean shared) {
        this(emailEnabled, emailMode, mentioned, pageUpdated, comment, shared, null);
    }

    public boolean pagePublishedOrInherited() {
        return pagePublished == null ? pageUpdated : pagePublished;
    }
}
