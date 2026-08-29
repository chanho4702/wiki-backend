package com.platform.wikibackend.template.dto;

import com.platform.wikibackend.domain.PageTemplate;

/** 목록·단건 공용 응답. 목록에서도 본문을 함께 준다 — 템플릿은 짧고, 미리보기가 곧 선택 근거다. */
public record TemplateResponse(
        Long id,
        Long spaceId,
        String name,
        String description,
        String icon,
        String content,
        String updatedAt) {

    public static TemplateResponse from(PageTemplate t) {
        return new TemplateResponse(t.getId(), t.getSpaceId(), t.getName(), t.getDescription(),
                t.getIcon(), t.getContent(),
                t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
    }
}
