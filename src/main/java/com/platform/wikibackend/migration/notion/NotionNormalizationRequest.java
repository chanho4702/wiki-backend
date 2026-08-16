package com.platform.wikibackend.migration.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.wikibackend.migration.normalization.ResolvedMigrationAsset;

import java.time.Instant;
import java.util.Map;

public record NotionNormalizationRequest(
        JsonNode snapshot,
        String sourceInstanceId,
        Instant capturedAt,
        String sourceChecksum,
        String payloadRef,
        Map<String, ResolvedMigrationAsset> resolvedAssetsByBlockId) {

    public NotionNormalizationRequest {
        resolvedAssetsByBlockId = resolvedAssetsByBlockId == null
                ? Map.of()
                : Map.copyOf(resolvedAssetsByBlockId);
    }
}
