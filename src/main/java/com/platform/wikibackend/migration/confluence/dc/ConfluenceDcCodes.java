package com.platform.wikibackend.migration.confluence.dc;

/** DC 클라이언트가 내는 실패 코드. item의 last_error_code와 손실 보고서에 그대로 실린다. */
public final class ConfluenceDcCodes {

    /** 429·5xx·네트워크 오류 — 원본이 잠시 못 받는 상태다. 재시도한다. */
    public static final String UNAVAILABLE = "DC_UNAVAILABLE";

    /** 401·403 — 토큰이 없거나 권한이 부족하다. 다시 불러도 같으므로 재시도하지 않는다. */
    public static final String AUTH = "DC_AUTH";

    /** 404 — 원본에서 사라졌다. 항목은 데드레터로 간다. */
    public static final String NOT_FOUND = "DC_NOT_FOUND";

    /** 그 밖의 4xx와 파싱 불가 응답. 우리가 요청을 잘못 만들었거나 상대가 DC가 아니다. */
    public static final String INVALID_RESPONSE = "DC_INVALID_RESPONSE";

    /**
     * 3xx. 리다이렉트를 따라가면 baseUrl 검증을 우회해 내부망으로 끌려갈 수 있어(SSRF)
     * 거부한다 — 프록시·리버스프록시 설정을 고치는 것이 답이지 우리가 따라갈 일이 아니다.
     */
    public static final String REDIRECT_REFUSED = "DC_REDIRECT_REFUSED";

    private ConfluenceDcCodes() {
    }
}
