package com.example.bank.account.service;

import com.example.bank.account.domain.AppUser;
import com.example.bank.account.domain.Customer;
import com.example.bank.common.domain.Role;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import com.example.bank.account.repository.AppUserRepository;
import com.example.bank.account.repository.CustomerRepository;
import com.example.bank.common.security.JwtService;
import com.example.bank.account.web.dto.AuthResponse;
import com.example.bank.account.web.dto.LoginRequest;
import com.example.bank.account.web.dto.RegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(AppUserRepository users, CustomerRepository customers, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService audit) {
        this.users = users;
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    /** Yeni müşteri + giriş kullanıcısı oluşturur. Kullanıcı adı e-posta adresidir. */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalize(req.email());
        if (customers.existsByEmail(email) || users.existsByUsername(email)) {
            throw new BankException(ErrorCode.EMAIL_ALREADY_USED, "Bu e-posta ile zaten bir kayıt var");
        }
        Customer customer = customers.save(new Customer(req.fullName().trim(), email, req.phone()));
        AppUser user = users.save(new AppUser(email, passwordEncoder.encode(req.password()),
                Role.CUSTOMER, customer.getId()));
        audit.record(email, "CUSTOMER_REGISTERED", "Customer", customer.getId(), null);
        return toResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String username = normalize(req.username());
        AppUser user = users.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Bu metot hata fırlatıp geri alınacağı için log ayrı transaction'da yazılır.
            audit.recordIndependent(username, "LOGIN_FAILED", "AppUser", user == null ? null : user.getId(), null);
            throw new BankException(ErrorCode.INVALID_CREDENTIALS, "Kullanıcı adı veya şifre hatalı");
        }
        audit.record(username, "LOGIN_SUCCESS", "AppUser", user.getId(), null);
        return toResponse(user);
    }

    private AuthResponse toResponse(AppUser user) {
        return new AuthResponse(jwtService.generate(user.getId(), user.getUsername(), user.getRole(), user.getCustomerId()), "Bearer", jwtService.getTtlSeconds(),
                user.getRole(), user.getCustomerId());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
