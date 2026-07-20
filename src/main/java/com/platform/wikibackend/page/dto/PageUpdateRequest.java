package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PageUpdateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String content,
        Long parentId,                 // null이면 루트로 이동, 값이 있으면 그 아래로 이동
        @NotNull Integer expectedVersion) {}
