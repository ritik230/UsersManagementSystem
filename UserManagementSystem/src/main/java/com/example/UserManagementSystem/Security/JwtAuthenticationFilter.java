package com.example.UserManagementSystem.Security;

import com.example.UserManagementSystem.Entity.TokenType;
import com.example.UserManagementSystem.Service.AuthTokenService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final AuthTokenService authTokenService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String email;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);

        try {
            email = jwtService.extractUsername(jwt);
            String tokenId = jwtService.extractTokenId(jwt);
            boolean accessToken = jwtService.isAccessToken(jwt);
            log.info("JWT filter received request: path={}, email={}, tokenId={}, accessToken={}",
                    request.getRequestURI(), email, tokenId, accessToken);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                boolean jwtValid = jwtService.isTokenValid(jwt, userDetails);
                boolean tokenActive = authTokenService.isTokenActive(jwt, TokenType.ACCESS);
                log.info("JWT auth evaluation: email={}, jwtValid={}, tokenActive={}", email, jwtValid, tokenActive);

                if (jwtValid && tokenActive) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.info("JWT authentication success: email={}, tokenId={}", email, tokenId);
                } else {
                    log.warn("JWT authentication rejected: email={}, tokenId={}, jwtValid={}, tokenActive={}",
                            email, tokenId, jwtValid, tokenActive);
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            log.warn("JWT authentication exception on path={}: {}", request.getRequestURI(), ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}

