package com.platform.wikibackend.space.dto;

import com.platform.wikibackend.domain.Space;

public record SpaceResponse(Long id, String key, String name, String description) {
    public static SpaceResponse from(Space s) {
        return new SpaceResponse(s.getId(), s.getKey(), s.getName(), s.getDescription());
    }
}
