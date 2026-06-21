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
        // The {accountId} in the URL is the sole target identifier. The request body also
        // carries an accountId (BMS ACCTSID parity); it must agree with the path so a caller
        // cannot address one account in the URL and mutate another via the body. @Valid has
        // already enforced the 1-11 digit numeric format, so the body value parses cleanly for
        // the numeric (zero-padding-tolerant) comparison.
        Long bodyAccountId = Long.valueOf(request.accountId());
        if (!accountId.equals(bodyAccountId)) {
            throw new ValidationException(
                    "Path account id " + accountId + " does not match request body account id "
                            + request.accountId(), "accountId");
        }
        return accountUpdateService.updateAccount(request);
    }
}
