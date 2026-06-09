package com.iot.platform.security;

import com.iot.platform.ops.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * 携带 X-Api-Key 且校验通过时，授予 ROLE_API_CLIENT，用于访问受限开放接口（如 GET 最近遥测）。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().contains("/api/v1/telemetry/recent")) {
            String k = request.getHeader("X-Api-Key");
            if (k != null && !k.trim().isEmpty() && apiKeyService.validateAndTouch(k.trim())) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        "api-client",
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
                auth.setDetails(null);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
