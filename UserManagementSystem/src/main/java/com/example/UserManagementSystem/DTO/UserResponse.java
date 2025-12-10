package com.example.UserManagementSystem.DTO;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private Set<String> roles;
}

