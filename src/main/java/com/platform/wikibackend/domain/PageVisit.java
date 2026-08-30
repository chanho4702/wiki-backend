package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 사용자가 마지막으로 그 페이지를 본 시각(W23).
 *
 * 방문마다 줄을 쌓지 않고 사용자·페이지당 한 줄을 갱신한다 — 필요한 것은 "마지막으로 언제
 * 봤나"뿐이고, 매 방문을 남기면 활동 이력이 되어 보존 정책이 따라붙는다.
 */
@Entity
@Table(name = "page_visit")
@IdClass(PageVisit.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageVisit {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Column(name = "visited_at", nullable = false)
    private Instant visitedAt;

    public static PageVisit of(long userId, long pageId) {
        PageVisit v = new PageVisit();
        v.userId = userId;
        v.pageId = pageId;
        v.visitedAt = Instant.now();
        return v;
    }

    public void touch() {
        this.visitedAt = Instant.now();
    }

    public record Key(Long userId, Long pageId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
