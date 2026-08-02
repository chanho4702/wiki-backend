package com.platform.wikibackend.grpc;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListAttachmentsRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 색인기가 실제로 물어보는 방식 그대로 — in-process gRPC 서버 위에 실 리포지토리(H2)를 얹고 검증한다.
 * 이 경로는 사람이 쓰는 화면이 없어서, 테스트가 아니면 깨진 걸 알아챌 방법이 없다.
 */
@DataJpaTest
@ActiveProfiles("test")
class WikiContentGrpcServiceTest {

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;

    Server server;
    ManagedChannel channel;
    WikiContentServiceGrpc.WikiContentServiceBlockingStub stub;

    @BeforeEach
    void setup() throws IOException {
        // @SpringBootTest 클래스들이 커밋해 두고 간 잔여물이 H2 인메모리 DB를 통해 넘어온다
        // (컨텍스트는 달라도 DB는 같다) — space.key unique 충돌을 피하려면 먼저 비운다.
        // deleteAll()이 아니라 deleteAllInBatch()인 이유: Hibernate는 플러시 때 INSERT를 DELETE보다
        // 먼저 실행한다. 지연된 삭제로는 뒤이은 save()가 아직 살아 있는 행과 충돌한다.
        attachments.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new WikiContentGrpcService(pages, spaces, attachments))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = WikiContentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(3, TimeUnit.SECONDS);
        server.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    void 페이지_본문과_스페이스_표시명을_함께_돌려준다() {
        Space s = spaces.save(Space.of("dev", "개발 위키", null, 1L));
        Page p = pages.save(Page.of(s.getId(), null, "배포 가이드", "# 배포\n무중단으로 한다", 7L));

        PageContent got = stub.getPageContent(
                GetPageContentRequest.newBuilder().setPageId(p.getId()).build());

        assertThat(got.getPageId()).isEqualTo(p.getId());
        assertThat(got.getSpaceKey()).isEqualTo("dev");
        // 스페이스명은 비정규화해서 실어 보낸다 — 색인기가 페이지마다 스페이스를 되묻지 않게
        assertThat(got.getSpaceName()).isEqualTo("개발 위키");
        assertThat(got.getTitle()).isEqualTo("배포 가이드");
        assertThat(got.getContent()).contains("무중단으로 한다");
        assertThat(got.getType()).isEqualTo(com.platform.proto.wiki.v1.PageType.PAGE);
        assertThat(got.getStatus()).isEqualTo(com.platform.proto.wiki.v1.PageStatus.PUBLISHED);
        assertThat(got.getAuthorId()).isEqualTo(7L);
        assertThat(got.getUpdatedAt()).isPositive();
        assertThat(got.getParentId()).isZero(); // 루트는 0
    }

    @Test
    void 폴더와_초안도_그대로_실어_보낸다() {
        // 걸러내기는 검색 질의 단계의 몫이다 — 색인에서 빼버리면 "초안 포함 검색"을 켤 때 재색인이 필요해진다
        Space s = spaces.save(Space.of("ops", "운영", null, 1L));
        Page folder = pages.save(Page.of(s.getId(), null, "런북", "", 1L, PageType.FOLDER, PageStatus.PUBLISHED));
        Page draft = pages.save(Page.of(s.getId(), folder.getId(), "초안 문서", "쓰는 중", 1L,
                PageType.PAGE, PageStatus.DRAFT));

        assertThat(stub.getPageContent(GetPageContentRequest.newBuilder().setPageId(folder.getId()).build())
                .getType()).isEqualTo(com.platform.proto.wiki.v1.PageType.FOLDER);

        PageContent d = stub.getPageContent(GetPageContentRequest.newBuilder().setPageId(draft.getId()).build());
        assertThat(d.getStatus()).isEqualTo(com.platform.proto.wiki.v1.PageStatus.DRAFT);
        assertThat(d.getParentId()).isEqualTo(folder.getId());
    }

