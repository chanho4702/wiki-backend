package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 지연 트리의 노드 하나(2026-08-28). `PageTreeItem` + `childCount`.
 *
 * childCount가 필요한 이유: 지연 트리는 자식을 불러오기 전에 펼침 화살표를 그릴지 정해야 한다.
 * 없으면 "펼쳤더니 비어 있는" 노드가 생기거나, 화살표를 그리려고 전부 미리 불러오게 된다.
 */
@Schema(description = "페이지 트리 노드 하나. 본문은 담지 않는다.")
public record PageNode(
        @Schema(description = "페이지 ID", example = "42") Long id,
        @Schema(description = "부모 페이지 ID. 루트면 null", example = "12") Long parentId,
        @Schema(description = "페이지 제목", example = "배포 절차") String title,
        @Schema(description = "page·folder·blog", example = "page") PageType type,
        @Schema(description = "draft 또는 published", example = "published") PageStatus status,
        @Schema(description = "같은 부모 안에서의 정렬 위치", example = "1024") Long position,
        @Schema(description = "페이지 아이콘 이모지", example = "📘") String icon,
        @Schema(description = "마지막으로 고친 사용자 ID", example = "7") Long updatedBy,
        @Schema(description = "마지막으로 고친 시각") java.time.Instant updatedAt,
        @Schema(description = "자식 수. 0이면 펼침 화살표를 그리지 않는다", example = "3") long childCount) {

    public static PageNode of(PageTreeItem item, long childCount) {
        return new PageNode(item.id(), item.parentId(), item.title(), item.type(), item.status(),
                item.position(), item.icon(), item.updatedBy(), item.updatedAt(), childCount);
    }
}
