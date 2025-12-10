package com.example.UserManagementSystem.Controller;

import com.example.UserManagementSystem.DTO.AuthResponse;
import com.example.UserManagementSystem.DTO.LoginRequest;
import com.example.UserManagementSystem.DTO.RegisterRequest;
import com.example.UserManagementSystem.DTO.UserResponse;
import com.example.UserManagementSystem.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController

@RequestMapping("/api/users")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = userService.register(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName(); // subject from JWT

        UserResponse user = userService.getByEmail(email); // you implement this

        return ResponseEntity.ok(user);
    }

}

