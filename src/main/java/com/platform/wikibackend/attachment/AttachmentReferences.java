package com.platform.wikibackend.attachment;

public final class AttachmentReferences {

    private AttachmentReferences() {
    }

    /** 본문에 박히는 인라인 참조 — 화면이 인증 fetch로 받아 Blob으로 그린다. 이미지·PDF만 허용된다. */
    public static String inlineUrl(long attachmentId) {
        return "/api/wiki/attachments/" + attachmentId + "/inline";
    }

    /**
     * 내려받기 주소. 이미지가 아닌 첨부는 이쪽을 쓴다 — inline은 안전한 형식만 열어 주므로
     * 문서 파일을 inline으로 걸면 열 때 400이 난다.
     */
    public static String downloadUrl(long attachmentId) {
        return "/api/wiki/attachments/" + attachmentId;
    }
}
