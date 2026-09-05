package com.platform.wikibackend.attachment.dto;

import com.platform.wikibackend.domain.Attachment;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "첨부 파일 한 건의 메타데이터. 저장 좌표는 내보내지 않는다.")
public record AttachmentResponse(
        @Schema(description = "첨부 ID", example = "9") Long id,
        @Schema(description = "붙어 있는 페이지 ID", example = "42") Long pageId,
        @Schema(description = "원본 파일 이름", example = "배포절차.pdf") String filename,
        @Schema(description = "MIME 타입", example = "application/pdf") String contentType,
        @Schema(description = "파일 크기(바이트)", example = "204800") Long sizeBytes,
        @Schema(description = "내용의 SHA-256 체크섬(hex)",
                example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        String checksumSha256,
        /** 1부터. 2 이상이면 지난 버전이 있다(W23). */
        @Schema(description = "현재 버전(1부터). 2 이상이면 지난 버전이 있다", example = "2") Integer version) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getPageId(), a.getFilename(), a.getContentType(),
                a.getSizeBytes(), a.getChecksumSha256(), a.getVersion());
    }
}
