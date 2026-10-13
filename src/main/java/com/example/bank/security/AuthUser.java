package com.example.bank.security;

import com.example.bank.domain.Role;

/** JWT'den çözülen, SecurityContext'te principal olarak taşınan kullanıcı. */
public record AuthUser(Long userId, String username, Role role, Long customerId) {

    public boolean isStaff() {
        return role == Role.TELLER || role == Role.ADMIN;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
