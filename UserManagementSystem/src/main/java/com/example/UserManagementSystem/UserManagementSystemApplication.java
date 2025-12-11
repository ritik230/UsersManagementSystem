package com.example.UserManagementSystem;

import com.example.UserManagementSystem.Entity.Role;
import com.example.UserManagementSystem.Entity.User;
import com.example.UserManagementSystem.Repository.RoleRepository;
import com.example.UserManagementSystem.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Bean;

import java.util.Set;

@SpringBootApplication
@RequiredArgsConstructor
@EnableCaching
public class UserManagementSystemApplication {
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    public static void main(String[] args) {
		SpringApplication.run(UserManagementSystemApplication.class, args);
	}
    @Bean
    CommandLineRunner initRoles(RoleRepository roleRepository) {
        return args -> {
            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));

            Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                    .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_ADMIN").build()));

            // Ensure admin user exists
            userRepository.findByEmail("admin@example.com")
                    .orElseGet(() -> {
                        User admin = User.builder()
                                .name("Admin")
                                .email("admin@example.com")
                                .password(passwordEncoder.encode("Admin@123")) // remember this
                                .roles(Set.of(userRole, adminRole))
                                .build();
                        return userRepository.save(admin);
                    });
        };
    }


}
