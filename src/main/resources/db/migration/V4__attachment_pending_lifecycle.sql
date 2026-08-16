ALTER TABLE attachment
    ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN confirmed_at TIMESTAMPTZ;

UPDATE attachment
SET confirmed_at = created_at
WHERE lifecycle_status = 'CONFIRMED';

ALTER TABLE attachment
    ADD CONSTRAINT chk_attachment_lifecycle_status
        CHECK (lifecycle_status IN ('PENDING', 'CONFIRMED')),
    ADD CONSTRAINT chk_attachment_confirmed_at
        CHECK ((lifecycle_status = 'PENDING' AND confirmed_at IS NULL)
            OR (lifecycle_status = 'CONFIRMED' AND confirmed_at IS NOT NULL));

CREATE INDEX idx_attachment_pending_created
    ON attachment (created_at, id)
    WHERE lifecycle_status = 'PENDING';
