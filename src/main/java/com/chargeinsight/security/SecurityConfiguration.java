package com.chargeinsight.security;

import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.MediaType;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    @Bean
    @ConditionalOnProperty(name = "charge.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/api/health", "/api/auth/login").permitAll()
                        .requestMatchers("/mcp", "/mcp/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**", "/api/agent/traces/**", "/api/agent/evaluations/**").hasRole("ADMIN")
                        .requestMatchers("/api/agent/sql/**").hasRole("ADMIN")
                        .requestMatchers("/api/agent/**").hasAnyRole("ADMIN", "ANALYST")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHORIZED", "缺少、过期或无效的访问凭证", request.getRequestURI()))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN", "当前账号没有访问该资源的权限", request.getRequestURI())))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
    JwtDecoder jwtDecoder(@Value("${charge.security.jwt-secret}") String jwtSecret) {
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    @ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> new JwtAuthenticationToken(jwt, java.util.Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(List.of()).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }

    private void writeSecurityError(HttpServletResponse response, int httpStatus, String status, String message, String path)
            throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), java.util.Map.of("status", status, "message", message,
                "path", path, "httpStatus", httpStatus, "timestamp", java.time.Instant.now().toString()));
    }
}
