package com.platform.wikibackend.migration.confluence.restriction;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.permission.PageRestrictionService;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 원본 페이지 제한을 우리 페이지 제한으로 옮긴다(M2 §4.3).
 *
 * 핵심 규칙은 하나다 — **fail-closed**(ADR-W14-07). 원본에서 몇 사람만 보던 문서를 우리 쪽에서
 * 대조하지 못했다고 전원 공개로 두면, 이관은 "성공"으로 끝나고 비공개 문서가 조용히 열린다.
 * 그래서 대조 실패한 주체가 하나라도 있으면 그 목록은 **잡 요청자 단독**이 되고 ERROR로 보고한다.
 * 잘못 잠긴 문서는 관리자가 열 수 있지만 잘못 열린 문서는 되돌릴 수 없다.
 *
 * 원본에 제한이 아예 없으면 아무것도 하지 않는다 — 우리 쪽에서 손으로 걸어 둔 제한을 이관이
 * 지우면 안 된다.
 */
@Component
@RequiredArgsConstructor
public class MigrationRestrictionApplier {

    private final MigrationPrincipalResolver resolver;
    private final PageRestrictionService restrictions;

    /**
     * @param content   스냅샷의 content 노드(EXTRACT가 눕혀 둔 restrictions를 읽는다)
     * @param requester 잡 요청자 — 대조 실패 시 유일하게 남는 주체
     */
    public List<MigrationStageIssue> apply(JsonNode content, long pageId, long requester) {
        JsonNode source = content.path("restrictions");
        Group view = resolveGroup(source.path("read"), requester);
        Group edit = resolveGroup(source.path("update"), requester);
        if (view.absent() && edit.absent()) {
            return List.of();
        }
        List<MigrationStageIssue> issues = new ArrayList<>();
        issues.addAll(view.issues());
        issues.addAll(edit.issues());
        restrictions.replaceImported(pageId, view.principals(), edit.principals(), requester);
        return issues;
    }

    private Group resolveGroup(JsonNode source, long requester) {
        List<RestrictionPrincipal> mapped = new ArrayList<>();
        List<MigrationStageIssue> issues = new ArrayList<>();
        boolean present = false;
        boolean unmapped = false;

        for (JsonNode user : source.path("users")) {
            present = true;
            MigrationPrincipalResolver.SourceUser sourceUser = new MigrationPrincipalResolver.SourceUser(
                    user.path("username").asText(""), user.path("displayName").asText(""),
                    user.path("email").asText(""));
            Optional<Long> resolved = resolver.resolveUser(sourceUser);
            if (resolved.isEmpty()) {
                unmapped = true;
                issues.add(MigrationStageIssue.error(ConfluenceDcIssues.RESTRICTION_PRINCIPAL_UNMAPPED,
                        "user:" + sourceUser.label()));
                continue;
            }
            mapped.add(new RestrictionPrincipal(PageRestriction.PrincipalType.USER.name(), resolved.get()));
        }
        for (JsonNode group : source.path("groups")) {
            present = true;
            String name = group.asText("");
            Optional<Long> resolved = resolver.resolveTeam(name);
            if (resolved.isEmpty()) {
                unmapped = true;
                issues.add(MigrationStageIssue.error(ConfluenceDcIssues.RESTRICTION_PRINCIPAL_UNMAPPED,
                        "group:" + (name.isBlank() ? "unknown" : name)));
                continue;
            }
            mapped.add(new RestrictionPrincipal(PageRestriction.PrincipalType.TEAM.name(), resolved.get()));
        }

        if (!present) {
            return new Group(false, List.of(), List.of());
        }
        if (unmapped) {
            // 한 명이라도 못 찾았으면 이 목록은 믿을 수 없다. 요청자만 남기고 닫는다.
            return new Group(true,
                    List.of(new RestrictionPrincipal(PageRestriction.PrincipalType.USER.name(), requester)),
                    issues);
        }
        Set<RestrictionPrincipal> deduped = new LinkedHashSet<>(mapped);
        return new Group(true, List.copyOf(deduped), issues);
    }

    /** 한 종류(보기/편집)의 제한 결과. absent는 "원본에 이 제한이 없었다"다. */
    private record Group(boolean present, List<RestrictionPrincipal> principals,
                         List<MigrationStageIssue> issues) {

        boolean absent() {
            return !present;
        }
    }
}
