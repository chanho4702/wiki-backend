package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;

public record PageResponse(Long id, Long spaceId, Long parentId, String title, String content,
                           Integer version, PageType type, PageStatus status, Long position,
                           String icon, Long views,
                           /** 보관 시각(W23). null이면 살아 있는 문서. */
                           java.time.Instant archivedAt,
                           /** 문서 소유자(W27-5). 정하지 않았으면 null — created_by로 대신하지 않는다. */
                           Long ownerId,
                           /** 검증(W27-5). 만료 판정은 화면이 verifiedUntil로 한다(서버는 저장만). */
                           java.time.Instant verifiedAt,
                           Long verifiedBy,
                           java.time.Instant verifiedUntil) {
    public static PageResponse from(Page p) {
        return new PageResponse(p.getId(), p.getSpaceId(), p.getParentId(), p.getTitle(), p.getContent(),
                p.getVersion(), p.getType(), p.getStatus(), p.getSortOrder(),
                p.getIcon(), p.getViewCount(), p.getArchivedAt(),
                p.getOwnerId(), p.getVerifiedAt(), p.getVerifiedBy(), p.getVerifiedUntil());
    }
}
