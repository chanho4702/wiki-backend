package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.AttachmentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentVersionRepository extends JpaRepository<AttachmentVersion, Long> {

    List<AttachmentVersion> findByAttachmentIdOrderByVersionDesc(Long attachmentId);

    Optional<AttachmentVersion> findByAttachmentIdAndVersion(Long attachmentId, Integer version);

    void deleteByAttachmentId(Long attachmentId);
}
