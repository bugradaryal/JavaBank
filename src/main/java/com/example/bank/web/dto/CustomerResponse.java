package com.example.bank.web.dto;

import com.example.bank.domain.Customer;

import java.time.Instant;

public record CustomerResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        Instant createdAt) {

    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getCreatedAt());
    }
}
