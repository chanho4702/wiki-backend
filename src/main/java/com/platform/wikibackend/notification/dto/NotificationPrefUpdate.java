package com.platform.wikibackend.notification.dto;

public record NotificationPrefUpdate(boolean emailEnabled, boolean mentioned, boolean pageUpdated,
                                     boolean comment, boolean shared) {
}
