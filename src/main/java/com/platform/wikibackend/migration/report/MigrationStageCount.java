package com.platform.wikibackend.migration.report;

import com.platform.wikibackend.migration.model.MigrationStage;

public record MigrationStageCount(MigrationStage stage, long total) {
}
