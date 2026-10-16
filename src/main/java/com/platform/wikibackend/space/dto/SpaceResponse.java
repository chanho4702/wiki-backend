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
        @Schema(description = "개인 스페이스의 주인. null이면 팀 스페이스", example = "7") Long ownerId,
        /** 스페이스 라벨(V41) — 디렉토리 분류. 문서 라벨이 아니다. 없으면 빈 배열(null이 아니다). */
        @Schema(description = "스페이스 라벨(디렉토리 분류, 이름순). 없으면 빈 배열", example = "[\"engineering\"]")
        java.util.List<String> labels) {
    /**
     * 라벨은 별도 원장이라 호출부가 함께 넘긴다 — 라벨 없이 만드는 팩토리를 두지 않는 이유:
     * 그런 길이 있으면 어느 응답은 라벨이 비어 나가는데 테스트는 통과한다.
     */
    public static SpaceResponse from(Space s, java.util.List<String> labels) {
        return new SpaceResponse(s.getId(), s.getKey(), s.getName(), s.getDescription(), s.getOwnerId(),
                labels == null ? java.util.List.of() : java.util.List.copyOf(labels));
    }
}
