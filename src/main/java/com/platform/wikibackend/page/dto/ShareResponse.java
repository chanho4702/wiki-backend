package com.platform.wikibackend.page.dto;

/** 실제로 전달된 수 — 볼 수 없는 수신자는 조용히 빠지므로 요청한 수와 다를 수 있다. */
public record ShareResponse(int delivered) {
}
