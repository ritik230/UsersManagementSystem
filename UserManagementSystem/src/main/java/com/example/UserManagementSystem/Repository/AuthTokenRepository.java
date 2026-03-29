package com.example.UserManagementSystem.Repository;

import com.example.UserManagementSystem.Entity.AuthToken;
import com.example.UserManagementSystem.Entity.TokenType;
import com.example.UserManagementSystem.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenId(String tokenId);
    List<AuthToken> findAllByUserAndTokenTypeAndRevokedFalse(User user, TokenType tokenType);
}
