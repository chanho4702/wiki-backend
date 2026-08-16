package com.platform.wikibackend.attachment;

public record StoredObject(StorageBackend backend, String bucket, String key, String version) {
}
