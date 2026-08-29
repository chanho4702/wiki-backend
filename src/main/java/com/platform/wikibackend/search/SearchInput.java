package com.platform.wikibackend.search;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * search.graphqls의 SearchInput과 같은 입력 모델.
 *
 * 정규화·검증 규칙은 search-service의 SearchInput과 같아야 한다 — 두 배포에서 같은 질의가
 * 다른 결과를 내면 "어느 배포냐"가 버그 리포트의 첫 질문이 된다.
 */
public record SearchInput(
        String query,
        List<String> spaceIds,
        List<DocType> docTypes,
        Boolean includeDrafts,
        List<String> authorIds,
        String updatedAfter,
        String updatedBefore,
        List<String> labels,
        /** 정렬. null이면 관련도. */
        SearchSort sort,
        Integer page,
        Integer size
) {
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    public Set<Long> requestedSpaceIds() {
        return longs(spaceIds, "spaceIds");
    }

    public Set<Long> requestedAuthorIds() {
        return longs(authorIds, "authorIds");
    }

    private static Set<Long> longs(List<String> raw, String field) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                parsed.add(Long.parseLong(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + "는 숫자 ID여야 합니다: " + value, e);
            }
        }
        return Set.copyOf(parsed);
    }

    public Instant updatedAfterInstant() {
        return toInstant(updatedAfter, "updatedAfter");
    }

    public Instant updatedBeforeInstant() {
        return toInstant(updatedBefore, "updatedBefore");
    }

    private static Instant toInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            // 날짜만 온 경우(2026-08-01)도 받아준다 — 그 날의 시작으로 읽는다.
            try {
                return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException(field + "는 ISO-8601 시각이어야 합니다: " + raw, e);
            }
        }
    }

    /** 저장할 때와 같은 규칙으로 정규화한다(PageLabel과 동일) — 대소문자만 달라 안 걸리면 안 된다. */
    public List<String> normalizedLabels() {
        if (labels == null || labels.isEmpty()) return List.of();
        return labels.stream()
                .filter(Objects::nonNull)
                .map(raw -> raw.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", "-"))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    public SearchSort normalizedSort() {
        return sort == null ? SearchSort.RELEVANCE : sort;
    }

    public boolean draftsIncluded() {
        return Boolean.TRUE.equals(includeDrafts);
    }

    public boolean wants(DocType type) {
        return docTypes == null || docTypes.isEmpty() || docTypes.contains(type);
    }

    public int normalizedPage() {
        return Math.max(page == null ? 0 : page, 0);
    }

    public int normalizedSize() {
        int requested = size == null ? DEFAULT_SIZE : size;
        return Math.max(0, Math.min(requested, MAX_SIZE));
    }
}
