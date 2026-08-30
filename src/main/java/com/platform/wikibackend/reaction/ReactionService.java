package com.platform.wikibackend.reaction;

import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.Reaction;
import com.platform.wikibackend.domain.Reaction.TargetType;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.ReactionRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 리액션(W23). 볼 수 있으면 누를 수 있다 — 댓글과 같은 기준선이다.
 *
 * 응답은 언제나 집계(이모지·수·내가 눌렀는지)다. 누가 눌렀는지 목록은 주지 않는다 — 필요해지면
 * 그때 열고, 지금 열면 "누가 안 눌렀나"까지 읽히는 화면이 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReactionService {

    private final ReactionRepository reactions;
    private final PageRepository pages;
    private final PageCommentRepository comments;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;

    @Transactional(readOnly = true)
    public List<ReactionSummary> forPage(long userId, long pageId) {
        requireViewablePage(userId, pageId);
        return summarize(userId, TargetType.PAGE, List.of(pageId)).getOrDefault(pageId, List.of());
    }

    public List<ReactionSummary> setOnPage(long userId, long pageId, String emoji, boolean on) {
        requireViewablePage(userId, pageId);
        toggle(userId, TargetType.PAGE, pageId, emoji, on);
        return summarize(userId, TargetType.PAGE, List.of(pageId)).getOrDefault(pageId, List.of());
    }

    public List<ReactionSummary> setOnComment(long userId, long commentId, String emoji, boolean on) {
        PageComment comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("코멘트를 찾을 수 없습니다: " + commentId));
        requireViewablePage(userId, comment.getPageId());
        toggle(userId, TargetType.COMMENT, commentId, emoji, on);
        return summarize(userId, TargetType.COMMENT, List.of(commentId)).getOrDefault(commentId, List.of());
    }

    /** 댓글 목록용 배치 집계 — 권한은 호출부(댓글 목록)가 이미 봤다. */
    @Transactional(readOnly = true)
    public Map<Long, List<ReactionSummary>> forComments(long userId, Collection<Long> commentIds) {
        return summarize(userId, TargetType.COMMENT, commentIds);
    }

    /** 댓글이 지워질 때 딸린 리액션도 치운다 — 남으면 다음 댓글 id가 그 수를 물려받을 수 있다. */
    public void removeAllForComment(long commentId) {
        reactions.deleteByTargetTypeAndTargetId(TargetType.COMMENT.name(), commentId);
    }

    private void toggle(long userId, TargetType type, long targetId, String emoji, boolean on) {
        Reaction.Key key = new Reaction.Key(type.name(), targetId, userId, Reaction.requireAllowed(emoji));
        if (on) {
            // 이미 있으면 그대로 — 두 번 눌러도 한 번이다(키가 그것을 보장한다).
            if (!reactions.existsById(key)) reactions.save(Reaction.of(type, targetId, userId, emoji));
        } else {
            reactions.deleteById(key);
        }
    }

    /** 이모지 순서는 고정 집합의 순서다 — 수가 바뀔 때마다 칩이 자리를 옮기면 누르려던 것을 놓친다. */
    private Map<Long, List<ReactionSummary>> summarize(long userId, TargetType type, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, Map<String, long[]>> acc = new LinkedHashMap<>(); // [count, mine]
        for (Reaction r : reactions.findAllFor(type.name(), ids)) {
            long[] cell = acc.computeIfAbsent(r.getTargetId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.getEmoji(), k -> new long[2]);
            cell[0]++;
            if (r.getUserId() == userId) cell[1] = 1;
        }
        Map<Long, List<ReactionSummary>> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, long[]>> e : acc.entrySet()) {
            Map<String, long[]> byEmoji = e.getValue();
            List<ReactionSummary> list = Reaction.ALLOWED.stream()
                    .filter(byEmoji::containsKey)
                    .map(emoji -> new ReactionSummary(emoji, byEmoji.get(emoji)[0], byEmoji.get(emoji)[1] == 1))
                    .toList();
            out.put(e.getKey(), list);
        }
        return out;
    }

    private void requireViewablePage(long userId, long pageId) {
        Page page = pages.findById(pageId)
                .orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
    }

    public record ReactionSummary(String emoji, long count, boolean reacted) {}
}
