package com.example.bank.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Email @Size(max = 200) String email,
        @Size(max = 30) String phone) {
}
