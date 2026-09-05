package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "페이지 한 건 — 본문과 메타데이터")
public record PageResponse(
        @Schema(description = "페이지 ID", example = "42") Long id,
        @Schema(description = "속한 스페이스 ID", example = "1") Long spaceId,
        @Schema(description = "부모 페이지 ID. 루트면 null", example = "12") Long parentId,
        @Schema(description = "페이지 제목", example = "배포 절차") String title,
        @Schema(description = "마크다운 본문", example = "# 배포 절차") String content,
        @Schema(description = "현재 버전. 수정 요청의 expectedVersion에 그대로 넣는다", example = "3")
        Integer version,
        @Schema(description = "page·folder·blog", example = "page") PageType type,
        @Schema(description = "draft 또는 published", example = "published") PageStatus status,
        @Schema(description = "같은 부모 안에서의 정렬 위치", example = "1024") Long position,
        @Schema(description = "페이지 아이콘 이모지", example = "📘") String icon,
        @Schema(description = "누적 조회수", example = "128") Long views,
        /** 보관 시각(W23). null이면 살아 있는 문서. */
        @Schema(description = "보관한 시각. null이면 살아 있는 문서") java.time.Instant archivedAt,
        /** 문서 소유자(W27-5). 정하지 않았으면 null — created_by로 대신하지 않는다. */
        @Schema(description = "문서 소유자. 정하지 않았으면 null(작성자로 대신하지 않는다)", example = "7")
        Long ownerId,
        /** 검증(W27-5). 만료 판정은 화면이 verifiedUntil로 한다(서버는 저장만). */
        @Schema(description = "검증한 시각") java.time.Instant verifiedAt,
        @Schema(description = "검증한 사용자 ID", example = "7") Long verifiedBy,
        @Schema(description = "검증 유효 기한. 만료 판정은 화면이 한다") java.time.Instant verifiedUntil,
        /**
         * 이관 원본의 작성자 이름(W29 M3) — 우리 계정으로 대조하지 못한 문서에만 값이 있다.
         * 화면은 값이 있으면 작성자 자리에 "이관됨 · {원본 이름}"을 보여준다.
         */
        @Schema(description = "이관 원본의 작성자 이름. 우리 계정과 짝지어지지 않은 문서에만 값이 있다",
                example = "hong.gildong")
        String importedAuthorName,
        /** 원본 문서 주소 — 이름만으로 확인할 수 없어 원본으로 가는 길을 함께 준다. */
        @Schema(description = "이관 원본 문서 주소", example = "https://confluence.example.com/x/AB")
        String importedSourceUrl) {
    public static PageResponse from(Page p) {
        return new PageResponse(p.getId(), p.getSpaceId(), p.getParentId(), p.getTitle(), p.getContent(),
                p.getVersion(), p.getType(), p.getStatus(), p.getSortOrder(),
                p.getIcon(), p.getViewCount(), p.getArchivedAt(),
                p.getOwnerId(), p.getVerifiedAt(), p.getVerifiedBy(), p.getVerifiedUntil(),
                p.getImportedAuthorName(), p.getImportedSourceUrl());
    }
}
