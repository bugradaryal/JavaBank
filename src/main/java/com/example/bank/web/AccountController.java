package com.example.bank.web;

import com.example.bank.security.AuthUser;
import com.example.bank.service.AccountService;
import com.example.bank.service.TransferService;
import com.example.bank.web.dto.AccountResponse;
import com.example.bank.web.dto.LedgerEntryResponse;
import com.example.bank.web.dto.MoneyRequest;
import com.example.bank.web.dto.OpenAccountRequest;
import com.example.bank.web.dto.PageResponse;
import com.example.bank.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransferService transferService;

    public AccountController(AccountService accountService, TransferService transferService) {
        this.accountService = accountService;
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.open(request, user));
    }

    @GetMapping
    public PageResponse<AccountResponse> list(@RequestParam(required = false) Long customerId,
                                              @AuthenticationPrincipal AuthUser user, Pageable pageable) {
        return accountService.list(customerId, user, pageable);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return accountService.get(id, user);
    }

    @GetMapping("/{id}/ledger")
    public PageResponse<LedgerEntryResponse> ledger(@PathVariable Long id, @AuthenticationPrincipal AuthUser user,
                                                    Pageable pageable) {
        return accountService.ledger(id, user, pageable);
    }

    @PostMapping("/{id}/close")
    public AccountResponse close(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return accountService.close(id, user);
    }

    @PostMapping("/{id}/deposit")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public ResponseEntity<TransferResponse> deposit(
            @PathVariable Long id, @Valid @RequestBody MoneyRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        return TransferResponses.of(transferService.deposit(user, id, request, idempotencyKey));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public ResponseEntity<TransferResponse> withdraw(
            @PathVariable Long id, @Valid @RequestBody MoneyRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        return TransferResponses.of(transferService.withdraw(user, id, request, idempotencyKey));
    }
}
