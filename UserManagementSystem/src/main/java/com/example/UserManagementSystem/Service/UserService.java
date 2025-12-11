package com.example.UserManagementSystem.Service;

import com.example.UserManagementSystem.DTO.*;
import com.example.UserManagementSystem.Entity.Role;
import com.example.UserManagementSystem.Entity.User;
import com.example.UserManagementSystem.Repository.RoleRepository;
import com.example.UserManagementSystem.Repository.UserRepository;
import com.example.UserManagementSystem.Security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserEventPublisher userEventPublisher;

    public UserResponse register(RegisterRequest request) {

        // 1. Check if email already used
        userRepository.findByEmail(request.getEmail())
                .ifPresent(u -> {
                    throw new IllegalArgumentException("Email already in use");
                });



        // 2. Get default ROLE_USER
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("Default role ROLE_USER not found"));

        // 3. Build User entity
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(userRole))
                .build();

        // 4. Save
        User saved = userRepository.save(user);

// publish event
        userEventPublisher.publishUserEvent(
                UserActivityEvent.builder()
                        .userId(saved.getId())
                        .email(saved.getEmail())
                        .type("REGISTER")
                        .timestamp(Instant.now())
                        .roles(
                                saved.getRoles().stream()
                                        .map(Role::getName)
                                        .collect(Collectors.toSet())
                        )
                        .build()
        );
        // 5. Map to response
        return UserResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .email(saved.getEmail())
                .roles(
                        saved.getRoles()
                                .stream()
                                .map(Role::getName)
                                .collect(Collectors.toSet())
                )
                .build();
    }


    public AuthResponse login(LoginRequest request) {
        // 1. Find user
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // 2. Check password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        // 3. Build UserDetails
        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                user.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority(role.getName()))
                        .collect(Collectors.toSet())
        );

        // 4. Generate token
        String token = jwtService.generateToken(userDetails);

        // 5. Build UserResponse
        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(
                        user.getRoles().stream()
                                .map(Role::getName)
                                .collect(Collectors.toSet())
                )
                .build();
        userEventPublisher.publishUserEvent(
                UserActivityEvent.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .type("LOGIN")
                        .timestamp(Instant.now())
                        .roles(
                                user.getRoles().stream()
                                        .map(Role::getName)
                                        .collect(Collectors.toSet())
                        )
                        .build()
        );
        // 6. Return AuthResponse
        return AuthResponse.builder()
                .token(token)
                .user(userResponse)
                .build();
    }
    @Cacheable(value = "users", key = "#email")
    public UserResponse getByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(
                        user.getRoles().stream()
                                .map(Role::getName)
                                .collect(Collectors.toSet())
                )
                .build();
    }
    @PreAuthorize("hasRole('ADMIN')")
    public Role createRole(String roleName) {
        String normalized = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
        return roleRepository.findByName(normalized)
                .orElseGet(() -> roleRepository.save(Role.builder().name(normalized).build()));
    }

    // ---------- ADMIN: ASSIGN ROLES TO USER ----------
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse assignRolesToUser(Long userId, List<String> roleNames) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Set<Role> roles = roleNames.stream()
                .map(rn -> rn.startsWith("ROLE_") ? rn : "ROLE_" + rn)
                .map(name -> roleRepository.findByName(name)
                        .orElseThrow(() -> new IllegalArgumentException("Role not found: " + name)))
                .collect(Collectors.toSet());

        user.getRoles().addAll(roles);
        User saved = userRepository.save(user);

        return mapToUserResponse(saved);
    }

    // ---------- ADMIN: STATS ----------
    @PreAuthorize("hasRole('ADMIN')")
    public AdminStatsResponse getAdminStats() {
        long totalUsers = userRepository.count();
        long adminCount = userRepository.findAll().stream()
                .filter(u -> u.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName())))
                .count();

        return AdminStatsResponse.builder()
                .totalUsers(totalUsers)
                .adminUsers(adminCount)
                .build();
    }

    // helper mapping method
    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(
                        user.getRoles().stream()
                                .map(Role::getName)
                                .collect(Collectors.toSet())
                )
                .build();
    }
}


