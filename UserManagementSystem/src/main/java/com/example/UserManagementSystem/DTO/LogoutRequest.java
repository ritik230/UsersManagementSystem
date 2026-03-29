package com.example.UserManagementSystem.DTO;

import lombok.Data;

@Data
public class LogoutRequest {
    private String refreshToken;
}
