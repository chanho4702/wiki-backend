package com.platform.wikibackend.directory;

/**
 * org-service가 아는 사람 한 명. {@code status}는 {@code PENDING|ACTIVE|SUSPENDED|DEACTIVATED},
 * {@code kind}는 {@code HUMAN|AGENT}이고 이메일이 없으면 빈 문자열이다(proto 계약 그대로).
 * 값이 뒤에 늘 수 있으므로 문자열로 두고, 아는 값만 분기한다.
 */
public record DirectoryMember(long id, String displayName, String email, String status, String kind) {

    public static final String SUSPENDED = "SUSPENDED";
    public static final String DEACTIVATED = "DEACTIVATED";

    public DirectoryMember {
        displayName = displayName == null ? "" : displayName;
        email = email == null ? "" : email.trim();
        status = status == null ? "" : status;
        kind = kind == null ? "" : kind;
    }

    /** 이 계정은 떠났다 — 알림을 보낼 곳이 아니다 */
    public boolean deactivated() {
        return DEACTIVATED.equals(status);
    }

    /**
     * 알림 메일을 보내지 않을 계정인가.
     *
     * <p>비활성은 alm-backend와 같은 규칙이다 — 떠난 사람에게 계속 보내지 않는다. <b>정지는 위키가
     * 한 가지 더 본다</b>: 메일 제목에 문서 제목이 그대로 실리는데(`'배포 절차' 문서가 …`), 정지된
     * 계정은 {@code AccountStatusInterceptor}가 위키 전체에서 막아 그 문서를 열 수도 없다. 열지 못하는
     * 문서의 제목을 메일로 계속 흘리지 않는다. 승인 대기(PENDING)는 대상이 아니다 — 아직 아무것도
     * 구독하지 못했으므로 보낼 알림 자체가 생기지 않는다.
     */
    public boolean blockedFromMail() {
        return DEACTIVATED.equals(status) || SUSPENDED.equals(status);
    }

    public boolean hasEmail() {
        return !email.isEmpty();
    }

    /** 화면에 쓸 이름이 실제로 있는가 — org가 이름을 비워 둔 사람이 있다 */
    public boolean hasDisplayName() {
        return !displayName.isBlank();
    }
}
