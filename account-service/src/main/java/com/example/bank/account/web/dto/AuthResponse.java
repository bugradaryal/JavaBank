package com.example.bank.account.web.dto;

import com.example.bank.common.domain.Role;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        Role role,
        Long customerId) {
}
