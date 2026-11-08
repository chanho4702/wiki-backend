package com.platform.wikibackend.attachment;

import java.time.Instant;

/** 버킷 목록의 한 줄 — 고아 리포트가 행 키 집합과 대조한다. */
public record ListedObject(String key, long size, Instant lastModified) {
}
