/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service;

/**
 * Admin-menu dispatcher service — the Java migration of the CICS admin-only
 * menu program {@code app/cbl/COADM01C.cbl} (TRANID {@code CA00}).
 * Translates the admin-user-typed option into a next-route identifier the
 * controller layer interprets to dispatch the chosen admin sub-program.
 *
 * <h2>COBOL Provenance — COADM01C.cbl</h2>
 *
 * <p>The {@code PROCESS-ENTER-KEY} paragraph (lines ~115–155) encodes the
 * dispatcher workflow:
 *
 * <ol>
 *   <li>{@code PERFORM VARYING WS-IDX FROM LENGTH OPTIONI BY -1 UNTIL ...
 *       OR WS-IDX = 1} — trims trailing spaces from {@code OPTIONI OF
 *       COADM1AI}.</li>
 *   <li>{@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'} — replaces any
 *       remaining spaces with {@code '0'} so an empty user input becomes the
 *       numeric value {@code "00"}.</li>
 *   <li>{@code MOVE WS-OPTION-X TO WS-OPTION (PIC 9(02))} — receives the
 *       numeric value; for non-numeric inputs the receiving field will fail
 *       the {@code IS NUMERIC} test.</li>
 *   <li>{@code IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT
 *       OR WS-OPTION = ZEROS} →
 *       {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.</li>
 *   <li>Otherwise {@code EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(
 *       WS-OPTION))}.</li>
 * </ol>
 *
 * <h2>Admin Menu Options (from COADM02Y.cpy)</h2>
 *
 * <p>The COBOL {@code CARDDEMO-ADMIN-MENU-OPTIONS} structure
 * ({@code app/cpy/COADM02Y.cpy}) declares {@code CDEMO-ADMIN-OPT-COUNT = 4}
 * and the following 4 options. The Java migration preserves the COBOL
 * source-of-truth order exactly (per AAP §0.10.2 Minimal Change Clause):
 *
 * <ol>
 *   <li>User List (Security)   ({@code COUSR00C}) → {@link #ROUTE_USER_LIST}</li>
 *   <li>User Add (Security)    ({@code COUSR01C}) → {@link #ROUTE_USER_ADD}</li>
 *   <li>User Update (Security) ({@code COUSR02C}) → {@link #ROUTE_USER_UPDATE}</li>
 *   <li>User Delete (Security) ({@code COUSR03C}) → {@link #ROUTE_USER_DELETE}</li>
 * </ol>
 *
 * <h2>Authorization Check (Java-Migration Addition)</h2>
 *
 * <p>The COBOL source ({@code app/cbl/COADM01C.cbl}) does not contain an
 * explicit user-type check in the {@code PROCESS-ENTER-KEY} paragraph
 * because {@code COADM01C} is only reachable from {@code COSGN00C} after
 * the sign-on program verifies that the caller is {@code CDEMO-USRTYP-ADMIN}.
 * The Java migration tightens this implicit contract by re-verifying the
 * user-type inside the dispatcher itself, so the service is safe to invoke
 * from any caller (REST controller, integration test, batch initiator)
 * without relying on the upstream sign-on flow's enforcement.
 *
 * <p>This authorisation check fires BEFORE any option parsing — preserves
 * defence in depth and prevents leaking information about valid options to
 * unauthorised callers.
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>Authorization check added</b> — see "Authorization Check"
 *       section above (a Java-migration tightening of an implicit COBOL
 *       contract; documented divergence per AAP §0.10.2 Minimal Change Clause).
 *       Reject message {@link #MSG_NOT_AUTHORIZED} matches the prompt's
 *       canonical wording {@code "You are not authorized for Admin functions..."}.</li>
 *   <li><b>Empty-vs-invalid disambiguation</b> — COBOL collapses empty input
 *       and zero input into the single message
 *       {@code 'Please enter a valid option number...'} because the
 *       {@code INSPECT REPLACING ALL ' ' BY '0'} step turns an empty input
 *       into the literal string {@code "00"}, which then trips the
 *       {@code WS-OPTION = ZEROS} branch. The Java migration distinguishes
 *       these for clearer UX (per the testing plan in the agent prompt):
 *       <ul>
 *         <li>Empty input → {@link #MSG_EMPTY_OPTION}
 *             ({@code 'Please select an option...'})</li>
 *         <li>Invalid numeric / out-of-range / non-numeric →
 *             {@link #MSG_INVALID_OPTION}
 *             ({@code 'Please enter a valid option number...'})</li>
 *       </ul>
 *       This is a documented divergence (AAP §0.10.2: "All deviations from
 *       literal COBOL logic must be documented with the original COBOL
 *       paragraph name and reason for divergence"). Reason: the Java
 *       front-end (REST controller, no BMS map) has no input-padding step
 *       equivalent to {@code INSPECT REPLACING}, so the empty-input semantic
 *       is now distinguishable from the zero-input semantic and the user
 *       benefits from a more specific reject message.</li>
 *   <li><b>No CICS commarea state</b> — the COBOL workflow stitches multiple
 *       calls together via {@code CARDDEMO-COMMAREA}; the Java migration
 *       reduces the dispatcher to a stateless function {@code dispatch(req)}.
 *       The route identifier on {@link AdminMenuResponse} replaces the COBOL
 *       {@code EXEC CICS XCTL PROGRAM(...)} side-effect.</li>
 *   <li><b>No {@code @Service} stereotype yet</b> — this class deliberately
 *       omits the {@code @Service} annotation; subsequent migration agents
 *       will add it when the full Spring application context is wired up.
 *       For now the no-args constructor allows unit tests to wire the
 *       service directly without a Spring context, matching the
 *       {@link MainMenuService} pattern.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE dispatcher logic (no helpers extracted to
 * other classes; all branches are visible in the single
 * {@link #dispatch(AdminMenuRequest)} method). The corresponding
 * {@code AdminMenuServiceTest} exercises every branch via real method calls;
 * no business logic is duplicated in the test.
 *
 * @see AdminMenuRequest
 * @see AdminMenuResponse
 * @see MainMenuService
 */
