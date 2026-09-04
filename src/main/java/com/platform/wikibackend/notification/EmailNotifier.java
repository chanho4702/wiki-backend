package com.platform.wikibackend.notification;

import com.platform.wikibackend.common.ActorNames;
import com.platform.wikibackend.domain.Notification;
import com.platform.wikibackend.domain.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 이메일 알림 채널(W23) — 알림함에 한 건이 새로 생길 때 같은 내용을 메일로도 보낸다.
 *
 * OpenSearch와 같은 **선택 옵션**이다: `WIKI_MAIL_HOST`가 비면 발송기가 없고 알림함만 남는다.
 * 설정 화면은 {@link #configured()}로 그 사실을 먼저 알린다 — 스위치를 켰는데 아무것도 오지
 * 않는 것이 최악의 경험이다.
 *
 * 발송은 **커밋 뒤, 다른 스레드**에서 한다. 저장 트랜잭션 안에서 SMTP를 기다리면 편집자의 저장이
 * 메일 서버 속도에 묶이고, 롤백된 저장의 메일이 먼저 나가 버린다. 실패는 로그로만 남긴다 — 메일은
 * 알림함의 사본이지 원본이 아니다.
 */
@Component
@Slf4j
public class EmailNotifier {

    private final ObjectProvider<JavaMailSender> senders;
    private final NotificationPrefService prefs;
    private final ActorNames actorNames;
    private final String host;
    private final String from;
    private final String publicUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wiki-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailNotifier(ObjectProvider<JavaMailSender> senders,
                         @Lazy NotificationPrefService prefs,
                         ActorNames actorNames,
                         @Value("${spring.mail.host:}") String host,
                         @Value("${platform.wiki.mail.from:wiki@localhost}") String from,
                         @Value("${platform.wiki.mail.public-url:http://localhost/wiki}") String publicUrl) {
        this.senders = senders;
        this.prefs = prefs;
        this.actorNames = actorNames;
        this.host = host == null ? "" : host.trim();
        this.from = from;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** host가 비어 있으면 Boot가 빈 host의 발송기를 만들어 두므로 빈 존재만으로 판단하지 않는다. */
    public boolean configured() {
        return !host.isEmpty() && senders.getIfAvailable() != null;
    }

    /**
     * 알림함에 새 행이 생긴 직후 호출 — 합쳐진(refresh) 알림에는 보내지 않는다(아직 안 읽은 사람에게
     * 또 보내는 것). 요약(DAILY) 모드인 사람은 여기서 보내지 않고 행을 그대로 둔다 — 요약 작업이
     * `emailed_at`이 빈 행을 모은다.
     */
    public void notify(Notification saved, Page page, String note) {
        if (!configured()) return;
        Notification.Type type = saved.getType();
        Optional<String> to = prefs.immediateEmailFor(saved.getUserId(), type);
        if (to.isEmpty()) return;

        String actor = Optional.ofNullable(actorNames.current()).orElse("누군가");
        saved.markEmailed(java.time.Instant.now()); // 나중에 요약 모드로 바꿔도 이 알림이 다시 나가지 않게
        sendAfterCommit(compose(to.get(), type, page, actor, note));
    }

    /** 커밋 뒤 별도 스레드로 보낸다. 트랜잭션 밖이면 바로. */
    public void sendAfterCommit(SimpleMailMessage message) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) return;
        Runnable send = () -> executor.execute(() -> {
            try {
                sender.send(message);
            } catch (Exception e) {
                log.warn("알림 메일 발송 실패: to={} subject={}", String.join(",", message.getTo() == null ? new String[0] : message.getTo()), message.getSubject(), e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    /** 하루 요약 한 통 — 항목마다 한 줄(무슨 일 · 문서 제목 · 링크). */
    public SimpleMailMessage composeDigest(String to, List<DigestLine> lines) {
        StringBuilder body = new StringBuilder();
        body.append("지난 하루 동안 위키에서 있었던 일 ").append(lines.size()).append("건입니다.\n\n");
        for (DigestLine line : lines) {
            body.append("- ").append(line.what()).append(": '").append(line.title()).append("'\n  ")
                    .append(publicUrl).append("/spaces/").append(line.spaceId()).append("/pages/").append(line.pageId());
            if (line.note() != null && !line.note().isBlank()) body.append("\n  \u201c").append(line.note().trim()).append("\u201d");
            body.append("\n");
        }
        body.append("\n이 메일은 위키 알림 설정(하루 한 번 요약)에 따라 보내졌습니다. 바꾸려면: ")
                .append(publicUrl).append("/settings/notifications\n");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[Wiki] 오늘의 알림 요약 — " + lines.size() + "건");
        message.setText(body.toString());
        return message;
    }

    public record DigestLine(String what, String title, long spaceId, long pageId, String note) {
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

    SimpleMailMessage compose(String to, Notification.Type type, Page page, String actor, String note) {
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
        if (note != null && !note.isBlank()) body.append("\u201c").append(note.trim()).append("\u201d\n\n");
        body.append("문서 열기: ").append(publicUrl).append("/spaces/").append(page.getSpaceId())
                .append("/pages/").append(page.getId()).append("\n\n");
        body.append("이 메일은 위키 알림 설정에 따라 보내졌습니다. 받지 않으려면: ")
                .append(publicUrl).append("/settings/notifications\n");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[Wiki] " + subject);
        message.setText(body.toString());
        return message;
    }
}
