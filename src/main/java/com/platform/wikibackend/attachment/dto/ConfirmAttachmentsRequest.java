package com.platform.wikibackend.attachment.dto;

import java.util.List;

public record ConfirmAttachmentsRequest(List<Long> attachmentIds) {
}
