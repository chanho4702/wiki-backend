package com.platform.wikibackend.task;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** 액션 아이템 REST(W23). "내 작업"은 스페이스를 가로지르므로 스페이스 경로 아래에 두지 않는다. */
@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService tasks;

    @GetMapping("/api/wiki/tasks/mine")
    public List<TaskService.TaskView> mine(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "false") boolean done) {
        return tasks.mine(userId(jwt), done);
    }

    /** 체크 토글 — 본문의 그 줄을 다시 쓰는 편집이다(리비전이 남는다). */
    @PutMapping("/api/wiki/pages/{pageId}/tasks/{lineNo}")
    public TaskService.TaskView setDone(@AuthenticationPrincipal Jwt jwt, @PathVariable long pageId,
                                        @PathVariable int lineNo, @RequestBody DoneRequest req) {
        return tasks.setDone(userId(jwt), pageId, lineNo, req.done());
    }

    public record DoneRequest(boolean done) {}
}
