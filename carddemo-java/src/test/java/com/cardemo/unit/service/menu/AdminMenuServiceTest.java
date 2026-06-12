package com.cardemo.unit.service.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fast, mocked pure-JVM unit test for {@link AdminMenuService}, the Spring
 * {@code @Service} that reproduces the legacy AWS CardDemo administrator
 * <strong>Admin Menu</strong> CICS program {@code COADM01C} (transaction
 * {@code CA00}) and its 4-option routing table from copybook
 * {@code app/cpy/COADM02Y.cpy}.
 *
 * <h2>Test strategy</h2>
 * <p>{@link AdminMenuService} is dependency-free: it owns only compile-time-known
 * routing metadata plus the {@code PROCESS-ENTER-KEY} selection logic, and it
 * collaborates with no repository, entity or external resource. These are
 * therefore millisecond, isolated unit tests &mdash; there is <em>no</em> Spring
 * context, database, Testcontainers or AWS. The class is wired with
 * {@link MockitoExtension} and the system under test is supplied via
 * {@link InjectMocks}; because the service has no collaborators, Mockito simply
 * constructs it through its implicit no-arg constructor (no {@code @Mock} fields
 * are declared, so no stubbing and no strict-stub concerns arise).</p>
 *
 * <h2>The admin twin &mdash; three faithful differences from the main menu</h2>
 * <p>This is the administrator counterpart of {@code MainMenuServiceTest}. Its
 * whole value is locking in the three behavioural differences that the COBOL
 * {@code COADM01C} / {@code COADM02Y} mandate relative to the regular-user
 * {@code COMEN01C} / {@code COMEN02Y}:</p>
 * <ol>
 *   <li><strong>Exactly 4 options</strong> ({@code COUSR00C}..{@code COUSR03C},
 *       the "(Security)" user-administration screens), not 10. Pinned by
 *       {@link #getOptionCount_returnsFour()} and
 *       {@link #getMenuOptions_returnsAllOptionsInExactOrder()}.</li>
 *   <li><strong>No per-option user-type field and no user-type gate.</strong> The
 *       {@code COADM02Y} option record carries no {@code usrtype} byte (only
 *       {@code NUM}/{@code NAME}/{@code PGMNAME}), and {@code COADM01C}
 *       {@code PROCESS-ENTER-KEY} contains <em>zero</em> user-type checks (its
 *       L136 is blank where {@code COMEN01C} has its admin-only gate). The
 *       menu-level ADMIN restriction is enforced upstream at sign-on
 *       ({@code COSGN00C}) and at the controller / security boundary, never
 *       inside {@code selectOption}. This is asserted directly and explicitly by
 *       {@link #selectOption_validOption_withUserTypeCommArea_stillNavigates()}:
 *       a {@code USER}-typed context still navigates.</li>
 *   <li><strong>Name-less "coming soon" message.</strong> The COBOL
 *       {@code STRING} that builds the placeholder text has its
 *       {@code CDEMO-ADMIN-OPT-NAME} operand commented out, yielding exactly
 *       {@code "This option is coming soon ..."} with no option name (the
 *       regular-user menu includes the name). That branch is unreachable for the
 *       shipped data, so it is characterized via
 *       {@link #getMenuOptions_noOptionTargetsDummyProgram()} and documented,
 *       never hard-asserted.</li>
 * </ol>
 *
 * <h2>Behavioural parity (AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <p>Every assertion encodes COBOL-identical behaviour with no added strictness
 * and no relaxation. The behaviour verified here is translated from the frozen
 * COBOL baseline at commit SHA {@code 27d6c6f}; the COBOL source is read-only
 * reference and is <strong>never copied</strong> into this repository &mdash;
 * only its observable contract is asserted. The high-value, fully-deterministic
 * checks are: the exact ordered 4-option table (no user-type column),
 * {@link AdminMenuService#getOptionCount()}{@code  == 4} (the {@code COADM02Y}
 * {@code OCCURS 9 TIMES} array capacity is mere capacity, not the option count),
 * navigation to the correct program with the COMMAREA &rarr; {@link CommArea}
 * hand-off ({@code CA00} / {@code COADM01C} / context {@code 0} / target program,
 * {@code COADM01C} L139&ndash;145), and the exact verbatim validation message on
 * bad input ({@code COADM01C} L131).</p>
 *
 * <h2>Reachability of the COBOL branches</h2>
 * <p>With the shipped 4-option table, {@link AdminMenuService#selectOption} has
 * only two outcomes reachable through the public API &mdash; a
 * <em>validation error</em> and a <em>navigation</em>. Unlike the main menu there
 * is no user-type gate branch at all; the only other {@code PROCESS-ENTER-KEY}
 * branch (the DUMMY "coming soon" placeholder) cannot be exercised through the
 * public API (and reflection is forbidden), so it is characterized indirectly via
 * the option-table invariant {@link #getMenuOptions_noOptionTargetsDummyProgram()}
 * rather than hard-asserted.</p>
 *
 * @see AdminMenuService
 * @see com.cardemo.model.dto.CommArea
 * @see com.cardemo.model.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMenuService — COBOL COADM01C / COADM02Y (tran CA00) parity")
class AdminMenuServiceTest {

    /**
     * System under test. The service has no collaborators, so {@link InjectMocks}
     * instantiates it via its implicit no-arg constructor; no {@code @Mock}
     * fields are declared.
     */
    @InjectMocks
    private AdminMenuService service;

    // ------------------------------------------------------------------
    // Option table — getOptionCount() / getMenuOptions()
    // COBOL: app/cpy/COADM02Y.cpy CARDDEMO-ADMIN-MENU-OPTIONS.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOptionCount() == 4 (CDEMO-ADMIN-OPT-COUNT VALUE 4; OCCURS 9 is capacity, not count)")
    void getOptionCount_returnsFour() {
        // COBOL COADM02Y L20: 05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4. The
        // REDEFINES table's OCCURS 9 TIMES (L45) is array capacity only; slots
        // 5..9 carry no data and are not selectable options.
        assertThat(service.getOptionCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("getMenuOptions() returns exactly 4 options")
    void getMenuOptions_returnsExactlyFourOptions() {
        var options = service.getMenuOptions();
        assertThat(options).hasSize(4);
    }

    @Test
    @DisplayName("getMenuOptions() returns all 4 COADM02Y options in exact order with exact metadata")
    void getMenuOptions_returnsAllOptionsInExactOrder() {
        var options = service.getMenuOptions();
        // Implementation-sensitive: number, trimmed name and target program must
        // all match the COADM02Y table verbatim, in option-number order, with
        // nothing added, dropped or reordered. There is deliberately NO user-type
        // column: the admin option record (CDEMO-ADMIN-OPT-NUM / -NAME / -PGMNAME)
        // carries no usrtype byte, unlike the regular-user COMEN02Y. Lambda
        // extractors (not method references) keep the inferred element type the
        // nested record without importing it; containsExactly enforces both
        // content and order. The "(Security)" parenthetical is part of the
        // canonical COBOL name text and is retained verbatim.
        assertThat(options)
                .extracting(o -> o.number(), o -> o.name().trim(), o -> o.targetProgram().trim())
                .containsExactly(
                        tuple(1, "User List (Security)", "COUSR00C"),
                        tuple(2, "User Add (Security)", "COUSR01C"),
                        tuple(3, "User Update (Security)", "COUSR02C"),
                        tuple(4, "User Delete (Security)", "COUSR03C"));
    }

    @Test
    @DisplayName("no option targets a DUMMY* program, so the COBOL 'coming soon' branch is dormant (unreachable)")
    void getMenuOptions_noOptionTargetsDummyProgram() {
        var options = service.getMenuOptions();
        // Characterizes the UNREACHABLE DUMMY placeholder branch of COADM01C
        // PROCESS-ENTER-KEY (L148-153): IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5)
        // NOT = 'DUMMY' ... else build the green placeholder message. Crucially the
        // admin STRING has its CDEMO-ADMIN-OPT-NAME operand COMMENTED OUT, so the
        // verbatim text is exactly "This option is coming soon ..." with NO option
        // name (the regular-user menu includes the name). No shipped COADM02Y option
        // targets a DUMMY* program, so this branch is never taken and its message is
        // intentionally NOT hard-asserted (and never produced via reflection).
        assertThat(options).noneMatch(o -> o.targetProgram().trim().startsWith("DUMMY"));
    }

    // ------------------------------------------------------------------
    // selectOption() — navigation (reachable). COBOL: COADM01C PROCESS-ENTER-KEY
    // navigate branch (L137-145) + COMMAREA hand-off (L139-145).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"1,COUSR00C", "2,COUSR01C", "3,COUSR02C", "4,COUSR03C"})
    @DisplayName("selectOption(): each valid option navigates and stamps the COMMAREA hand-off (XCTL replacement)")
    void selectOption_validOption_navigatesAndUpdatesCommArea(String input, String expectedProgram) {
        // Fresh context per run (no shared mutable state across parameterized runs).
        CommArea commArea = new CommArea();
        commArea.setUserType(UserType.ADMIN);  // admin context (the menu is admin-only upstream)
        commArea.setProgramContext(1);         // non-zero so we can prove the service resets it to 0

        // Capture with var: never name or construct MenuSelectionResult (it carries
        // an extra severity component); only read its three documented accessors
        // navigate()/targetProgram()/message().
        var result = service.selectOption(input, commArea);

        assertThat(result.navigate()).isTrue();
        assertThat(result.targetProgram().trim()).isEqualTo(expectedProgram);
        assertThat(result.message()).isNull();

        // COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID, WS-PGMNAME -> CDEMO-FROM-PROGRAM,
        // ZEROS -> CDEMO-PGM-CONTEXT, then EXEC CICS XCTL PROGRAM(target). The
        // in-process XCTL transfer + COMMAREA threading is replaced by mutating the
        // propagated CommArea context and returning the target (COADM01C L139-145).
        assertThat(commArea.getFromTranId()).isEqualTo("CA00");
        assertThat(commArea.getFromProgram()).isEqualTo("COADM01C");
        assertThat(commArea.getProgramContext()).isEqualTo(0);
        assertThat(commArea.getToProgram().trim()).isEqualTo(expectedProgram);
    }

    @Test
    @DisplayName("selectOption(): a USER-typed context still navigates — COADM01C has NO per-option user-type gate")
    void selectOption_validOption_withUserTypeCommArea_stillNavigates() {
        // THE defining difference from the main menu. COMEN01C PROCESS-ENTER-KEY has
        // an admin-only gate (IF CDEMO-USRTYP-USER AND option usrtype = 'A' -> "No
        // access..."); COADM01C has NONE — its L136 is blank and the paragraph
        // contains zero CDEMO-USRTYP checks. The whole Admin Menu is ADMIN-only, but
        // that restriction is enforced upstream (sign-on / SecurityConfig), NOT
        // inside selectOption. So a USER-typed CommArea must NOT be blocked here: it
        // navigates exactly as an ADMIN context would. Asserting this pins the
        // absence of the per-option gate.
        CommArea commArea = new CommArea();
        commArea.setUserType(UserType.USER);

        var result = service.selectOption("1", commArea);

        assertThat(result.navigate()).isTrue();
        assertThat(result.targetProgram().trim()).isEqualTo("COUSR00C");
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("service constants mirror COADM01C working storage (WS-TRANID 'CA00' / WS-PGMNAME 'COADM01C')")
    void selectOption_usesServiceConstants() {
        // COBOL COADM01C working storage: WS-PGMNAME VALUE 'COADM01C',
        // WS-TRANID VALUE 'CA00' — surfaced as the public service constants.
        assertThat(AdminMenuService.TRANSACTION_ID).isEqualTo("CA00");
        assertThat(AdminMenuService.PROGRAM_NAME).isEqualTo("COADM01C");
    }

    // ------------------------------------------------------------------
    // selectOption() — validation error (reachable). COBOL: COADM01C
    // PROCESS-ENTER-KEY validation (L127-131).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "00", "5", "9", "99", "abc", "xyz"})
    @DisplayName("selectOption(): invalid / out-of-range / non-numeric input yields the verbatim COBOL error and does not navigate")
    void selectOption_invalidOption_returnsValidationError(String input) {
        // Chosen inputs are unambiguously invalid under any faithful PROCESS-ENTER-KEY
        // implementation (COADM01C L127-129: IS NOT NUMERIC OR > CDEMO-ADMIN-OPT-COUNT
        // OR = ZEROS):
        //   null, "", " " -> COBOL right-justifies + INSPECT REPLACING ' ' BY '0' -> "00" -> 0 (= ZEROS, invalid);
        //   "0", "00"      -> zero (= ZEROS, invalid);
        //   "5"            -> > count 4 (count + 1, invalid);
        //   "9"            -> at the OCCURS 9 array capacity but > count 4 -> proves the
        //                     capacity is NOT the real option count (slots 5..9 unselectable);
        //   "99"           -> far out of range (invalid);
        //   "abc", "xyz"   -> non-numeric (IS NOT NUMERIC, invalid).
        // (Negative / embedded-digit inputs like "-1"/"1a"/"1.5" are deliberately
        // omitted as parse-implementation dependent.)
        CommArea commArea = new CommArea();
        commArea.setUserType(UserType.ADMIN);

        var result = service.selectOption(input, commArea);

        assertThat(result.navigate()).isFalse();
        // Verbatim COBOL text (COADM01C L131): MOVE 'Please enter a valid option
        // number...' TO WS-MESSAGE — three trailing dots, no trailing space.
        assertThat(result.message()).isEqualTo("Please enter a valid option number...");
        assertThat(result.targetProgram()).isNull();
        // The validation early-return precedes any COMMAREA mutation (COBOL paragraph
        // fall-through eliminated per AAP §0.7.4), so the context is left untouched.
        assertThat(commArea.getToProgram()).isNull();
    }
}
