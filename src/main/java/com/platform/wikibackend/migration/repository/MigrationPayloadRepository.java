package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationPayload;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MigrationPayloadRepository extends JpaRepository<MigrationPayload, Long> {

    Optional<MigrationPayload> findByItemIdAndKind(Long itemId, MigrationPayloadKind kind);
}
