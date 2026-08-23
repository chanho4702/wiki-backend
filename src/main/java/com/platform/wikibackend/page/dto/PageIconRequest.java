package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.Size;

/** V10 — 이모지 아이콘 설정. null = 해제. 이모지 1개(조합 이모지 여유분 16자)만 받는다. */
public record PageIconRequest(@Size(max = 16, message = "아이콘은 이모지 1개만 지정할 수 있습니다") String icon) {
}
