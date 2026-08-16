package com.platform.wikibackend.migration.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.wikibackend.migration.normalization.MigrationNormalizationIssue;

import java.util.List;

public record ConfluenceNormalizationResult(
        JsonNode documentIr,
        List<MigrationNormalizationIssue> issues) {

    public ConfluenceNormalizationResult {
        issues = List.copyOf(issues);
    }
}
