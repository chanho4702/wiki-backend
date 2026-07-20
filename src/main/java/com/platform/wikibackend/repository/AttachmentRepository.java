package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByPageId(Long pageId);
}
