package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.NotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationPrefRepository extends JpaRepository<NotificationPref, Long> {
    List<NotificationPref> findByEmailModeAndEmailEnabledTrueAndEmailIsNotNull(NotificationPref.EmailMode mode);
}
