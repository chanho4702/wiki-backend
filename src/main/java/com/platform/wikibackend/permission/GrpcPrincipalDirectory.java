package com.platform.wikibackend.permission;

import com.platform.proto.org.v1.PrincipalKind;
import com.platform.proto.org.v1.PrincipalRef;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ValidatePrincipalsRequest;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * org 원장에서 USER/TEAM 실재 여부를 일괄 확인한다({@code ValidatePrincipals}).
 *
 * <p>버전 표기는 이 리포가 무는 common-proto 버전(0.16.0)이다 — 이 RPC 자체는 0.9.0에 들어왔지만,
 * 주석에 도입 버전을 적어 두니 아티팩트가 올라갈 때마다 사실과 어긋났다.
 */
@Slf4j
@RequiredArgsConstructor
public class GrpcPrincipalDirectory implements PrincipalDirectory {

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    @Override
    public void requireExisting(Collection<RestrictionPrincipal> principals) {
        ValidatePrincipalsRequest.Builder request = ValidatePrincipalsRequest.newBuilder();
        for (RestrictionPrincipal principal : new LinkedHashSet<>(principals)) {
            if (principal.id() <= 0) {
                throw new IllegalArgumentException("주체 ID는 양수여야 합니다: " + principal.id());
            }
            PageRestriction.PrincipalType type = principal.toType();
            request.addPrincipals(PrincipalRef.newBuilder()
                    .setKind(type == PageRestriction.PrincipalType.USER
                            ? PrincipalKind.PRINCIPAL_USER
                            : PrincipalKind.PRINCIPAL_TEAM)
                    .setId(principal.id()));
        }
        if (request.getPrincipalsCount() == 0) return;

        try {
            var response = stub.validatePrincipals(request.build());
            if (response.getMissingCount() > 0) {
                PrincipalRef missing = response.getMissing(0);
                String type = missing.getKind() == PrincipalKind.PRINCIPAL_TEAM ? "TEAM" : "USER";
                throw new IllegalArgumentException("존재하지 않는 제한 주체입니다: " + type + " " + missing.getId());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("제한 주체 검증 실패 — 저장을 닫는다", e);
            throw new ServiceUnavailableException("조직 디렉터리에 연결할 수 없습니다");
        }
    }
}
