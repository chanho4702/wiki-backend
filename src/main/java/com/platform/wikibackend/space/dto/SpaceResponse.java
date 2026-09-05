package com.platform.wikibackend.space.dto;

import com.platform.wikibackend.domain.Space;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "스페이스 한 건")
public record SpaceResponse(
        @Schema(description = "스페이스 ID", example = "1") Long id,
        @Schema(description = "주소에 쓰이는 스페이스 키", example = "platform-ops") String key,
        @Schema(description = "스페이스 이름", example = "플랫폼 운영") String name,
        @Schema(description = "스페이스 설명", example = "배포·장애 대응 문서를 모은다") String description,
        /** 개인 스페이스의 주인(W23). null이면 팀 스페이스. */
        @Schema(description = "개인 스페이스의 주인. null이면 팀 스페이스", example = "7") Long ownerId) {
    public static SpaceResponse from(Space s) {
        return new SpaceResponse(s.getId(), s.getKey(), s.getName(), s.getDescription(), s.getOwnerId());
    }
}
