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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.MenuOptionDto;
import com.awsm2.carddemo.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JUnit 5 + AssertJ unit tests for {@link MenuService}.
 *
 * <p><b>COBOL provenance &mdash; the single AAP-sanctioned dual-program
 * service.</b> {@link MenuService} translates the two CICS menu programs
 * {@code app/cbl/COMEN01C.cbl} (CICS transaction id {@code CM00}, main
 * menu for regular users) and {@code app/cbl/COADM01C.cbl} (CICS
 * transaction id {@code CA00}, admin menu) into a single Java
 * {@code @Service} class &mdash; the only intentional exception to the
 * one-service-per-COBOL-program rule (AAP &sect;0.7.1) and explicitly
 * sanctioned by AAP &sect;0.4.1 ("MenuService.java &larr; COMEN01C,
 * COADM01C &mdash; serve menu structure from COMEN02Y/COADM02Y").</p>
 *
 * <p>The COBOL menu programs share an identical control structure (read
 * literal-storage option table &rarr; render BMS map &rarr; dispatch on
 * PF/AID key &rarr; validate option, gate by user-type, {@code XCTL} to
 * target program). The only meaningful difference is the working-storage
 * option table they consume:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} &mdash; main-menu option table
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS}, 10 entries, all gated by
 *       {@code USR-TYPE = 'U'}).</li>
 *   <li>{@code app/cpy/COADM02Y.cpy} &mdash; admin-menu option table
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, 4 entries, no
 *       {@code USR-TYPE} field because all admin-menu entries are
 *       admin-only by construction).</li>
 * </ul>
 *
 * <p><b>Behaviors under test.</b> The {@link MenuService} class exposes
 * three public methods, each covered by a dedicated {@link Nested} group:</p>
 *
 * <ol>
 *   <li>{@link MenuService#getMainMenu(String)} &rarr; covered by
 *       {@link MainMenuOptions}: verifies the 10-entry main-menu option
 *       table matches {@code app/cpy/COMEN02Y.cpy} verbatim (option
 *       numbers, labels, COBOL target-program identifiers, REST endpoint
 *       URLs, user-type gates, enabled flags), that user-identity fields
 *       are empty (controller augments from JWT), and that the response
 *       is idempotent across invocations.</li>
 *   <li>{@link MenuService#getAdminMenu(String)} &rarr; covered by
 *       {@link AdminMenuOptions} (the happy-path) and {@link AdminGate}
 *       (the non-admin denial gate). The happy-path tests verify the
 *       4-entry admin-menu option table matches
 *       {@code app/cpy/COADM02Y.cpy} verbatim and includes the four
 *       user-maintenance options ({@code COUSR00C} through
 *       {@code COUSR03C}). The denial-gate tests verify that callers
 *       supplying any user-type other than {@code "A"} receive a
 *       {@link ValidationException} carrying the verbatim COBOL message
 *       {@code "No access - Admin Only option (user type must be 'A')"}
 *       (mirroring {@code COMEN01C.cbl:PROCESS-ENTER-KEY}).</li>
 *   <li>{@link MenuService#resolveMenuTarget(String, String, boolean)}
 *       &rarr; covered by {@link ResolveMenuTarget}: verifies the
 *       four-stage validation cascade from {@code COMEN01C.cbl} /
 *       {@code COADM01C.cbl} {@code PROCESS-ENTER-KEY} paragraphs:
 *       (1) numeric check, (2) bounds check, (3) admin-only gate (only
 *       fires when {@code isAdminMenu=true} on a {@code "U"} caller given
 *       current data), and (4) {@code DUMMY} "coming soon" gate (no
 *       currently-active entries; logic preserved per AAP &sect;0.7.3).</li>
 * </ol>
 *
 * <p><b>Test scaffolding rationale.</b> {@link MenuService} has <em>no</em>
 * collaborators &mdash; the menu tables are immutable {@code static final}
 * {@code List<MenuOptionDto>} fields initialized at class-load time. We
 * therefore construct the unit-under-test via plain
 * {@code new MenuService()} in {@link #setUp()} rather than via
 * {@code @InjectMocks}. The schema-declared
 * {@code @ExtendWith(MockitoExtension.class)} annotation is preserved on
 * the class for consistency with peer service-layer tests
 * (e.g., {@code SignonServiceTest}, {@code AccountViewServiceTest}); the
 * extension is a no-op when no {@code @Mock} fields are declared.</p>
 *
 * <p><b>Assertion strategy.</b> AssertJ's fluent {@link
 * org.assertj.core.api.Assertions#assertThat(Object) assertThat} and
 * {@link org.assertj.core.api.Assertions#assertThatThrownBy(
 * org.assertj.core.api.ThrowableAssert.ThrowingCallable) assertThatThrownBy}
 * are used throughout. Error-message assertions use
 * {@link org.assertj.core.api.AbstractThrowableAssert#hasMessage(String)
 * hasMessage(String)} with the <em>verbatim</em> COBOL-derived message
 * text so any future drift in the canonical message wording will fail the
 * test immediately (preserving the "Error codes and condition handling
 * surfaced to downstream consumers must be preserved verbatim" mandate
 * from AAP &sect;0.7.2).</p>
 *
 * @see MenuService
 * @see MainMenuDto
 * @see AdminMenuDto
 * @see MenuOptionDto
 * @see ValidationException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MenuService \u2014 COMEN01C + COADM01C static menus")
class MenuServiceTest {

    /**
     * Verbatim error message used by {@link MenuService} for admin-only
     * access denial. Sourced from
     * {@code app/cbl/COMEN01C.cbl:PROCESS-ENTER-KEY} (line 140 of the
     * COBOL program: {@code MOVE 'No access - Admin Only option... ' TO
     * WS-MESSAGE}) with the trailing COBOL space collapsed and a brief
     * parenthetical hint appended for REST clients. Tests assert this
     * string verbatim so any future drift in the canonical message
     * wording will fail immediately.
     */
    private static final String EXPECTED_ADMIN_ONLY_MESSAGE =
            "No access - Admin Only option (user type must be 'A')";

    /**
     * Verbatim main-menu title used by {@link MenuService}. Sourced from
     * {@code app/cpy/COTTL01Y.cpy} {@code CCDA-TITLE01}/{@code CCDA-TITLE02}
     * with the screen-specific suffix {@code " - Main Menu"} appended;
     * length-bounded to 40 chars to align with the BMS
     * {@code TITLE01}/{@code TITLE02} fields in
     * {@code app/bms/COMEN01.bms}.
     */
    private static final String EXPECTED_MAIN_MENU_TITLE =
            "AWS CardDemo - Main Menu";

    /**
     * Verbatim admin-menu title used by {@link MenuService}. Sourced from
     * {@code app/cpy/COTTL01Y.cpy} {@code CCDA-TITLE01}/{@code CCDA-TITLE02}
     * with the screen-specific suffix {@code " - Admin Menu"} appended;
     * length-bounded to 40 chars to align with the BMS
     * {@code TITLE01}/{@code TITLE02} fields in
     * {@code app/bms/COADM01.bms}.
     */
    private static final String EXPECTED_ADMIN_MENU_TITLE =
            "AWS CardDemo - Admin Menu";

    /**
     * Admin user-type discriminator. Maps to
     * {@code SEC-USR-TYPE PIC X(01)} value {@code 'A'} from
     * {@code app/cpy/CSUSR01Y.cpy} (admin user).
     */
    private static final String USER_TYPE_ADMIN = "A";

    /**
     * Regular user-type discriminator. Maps to
     * {@code SEC-USR-TYPE PIC X(01)} value {@code 'U'} from
     * {@code app/cpy/CSUSR01Y.cpy} (regular user).
     */
    private static final String USER_TYPE_USER = "U";

    /**
     * Expected count of entries in the main-menu option table. Matches
     * the {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} declaration in
     * {@code app/cpy/COMEN02Y.cpy} (line 21) verbatim.
     */
    private static final int EXPECTED_MAIN_MENU_OPTION_COUNT = 10;

    /**
     * Expected count of entries in the admin-menu option table. Matches
     * the {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} declaration in
     * {@code app/cpy/COADM02Y.cpy} (line 20) verbatim.
     */
    private static final int EXPECTED_ADMIN_MENU_OPTION_COUNT = 4;

    /**
     * The unit under test. Constructed fresh in {@link #setUp()} before
     * each individual test method so no shared state can leak between
     * tests. The service itself is stateless (its menu tables are
     * immutable class-load-time constants) but the per-test reconstruction
     * keeps the test methodology consistent with peer service-layer tests
     * and protects against any future state introduction.
     */
    private MenuService service;

    /**
     * Per-test setup. Instantiates {@link MenuService} directly with
     * {@code new} because the service has zero dependencies (pure
     * constant-data service serving static menu options from
     * {@code COMEN02Y.cpy}/{@code COADM02Y.cpy} literal storage). The
     * file schema's {@code internal_imports} entry for {@code MenuService}
     * explicitly directs: <em>"instantiated directly via
     * {@code new MenuService()} because the service has no dependencies"</em>.
     */
    @BeforeEach
    void setUp() {
        // COBOL: COMEN01C + COADM01C are pure pseudo-conversational handlers
        // with no static collaborators in their PROCEDURE DIVISION beyond
        // the BMS map I/O verbs and the literal-storage tables themselves;
        // the Java equivalent has zero injected collaborators, so a direct
        // `new` constructor call models the COBOL execution context faithfully.
        service = new MenuService();
    }

    // -----------------------------------------------------------------------
    // MAIN MENU OPTIONS (COMEN01C / COMEN02Y.cpy)
    // -----------------------------------------------------------------------

    /**
     * Tests for {@link MenuService#getMainMenu(String)} &mdash; the
     * regular-user main menu sourced from
     * {@code app/cpy/COMEN02Y.cpy:CARDDEMO-MAIN-MENU-OPTIONS}.
     *
     * <p>Verifies the verbatim 10-entry option table:</p>
     * <pre>{@code
     *   Option  Label                  TargetProgram  TargetEndpoint                UsrType
     *   ------  --------------------  -------------  ---------------------------  -------
     *     01    Account View          COACTVWC       /api/accounts/{id}            U
     *     02    Account Update        COACTUPC       /api/accounts/{id}            U
     *     03    Credit Card List      COCRDLIC       /api/cards                    U
     *     04    Credit Card View      COCRDSLC       /api/cards/{cardNumber}       U
     *     05    Credit Card Update    COCRDUPC       /api/cards/{cardNumber}       U
     *     06    Transaction List      COTRN00C       /api/transactions             U
     *     07    Transaction View      COTRN01C       /api/transactions/{id}        U
     *     08    Transaction Add       COTRN02C       /api/transactions             U
     *     09    Transaction Reports   CORPT00C       /api/reports/submit           U
     *     10    Bill Payment          COBIL00C       /api/billing/pay              U
     * }</pre>
     */
    @Nested
    @DisplayName("getMainMenu(userType) \u2014 COMEN02Y.cpy main-menu options")
    class MainMenuOptions {

        /**
         * Verifies that {@link MenuService#getMainMenu(String)} never
         * returns {@code null} for a valid regular-user invocation
         * (mirrors the COBOL behavior where {@code COMEN01C:MAIN-PARA}
         * always renders the menu, even on first invocation with empty
         * COMMAREA &mdash; the program never returns "no menu").
         */
        @Test
        @DisplayName("returns non-null DTO for regular user")
        void getMainMenu_returnsNonNullForRegularUser() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result).as("Main menu response should never be null")
                    .isNotNull();
        }

        /**
         * Verifies that the returned options list contains exactly
         * {@value #EXPECTED_MAIN_MENU_OPTION_COUNT} entries, matching the
         * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} declaration in
         * {@code app/cpy/COMEN02Y.cpy} verbatim.
         */
        @Test
        @DisplayName("contains exactly 10 options (COMEN02Y.cpy CDEMO-MENU-OPT-COUNT)")
        void getMainMenu_returnsExactly10Options() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options())
                    .as("Main menu must have exactly %d options per COMEN02Y.cpy",
                            EXPECTED_MAIN_MENU_OPTION_COUNT)
                    .hasSize(EXPECTED_MAIN_MENU_OPTION_COUNT);
        }

        /**
         * Verifies that the options list is never empty (defense-in-depth
         * test in addition to the exact-count assertion above &mdash;
         * catches any future refactor that accidentally swaps in an empty
         * collection).
         */
        @Test
        @DisplayName("options list is not empty")
        void getMainMenu_optionsAreNotEmpty() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options())
                    .as("Main menu options must not be empty")
                    .isNotEmpty();
        }

        /**
         * Verifies that the returned options list is unmodifiable. The
         * {@link MenuService} initializes its tables via
         * {@link List#of(Object[])} which returns an immutable list per
         * Java SE 9+ semantics. Any attempt to mutate the list must
         * throw {@link UnsupportedOperationException}, guaranteeing
         * thread-safe sharing without synchronization (per the
         * {@code MenuService} class-level Javadoc).
         */
        @Test
        @DisplayName("options list is unmodifiable")
        void getMainMenu_optionsListIsImmutable() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);
            List<MenuOptionDto> options = result.options();

            assertThatThrownBy(() -> options.add(new MenuOptionDto(99,
                            "Hacked", "HACKED", "/api/hacked", "U", true)))
                    .as("Main menu options must be immutable per List.of() contract")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * Verifies option 1 = {@code Account View} (COBOL target program
         * {@code COACTVWC}). Sourced from {@code app/cpy/COMEN02Y.cpy}
         * lines 25-29 (option number 1, label
         * {@code 'Account View                       '},
         * program {@code 'COACTVWC'}, user-type {@code 'U'}). The label
         * is trimmed of trailing spaces in the Java port because Java
         * Strings are not fixed-width like {@code PIC X(35)}.
         */
        @Test
        @DisplayName("option 1 = Account View / COACTVWC / /api/accounts/{id}")
        void getMainMenu_option1IsAccountView() {
            // COBOL: COMEN02Y.cpy option 1 - 'Account View' COACTVWC USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(0);

            assertThat(option.optionNumber()).isEqualTo(1);
            assertThat(option.label()).isEqualTo("Account View");
            assertThat(option.targetProgram()).isEqualTo("COACTVWC");
            assertThat(option.targetEndpoint()).isEqualTo("/api/accounts/{id}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 2 = {@code Account Update} (COBOL target program
         * {@code COACTUPC}). Sourced from {@code app/cpy/COMEN02Y.cpy}
         * lines 31-35.
         */
        @Test
        @DisplayName("option 2 = Account Update / COACTUPC / /api/accounts/{id}")
        void getMainMenu_option2IsAccountUpdate() {
            // COBOL: COMEN02Y.cpy option 2 - 'Account Update' COACTUPC USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(1);

            assertThat(option.optionNumber()).isEqualTo(2);
            assertThat(option.label()).isEqualTo("Account Update");
            assertThat(option.targetProgram()).isEqualTo("COACTUPC");
            assertThat(option.targetEndpoint()).isEqualTo("/api/accounts/{id}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 3 = {@code Credit Card List} (COBOL target
         * program {@code COCRDLIC}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 37-41.
         */
        @Test
        @DisplayName("option 3 = Credit Card List / COCRDLIC / /api/cards")
        void getMainMenu_option3IsCreditCardList() {
            // COBOL: COMEN02Y.cpy option 3 - 'Credit Card List' COCRDLIC USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(2);

            assertThat(option.optionNumber()).isEqualTo(3);
            assertThat(option.label()).isEqualTo("Credit Card List");
            assertThat(option.targetProgram()).isEqualTo("COCRDLIC");
            assertThat(option.targetEndpoint()).isEqualTo("/api/cards");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 4 = {@code Credit Card View} (COBOL target
         * program {@code COCRDSLC}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 43-47.
         */
        @Test
        @DisplayName("option 4 = Credit Card View / COCRDSLC / /api/cards/{cardNumber}")
        void getMainMenu_option4IsCreditCardView() {
            // COBOL: COMEN02Y.cpy option 4 - 'Credit Card View' COCRDSLC USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(3);

            assertThat(option.optionNumber()).isEqualTo(4);
            assertThat(option.label()).isEqualTo("Credit Card View");
            assertThat(option.targetProgram()).isEqualTo("COCRDSLC");
            assertThat(option.targetEndpoint()).isEqualTo("/api/cards/{cardNumber}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 5 = {@code Credit Card Update} (COBOL target
         * program {@code COCRDUPC}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 49-53.
         */
        @Test
        @DisplayName("option 5 = Credit Card Update / COCRDUPC / /api/cards/{cardNumber}")
        void getMainMenu_option5IsCreditCardUpdate() {
            // COBOL: COMEN02Y.cpy option 5 - 'Credit Card Update' COCRDUPC USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(4);

            assertThat(option.optionNumber()).isEqualTo(5);
            assertThat(option.label()).isEqualTo("Credit Card Update");
            assertThat(option.targetProgram()).isEqualTo("COCRDUPC");
            assertThat(option.targetEndpoint()).isEqualTo("/api/cards/{cardNumber}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 6 = {@code Transaction List} (COBOL target
         * program {@code COTRN00C}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 55-59.
         */
        @Test
        @DisplayName("option 6 = Transaction List / COTRN00C / /api/transactions")
        void getMainMenu_option6IsTransactionList() {
            // COBOL: COMEN02Y.cpy option 6 - 'Transaction List' COTRN00C USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(5);

            assertThat(option.optionNumber()).isEqualTo(6);
            assertThat(option.label()).isEqualTo("Transaction List");
            assertThat(option.targetProgram()).isEqualTo("COTRN00C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/transactions");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 7 = {@code Transaction View} (COBOL target
         * program {@code COTRN01C}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 61-65.
         */
        @Test
        @DisplayName("option 7 = Transaction View / COTRN01C / /api/transactions/{id}")
        void getMainMenu_option7IsTransactionView() {
            // COBOL: COMEN02Y.cpy option 7 - 'Transaction View' COTRN01C USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(6);

            assertThat(option.optionNumber()).isEqualTo(7);
            assertThat(option.label()).isEqualTo("Transaction View");
            assertThat(option.targetProgram()).isEqualTo("COTRN01C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/transactions/{id}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 8 = {@code Transaction Add} (COBOL target
         * program {@code COTRN02C}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 67-72.
         *
         * <p><b>Note on label.</b> The source COBOL copybook contains a
         * commented-out alternate label {@code 'Transaction Add (Admin
         * Only)       '} (line 69) suggesting an earlier intent to gate
         * this option admin-only. The <em>active</em> label is
         * {@code 'Transaction Add                    '.} (line 70) and
         * the user-type gate is {@code 'U'} (line 72), so any user can
         * use transaction-add. The Java port preserves this verbatim.</p>
         */
        @Test
        @DisplayName("option 8 = Transaction Add / COTRN02C / /api/transactions")
        void getMainMenu_option8IsTransactionAdd() {
            // COBOL: COMEN02Y.cpy option 8 - 'Transaction Add' COTRN02C USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(7);

            assertThat(option.optionNumber()).isEqualTo(8);
            assertThat(option.label()).isEqualTo("Transaction Add");
            assertThat(option.targetProgram()).isEqualTo("COTRN02C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/transactions");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 9 = {@code Transaction Reports} (COBOL target
         * program {@code CORPT00C}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 74-78.
         */
        @Test
        @DisplayName("option 9 = Transaction Reports / CORPT00C / /api/reports/submit")
        void getMainMenu_option9IsTransactionReports() {
            // COBOL: COMEN02Y.cpy option 9 - 'Transaction Reports' CORPT00C USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(8);

            assertThat(option.optionNumber()).isEqualTo(9);
            assertThat(option.label()).isEqualTo("Transaction Reports");
            assertThat(option.targetProgram()).isEqualTo("CORPT00C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/reports/submit");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 10 = {@code Bill Payment} (COBOL target
         * program {@code COBIL00C}). Sourced from
         * {@code app/cpy/COMEN02Y.cpy} lines 80-84.
         */
        @Test
        @DisplayName("option 10 = Bill Payment / COBIL00C / /api/billing/pay")
        void getMainMenu_option10IsBillPayment() {
            // COBOL: COMEN02Y.cpy option 10 - 'Bill Payment' COBIL00C USR='U'
            MenuOptionDto option = service.getMainMenu(USER_TYPE_USER)
                    .options().get(9);

            assertThat(option.optionNumber()).isEqualTo(10);
            assertThat(option.label()).isEqualTo("Bill Payment");
            assertThat(option.targetProgram()).isEqualTo("COBIL00C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/billing/pay");
            assertThat(option.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies that every main-menu option is gated by
         * {@code USR-TYPE = 'U'}, matching the source COBOL copybook
         * {@code app/cpy/COMEN02Y.cpy} where every {@code FILLER PIC
         * X(01) VALUE 'U'} entry tags the option as a regular-user
         * option (lines 29, 35, 41, 47, 53, 59, 65, 72, 78, 84).
         */
        @Test
        @DisplayName("all 10 options carry user-type 'U' (per COMEN02Y.cpy)")
        void getMainMenu_allOptionsHaveUserTypeU() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options())
                    .as("Every main-menu option in COMEN02Y.cpy is USR='U'")
                    .allSatisfy(option ->
                            assertThat(option.userType()).isEqualTo(USER_TYPE_USER));
        }

        /**
         * Verifies that every main-menu option is enabled by default.
         * The {@code enabled} field is a net-new addition (no COBOL
         * counterpart in {@code COMEN02Y.cpy}) introduced for stateless
         * REST semantics per AAP &sect;0.3.4 &mdash; in the legacy CICS
         * model, runtime gating was done by re-rendering the menu
         * without the disallowed option. In REST every option is
         * enabled at request time; per-session gating is the controller's
         * concern.
         */
        @Test
        @DisplayName("all 10 options are enabled by default")
        void getMainMenu_allOptionsAreEnabled() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options())
                    .as("Every main-menu option must be enabled by default")
                    .allSatisfy(option -> assertThat(option.enabled()).isTrue());
        }

        /**
         * Verifies that option numbers are sequential 1..10 (matching
         * the COBOL {@code CDEMO-MENU-OPT-NUM} sequence
         * {@code 1, 2, 3, ..., 10} in {@code app/cpy/COMEN02Y.cpy}).
         * Establishes a strict ordering invariant that any future
         * refactor of the static table cannot accidentally violate.
         */
        @Test
        @DisplayName("option numbers are sequential 1..10")
        void getMainMenu_optionNumbersAreSequential1to10() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            // COBOL: COMEN02Y.cpy emits CDEMO-MENU-OPT-NUM PIC 9(02) VALUE 1..10
            List<MenuOptionDto> options = result.options();
            for (int i = 0; i < options.size(); i++) {
                assertThat(options.get(i).optionNumber())
                        .as("Option at zero-based index %d must have "
                                + "optionNumber %d", i, i + 1)
                        .isEqualTo(i + 1);
            }
        }

        /**
         * Verifies idempotency &mdash; calling {@link
         * MenuService#getMainMenu(String)} twice returns equal
         * structural content (same option list). Mirrors the COBOL
         * behavior where {@code BUILD-MENU-OPTIONS} is invoked on every
         * pseudo-conversational re-entry and always emits the same
         * menu (modulo the date/time stamp, which the Java
         * implementation defers to the controller).
         */
        @Test
        @DisplayName("returns structurally-equal results across invocations")
        void getMainMenu_isStableAcrossInvocations() {
            MainMenuDto first = service.getMainMenu(USER_TYPE_USER);
            MainMenuDto second = service.getMainMenu(USER_TYPE_USER);

            // The options list is a singleton static reference, so the
            // returned DTOs carry identical content (and the same list
            // reference). Use structural equality so the test does not
            // depend on whether MenuService returns the same DTO object
            // (today it allocates a new DTO each call).
            assertThat(first.options())
                    .as("Main menu options should be stable across invocations")
                    .containsExactlyElementsOf(second.options());
            assertThat(first.title()).isEqualTo(second.title());
            assertThat(first.userType()).isEqualTo(second.userType());
        }

        /**
         * Verifies that the requestor's user-type is echoed back into
         * {@link MainMenuDto#userType()} unchanged so the client can
         * render role-appropriate badging (e.g., a "View as Admin"
         * banner when an admin inspects the regular menu).
         */
        @Test
        @DisplayName("echoes back the requestor user-type for regular user")
        void getMainMenu_echoesUserTypeForRegularUser() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.userType())
                    .as("Main menu must echo requestor's user-type for "
                            + "client-side badging")
                    .isEqualTo(USER_TYPE_USER);
        }

        /**
         * Verifies that an admin user can still request the main menu
         * (per AAP &sect;0.3.4 &mdash; admins may inspect the regular
         * menu) and that the echoed user-type is preserved verbatim.
         * Mirrors the COBOL behavior where the {@code BUILD-MENU-OPTIONS}
         * filter logic
         * ({@code IF CDEMO-MENU-OPT-USRTYPE = CDEMO-USRTYPE OR
         * LOW-VALUES}) also admitted admin requestors against
         * {@code 'U'}-gated options.
         */
        @Test
        @DisplayName("admin user can request the main menu")
        void getMainMenu_admin_canStillSeeMainMenu() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_ADMIN);

            assertThat(result).isNotNull();
            assertThat(result.options())
                    .hasSize(EXPECTED_MAIN_MENU_OPTION_COUNT);
            assertThat(result.userType()).isEqualTo(USER_TYPE_ADMIN);
        }

        /**
         * Verifies graceful handling of a {@code null} user-type. The
         * service does not validate user-type because the controller's
         * {@code @PreAuthorize("isAuthenticated()")} gate has already
         * enforced presence of an authenticated user; {@code null} is
         * therefore passed through and echoed back unchanged.
         */
        @Test
        @DisplayName("handles null user-type without throwing")
        void getMainMenu_handlesNullUserType() {
            MainMenuDto result = service.getMainMenu(null);

            assertThat(result).isNotNull();
            assertThat(result.userType())
                    .as("Null user-type is propagated unchanged for "
                            + "controller-layer handling")
                    .isNull();
            assertThat(result.options())
                    .hasSize(EXPECTED_MAIN_MENU_OPTION_COUNT);
        }

        /**
         * Verifies the page title matches the expected
         * {@value #EXPECTED_MAIN_MENU_TITLE} (sourced from
         * {@code app/cpy/COTTL01Y.cpy} with the screen-specific suffix
         * appended).
         */
        @Test
        @DisplayName("title equals 'AWS CardDemo - Main Menu'")
        void getMainMenu_titleIsExpectedString() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.title())
                    .as("Main menu title must match COTTL01Y.cpy-derived literal")
                    .isEqualTo(EXPECTED_MAIN_MENU_TITLE);
        }

        /**
         * Verifies that user-identity fields ({@code userId},
         * {@code firstName}, {@code lastName}) are returned as empty
         * strings &mdash; the {@link MenuService} contract is that the
         * controller layer augments these fields from JWT claims before
         * serializing the response, preserving the layered-architecture
         * discipline from AAP &sect;0.3.3 (authentication context is a
         * controller concern, menu data is a service concern).
         */
        @Test
        @DisplayName("user-identity fields are empty (controller augments from JWT)")
        void getMainMenu_userIdentityFieldsAreEmpty() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.userId()).isEqualTo("");
            assertThat(result.firstName()).isEqualTo("");
            assertThat(result.lastName()).isEqualTo("");
        }

        /**
         * Verifies that every option carries a non-null,
         * non-blank label, target program, and target endpoint
         * (defense-in-depth integrity check on the entire table). No
         * COBOL {@code FILLER PIC X(35)} value in
         * {@code app/cpy/COMEN02Y.cpy} is blank or null &mdash; the
         * Java port must preserve this.
         */
        @Test
        @DisplayName("every option has non-blank label, targetProgram, and targetEndpoint")
        void getMainMenu_everyOptionHasRequiredStringFields() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options()).allSatisfy(option -> {
                assertThat(option.label())
                        .as("Option %d label must not be blank", option.optionNumber())
                        .isNotBlank();
                assertThat(option.targetProgram())
                        .as("Option %d targetProgram must not be blank",
                                option.optionNumber())
                        .isNotBlank();
                assertThat(option.targetEndpoint())
                        .as("Option %d targetEndpoint must not be blank",
                                option.optionNumber())
                        .isNotBlank();
            });
        }

        /**
         * Verifies that every {@code targetProgram} field is 8
         * characters or fewer, matching the COBOL
         * {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} declaration in
         * {@code app/cpy/COMEN02Y.cpy} (line 91) verbatim.
         */
        @Test
        @DisplayName("every targetProgram is <= 8 chars (PIC X(08) constraint)")
        void getMainMenu_everyTargetProgramFitsPicX08() {
            MainMenuDto result = service.getMainMenu(USER_TYPE_USER);

            assertThat(result.options()).allSatisfy(option ->
                    assertThat(option.targetProgram().length())
                            .as("Option %d targetProgram '%s' exceeds PIC X(08)",
                                    option.optionNumber(), option.targetProgram())
                            .isLessThanOrEqualTo(8));
        }
    }

    // -----------------------------------------------------------------------
    // ADMIN MENU OPTIONS (COADM01C / COADM02Y.cpy)
    // -----------------------------------------------------------------------

    /**
     * Tests for {@link MenuService#getAdminMenu(String)} happy-path
     * &mdash; admin user receives the admin menu sourced from
     * {@code app/cpy/COADM02Y.cpy:CARDDEMO-ADMIN-MENU-OPTIONS}.
     *
     * <p>Verifies the verbatim 4-entry option table (all admin-only by
     * construction; {@code COADM02Y.cpy} omits the {@code USR-TYPE}
     * field that {@code COMEN02Y.cpy} carries):</p>
     * <pre>{@code
     *   Option  Label                       TargetProgram  TargetEndpoint
     *   ------  -------------------------   -------------  ------------------------
     *     01    User List (Security)        COUSR00C       /api/admin/users
     *     02    User Add (Security)         COUSR01C       /api/admin/users
     *     03    User Update (Security)      COUSR02C       /api/admin/users/{id}
     *     04    User Delete (Security)      COUSR03C       /api/admin/users/{id}
     * }</pre>
     */
    @Nested
    @DisplayName("getAdminMenu(\"A\") \u2014 COADM02Y.cpy admin-menu options (happy path)")
    class AdminMenuOptions {

        /**
         * Verifies that {@link MenuService#getAdminMenu(String)} never
         * returns {@code null} for a valid admin invocation.
         */
        @Test
        @DisplayName("returns non-null DTO for admin user")
        void getAdminMenu_returnsNonNullForAdminUser() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result).as("Admin menu response should never be null "
                    + "for an admin caller").isNotNull();
        }

        /**
         * Verifies that the returned options list contains exactly
         * {@value #EXPECTED_ADMIN_MENU_OPTION_COUNT} entries, matching
         * the {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}
         * declaration in {@code app/cpy/COADM02Y.cpy} (line 20) verbatim.
         */
        @Test
        @DisplayName("contains exactly 4 options (COADM02Y.cpy CDEMO-ADMIN-OPT-COUNT)")
        void getAdminMenu_returnsExactly4Options() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options())
                    .as("Admin menu must have exactly %d options per COADM02Y.cpy",
                            EXPECTED_ADMIN_MENU_OPTION_COUNT)
                    .hasSize(EXPECTED_ADMIN_MENU_OPTION_COUNT);
        }

        /**
         * Verifies that the options list is never empty (defense-in-depth
         * test in addition to the exact-count assertion).
         */
        @Test
        @DisplayName("options list is not empty")
        void getAdminMenu_optionsAreNotEmpty() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options()).isNotEmpty();
        }

        /**
         * Verifies that the returned options list is unmodifiable
         * (initialized via {@link List#of(Object[])}). Mirrors the
         * thread-safety guarantee documented on
         * {@code MenuService.ADMIN_MENU_OPTIONS}.
         */
        @Test
        @DisplayName("options list is unmodifiable")
        void getAdminMenu_optionsListIsImmutable() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);
            List<MenuOptionDto> options = result.options();

            assertThatThrownBy(() -> options.add(new MenuOptionDto(99,
                            "Hacked", "HACKED", "/api/hacked", "A", true)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * Verifies option 1 = {@code User List (Security)} (COBOL
         * target program {@code COUSR00C}). Sourced from
         * {@code app/cpy/COADM02Y.cpy} lines 24-27.
         *
         * <p>The trailing parenthetical {@code (Security)} is preserved
         * verbatim from the COBOL literal {@code 'User List (Security)
         * '} (line 26) and signals that the option dispatches to the
         * security-domain {@code USRSEC} record-maintenance flows.</p>
         */
        @Test
        @DisplayName("option 1 = User List (Security) / COUSR00C / /api/admin/users")
        void getAdminMenu_option1IsUserList() {
            // COBOL: COADM02Y.cpy option 1 - 'User List (Security)' COUSR00C
            MenuOptionDto option = service.getAdminMenu(USER_TYPE_ADMIN)
                    .options().get(0);

            assertThat(option.optionNumber()).isEqualTo(1);
            assertThat(option.label()).isEqualTo("User List (Security)");
            assertThat(option.targetProgram()).isEqualTo("COUSR00C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/admin/users");
            assertThat(option.userType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 2 = {@code User Add (Security)} (COBOL
         * target program {@code COUSR01C}). Sourced from
         * {@code app/cpy/COADM02Y.cpy} lines 29-32.
         */
        @Test
        @DisplayName("option 2 = User Add (Security) / COUSR01C / /api/admin/users")
        void getAdminMenu_option2IsUserAdd() {
            // COBOL: COADM02Y.cpy option 2 - 'User Add (Security)' COUSR01C
            MenuOptionDto option = service.getAdminMenu(USER_TYPE_ADMIN)
                    .options().get(1);

            assertThat(option.optionNumber()).isEqualTo(2);
            assertThat(option.label()).isEqualTo("User Add (Security)");
            assertThat(option.targetProgram()).isEqualTo("COUSR01C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/admin/users");
            assertThat(option.userType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 3 = {@code User Update (Security)} (COBOL
         * target program {@code COUSR02C}). Sourced from
         * {@code app/cpy/COADM02Y.cpy} lines 34-37.
         */
        @Test
        @DisplayName("option 3 = User Update (Security) / COUSR02C / /api/admin/users/{id}")
        void getAdminMenu_option3IsUserUpdate() {
            // COBOL: COADM02Y.cpy option 3 - 'User Update (Security)' COUSR02C
            MenuOptionDto option = service.getAdminMenu(USER_TYPE_ADMIN)
                    .options().get(2);

            assertThat(option.optionNumber()).isEqualTo(3);
            assertThat(option.label()).isEqualTo("User Update (Security)");
            assertThat(option.targetProgram()).isEqualTo("COUSR02C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/admin/users/{id}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies option 4 = {@code User Delete (Security)} (COBOL
         * target program {@code COUSR03C}). Sourced from
         * {@code app/cpy/COADM02Y.cpy} lines 39-42.
         */
        @Test
        @DisplayName("option 4 = User Delete (Security) / COUSR03C / /api/admin/users/{id}")
        void getAdminMenu_option4IsUserDelete() {
            // COBOL: COADM02Y.cpy option 4 - 'User Delete (Security)' COUSR03C
            MenuOptionDto option = service.getAdminMenu(USER_TYPE_ADMIN)
                    .options().get(3);

            assertThat(option.optionNumber()).isEqualTo(4);
            assertThat(option.label()).isEqualTo("User Delete (Security)");
            assertThat(option.targetProgram()).isEqualTo("COUSR03C");
            assertThat(option.targetEndpoint()).isEqualTo("/api/admin/users/{id}");
            assertThat(option.userType()).isEqualTo(USER_TYPE_ADMIN);
            assertThat(option.enabled()).isTrue();
        }

        /**
         * Verifies that every admin-menu option is tagged
         * {@code userType="A"} in the Java port. Although
         * {@code app/cpy/COADM02Y.cpy} omits the
         * {@code CDEMO-ADMIN-OPT-USRTYPE} field altogether (admin-menu
         * entries are admin-only by construction in COBOL), the Java
         * port carries {@code userType="A"} on every entry for clarity
         * and to keep the dispatch-time gate in
         * {@link MenuService#resolveMenuTarget(String, String, boolean)}
         * uniform across both tables.
         */
        @Test
        @DisplayName("all 4 options are tagged userType='A'")
        void getAdminMenu_allOptionsHaveUserTypeA() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options()).allSatisfy(option ->
                    assertThat(option.userType()).isEqualTo(USER_TYPE_ADMIN));
        }

        /**
         * Verifies that every admin-menu option is enabled by default.
         */
        @Test
        @DisplayName("all 4 options are enabled by default")
        void getAdminMenu_allOptionsAreEnabled() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options()).allSatisfy(option ->
                    assertThat(option.enabled()).isTrue());
        }

        /**
         * Verifies that the admin menu includes user-maintenance
         * options (i.e., that all four {@code COUSR0xC} target programs
         * are present) &mdash; defense-in-depth check against any
         * future refactor accidentally dropping a USRSEC option.
         */
        @Test
        @DisplayName("includes user-maintenance options (COUSR00C..COUSR03C)")
        void getAdminMenu_includesUserMaintenanceOptions() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options())
                    .extracting(MenuOptionDto::targetProgram)
                    .as("Admin menu must include all four user-maintenance "
                            + "programs from COADM02Y.cpy")
                    .containsExactly("COUSR00C", "COUSR01C",
                            "COUSR02C", "COUSR03C");
        }

        /**
         * Verifies that option numbers are sequential 1..4 (matching
         * the COBOL {@code CDEMO-ADMIN-OPT-NUM} sequence
         * {@code 1, 2, 3, 4} in {@code app/cpy/COADM02Y.cpy}).
         */
        @Test
        @DisplayName("option numbers are sequential 1..4")
        void getAdminMenu_optionNumbersAreSequential1to4() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            List<MenuOptionDto> options = result.options();
            for (int i = 0; i < options.size(); i++) {
                assertThat(options.get(i).optionNumber())
                        .as("Option at zero-based index %d must have "
                                + "optionNumber %d", i, i + 1)
                        .isEqualTo(i + 1);
            }
        }

        /**
         * Verifies idempotency &mdash; calling {@link
         * MenuService#getAdminMenu(String)} twice returns structurally
         * equal results.
         */
        @Test
        @DisplayName("returns structurally-equal results across invocations")
        void getAdminMenu_isStableAcrossInvocations() {
            AdminMenuDto first = service.getAdminMenu(USER_TYPE_ADMIN);
            AdminMenuDto second = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(first.options())
                    .containsExactlyElementsOf(second.options());
            assertThat(first.title()).isEqualTo(second.title());
        }

        /**
         * Verifies the page title matches the expected
         * {@value #EXPECTED_ADMIN_MENU_TITLE}.
         */
        @Test
        @DisplayName("title equals 'AWS CardDemo - Admin Menu'")
        void getAdminMenu_titleIsExpectedString() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.title())
                    .as("Admin menu title must match COTTL01Y.cpy-derived literal")
                    .isEqualTo(EXPECTED_ADMIN_MENU_TITLE);
        }

        /**
         * Verifies that user-identity fields are empty &mdash; controller
         * augments from JWT claims before serializing the response.
         * Mirrors the same behavior tested for {@link MainMenuDto}.
         *
         * <p>Note: {@link AdminMenuDto} does <em>not</em> carry a
         * {@code userType} field (unlike {@link MainMenuDto}) because the
         * admin endpoint's implicit user-type is always {@code 'A'}
         * (gated by {@code @PreAuthorize("hasRole('ADMIN')")} at the
         * controller). See AAP &sect;0.3.4 for the design rationale.</p>
         */
        @Test
        @DisplayName("user-identity fields are empty (controller augments from JWT)")
        void getAdminMenu_userIdentityFieldsAreEmpty() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.userId()).isEqualTo("");
            assertThat(result.firstName()).isEqualTo("");
            assertThat(result.lastName()).isEqualTo("");
        }

        /**
         * Verifies that every admin-menu option carries non-null,
         * non-blank label, target program, and target endpoint fields.
         */
        @Test
        @DisplayName("every option has non-blank label, targetProgram, and targetEndpoint")
        void getAdminMenu_everyOptionHasRequiredStringFields() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options()).allSatisfy(option -> {
                assertThat(option.label())
                        .as("Option %d label must not be blank",
                                option.optionNumber())
                        .isNotBlank();
                assertThat(option.targetProgram())
                        .as("Option %d targetProgram must not be blank",
                                option.optionNumber())
                        .isNotBlank();
                assertThat(option.targetEndpoint())
                        .as("Option %d targetEndpoint must not be blank",
                                option.optionNumber())
                        .isNotBlank();
            });
        }

        /**
         * Verifies that every {@code targetProgram} field is 8
         * characters or fewer, matching the COBOL
         * {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} declaration in
         * {@code app/cpy/COADM02Y.cpy} (line 48) verbatim.
         */
        @Test
        @DisplayName("every targetProgram is <= 8 chars (PIC X(08) constraint)")
        void getAdminMenu_everyTargetProgramFitsPicX08() {
            AdminMenuDto result = service.getAdminMenu(USER_TYPE_ADMIN);

            assertThat(result.options()).allSatisfy(option ->
                    assertThat(option.targetProgram().length())
                            .as("Option %d targetProgram '%s' exceeds PIC X(08)",
                                    option.optionNumber(), option.targetProgram())
                            .isLessThanOrEqualTo(8));
        }
    }

    // -----------------------------------------------------------------------
    // ADMIN GATE (defense-in-depth user-type check on getAdminMenu)
    // -----------------------------------------------------------------------

    /**
     * Tests for the defense-in-depth admin-only gate on
     * {@link MenuService#getAdminMenu(String)}.
     *
     * <p>In the COBOL source, admin-only access is enforced
     * <em>indirectly</em> by {@code COSGN00C} (the sign-on program),
     * which inspects {@code SEC-USR-TYPE} from
     * {@code app/cpy/CSUSR01Y.cpy} and routes admin users
     * ({@code SEC-USR-TYPE = 'A'}) to {@code COADM01C}, sending all
     * other users to {@code COMEN01C}. {@code COADM01C} itself therefore
     * does not check the user-type &mdash; the gate is upstream.</p>
     *
     * <p>In the Java target, the gate is implemented in two layers:</p>
     *
     * <ol>
     *   <li>The controller ({@code MenuController.getAdminMenu()}) carries
     *       {@code @PreAuthorize("hasRole('ADMIN')")} so regular users
     *       receive HTTP 403 Forbidden before this service is invoked.</li>
     *   <li>This service redundantly checks the {@code userType}
     *       argument against {@code "A"} and throws
     *       {@link ValidationException} (mapped to HTTP 400) for callers
     *       who reach this point with a non-admin user-type. This
     *       defense-in-depth check protects against future controller
     *       misconfiguration.</li>
     * </ol>
     *
     * <p>All tests in this group verify the layer-2 defense by passing
     * non-admin user-types directly to
     * {@link MenuService#getAdminMenu(String)} and asserting the
     * verbatim COBOL error message
     * {@value #EXPECTED_ADMIN_ONLY_MESSAGE} is surfaced.</p>
     */
    @Nested
    @DisplayName("getAdminMenu(non-admin) \u2014 defense-in-depth admin gate")
    class AdminGate {

        /**
         * Verifies that a regular user attempting to fetch the admin
         * menu receives a {@link ValidationException} carrying the
         * verbatim COBOL error message
         * {@value #EXPECTED_ADMIN_ONLY_MESSAGE}.
         *
         * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} line 140
         * {@code MOVE 'No access - Admin Only option... ' TO WS-MESSAGE}.</p>
         */
        @Test
        @DisplayName("throws ValidationException with verbatim COBOL message "
                + "for regular user")
        void getAdminMenu_forRegularUser_throwsValidationException() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY -- denies admin-only access
            // for CDEMO-USRTYP-USER callers
            assertThatThrownBy(() -> service.getAdminMenu(USER_TYPE_USER))
                    .as("Admin gate must deny 'U' callers")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that a null user-type is treated as a non-admin
         * caller and rejected with the same admin-only error.
         */
        @Test
        @DisplayName("throws ValidationException for null user-type")
        void getAdminMenu_forNullUserType_throwsValidationException() {
            assertThatThrownBy(() -> service.getAdminMenu(null))
                    .as("Admin gate must deny null user-type")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that an empty user-type is treated as a non-admin
         * caller and rejected.
         */
        @Test
        @DisplayName("throws ValidationException for empty user-type")
        void getAdminMenu_forEmptyUserType_throwsValidationException() {
            assertThatThrownBy(() -> service.getAdminMenu(""))
                    .as("Admin gate must deny empty-string user-type")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that an arbitrary, unrecognized user-type code is
         * treated as a non-admin caller and rejected (e.g., a future
         * "X" code or any other non-"A" value).
         */
        @Test
        @DisplayName("throws ValidationException for unrecognized user-type")
        void getAdminMenu_forArbitraryUserType_throwsValidationException() {
            assertThatThrownBy(() -> service.getAdminMenu("X"))
                    .as("Admin gate must deny any user-type other than 'A'")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that the admin gate is case-sensitive: lowercase
         * {@code "a"} is rejected, only uppercase {@code "A"} grants
         * access. Mirrors the COBOL {@code PIC X(01) VALUE 'A'}
         * literal-match semantics (COBOL string compares are
         * character-exact, so {@code 'a'} would not equal {@code 'A'}).
         */
        @Test
        @DisplayName("admin gate is case-sensitive (lowercase 'a' is rejected)")
        void getAdminMenu_isCaseSensitive() {
            assertThatThrownBy(() -> service.getAdminMenu("a"))
                    .as("Admin gate must be case-sensitive ('a' != 'A')")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that the thrown {@link ValidationException} carries
         * the default {@code "VALIDATION"} reason code
         * (per {@link ValidationException#DEFAULT_REASON_CODE}). The
         * reason code is propagated by {@code GlobalExceptionHandler}
         * into the {@code code} property of the standardized JSON
         * error envelope (AAP &sect;0.3.4).
         */
        @Test
        @DisplayName("thrown ValidationException carries reason code 'VALIDATION'")
        void getAdminMenu_thrownExceptionCarriesValidationReasonCode() {
            try {
                service.getAdminMenu(USER_TYPE_USER);
                throw new AssertionError(
                        "Expected ValidationException but none was thrown");
            } catch (ValidationException ex) {
                assertThat(ex.getReasonCode())
                        .as("ValidationException must use the 'VALIDATION' "
                                + "reason code per AAP §0.3.4")
                        .isEqualTo(ValidationException.DEFAULT_REASON_CODE);
            }
        }

        /**
         * Verifies that the thrown {@link ValidationException} carries
         * an empty field-errors list (admin-only denial is a
         * whole-request rejection, not a per-field validation failure).
         */
        @Test
        @DisplayName("thrown ValidationException carries empty field-errors list")
        void getAdminMenu_thrownExceptionHasNoFieldErrors() {
            try {
                service.getAdminMenu(USER_TYPE_USER);
                throw new AssertionError(
                        "Expected ValidationException but none was thrown");
            } catch (ValidationException ex) {
                assertThat(ex.getFieldErrors())
                        .as("Admin-only denial is whole-request, not per-field")
                        .isEmpty();
            }
        }
    }

    // -----------------------------------------------------------------------
    // RESOLVE MENU TARGET (PROCESS-ENTER-KEY four-stage validation cascade)
    // -----------------------------------------------------------------------

    /**
     * Tests for {@link
     * MenuService#resolveMenuTarget(String, String, boolean)} &mdash;
     * the four-stage validation cascade ported from
     * {@code COMEN01C.cbl:PROCESS-ENTER-KEY} (and the structurally
     * identical {@code COADM01C.cbl:PROCESS-ENTER-KEY}):
     *
     * <ol>
     *   <li><b>Numeric check</b> &mdash; reject non-numeric input with
     *       message {@code "Invalid menu option: 0"} (the COBOL
     *       {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}
     *       normalisation does not apply in REST: a non-numeric input
     *       is rejected outright).</li>
     *   <li><b>Bounds check</b> &mdash; reject out-of-range option
     *       numbers with message
     *       {@code "Invalid menu option: <number>"}.</li>
     *   <li><b>Admin-only gate</b> &mdash; reject {@code "U"} callers
     *       attempting an admin-only option with message
     *       {@value #EXPECTED_ADMIN_ONLY_MESSAGE} (only fires when
     *       {@code isAdminMenu=true} on a {@code "U"} caller given
     *       current static data).</li>
     *   <li><b>"Coming soon" gate</b> &mdash; reject options whose
     *       target program starts with {@code "DUMMY"} (no currently-active
     *       entries; logic preserved per AAP &sect;0.7.3 Minimal Change
     *       Clause).</li>
     * </ol>
     */
    @Nested
    @DisplayName("resolveMenuTarget(option, userType, isAdminMenu) \u2014 "
            + "PROCESS-ENTER-KEY validation cascade")
    class ResolveMenuTarget {

        /**
         * Verifies that resolving a valid main-menu option returns the
         * matching {@link MenuOptionDto} with all six fields populated.
         */
        @Test
        @DisplayName("main menu / valid option returns matching MenuOptionDto")
        void resolveMenuTarget_mainMenuValidOption_returnsCorrectDto() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY successful XCTL dispatch
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "1", USER_TYPE_USER, false);

            assertThat(resolved).isNotNull();
            assertThat(resolved.optionNumber()).isEqualTo(1);
            assertThat(resolved.label()).isEqualTo("Account View");
            assertThat(resolved.targetProgram()).isEqualTo("COACTVWC");
            assertThat(resolved.targetEndpoint()).isEqualTo("/api/accounts/{id}");
            assertThat(resolved.userType()).isEqualTo(USER_TYPE_USER);
            assertThat(resolved.enabled()).isTrue();
        }

        /**
         * Verifies resolution of main-menu option 10 (the last entry)
         * &mdash; boundary case validating the upper edge of the
         * bounds check.
         */
        @Test
        @DisplayName("main menu / option 10 (last) resolves to Bill Payment")
        void resolveMenuTarget_mainMenuOption10_returnsBillPayment() {
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "10", USER_TYPE_USER, false);

            assertThat(resolved.optionNumber()).isEqualTo(10);
            assertThat(resolved.targetProgram()).isEqualTo("COBIL00C");
            assertThat(resolved.targetEndpoint()).isEqualTo("/api/billing/pay");
        }

        /**
         * Verifies resolution of main-menu option 5 (a middle entry)
         * &mdash; happy-path validation of an entry deep in the table.
         */
        @Test
        @DisplayName("main menu / option 5 resolves to Credit Card Update")
        void resolveMenuTarget_mainMenuOption5_returnsCreditCardUpdate() {
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "5", USER_TYPE_USER, false);

            assertThat(resolved.optionNumber()).isEqualTo(5);
            assertThat(resolved.targetProgram()).isEqualTo("COCRDUPC");
        }

        /**
         * Verifies resolution of admin-menu option 1 (the first entry)
         * for an admin caller.
         */
        @Test
        @DisplayName("admin menu / option 1 (admin caller) resolves to User List")
        void resolveMenuTarget_adminMenuOption1_returnsUserList() {
            // COBOL: COADM01C.cbl PROCESS-ENTER-KEY successful XCTL dispatch
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "1", USER_TYPE_ADMIN, true);

            assertThat(resolved).isNotNull();
            assertThat(resolved.optionNumber()).isEqualTo(1);
            assertThat(resolved.label()).isEqualTo("User List (Security)");
            assertThat(resolved.targetProgram()).isEqualTo("COUSR00C");
            assertThat(resolved.targetEndpoint()).isEqualTo("/api/admin/users");
            assertThat(resolved.userType()).isEqualTo(USER_TYPE_ADMIN);
        }

        /**
         * Verifies resolution of admin-menu option 4 (the last entry)
         * for an admin caller &mdash; boundary case validating the
         * upper edge of the admin-menu bounds check.
         */
        @Test
        @DisplayName("admin menu / option 4 (last, admin caller) resolves to User Delete")
        void resolveMenuTarget_adminMenuOption4_returnsUserDelete() {
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "4", USER_TYPE_ADMIN, true);

            assertThat(resolved.optionNumber()).isEqualTo(4);
            assertThat(resolved.targetProgram()).isEqualTo("COUSR03C");
            assertThat(resolved.targetEndpoint())
                    .isEqualTo("/api/admin/users/{id}");
        }

        /**
         * Verifies the numeric-check stage: non-numeric input is
         * rejected with a {@link ValidationException} carrying the
         * verbatim "Invalid menu option: 0" message (the COBOL
         * {@code WS-OPTION-X} normalisation does not apply; non-numeric
         * input is treated as out-of-range with the substituted
         * sentinel value 0).
         */
        @Test
        @DisplayName("non-numeric option throws ValidationException 'Invalid menu option: 0'")
        void resolveMenuTarget_nonNumericOption_throwsValidation() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY line 127
            // IF WS-OPTION IS NOT NUMERIC -> MOVE 'Please enter a valid option number...'
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "abc", USER_TYPE_USER, false))
                    .as("Non-numeric input must be rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 0");
        }

        /**
         * Verifies that a {@code null} option is rejected (caught by
         * the {@code NullPointerException} catch in the parse block,
         * producing the same "Invalid menu option: 0" message).
         */
        @Test
        @DisplayName("null option throws ValidationException 'Invalid menu option: 0'")
        void resolveMenuTarget_nullOption_throwsValidation() {
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            null, USER_TYPE_USER, false))
                    .as("Null option must be rejected without NPE")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 0");
        }

        /**
         * Verifies that a blank/whitespace-only option is rejected with
         * the "Invalid menu option: 0" message.
         */
        @Test
        @DisplayName("blank option throws ValidationException 'Invalid menu option: 0'")
        void resolveMenuTarget_blankOption_throwsValidation() {
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "   ", USER_TYPE_USER, false))
                    .as("Blank option must be rejected (Integer.parseInt "
                            + "throws NumberFormatException)")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 0");
        }

        /**
         * Verifies the bounds-check stage: option 0 is rejected (COBOL
         * line 129 {@code IF WS-OPTION = ZEROS}).
         */
        @Test
        @DisplayName("option 0 throws ValidationException 'Invalid menu option: 0'")
        void resolveMenuTarget_zeroOption_throwsValidation() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY line 129
            // IF WS-OPTION = ZEROS -> 'Please enter a valid option number...'
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "0", USER_TYPE_USER, false))
                    .as("Option 0 must be rejected per COBOL line 129")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 0");
        }

        /**
         * Verifies the bounds-check stage: main-menu option 11 is
         * rejected (COBOL line 128 {@code IF WS-OPTION > COUNT}; main
         * menu has count 10).
         */
        @Test
        @DisplayName("main menu / option 11 (above 10) throws ValidationException")
        void resolveMenuTarget_mainMenuOptionAboveSize_throwsValidation() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY line 128
            // IF WS-OPTION > CDEMO-MENU-OPT-COUNT -> 'Please enter a valid option number...'
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "11", USER_TYPE_USER, false))
                    .as("Main menu option > 10 must be rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 11");
        }

        /**
         * Verifies the bounds-check stage on the admin menu: option 5
         * is rejected (admin menu has count 4).
         */
        @Test
        @DisplayName("admin menu / option 5 (above 4) throws ValidationException")
        void resolveMenuTarget_adminMenuOptionAboveSize_throwsValidation() {
            // COBOL: COADM01C.cbl PROCESS-ENTER-KEY line 128
            // IF WS-OPTION > CDEMO-ADMIN-OPT-COUNT -> 'Please enter a valid option number...'
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "5", USER_TYPE_ADMIN, true))
                    .as("Admin menu option > 4 must be rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: 5");
        }

        /**
         * Verifies that a negative option number is rejected with the
         * appropriate verbatim message.
         */
        @Test
        @DisplayName("negative option throws ValidationException")
        void resolveMenuTarget_negativeOption_throwsValidation() {
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "-1", USER_TYPE_USER, false))
                    .as("Negative option must be rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Invalid menu option: -1");
        }

        /**
         * Verifies that the error message includes the offending option
         * number (used by REST clients for debugging).
         */
        @Test
        @DisplayName("error message contains offending option number")
        void resolveMenuTarget_errorMessageContainsOffendingNumber() {
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "99", USER_TYPE_USER, false))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("99");
        }

        /**
         * Verifies that the parser accepts leading zeros in the option
         * string (mirroring the COBOL {@code WS-OPTION-X JUST RIGHT}
         * declaration which right-justifies and zero-pads the input).
         * Java's {@link Integer#parseInt(String)} parses "01" as 1
         * naturally.
         */
        @Test
        @DisplayName("accepts leading zeros (matches COBOL JUST RIGHT padding)")
        void resolveMenuTarget_acceptsLeadingZeros() {
            // COBOL: WS-OPTION-X PIC X(02) JUST RIGHT -> Java parses "01" as 1
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "01", USER_TYPE_USER, false);

            assertThat(resolved.optionNumber()).isEqualTo(1);
            assertThat(resolved.targetProgram()).isEqualTo("COACTVWC");
        }

        /**
         * Verifies the admin-only gate stage: a regular user attempting
         * to resolve a target on the admin menu is rejected with the
         * verbatim {@value #EXPECTED_ADMIN_ONLY_MESSAGE} message.
         *
         * <p>This test exercises the admin-only gate logic at line 606
         * of {@link MenuService}:</p>
         *
         * <pre>{@code
         *   if (USER_TYPE_USER.equals(userType)
         *           && USER_TYPE_ADMIN.equals(selected.userType())) {
         *       throw new ValidationException(ERR_ADMIN_ONLY);
         *   }
         * }</pre>
         *
         * <p>All admin-menu options carry {@code userType="A"}, so a
         * {@code "U"} caller invoking
         * {@code resolveMenuTarget("1", "U", true)} hits the gate
         * exactly as the source COBOL did with
         * {@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'}.</p>
         */
        @Test
        @DisplayName("admin menu / regular user is rejected with admin-only message")
        void resolveMenuTarget_adminMenuRegularUser_throwsAdminOnly() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY line 136-143
            // IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'
            //     MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
            assertThatThrownBy(() -> service.resolveMenuTarget(
                            "1", USER_TYPE_USER, true))
                    .as("Regular user attempting admin option must be rejected")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        /**
         * Verifies that the admin-only gate fires on all four admin
         * options when invoked by a regular user &mdash; defense-in-depth
         * test that the gate is uniformly applied across the entire
         * admin table.
         */
        @Test
        @DisplayName("admin menu / regular user is rejected on every admin option")
        void resolveMenuTarget_adminMenuRegularUser_rejectedOnEveryOption() {
            for (int i = 1; i <= EXPECTED_ADMIN_MENU_OPTION_COUNT; i++) {
                final String optionString = Integer.toString(i);
                assertThatThrownBy(() -> service.resolveMenuTarget(
                                optionString, USER_TYPE_USER, true))
                        .as("Regular user must be rejected on admin option %d", i)
                        .isInstanceOf(ValidationException.class)
                        .hasMessage(EXPECTED_ADMIN_ONLY_MESSAGE);
            }
        }

        /**
         * Verifies the admin-only gate does <em>not</em> fire when an
         * admin caller invokes the same admin option &mdash; only
         * {@code "U"} callers are blocked. Smoke test confirming the
         * predicate {@code USER_TYPE_USER.equals(userType) &&
         * USER_TYPE_ADMIN.equals(selected.userType())} requires
         * <em>both</em> halves to be true.
         */
        @Test
        @DisplayName("admin menu / admin user passes the admin-only gate")
        void resolveMenuTarget_adminMenuAdminUser_passesGate() {
            // For all four admin options, an admin caller must receive
            // the corresponding MenuOptionDto without exception.
            for (int i = 1; i <= EXPECTED_ADMIN_MENU_OPTION_COUNT; i++) {
                MenuOptionDto resolved = service.resolveMenuTarget(
                        Integer.toString(i), USER_TYPE_ADMIN, true);
                assertThat(resolved)
                        .as("Admin caller on admin option %d must resolve "
                                + "without exception", i)
                        .isNotNull();
                assertThat(resolved.optionNumber()).isEqualTo(i);
            }
        }

        /**
         * Verifies that all 10 main-menu options resolve successfully
         * for a regular user (no admin-only gate fires because every
         * main-menu option in {@code COMEN02Y.cpy} is gated by
         * {@code 'U'} per AAP &sect;0.4.1).
         */
        @Test
        @DisplayName("main menu / regular user resolves every option 1..10")
        void resolveMenuTarget_mainMenuRegularUser_resolvesEveryOption() {
            for (int i = 1; i <= EXPECTED_MAIN_MENU_OPTION_COUNT; i++) {
                MenuOptionDto resolved = service.resolveMenuTarget(
                        Integer.toString(i), USER_TYPE_USER, false);
                assertThat(resolved)
                        .as("Main menu option %d must resolve for regular user", i)
                        .isNotNull();
                assertThat(resolved.optionNumber()).isEqualTo(i);
                assertThat(resolved.userType()).isEqualTo(USER_TYPE_USER);
            }
        }

        /**
         * Verifies that the thrown {@link ValidationException} carries
         * the default {@code "VALIDATION"} reason code on the invalid-
         * option path (parallels the same check on the admin-only path
         * in {@link AdminGate#getAdminMenu_thrownExceptionCarriesValidationReasonCode}).
         */
        @Test
        @DisplayName("invalid option exception carries reason code 'VALIDATION'")
        void resolveMenuTarget_invalidOptionException_carriesValidationReasonCode() {
            try {
                service.resolveMenuTarget("99", USER_TYPE_USER, false);
                throw new AssertionError(
                        "Expected ValidationException but none was thrown");
            } catch (ValidationException ex) {
                assertThat(ex.getReasonCode())
                        .isEqualTo(ValidationException.DEFAULT_REASON_CODE);
                assertThat(ex.getFieldErrors()).isEmpty();
            }
        }

        /**
         * Verifies that the COBOL "coming soon" gate is preserved by
         * confirming that none of the currently-active main-menu
         * options trigger the gate (none of the targetProgram values
         * in {@code COMEN02Y.cpy} start with {@code "DUMMY"}). Acts as
         * a guardrail against future regressions where a typo or
         * placeholder accidentally introduces a {@code "DUMMY"} prefix.
         *
         * <p>COBOL: {@code COMEN01C.cbl:PROCESS-ENTER-KEY} line 146
         * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}.</p>
         */
        @Test
        @DisplayName("main menu / no targetProgram starts with 'DUMMY' (coming-soon guard)")
        void resolveMenuTarget_mainMenu_noDummyPrefixedEntries() {
            // COBOL: COMEN01C.cbl PROCESS-ENTER-KEY line 146
            // IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
            MainMenuDto menu = service.getMainMenu(USER_TYPE_USER);
            assertThat(menu.options())
                    .as("No main-menu option should be DUMMY-prefixed "
                            + "(coming-soon guard)")
                    .allSatisfy(option -> assertThat(option.targetProgram())
                            .doesNotStartWith("DUMMY"));
        }

        /**
         * Same as above, but applied to the admin menu.
         */
        @Test
        @DisplayName("admin menu / no targetProgram starts with 'DUMMY' (coming-soon guard)")
        void resolveMenuTarget_adminMenu_noDummyPrefixedEntries() {
            AdminMenuDto menu = service.getAdminMenu(USER_TYPE_ADMIN);
            assertThat(menu.options())
                    .as("No admin-menu option should be DUMMY-prefixed "
                            + "(coming-soon guard)")
                    .allSatisfy(option -> assertThat(option.targetProgram())
                            .doesNotStartWith("DUMMY"));
        }

        /**
         * Verifies that the returned {@link MenuOptionDto} on a
         * successful resolve carries all six fields populated (no
         * {@code null} components). Defense-in-depth check that the
         * resolution path does not accidentally substitute placeholder
         * values.
         */
        @Test
        @DisplayName("resolved MenuOptionDto has all six fields populated")
        void resolveMenuTarget_returnedDtoHasAllFieldsPopulated() {
            MenuOptionDto resolved = service.resolveMenuTarget(
                    "3", USER_TYPE_USER, false);

            assertThat(resolved.optionNumber()).isPositive();
            assertThat(resolved.label()).isNotBlank();
            assertThat(resolved.targetProgram()).isNotBlank();
            assertThat(resolved.targetEndpoint()).isNotBlank();
            assertThat(resolved.userType()).isNotBlank();
            // 'enabled' is a boolean; default is true on all entries
            assertThat(resolved.enabled()).isTrue();
        }

        /**
         * Verifies that resolving the same option twice returns
         * structurally equal results (records implement structural
         * equality via the canonical {@code equals()} method).
         */
        @Test
        @DisplayName("repeated resolve returns equal results (record equality)")
        void resolveMenuTarget_repeatedResolve_returnsEqualResults() {
            MenuOptionDto first = service.resolveMenuTarget(
                    "1", USER_TYPE_USER, false);
            MenuOptionDto second = service.resolveMenuTarget(
                    "1", USER_TYPE_USER, false);

            assertThat(first)
                    .as("Records must satisfy structural equality on "
                            + "repeated resolves")
                    .isEqualTo(second);
        }
    }
}
