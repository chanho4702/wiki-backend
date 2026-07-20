package com.platform.wikibackend.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SpaceCreateRequest(
        @NotBlank @Size(max = 30) @Pattern(regexp = "[a-z0-9-]+", message = "key는 소문자·숫자·하이픈만") String key,
        @NotBlank @Size(max = 100) String name,
        String description) {}
