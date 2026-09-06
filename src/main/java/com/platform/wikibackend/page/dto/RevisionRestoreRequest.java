package com.platform.wikibackend.page.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 복원 요청 본문 — **선택**이다. 본문 없이 POST하면 서버가 "v{n} 버전으로 복원"을 남긴다.
 *
 * 본문을 선택으로 둔 이유: 복원은 기본 요약만으로도 충분히 읽히는 조작이고("어느 버전에서
 * 되돌렸나"가 가장 중요한 정보), 이미 본문 없이 부르는 클라이언트가 있다. 사람이 이유를 적어
 * 두고 싶을 때만 실어 보낸다.
 */
@Schema(description = "리비전 복원 요청. 본문 전체가 선택이며, 비우면 기본 요약이 남는다.")
public record RevisionRestoreRequest(
        @Schema(description = "복원으로 생기는 새 리비전에 남길 변경 요약. 비우면 \"v{n} 버전으로 복원\"",
                example = "장애 전 상태로 되돌림")
        @Size(max = 500) String changeNote) {
}
