package com.platform.wikibackend.migration.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.wikibackend.migration.normalization.ResolvedMigrationAsset;

import java.time.Instant;
import java.util.Map;

public record ConfluenceNormalizationRequest(
        JsonNode snapshot,
        String sourceInstanceId,
        Instant capturedAt,
        String sourceChecksum,
        String payloadRef,
        Map<String, ResolvedMigrationAsset> resolvedAssetsByReference) {

    public ConfluenceNormalizationRequest {
        resolvedAssetsByReference = resolvedAssetsByReference == null
                ? Map.of()
                : Map.copyOf(resolvedAssetsByReference);
    }
}