public class AdminMenuService {

    // ---------------------------------------------------------------------
    // Reject messages — Java-migration equivalents of COBOL WS-MESSAGE values
    // ---------------------------------------------------------------------

    /**
     * Reject message for a non-admin caller — Java-migration addition (the
     * authorisation check is implicit in the COBOL workflow because
     * {@code COADM01C} is only reachable via {@code COSGN00C}'s admin
     * dispatch branch). The wording matches the agent prompt's canonical
     * Reject-Conditions specification.
     */
    static final String MSG_NOT_AUTHORIZED =
        "You are not authorized for Admin functions...";

    /**
     * Reject message for empty input (Java-migration addition; see the
     * class-level "Empty-vs-invalid disambiguation" note). COBOL would emit
     * {@code 'Please enter a valid option number...'} for the same input,
     * but the Java migration distinguishes empty from invalid for clearer
     * UX.
     */
    static final String MSG_EMPTY_OPTION = "Please select an option...";

    /**
     * Reject message for non-numeric, zero, or out-of-range input — direct
     * port of COBOL {@code 'Please enter a valid option number...'} from
     * the {@code PROCESS-ENTER-KEY} paragraph (lines 131–132 of
     * {@code app/cbl/COADM01C.cbl}).
     */
    static final String MSG_INVALID_OPTION =
        "Please enter a valid option number...";

    // ---------------------------------------------------------------------
    // User-type constants — match COCOM01Y.cpy 88-level CDEMO-USRTYP-ADMIN
    // ---------------------------------------------------------------------

    /**
     * COBOL {@code CDEMO-USRTYP-ADMIN} 88-level value from
     * {@code app/cpy/COCOM01Y.cpy} line 27 ({@code VALUE 'A'}). Only callers
     * carrying this exact user-type code are authorised to invoke any of
     * the 4 admin menu options.
     */
    static final String USER_TYPE_ADMIN = "A";

    // ---------------------------------------------------------------------
    // Route constants — controller-layer next-route identifiers replacing
    // COBOL XCTL PROGRAM(<sub-program>) dispatch.
    // ---------------------------------------------------------------------

    /** Option 1 — {@code User List (Security)}; COBOL {@code XCTL PROGRAM('COUSR00C')}. */
    static final String ROUTE_USER_LIST = "USER_LIST";
    /** Option 2 — {@code User Add (Security)}; COBOL {@code XCTL PROGRAM('COUSR01C')}. */
    static final String ROUTE_USER_ADD = "USER_ADD";
    /** Option 3 — {@code User Update (Security)}; COBOL {@code XCTL PROGRAM('COUSR02C')}. */
    static final String ROUTE_USER_UPDATE = "USER_UPDATE";
    /** Option 4 — {@code User Delete (Security)}; COBOL {@code XCTL PROGRAM('COUSR03C')}. */
    static final String ROUTE_USER_DELETE = "USER_DELETE";

    /**
     * Maximum option number — direct port of COBOL
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} from
     * {@code app/cpy/COADM02Y.cpy} line 20.
     */
    static final int MAX_OPTION = 4;

