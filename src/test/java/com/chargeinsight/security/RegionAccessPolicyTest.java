package com.chargeinsight.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RegionAccessPolicyTest {
    private final RegionAccessPolicy policy = new RegionAccessPolicy();

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void allowsRegionIncludedInJwtScope() {
        authenticateWithRegions(List.of("华东"));
        assertThatCode(() -> policy.assertAllowed("华东")).doesNotThrowAnyException();
    }

    @Test
    void rejectsRegionOutsideJwtScope() {
        authenticateWithRegions(List.of("华东"));
        assertThatThrownBy(() -> policy.assertAllowed("华南"))
                .isInstanceOf(RegionAccessPolicy.RegionAccessDeniedException.class);
    }

    @Test
    void rejectsCrossRegionAccessForScopedAnalyst() {
        authenticateWithRegions(List.of("华东"));
        assertThatThrownBy(() -> policy.assertAllRegionsAllowed())
                .isInstanceOf(RegionAccessPolicy.RegionAccessDeniedException.class);
    }

    private void authenticateWithRegions(List<String> regions) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256").subject("analyst")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("regionScopes", regions).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
