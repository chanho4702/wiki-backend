package com.platform.wikibackend.attachment;

import java.util.Locale;

public enum StorageBackend {
    LOCAL,
    S3;

    public static StorageBackend fromConfig(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("지원하지 않는 첨부 저장소: " + value, e);
        }
    }
}
