package com.platform.wikibackend.migration.model;

/** 단계 사이에 넘기는 산출물의 종류. EXTRACT→SNAPSHOT, NORMALIZE→IR, RESOLVE→MARKDOWN. */
public enum MigrationPayloadKind {
    SNAPSHOT,
    IR,
    MARKDOWN
}
