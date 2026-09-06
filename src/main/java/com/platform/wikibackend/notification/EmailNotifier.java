package com.platform.wikibackend.notification;

import com.platform.wikibackend.common.ActorNames;
import com.platform.wikibackend.directory.DirectoryMember;
import com.platform.wikibackend.directory.MemberDirectory;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 이메일 알림 채널(W23) — 알림함에 한 건이 새로 생길 때 같은 내용을 메일로도 보낸다.
 *
 * <p><b>발송기는 org-service다</b>(M4, 2026-09-07 플랫폼 메일 설계). 위키는 SMTP를 직접 말하지 않고
 * {@link OrgMailClient}로 "누구에게 어떤 제목과 본문을"만 넘긴다 — 서버·자격증명·TLS·보내는 주소는
 * 관리 화면이 정하고, 재시도와 발송 로그도 허브가 가진다. 허브가 꺼져 있으면(설정 스위치 off 또는
 * `ORG_INTERNAL_TOKEN` 미설정) 아무것도 나가지 않고 알림함만 남는다 — 설정 화면은 {@link #enabled()}로
 * 그 사실을 먼저 알린다. 스위치를 켰는데 아무것도 오지 않는 것이 최악의 경험이다.
 *
 * 발송은 **커밋 뒤, 다른 스레드**에서 한다. 저장 트랜잭션 안에서 허브를 기다리면 편집자의 저장이
 * 메일 경로에 묶이고, 롤백된 저장의 메일이 먼저 나가 버린다. 실패는 로그로만 남긴다 — 메일은
 * 알림함의 사본이지 원본이 아니다.
 *
 * <p><b>주소는 커밋 뒤에 정한다</b>(alm-backend와 같은 구조). 정본은 org-service 디렉터리이고 개인
 * 설정에 남은 주소는 로그인 때 찍힌 스냅샷이라, org에서 이메일을 바꾸면 낡는다. 한 트랜잭션에서
 * 생긴 알림을 모아 두었다가 {@code GetMembers} <b>한 번</b>으로 전원의 주소를 읽는다 — 워처가 열 명인
 * 문서를 고치면 왕복도 열 번이 되던 것을 막는다.
 */
@Component
@Slf4j
public class EmailNotifier {

    /** 한 트랜잭션에서 쌓이는 발송 대기함의 자리(스레드에 매인다) */
    private static final String PENDING_KEY = EmailNotifier.class.getName() + ".pending";

    private final OrgMailClient orgMail;
    private final ObjectProvider<MemberDirectory> directory;
    private final NotificationPrefService prefs;
    private final ActorNames actorNames;
    private final String publicUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wiki-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailNotifier(OrgMailClient orgMail,
                         ObjectProvider<MemberDirectory> directory,
                         @Lazy NotificationPrefService prefs,
                         ActorNames actorNames,
                         @Value("${platform.wiki.mail.public-url:http://localhost/wiki}") String publicUrl) {
        this.orgMail = orgMail;
        this.directory = directory;
        this.prefs = prefs;
        this.actorNames = actorNames;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /**
     * 메일 채널이 살아 있는가 — 허브의 {@code /internal/org/mail/status}를 60초 캐시로 본다.
     * 설정 화면이 이 값으로 "지금 켜도 아무것도 오지 않는다"를 미리 말한다.
     */
    public boolean enabled() {
        return orgMail.enabled();
    }

    /**
     * 알림함에 새 행이 생긴 직후 호출 — 합쳐진(refresh) 알림에는 보내지 않는다(아직 안 읽은 사람에게
     * 또 보내는 것). 요약(DAILY) 모드인 사람은 여기서 보내지 않고 행을 그대로 둔다 — 요약 작업이
     * `emailed_at`이 빈 행을 모은다.
     *
     * <p>여기서 허브 상태를 묻지 않는다. 이 메서드는 저장 요청 스레드에서 돌기 때문이다 — 채널이
     * 켜졌는지 확인하려고 남의 서비스에 HTTP를 거는 순간 편집자의 저장이 그 왕복에 묶인다.
     * 판단은 커밋 뒤 메일 스레드({@link #dispatch})가 한다.
     */
    public void notify(Notification saved, Page page, String note) {
        Notification.Type type = saved.getType();
        // 스위치와 스냅샷 주소는 지금 읽는다 — 이미 열려 있는 트랜잭션의 DB 조회다. 밖으로 미루는 것은
        // 남의 서비스를 부르는 일(디렉터리·메일 허브)뿐이다.
        Optional<NotificationPrefService.MailTarget> target = prefs.immediateTarget(saved.getUserId(), type);
        if (target.isEmpty()) return;

        String actor = Optional.ofNullable(actorNames.current()).orElse("누군가");
        saved.markEmailed(java.time.Instant.now()); // 나중에 요약 모드로 바꿔도 이 알림이 다시 나가지 않게
        enqueue(new Pending(saved.getUserId(), target.get().snapshotEmail(), compose(type, page, actor, note)));
    }

    /** 하루 요약 한 통 — 주소는 다른 알림과 같은 규칙으로 커밋 뒤에 정해진다. */
    public void notifyDigest(long userId, String snapshotEmail, List<DigestLine> lines) {
        if (lines.isEmpty()) return;
        enqueue(new Pending(userId, snapshotEmail, composeDigest(lines)));
    }

    /**
     * 트랜잭션 안이면 묶음에 쌓고 커밋 뒤 한 번에 보낸다(디렉터리 조회 1회). 트랜잭션 밖이면 바로 보낸다 —
     * 그때는 롤백으로 되돌아갈 저장도, 묶을 형제 알림도 없다.
     */
    private void enqueue(Pending pending) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(List.of(pending));
            return;
        }
        @SuppressWarnings("unchecked")
        List<Pending> batch = (List<Pending>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (batch == null) {
            List<Pending> created = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(List.copyOf(created)); }
                // 롤백이든 커밋이든 자리를 비운다 — 안 비우면 같은 스레드의 다음 요청에 섞인다
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
            batch = created;
        }
        batch.add(pending);
    }

    /**
     * 메일 스레드에서 주소를 정하고 허브에 넘긴다. 여기서만 남의 서비스를 부른다 — 트랜잭션은 이미
     * 끝났고, 수신자가 여럿이어도 디렉터리 왕복은 한 번이다.
     *
     * <p>허브가 꺼져 있으면 디렉터리도 읽지 않는다. 메일을 쓰지 않는 설치에서 문서를 고칠 때마다
     * org에 GetMembers를 거는 것은 그냥 낭비다.
     */
    private void dispatch(List<Pending> batch) {
        if (batch.isEmpty()) return;
        executor.execute(() -> {
            if (!orgMail.enabled()) return;
            Set<Long> ids = new LinkedHashSet<>();
            for (Pending pending : batch) ids.add(pending.userId());
            Map<Long, DirectoryMember> found = members(ids);
            for (Pending pending : batch) {
                Optional<String> to = recipient(pending, found.get(pending.userId()));
                if (to.isEmpty()) continue;
                orgMail.send(List.of(to.get()), pending.draft().subject(), pending.draft().text());
            }
        });
    }

    /**
     * org가 아는 사람들. 디렉터리 빈이 없거나(docs 프로필) 조회가 실패하면 빈 결과다 — 그러면
     * 스냅샷으로 폴백한다. 메일은 부수 채널이라 org 불능이 발송을 멈출 이유가 되지 않는다.
     */
    private Map<Long, DirectoryMember> members(Set<Long> ids) {
        MemberDirectory available = directory.getIfAvailable();
        if (available == null) return Map.of();
        try {
            return available.members(ids);
        } catch (Exception e) {
            log.warn("사용자 디렉터리를 읽지 못해 스냅샷 주소로 보낸다: users={}", ids, e);
            return Map.of();
        }
    }

    /**
     * 보낼 주소 — 정본은 org-service 디렉터리다. 개인 설정에 남은 주소는 로그인 때 찍힌 스냅샷이라
     * org에서 이메일을 바꾸면 낡는다.
     *
     * <p>디렉터리가 답을 못 주면(org 불능 등) 스냅샷으로 폴백하고, 그것도 없으면 보내지 않는다 —
     * 주소를 모르는 것은 조용히 넘어갈 일이지 문서 저장을 막을 일이 아니다. 다만 디렉터리가
     * "이 계정은 막혀 있다"고 답하면 폴백하지 않는다({@link DirectoryMember#blockedFromMail()}).
     */
    private Optional<String> recipient(Pending pending, DirectoryMember member) {
        if (member != null) {
            if (member.blockedFromMail()) {
                log.debug("막힌 계정이라 알림 메일을 보내지 않는다: user={} status={}",
                        pending.userId(), member.status());
                return Optional.empty();
            }
            if (member.hasEmail()) return Optional.of(member.email());
        }
        String snapshot = pending.snapshotEmail();
        if (snapshot == null || snapshot.isBlank()) {
            log.warn("보낼 주소를 몰라 알림 메일을 생략한다: user={}", pending.userId());
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /** 수신 주소를 빼고 만든다 — 주소는 커밋 뒤 디렉터리를 읽어 {@link #dispatch}가 채운다 */
    Draft composeDigest(List<DigestLine> lines) {
        StringBuilder body = new StringBuilder();
        body.append("지난 하루 동안 위키에서 있었던 일 ").append(lines.size()).append("건입니다.\n\n");
        for (DigestLine line : lines) {
            body.append("- ").append(line.what()).append(": '").append(line.title()).append("'\n  ")
                    .append(publicUrl).append("/spaces/").append(line.spaceId()).append("/pages/").append(line.pageId());
            if (line.note() != null && !line.note().isBlank()) body.append("\n  “").append(line.note().trim()).append("”");
            body.append("\n");
        }
        body.append("\n이 메일은 위키 알림 설정(하루 한 번 요약)에 따라 보내졌습니다. 바꾸려면: ")
                .append(publicUrl).append("/settings/notifications\n");
        return new Draft("[Wiki] 오늘의 알림 요약 — " + lines.size() + "건", body.toString());
    }

    public record DigestLine(String what, String title, long spaceId, long pageId, String note) {
    }

    /** 보내는 주소는 허브(관리 화면)가 정한다 — 위키가 만드는 것은 제목과 본문뿐이다. */
    record Draft(String subject, String text) {
    }

    /** 커밋 뒤 주소가 정해질 때까지 들고 있는 한 통 */
    private record Pending(long userId, String snapshotEmail, Draft draft) {
    }

    public static String describe(Notification.Type type) {
        return switch (type) {
            case MENTIONED -> "나를 멘션";
            case PAGE_UPDATED -> "문서 업데이트";
            case COMMENT -> "새 댓글";
            case SHARED -> "문서 공유";
            case PAGE_PUBLISHED -> "새 문서";
        };
    }

    /** 수신 주소를 빼고 만든다 — 주소는 커밋 뒤 디렉터리를 읽어 {@link #dispatch}가 채운다 */
    Draft compose(Notification.Type type, Page page, String actor, String note) {
        String title = page.getTitle();
        String subject = switch (type) {
            case MENTIONED -> actor + "님이 '" + title + "'에서 나를 멘션했습니다";
            case PAGE_UPDATED -> "'" + title + "' 문서가 업데이트되었습니다";
            case COMMENT -> "'" + title + "' 문서에 새 댓글이 달렸습니다";
            case SHARED -> actor + "님이 '" + title + "' 문서를 공유했습니다";
            case PAGE_PUBLISHED -> actor + "님이 새 문서 '" + title + "'을 게시했습니다";
        };
        StringBuilder body = new StringBuilder();
        body.append(subject).append("\n\n");
        if (note != null && !note.isBlank()) body.append("“").append(note.trim()).append("”\n\n");
        body.append("문서 열기: ").append(publicUrl).append("/spaces/").append(page.getSpaceId())
                .append("/pages/").append(page.getId()).append("\n\n");
        body.append("이 메일은 위키 알림 설정에 따라 보내졌습니다. 받지 않으려면: ")
                .append(publicUrl).append("/settings/notifications\n");
        return new Draft("[Wiki] " + subject, body.toString());
    }
}
