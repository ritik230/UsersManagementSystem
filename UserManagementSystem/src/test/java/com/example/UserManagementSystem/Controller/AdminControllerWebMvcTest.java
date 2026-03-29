package com.example.UserManagementSystem.Controller;

import com.example.UserManagementSystem.DTO.AdminStatsResponse;
import com.example.UserManagementSystem.DTO.UserResponse;
import com.example.UserManagementSystem.Entity.Role;
import com.example.UserManagementSystem.Service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminControllerWebMvcTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AdminController adminController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void createRoleReturnsRolePayload() throws Exception {
        when(userService.createRole("MANAGER")).thenReturn(Role.builder().id(3L).name("ROLE_MANAGER").build());

        mockMvc.perform(post("/api/admin/roles")
                        .param("name", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ROLE_MANAGER"));
    }

    @Test
    void assignRolesReturnsUpdatedUser() throws Exception {
        UserResponse response = UserResponse.builder()
                .id(7L)
                .name("Bob")
                .email("bob@example.com")
                .roles(Set.of("ROLE_USER", "ROLE_ADMIN"))
                .build();

        when(userService.assignRolesToUser(eq(7L), anyList())).thenReturn(response);

        mockMvc.perform(post("/api/admin/users/7/roles")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void statsReturnsAdminSummary() throws Exception {
        when(userService.getAdminStats()).thenReturn(AdminStatsResponse.builder()
                .totalUsers(5L)
                .adminUsers(2L)
                .build());

        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(5))
                .andExpect(jsonPath("$.adminUsers").value(2));
    }

    @Test
    void directControllerMethodDelegatesToService() {
        AdminStatsResponse response = AdminStatsResponse.builder()
                .totalUsers(5L)
                .adminUsers(2L)
                .build();
        when(userService.getAdminStats()).thenReturn(response);

        AdminStatsResponse body = adminController.stats().getBody();

        assertEquals(5L, body.getTotalUsers());
        assertEquals(2L, body.getAdminUsers());
    }
}
