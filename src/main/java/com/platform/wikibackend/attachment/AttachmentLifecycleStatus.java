package com.platform.wikibackend.attachment;

/**
 * 에디터 업로드의 수명주기. PENDING은 페이지 본문 저장 전 임시 객체이며,
 * CONFIRMED만 색인·장기 보존 대상으로 취급한다.
 */
public enum AttachmentLifecycleStatus {
    PENDING,
    CONFIRMED
}
