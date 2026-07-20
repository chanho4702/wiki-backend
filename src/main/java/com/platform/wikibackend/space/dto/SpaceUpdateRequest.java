package com.platform.wikibackend.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpaceUpdateRequest(@NotBlank @Size(max = 100) String name, String description) {}
