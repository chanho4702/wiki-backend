package com.platform.wikibackend.page;

import com.platform.wikibackend.audit.AuditService;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.notification.NotificationService;
import com.platform.wikibackend.page.dto.PageResponse;
import com.platform.wikibackend.page.dto.ShareRequest;
import com.platform.wikibackend.page.dto.ShareResponse;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.personal.PersonalService;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 문서 메타데이터 — 소유자·검증·아이콘·공유·조회수. 본문이 아니라 version·리비전을 올리지 않는다.
 * PageService에서 분리(2026-10-18).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PageMetaService {

    private final PageRepository pages;
    private final SpaceService spaces;
    private final EffectivePermissionService effective;
    private final AuditService audit;
    private final NotificationService notificationService;
    private final PersonalService personal;
    private final PageStructure structure;

    /**
     * 소유자 지정·해제(W27-5) — 문서의 기본 책임자 표시다. 권한과는 무관하다(권한은 V12 제한).
     *
     * 메타데이터라 version·리비전을 올리지 않는다(아이콘·이동과 같은 취급). 대신 감사 로그에는
     * 남긴다 — "이 문서 담당이 언제 누구에서 누구로 바뀌었나"는 나중에 반드시 물어보게 된다.
     */
    public PageResponse setOwner(long userId, long pageId, Long ownerId) {
        if (ownerId != null && ownerId <= 0) throw new IllegalArgumentException("소유자 id가 올바르지 않습니다");
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        Long before = p.getOwnerId();
        p.changeOwner(ownerId);
        audit.recordPage(userId, AuditAction.PAGE_OWNER_CHANGED, p,
                (before == null ? "없음" : String.valueOf(before)) + " → "
                        + (ownerId == null ? "없음" : String.valueOf(ownerId)));
        return PageResponse.from(p);
    }

    /** 검증 기본 유효기간 — 분기 한 번. 이보다 짧으면 갱신이 일이 되고, 길면 배지가 무의미해진다. */
    public static final int VERIFICATION_DAYS = 90;

    /**
     * 검증(W27-5) — 사람이 "지금 읽어봤고 맞다"를 누르는 것이다. 만료일이 지나도 문서가 숨거나
     * 잠기지 않는다: 만료 판정은 읽는 쪽이 하고, 서버는 사람이 누른 사실만 저장한다.
     */
    public PageResponse verify(long userId, long pageId, LocalDate until) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        LocalDate deadline = until == null
                ? LocalDate.now(ZoneOffset.UTC).plusDays(VERIFICATION_DAYS)
                : until;
        p.verify(userId, deadline.atStartOfDay(ZoneOffset.UTC).toInstant());
        audit.recordPage(userId, AuditAction.PAGE_VERIFIED, p,
                "유효기간 " + deadline);
        return PageResponse.from(p);
    }

    public PageResponse unverify(long userId, long pageId) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        p.unverify();
        audit.recordPage(userId, AuditAction.PAGE_UNVERIFIED, p, null);
        return PageResponse.from(p);
    }

    /** 이모지 아이콘 설정/해제(null) — 메타데이터 변경이라 version·리비전을 올리지 않는다(move와 같은 취급). */
    public PageResponse setIcon(long userId, long pageId, String icon) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, p);
        PageWriteGuard.requireWritable(p);
        p.changeIcon(icon);
        // 트리 응답에 icon이 실리므로 검색 인덱스 재색인은 불필요(본문·제목 불변) — 이벤트 미발행.
        return PageResponse.from(p);
    }

    /** 페이지 공유(W23) — 보는 사람이면 누구나 보낼 수 있다(링크 복사와 같은 범위). */
    public ShareResponse share(
            long userId, long pageId, ShareRequest req) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, p);
        // 초안은 작성자·관리자만 보므로 공유해도 받는 사람이 열 수 없다 — 조용히 건너뛰지 않고 거부한다.
        if (p.getStatus() == com.platform.wikibackend.domain.PageStatus.DRAFT) {
            throw new IllegalArgumentException("초안은 공유할 수 없습니다. 게시한 뒤 공유하세요");
        }
        int delivered = notificationService.share(userId, p, req.userIds(), req.note());
        return new ShareResponse(delivered);
    }

    /** 조회 1회 기록 — 원자 UPDATE(동시 조회 lost update 방지) 후 누적치 반환. */
    public long recordView(long userId, long pageId) {
        Page p = structure.getOwned(pageId);
        spaces.require(userId, p.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, p);
        pages.incrementViewCount(pageId);
        // 개인 "최근 방문"도 여기서 남긴다 — 모든 열람이 이 경로를 지나므로 왕복을 늘릴 이유가 없다(W23).
        personal.recordVisit(userId, pageId);
        return pages.findViewCount(pageId);
    }
}
