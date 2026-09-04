package com.platform.wikibackend.page.dto;

/** 소유자 지정(W27-5). null이면 해제 — "정하지 않음"이 유효한 상태다. */
public record PageOwnerRequest(Long ownerId) {
}
