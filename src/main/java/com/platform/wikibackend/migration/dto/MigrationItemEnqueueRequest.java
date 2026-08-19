package com.platform.wikibackend.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * extractor가 발견한 원본 객체 한 건. 본문은 immutable object storage에 두고 여기에는
 * 참조와 checksum만 넣는다 — 원본 payload를 DB에 담지 않는 것이 W14 계약이다.
 */
public record MigrationItemEnqueueRequest(
        @NotBlank @Size(max = 512) String externalObjectId,
        @Size(max = 100) String sourceVersion,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}", message = "sourceChecksum은 소문자 SHA-256이어야 합니다")
        String sourceChecksum,
        @NotBlank @Size(max = 1024) String payloadRef) {
}
