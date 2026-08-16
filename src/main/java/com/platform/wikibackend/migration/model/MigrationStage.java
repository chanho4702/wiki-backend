package com.platform.wikibackend.migration.model;

public enum MigrationStage {
    EXTRACT,
    NORMALIZE,
    MEDIA_COPY,
    RESOLVE,
    VERIFY,
    DONE
}
