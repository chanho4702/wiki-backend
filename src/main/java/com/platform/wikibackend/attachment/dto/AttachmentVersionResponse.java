package com.platform.wikibackend.attachment.dto;

import com.platform.wikibackend.domain.AttachmentVersion;

/** 첨부의 지난 버전 한 줄. 저장 좌표는 내보내지 않는다 — 클라이언트가 알 이유가 없다. */
public record AttachmentVersionResponse(
        Integer version,
        String contentType,
        Long sizeBytes,
        Long uploadedBy,
        String createdAt) {

    public static AttachmentVersionResponse from(AttachmentVersion v) {
        return new AttachmentVersionResponse(v.getVersion(), v.getContentType(), v.getSizeBytes(),
                v.getUploadedBy(), v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
    }
}
