package com.platform.wikibackend.attachment;

import org.springframework.core.io.Resource;

import java.io.InputStream;

public interface AttachmentStorage {
    StorageBackend backend();

    StoredObject store(InputStream input, long contentLength, String contentType);

    Resource open(String bucket, String key, String version);

    boolean delete(String bucket, String key, String version);
}
