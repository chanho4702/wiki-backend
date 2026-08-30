package com.platform.wikibackend.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 지금 요청을 보낸 사람의 표시명(JWT `name`).
 *
 * 리비전에 편집자 이름을 남기려고(V28) 컨트롤러마다 이름을 서비스 인자로 끌고 다니면 저장
 * 경로 여섯 곳의 시그니처가 바뀐다. 이름은 인증 컨텍스트에 이미 있으므로 거기서 읽는다.
 * 요청 밖(스케줄러·이벤트)에서는 null — 그 리비전은 화면이 id로 폴백한다.
 */
@Component
public class ActorNames {

    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return jwt.getClaimAsString("name");
    }
}
