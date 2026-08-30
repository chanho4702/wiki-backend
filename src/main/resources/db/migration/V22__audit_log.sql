-- W23 감사 로그.
--
-- "누가 이 문서를 지웠나", "언제부터 이 페이지가 잠겼나"를 확인할 방법이 없었다. 이력이
-- 남는 것은 본문 리비전뿐이고, 지우기·권한 변경처럼 되돌리기 어려운 조작은 흔적이 없었다.
--
-- 대상 이름을 함께 저장한다(target_label). id만 남기면 지워진 문서의 기록이 숫자만 남아
-- 아무도 못 알아본다 — 감사 로그가 읽히려면 그때 그 이름이 필요하다.
CREATE TABLE audit_log (
    id           BIGSERIAL    PRIMARY KEY,
    space_id     BIGINT       NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    actor_id     BIGINT       NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    target_type  VARCHAR(20)  NOT NULL,
    -- 대상 식별자. 지워진 대상도 기록은 남으므로 FK를 걸지 않는다.
    target_id    BIGINT,
    target_label VARCHAR(255) NOT NULL,
    -- 사람이 읽을 보조 설명(무엇에서 무엇으로 바뀌었는지 등). 구조화 대상이 아니다.
    detail       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_space ON audit_log (space_id, created_at DESC, id DESC);
