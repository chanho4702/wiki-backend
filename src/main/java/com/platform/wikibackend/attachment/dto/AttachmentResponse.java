package com.platform.wikibackend.attachment.dto;

import com.platform.wikibackend.domain.Attachment;

public record AttachmentResponse(Long id, Long pageId, String filename, String contentType,
                                 Long sizeBytes, String checksumSha256,
                                 /** 1부터. 2 이상이면 지난 버전이 있다(W23). */
                                 Integer version) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getPageId(), a.getFilename(), a.getContentType(),
                a.getSizeBytes(), a.getChecksumSha256(), a.getVersion());
    }
}
