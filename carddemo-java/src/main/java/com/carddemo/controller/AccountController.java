package com.carddemo.controller;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.AccountUpdateRequest;
import com.carddemo.model.dto.AccountUpdateResponse;
import com.carddemo.model.dto.AccountViewResponse;
import com.carddemo.service.account.AccountUpdateService;
import com.carddemo.service.account.AccountViewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account REST controller. Re-platforms the CICS account-view (COACTVWC) and
 * account-update (COACTUPC) programs and their BMS screens COACTVW / COACTUP
 * (reference only, lineage commit 27d6c6f). Stateless JSON endpoints replace the
 * pseudo-conversational COMMAREA flow.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountViewService accountViewService;
    private final AccountUpdateService accountUpdateService;

    public AccountController(AccountViewService accountViewService,
                            AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    @GetMapping("/{accountId}")
    public AccountViewResponse getAccount(@PathVariable Long accountId) {
        return accountViewService.getAccountView(accountId);
    }

    @PutMapping("/{accountId}")
    public AccountUpdateResponse updateAccount(@PathVariable Long accountId,
                                               @Valid @RequestBody AccountUpdateRequest request) {
        // The path variable is the authoritative target account. Reject a body whose accountId
        // selects a different record so a request to PUT /api/accounts/{accountId} cannot update a
        // different account (data-integrity guard). Comparison is zero-padding tolerant and numeric
        // (path 111 == body "00000000111"), and mirrors the path/body consistency checks already
        // enforced by the card and user update controllers.
        if (!matchesPathAccount(accountId, request.accountId())) {
            throw new ValidationException(
                    "Account ID in path does not match request body", "accountId");
        }
        return accountUpdateService.updateAccount(request);
    }

    /**
     * Null-safe, zero-padding-tolerant numeric comparison of the path account id against the
     * request-body account id. The body id is constrained to {@code \d{1,11}} by bean validation
     * (which runs before this method), so a well-formed request always reaches a numeric compare;
     * any unexpected null/non-numeric value degrades to a {@code false} (mismatch &rarr; 400) rather
     * than surfacing a 500.
     *
     * @param pathAccountId the account id from the URL path (authoritative target)
     * @param bodyAccountId the account id echoed in the request body
     * @return {@code true} only when both are present and numerically equal
     */
    private static boolean matchesPathAccount(Long pathAccountId, String bodyAccountId) {
        if (pathAccountId == null || bodyAccountId == null) {
            return false;
        }
        try {
            return pathAccountId.longValue() == Long.parseLong(bodyAccountId.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
