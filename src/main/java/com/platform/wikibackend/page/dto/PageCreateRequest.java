package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PageCreateRequest(
        @NotNull Long spaceId,
        Long parentId,
        @NotBlank @Size(max = 255) String title,
        @NotNull String content) {}
