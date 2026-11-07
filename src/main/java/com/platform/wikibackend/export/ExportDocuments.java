package com.platform.wikibackend.export;

import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.page.TreeService;
import com.platform.wikibackend.page.dto.PageNode;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 파일 내보내기(PDF·Word)가 함께 쓰는 "무엇을 담을 수 있는가" 판정 — 렌더러와 무관한 부분.
 *
 * 문서 수집: 루트는 스페이스 VIEW + effective VIEW(초안 가시성 포함)를 통과해야 하고, 하위 포함 시 순서·
 * 가시성은 트리 API와 같은 규칙(TreeService.descendants)이다. 안 보이는 문서는 조용히 빠진다 —
 * 내보내기가 권한을 넓히면 안 된다.
 *
 * 이미지: 렌더러는 어떤 URL도 열지 않는다(SSRF, 감사 W-06). 위키 첨부 이미지만 저장소에서 바로 읽는다.
 * 호출 쪽 트랜잭션(readOnly) 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
class ExportDocuments {

    /** 한 파일에 담는 문서 상한 — 이보다 크면 파일이 아니라 스페이스 내보내기가 맞다. */
    static final int MAX_DOCS = 100;

    /** 파일에 심는 첨부 이미지 한 장의 상한 — 넘으면 대체 텍스트. 업로드 상한(기본 20MB)보다 작게 둔다. */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** PDF·Word 둘 다 플러그인 없이 그리는 래스터 형식만. webp·svg는 대체 텍스트로 남는다. */
    static final Set<String> EMBEDDABLE = Set.of("image/png", "image/jpeg", "image/gif");

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final TreeService tree;
    private final AttachmentRepository attachments;
    private final AttachmentStorageRouter storage;

    record Doc(String title, String markdown) {
    }

    record Collected(String rootTitle, List<Doc> docs) {
    }

    record Image(String contentType, byte[] bytes) {
    }

    Collected collect(long userId, long pageId, boolean includeChildren) {
        Page root = pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, root.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, root);

        List<Doc> docs = new ArrayList<>();
        docs.add(new Doc(root.getTitle(), root.getContent()));
        if (includeChildren) {
            List<PageNode> nodes = tree.descendants(userId, pageId); // 트리 순서 + 가시성 필터
            Map<Long, Page> byId = pages.findAllById(nodes.stream().map(PageNode::id).toList()).stream()
                    .collect(Collectors.toMap(Page::getId, Function.identity()));
            for (PageNode node : nodes) {
                if (docs.size() >= MAX_DOCS) break;
                Page page = byId.get(node.id());
                if (page == null || page.isArchived()) continue;
                docs.add(new Doc(page.getTitle(), page.getContent()));
            }
        }
        return new Collected(root.getTitle(), docs);
    }

    /**
     * 위키 첨부 이미지를 저장소에서 직접 읽는다(감사 W-06). 권한은 첨부 내려받기와 같은 판정(그 첨부가
     * 달린 문서의 space VIEW + effective VIEW) — 남의 문서 첨부 id를 본문에 적어 빼내지 못한다.
     * 볼 수 없거나 없는 첨부·이미지가 아닌 첨부·상한 초과는 빈 값(대체 텍스트), org 불능(503)은 그대로 올린다.
     */
    Optional<Image> image(long userId, long attachmentId) {
        // 예외로 거르지 않는다 — 트랜잭션 안의 서비스 호출이 403을 던지면 내보내기 트랜잭션 전체가
        // rollback-only가 된다. 첨부 내려받기와 같은 판정을 값으로 한다.
        Attachment a = attachments.findById(attachmentId).orElse(null);
        if (a == null) return Optional.empty();
        Page page = pages.findById(a.getPageId()).orElse(null);
        if (page == null || !spaces.canView(userId, page.getSpaceId()) || !effective.canView(userId, page)) {
            return Optional.empty();
        }
        if (!EMBEDDABLE.contains(a.getContentType()) || a.getSizeBytes() > MAX_IMAGE_BYTES) {
            return Optional.empty();
        }
        Resource resource = storage.open(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion());
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) return Optional.empty();
            return Optional.of(new Image(a.getContentType(), bytes));
        } catch (IOException e) {
            return Optional.empty(); // 저장소에서 못 읽은 이미지 하나로 내보내기 전체를 실패시키지 않는다
        }
    }
}
