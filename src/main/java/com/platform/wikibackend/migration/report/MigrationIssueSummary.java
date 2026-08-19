package com.platform.wikibackend.migration.report;

import com.platform.wikibackend.migration.model.MigrationIssueSeverity;

/** 같은 code가 여러 item·여러 위치에서 나오므로 distinct 건수와 총 발생 수를 함께 센다. */
public record MigrationIssueSummary(MigrationIssueSeverity severity, String code,
                                    long distinctPaths, long occurrences) {
}
