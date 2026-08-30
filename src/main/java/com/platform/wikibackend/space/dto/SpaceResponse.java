package com.platform.wikibackend.space.dto;

import com.platform.wikibackend.domain.Space;

public record SpaceResponse(Long id, String key, String name, String description,
                            /** 개인 스페이스의 주인(W23). null이면 팀 스페이스. */
                            Long ownerId) {
    public static SpaceResponse from(Space s) {
        return new SpaceResponse(s.getId(), s.getKey(), s.getName(), s.getDescription(), s.getOwnerId());
    }
}
