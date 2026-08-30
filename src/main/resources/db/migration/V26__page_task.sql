-- W23 액션 아이템.
--
-- 체크박스 목록은 있었지만 "누가 언제까지"가 없어서 회의록의 할 일이 회의록 안에서만 살았다 —
-- 내 몫이 어느 문서에 흩어져 있는지 볼 방법이 없었다.
--
-- 본문의 파생물이다(백링크·라벨과 같은 계열): 저장할 때마다 본문을 훑어 이 표를 통째로
-- 다시 만든다. 담당자는 항목 안의 멘션(`[@이름](user:id)`), 기한은 날짜 요소(`[…](date:…)`)다 —
-- 새 문법을 들이지 않고 이미 있는 두 요소를 조합한다.
CREATE TABLE page_task (
    id          BIGSERIAL    PRIMARY KEY,
    page_id     BIGINT       NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    -- 본문에서의 줄 번호(1부터). 체크 토글이 이 줄을 다시 쓴다.
    line_no     INT          NOT NULL,
    text        VARCHAR(500) NOT NULL,
    assignee_id BIGINT,
    due_date    DATE,
    done        BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT uq_page_task_line UNIQUE (page_id, line_no)
);
CREATE INDEX idx_page_task_assignee ON page_task (assignee_id, done, due_date);
