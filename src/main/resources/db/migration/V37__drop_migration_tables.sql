-- 이관 엔진이 migration-service로 나갔다(W29 X4). 위키에는 "받아 넣는" import API만 남는다.
--
-- 이 테이블들은 잡 원장이다 — 무엇을 어디서 가져와 어디에 넣었는지. 그 원장은 이제 엔진 쪽
-- migrationdb가 가진다. 위키가 사본을 들고 있으면 두 원장이 조용히 갈라지고, 위키는 그것을
-- 갱신할 코드를 더 이상 갖지 않으므로 남은 행은 그 즉시 거짓이 된다.
--
-- 데이터 이전은 없다. 지금까지 돈 잡은 dev 전용이고, 엔진의 V1이 같은 모양을 새 번호로 다시
-- 만든다. 옮길 것이 있었다면 이 파일이 아니라 엔진 쪽 백필 스크립트가 할 일이다.
--
-- page.imported_author_name / imported_source_url(V36)은 **남긴다**. 그 두 컬럼은 잡 원장이
-- 아니라 문서 자신의 속성이다 — "이 문서의 원본 작성자는 우리 계정에 없다"는 사실은 이관이
-- 끝난 뒤에도 화면이 계속 보여줘야 하고, import API가 지금도 채운다.

-- FK 역순으로 지운다. migration_issue → migration_item(job_id, id) 복합 FK,
-- migration_payload → migration_item, migration_source·migration_object_map → migration_job.
DROP TABLE IF EXISTS migration_issue;
DROP TABLE IF EXISTS migration_payload;
DROP TABLE IF EXISTS migration_source;
DROP TABLE IF EXISTS migration_object_map;
DROP TABLE IF EXISTS migration_item;
DROP TABLE IF EXISTS migration_job;
