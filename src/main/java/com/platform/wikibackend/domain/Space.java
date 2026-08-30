package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "space")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    /** 개인 스페이스의 주인(V27). null이면 팀 스페이스. 한 사람에 하나다(부분 유니크 인덱스). */
    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    /** 개인 스페이스 — key는 서버가 정한다(`me-{id}`). 사용자 입력 key와 겹치지 않는 접두다. */
    public static Space personalOf(long ownerId, String ownerName) {
        Space s = of("me-" + ownerId, ownerName + "의 스페이스", null, ownerId);
        s.ownerId = ownerId;
        return s;
    }

    public boolean isPersonal() {
        return ownerId != null;
    }

    public static Space of(String key, String name, String description, Long createdBy) {
        Space s = new Space();
        s.key = key;
        s.name = name;
        s.description = description;
        s.createdBy = createdBy;
        return s;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
