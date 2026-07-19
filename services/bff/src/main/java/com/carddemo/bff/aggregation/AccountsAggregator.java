package com.carddemo.bff.aggregation;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.AccountView;

/**
 * [DEFERRED] Account View aggregator (typed stub).
 *
 * <p>Backend-for-frontend aggregation seam for the Account View screen. In this
 * walking-skeleton run the account read path is deliberately <em>not</em> wired:
 * only the Card Detail slice is the live tracer. This aggregator therefore
 * performs <strong>no</strong> downstream service call, <strong>no</strong>
 * persistence access, and <strong>no</strong> business logic; it returns a
 * well-formed, typed placeholder {@link AccountView} so the frozen BFF contract
 * ({@code GET /api/accounts/{accountId}}) remains satisfiable end-to-end.</p>
 *
 * <p>Aggregation only: this class has no collaborators and injects nothing. A
 * typed placeholder is the correct outcome for this skeleton; a fabricated real
 * implementation would be a defect.</p>
 *
 * <p>Provenance: [SRC: COACTVWC | ACCTDAT] &mdash; the legacy CICS Account View
 * program {@code COACTVWC} reading the {@code ACCTDAT} VSAM dataset
 * (record layout {@code app/cpy/CVACT01Y.cpy}: {@code ACCT-ID},
 * {@code ACCT-ACTIVE-STATUS}, {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}).</p>
 */
@Service
public class AccountsAggregator {

    /**
     * Returns a typed placeholder {@link AccountView} for the requested account.
     *
     * <p><strong>[DEFERRED] stub:</strong> this method intentionally makes no
     * downstream call and applies no business logic. The supplied
     * {@code accountId} is echoed back into the response so the placeholder is
     * coherent for the specific request; the monetary fields are emitted as
     * zeroed decimal strings ({@code "0.00"}), matching the BFF contract's
     * decimal-string money representation, and the active-status flag defaults
     * to inactive ({@link AccountView.ActiveStatusEnum#N}).</p>
     *
     * <p>The sibling {@code web} controller (which implements the generated
     * {@code AccountsApi} for {@code GET /api/accounts/{accountId}}) delegates to
     * this method and wraps the result in {@code ResponseEntity.ok(...)}.</p>
     *
     * @param accountId the account identifier from the request path, echoed into
     *                  the placeholder response; may be {@code null}
     * @return a non-{@code null}, fully-typed placeholder {@link AccountView}
     */
    public AccountView getAccount(String accountId) {
        AccountView view = new AccountView();
        view.setAccountId(accountId);
        view.setActiveStatus(AccountView.ActiveStatusEnum.N);
        view.setCurrentBalance("0.00");
        view.setCreditLimit("0.00");
        return view;
    }
}
