package com.platform.wikibackend;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public class TestAuth {

    public static RequestPostProcessor asUser(long id, String name) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com").claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
