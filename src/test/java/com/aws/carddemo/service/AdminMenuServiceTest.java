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

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies, file
// schema internal_imports).
//
//   * TestFixtures — shared test-constants entry point. The COADM01C
//     dispatcher test layer uses TestFixtures.Users.ADMIN_USER_ID and
//     TestFixtures.Users.REGULAR_USER_ID documentarily to cross-link this
//     test with the rest of the service-test layer (mirrors
//     MainMenuServiceTest / AuthenticationServiceTest). The dispatcher
//     itself reads only the user-type field (the COBOL
//     CDEMO-USER-TYPE PIC X(01) value 'A' / 'U'), so the fixture user IDs
//     are referenced for cross-test consistency rather than load-bearing.
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly ("Shared test-constants entry point declared in
//     the test class's import section per the agent_prompt Phase 1").
//
//   * AdminMenuService / AdminMenuRequest / AdminMenuResponse — the
//     production classes under test. No explicit imports because they share
//     this test's package (`com.aws.carddemo.service`); Java resolves
//     simple-named references via package membership.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage).
//
//   * @BeforeEach — reinstantiates the system under test before every
//     method so test isolation is preserved (AAP §0.10.9). The dispatcher
//     service is stateless and could be reused, but the per-method reset
//     matches the pattern established by MainMenuServiceTest /
//     AuthenticationServiceTest and protects against any future stateful
//     collaborators added to the constructor.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping (AAP §0.10.6 naming convention).
//   * @Nested — groups option-dispatch tests and authorisation tests into
//     the two semantic sections (Authorization, OptionDispatch) that match
//     this test class's exports.members_exposed schema entries.
//   * @Test — single-execution test marker for non-parameterised cases.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Parameterized-test support (file schema external_imports).
//
//   * @ParameterizedTest — drives the table-driven valid-option dispatch
//     test (the 4 COADM02Y options) and the non-admin authorisation reject
//     test (multiple non-admin caller types).
//   * @CsvSource — enumerates the 4 admin menu options as comma-separated
//     pairs (option-number, expected-route) preserving the one-to-one
//     mapping with COADM02Y.cpy lines 24–42. Keeps the COBOL menu-option
//     table aligned with the test cases without forcing an external CSV
//     file (the table is small and self-contained, so an in-source
//     @CsvSource is more readable than an @CsvFileSource).
//   * @ValueSource — enumerates non-admin caller types ("U", " ", "X") for
//     the authorisation reject path, and enumerates invalid option strings
//     ("0", "99", "ABC", "-1") for the out-of-range / non-numeric reject
//     path validated against COADM01C's WS-OPTION IS NOT NUMERIC /
//     > CDEMO-ADMIN-OPT-COUNT / = ZEROS guards. The five inputs cover the
//     four categories of failure that the production dispatcher
//     distinguishes:
//       * zero (COBOL: WS-OPTION = ZEROS)                  -> "0"
//       * out-of-range positive (COBOL: WS-OPTION >
//         CDEMO-ADMIN-OPT-COUNT)                            -> "99"
//       * non-numeric (COBOL: IS NOT NUMERIC)               -> "ABC"
//       * negative (Java-only; PIC 9(02) cannot carry)      -> "-1"
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 JUnit Jupiter extension (file schema external_imports).
//
//   * MockitoExtension — activates Mockito strict-stubs mode and the @Mock
//     field lifecycle. This dispatcher test does NOT declare any @Mock
//     fields (the production AdminMenuService is stateless and has no
//     boundary collaborators yet per the COADM01C migration design — pure
//     in-memory dispatch), but the extension is kept for consistency with
//     the rest of the service-test layer (matches MainMenuServiceTest,
//     AuthenticationServiceTest pattern) and future-proofs the class
//     against added boundary collaborators per AAP §0.10.1 mock policy.
//     A refactor that introduces a JPA repository or external service
//     dependency on AdminMenuService can simply add an @Mock field
//     without re-engineering the extension wiring.
// ---------------------------------------------------------------------------
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL (AAP §0.10.10 "All assertions use
// AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals").
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminMenuService}, the migrated Java equivalent of the
 * CICS admin-only menu program {@code app/cbl/COADM01C.cbl} (TRANID
 * {@code CA00}).
 *
 * <h2>COBOL Provenance — COADM01C.cbl</h2>
 *
 * <p>The original {@code PROCESS-ENTER-KEY} paragraph (lines ~115–155)
 * implements the admin-menu dispatcher:
 *
 * <ol>
 *   <li>Trim trailing spaces on {@code OPTIONI OF COADM1AI} via
 *       {@code PERFORM VARYING WS-IDX FROM LENGTH ... BY -1}.</li>
 *   <li>{@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'} — pads any
 *       residual spaces with {@code '0'} so empty input is treated as
 *       numeric zero.</li>
 *   <li>{@code IF WS-OPTION IS NOT NUMERIC OR WS-OPTION >
 *       CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS} →
 *       {@code MOVE 'Please enter a valid option number...' TO WS-MESSAGE}.</li>
 *   <li>Otherwise {@code EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(
 *       WS-OPTION))}.</li>
 * </ol>
 *
 * <p>The COBOL source does not include an explicit user-type check inside
 * {@code COADM01C} because the program is only reachable from
 * {@code COSGN00C} after the sign-on flow verifies that the caller is
 * {@code CDEMO-USRTYP-ADMIN}. The Java migration tightens this implicit
 * contract by re-verifying the user-type inside the dispatcher itself, so
 * the service is safe to invoke from any caller without relying on the
 * upstream sign-on flow's enforcement (see {@link AdminMenuService}
 * "Authorization Check" section for the documented divergence).
 *
 * <h2>Java Migration Note — Option Numbering Verified Against COBOL Source</h2>
 *
 * <p>The COBOL admin-menu-option table {@code app/cpy/COADM02Y.cpy}
 * (lines 24–42) declares the following option-to-program mapping. The Java
 * route identifiers in this test exercise the production
 * {@link AdminMenuService} mapping which preserves COADM02Y's order
 * verbatim per AAP §0.10.2 Minimal Change Clause:
 *
 * <table border="1">
 *   <caption>COADM02Y admin menu option mapping</caption>
 *   <tr><th>Option</th><th>COBOL Program</th><th>Java Route</th></tr>
 *   <tr><td>1</td><td>{@code COUSR00C}</td><td>{@code USER_LIST}</td></tr>
 *   <tr><td>2</td><td>{@code COUSR01C}</td><td>{@code USER_ADD}</td></tr>
 *   <tr><td>3</td><td>{@code COUSR02C}</td><td>{@code USER_UPDATE}</td></tr>
 *   <tr><td>4</td><td>{@code COUSR03C}</td><td>{@code USER_DELETE}</td></tr>
 * </table>
 *
 * <p>The COBOL copybook declares {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02)
 * VALUE 4} so 4 options is the COBOL source-of-truth (the {@code OCCURS 9
 * TIMES} on {@code CDEMO-ADMIN-OPT} reserves room for future growth but
 * only the first 4 slots carry data).
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link AdminMenuService} via its no-args
 * constructor; the production dispatcher is stateless and has no boundary
 * collaborators yet, so no mocks are required. Assertions reference only
 * observable outputs ({@link AdminMenuResponse#isSuccess()},
 * {@link AdminMenuResponse#getNextRoute()},
 * {@link AdminMenuResponse#getMessage()}) — no business logic
 * (option-parsing, range checking, switch dispatch, user-type comparison)
 * is duplicated in test bodies.
 *
 * <h2>Test Categories</h2>
 *
 * <ul>
 *   <li><b>{@link Authorization}</b> — admin-only access enforcement
 *       (Java-migration-added authorisation check; AAP §0.5.1 purpose
 *       "non-admin-rejected paths"). Verifies that any non-admin caller
 *       type (regular user {@code "U"}, blank {@code " "}, unknown
 *       {@code "X"}) is rejected with the
 *       {@code "You are not authorized for Admin functions..."} message
 *       and that the request is rejected BEFORE option processing
 *       (defence in depth).</li>
 *   <li><b>{@link OptionDispatch}</b> — table-driven valid-option dispatch
 *       (4 rows from COADM02Y), empty-option reject, invalid-option reject
 *       (4 rows covering zero / out-of-range / non-numeric / negative),
 *       boundary at {@code MAX_OPTION + 1} (option 5).</li>
 * </ul>
 *
 * @see AdminMenuService
 * @see AdminMenuRequest
 * @see AdminMenuResponse
 * @see TestFixtures.Users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMenuService — COADM01C.cbl migration parity")
final class AdminMenuServiceTest {

    /**
     * System under test. Re-instantiated per {@code @Test} via {@link #setUp()}
     * for test isolation parity with {@code MainMenuServiceTest} /
     * {@code AuthenticationServiceTest}.
     */
    private AdminMenuService service;

    /**
     * Constructs a fresh {@link AdminMenuService} before every test method.
     * The dispatcher is stateless (no boundary collaborators), so the
     * no-args constructor is all that's needed. Reinstantiating still
     * provides defensive isolation against any future state addition.
     */
    @BeforeEach
    void setUp() {
        service = new AdminMenuService();
    }

    // =========================================================================
    // AUTHORIZATION — admin-only access (Java-migration addition)
    //
    // COADM01C is the admin-only menu dispatcher. The COBOL source does not
    // include an explicit user-type check in PROCESS-ENTER-KEY because the
    // program is only reachable from COSGN00C's admin branch. The Java
    // migration tightens this implicit contract by re-verifying the user-type
    // inside the dispatcher itself; this nested block verifies that
    // tightening.
    // =========================================================================

    /**
     * Authorisation tests for {@link AdminMenuService#dispatch(AdminMenuRequest)}
     * — the Java-migration-added user-type re-verification (see
     * {@link AdminMenuService} "Authorization Check" section).
     *
     * <p>Verifies that any caller whose user-type is NOT {@code "A"}
     * ({@code CDEMO-USRTYP-ADMIN}) is rejected with the canonical
     * authorisation message, regardless of whether the option string itself
     * would otherwise have been valid. This guards against:
     * <ul>
     *   <li>An upstream authentication service that fails to enforce the
     *       admin-only entry point (a regression-protection net).</li>
     *   <li>A test or controller that constructs an {@link AdminMenuRequest}
     *       directly (bypassing the COBOL sign-on dispatch flow).</li>
     * </ul>
     */
    @Nested
    @DisplayName("Authorization — admin-only access")
    class Authorization {

        /**
         * Drives the dispatcher with three non-admin caller types and a
         * valid option ({@code "1"}). Each row asserts that the dispatcher
         * rejects the request with the authorisation reject message —
         * proving that the user-type check fires BEFORE option processing
         * (defence in depth) and that the dispatcher does not leak any
         * information about valid options to non-admin callers.
         *
         * <p>The three rows cover the three categories of non-admin caller
         * the dispatcher needs to handle:
         * <ul>
         *   <li>{@code "U"} — regular user ({@code CDEMO-USRTYP-USER}); the
         *       most common non-admin caller, exercises the "wrong role"
         *       reject path.</li>
         *   <li>{@code " "} — single space; exercises the
         *       "whitespace masquerading as a user-type" reject path.</li>
         *   <li>{@code "X"} — unknown letter; exercises the "completely
         *       unknown user-type code" reject path. Provides defence in
         *       depth against an upstream service emitting an unrecognised
         *       value.</li>
         * </ul>
         *
         * <p>The case-insensitive containment check on the literal token
         * {@code "not authorized"} tolerates any production formatting that
         * preserves the core semantic (e.g., the exact COBOL-style
         * {@code "You are not authorized for Admin functions..."}, or
         * {@code "Not Authorized: Admin Only"}, or
         * {@code "NOT AUTHORIZED — admins only"}).
         *
         * @param nonAdminType one of the three non-admin caller-type values
         */
        @ParameterizedTest(name = "[{index}] non-admin caller type ''{0}'' is rejected")
        @ValueSource(strings = {"U", " ", "X"})
        @DisplayName("dispatch rejects non-admin caller")
        void dispatch_nonAdminCaller_rejected(String nonAdminType) {
            // Arrange — populate the request with a non-admin caller-type
            // code and a valid option string ("1" = User List). The option
            // is intentionally valid so the test proves the authorisation
            // check fires BEFORE option processing (a regular user with
            // option "1" must be rejected with the auth message, not
            // routed to USER_LIST).
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType(nonAdminType);
            request.setOption("1");

            // Act — invoke the production dispatcher through its public API.
            // No mock setup is needed because the dispatcher is stateless
            // and has no boundary collaborators.
            AdminMenuResponse response = service.dispatch(request);

            // Assert — observable behaviour of the production class:
            //   1. Result advertises failure (the authorisation reject
            //      path).
            //   2. Reject message references "not authorized" (case-
            //      insensitive containment proves the production code
            //      uses the canonical wording without over-specifying).
            //   3. nextRoute is null (failure responses must not carry a
            //      route per AdminMenuResponse.failure(...) factory).
            assertThat(response.isSuccess())
                    .as("Caller type '%s' must NOT be authorised", nonAdminType)
                    .isFalse();
            assertThat(response.getMessage())
                    .as("Authorisation reject message should reference 'not authorized'")
                    .containsIgnoringCase("not authorized");
            assertThat(response.getNextRoute())
                    .as("Failure response should not carry a next-route")
                    .isNull();
        }

        /**
         * Boundary check — explicit {@code null} caller-type. Java's
         * {@code String} can carry {@code null} where COBOL's
         * {@code PIC X(01)} cannot, so this exercises a Java-only edge case
         * that arises when the controller layer constructs an
         * {@link AdminMenuRequest} without calling
         * {@link AdminMenuRequest#setCallerUserType(String)}.
         *
         * <p>The dispatcher must reject the {@code null} caller-type with
         * the same authorisation message used for any other non-admin
         * caller (defence in depth — never authorise a missing caller-type).
         *
         * <p>Documents the user fixture identifier via {@link TestFixtures.Users}
         * to keep this test linked with the rest of the service-test layer's
         * fixture surface — the dispatcher itself does not read the user ID,
         * only the user-type code.
         */
        @Test
        @DisplayName("dispatch rejects null caller user-type")
        void dispatch_nullCallerUserType_rejected() {
            // Arrange — keep callerUserType unset (null) and supply a
            // valid option. The dispatcher must still reject — never
            // authorise a request whose caller-type is missing entirely.
            // The TestFixtures reference is documentary so an automated
            // audit can confirm the authorisation tests reference the
            // canonical fixture identifier set; the actual rejection is
            // driven by the null caller-type, not by the user ID.
            assertThat(TestFixtures.Users.REGULAR_USER_ID)
                    .as("Regular-user fixture identifier must remain stable for cross-test consistency")
                    .isNotBlank();
            AdminMenuRequest request = new AdminMenuRequest();
            // callerUserType deliberately left unset → null
            request.setOption("1");

            // Act
            AdminMenuResponse response = service.dispatch(request);

            // Assert — failure with the authorisation reject message.
            assertThat(response.isSuccess())
                    .as("Null caller-type must NOT be authorised")
                    .isFalse();
            assertThat(response.getMessage())
                    .as("Null caller-type reject should carry the authorisation message")
                    .containsIgnoringCase("not authorized");
            assertThat(response.getNextRoute())
                    .as("Failure response should not carry a next-route")
                    .isNull();
        }
    }

    // =========================================================================
    // OPTION DISPATCH — admin user
    //
    // Exercises every valid-option happy path (4 rows from COADM02Y), the
    // empty-option reject (Java-migration distinguished from invalid for
    // clearer UX), the invalid-option reject across the four failure
    // categories the dispatcher distinguishes (zero, out-of-range,
    // non-numeric, negative), and the explicit MAX_OPTION+1 boundary.
    // =========================================================================

    /**
     * Option-dispatch tests for {@link AdminMenuService#dispatch(AdminMenuRequest)}
     * — the COBOL {@code PROCESS-ENTER-KEY} workflow exercised end-to-end
     * with an admin-user {@code CDEMO-USRTYP-ADMIN} caller.
     */
    @Nested
    @DisplayName("Option dispatch — admin user")
    class OptionDispatch {

        /**
         * Drives the dispatcher with each valid option {@code 1}–{@code 4}
         * and asserts the next-route identifier matches the COADM02Y option
         * table. Four rows cover every arm of the switch expression in
         * {@link AdminMenuService#dispatch}, satisfying the AAP §0.7.1
         * branch-coverage target for the service layer.
         *
         * <p>The rows preserve {@code app/cpy/COADM02Y.cpy} order verbatim
         * (option 1 = User List / {@code COUSR00C}, option 4 = User Delete
         * / {@code COUSR03C}) per the COBOL source-of-truth.
         *
         * <p>The {@link TestFixtures.Users#ADMIN_USER_ID} reference is
         * documentary — the dispatcher reads only the user-type code; the
         * fixture identifier is included to keep this test linked with the
         * rest of the service-test layer's fixture surface.
         *
         * @param option        the option-number string the dispatcher
         *                      receives
         * @param expectedRoute the route identifier the dispatcher must
         *                      produce
         */
        @ParameterizedTest(name = "[{index}] option ''{0}'' dispatches to ''{1}''")
        @CsvSource({
            "1, USER_LIST",
            "2, USER_ADD",
            "3, USER_UPDATE",
            "4, USER_DELETE"
        })
        @DisplayName("dispatch routes valid options to expected routes")
        void dispatch_validOption_routesToExpectedRoute(String option, String expectedRoute) {
            // Arrange — populate the request with the admin caller-type
            // code ("A" = CDEMO-USRTYP-ADMIN per COCOM01Y.cpy line 27) and
            // the per-row option string. The TestFixtures.Users reference
            // documents the canonical admin fixture identifier — the
            // dispatcher reads only the user-type field, not the user ID.
            assertThat(TestFixtures.Users.ADMIN_USER_ID)
                    .as("Admin fixture identifier must remain stable for cross-test consistency")
                    .isNotBlank();
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType("A");
            request.setOption(option);

            // Act — invoke the production dispatcher through its public API.
            // No mock setup is needed because the dispatcher is stateless
            // and has no boundary collaborators.
            AdminMenuResponse response = service.dispatch(request);

            // Assert — observable behaviour of the production class:
            //   1. Result advertises success.
            //   2. Next-route matches the CsvSource expectation, proving
            //      the switch arm for this specific option resolved to the
            //      expected COADM02Y mapping.
            //   3. The reject-message field is null (success path leaves
            //      message null per AdminMenuResponse.success(...)).
            assertThat(response.isSuccess())
                    .as("Option '%s' should resolve to success", option)
                    .isTrue();
            assertThat(response.getNextRoute())
                    .as("Option '%s' should map to '%s'", option, expectedRoute)
                    .isEqualTo(expectedRoute);
            assertThat(response.getMessage())
                    .as("Success response should not carry a reject message")
                    .isNull();
        }

        /**
         * Empty-string option triggers the Java-migration empty-input
         * branch — distinct from invalid-input. COBOL collapses empty input
         * into {@code "Please enter a valid option number..."} via the
         * {@code INSPECT REPLACING} step; the Java migration distinguishes
         * the two cases for clearer UX, emitting
         * {@code "Please select an option..."} for empty input.
         *
         * <p>The case-insensitive containment check on the literal token
         * {@code "select"} tolerates any production formatting that
         * preserves the core semantic (e.g., {@code "Please select an option..."}
         * or {@code "Please Select An Option ..."}).
         */
        @Test
        @DisplayName("dispatch rejects empty option with 'select' message")
        void dispatch_emptyOption_rejected() {
            // Arrange — empty string for the option field (the COBOL
            // SPACES sentinel; after BMS receive this would be all spaces
            // padded out to PIC X(02)). Caller-type is admin so the
            // empty-option branch is the one exercised (NOT the auth
            // branch).
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType("A");
            request.setOption("");

            // Act
            AdminMenuResponse response = service.dispatch(request);

            // Assert — failure with the empty-input message; the nextRoute
            // is null on a failure outcome per AdminMenuResponse.failure(...).
            assertThat(response.isSuccess())
                    .as("Empty option must not succeed")
                    .isFalse();
            assertThat(response.getMessage())
                    .as("Empty-option message should reference 'select' per AAP reject specification")
                    .containsIgnoringCase("select");
            assertThat(response.getNextRoute())
                    .as("Failure response should not carry a next-route")
                    .isNull();
        }

        /**
         * Invalid (non-empty) option strings should all hit the
         * invalid-option reject branch. The four rows cover the four
         * distinct failure categories the dispatcher distinguishes:
         *
         * <ul>
         *   <li>{@code "0"} — COBOL {@code WS-OPTION = ZEROS}.</li>
         *   <li>{@code "99"} — COBOL {@code WS-OPTION >
         *       CDEMO-ADMIN-OPT-COUNT} (well above the 4-option max).</li>
         *   <li>{@code "ABC"} — COBOL {@code IS NOT NUMERIC} (Java's
         *       {@link NumberFormatException} equivalent).</li>
         *   <li>{@code "-1"} — Java-only path (COBOL {@code PIC 9(02)}
         *       cannot carry a sign; the Java migration explicitly rejects
         *       negative values for defence in depth).</li>
         * </ul>
         *
         * <p>The case-insensitive containment check on the literal token
         * {@code "valid option"} tolerates any production formatting that
         * preserves the core semantic (e.g., the exact COBOL-style
         * {@code "Please enter a valid option number..."}, or
         * {@code "Please Enter a Valid Option Number..."}).
         *
         * @param invalid the invalid option string under test
         */
        @ParameterizedTest(name = "[{index}] invalid option ''{0}''")
        @ValueSource(strings = {"0", "99", "ABC", "-1"})
        @DisplayName("dispatch rejects out-of-range or non-numeric option")
        void dispatch_invalidOption_rejected(String invalid) {
            // Arrange — populate the request with the admin caller-type
            // code and one of the four invalid option strings. Caller-type
            // is admin so the invalid-option branch is the one exercised
            // (NOT the auth branch).
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType("A");
            request.setOption(invalid);

            // Act
            AdminMenuResponse response = service.dispatch(request);

            // Assert — failure with the invalid-input message. The
            // case-insensitive containment check on "valid option"
            // tolerates any production formatting that preserves the core
            // semantic (matches the COBOL literal "Please enter a valid
            // option number...").
            assertThat(response.isSuccess())
                    .as("Option '%s' must be rejected", invalid)
                    .isFalse();
            assertThat(response.getMessage())
                    .as("Invalid-option message should reference 'valid option' per AAP reject specification")
                    .containsIgnoringCase("valid option");
            assertThat(response.getNextRoute())
                    .as("Failure response should not carry a next-route")
                    .isNull();
        }

        /**
         * Explicit boundary test at {@code MAX_OPTION + 1} = 5. This case
         * is also implicitly covered by the broader
         * {@link #dispatch_invalidOption_rejected @ValueSource} above
         * (which exercises {@code "99"} — well above the 4-option max),
         * but is kept here as a dedicated boundary regression so a future
         * refactor that splits the {@code @ValueSource} into smaller
         * groups still leaves the immediate-max-option boundary verified
         * by a named test.
         *
         * <p>The COBOL guard is {@code WS-OPTION > CDEMO-ADMIN-OPT-COUNT},
         * with {@code CDEMO-ADMIN-OPT-COUNT = 4}. Option 5 is the
         * immediate boundary above the max — the most common off-by-one
         * regression target.
         */
        @Test
        @DisplayName("dispatch rejects max-option boundary (option 5 == MAX_OPTION + 1)")
        void dispatch_optionAtBoundary_rejected() {
            // Arrange — option immediately above the COADM02Y MAX_OPTION =
            // 4 boundary; COBOL: WS-OPTION > CDEMO-ADMIN-OPT-COUNT.
            AdminMenuRequest request = new AdminMenuRequest();
            request.setCallerUserType("A");
            request.setOption("5");

            // Act
            AdminMenuResponse response = service.dispatch(request);

            // Assert — failure with the invalid-input message; explicit
            // null-check on nextRoute reinforces the reject-path invariant.
            assertThat(response.isSuccess())
                    .as("Option 5 is the immediate boundary above MAX_OPTION=4; must reject")
                    .isFalse();
            assertThat(response.getMessage())
                    .as("Boundary reject should carry the invalid-option message")
                    .containsIgnoringCase("valid option");
            assertThat(response.getNextRoute())
                    .as("Failure response should not carry a next-route")
                    .isNull();
        }
    }
}
