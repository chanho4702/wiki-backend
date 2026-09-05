package com.platform.wikibackend.space.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "스페이스 수정 요청. key는 바꿀 수 없다.")
public record SpaceUpdateRequest(
        @Schema(description = "스페이스 이름", example = "플랫폼 운영") @NotBlank @Size(max = 100) String name,
        @Schema(description = "스페이스 설명", example = "배포·장애 대응 문서를 모은다") String description) {}
