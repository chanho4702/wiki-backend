package com.platform.wikibackend.permission.dto;

import com.platform.wikibackend.domain.PageRestriction;

/** 제한 주체 — type: "USER" | "TEAM". 이름 해석은 프론트(org 디렉터리) 몫. */
public record RestrictionPrincipal(String type, long id) {
    public PageRestriction.PrincipalType toType() {
        try {
            return PageRestriction.PrincipalType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("주체 타입은 USER 또는 TEAM이어야 합니다: " + type);
        }
    }
}
