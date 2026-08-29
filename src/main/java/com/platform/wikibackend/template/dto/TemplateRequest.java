package com.platform.wikibackend.template.dto;

import jakarta.validation.constraints.NotBlank;

/** 템플릿 생성·수정 요청. 검증 메시지는 프론트가 그대로 노출한다. */
public record TemplateRequest(
        @NotBlank(message = "템플릿 이름을 입력하세요") String name,
        String description,
        String icon,
        String content) {
}
