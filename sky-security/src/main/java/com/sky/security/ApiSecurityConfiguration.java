package com.sky.security;

import com.sky.utils.properties.JwtProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class ApiSecurityConfiguration {
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService noFormUsers() {
        return name -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("JWT authentication only"); };
    }
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, JwtProperties jwt, AccountAccess accounts,
            @Value("${sky.internal.token:}") String internal,
            @Value("${sky.security.docs-enabled:false}") boolean docs,
            @Value("${sky.security.cors-origins:}") String origins) throws Exception {
        var cors = new CorsConfiguration();
        if (!origins.isBlank()) cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "token", "authentication", "X-Request-Id"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", cors);
        return http.cors(c -> c.configurationSource(source))
                // Credentials are explicit headers; cookies and HTTP basic are never used for authentication.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> JwtAuthenticationFilter.error(res, 401, "authentication required"))
                        .accessDeniedHandler((req, res, ex) -> JwtAuthenticationFilter.error(res, 403, "permission denied")))
                .addFilterBefore(new JwtAuthenticationFilter(jwt, accounts, internal, docs), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(req -> SecurityRoutes.publicRequest(req, docs)).permitAll()
                        .requestMatchers("/internal/**", "/actuator/**").hasRole("INTERNAL")
                        .requestMatchers(HttpMethod.POST, "/admin/employee/logout").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/admin/employee/editPassword").hasRole("ADMIN")
                        .requestMatchers("/admin/**").access((auth, ctx) -> {
                            var principal = auth.get();
                            boolean allowed = false;
                            if (principal.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_ADMIN"))) {
                                try { allowed = accounts.permitted((Long) principal.getPrincipal(), ctx.getRequest().getMethod(), ctx.getRequest().getRequestURI()); }
                                catch (RuntimeException ignored) { /* unavailable permissions must never allow access */ }
                            }
                            return new AuthorizationDecision(allowed);
                        })
                        .requestMatchers("/user/**").hasRole("USER")
                        .anyRequest().denyAll()).build();
    }
}
