package com.platform.wikibackend.permission.dto;

import java.util.List;

public record PageRestrictionsResponse(List<RestrictionPrincipal> view,
                                       List<RestrictionPrincipal> edit,
                                       List<InheritedRestriction> inherited) {
}
