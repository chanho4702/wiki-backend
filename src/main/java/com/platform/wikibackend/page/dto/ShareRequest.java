package com.platform.wikibackend.page.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 페이지 공유 요청(W23). 수신자는 org member id. */
public record ShareRequest(
        @NotEmpty(message = "받는 사람을 한 명 이상 고르세요") List<Long> userIds,
        @Size(max = 300, message = "메모는 300자를 넘을 수 없습니다") String note) {
}
