package com.platform.wikibackend.template;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageTemplate;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageTemplateRepository;
import com.platform.wikibackend.space.SpaceService;
import com.platform.wikibackend.template.dto.TemplateRequest;
import com.platform.wikibackend.template.dto.TemplateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 페이지 템플릿(W23) — 그 스페이스가 합의한 문서 형태.
 *
 * 읽기는 스페이스 VIEW, 쓰기는 **ADMIN**이다. 템플릿은 그 스페이스의 모든 사람이 새 문서를
 * 만들 때 마주치는 공용 자산이라, 편집 권한자 아무나 바꾸면 팀의 문서 형식이 조용히 흔들린다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TemplateService {

    /**
     * 스페이스당 템플릿 수 상한 — 고르라고 띄우는 목록이라 길어지면 오히려 못 고른다.
     * 상한에 걸린다는 것은 보통 템플릿이 아니라 문서를 만들어야 한다는 신호다.
     */
    public static final int MAX_PER_SPACE = 50;

    private final PageTemplateRepository templates;
    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final com.platform.wikibackend.audit.AuditService audit;

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        return templates.findBySpaceIdOrderByNameAsc(spaceId).stream()
                .sorted(Comparator.comparing(PageTemplate::sortKey))
                .map(TemplateResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(long userId, long templateId) {
        PageTemplate template = find(templateId);
        spaces.getForView(userId, template.getSpaceId());
        return TemplateResponse.from(template);
    }

    public TemplateResponse create(long userId, long spaceId, TemplateRequest req) {
        spaces.require(userId, spaceId, WikiAction.ADMIN);
        if (templates.findBySpaceIdOrderByNameAsc(spaceId).size() >= MAX_PER_SPACE) {
            throw new ConflictException("템플릿은 스페이스당 " + MAX_PER_SPACE + "개까지입니다");
        }
        PageTemplate template = PageTemplate.of(spaceId, req.name(), req.description(), req.icon(),
                req.content(), userId);
        requireNameFree(spaceId, template.getName(), null);
        PageTemplate saved = templates.save(template);
        audit.record(spaceId, userId, com.platform.wikibackend.domain.AuditAction.TEMPLATE_CREATED,
                "TEMPLATE", saved.getId(), saved.getName(), null);
        return TemplateResponse.from(saved);
    }

    public TemplateResponse update(long userId, long templateId, TemplateRequest req) {
        PageTemplate template = find(templateId);
        spaces.require(userId, template.getSpaceId(), WikiAction.ADMIN);
        template.apply(req.name(), req.description(), req.icon(), req.content(), userId);
        requireNameFree(template.getSpaceId(), template.getName(), templateId);
        audit.record(template.getSpaceId(), userId,
                com.platform.wikibackend.domain.AuditAction.TEMPLATE_UPDATED,
                "TEMPLATE", templateId, template.getName(), null);
        return TemplateResponse.from(templates.save(template));
    }

    public void delete(long userId, long templateId) {
        PageTemplate template = find(templateId);
        spaces.require(userId, template.getSpaceId(), WikiAction.ADMIN);
        audit.record(template.getSpaceId(), userId,
                com.platform.wikibackend.domain.AuditAction.TEMPLATE_DELETED,
                "TEMPLATE", templateId, template.getName(), null);
        templates.delete(template);
    }

    /**
     * 지금 있는 페이지를 템플릿으로 저장한다.
     *
     * 템플릿을 처음부터 쓰는 사람은 드물다 — 이미 잘 쓴 문서 하나가 곧 형식이다. 본문만 가져오고
     * 제목·첨부·라벨은 가져오지 않는다: 템플릿에서 만든 문서마다 같은 제목이 붙으면 곤란하고,
     * 첨부는 그 문서에 딸린 것이지 형식이 아니다.
     */
    public TemplateResponse createFromPage(long userId, long pageId, TemplateRequest req) {
        Page page = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, page.getSpaceId(), WikiAction.ADMIN);
        effective.requireView(userId, page);
        String name = req == null || req.name() == null || req.name().isBlank()
                ? page.getTitle()
                : req.name();
        return create(userId, page.getSpaceId(),
                new TemplateRequest(name,
                        req == null ? null : req.description(),
                        req == null ? page.getIcon() : req.icon(),
                        page.getContent()));
    }

    private void requireNameFree(long spaceId, String name, Long selfId) {
        templates.findBySpaceIdAndName(spaceId, name).ifPresent(existing -> {
            if (selfId == null || !existing.getId().equals(selfId)) {
                throw new ConflictException("같은 이름의 템플릿이 이미 있습니다: " + name);
            }
        });
    }

    private PageTemplate find(long templateId) {
        return templates.findById(templateId)
                .orElseThrow(() -> new NotFoundException("템플릿 없음: " + templateId));
    }
}
