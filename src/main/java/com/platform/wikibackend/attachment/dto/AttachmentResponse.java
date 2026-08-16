package com.platform.wikibackend.attachment.dto;

import com.platform.wikibackend.domain.Attachment;

public record AttachmentResponse(Long id, String filename, String contentType,
                                 Long sizeBytes, String checksumSha256) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getFilename(), a.getContentType(),
                a.getSizeBytes(), a.getChecksumSha256());
    }
}
