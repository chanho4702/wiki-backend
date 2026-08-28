package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;

/**
 * 지연 트리의 노드 하나(2026-08-28). `PageTreeItem` + `childCount`.
 *
 * childCount가 필요한 이유: 지연 트리는 자식을 불러오기 전에 펼침 화살표를 그릴지 정해야 한다.
 * 없으면 "펼쳤더니 비어 있는" 노드가 생기거나, 화살표를 그리려고 전부 미리 불러오게 된다.
 */
public record PageNode(Long id, Long parentId, String title, PageType type, PageStatus status,
                       Long position, String icon, Long updatedBy, java.time.Instant updatedAt,
                       long childCount) {

    public static PageNode of(PageTreeItem item, long childCount) {
        return new PageNode(item.id(), item.parentId(), item.title(), item.type(), item.status(),
                item.position(), item.icon(), item.updatedBy(), item.updatedAt(), childCount);
    }
}
