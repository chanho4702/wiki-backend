package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Locale;

/** 페이지 라벨(V14). 이름은 정규화해 저장한다 — 대소문자만 다른 라벨이 갈라지면 목록이 무의미해진다. */
@Entity
@Table(name = "page_label")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageLabel {

    /** 컨플루언스처럼 공백을 허용하지 않는다 — 라벨은 한 단어여야 검색·필터에서 다루기 쉽다. */
    public static final int MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Column(nullable = false, length = MAX_LENGTH)
    private String name;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PageLabel of(long pageId, String name, long createdBy) {
        PageLabel label = new PageLabel();
        label.pageId = pageId;
        label.name = normalize(name);
        label.createdBy = createdBy;
        return label;
    }

    /** 앞뒤 공백 제거 + 소문자 + 내부 공백은 하이픈. 빈 문자열이면 거부한다. */
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("라벨을 입력하세요");
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", "-");
        if (value.isEmpty()) throw new IllegalArgumentException("라벨을 입력하세요");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("라벨은 " + MAX_LENGTH + "자를 넘을 수 없습니다");
        }
        return value;
    }
}
