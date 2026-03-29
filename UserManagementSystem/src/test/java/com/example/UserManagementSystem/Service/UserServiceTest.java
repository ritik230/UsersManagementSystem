package com.example.UserManagementSystem.Service;

import com.example.UserManagementSystem.DTO.AdminStatsResponse;
import com.example.UserManagementSystem.DTO.AuthResponse;
import com.example.UserManagementSystem.DTO.LoginRequest;
import com.example.UserManagementSystem.DTO.RegisterRequest;
import com.example.UserManagementSystem.DTO.UserActivityEvent;
import com.example.UserManagementSystem.DTO.UserResponse;
import com.example.UserManagementSystem.Entity.Role;
import com.example.UserManagementSystem.Entity.TokenType;
import com.example.UserManagementSystem.Entity.User;
import com.example.UserManagementSystem.Repository.RoleRepository;
import com.example.UserManagementSystem.Repository.UserRepository;
import com.example.UserManagementSystem.Security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserEventPublisher userEventPublisher;

    @Mock
    private AuthTokenService authTokenService;

    @InjectMocks
    private UserService userService;

    @Test
    void registerCreatesUserAndPublishesRegisterEvent() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Alice");
        request.setEmail("alice@example.com");
        request.setPassword("Password@123");

        Role userRole = role("ROLE_USER");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("Password@123")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        UserResponse response = userService.register(request);

        assertEquals(10L, response.getId());
        assertEquals("Alice", response.getName());
        assertEquals("alice@example.com", response.getEmail());
        assertEquals(Set.of("ROLE_USER"), response.getRoles());

        ArgumentCaptor<UserActivityEvent> eventCaptor = ArgumentCaptor.forClass(UserActivityEvent.class);
        verify(userEventPublisher).publishUserEvent(eventCaptor.capture());
        UserActivityEvent event = eventCaptor.getValue();
        assertEquals("REGISTER", event.getType());
        assertEquals("alice@example.com", event.getEmail());
        assertEquals(Set.of("ROLE_USER"), event.getRoles());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Alice");
        request.setEmail("alice@example.com");
        request.setPassword("Password@123");

        when(userRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(user(1L, "Alice", "alice@example.com", "encoded", Set.of(role("ROLE_USER")))));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.register(request));

        assertEquals("Email already in use", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
        verify(userEventPublisher, never()).publishUserEvent(any(UserActivityEvent.class));
    }

    @Test
    void registerFailsWhenDefaultRoleIsMissing() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Alice");
        request.setEmail("alice@example.com");
        request.setPassword("Password@123");

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> userService.register(request));

        assertEquals("Default role ROLE_USER not found", exception.getMessage());
    }

    @Test
    void loginReturnsAccessAndRefreshTokensAndPublishesLoginEvent() {
        Role userRole = role("ROLE_USER");
        User storedUser = user(5L, "Alice", "alice@example.com", "encoded-password", Set.of(userRole));

        LoginRequest request = new LoginRequest();
        request.setEmail("alice@example.com");
        request.setPassword("Password@123");

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("Password@123", "encoded-password")).thenReturn(true);
        when(jwtService.generateAccessToken(any(UserDetails.class))).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(UserDetails.class))).thenReturn("refresh-token");

        AuthResponse response = userService.login(request);

        assertEquals("access-token", response.getToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals("alice@example.com", response.getUser().getEmail());
        assertEquals(Set.of("ROLE_USER"), response.getUser().getRoles());
        verify(authTokenService).revokeAllTokens(storedUser);
        verify(authTokenService).saveToken(storedUser, "access-token", TokenType.ACCESS);
        verify(authTokenService).saveToken(storedUser, "refresh-token", TokenType.REFRESH);
        verify(userEventPublisher).publishUserEvent(argThat(event ->
                "LOGIN".equals(event.getType()) && "alice@example.com".equals(event.getEmail())));
    }

    @Test
    void loginRejectsUnknownEmail() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@example.com");
        request.setPassword("Password@123");

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.login(request));

        assertEquals("Invalid email or password", exception.getMessage());
        verify(userEventPublisher, never()).publishUserEvent(any(UserActivityEvent.class));
    }

    @Test
    void loginRejectsWrongPassword() {
        User storedUser = user(5L, "Alice", "alice@example.com", "encoded-password", Set.of(role("ROLE_USER")));

        LoginRequest request = new LoginRequest();
        request.setEmail("alice@example.com");
        request.setPassword("wrong-password");

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(storedUser));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.login(request));

        assertEquals("Invalid email or password", exception.getMessage());
        verify(jwtService, never()).generateAccessToken(any(UserDetails.class));
    }

    @Test
    void refreshTokenRotatesExistingTokens() {
        User storedUser = user(5L, "Alice", "alice@example.com", "encoded-password", Set.of(role("ROLE_USER")));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(storedUser));
        when(jwtService.extractUsername("refresh-token")).thenReturn("alice@example.com");
        when(jwtService.isRefreshTokenValid(any(String.class), any(UserDetails.class))).thenReturn(true);
        when(authTokenService.isTokenActive("refresh-token", TokenType.REFRESH)).thenReturn(true);
        when(jwtService.generateAccessToken(any(UserDetails.class))).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(any(UserDetails.class))).thenReturn("new-refresh-token");

        AuthResponse response = userService.refreshToken("refresh-token");

        assertEquals("new-access-token", response.getToken());
        assertEquals("new-refresh-token", response.getRefreshToken());
        verify(authTokenService).revokeAllTokens(storedUser);
        verify(authTokenService).saveToken(storedUser, "new-access-token", TokenType.ACCESS);
        verify(authTokenService).saveToken(storedUser, "new-refresh-token", TokenType.REFRESH);
    }

    @Test
    void refreshTokenRejectsRevokedToken() {
        User storedUser = user(5L, "Alice", "alice@example.com", "encoded-password", Set.of(role("ROLE_USER")));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(storedUser));
        when(jwtService.extractUsername("refresh-token")).thenReturn("alice@example.com");
        when(jwtService.isRefreshTokenValid(any(String.class), any(UserDetails.class))).thenReturn(true);
        when(authTokenService.isTokenActive("refresh-token", TokenType.REFRESH)).thenReturn(false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.refreshToken("refresh-token"));

        assertEquals("Invalid refresh token", exception.getMessage());
    }

    @Test
    void logoutRevokesProvidedTokens() {
        userService.logout("access-token", "refresh-token");

        verify(authTokenService).revokeToken("access-token");
        verify(authTokenService).revokeToken("refresh-token");
    }

    @Test
    void getByEmailMapsUserResponse() {
        User storedUser = user(5L, "Alice", "alice@example.com", "encoded-password",
                Set.of(role("ROLE_USER"), role("ROLE_ADMIN")));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(storedUser));

        UserResponse response = userService.getByEmail("alice@example.com");

        assertEquals(5L, response.getId());
        assertEquals("Alice", response.getName());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), response.getRoles());
    }

    @Test
    void getByEmailRejectsMissingUser() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.getByEmail("missing@example.com"));

        assertEquals("User not found", exception.getMessage());
    }

    @Test
    void createRoleNormalizesNameAndSavesWhenMissing() {
        when(roleRepository.findByName("ROLE_MANAGER")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role created = userService.createRole("MANAGER");

        assertEquals("ROLE_MANAGER", created.getName());
    }

    @Test
    void assignRolesToUserAddsResolvedRoles() {
        User storedUser = user(8L, "Bob", "bob@example.com", "encoded-password",
                new HashSet<>(Set.of(role("ROLE_USER"))));
        Role adminRole = role("ROLE_ADMIN");

        when(userRepository.findById(8L)).thenReturn(Optional.of(storedUser));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.assignRolesToUser(8L, List.of("ADMIN"));

        assertTrue(response.getRoles().contains("ROLE_ADMIN"));
        assertTrue(response.getRoles().contains("ROLE_USER"));
    }

    @Test
    void assignRolesToUserRejectsUnknownRole() {
        User storedUser = user(8L, "Bob", "bob@example.com", "encoded-password", Set.of(role("ROLE_USER")));

        when(userRepository.findById(8L)).thenReturn(Optional.of(storedUser));
        when(roleRepository.findByName("ROLE_MANAGER")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.assignRolesToUser(8L, List.of("MANAGER")));

        assertEquals("Role not found: ROLE_MANAGER", exception.getMessage());
    }

    @Test
    void getAdminStatsCountsAdminUsers() {
        User admin = user(1L, "Admin", "admin@example.com", "encoded", Set.of(role("ROLE_ADMIN"), role("ROLE_USER")));
        User regular = user(2L, "Bob", "bob@example.com", "encoded", Set.of(role("ROLE_USER")));

        when(userRepository.count()).thenReturn(2L);
        when(userRepository.findAll()).thenReturn(List.of(admin, regular));

        AdminStatsResponse response = userService.getAdminStats();

        assertEquals(2L, response.getTotalUsers());
        assertEquals(1L, response.getAdminUsers());
        assertFalse(response.getAdminUsers() > response.getTotalUsers());
    }

    private static Role role(String name) {
        return Role.builder().name(name).build();
    }

    private static User user(Long id, String name, String email, String password, Set<Role> roles) {
        return User.builder()
                .id(id)
                .name(name)
                .email(email)
                .password(password)
                .roles(roles)
                .build();
    }
}