    @Test
    void 없는_페이지는_NOT_FOUND다() {
        // 색인기는 이걸 "이미 지워졌다"로 읽는다 — 삭제와 조회가 경합해도 색인이 수렴하는 근거
        assertThatThrownBy(() -> stub.getPageContent(
                GetPageContentRequest.newBuilder().setPageId(9999L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void 백필_스트림은_배치_경계를_넘어_전량을_흘린다() {
        // BATCH=200 — 커서 페이징이 실제로 다음 배치로 넘어가는지 본다(경계에서 멈추면 색인이 조용히 잘린다)
        Space s = spaces.save(Space.of("big", "큰 스페이스", null, 1L));
        for (int i = 0; i < 205; i++) {
            pages.save(Page.of(s.getId(), null, "문서 " + i, "내용 " + i, 1L));
        }

        List<PageContent> all = drain(stub.listPageContents(
                ListPageContentsRequest.newBuilder().build()));

        assertThat(all).hasSize(205);
        assertThat(all.stream().map(PageContent::getPageId).distinct()).hasSize(205);
    }

    @Test
    void 백필은_스페이스로_좁힐_수_있다() {
        Space a = spaces.save(Space.of("a", "A", null, 1L));
        Space b = spaces.save(Space.of("b", "B", null, 1L));
        pages.save(Page.of(a.getId(), null, "a1", "x", 1L));
        pages.save(Page.of(a.getId(), null, "a2", "x", 1L));
        pages.save(Page.of(b.getId(), null, "b1", "x", 1L));

        List<PageContent> onlyA = drain(stub.listPageContents(
                ListPageContentsRequest.newBuilder().setSpaceId(a.getId()).build()));

        assertThat(onlyA).hasSize(2);
        assertThat(onlyA).allSatisfy(p -> assertThat(p.getSpaceKey()).isEqualTo("a"));
    }

    @Test
    void 첨부_백필은_페이지를_조인해_spaceId를_채운다() {
        // Attachment 엔티티에는 spaceId가 없다 — 조인으로 채우지 않으면 색인의 권한 필터가 걸리지 않는다
        Space s = spaces.save(Space.of("doc", "문서함", null, 1L));
        Page p = pages.save(Page.of(s.getId(), null, "제안서", "본문", 1L));
        attachments.save(Attachment.of(p.getId(), "제안서.pdf", "application/pdf", 1024L, "uuid-1", 5L));

        List<AttachmentMeta> got = drain(stub.listAttachments(ListAttachmentsRequest.newBuilder().build()));

        assertThat(got).hasSize(1);
        assertThat(got.getFirst().getSpaceId()).isEqualTo(s.getId());
        assertThat(got.getFirst().getPageId()).isEqualTo(p.getId());
        assertThat(got.getFirst().getFilename()).isEqualTo("제안서.pdf");
        assertThat(got.getFirst().getSizeBytes()).isEqualTo(1024L);
    }

    @Test
    void 첨부_백필도_스페이스로_좁힐_수_있다() {
        Space a = spaces.save(Space.of("sa", "SA", null, 1L));
        Space b = spaces.save(Space.of("sb", "SB", null, 1L));
        Page pa = pages.save(Page.of(a.getId(), null, "pa", "x", 1L));
        Page pb = pages.save(Page.of(b.getId(), null, "pb", "x", 1L));
        attachments.save(Attachment.of(pa.getId(), "a.txt", "text/plain", 1L, "k-a", 1L));
        attachments.save(Attachment.of(pb.getId(), "b.txt", "text/plain", 1L, "k-b", 1L));

        List<AttachmentMeta> onlyB = drain(stub.listAttachments(
                ListAttachmentsRequest.newBuilder().setSpaceId(b.getId()).build()));

        assertThat(onlyB).hasSize(1);
        assertThat(onlyB.getFirst().getFilename()).isEqualTo("b.txt");
    }

    private static <T> List<T> drain(java.util.Iterator<T> it) {
        List<T> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
