package com.example.UserManagementSystem.Controller;

import com.example.UserManagementSystem.DTO.AdminStatsResponse;
import com.example.UserManagementSystem.DTO.UserResponse;
import com.example.UserManagementSystem.Entity.Role;
import com.example.UserManagementSystem.Service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    // Create a role (e.g. ROLE_MANAGER, ROLE_ADMIN)
    @PostMapping("/roles")
    public ResponseEntity<Role> createRole(@RequestParam String name) {
        Role role = userService.createRole(name);
        return ResponseEntity.ok(role);
    }

    // Assign roles to a user
    @PostMapping("/users/{userId}/roles")
    public ResponseEntity<UserResponse> assignRoles(
            @PathVariable Long userId,
            @RequestBody List<String> roles) {
        UserResponse user = userService.assignRolesToUser(userId, roles);
        return ResponseEntity.ok(user);
    }

    // Admin stats
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> stats() {
        AdminStatsResponse stats = userService.getAdminStats();
        return ResponseEntity.ok(stats);
    }
}
