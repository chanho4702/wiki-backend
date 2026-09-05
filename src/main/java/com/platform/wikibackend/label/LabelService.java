package com.platform.wikibackend.label;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageLabel;
import com.platform.wikibackend.domain.PageLink;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.dto.PageTreeItem;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageLinkRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 라벨과 백링크(W21-2).
 *
 * 라벨은 사용자가 직접 붙이고, 링크 그래프는 저장할 때마다 본문에서 다시 뽑는다 —
 * 사용자가 관리하는 데이터가 아니라 본문의 파생물이므로 "저장 시 전량 재작성"이 가장 단순하고
 * 본문과 어긋날 여지가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LabelService {

    /** `[[제목]]` — wiki-front WIKI_LINK_SOURCE와 같은 패턴. 대괄호·개행은 제목에 못 들어간다. */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]\\n]+)\\]\\]");

    /** 코드 펜스와 인라인 코드 — 그 안의 `[[...]]`는 링크가 아니다(렌더러와 같은 규칙). */
    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]*`");

    private static final int MAX_LABELS_PER_PAGE = 30;

    private final PageLabelRepository labels;
    private final PageLinkRepository links;
    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final com.platform.wikibackend.event.EventRelay events;
    private final com.platform.wikibackend.permission.PermissionClient permissions;

    @Transactional(readOnly = true)
    public List<String> list(long userId, long pageId) {
        Page page = owned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return labels.findByPageIdOrderByName(pageId).stream().map(PageLabel::getName).toList();
    }

    /** 전량 교체 — 부분 추가/삭제 API 두 벌을 두는 대신 화면이 최종 상태를 보낸다. */
    public List<String> replace(long userId, long pageId, List<String> raw) {
        Page page = owned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);

        Set<String> normalized = new LinkedHashSet<>();
        for (String value : raw) normalized.add(PageLabel.normalize(value));
        if (normalized.size() > MAX_LABELS_PER_PAGE) {
            throw new IllegalArgumentException("라벨은 페이지당 " + MAX_LABELS_PER_PAGE + "개까지입니다");
        }
        labels.deleteByPageId(pageId);
        labels.flush(); // 같은 트랜잭션에서 같은 이름을 다시 넣을 때 유니크 제약과 부딪히지 않게
        List<PageLabel> saved = normalized.stream()
                .map(name -> PageLabel.of(pageId, name, userId))
                .toList();
        labels.saveAll(saved);
        // 라벨은 색인 문서의 일부다(검색 라벨 필터) — 바뀌면 그 페이지를 다시 색인해야 한다.
        events.afterCommit(WikiEvents.pageUpdated(userId, page));
        return saved.stream().map(PageLabel::getName).toList();
    }

    @Transactional(readOnly = true)
    public List<LabelCountResponse> listInSpace(long userId, long spaceId) {
        spaces.getForView(userId, spaceId);
        return labels.countBySpaceId(spaceId).stream()
                .map(row -> new LabelCountResponse(row.getName(), row.getCount()))
                .toList();
    }

    /** 자동완성 후보 상한 — 고르라고 띄우는 목록이라 길면 오히려 못 고른다. */
    public static final int SUGGEST_LIMIT = 20;

    /**
     * 접근 가능한 스페이스 전체에서 라벨 후보 — 검색 화면의 라벨 필터가 쓴다.
     *
     * 이게 없어서 검색의 라벨 입력이 자유 텍스트였고, 오타를 치면 0건이 나오는데 사용자는
     * 이유를 알 수 없었다. 질의는 저장할 때와 같은 규칙으로 정규화한다.
     */
    @Transactional(readOnly = true)
    public List<LabelCountResponse> suggest(long userId, String rawPrefix) {
        com.platform.wikibackend.permission.AccessScope scope = permissions.accessibleSpaces(userId);
        java.util.Set<Long> spaceIds = scope.all()
                ? spaces.allIds()
                : scope.spaceIds();
        if (spaceIds.isEmpty()) return List.of();

        String prefix = rawPrefix == null || rawPrefix.isBlank() ? "" : PageLabel.normalize(rawPrefix);
        return labels.suggest(spaceIds, prefix, org.springframework.data.domain.Limit.of(SUGGEST_LIMIT)).stream()
                .map(row -> new LabelCountResponse(row.getName(), row.getCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PageTreeItem> pagesWithLabel(long userId, long spaceId, String rawName) {
        spaces.getForView(userId, spaceId);
        List<Long> ids = labels.findPageIdsBySpaceIdAndName(spaceId, PageLabel.normalize(rawName));
        if (ids.isEmpty()) return List.of();
        return visibleOnly(userId, pages.findAllById(ids)).stream().map(PageTreeItem::from).toList();
    }

    /** 백링크 — 같은 스페이스에서 이 페이지 제목을 `[[ ]]`로 가리키는 문서들. */
    @Transactional(readOnly = true)
    public List<PageTreeItem> backlinks(long userId, long pageId) {
        Page page = owned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        List<Page> sources = links.findBacklinks(
                page.getSpaceId(), PageLink.normalizeTitle(page.getTitle()), pageId);
        return visibleOnly(userId, sources).stream().map(PageTreeItem::from).toList();
    }

    /**
     * 본문의 내부 링크를 다시 뽑아 그래프를 갱신한다. 페이지 생성·수정·복원 경로가 호출한다.
     * 저장과 같은 트랜잭션에 두어 "저장은 됐는데 그래프만 옛날 것"인 상태를 만들지 않는다.
     */
    public void reindexLinks(Page page) {
        links.deleteBySourcePageId(page.getId());
        links.flush();
        Set<String> targets = new LinkedHashSet<>(extractTargets(page.getContent()));
        targets.remove(PageLink.normalizeTitle(page.getTitle())); // 자기 자신은 백링크가 아니다
        if (targets.isEmpty()) return;
        List<PageLink> rows = new ArrayList<>();
        for (String target : targets) rows.add(PageLink.of(page.getId(), page.getSpaceId(), target));
        links.saveAll(rows);
    }

    /** 코드 구간을 지운 뒤 `[[제목]]`을 모은다 — 코드 예시 속 대괄호가 링크로 잡히면 안 된다. */
    static Set<String> extractTargets(String markdown) {
        if (markdown == null || markdown.isEmpty()) return Set.of();
        String stripped = CODE.matcher(markdown).replaceAll(" ");
        Set<String> found = new LinkedHashSet<>();
        Matcher m = WIKI_LINK.matcher(stripped);
        while (m.find()) {
            String title = PageLink.normalizeTitle(m.group(1));
            if (!title.isEmpty()) found.add(title);
        }
        return found;
    }

    /** PageService에 의존하면 순환(PageService → LabelService)이라 여기서 직접 읽는다. */
    private Page owned(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
    }

    private List<Page> visibleOnly(long userId, List<Page> candidates) {
        if (candidates.isEmpty()) return List.of();
        Set<Long> visible = effective.viewablePageIds(userId, candidates);
        return candidates.stream().filter(p -> visible.contains(p.getId())).toList();
    }

    @Schema(description = "라벨 하나와 그 라벨이 붙은 페이지 수")
    public record LabelCountResponse(
            @Schema(description = "라벨 이름", example = "배포") String name,
            @Schema(description = "그 라벨이 붙은 페이지 수", example = "12") long count) {
    }
}
