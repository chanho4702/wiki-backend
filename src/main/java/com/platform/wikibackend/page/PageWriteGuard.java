package com.platform.wikibackend.page;

import com.platform.common.error.ConflictException;
import com.platform.wikibackend.domain.Page;

/**
 * 보관(Archive) 불변식 — "보관 해제 전까지 문서는 바뀌지 않는다".
 *
 * 감사·검증 배지·"끝난 문서" 표시가 이 전제 위에 있다. 예전에는 일반 저장과 체크 토글에만 검사가 있고
 * 복원·공동 초안 게시·공동 편집 티켓·이동·게시·아이콘·소유자·검증·라벨·첨부가 빠져 있었다(감사 W-07).
 * 버전을 올리거나 메타를 바꾸는 경로는 전부 여기를 지난다 — 새 쓰기 경로를 만들면 여기도 부른다.
 */
public final class PageWriteGuard {

    public static final String ARCHIVED_MESSAGE = "보관된 문서는 편집할 수 없습니다. 먼저 보관을 해제하세요";

    private PageWriteGuard() {
    }

    public static void requireWritable(Page page) {
        if (page.isArchived()) throw new ConflictException(ARCHIVED_MESSAGE);
    }

    /** 새 자식·이동 대상 부모 — 보관된 문서 아래에는 아무것도 들어가지 않는다. */
    public static void requireWritableParent(Page parent) {
        if (parent != null && parent.isArchived()) {
            throw new ConflictException("보관된 문서 아래로는 문서를 만들거나 옮길 수 없습니다. 먼저 보관을 해제하세요");
        }
    }
}
