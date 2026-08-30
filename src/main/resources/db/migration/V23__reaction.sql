-- W23 리액션.
--
-- "잘 봤다"를 표현할 방법이 댓글뿐이었다. 한마디 남기자고 댓글을 쓰면 스레드가 잡음으로 차고,
-- 그래서 아무도 안 남긴다 — 문서가 읽히는지 작성자가 알 길이 없었다.
--
-- 문서와 댓글이 같은 표를 쓴다(target_type). 둘을 나누면 집계·토글 코드가 두 벌이 된다.
-- 사용자·대상·이모지가 키라 같은 이모지를 두 번 누를 수 없다 — 토글이 곧 이 제약이다.
CREATE TABLE reaction (
    target_type VARCHAR(10) NOT NULL,
    target_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    emoji       VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type, target_id, user_id, emoji)
);
