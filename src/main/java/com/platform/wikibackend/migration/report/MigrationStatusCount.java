package com.platform.wikibackend.migration.report;

import com.platform.wikibackend.migration.model.MigrationItemStatus;

public record MigrationStatusCount(MigrationItemStatus status, long total) {
}
