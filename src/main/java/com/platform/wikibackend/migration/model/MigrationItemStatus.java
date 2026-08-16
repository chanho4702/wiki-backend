package com.platform.wikibackend.migration.model;

public enum MigrationItemStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    DEAD_LETTER
}
