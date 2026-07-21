package com.carddemo.bff.aggregation;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.UserListResponse;

/**
 * [DEFERRED] User List (admin) aggregator for the CardDemo backend-for-frontend.
 *
 * <p>This is a <strong>typed placeholder stub</strong>. It returns an empty,
 * well-formed {@link UserListResponse} page and performs <em>no</em> downstream
 * service call, <em>no</em> persistence, and <em>no</em> business logic. In the
 * walking skeleton only the Card Detail path is the live vertical tracer; every
 * other aggregation surface &mdash; including this one &mdash; is a typed
 * [DEFERRED] stub. A typed placeholder is the CORRECT outcome here; a fabricated
 * implementation (invented user rows, a real repository or HTTP call) would be a
 * failure.</p>
 *
 * <p><strong>Contract is the single source of truth (SSoT).</strong> The shape
 * returned here is the {@code UserListResponse} model generated at build time
 * from the frozen OpenAPI 3.1 contract {@code /contracts/bff.openapi.yaml}
 * (generator {@code spring}, {@code interfaceOnly=true}) into the
 * {@code com.carddemo.bff.model} package. That generated type is NEVER
 * hand-edited; to change the shape, edit the frozen contract and regenerate.</p>
 *
 * <p><strong>Aggregation only.</strong> The sibling {@code web} controller that
 * implements the generated {@code UsersApi} ({@code GET /api/users}) delegates to
 * {@link #listUsers(Integer, Integer)} and wraps the result in
 * {@code ResponseEntity.ok(...)}. This class holds no collaborators and requires
 * no dependency injection.</p>
 *
 * <p>Provenance: [SRC: COUSR00C | USRSEC] &mdash; app/csd/CARDDEMO.CSD (legacy
 * User List transaction {@code COUSR00C}, "List all users from USRSEC file",
 * reading the {@code USRSEC} KSDS whose layout is app/cpy/CSUSR01Y.cpy). The
 * legacy {@code SEC-USR-PWD} password field is intentionally NOT surfaced by the
 * contract or by this aggregator.</p>
 */
@Service
public class UsersAggregator {

    /**
     * Zero-based page index echoed when the caller supplies no {@code page}.
     * Mirrors the {@code page} query-parameter default declared in the frozen
     * BFF contract for {@code GET /api/users}.
     */
    private static final int DEFAULT_PAGE = 0;

    /**
     * Page size echoed when the caller supplies no {@code size}. Mirrors the
     * {@code size} query-parameter default declared in the frozen BFF contract
     * for {@code GET /api/users}.
     */
    private static final int DEFAULT_SIZE = 20;

    /**
     * Return an empty, well-formed page of administrative users.
     *
     * <p>[DEFERRED] typed stub: the response echoes the requested paging inputs
     * (falling back to the contract defaults when {@code null}) and reports an
     * empty result set. No user data is read, aggregated, or fabricated, and no
     * downstream service or datastore is contacted.</p>
     *
     * @param page zero-based page index from the contract query parameter; when
     *             {@code null} the contract default ({@value #DEFAULT_PAGE}) is echoed
     * @param size page size from the contract query parameter; when {@code null}
     *             the contract default ({@value #DEFAULT_SIZE}) is echoed
     * @return an empty {@link UserListResponse}: an empty {@code items} list, the
     *         echoed or defaulted {@code page} and {@code size}, and
     *         {@code totalItems}/{@code totalPages} of zero
     */
    public UserListResponse listUsers(Integer page, Integer size) {
        UserListResponse response = new UserListResponse();
        response.setItems(List.of());
        response.setPage(page != null ? page : DEFAULT_PAGE);
        response.setSize(size != null ? size : DEFAULT_SIZE);
        response.setTotalItems(0L);
        response.setTotalPages(0);
        return response;
    }
}
