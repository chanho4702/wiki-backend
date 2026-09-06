package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.NotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationPrefRepository extends JpaRepository<NotificationPref, Long> {
    /**
     * 주소로 거르지 않는다 — 정본은 org 디렉터리이고 행의 email은 폴백일 뿐이라, 스냅샷이 없다는
     * 이유로 여기서 빼면 org가 아는 주소로도 요약이 못 나간다(주소 없음 판정은 발송 직전에 한다).
     */
    List<NotificationPref> findByEmailModeAndEmailEnabledTrue(NotificationPref.EmailMode mode);
}
