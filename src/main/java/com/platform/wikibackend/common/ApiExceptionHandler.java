package com.platform.wikibackend.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NotFoundException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(ForbiddenException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(com.platform.wikibackend.permission.MoveImpactException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> moveImpact(com.platform.wikibackend.permission.MoveImpactException e) {
        // 유일하게 error 외 필드를 싣는 응답 — 프론트가 impact 유무로 확인 다이얼로그를 분기한다
        return Map.of("error", e.getMessage(), "impact",
                Map.of("newlyRestrictedBy", e.getNewlyRestrictedBy()));
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(ConflictException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(ServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(ServiceUnavailableException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(UnsafeInlineMediaTypeException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Map<String, String> unsupportedInline(UnsafeInlineMediaTypeException e) {
        return Map.of("error", e.getMessage());
    }
}
