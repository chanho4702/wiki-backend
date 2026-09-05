package com.platform.wikibackend.attachment.dto;

import com.platform.wikibackend.domain.AttachmentVersion;
import io.swagger.v3.oas.annotations.media.Schema;

/** 첨부의 지난 버전 한 줄. 저장 좌표는 내보내지 않는다 — 클라이언트가 알 이유가 없다. */
@Schema(description = "첨부의 지난 버전 한 줄")
public record AttachmentVersionResponse(
        @Schema(description = "버전 번호(1부터)", example = "1") Integer version,
        @Schema(description = "그때의 MIME 타입", example = "application/pdf") String contentType,
        @Schema(description = "그때의 파일 크기(바이트)", example = "198400") Long sizeBytes,
        @Schema(description = "그 버전을 올린 사용자 ID", example = "7") Long uploadedBy,
        @Schema(description = "그 버전이 만들어진 시각(ISO-8601)", example = "2026-09-01T02:30:00Z")
        String createdAt) {

    public static AttachmentVersionResponse from(AttachmentVersion v) {
        return new AttachmentVersionResponse(v.getVersion(), v.getContentType(), v.getSizeBytes(),
                v.getUploadedBy(), v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
    }
}
