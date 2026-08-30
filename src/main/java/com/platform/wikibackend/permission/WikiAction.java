package com.platform.wikibackend.permission;

/** 계층은 org-service가 판정한다: VIEW < COMMENT < EDIT < ADMIN. COMMENT(W23)는 "댓글 추가"다. */
public enum WikiAction { VIEW, COMMENT, EDIT, ADMIN }
