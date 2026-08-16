package com.platform.wikibackend.attachment;

final class AttachmentReferences {

    private AttachmentReferences() {
    }

    static String inlineUrl(long attachmentId) {
        return "/api/wiki/attachments/" + attachmentId + "/inline";
    }
}
