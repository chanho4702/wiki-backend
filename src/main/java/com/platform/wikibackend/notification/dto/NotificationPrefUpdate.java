package com.platform.wikibackend.notification.dto;

import com.platform.wikibackend.domain.NotificationPref;

/** emailMode는 선택 — 없으면 IMMEDIATE(이 필드 도입 이전 클라이언트 호환). */
public record NotificationPrefUpdate(boolean emailEnabled, NotificationPref.EmailMode emailMode,
                                     boolean mentioned, boolean pageUpdated, boolean comment, boolean shared) {
}