    /**
     * No-args constructor — subsequent migration agents will replace this
     * with constructor-injected collaborators if and when the dispatcher
     * needs them (e.g., a per-option authorisation service). For now the
     * dispatcher is stateless and the no-args constructor lets the test
     * layer instantiate it without Spring context.
     */
    public AdminMenuService() {
        // Intentionally empty — stateless dispatcher.
    }

    /**
     * Dispatches the supplied request to the next route identifier or
     * produces a reject response. Implements the Java equivalent of the
     * COBOL {@code PROCESS-ENTER-KEY} paragraph of
     * {@code app/cbl/COADM01C.cbl} (with the added Java-migration
     * authorisation check; see class-level "Authorization Check" section).
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>If {@code request.getCallerUserType()} is not {@link #USER_TYPE_ADMIN}
     *       ({@code "A"}), return {@link AdminMenuResponse#failure(String)}
     *       carrying {@link #MSG_NOT_AUTHORIZED}.</li>
     *   <li>If {@code request.getOption()} is {@code null} or all-whitespace,
     *       return failure carrying {@link #MSG_EMPTY_OPTION}.</li>
     *   <li>Attempt {@link Integer#parseInt(String)} on the trimmed option;
     *       any {@link NumberFormatException} → return failure with
     *       {@link #MSG_INVALID_OPTION}.</li>
     *   <li>If the parsed option is {@code <= 0} or {@code > } {@link #MAX_OPTION},
     *       return failure with {@link #MSG_INVALID_OPTION}.</li>
     *   <li>Otherwise resolve the option to a route via the {@code switch}
     *       expression and return {@link AdminMenuResponse#success(String)}.</li>
     * </ol>
     *
     * @param request the menu-dispatch request carrying the user-typed
     *                option string and the caller's user-type code; must not
     *                be {@code null}
     * @return a populated {@link AdminMenuResponse} encoding either success
     *         (with a populated next-route identifier) or failure (with a
     *         populated reject message)
     */
    public AdminMenuResponse dispatch(AdminMenuRequest request) {
        // Step 1 — authorisation (Java-migration addition). COBOL: implicit
        // (COADM01C is only reachable from COSGN00C's admin branch). The
        // explicit check here matches the agent prompt's reject specification
        // and aligns with the Spring Security authorisation model the
        // controller layer will eventually use.
        String callerType = request.getCallerUserType();
        if (!USER_TYPE_ADMIN.equals(callerType)) {
            return AdminMenuResponse.failure(MSG_NOT_AUTHORIZED);
        }

        // Step 2 — empty / blank input (Java-migration addition; COBOL
        // collapses empty into "Please enter a valid option number...").
        String rawOption = request.getOption();
        if (rawOption == null || rawOption.isBlank()) {
            return AdminMenuResponse.failure(MSG_EMPTY_OPTION);
        }

        // Step 3 — parse the trimmed option as an integer. COBOL would
        // detect a non-numeric value through the IS NOT NUMERIC test on
        // WS-OPTION (PIC 9(02)); the Java equivalent is a guarded
        // Integer.parseInt with NumberFormatException → invalid-option
        // reject.
        int option;
        try {
            option = Integer.parseInt(rawOption.trim());
        } catch (NumberFormatException nfe) {
            return AdminMenuResponse.failure(MSG_INVALID_OPTION);
        }

        // Step 4 — range validation. COBOL: WS-OPTION = ZEROS OR
        // WS-OPTION > CDEMO-ADMIN-OPT-COUNT. The Java migration additionally
        // rejects negative values (which COBOL's PIC 9(02) cannot carry but
        // a Java int can if a test or controller mis-supplies a value).
        if (option <= 0 || option > MAX_OPTION) {
            return AdminMenuResponse.failure(MSG_INVALID_OPTION);
        }

        // Step 5 — dispatch to route. The switch arms preserve the COBOL
        // CDEMO-ADMIN-OPTIONS-DATA order from app/cpy/COADM02Y.cpy lines
        // 24–42 verbatim:
        //   1 → COUSR00C (User List)
        //   2 → COUSR01C (User Add)
        //   3 → COUSR02C (User Update)
        //   4 → COUSR03C (User Delete)
        return switch (option) {
            case 1 -> AdminMenuResponse.success(ROUTE_USER_LIST);
            case 2 -> AdminMenuResponse.success(ROUTE_USER_ADD);
            case 3 -> AdminMenuResponse.success(ROUTE_USER_UPDATE);
            case 4 -> AdminMenuResponse.success(ROUTE_USER_DELETE);
            // Unreachable in practice (Step 4 already rejected anything
            // outside 1..MAX_OPTION) — but the default arm is required by
            // the Java compiler for switch-expression exhaustiveness.
            default -> AdminMenuResponse.failure(MSG_INVALID_OPTION);
        };
    }
}
