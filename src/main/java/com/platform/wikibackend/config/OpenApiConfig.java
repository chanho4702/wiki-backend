package com.platform.wikibackend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * `GET /v3/api-docs`로 나가는 OpenAPI 3 스펙의 메타와 공통 오류 응답.
 *
 * UI(swagger-ui/scalar)는 붙이지 않는다 — 이 스펙은 사람이 브라우저로 보는 것이 아니라
 * myFront의 `scripts/api`가 긁어 가 문서 위키 페이지를 생성하는 입력이다.
 * 게이트웨이·nginx가 `/v3`를 라우팅하지 않으므로 클러스터 안에서만 보인다.
 *
 * `docs` 프로필(공개 문서 인스턴스)에서는 {@link DocsSecurityConfig}의 `anyRequest().denyAll()`에
 * 걸려 스펙이 나가지 않는다. 이 빈 자체는 프로필과 무관하게 등록되지만 경로가 닫혀 있다.
 */
@Configuration
public class OpenApiConfig {

    /** common-starter의 오류 계약 — 어떤 실패든 바디는 이 모양 하나뿐이다. */
    static final String ERROR_SCHEMA = "PlatformError";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String BEARER = "bearerAuth";
    private static final String APPLICATION_JSON = "application/json";
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    /** 이동 영향 409만 바디가 다르다 — error 외에 impact를 싣는다(WikiExceptionHandler). */
    public static final String MOVE_IMPACT_SCHEMA = "MoveImpactError";

