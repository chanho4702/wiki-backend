package com.platform.wikibackend.common;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
