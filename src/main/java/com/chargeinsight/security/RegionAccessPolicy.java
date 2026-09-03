package com.chargeinsight.security;

import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Enforces token-owned region scopes at the data-access boundary, never from request headers. */
@Component
public class RegionAccessPolicy {
    public void assertAllowed(String region) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return; // Security disabled: preserves the existing local development mode.
        }
        Collection<String> scopes = jwt.getClaimAsStringList("regionScopes");
        if (scopes == null || (!scopes.contains("*") && !scopes.contains(region))) {
            throw new RegionAccessDeniedException(region);
        }
    }

    /** A cross-region result is allowed only for an unrestricted ADMIN-style scope. */
    public void assertAllRegionsAllowed() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return;
        }
        Collection<String> scopes = jwt.getClaimAsStringList("regionScopes");
        if (scopes == null || !scopes.contains("*")) {
            throw new RegionAccessDeniedException("全部运营大区");
        }
    }

    public static class RegionAccessDeniedException extends RuntimeException {
        public RegionAccessDeniedException(String region) { super("当前账号无权访问运营大区：" + region); }
    }
}
