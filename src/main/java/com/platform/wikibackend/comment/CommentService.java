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

    @Transactional(readOnly = true)
    public List<CommentResponse> list(long userId, long pageId) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        return comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    public CommentResponse create(long userId, String userName, long pageId, CommentCreateRequest req) {
        Page page = requirePage(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
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
        PageComment saved = comments.save(PageComment.of(
                pageId, req.parentId(), userId, normalizeAuthorName(userName, userId), req.body().trim()));
        notificationService.onCommentAdded(userId, page, saved.getBody());
        return CommentResponse.from(saved);
    }

    public CommentResponse update(long userId, long commentId, CommentUpdateRequest req) {
        PageComment comment = requireComment(commentId);
        Page page = requirePage(comment.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
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
