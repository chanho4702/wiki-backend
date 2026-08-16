-- Yjs binary 정본은 collaboration-service가 소유한다. wiki-backend는 같은 PostgreSQL transaction에서
-- page revision과 base/generation만 전진시키기 위해 metadata projection을 공유한다.
CREATE TABLE IF NOT EXISTS collaboration_document (
    room text PRIMARY KEY CHECK (room ~ '^page:[1-9][0-9]*$'),
    state bytea NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    base_page_version bigint NOT NULL DEFAULT 1 CHECK (base_page_version > 0),
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE collaboration_document
    ADD COLUMN IF NOT EXISTS base_page_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 1;
