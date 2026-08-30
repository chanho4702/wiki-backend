package com.platform.wikibackend.common;

import com.platform.wikibackend.permission.MoveImpactException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 위키만의 예외. 404·403·409·503·400 같은 공통 계약은 common-starter의 PlatformApiExceptionHandler가
 * 같은 `{"error": …}` 모양으로 맡는다 — 여기는 그것으로 표현할 수 없는 둘만 남는다.
 */
@RestControllerAdvice
@Order(0)
public class WikiExceptionHandler {

    @ExceptionHandler(MoveImpactException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> moveImpact(MoveImpactException e) {
        // 유일하게 error 외 필드를 싣는 응답 — 프론트가 impact 유무로 확인 다이얼로그를 분기한다
        return Map.of("error", e.getMessage(), "impact",
                Map.of("newlyRestrictedBy", e.getNewlyRestrictedBy()));
    }

    @ExceptionHandler(UnsafeInlineMediaTypeException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Map<String, String> unsupportedInline(UnsafeInlineMediaTypeException e) {
        return Map.of("error", e.getMessage());
    }
}
