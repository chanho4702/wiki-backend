package com.platform.wikibackend.permission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 기본 TeamDirectory — org gRPC 연동(증분 2) 전까지 빈 목록(fail-closed). 테스트 페이크가 대체한다. */
@Configuration
public class NoopTeamDirectory {

    @Bean
    @ConditionalOnMissingBean(TeamDirectory.class)
    public TeamDirectory noopTeamDirectory() {
        return userId -> List.of();
    }
}
