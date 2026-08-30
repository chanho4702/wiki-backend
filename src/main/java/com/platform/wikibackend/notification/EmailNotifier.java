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

    /** 알림함에 새 행이 생긴 직후 호출 — 합쳐진(refresh) 알림에는 보내지 않는다(아직 안 읽은 사람에게 또 보내는 것). */
    public void notify(long userId, Notification.Type type, Page page, String note) {
        if (!configured()) return;
        Optional<String> to = prefs.emailFor(userId, type);
        if (to.isEmpty()) return;
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) return;

        String actor = Optional.ofNullable(actorNames.current()).orElse("누군가");
        SimpleMailMessage message = compose(to.get(), type, page, actor, note);
        Runnable send = () -> executor.execute(() -> {
            try {
                sender.send(message);
            } catch (Exception e) {
                log.warn("알림 메일 발송 실패: to={} page={} type={}", to.get(), page.getId(), type, e);
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

    SimpleMailMessage compose(String to, Notification.Type type, Page page, String actor, String note) {
        String title = page.getTitle();
        String subject = switch (type) {
            case MENTIONED -> actor + "님이 '" + title + "'에서 나를 멘션했습니다";
            case PAGE_UPDATED -> "'" + title + "' 문서가 업데이트되었습니다";
            case COMMENT -> "'" + title + "' 문서에 새 댓글이 달렸습니다";
            case SHARED -> actor + "님이 '" + title + "' 문서를 공유했습니다";
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
