package com.platform.wikibackend.page;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 휴지통 행(V13) — 네이티브 조회 결과의 얇은 표현. 본문은 싣지 않는다.
 *
 * timestamptz 컬럼을 JDBC 드라이버가 무엇으로 돌려주는지는 드라이버마다 다르다
 * (H2는 OffsetDateTime, PostgreSQL은 설정에 따라 Timestamp). 그 차이를 여기서만 흡수한다.
 */
public record TrashRow(Long id, Long parentId, String title, String type, String icon,
                       Instant deletedAt, Long deletedBy, boolean deletedRoot) {

    public static TrashRow from(Object[] row) {
        return new TrashRow(
                asLong(row[0]), asLong(row[1]), (String) row[2], (String) row[3], (String) row[4],
                asInstant(row[5]), asLong(row[6]), Boolean.TRUE.equals(row[7]));
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant asInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException(
                    "지원하지 않는 시각 타입입니다: " + value.getClass().getName());
        };
    }
}
