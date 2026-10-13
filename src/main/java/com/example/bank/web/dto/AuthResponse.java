package com.example.bank.web.dto;

import com.example.bank.domain.Role;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        Role role,
        Long customerId) {
}
