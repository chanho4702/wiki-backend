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
 *
 * 이미지: 렌더러는 어떤 URL도 열지 않는다(SSRF, 감사 W-06). 위키 첨부 이미지만 저장소에서 바로 읽어 넣는다.
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
    private final com.platform.wikibackend.repository.AttachmentRepository attachments;
    private final com.platform.wikibackend.attachment.AttachmentStorageRouter storage;

    /** PDF에 심는 첨부 이미지 한 장의 상한 — 넘으면 대체 텍스트. 업로드 상한(기본 20MB)보다 작게 둔다. */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** openhtmltopdf가 플러그인 없이 그리는 래스터 형식만. webp·svg는 대체 텍스트로 남는다. */
    private static final java.util.Set<String> EMBEDDABLE = java.util.Set.of("image/png", "image/jpeg", "image/gif");

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
        return new Export(root.getTitle() + ".pdf", renderer.render(root.getTitle(), docs, id -> image(userId, id)));
    }

    /**
     * 위키 첨부 이미지를 저장소에서 직접 읽어 data URI로(감사 W-06). 렌더러가 URL을 열지 않으므로 이것이
     * 이미지가 PDF에 들어가는 유일한 길이다. 권한은 첨부 내려받기와 같은 판정(그 첨부가 달린 문서의
     * space VIEW + effective VIEW) — 남의 문서 첨부 id를 본문에 적어 빼내지 못한다.
     * 볼 수 없거나 없는 첨부는 조용히 빈 값(대체 텍스트), org 불능(503)은 그대로 올린다.
     */
    private java.util.Optional<String> image(long userId, long attachmentId) {
        // 예외로 거르지 않는다 — 트랜잭션 안의 서비스 호출이 403을 던지면 내보내기 트랜잭션 전체가
        // rollback-only가 된다. 첨부 내려받기와 같은 판정을 값으로 한다.
        com.platform.wikibackend.domain.Attachment a = attachments.findById(attachmentId).orElse(null);
        if (a == null) return java.util.Optional.empty();
        Page page = pages.findById(a.getPageId()).orElse(null);
        if (page == null || !spaces.canView(userId, page.getSpaceId()) || !effective.canView(userId, page)) {
            return java.util.Optional.empty();
        }
        if (!EMBEDDABLE.contains(a.getContentType()) || a.getSizeBytes() > MAX_IMAGE_BYTES) {
            return java.util.Optional.empty();
        }
        org.springframework.core.io.Resource resource = storage.open(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion());
        try (java.io.InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) return java.util.Optional.empty();
            return java.util.Optional.of("data:" + a.getContentType() + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(bytes));
        } catch (java.io.IOException e) {
            return java.util.Optional.empty(); // 저장소에서 못 읽은 이미지 하나로 내보내기 전체를 실패시키지 않는다
        }
    }
}
