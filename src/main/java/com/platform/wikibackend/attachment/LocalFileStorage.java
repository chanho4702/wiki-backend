package com.platform.wikibackend.attachment;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
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
public class LocalFileStorage {

    private final Path dir;

    public LocalFileStorage(@Value("${platform.wiki.files-dir}") String filesDir) {
        this.dir = Path.of(filesDir);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(dir);
    }

    public String store(InputStream in) {
        String key = UUID.randomUUID().toString();
        try {
            Files.copy(in, dir.resolve(key), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장 실패", e);
        }
        return key;
    }

    public Resource open(String key) {
        return new PathResource(dir.resolve(key));
    }

    /** 실패는 무해(고아 파일) — 호출부가 WARN 로그. */
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(dir.resolve(key));
        } catch (IOException e) {
            return false;
        }
    }
}
