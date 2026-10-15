package com.example.bank.account.service;

import com.example.bank.account.domain.Customer;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import com.example.bank.account.repository.AccountRepository;
import com.example.bank.account.repository.AppUserRepository;
import com.example.bank.account.repository.CustomerRepository;
import com.example.bank.common.security.AuthUser;
import com.example.bank.account.web.dto.CustomerRequest;
import com.example.bank.account.web.dto.CustomerResponse;
import com.example.bank.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final AppUserRepository users;
    private final AuditService audit;

    public CustomerService(CustomerRepository customers, AccountRepository accounts, AppUserRepository users,
                           AuditService audit) {
        this.customers = customers;
        this.accounts = accounts;
        this.users = users;
        this.audit = audit;
    }

    /** Gişe/admin, giriş kullanıcısı olmayan bir müşteri kaydı açar. */
    @Transactional
    public CustomerResponse create(CustomerRequest req, AuthUser actor) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (customers.existsByEmail(email)) {
            throw new BankException(ErrorCode.EMAIL_ALREADY_USED, "Bu e-posta ile zaten bir müşteri var");
        }
        Customer c = customers.save(new Customer(req.fullName().trim(), email, req.phone()));
        audit.record(actor.username(), "CUSTOMER_CREATED", "Customer", c.getId(), null);
        return CustomerResponse.from(c);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id, AuthUser actor) {
        authorize(id, actor);
        return CustomerResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return PageResponse.from(customers.findAll(pageable), CustomerResponse::from);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest req, AuthUser actor) {
        authorize(id, actor);
        Customer c = find(id);
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (customers.existsByEmailAndIdNot(email, id)) {
            throw new BankException(ErrorCode.EMAIL_ALREADY_USED, "Bu e-posta başka bir müşteride kayıtlı");
        }
        c.update(req.fullName().trim(), email, req.phone());
        audit.record(actor.username(), "CUSTOMER_UPDATED", "Customer", id, null);
        return CustomerResponse.from(c);
    }

    /** Sadece ADMIN. Hesabı olan müşteri silinemez. */
    @Transactional
    public void delete(Long id, AuthUser actor) {
        Customer c = find(id);
        if (accounts.existsByCustomerId(id)) {
            throw new BankException(ErrorCode.CUSTOMER_HAS_ACCOUNTS, "Hesabı olan müşteri silinemez");
        }
        users.deleteByCustomerId(id);
        customers.delete(c);
        audit.record(actor.username(), "CUSTOMER_DELETED", "Customer", id, null);
    }

    private Customer find(Long id) {
        return customers.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, "Müşteri bulunamadı: " + id));
    }

    private void authorize(Long customerId, AuthUser actor) {
        if (!actor.isStaff() && !Objects.equals(actor.customerId(), customerId)) {
            throw new BankException(ErrorCode.ACCESS_DENIED, "Bu müşteri kaydına erişimin yok");
        }
    }
}
