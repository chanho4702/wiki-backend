package com.platform.wikibackend.common;

/** 의존 서비스(예: org-service 권한 gRPC) 불능 시 — 503으로 전파해 프론트가 장애를 인지하게 한다. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) { super(message); }
    public ServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
