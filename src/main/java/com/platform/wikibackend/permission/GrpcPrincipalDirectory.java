package com.platform.wikibackend.permission;

import com.platform.proto.org.v1.PrincipalKind;
import com.platform.proto.org.v1.PrincipalRef;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ValidatePrincipalsRequest;
import com.platform.wikibackend.common.ServiceUnavailableException;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashSet;

/** org 원장에서 USER/TEAM 실재 여부를 일괄 확인한다(common-proto 0.9.0). */
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
