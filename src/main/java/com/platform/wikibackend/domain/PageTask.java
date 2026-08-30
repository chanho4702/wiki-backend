package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 액션 아이템(W23) — 본문 체크박스 한 줄의 파생 행. 저장할 때마다 본문에서 다시 만든다.
 */
@Entity
@Table(name = "page_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageTask {

    public static final int MAX_TEXT = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(nullable = false, length = MAX_TEXT)
    private String text;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(nullable = false)
    private boolean done;

    public static PageTask of(long pageId, int lineNo, String text, Long assigneeId, LocalDate dueDate, boolean done) {
        PageTask t = new PageTask();
        t.pageId = pageId;
        t.lineNo = lineNo;
        t.text = text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
        t.assigneeId = assigneeId;
        t.dueDate = dueDate;
        t.done = done;
        return t;
    }
}
