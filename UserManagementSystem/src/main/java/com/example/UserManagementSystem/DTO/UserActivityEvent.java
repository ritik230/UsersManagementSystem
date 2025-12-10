package com.example.UserManagementSystem.DTO;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

@Data
@Builder
public class UserActivityEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String email;
    private String type;      // REGISTER, LOGIN
    private Instant timestamp;
    private Set<String> roles;
}
