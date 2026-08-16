package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CollaborationDraftCommitRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String content,
        @NotNull @Positive Integer expectedPageVersion,
        @NotNull @Positive Long expectedGeneration) {}
