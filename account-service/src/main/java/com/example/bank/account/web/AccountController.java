package com.example.bank.account.web;

import com.example.bank.account.service.AccountService;
import com.example.bank.account.web.dto.AccountResponse;
import com.example.bank.account.web.dto.LedgerEntryResponse;
import com.example.bank.account.web.dto.OpenAccountRequest;
import com.example.bank.common.security.AuthUser;
import com.example.bank.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Para yatırma/çekme/transfer artık transfer-service'te; burada sadece hesap yönetimi ve okuma var. */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
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
}
