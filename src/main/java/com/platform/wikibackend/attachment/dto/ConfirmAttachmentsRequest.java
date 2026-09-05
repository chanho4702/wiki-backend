package com.platform.wikibackend.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "본문 저장 뒤 임시 첨부를 확정하는 요청")
public record ConfirmAttachmentsRequest(
        @Schema(description = "저장한 본문에 실제로 남은 첨부 ID 목록. 여기 없는 임시 첨부는 정리된다",
                example = "[9, 10]")
        List<Long> attachmentIds) {
}
