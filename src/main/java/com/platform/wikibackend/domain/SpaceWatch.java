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

/**
 * 스페이스 구독(V32) — 페이지 구독({@link PageWatch})의 확장.
 *
 * 알림 대상은 두 원장의 합집합이다. 스페이스 구독만으로는 볼 수 없는 문서의 알림이 새어나가지
 * 않는다 — 발송 직전 수신자별 VIEW 판정이 한 번 더 걸린다(NotificationService.deliver).
 */
@Entity
@Table(name = "space_watch")
@IdClass(SpaceWatch.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpaceWatch {

    @Id
    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SpaceWatch of(long spaceId, long userId) {
        SpaceWatch watch = new SpaceWatch();
        watch.spaceId = spaceId;
        watch.userId = userId;
        return watch;
    }

    public record Key(Long spaceId, Long userId) implements Serializable {
        public Key() { this(null, null); }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(spaceId, k.spaceId) && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(spaceId, userId); }
    }
}
