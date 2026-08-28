package com.platform.wikibackend.comment;

import com.platform.wikibackend.comment.dto.CommentCreateRequest;
import com.platform.wikibackend.comment.dto.CommentResponse;
import com.platform.wikibackend.comment.dto.CommentUpdateRequest;
import com.platform.wikibackend.common.ForbiddenException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 페이지 댓글/답글. 읽기·쓰기 모두 스페이스 VIEW를 요구한다 — org-service에 COMMENT action이
 * 생기기 전까지의 기준선이며, 보는 사람은 댓글도 달 수 있다(Confluence 기본과 동일).
 * 수정·삭제는 작성자만, 삭제는 스페이스 ADMIN도 가능하다(moderation).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommentService {

    private final PageCommentRepository comments;
    private final PageRepository pages;
    private final SpaceService spaces;
    private final PermissionClient permissions;
    private final com.platform.wikibackend.notification.NotificationService notificationService;
    private final com.platform.wikibackend.permission.EffectivePermissionService effective;
    private final com.platform.wikibackend.watch.WatchService watches;

    @Transactional(readOnly = true)
    public List<CommentResponse> list(long userId, long pageId) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    public CommentResponse create(long userId, String userName, long pageId, CommentCreateRequest req) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        if (req.parentId() != null) {
            PageComment parent = comments.findById(req.parentId())
                    .orElseThrow(() -> new NotFoundException("부모 코멘트를 찾을 수 없습니다: " + req.parentId()));
            if (!parent.getPageId().equals(pageId)) {
                throw new IllegalArgumentException("부모 코멘트가 같은 페이지에 없습니다");
            }
            if (parent.getParentId() != null) {
                throw new IllegalArgumentException("답글에는 답글을 달 수 없습니다");
            }
        }
        String author = normalizeAuthorName(userName, userId);
        String body = req.body().trim();
        PageComment saved;
        if (req.anchorQuote() != null && !req.anchorQuote().isBlank()) {
            if (req.parentId() != null) {
                throw new IllegalArgumentException("답글에는 본문 구간을 붙일 수 없습니다");
            }
            // 앵커는 **렌더된 본문** 기준이다(사용자가 화면에서 드래그한 텍스트).
            // 마크다운 원문과 대조하지 않는 이유: 서식을 가로지르는 선택(`배포는 **금요일**에`)은
            // 원문에 그대로 없어서 정당한 선택을 거부하게 된다. 서버는 앵커를 보관만 하고,
            // 실제 위치 찾기는 렌더러가 한다 — 못 찾으면 "위치 없음"으로 남긴다.
            int occurrence = req.anchorOccurrence() == null ? 0 : req.anchorOccurrence();
            if (occurrence < 0) {
                throw new IllegalArgumentException("본문 구간 위치가 올바르지 않습니다");
            }
            saved = comments.save(PageComment.inlineOf(
                    pageId, userId, author, body, req.anchorQuote(), occurrence));
        } else {
            saved = comments.save(PageComment.of(pageId, req.parentId(), userId, author, body));
        }
        // 댓글을 달면 그 문서의 대화에 참여한 것이다 — 컨플루언스와 같이 자동 구독한다.
        watches.autoWatch(pageId, userId);
        notificationService.onCommentAdded(userId, page, saved.getBody());
        return CommentResponse.from(saved);
    }

    /** 해결/재개 — 인라인 스레드만 대상이고, VIEW 권한이 있으면 누구나 닫을 수 있다(컨플루언스 규칙). */
    public CommentResponse setResolved(long userId, long commentId, boolean resolved) {
        PageComment comment = requireComment(commentId);
        Page page = requirePage(comment.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        if (!comment.isInline()) {
            throw new IllegalArgumentException("인라인 댓글만 해결할 수 있습니다");
        }
        if (resolved) comment.resolve(userId, Instant.now());
        else comment.reopen();
        return CommentResponse.from(comment);
    }


    public CommentResponse update(long userId, long commentId, CommentUpdateRequest req) {
        PageComment comment = requireComment(commentId);
        Page page = requirePage(comment.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        if (!comment.getAuthorId().equals(userId)) {
            throw new ForbiddenException("본인의 코멘트만 수정할 수 있습니다");
        }
        String trimmed = req.body().trim();
        if (!trimmed.equals(comment.getBody())) {
            comment.edit(trimmed, Instant.now());
        }
        return CommentResponse.from(comment);
    }

    /** 최상위 댓글을 지우면 답글도 함께 사라진다(FK cascade). */
    public void delete(long userId, long commentId) {
        PageComment comment = requireComment(commentId);
        Page page = requirePage(comment.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        boolean isAuthor = comment.getAuthorId().equals(userId);
        if (!isAuthor && !permissions.isAllowed(userId, page.getSpaceId(), WikiAction.ADMIN)) {
            throw new ForbiddenException("본인의 코멘트만 삭제할 수 있습니다");
        }
        // bulk 한 방 — 개별 delete는 PG의 답글 cascade와 충돌하고, H2에는 cascade가 없다.
        comments.deleteWithReplies(commentId);
    }

    private Page requirePage(long pageId) {
        return pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지를 찾을 수 없습니다: " + pageId));
    }

    private PageComment requireComment(long commentId) {
        return comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다: " + commentId));
    }

    private static String normalizeAuthorName(String userName, long userId) {
        if (userName == null || userName.isBlank()) return "사용자 #" + userId;
        String trimmed = userName.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }
}
