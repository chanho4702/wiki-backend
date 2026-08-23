package com.platform.wikibackend.attachment;

public final class AttachmentReferences {

    private AttachmentReferences() {
    }

    public static String inlineUrl(long attachmentId) {
        return "/api/wiki/attachments/" + attachmentId + "/inline";
    }
}
