package com.platform.wikibackend.permission.dto;

import java.util.List;

/** 조상에서 상속되는 VIEW 제한 — 다이얼로그의 읽기 전용 표시("상위 '운영 문서'에서 상속됨"). */
public record InheritedRestriction(Long pageId, String pageTitle, List<RestrictionPrincipal> principals) {
}
