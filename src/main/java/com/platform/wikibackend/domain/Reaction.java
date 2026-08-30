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
import java.util.List;

/**
 * 리액션(W23) — 문서·댓글에 붙는 이모지 하나.
 *
 * 이모지 집합은 고정이다. 아무 문자나 받으면 집계 화면이 예측 불가능한 기호로 차고, 유니코드
 * 정규화 차이로 같은 이모지가 두 줄이 된다.
 */
@Entity
@Table(name = "reaction")
@IdClass(Reaction.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reaction {

    public enum TargetType { PAGE, COMMENT }

    public static final List<String> ALLOWED = List.of("👍", "❤️", "🎉", "👀", "😄", "🙏");

    @Id
    @Column(name = "target_type", nullable = false, length = 10)
    private String targetType;

    @Id
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(nullable = false, length = 16)
    private String emoji;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Reaction of(TargetType type, long targetId, long userId, String emoji) {
        Reaction r = new Reaction();
        r.targetType = type.name();
        r.targetId = targetId;
        r.userId = userId;
        r.emoji = requireAllowed(emoji);
        return r;
    }

    public static String requireAllowed(String emoji) {
        if (emoji == null || !ALLOWED.contains(emoji)) {
            throw new IllegalArgumentException("지원하지 않는 리액션입니다: " + emoji);
        }
        return emoji;
    }

    public record Key(String targetType, Long targetId, Long userId, String emoji) implements Serializable {
        public Key() {
            this(null, null, null, null);
        }
    }
}
