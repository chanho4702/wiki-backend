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

/** 사용자가 별표한 스페이스(W23). 키가 (user, space)라 같은 스페이스를 두 번 별표할 수 없다. */
@Entity
@Table(name = "space_star")
@IdClass(SpaceStar.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpaceStar {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SpaceStar of(long userId, long spaceId) {
        SpaceStar s = new SpaceStar();
        s.userId = userId;
        s.spaceId = spaceId;
        return s;
    }

    public record Key(Long userId, Long spaceId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
