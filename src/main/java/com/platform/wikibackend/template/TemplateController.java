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

import static com.platform.wikibackend.space.SpaceController.userId;

/** 페이지 템플릿 REST(W23). 읽기는 스페이스 VIEW, 쓰기는 ADMIN — 판정은 서비스가 한다. */
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templates;

    @GetMapping("/api/wiki/spaces/{spaceId}/templates")
    public List<TemplateResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId) {
        return templates.list(userId(jwt), spaceId);
    }

    @PostMapping("/api/wiki/spaces/{spaceId}/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long spaceId,
                                   @Valid @RequestBody TemplateRequest req) {
        return templates.create(userId(jwt), spaceId, req);
    }

    @GetMapping("/api/wiki/templates/{templateId}")
    public TemplateResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long templateId) {
        return templates.get(userId(jwt), templateId);
    }

    @PutMapping("/api/wiki/templates/{templateId}")
    public TemplateResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long templateId,
                                   @Valid @RequestBody TemplateRequest req) {
        return templates.update(userId(jwt), templateId, req);
    }

    @DeleteMapping("/api/wiki/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long templateId) {
        templates.delete(userId(jwt), templateId);
    }

    /** 지금 있는 페이지를 템플릿으로 — 이름을 안 주면 그 페이지 제목을 쓴다. */
    @PostMapping("/api/wiki/pages/{pageId}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse fromPage(@AuthenticationPrincipal Jwt jwt, @PathVariable Long pageId,
                                     @RequestBody(required = false) TemplateRequest req) {
        return templates.createFromPage(userId(jwt), pageId, req);
    }
}
