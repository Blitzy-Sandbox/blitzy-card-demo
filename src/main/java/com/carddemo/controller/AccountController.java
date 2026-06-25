package com.carddemo.controller;

import com.carddemo.dto.AccountDto;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for account inquiry and maintenance under {@code /api/accounts}.
 *
 * <p>Exposes the consolidated account view ({@code GET /api/accounts/{id}}) and the
 * account maintenance update ({@code PUT /api/accounts/{id}}). The controller is a
 * stateless HTTP boundary: it binds and validates the request, delegates to the
 * {@link AccountViewService} and {@link AccountUpdateService} collaborators, and
 * returns their result. It performs no business logic, no data access and no inline
 * error handling; not-found, validation and optimistic-lock conditions are raised by
 * the services and rendered centrally by the global exception handler.</p>
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountViewService accountViewService;

    private final AccountUpdateService accountUpdateService;

    /**
     * Creates the controller with its service collaborators.
     *
     * @param accountViewService   service backing the account view inquiry
     * @param accountUpdateService service backing the account maintenance update
     */
    public AccountController(AccountViewService accountViewService,
            AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    /**
     * Returns the consolidated view of a single account.
     *
     * @param id the account identifier
     * @return {@code 200 OK} with the account view response
     */
    @GetMapping("/{id}")
    public ResponseEntity<AccountDto.ViewResponse> getAccount(@PathVariable("id") Long id) {
        return ResponseEntity.ok(accountViewService.getAccount(id));
    }

    /**
     * Applies a maintenance update to a single account and returns the refreshed view.
     *
     * @param id      the account identifier
     * @param request the validated account update request body
     * @return {@code 200 OK} with the updated account view response
     */
    @PutMapping("/{id}")
    public ResponseEntity<AccountDto.ViewResponse> updateAccount(@PathVariable("id") Long id,
            @Valid @RequestBody AccountDto.UpdateRequest request) {
        return ResponseEntity.ok(accountUpdateService.updateAccount(id, request));
    }
}
