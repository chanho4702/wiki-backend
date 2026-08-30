package com.platform.wikibackend.task;

import com.platform.wikibackend.common.ConflictException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageTask;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageTaskRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 액션 아이템(W23) — 본문 체크박스의 파생 표와 "내 작업" 목록.
 *
 * 체크박스 목록은 있었지만 "누가 언제까지"가 없어서 회의록의 할 일이 회의록 안에서만 살았다.
 * 새 문법을 들이지 않는다: 항목 안의 멘션이 담당자, 날짜 요소가 기한이다.
 *
 *   - [ ] 배포 공지 [@김철수](user:12) [2026-09-01](date:2026-09-01)
 *
 * 본문의 파생물이라(백링크·라벨과 같은 계열) 저장할 때마다 통째로 다시 만든다 — 줄이 밀리면
 * 줄 번호로 찾는 토글이 엉뚱한 줄을 건드리므로, 표가 항상 현재 본문과 같아야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private static final Pattern TASK_LINE = Pattern.compile("^\\s*[-*+]\\s+\\[( |x|X)\\]\\s+(.*\\S)\\s*$");
    private static final Pattern MENTION = Pattern.compile("\\[@[^\\]]*\\]\\(user:(\\d+)\\)");
    private static final Pattern DATE = Pattern.compile("\\[[^\\]]*\\]\\(date:(\\d{4}-\\d{2}-\\d{2})\\)");
    /** 표시용 텍스트 — 멘션·날짜 링크를 걷어내고 이름·날짜만 남긴다. */
    private static final Pattern MENTION_TO_TEXT = Pattern.compile("\\[(@[^\\]]*)\\]\\(user:\\d+\\)");
    private static final Pattern DATE_TO_TEXT = Pattern.compile("\\[([^\\]]*)\\]\\(date:\\d{4}-\\d{2}-\\d{2}\\)");

    private final PageTaskRepository tasks;
    private final PageRepository pages;
    private final PageRevisionRepository revisions;
    private final SpaceRepository spaces;
    private final PermissionClient permissions;
    private final EffectivePermissionService effective;
    private final com.platform.wikibackend.common.ActorNames actorNames;
    private final EventRelay events;

    /** 본문이 바뀐 뒤 호출 — 그 페이지의 작업 표를 본문에서 다시 만든다. */
    public void sync(Page page) {
        tasks.deleteByPageId(page.getId());
        tasks.saveAll(parse(page.getId(), page.getContent()));
    }

    static List<PageTask> parse(long pageId, String content) {
        List<PageTask> out = new ArrayList<>();
        String[] lines = content == null ? new String[0] : content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TASK_LINE.matcher(lines[i]);
            if (!m.matches()) continue;
            String raw = m.group(2);
            Matcher user = MENTION.matcher(raw);
            Long assignee = user.find() ? Long.parseLong(user.group(1)) : null;
            Matcher date = DATE.matcher(raw);
            LocalDate due = null;
            if (date.find()) {
                try {
                    due = LocalDate.parse(date.group(1));
                } catch (DateTimeParseException ignored) {
                    // 날짜 요소가 깨진 값이면 기한 없음으로 둔다 — 한 줄 때문에 저장이 실패하면 안 된다
                }
            }
            String text = DATE_TO_TEXT.matcher(MENTION_TO_TEXT.matcher(raw).replaceAll("$1")).replaceAll("$1").trim();
            out.add(PageTask.of(pageId, i + 1, text, assignee, due, !m.group(1).equals(" ")));
        }
        return out;
    }

    /**
     * 내 작업 — 담당자가 나인 것. 읽을 때마다 지금 권한으로 거른다(별표와 같은 이유: 문서가
     * 잠긴 뒤에도 항목 텍스트가 남으면 그것만으로 샌다).
     */
    @Transactional(readOnly = true)
    public List<TaskView> mine(long userId, boolean done) {
        List<PageTask> rows = tasks.findMine(userId, done);
        if (rows.isEmpty()) return List.of();
        Map<Long, Page> pageById = pages.findAllById(rows.stream().map(PageTask::getPageId).distinct().toList())
                .stream().collect(Collectors.toMap(Page::getId, Function.identity()));
        AccessScope scope = permissions.accessibleSpaces(userId);
        List<Page> inSpaces = pageById.values().stream()
                .filter(p -> !p.isArchived() && scope.contains(p.getSpaceId())).toList();
        Set<Long> visible = effective.viewablePageIds(userId, inSpaces);
        Map<Long, Space> spaceById = spaces.findAllById(
                        inSpaces.stream().map(Page::getSpaceId).distinct().toList()).stream()
                .collect(Collectors.toMap(Space::getId, Function.identity()));
        return rows.stream()
                .filter(t -> visible.contains(t.getPageId()))
                .map(t -> {
                    Page p = pageById.get(t.getPageId());
                    Space s = spaceById.get(p.getSpaceId());
                    return new TaskView(t.getPageId(), p.getSpaceId(), s == null ? null : s.getName(),
                            p.getTitle(), t.getLineNo(), t.getText(), t.getAssigneeId(),
                            t.getDueDate() == null ? null : t.getDueDate().toString(), t.isDone());
                })
                .toList();
    }

    /**
     * 체크 토글 — 본문의 그 줄을 다시 쓴다. 편집이므로 EDIT 권한·리비전·버전이 모두 따라간다.
     * 줄이 이미 다른 내용이면(동시 편집) 엉뚱한 줄을 건드리지 않고 409로 끝낸다.
     */
    public TaskView setDone(long userId, long pageId, int lineNo, boolean done) {
        Page page = pages.findById(pageId).orElseThrow(() -> new NotFoundException("페이지 없음: " + pageId));
        if (!permissions.isAllowed(userId, page.getSpaceId(), WikiAction.EDIT)) {
            throw new com.platform.wikibackend.common.ForbiddenException("EDIT 권한이 필요합니다 (space " + page.getSpaceId() + ")");
        }
        effective.requireEdit(userId, page);
        if (page.isArchived()) throw new ConflictException("보관된 문서는 편집할 수 없습니다. 먼저 보관을 해제하세요");

        String[] lines = page.getContent().split("\n", -1);
        if (lineNo < 1 || lineNo > lines.length || !TASK_LINE.matcher(lines[lineNo - 1]).matches()) {
            throw new ConflictException("그 줄은 더 이상 작업 항목이 아닙니다. 문서를 새로고침하세요");
        }
        lines[lineNo - 1] = lines[lineNo - 1].replaceFirst("\\[( |x|X)\\]", done ? "[x]" : "[ ]");
        page.edit(page.getTitle(), String.join("\n", lines), userId);
        revisions.save(PageRevision.snapshotOf(page, done ? "작업 완료 표시" : "작업 다시 열기").withEditorName(actorNames.current()));
        sync(page);
        events.afterCommit(WikiEvents.pageUpdated(userId, page));

        PageTask task = tasks.findByPageIdOrderByLineNoAsc(pageId).stream()
                .filter(t -> t.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalStateException("토글 직후 작업 행이 없습니다: " + pageId + ":" + lineNo));
        Space s = spaces.findById(page.getSpaceId()).orElse(null);
        return new TaskView(pageId, page.getSpaceId(), s == null ? null : s.getName(), page.getTitle(),
                lineNo, task.getText(), task.getAssigneeId(),
                task.getDueDate() == null ? null : task.getDueDate().toString(), task.isDone());
    }

    public record TaskView(Long pageId, Long spaceId, String spaceName, String pageTitle, int lineNo,
                           String text, Long assigneeId, String dueDate, boolean done) {
    }
}
