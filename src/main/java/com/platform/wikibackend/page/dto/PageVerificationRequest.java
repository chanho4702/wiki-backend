package com.platform.wikibackend.page.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * 검증(W27-5). verifiedUntil이 없으면 서버가 기본 유효기간(90일)을 붙인다.
 *
 * 날짜(시각 아님)로 받는다 — 사람이 고르는 것은 "언제까지 믿을 만한가"이고, 그 판단에 분 단위
 * 정밀도는 의미가 없다.
 */
public record PageVerificationRequest(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate verifiedUntil) {
}
