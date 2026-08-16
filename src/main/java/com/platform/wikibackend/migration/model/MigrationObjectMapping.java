package com.platform.wikibackend.migration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "migration_object_map")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationObjectMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, unique = true, length = 64, updatable = false)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private MigrationProvider provider;

    @Column(name = "source_instance_id", nullable = false, length = 255, updatable = false)
    private String sourceInstanceId;

    @Column(name = "external_object_id", nullable = false, length = 512, updatable = false)
    private String externalObjectId;

    @Column(name = "source_version", length = 100)
    private String sourceVersion;

    @Column(name = "source_checksum", nullable = false, length = 64)
    private String sourceChecksum;

    @Column(name = "target_page_id")
    private Long targetPageId;

    @Column(name = "last_job_id")
    private Long lastJobId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public static MigrationObjectMapping create(MigrationProvider provider, String sourceInstanceId,
                                                String externalObjectId, String sourceVersion,
                                                String sourceChecksum, Long targetPageId, Long lastJobId) {
        MigrationObjectMapping mapping = new MigrationObjectMapping();
        mapping.provider = MigrationSourceKey.require(provider, "provider");
        mapping.sourceInstanceId = MigrationSourceKey.requireText(sourceInstanceId, "sourceInstanceId", 255);
        mapping.externalObjectId = MigrationSourceKey.requireText(externalObjectId, "externalObjectId", 512);
        mapping.sourceKey = MigrationSourceKey.object(provider, sourceInstanceId, externalObjectId);
        mapping.update(sourceVersion, sourceChecksum, targetPageId, lastJobId);
        return mapping;
    }

    public static String sourceKeyFor(MigrationProvider provider, String sourceInstanceId,
                                      String externalObjectId) {
        return MigrationSourceKey.object(provider, sourceInstanceId, externalObjectId);
    }

    public void update(String sourceVersion, String sourceChecksum, Long targetPageId, Long lastJobId) {
        this.sourceVersion = sourceVersion == null ? null
                : MigrationSourceKey.requireText(sourceVersion, "sourceVersion", 100);
        this.sourceChecksum = MigrationSourceKey.requireChecksum(sourceChecksum);
        this.targetPageId = MigrationSourceKey.require(targetPageId, "targetPageId");
        this.lastJobId = MigrationSourceKey.require(lastJobId, "lastJobId");
    }
}
