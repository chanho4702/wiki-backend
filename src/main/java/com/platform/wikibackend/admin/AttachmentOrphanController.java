package com.platform.wikibackend.admin;

import com.platform.wikibackend.config.ConflictResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 첨부 고아 객체 드라이런 리포트 — 전역 관리자 전용, 읽기만(옵시디언 49 N3). */
@Tag(name = "Admin", description = "플랫폼 관리자 전용 위키 현황. 전역 관리자만 읽을 수 있다.")
@RestController
@RequiredArgsConstructor
public class AttachmentOrphanController {

    private final AttachmentOrphanReportService reports;

    @Operation(summary = "첨부 고아 객체를 드라이런으로 조회한다 — 전역 관리자만, 삭제 없음",
            description = "S3 버킷 객체와 첨부 행(현재·지난 버전)을 대조해 버킷에만 있는 객체, 객체가 없는 행, "
                    + "페이지·첨부가 사라졌는데 남은 행을 센다. 최근 1시간 안의 객체·행은 업로드 경합 때문에 뺀다. "
                    + "S3 저장소가 꺼져 있으면 409.")
    @ConflictResponse("S3 첨부 저장소가 꺼져 있어 고아 객체를 조회할 수 없습니다")
    @GetMapping("/api/wiki/admin/attachments/orphans")
    public AttachmentOrphanReport orphans(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "샘플 상한(기본 200, 최대 1000 — 넘기면 잘라서 적용)")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "읽을 객체 상한(기본 200000, 최대 1000000) — 넘으면 truncated")
            @RequestParam(required = false) Integer maxObjects) {
        return reports.report(userId(jwt), limit, maxObjects);
    }
}
