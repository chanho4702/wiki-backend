package com.platform.wikibackend.page.dto;

/**
 * 복원 결과(W21-1).
 *
 * `reparentedToRoot`는 원래 부모가 사라져 루트로 올라왔다는 뜻이다 — 화면이 "원래 위치가 없어
 * 최상위로 복원했습니다"를 알려야 사용자가 문서를 엉뚱한 곳에서 찾지 않는다.
 * `restoredCount`는 함께 되살아난 하위를 포함한 총 개수다.
 */
public record PageRestoreResponse(PageResponse page, boolean reparentedToRoot, int restoredCount) {
}
