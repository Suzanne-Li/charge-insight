package com.chargeinsight.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Service
@DependsOn("authSchemaInitializer")
@ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
public class LocalAuthService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public LocalAuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
            @Value("${charge.security.jwt-secret}") String jwtSecret) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("启用认证时 CHARGE_JWT_SECRET 至少需要 32 个字符");
        }
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public LoginResult login(String username, String password) {
        List<UserRecord> users = jdbcTemplate.query("SELECT id, username, password_hash, enabled FROM auth_user WHERE username = ?",
                (row, ignored) -> new UserRecord(row.getLong("id"), row.getString("username"), row.getString("password_hash"), row.getBoolean("enabled")), username);
        if (users.size() != 1 || !users.get(0).enabled() || !passwordEncoder.matches(password, users.get(0).passwordHash())) {
            throw new InvalidCredentialsException();
        }
        UserRecord user = users.get(0);
        List<String> roles = jdbcTemplate.queryForList("SELECT role_name FROM auth_user_role WHERE user_id = ? ORDER BY role_name", String.class, user.id());
        List<String> regionScopes = jdbcTemplate.queryForList("SELECT region_name FROM auth_user_region_scope WHERE user_id = ? ORDER BY region_name", String.class, user.id());
        Instant now = Instant.now();
        Instant expiresAt = now.plus(30, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.username()).issuedAt(now).expiresAt(expiresAt)
                .claim("roles", roles).claim("regionScopes", regionScopes).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResult(accessToken, "Bearer", expiresAt, roles, regionScopes);
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 12) {
            throw new IllegalArgumentException("newPassword 至少需要 12 个字符");
        }
        List<UserRecord> users = jdbcTemplate.query("SELECT id, username, password_hash, enabled FROM auth_user WHERE username = ?",
                (row, ignored) -> new UserRecord(row.getLong("id"), row.getString("username"), row.getString("password_hash"), row.getBoolean("enabled")), username);
        if (users.size() != 1 || !users.get(0).enabled() || !passwordEncoder.matches(currentPassword, users.get(0).passwordHash())) {
            throw new InvalidCredentialsException();
        }
        jdbcTemplate.update("UPDATE auth_user SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), users.get(0).id());
    }

    @Transactional
    public ManagedUser createUser(String username, String password, List<String> roles, List<String> regionScopes) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new IllegalArgumentException("username 仅允许 3-64 位字母、数字、点、下划线或连字符");
        }
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("password 至少需要 12 个字符");
        }
        if (roles == null || roles.isEmpty() || !Set.of("ADMIN", "ANALYST").containsAll(roles)) {
            throw new IllegalArgumentException("roles 必须为 ADMIN 或 ANALYST，且不能为空");
        }
        if (regionScopes == null || regionScopes.isEmpty() || regionScopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("regionScopes 不能为空");
        }
        try {
            jdbcTemplate.update("INSERT INTO auth_user(username, password_hash, enabled) VALUES (?, ?, 1)", username, passwordEncoder.encode(password));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new IllegalArgumentException("username 已存在");
        }
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM auth_user WHERE username = ?", Long.class, username);
        roles.stream().distinct().forEach(role -> jdbcTemplate.update("INSERT INTO auth_user_role(user_id, role_name) VALUES (?, ?)", userId, role));
        regionScopes.stream().map(String::trim).distinct()
                .forEach(scope -> jdbcTemplate.update("INSERT INTO auth_user_region_scope(user_id, region_name) VALUES (?, ?)", userId, scope));
        return new ManagedUser(userId, username, roles.stream().distinct().toList(), regionScopes.stream().map(String::trim).distinct().toList(), true);
    }

    public List<ManagedUser> listUsers() {
        return jdbcTemplate.query("SELECT id, username, enabled FROM auth_user ORDER BY id", (row, ignored) ->
                new BasicUser(row.getLong("id"), row.getString("username"), row.getBoolean("enabled"))).stream()
                .map(user -> new ManagedUser(user.id(), user.username(), rolesFor(user.id()), regionScopesFor(user.id()), user.enabled())).toList();
    }

    @Transactional
    public ManagedUser updateAccess(String username, List<String> roles, List<String> regionScopes, boolean enabled) {
        validateAccess(roles, regionScopes);
        BasicUser user = findUser(username);
        jdbcTemplate.update("UPDATE auth_user SET enabled = ? WHERE id = ?", enabled, user.id());
        jdbcTemplate.update("DELETE FROM auth_user_role WHERE user_id = ?", user.id());
        roles.stream().distinct().forEach(role -> jdbcTemplate.update("INSERT INTO auth_user_role(user_id, role_name) VALUES (?, ?)", user.id(), role));
        jdbcTemplate.update("DELETE FROM auth_user_region_scope WHERE user_id = ?", user.id());
        regionScopes.stream().map(String::trim).distinct()
                .forEach(scope -> jdbcTemplate.update("INSERT INTO auth_user_region_scope(user_id, region_name) VALUES (?, ?)", user.id(), scope));
        return new ManagedUser(user.id(), user.username(), roles.stream().distinct().toList(), regionScopes.stream().map(String::trim).distinct().toList(), enabled);
    }

    @Transactional
    public void resetPassword(String username, String newPassword) {
        if (newPassword == null || newPassword.length() < 12) {
            throw new IllegalArgumentException("newPassword 至少需要 12 个字符");
        }
        BasicUser user = findUser(username);
        jdbcTemplate.update("UPDATE auth_user SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), user.id());
    }

    private void validateAccess(List<String> roles, List<String> regionScopes) {
        if (roles == null || roles.isEmpty() || !Set.of("ADMIN", "ANALYST").containsAll(roles)) {
            throw new IllegalArgumentException("roles 必须为 ADMIN 或 ANALYST，且不能为空");
        }
        if (regionScopes == null || regionScopes.isEmpty() || regionScopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("regionScopes 不能为空");
        }
    }

    private BasicUser findUser(String username) {
        List<BasicUser> users = jdbcTemplate.query("SELECT id, username, enabled FROM auth_user WHERE username = ?",
                (row, ignored) -> new BasicUser(row.getLong("id"), row.getString("username"), row.getBoolean("enabled")), username);
        if (users.size() != 1) throw new IllegalArgumentException("用户不存在");
        return users.get(0);
    }

    private List<String> rolesFor(long userId) {
        return jdbcTemplate.queryForList("SELECT role_name FROM auth_user_role WHERE user_id = ? ORDER BY role_name", String.class, userId);
    }

    private List<String> regionScopesFor(long userId) {
        return jdbcTemplate.queryForList("SELECT region_name FROM auth_user_region_scope WHERE user_id = ? ORDER BY region_name", String.class, userId);
    }

    public static class InvalidCredentialsException extends RuntimeException { }
    private record UserRecord(long id, String username, String passwordHash, boolean enabled) { }
    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, List<String> roles, List<String> regionScopes) { }
    private record BasicUser(long id, String username, boolean enabled) { }
    public record ManagedUser(long id, String username, List<String> roles, List<String> regionScopes, boolean enabled) { }
}
