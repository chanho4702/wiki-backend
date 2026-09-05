package com.platform.wikibackend.template;

import com.platform.wikibackend.template.dto.TemplateRequest;
import com.platform.wikibackend.template.dto.TemplateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.wikibackend.config.ConflictResponse;
import io.swagger.v3.oas.annotations.Parameter;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 페이지 템플릿 REST(W23). 읽기는 스페이스 VIEW, 쓰기는 ADMIN — 판정은 서비스가 한다. */
@Tag(name = "Templates", description = "스페이스 페이지 템플릿.")
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templates;

    @Operation(summary = "스페이스의 템플릿 목록을 조회한다")
    @GetMapping("/api/wiki/spaces/{spaceId}/templates")
    public List<TemplateResponse> list(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "스페이스 ID") @PathVariable Long spaceId) {
        return templates.list(userId(jwt), spaceId);
    }

    @ConflictResponse("같은 이름의 템플릿이 있거나, 스페이스당 개수 상한을 넘었습니다")
    @Operation(summary = "스페이스에 템플릿을 만든다")
    @PostMapping("/api/wiki/spaces/{spaceId}/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "스페이스 ID") @PathVariable Long spaceId,
                                   @Valid @RequestBody TemplateRequest req) {
        return templates.create(userId(jwt), spaceId, req);
    }

    @Operation(summary = "템플릿 본문을 조회한다")
    @GetMapping("/api/wiki/templates/{templateId}")
    public TemplateResponse get(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "템플릿 ID") @PathVariable Long templateId) {
        return templates.get(userId(jwt), templateId);
    }

    @ConflictResponse("같은 이름의 템플릿이 이미 있습니다")
    @Operation(summary = "템플릿을 수정한다")
    @PutMapping("/api/wiki/templates/{templateId}")
    public TemplateResponse update(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "템플릿 ID") @PathVariable Long templateId,
                                   @Valid @RequestBody TemplateRequest req) {
        return templates.update(userId(jwt), templateId, req);
    }

    @Operation(summary = "템플릿을 삭제한다")
    @DeleteMapping("/api/wiki/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "템플릿 ID") @PathVariable Long templateId) {
        templates.delete(userId(jwt), templateId);
    }

    /** 지금 있는 페이지를 템플릿으로 — 이름을 안 주면 그 페이지 제목을 쓴다. */
    @ConflictResponse("같은 이름의 템플릿이 있거나, 스페이스당 개수 상한을 넘었습니다")
    @Operation(summary = "지금 페이지를 템플릿으로 저장한다 — 이름을 비우면 페이지 제목을 쓴다")
    @PostMapping("/api/wiki/pages/{pageId}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse fromPage(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "페이지 ID") @PathVariable Long pageId,
                                     @RequestBody(required = false) TemplateRequest req) {
        return templates.createFromPage(userId(jwt), pageId, req);
    }
}
