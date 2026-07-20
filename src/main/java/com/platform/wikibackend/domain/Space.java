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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

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
