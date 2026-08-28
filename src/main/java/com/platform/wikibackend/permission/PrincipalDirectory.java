package com.platform.wikibackend.permission;

import com.platform.wikibackend.permission.dto.RestrictionPrincipal;

import java.util.Collection;

/** 페이지 제한 주체의 원장(org-service) 실재 여부를 저장 전에 검증한다. */
public interface PrincipalDirectory {
    void requireExisting(Collection<RestrictionPrincipal> principals);
}
