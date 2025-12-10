package com.example.UserManagementSystem.DTO;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminStatsResponse {
    private long totalUsers;
    private long adminUsers;
}
