package com.platform.wikibackend.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 첨부 고아 객체 드라이런 리포트 응답 — 읽기 전용, 아무것도 지우지 않는다. */
@Schema(description = "첨부 저장소(S3 버킷)와 첨부 행(attachment·attachment_version)의 대조 결과. 삭제는 하지 않는다.")
public record AttachmentOrphanReport(
        @Schema(description = "스캔 시작 시각") Instant scannedAt,
        @Schema(description = "대조한 버킷") String bucket,
        @Schema(description = "목록에서 읽은 객체 수(truncated면 상한까지)") long objectsTotal,
        @Schema(description = "이 버킷을 가리키는 S3 행 수(현재 첨부 + 지난 버전)") long rowsTotal,
        @Schema(description = "객체 상한·시간 예산에 걸려 버킷 끝까지 보지 못했는가") boolean truncated,
        @Schema(description = "버킷에는 있으나 어떤 행도 참조하지 않는 객체") OrphanObjects orphanObjects,
        @Schema(description = "행은 있으나 객체가 없는 것(reason=object-missing)") RowGroup danglingRows,
        @Schema(description = "페이지(page-missing) 또는 첨부(attachment-missing)가 사라졌는데 남은 행")
        RowGroup purgedPageRows,
        @Schema(description = "판정 규칙·제외 항목·한계") List<String> notes) {

    public record OrphanObjects(long count, long bytes, List<ObjectSample> sample) {
    }

    public record ObjectSample(String key, long size, Instant lastModified) {
    }

    public record RowGroup(long count, List<RowSample> sample) {
    }

    /** {@code version}은 지난 버전 행일 때만, {@code pageId}는 첨부 행이 남아 있을 때만 나온다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowSample(Long attachmentId, Long pageId, String key, String reason, Integer version) {
    }
}
