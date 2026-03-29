package com.example.UserManagementSystem.Controller;

import com.example.UserManagementSystem.DTO.AuthResponse;
import com.example.UserManagementSystem.DTO.UserResponse;
import com.example.UserManagementSystem.Service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerWebMvcTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerReturnsCreatedResponse() throws Exception {
        UserResponse response = UserResponse.builder()
                .id(1L)
                .name("Alice")
                .email("alice@example.com")
                .roles(Set.of("ROLE_USER"))
                .build();

        when(userService.register(any())).thenReturn(response);

        mockMvc.perform(post("/api/users/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RegisterBody("Alice", "alice@example.com", "Password@123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.roles", hasItem("ROLE_USER")));
    }

    @Test
    void registerRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RegisterBody("", "bad-email", "123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsTokenAndUser() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .token("jwt-token")
                .refreshToken("refresh-token")
                .user(UserResponse.builder()
                        .id(1L)
                        .name("Alice")
                        .email("alice@example.com")
                        .roles(Set.of("ROLE_USER"))
                        .build())
                .build();

        when(userService.login(any())).thenReturn(response);

        mockMvc.perform(post("/api/users/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginBody("alice@example.com", "Password@123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"));
    }

    @Test
    void loginRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/users/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginBody("bad-email", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meReturnsCurrentUserDetails() {
        UserResponse response = UserResponse.builder()
                .id(1L)
                .name("Alice")
                .email("alice@example.com")
                .roles(Set.of("ROLE_USER"))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice@example.com", "n/a")
        );
        when(userService.getByEmail("alice@example.com")).thenReturn(response);

        UserResponse body = authController.me().getBody();

        assertEquals("alice@example.com", body.getEmail());
        verify(userService).getByEmail("alice@example.com");
    }

    @Test
    void refreshReturnsNewTokens() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .token("new-access-token")
                .refreshToken("new-refresh-token")
                .user(UserResponse.builder()
                        .id(1L)
                        .name("Alice")
                        .email("alice@example.com")
                        .roles(Set.of("ROLE_USER"))
                        .build())
                .build();

        when(userService.refreshToken("refresh-token")).thenReturn(response);

        mockMvc.perform(post("/api/users/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshBody("refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    void logoutReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/users/logout")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LogoutBody("refresh-token"))))
                .andExpect(status().isNoContent());

        verify(userService).logout("access-token", "refresh-token");
    }

    private record RegisterBody(String name, String email, String password) {
    }

    private record LoginBody(String email, String password) {
    }

    private record RefreshBody(String refreshToken) {
    }

    private record LogoutBody(String refreshToken) {
    }
}
