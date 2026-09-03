package com.chargeinsight.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

/** ADMIN-only by SecurityConfiguration; passwords are accepted but never returned or logged. */
@RestController
@RequestMapping("/api/admin/users")
@ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
public class AuthAdminController {
    private final LocalAuthService authService;
    public AuthAdminController(LocalAuthService authService) { this.authService = authService; }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request) {
        return Map.of("status", "SUCCESS", "result",
                authService.createUser(request.username(), request.password(), request.roles(), request.regionScopes()));
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("status", "SUCCESS", "users", authService.listUsers());
    }

    @PatchMapping("/{username}/access")
    public Map<String, Object> updateAccess(@PathVariable String username, @AuthenticationPrincipal Jwt currentUser,
            @Valid @RequestBody UpdateAccessRequest request) {
        if (username.equals(currentUser.getSubject()) && (!request.enabled() || !request.roles().contains("ADMIN"))) {
            throw new IllegalArgumentException("不能禁用自己或移除自己的 ADMIN 角色");
        }
        return Map.of("status", "SUCCESS", "result",
                authService.updateAccess(username, request.roles(), request.regionScopes(), request.enabled()));
    }

    @PostMapping("/{username}/password/reset")
    public Map<String, Object> resetPassword(@PathVariable String username, @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(username, request.newPassword());
        return Map.of("status", "SUCCESS", "message", "密码已重置；该用户需要使用新密码重新登录");
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password,
                                    @NotEmpty List<String> roles, @NotEmpty List<String> regionScopes) { }
    public record UpdateAccessRequest(@NotEmpty List<String> roles, @NotEmpty List<String> regionScopes, boolean enabled) { }
    public record ResetPasswordRequest(@NotBlank String newPassword) { }
}
