package com.platform.wikibackend;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 격리용 페이지 정리.
 *
 * Page에 걸린 `@SQLRestriction("deleted_at is null")` 때문에 `pages.deleteAll()`은 휴지통 행을
 * 보지 못해 남긴다. 그 잔여 행이 다음 테스트로 새는 것을 막으려면 네이티브 삭제가 필요하다.
 *
 * 이 목적만을 위한 메서드를 운영 리포지토리에 두지 않으려고 테스트 쪽으로 뺐다 — 운영 코드에는
 * "휴지통을 포함해 전부 지운다"는 수단이 존재하지 않아야 한다.
 */
public final class TestPages {

    private TestPages() {
    }

    public static void deleteAll(JdbcTemplate jdbc) {
        jdbc.update("delete from page");
    }
}
