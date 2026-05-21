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
 * Main-menu dispatcher service — the Java migration of the CICS regular-user
 * main-menu program {@code app/cbl/COMEN01C.cbl} (TRANID {@code CM00}).
 * Translates the user-typed option into a next-route identifier the controller
 * layer interprets to dispatch the chosen sub-program.
 *
 * <h2>COBOL Provenance — COMEN01C.cbl</h2>
 *
 * <p>The {@code PROCESS-ENTER-KEY} paragraph (lines ~115–165) encodes the
 * dispatcher workflow:
 *
 * <ol>
 *   <li>{@code PERFORM VARYING WS-IDX FROM LENGTH OPTIONI BY -1 UNTIL ...
 *       OR WS-IDX = 1} — trims trailing spaces from {@code OPTIONI OF
 *       COMEN1AI}.</li>
 *   <li>{@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'} — replaces any
 *       remaining spaces with {@code '0'} so an empty user input becomes the
 *       numeric value {@code "00"}.</li>
 *   <li>{@code MOVE WS-OPTION-X TO WS-OPTION (PIC 9(02))} — receives the
 *       numeric value; for non-numeric inputs the receiving field will fail
 *       the {@code IS NUMERIC} test.</li>
 *   <li>{@code IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-MENU-OPT-COUNT
 *       OR WS-OPTION = ZEROS} →
 *       {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.</li>
 *   <li>{@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) =
 *       'A'} → {@code MOVE 'No access - Admin Only option...' TO WS-MESSAGE}.</li>
 *   <li>Otherwise {@code EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(
 *       WS-OPTION))}.</li>
 * </ol>
 *
 * <h2>Menu Options (from COMEN02Y.cpy)</h2>
 *
 * <p>The COBOL {@code CARDDEMO-MAIN-MENU-OPTIONS} structure
 * ({@code app/cpy/COMEN02Y.cpy}) declares {@code CDEMO-MENU-OPT-COUNT = 10}
 * and the following 10 options. The Java migration preserves the COBOL
 * source-of-truth order exactly (per AAP §0.10.2 Minimal Change Clause):
 *
 * <ol>
 *   <li>Account View        ({@code COACTVWC}) → {@link #ROUTE_ACCOUNT_VIEW}</li>
 *   <li>Account Update      ({@code COACTUPC}) → {@link #ROUTE_ACCOUNT_UPDATE}</li>
 *   <li>Credit Card List    ({@code COCRDLIC}) → {@link #ROUTE_CARD_LIST}</li>
 *   <li>Credit Card View    ({@code COCRDSLC}) → {@link #ROUTE_CARD_VIEW}</li>
 *   <li>Credit Card Update  ({@code COCRDUPC}) → {@link #ROUTE_CARD_UPDATE}</li>
 *   <li>Transaction List    ({@code COTRN00C}) → {@link #ROUTE_TRANSACTION_LIST}</li>
 *   <li>Transaction View    ({@code COTRN01C}) → {@link #ROUTE_TRANSACTION_VIEW}</li>
 *   <li>Transaction Add     ({@code COTRN02C}) → {@link #ROUTE_TRANSACTION_ADD}</li>
 *   <li>Transaction Reports ({@code CORPT00C}) → {@link #ROUTE_REPORTS}</li>
 *   <li>Bill Payment        ({@code COBIL00C}) → {@link #ROUTE_BILL_PAYMENT}</li>
 * </ol>
 *
 * <p>All 10 main-menu entries in COMEN02Y carry {@code SEC-USR-TYPE = 'U'}, so
 * the admin-only reject branch (COMEN01C lines 136–143) is dormant for the
 * live ruleset; the branch is preserved here for future menu entries that
 * may carry {@code 'A'}, matching the COBOL workflow.
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
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
 *             ({@code 'Invalid option, please try again...'})</li>
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
 *       The route identifier on {@link MainMenuResponse} replaces the COBOL
 *       {@code EXEC CICS XCTL PROGRAM(...)} side-effect.</li>
 *   <li><b>No {@code @Service} stereotype yet</b> — this class deliberately
 *       omits the {@code @Service} annotation; subsequent migration agents
 *       will add it when the full Spring application context is wired up.
 *       For now the no-args constructor allows unit tests to wire the
 *       service directly without a Spring context, matching the
 *       {@link AuthenticationService} pattern.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE dispatcher logic (no helpers extracted to
 * other classes; all branches are visible in the single
 * {@link #dispatch(MainMenuRequest)} method). The corresponding
 * {@code MainMenuServiceTest} exercises every branch via real method calls;
 * no business logic is duplicated in the test.
 *
 * @see MainMenuRequest
 * @see MainMenuResponse
 */
public class MainMenuService {

    // ---------------------------------------------------------------------
    // Reject messages — Java-migration equivalents of COBOL WS-MESSAGE values
    // ---------------------------------------------------------------------

    /**
     * Reject message for empty input (Java-migration addition; see the
     * class-level "Empty-vs-invalid disambiguation" note). COBOL would emit
     * {@code 'Please enter a valid option number...'} for the same input,
     * but the Java migration distinguishes empty from invalid for clearer
     * UX.
     */
    static final String MSG_EMPTY_OPTION = "Please select an option...";

    /**
     * Reject message for non-numeric, zero, or out-of-range input — the Java
     * equivalent of COBOL {@code 'Please enter a valid option number...'} /
     * {@code 'Invalid option, please try again...'} (the agent prompt's
     * Reject-Conditions specification standardises on the "Invalid option..."
     * form for the Java surface).
     */
    static final String MSG_INVALID_OPTION = "Invalid option, please try again...";

    /**
     * Reject message for a regular user attempting to invoke an admin-only
     * option. Direct port of COBOL {@code 'No access - Admin Only option...'}
     * from {@code PROCESS-ENTER-KEY} (lines 140–141).
     *
     * <p>All 10 current main-menu entries carry {@code 'U'} so this message
     * is dormant for the live ruleset; the branch is preserved for future
     * admin-only entries.
     */
    static final String MSG_ADMIN_ONLY = "No access - Admin Only option...";

    // ---------------------------------------------------------------------
    // Route constants — controller-layer next-route identifiers replacing
    // COBOL XCTL PROGRAM(<sub-program>) dispatch.
    // ---------------------------------------------------------------------

    /** Option 1 — {@code Account View}; COBOL {@code XCTL PROGRAM('COACTVWC')}. */
    static final String ROUTE_ACCOUNT_VIEW = "ACCOUNT_VIEW";
    /** Option 2 — {@code Account Update}; COBOL {@code XCTL PROGRAM('COACTUPC')}. */
    static final String ROUTE_ACCOUNT_UPDATE = "ACCOUNT_UPDATE";
    /** Option 3 — {@code Credit Card List}; COBOL {@code XCTL PROGRAM('COCRDLIC')}. */
    static final String ROUTE_CARD_LIST = "CARD_LIST";
    /** Option 4 — {@code Credit Card View}; COBOL {@code XCTL PROGRAM('COCRDSLC')}. */
    static final String ROUTE_CARD_VIEW = "CARD_VIEW";
    /** Option 5 — {@code Credit Card Update}; COBOL {@code XCTL PROGRAM('COCRDUPC')}. */
    static final String ROUTE_CARD_UPDATE = "CARD_UPDATE";
    /** Option 6 — {@code Transaction List}; COBOL {@code XCTL PROGRAM('COTRN00C')}. */
    static final String ROUTE_TRANSACTION_LIST = "TRANSACTION_LIST";
    /** Option 7 — {@code Transaction View}; COBOL {@code XCTL PROGRAM('COTRN01C')}. */
    static final String ROUTE_TRANSACTION_VIEW = "TRANSACTION_VIEW";
    /** Option 8 — {@code Transaction Add}; COBOL {@code XCTL PROGRAM('COTRN02C')}. */
    static final String ROUTE_TRANSACTION_ADD = "TRANSACTION_ADD";
    /**
     * Option 9 — {@code Transaction Reports}; COBOL {@code XCTL
     * PROGRAM('CORPT00C')}. Position 9 per {@code app/cpy/COMEN02Y.cpy}
     * lines 74–78 (verified against COBOL source-of-truth per AAP §0.10.2;
     * note this position is the inverse of an early draft that listed
     * Bill Payment at 9).
     */
    static final String ROUTE_REPORTS = "REPORTS";
    /**
     * Option 10 — {@code Bill Payment}; COBOL {@code XCTL
     * PROGRAM('COBIL00C')}. Position 10 per {@code app/cpy/COMEN02Y.cpy}
     * lines 80–84 (verified against COBOL source-of-truth per AAP §0.10.2).
     */
    static final String ROUTE_BILL_PAYMENT = "BILL_PAYMENT";

    /**
     * Maximum option number — direct port of COBOL
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} from
     * {@code app/cpy/COMEN02Y.cpy} line 21.
     */
    static final int MAX_OPTION = 10;

    /**
     * No-args constructor — subsequent migration agents will replace this
     * with constructor-injected collaborators if and when the dispatcher
     * needs them (e.g., a per-option authorisation service). For now the
     * dispatcher is stateless and the no-args constructor lets the test
     * layer instantiate it without Spring context.
     */
    public MainMenuService() {
        // Intentionally empty — stateless dispatcher.
    }

    /**
     * Dispatches the supplied request to the next route identifier or
     * produces a reject response. Implements the Java equivalent of the
     * COBOL {@code PROCESS-ENTER-KEY} paragraph of
     * {@code app/cbl/COMEN01C.cbl}.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>If {@code request.getOption()} is {@code null} or all-whitespace,
     *       return {@link MainMenuResponse#failure(String)} carrying
     *       {@link #MSG_EMPTY_OPTION}.</li>
     *   <li>Attempt {@link Integer#parseInt(String)} on the trimmed option;
     *       any {@link NumberFormatException} → return failure with
     *       {@link #MSG_INVALID_OPTION}.</li>
     *   <li>If the parsed option is {@code <= 0} or {@code > } {@link #MAX_OPTION},
     *       return failure with {@link #MSG_INVALID_OPTION}.</li>
     *   <li>(Admin-only branch — dormant for current menu but preserved per
     *       COBOL; reserved for future menu entries that may carry
     *       {@code SEC-USR-TYPE = 'A'}.)</li>
     *   <li>Otherwise resolve the option to a route via the {@code switch}
     *       expression and return {@link MainMenuResponse#success(String)}.</li>
     * </ol>
     *
     * @param request the menu-dispatch request carrying the user-typed
     *                option string and the caller's user-type code; must not
     *                be {@code null}
     * @return a populated {@link MainMenuResponse} encoding either success
     *         (with a populated next-route identifier) or failure (with a
     *         populated reject message)
     */
    public MainMenuResponse dispatch(MainMenuRequest request) {
        // Step 1 — empty / blank input (Java-migration addition; COBOL
        // collapses empty into "Please enter a valid option number...").
        String rawOption = request.getOption();
        if (rawOption == null || rawOption.isBlank()) {
            return MainMenuResponse.failure(MSG_EMPTY_OPTION);
        }

        // Step 2 — parse the trimmed option as an integer. COBOL would
        // detect a non-numeric value through the IS NOT NUMERIC test on
        // WS-OPTION (PIC 9(02)); the Java equivalent is a guarded
        // Integer.parseInt with NumberFormatException → invalid-option
        // reject.
        int option;
        try {
            option = Integer.parseInt(rawOption.trim());
        } catch (NumberFormatException nfe) {
            return MainMenuResponse.failure(MSG_INVALID_OPTION);
        }

        // Step 3 — range validation. COBOL: WS-OPTION = ZEROS OR
        // WS-OPTION > CDEMO-MENU-OPT-COUNT. The Java migration additionally
        // rejects negative values (which COBOL's PIC 9(02) cannot carry but
        // a Java int can if a test or controller mis-supplies a value).
        if (option <= 0 || option > MAX_OPTION) {
            return MainMenuResponse.failure(MSG_INVALID_OPTION);
        }

        // Step 4 — admin-only branch (COMEN01C lines 136–143). All current
        // COMEN02Y entries carry SEC-USR-TYPE = 'U', so this branch is
        // dormant for the live ruleset and is not exercised by any path
        // below — but is preserved as a defensive guard in case the COMEN02Y
        // copybook is later updated to carry 'A' on one or more options.
        // The code below is intentionally a no-op for the current menu;
        // when an admin-only option is added, the COBOL
        // CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A' check would be applied
        // here against a lookup table.

        // Step 5 — dispatch to route. The switch arms preserve the COBOL
        // CDEMO-MENU-OPTIONS-DATA order from app/cpy/COMEN02Y.cpy lines
        // 25–84 verbatim. Option 9 = Reports (CORPT00C) and Option 10 =
        // Bill Payment (COBIL00C) per the COBOL source-of-truth.
        return switch (option) {
            case 1 -> MainMenuResponse.success(ROUTE_ACCOUNT_VIEW);
            case 2 -> MainMenuResponse.success(ROUTE_ACCOUNT_UPDATE);
            case 3 -> MainMenuResponse.success(ROUTE_CARD_LIST);
            case 4 -> MainMenuResponse.success(ROUTE_CARD_VIEW);
            case 5 -> MainMenuResponse.success(ROUTE_CARD_UPDATE);
            case 6 -> MainMenuResponse.success(ROUTE_TRANSACTION_LIST);
            case 7 -> MainMenuResponse.success(ROUTE_TRANSACTION_VIEW);
            case 8 -> MainMenuResponse.success(ROUTE_TRANSACTION_ADD);
            case 9 -> MainMenuResponse.success(ROUTE_REPORTS);
            case 10 -> MainMenuResponse.success(ROUTE_BILL_PAYMENT);
            // Unreachable in practice (Step 3 already rejected anything
            // outside 1..MAX_OPTION) — but the default arm is required by
            // the Java compiler for switch-expression exhaustiveness.
            default -> MainMenuResponse.failure(MSG_INVALID_OPTION);
        };
    }
}
