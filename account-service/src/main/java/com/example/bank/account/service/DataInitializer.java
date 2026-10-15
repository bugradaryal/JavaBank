package com.example.bank.account.service;

import com.example.bank.account.domain.AppUser;
import com.example.bank.common.domain.Role;
import com.example.bank.account.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** İlk açılışta personel hesaplarını oluşturur (admin ve teller). Şifreler application.yml'den gelir. */
@Component
public class DataInitializer implements CommandLineRunner {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String tellerPassword;

    public DataInitializer(AppUserRepository users, PasswordEncoder passwordEncoder,
                           @Value("${app.bootstrap.admin-password}") String adminPassword,
                           @Value("${app.bootstrap.teller-password}") String tellerPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.tellerPassword = tellerPassword;
    }

    @Override
    public void run(String... args) {
        createIfMissing("admin", adminPassword, Role.ADMIN);
        createIfMissing("teller", tellerPassword, Role.TELLER);
    }

    private void createIfMissing(String username, String password, Role role) {
        if (!users.existsByUsername(username)) {
            users.save(new AppUser(username, passwordEncoder.encode(password), role, null));
        }
    }
}
