package com.example.bank.web;

import com.example.bank.security.AuthUser;
import com.example.bank.service.CustomerService;
import com.example.bank.web.dto.CustomerRequest;
import com.example.bank.web.dto.CustomerResponse;
import com.example.bank.web.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request,
                                                   @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(request, user));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return customerService.list(pageable);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return customerService.get(id, user);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request,
                                   @AuthenticationPrincipal AuthUser user) {
        return customerService.update(id, request, user);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        customerService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
