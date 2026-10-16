package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 스페이스 라벨(V41) — 스페이스 디렉토리가 스페이스를 묶어 보는 분류. 문서 라벨({@link PageLabel})과
 * 다른 원장이다(마이그레이션 주석 참고). 이름 규칙은 문서 라벨과 같다.
 */
@Entity
@Table(name = "space_label")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpaceLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(nullable = false, length = PageLabel.MAX_LENGTH)
    private String name;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SpaceLabel of(long spaceId, String name, long createdBy) {
        SpaceLabel label = new SpaceLabel();
        label.spaceId = spaceId;
        label.name = PageLabel.normalize(name);
        label.createdBy = createdBy;
        return label;
    }
}
