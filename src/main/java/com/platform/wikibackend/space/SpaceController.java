package com.platform.wikibackend.space;

import com.platform.wikibackend.space.dto.SpaceCreateRequest;
import com.platform.wikibackend.space.dto.SpaceResponse;
import com.platform.wikibackend.space.dto.SpaceUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Spaces", description = "스페이스 목록·생성·조회·수정·삭제.")
@RestController
@RequestMapping("/api/wiki/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaces;

    @Operation(summary = "내가 접근할 수 있는 스페이스를 조회한다")
    @GetMapping
    public List<SpaceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return spaces.listAccessible(userId(jwt));
    }

    @Operation(summary = "스페이스를 만든다 — 생성자에게 ADMIN이 자동으로 붙는다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpaceResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SpaceCreateRequest req) {
        return spaces.create(userId(jwt), req);
    }

    /** 내 개인 스페이스 — 없으면 만든다(멱등). 이름은 토큰의 표시명을 쓴다. */
    @Operation(summary = "내 개인 스페이스를 가져온다 — 없으면 만든다")
    @PostMapping("/personal")
    public SpaceResponse personal(@AuthenticationPrincipal Jwt jwt) {
        return spaces.ensurePersonal(userId(jwt), jwt.getClaimAsString("name"));
    }

    @Operation(summary = "스페이스를 조회한다")
    @GetMapping("/{id}")
    public SpaceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return SpaceResponse.from(spaces.getForView(userId(jwt), id));
    }

    @Operation(summary = "스페이스 이름과 설명을 수정한다")
    @PutMapping("/{id}")
    public SpaceResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                @Valid @RequestBody SpaceUpdateRequest req) {
        return spaces.update(userId(jwt), id, req);
    }

    @Operation(summary = "스페이스를 삭제한다")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        spaces.delete(userId(jwt), id);
    }

    /** page·attachment 컨트롤러가 import static으로 공용 — 반드시 public. */
    public static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
