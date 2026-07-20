package com.platform.wikibackend.permission;

import java.util.Set;

/** 사용자가 접근 가능한 스페이스 범위. all=true면 전 스페이스(GLOBAL grant 보유자). */
public record AccessScope(boolean all, Set<Long> spaceIds) {
    public boolean contains(long spaceId) {
        return all || spaceIds.contains(spaceId);
    }
}
