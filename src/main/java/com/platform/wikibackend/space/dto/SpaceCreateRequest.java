package com.platform.wikibackend.space.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "스페이스 생성 요청")
public record SpaceCreateRequest(
        @Schema(description = "주소에 쓰이는 스페이스 키. 소문자·숫자·하이픈만", example = "platform-ops")
        @NotBlank @Size(max = 30) @Pattern(regexp = "[a-z0-9-]+", message = "key는 소문자·숫자·하이픈만") String key,
        @Schema(description = "스페이스 이름", example = "플랫폼 운영")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "스페이스 설명", example = "배포·장애 대응 문서를 모은다")
        String description) {}
