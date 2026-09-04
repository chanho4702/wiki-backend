package com.platform.wikibackend.migration.confluence.handler;

/** 컨플루언스 DC 단계가 보고하는 손실·경고 코드. 손실 보고서의 집계 키가 된다. */
public final class ConfluenceDcIssues {

    /** enqueue 때 본 버전과 실제로 읽은 버전이 다르다 — 발견 뒤 원본이 수정됐다. */
    public static final String SOURCE_VERSION_DRIFT = "SOURCE_VERSION_DRIFT";

    /** M1은 첨부 본체를 옮기지 않는다(M2). 본문의 참조는 남지만 파일은 아직 없다. */
    public static final String ATTACHMENT_NOT_COPIED = "ATTACHMENT_NOT_COPIED";

    /** 조상의 대상 페이지를 찾지 못해 루트에 두었다. */
    public static final String PARENT_NOT_FOUND = "PARENT_NOT_FOUND";

    /** 원본 작성자를 우리 사용자로 대조하지 못했다 — 잡 요청자를 작성자로 쓴다(기획 P2는 M3). */
    public static final String AUTHOR_UNMAPPED = "AUTHOR_UNMAPPED";

    /** IR이 우리 계약을 벗어났다. 재시도해도 같으므로 항목을 데드레터로 보낸다. */
    public static final String IR_INVALID = "IR_INVALID";

    /** 원본 스냅샷을 IR로 옮기지 못했다(잘린 XHTML·알 수 없는 구조). */
    public static final String SNAPSHOT_INVALID = "SNAPSHOT_INVALID";

    /** 대조 실패 — 옮긴 결과가 원본과 다르다. */
    public static final String VERIFY_PAGE_MISSING = "VERIFY_PAGE_MISSING";
    public static final String VERIFY_TITLE_MISMATCH = "VERIFY_TITLE_MISMATCH";
    public static final String VERIFY_BODY_EMPTY = "VERIFY_BODY_EMPTY";
    public static final String VERIFY_LABEL_MISMATCH = "VERIFY_LABEL_MISMATCH";
    public static final String VERIFY_MARKDOWN_MISSING = "VERIFY_MARKDOWN_MISSING";

    /** 이 job에 원본 접속 정보가 없다 — 잡 생성이 잘못됐다. */
    public static final String SOURCE_MISSING = "MIGRATION_SOURCE_MISSING";

    private ConfluenceDcIssues() {
    }
}
