package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CollaborationDraftMetadataRepository
        extends JpaRepository<CollaborationDraftMetadata, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from CollaborationDraftMetadata draft where draft.room = :room")
    Optional<CollaborationDraftMetadata> findByRoomForUpdate(@Param("room") String room);
}