    /**
     * 오류 설명 문구. wiki·alm·org 세 서비스가 같은 문자열을 쓴다 — 여기만 바꾸지 않는다.
     * 생성기가 세 서비스의 문서를 나란히 싣기 때문에 표현이 갈리면 그대로 드러난다.
     */
    private static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            "400", "요청 검증 실패",
            "401", "인증 실패 — 토큰 없음·만료·무효",
            "403", "권한 없음",
            "404", "대상 없음",
            "409", "버전 충돌 — expectedVersion 불일치",
            "503", "권한 서비스(org) 불능");

    @Bean
    OpenAPI wikiOpenApi(@Value("${spring.application.version:0.1.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("WIKI API")
                        .version(version)
                        .description("""
                                스페이스와 계층형 페이지, 버전 이력, 첨부, 댓글을 다루는 위키 정본 서비스.
                                모든 경로는 `/api/wiki` 아래에 있고, 인가는 org-service의 스페이스 권한으로 판정한다.
                                오류 응답은 플랫폼 공통 계약인 `{"error": "메시지"}` 한 가지다."""))
                .servers(List.of(new Server().url("/").description("게이트웨이 뒤 위키 서비스")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("개인 API 토큰 `chanho_pat_…` 또는 세션 JWT"))
                        .addSchemas(ERROR_SCHEMA, platformError())
                        .addSchemas(MOVE_IMPACT_SCHEMA, moveImpactError()))
                // 공개 엔드포인트가 없으므로 전역으로 건다 — 오퍼레이션별 예외를 두지 않는다.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    private static Schema<Object> platformError() {
        return new ObjectSchema()
                .description("플랫폼 공통 오류 응답. 메시지는 한국어이며 사용자에게 그대로 보인다.")
                .addProperty("error", new StringSchema()
                        .description("사용자에게 보일 오류 메시지")
                        .example("다른 사용자가 먼저 수정했습니다."))
                .required(List.of("error"));
    }

    /**
     * 이동 영향 409의 바디. 공통 오류에 {@code impact}가 더 붙는다 — 프론트가 이 목록으로
     * "이 사람들이 접근을 잃는다" 확인 다이얼로그를 그리고 confirmImpact=true로 재요청한다.
     * WikiExceptionHandler.moveImpact()가 실제로 내보내는 모양 그대로다.
     */
    private static Schema<Object> moveImpactError() {
        return new ObjectSchema()
                .description("이동 영향으로 멈춘 409. 새로 적용될 보기 제한을 함께 싣는다.")
                .addProperty("error", new StringSchema()
                        .description("사용자에게 보일 오류 메시지")
                        .example("이동하면 새 위치의 보기 제한이 적용되어 일부 사용자가 접근을 잃습니다. 확인 후 다시 시도하세요"))
                .addProperty("impact", new ObjectSchema()
                        .description("확인 다이얼로그가 그릴 영향")
                        .addProperty("newlyRestrictedBy", new ArraySchema()
                                .description("이동 후 새로 적용되는 조상 VIEW 제한")
                                .items(new Schema<>().$ref("#/components/schemas/InheritedRestriction")))
                        .required(List.of("newlyRestrictedBy")))
                .required(List.of("error", "impact"));
    }

    /**
     * 컨트롤러가 직접 선언하지 않는 공통 실패를 채운다. 코드가 실제로 낼 수 있는 것만 넣는다.
     *
     * <ul>
     *   <li>401·403 — 모든 경로가 인증을 요구하고(SecurityConfig) 스페이스 권한을 판정한다.</li>
     *   <li>400 — 요청 본문이 있는 오퍼레이션만. 본문 없는 GET·DELETE에는 검증할 것이 없다.</li>
     *   <li>404 — 경로 변수로 대상을 지목하는 오퍼레이션만. 목록 조회에는 붙이지 않는다.</li>
     *   <li>409 — {@link ConflictResponse}를 붙인 엔드포인트는 그 사유로, 낙관적 락이 있는
     *       PUT(요청 DTO에 {@code expectedVersion})은 정본 문구로. 둘 다인 곳은 사유가 이긴다.</li>
     *   <li>503 — 전부. 읽기·쓰기가 모두 org-service gRPC 권한 판정을 타고, 그게 불능이면
     *       {@code ServiceUnavailableException}으로 503이 나간다(fail-open이 아니라 가용성 장애).</li>
     * </ul>
     *
     * 400·503 두 줄은 alm-backend·org-service와 맞춘 플랫폼 공통 규칙이다 — 여기만 바꾸지 않는다.
     * 이미 선언된 응답 코드는 건드리지 않는다 — 컨트롤러 주석이 항상 이긴다.
     */
    @Bean
    OperationCustomizer commonErrorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            addIfAbsent(responses, "401");
            addIfAbsent(responses, "403");
            if (hasRequestBody(handlerMethod)) {
                addIfAbsent(responses, "400");
            }
            if (hasPathVariable(handlerMethod)) {
                addIfAbsent(responses, "404");
            }
            // 엔드포인트별 사유가 먼저다 — 낙관적 락 정본 문구보다 구체적이다.
            ConflictResponse conflict = handlerMethod.getMethodAnnotation(ConflictResponse.class);
            if (conflict != null) {
                addIfAbsent(responses, "409", conflict.value(), conflict.schema().isEmpty()
                        ? ERROR_REF
                        : "#/components/schemas/" + conflict.schema());
            }
            if (isOptimisticLockedPut(operation, handlerMethod)) {
                addIfAbsent(responses, "409");
            }
            addIfAbsent(responses, "503");
            relabelMultipartBody(operation, handlerMethod);
            return operation;
        };
    }

    private static void addIfAbsent(ApiResponses responses, String code) {
        addIfAbsent(responses, code, ERROR_DESCRIPTIONS.get(code));
    }

    private static void addIfAbsent(ApiResponses responses, String code, String description) {
        addIfAbsent(responses, code, description, ERROR_REF);
    }

    private static void addIfAbsent(ApiResponses responses, String code, String description, String ref) {
        if (responses.containsKey(code)) {
            return;
        }
        responses.addApiResponse(code, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<Object>().$ref(ref)))));
    }

    /**
     * 파일을 받는 오퍼레이션의 요청 본문 미디어 타입을 실제와 맞춘다.
     *
     * springdoc은 본문 스키마(파일 필드 binary)는 제대로 만들지만, 미디어 타입은 매핑의
     * {@code consumes}에서 가져오므로 선언이 없으면 기본값 application/json이 붙는다.
     * 매핑에 {@code consumes}를 다는 것이 정공법이지만 그건 런타임 콘텐츠 협상까지 바꾼다
     * (비-multipart 요청이 바인딩 실패가 아니라 415로 갈린다). 문서만 고친다.
     */
    private static void relabelMultipartBody(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getRequestBody() == null || !hasMultipartParameter(handlerMethod)) {
            return;
        }
        Content content = operation.getRequestBody().getContent();
        if (content == null || !content.containsKey(APPLICATION_JSON)
                || content.containsKey(MULTIPART_FORM_DATA)) {
            return;
        }
        content.addMediaType(MULTIPART_FORM_DATA, content.remove(APPLICATION_JSON));
    }

    private static boolean hasMultipartParameter(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            Class<?> type = parameter.getParameterType();
            if (MultipartFile.class.isAssignableFrom(type)
                    || (type.isArray() && MultipartFile.class.isAssignableFrom(type.getComponentType()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 요청 본문을 받는가 — {@code @RequestBody} 또는 파일 업로드(MultipartFile).
     *
     * 오퍼레이션의 {@code requestBody} 유무로 판정하지 않는 이유: 그건 springdoc이 이 커스터마이저
     * 전에 채워 두었는지에 기댄다. 핸들러 시그니처는 실행 순서와 무관하다.
     */
    private static boolean hasRequestBody(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)
                    || parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestPart.class)) {
                return true;
            }
        }
        return hasMultipartParameter(handlerMethod);
    }

    private static boolean hasPathVariable(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PUT이면서 요청 본문에 {@code expectedVersion}이 있는 오퍼레이션.
     *
     * HTTP 메서드는 핸들러 애너테이션에서 읽는다. 본문 스키마는 springdoc이 이미 풀어 둔
     * {@code $ref}를 쓰지 않고 핸들러 파라미터 타입을 직접 본다 — `$ref` 해석 순서에 기대면
     * 커스터마이저 실행 시점에 따라 결과가 갈린다.
     */
    private static boolean isOptimisticLockedPut(Operation operation, HandlerMethod handlerMethod) {
        return isPut(handlerMethod.getMethod()) && requestBodyHasExpectedVersion(handlerMethod)
                && operation.getRequestBody() != null;
    }

    private static boolean isPut(Method method) {
        if (method.getAnnotation(PutMapping.class) != null) {
            return true;
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping != null && Arrays.asList(mapping.method()).contains(RequestMethod.PUT);
    }

    private static boolean requestBodyHasExpectedVersion(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (!parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)) {
                continue;
            }
            if (declaresExpectedVersion(parameter.getParameterType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresExpectedVersion(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .anyMatch(c -> "expectedVersion".equals(c.getName()));
        }
        for (Field field : type.getDeclaredFields()) {
            if ("expectedVersion".equals(field.getName())) {
                return true;
            }
        }
        return false;
    }
}
