package com.platform.wikibackend.search;

import java.util.List;

/** search.graphqls의 SearchHit 응답 모델 — search-service의 같은 이름 레코드와 필드가 같다. */
public record SearchHit(
        String id,
        DocType docType,
        String spaceId,
        String spaceKey,
        String spaceName,
        String pageId,
        /** PAGE만 채워진다. */
        String pageType,
        String title,
        String filename,
        List<String> highlights,
        String updatedAt,
        double score
) {}
