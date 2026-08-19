package com.platform.wikibackend.migration.dto;

import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MigrationJobCreateRequest(
        @NotNull MigrationProvider provider,
        @NotBlank @Size(max = 255) String sourceInstanceId,
        @NotNull Long targetSpaceId,
        @NotNull MigrationJobMode mode) {
}
