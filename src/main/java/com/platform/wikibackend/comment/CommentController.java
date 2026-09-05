package com.platform.wikibackend.comment;

import com.platform.wikibackend.comment.dto.CommentCreateRequest;
import com.platform.wikibackend.comment.dto.CommentResponse;
import com.platform.wikibackend.comment.dto.CommentUpdateRequest;
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
import io.swagger.v3.oas.annotations.Parameter;

import static com.platform.wikibackend.space.SpaceController.userId;

@Tag(name = "Comments", description = "페이지 댓글과 본문 구간에 붙는 인라인 스레드.")
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService comments;

    @Operation(summary = "페이지의 댓글을 조회한다")
    @GetMapping("/api/wiki/pages/{pageId}/comments")
    public List<CommentResponse> list(@Parameter(description = "페이지 ID") @PathVariable long pageId, @AuthenticationPrincipal Jwt jwt) {
        return comments.list(userId(jwt), pageId);
    }

    @Operation(summary = "페이지에 댓글을 단다 — 인용 구간을 주면 인라인 댓글이다")
    @PostMapping("/api/wiki/pages/{pageId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@Parameter(description = "페이지 ID") @PathVariable long pageId,
                                  @Valid @RequestBody CommentCreateRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        return comments.create(userId(jwt), jwt.getClaimAsString("name"), pageId, req);
    }

    @Operation(summary = "댓글 본문을 수정한다 — 작성자 본인만")
    @PutMapping("/api/wiki/comments/{commentId}")
    public CommentResponse update(@Parameter(description = "댓글 ID") @PathVariable long commentId,
                                  @Valid @RequestBody CommentUpdateRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        return comments.update(userId(jwt), commentId, req);
    }

    /** 해결/재개 — 인라인 스레드 전용. 본문은 안 바뀌므로 PUT /comments/{id}(본문 수정)와 분리한다. */
    @Operation(summary = "인라인 댓글 스레드를 해결 처리하거나 되돌린다")
    @PutMapping("/api/wiki/comments/{commentId}/resolved")
    public CommentResponse setResolved(@Parameter(description = "댓글 ID") @PathVariable long commentId,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @RequestBody ResolvedRequest req) {
        return comments.setResolved(userId(jwt), commentId, req.resolved());
    }

    public record ResolvedRequest(boolean resolved) {}

    @Operation(summary = "댓글을 삭제한다 — 작성자 또는 스페이스 ADMIN")
    @DeleteMapping("/api/wiki/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "댓글 ID") @PathVariable long commentId, @AuthenticationPrincipal Jwt jwt) {
        comments.delete(userId(jwt), commentId);
    }
}
