package com.chargeinsight.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
public class AuthController {
    private final LocalAuthService authService;
    public AuthController(LocalAuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return Map.of("status", "SUCCESS", "result", authService.login(request.username(), request.password()));
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(jwt.getSubject(), request.currentPassword(), request.newPassword());
        return Map.of("status", "SUCCESS", "message", "密码已更新，请重新登录");
    }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) { }
}
