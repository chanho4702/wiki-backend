package com.platform.wikibackend.attachment;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 로컬 디스크 첨부 저장 — 파일명은 UUID(storage_key), 원본명은 DB에만(경로 조작 차단). */
@Component
public class LocalFileStorage implements AttachmentStorage {

    private final Path dir;

    public LocalFileStorage(@Value("${platform.wiki.files-dir}") String filesDir) {
        this.dir = Path.of(filesDir);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.LOCAL;
    }

    @Override
    public StoredObject store(InputStream in, long contentLength, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            Files.copy(in, resolve(key), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(resolve(key));
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("첨부 저장 실패", e);
        }
        return new StoredObject(backend(), null, key, null);
    }

    @Override
    public Resource open(String bucket, String key, String version) {
        return new FileSystemResource(resolve(key));
    }

    /** 실패는 무해(고아 파일) — 호출부가 WARN 로그. */
    @Override
    public boolean delete(String bucket, String key, String version) {
        try {
            return Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolve(String key) {
        Path resolved = dir.resolve(key).normalize();
        if (!resolved.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("잘못된 첨부 storage key");
        }
        return resolved;
    }
}
