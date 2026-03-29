package com.example.UserManagementSystem.Service;

import com.example.UserManagementSystem.Entity.AuthToken;
import com.example.UserManagementSystem.Entity.TokenType;
import com.example.UserManagementSystem.Entity.User;
import com.example.UserManagementSystem.Repository.AuthTokenRepository;
import com.example.UserManagementSystem.Security.JwtService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthTokenService {

    private final AuthTokenRepository authTokenRepository;
    private final JwtService jwtService;

    @Transactional
    public void saveToken(User user, String token, TokenType tokenType) {
        String tokenId = jwtService.extractTokenId(token);
        Instant expiresAt = jwtService.extractExpiration(token).toInstant();
        authTokenRepository.save(AuthToken.builder()
                .tokenId(tokenId)
                .tokenType(tokenType)
                .expiresAt(expiresAt)
                .revoked(false)
                .user(user)
                .build());
        log.info("Saved auth token: email={}, tokenId={}, tokenType={}, expiresAt={}",
                user.getEmail(), tokenId, tokenType, expiresAt);
    }

    public boolean isTokenActive(String token, TokenType tokenType) {
        String tokenId = jwtService.extractTokenId(token);
        Instant now = Instant.now();
        AuthToken stored = authTokenRepository.findByTokenId(tokenId).orElse(null);
        boolean active = stored != null
                && stored.getTokenType() == tokenType
                && !stored.isRevoked()
                && stored.getExpiresAt().isAfter(now);
        if (stored == null) {
            log.info("Auth token check: tokenId={}, tokenType={}, active=false, reason=NOT_FOUND", tokenId, tokenType);
        } else {
            log.info(
                    "Auth token check: tokenId={}, requestedType={}, storedType={}, revoked={}, expiresAt={}, now={}, active={}",
                    tokenId,
                    tokenType,
                    stored.getTokenType(),
                    stored.isRevoked(),
                    stored.getExpiresAt(),
                    now,
                    active
            );
        }
        return active;
    }

    @Transactional
    public void revokeToken(String token) {
        String tokenId = jwtService.extractTokenId(token);
        authTokenRepository.findByTokenId(tokenId).ifPresent(stored -> {
            stored.setRevoked(true);
            authTokenRepository.save(stored);
            log.info("Revoked single token: email={}, tokenId={}, tokenType={}",
                    stored.getUser().getEmail(), tokenId, stored.getTokenType());
        });
    }

    @Transactional
    public void revokeAllTokens(User user) {
        revokeTokens(user, TokenType.ACCESS);
        revokeTokens(user, TokenType.REFRESH);
    }

    @Transactional
    public void revokeTokens(User user, TokenType tokenType) {
        List<AuthToken> tokens = authTokenRepository.findAllByUserAndTokenTypeAndRevokedFalse(user, tokenType);
        if (tokens.isEmpty()) {
            log.info("No active tokens to revoke: email={}, tokenType={}", user.getEmail(), tokenType);
            return;
        }

        tokens.forEach(token -> token.setRevoked(true));
        authTokenRepository.saveAll(tokens);
        log.info("Revoked {} tokens for email={}, tokenType={}", tokens.size(), user.getEmail(), tokenType);
    }
}
