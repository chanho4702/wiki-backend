package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** 페이지 구독(V15) — 알림을 누구에게 보낼지의 단일 원장. */
@Entity
@Table(name = "page_watch")
@IdClass(PageWatch.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageWatch {

    @Id
    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PageWatch of(long pageId, long userId) {
        PageWatch watch = new PageWatch();
        watch.pageId = pageId;
        watch.userId = userId;
        return watch;
    }

    public record Key(Long pageId, Long userId) implements Serializable {
        public Key() { this(null, null); }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(pageId, k.pageId) && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(pageId, userId); }
    }
}
