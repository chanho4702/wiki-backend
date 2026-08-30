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

/** 사용자가 별표한 페이지(W23). 키가 (user, page)라 같은 문서를 두 번 별표할 수 없다. */
@Entity
@Table(name = "page_star")
@IdClass(PageStar.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageStar {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PageStar of(long userId, long pageId) {
        PageStar s = new PageStar();
        s.userId = userId;
        s.pageId = pageId;
        return s;
    }

    public record Key(Long userId, Long pageId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
