ALTER TABLE attachment
    ADD COLUMN storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN storage_bucket VARCHAR(255),
    ADD COLUMN storage_version VARCHAR(255),
    ADD COLUMN checksum_sha256 VARCHAR(64);

ALTER TABLE attachment
    ADD CONSTRAINT chk_attachment_storage_backend
        CHECK (storage_backend IN ('LOCAL', 'S3')),
    ADD CONSTRAINT chk_attachment_s3_bucket
        CHECK (storage_backend <> 'S3' OR storage_bucket IS NOT NULL),
    ADD CONSTRAINT chk_attachment_checksum_sha256
        CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$');
