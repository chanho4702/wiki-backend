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

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService comments;

    @GetMapping("/api/wiki/pages/{pageId}/comments")
    public List<CommentResponse> list(@PathVariable long pageId, @AuthenticationPrincipal Jwt jwt) {
        return comments.list(userId(jwt), pageId);
    }

    @PostMapping("/api/wiki/pages/{pageId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@PathVariable long pageId,
                                  @Valid @RequestBody CommentCreateRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        return comments.create(userId(jwt), jwt.getClaimAsString("name"), pageId, req);
    }

    @PutMapping("/api/wiki/comments/{commentId}")
    public CommentResponse update(@PathVariable long commentId,
                                  @Valid @RequestBody CommentUpdateRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        return comments.update(userId(jwt), commentId, req);
    }

    @DeleteMapping("/api/wiki/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long commentId, @AuthenticationPrincipal Jwt jwt) {
        comments.delete(userId(jwt), commentId);
    }
}
