package com.platform.wikibackend.migration.model;

/**
 * 단계 사이에 넘기는 산출물의 종류. EXTRACT→SNAPSHOT, NORMALIZE→IR, MEDIA_COPY→MEDIA_MANIFEST,
 * RESOLVE→MARKDOWN.
 */
public enum MigrationPayloadKind {
    SNAPSHOT,
    IR,
    MARKDOWN,
    /** 스테이징해 둔 첨부 바이트의 좌표 목록(M2). RESOLVE가 이걸 보고 첨부 레코드를 만든다. */
    MEDIA_MANIFEST
}
