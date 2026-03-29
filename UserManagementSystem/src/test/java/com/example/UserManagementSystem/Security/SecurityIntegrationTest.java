package com.example.UserManagementSystem.Security;

import com.example.UserManagementSystem.Entity.AuthToken;
import com.example.UserManagementSystem.Entity.TokenType;
import com.example.UserManagementSystem.Entity.User;
import com.example.UserManagementSystem.Repository.AuthTokenRepository;
import com.example.UserManagementSystem.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=h2")
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Test
    void publicRegisterEndpointIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType("application/json")
                        .content("{\"name\":\"\",\"email\":\"bad-email\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedMeEndpointRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void adminCanAccessStatsEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").isNumber())
                .andExpect(jsonPath("$.adminUsers").isNumber());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "USER")
    void nonAdminCannotAccessStatsEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void authenticatedUserCanReadOwnProfile() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"));
    }

    @Test
    void activeAccessTokenAuthenticatesRequest() throws Exception {
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();
        String accessToken = jwtService.generateAccessToken(
                new org.springframework.security.core.userdetails.User(
                        admin.getEmail(),
                        admin.getPassword(),
                        admin.getRoles().stream()
                                .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority(role.getName()))
                                .toList()
                )
        );

        authTokenRepository.save(AuthToken.builder()
                .tokenId(jwtService.extractTokenId(accessToken))
                .tokenType(TokenType.ACCESS)
                .expiresAt(jwtService.extractExpiration(accessToken).toInstant())
                .revoked(false)
                .user(admin)
                .build());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"));
    }

    @Test
    void revokedAccessTokenIsRejected() throws Exception {
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();
        String accessToken = jwtService.generateAccessToken(
                new org.springframework.security.core.userdetails.User(
                        admin.getEmail(),
                        admin.getPassword(),
                        admin.getRoles().stream()
                                .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority(role.getName()))
                                .toList()
                )
        );

        authTokenRepository.save(AuthToken.builder()
                .tokenId(jwtService.extractTokenId(accessToken))
                .tokenType(TokenType.ACCESS)
                .expiresAt(jwtService.extractExpiration(accessToken).toInstant())
                .revoked(true)
                .user(admin)
                .build());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }
}
