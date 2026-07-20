package com.platform.wikibackend.event;

import com.platform.proto.events.v1.*;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;

import java.util.UUID;

/** EventEnvelope 조립 — 본문(content)은 싣지 않는다(스펙). */
public final class WikiEvents {

    private WikiEvents() {}

    private static EventEnvelope.Builder base(long actorId) {
        return EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(System.currentTimeMillis())
                .setActorId(actorId)
                .setSource("wiki-backend");
    }

    public static EventEnvelope spaceCreated(long actorId, Space s) {
        return base(actorId).setSpaceCreated(SpaceCreated.newBuilder()
                .setSpaceId(s.getId()).setKey(s.getKey()).setName(s.getName())).build();
    }

    public static EventEnvelope spaceDeleted(long actorId, long spaceId) {
        return base(actorId).setSpaceDeleted(SpaceDeleted.newBuilder().setSpaceId(spaceId)).build();
    }

    public static EventEnvelope pageCreated(long actorId, Page p) {
        return base(actorId).setPageCreated(PageCreated.newBuilder()
                .setPageId(p.getId()).setSpaceId(p.getSpaceId()).setTitle(p.getTitle())).build();
    }

    public static EventEnvelope pageUpdated(long actorId, Page p) {
        return base(actorId).setPageUpdated(PageUpdated.newBuilder()
                .setPageId(p.getId()).setSpaceId(p.getSpaceId()).setTitle(p.getTitle())
                .setVersion(p.getVersion())).build();
    }

    public static EventEnvelope pageDeleted(long actorId, long pageId, long spaceId) {
        return base(actorId).setPageDeleted(PageDeleted.newBuilder()
                .setPageId(pageId).setSpaceId(spaceId)).build();
    }

    public static EventEnvelope attachmentAdded(long actorId, Attachment a, long spaceId) {
        return base(actorId).setAttachmentAdded(AttachmentAdded.newBuilder()
                .setAttachmentId(a.getId()).setPageId(a.getPageId()).setSpaceId(spaceId)
                .setFilename(a.getFilename())).build();
    }
}
