package com.platform.wikibackend.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 위키 전체 현황 한 장 — 관리자 대시보드의 "WIKI" 카드가 그대로 읽는다.
 *
 * 전부 COUNT/SUM 한 줄짜리 집계다. 본문 스캔·조인 집계·정렬 페이지네이션은 넣지 않는다 —
 * 이 엔드포인트가 메인 서비스에 부하를 주면 안 된다는 것이 설계 전제다(설계 §0).
 */
@Schema(description = "위키 전체 현황 통계. 관리자 대시보드용 집계 한 장이다.")
public record WikiAdminStats(

        @Schema(description = "스페이스 수", example = "12")
        long spaces,

        @Schema(description = "문서 수. 휴지통은 빼고, 폴더·블로그 글은 포함한다", example = "1834")
        long pages,

        @Schema(description = "초안 상태 문서 수. 휴지통 제외", example = "40")
        long draftPages,

        @Schema(description = "휴지통에 있는 문서 수", example = "7")
        long trashedPages,

        @Schema(description = "누적 리비전 수", example = "15220")
        long revisions,

        @Schema(description = "확정된 첨부 수. 저장만 되고 본문에 붙지 않은 임시 업로드는 세지 않는다",
                example = "310")
        long attachments,

        @Schema(description = "확정된 첨부의 총 바이트", example = "123456789")
        long attachmentBytes,

        @Schema(description = "최근 7일 편집 수. 리비전 한 건이 편집 한 번이다", example = "96")
        long editsLast7Days,

        @Schema(description = "댓글 수. 인라인 댓글과 답글을 모두 포함한다", example = "420")
        long comments) {
}
