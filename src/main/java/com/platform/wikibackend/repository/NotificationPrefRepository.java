package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.NotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPrefRepository extends JpaRepository<NotificationPref, Long> {
}
