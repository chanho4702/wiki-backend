package com.platform.wikibackend.export;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.TreeService;
import com.platform.wikibackend.page.dto.PageNode;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 페이지 PDF 내보내기(W26) — 문서(선택 시 하위 포함)를 한 PDF로.
 *
 * 프론트 인쇄(window.print)로 대신하던 것을 서버 렌더로 바꿨다(2026-08-31 사용자 요청): 인쇄는
 * 브라우저·용지 설정에 따라 결과가 제각각이고, 하위 문서 묶음을 한 파일로 만들 수 없었다.
 *
 * 하위 포함 시 순서·가시성은 트리 API와 같은 규칙이다(TreeService.descendants — effective VIEW 필터).
 * 안 보이는 문서는 조용히 빠진다: 내보내기가 권한을 넓히면 안 된다.
 */
@Service
@RequiredArgsConstructor
public class PdfExportService {

    /** 한 PDF에 담는 문서 상한 — 이보다 크면 파일이 아니라 스페이스 내보내기가 맞다. */
    static final int MAX_DOCS = 100;

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final TreeService tree;
    private final MarkdownPdfRenderer renderer;

    public record Export(String filename, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Export export(long userId, long pageId, boolean includeChildren) {
        Page root = pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, root.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, root);

        List<MarkdownPdfRenderer.Doc> docs = new ArrayList<>();
        docs.add(new MarkdownPdfRenderer.Doc(root.getTitle(), root.getContent()));
        if (includeChildren) {
            List<PageNode> nodes = tree.descendants(userId, pageId); // 트리 순서 + 가시성 필터
            Map<Long, Page> byId = pages.findAllById(nodes.stream().map(PageNode::id).toList()).stream()
                    .collect(Collectors.toMap(Page::getId, Function.identity()));
            for (PageNode node : nodes) {
                if (docs.size() >= MAX_DOCS) break;
                Page page = byId.get(node.id());
                if (page == null || page.isArchived()) continue;
                docs.add(new MarkdownPdfRenderer.Doc(page.getTitle(), page.getContent()));
            }
        }
        return new Export(root.getTitle() + ".pdf", renderer.render(root.getTitle(), docs));
    }
}
