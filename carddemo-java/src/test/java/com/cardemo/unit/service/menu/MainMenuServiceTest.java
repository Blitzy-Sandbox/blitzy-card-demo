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
import com.cardemo.service.menu.MainMenuService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fast, mocked pure-JVM unit test for {@link MainMenuService}, the Spring
 * {@code @Service} that reproduces the legacy AWS CardDemo regular-user
 * <strong>Main Menu</strong> CICS program {@code COMEN01C} (transaction
 * {@code CM00}) and its 10-option routing table from copybook
 * {@code app/cpy/COMEN02Y.cpy}.
 *
 * <h2>Test strategy</h2>
 * <p>{@link MainMenuService} is dependency-free: it owns only compile-time-known
 * routing metadata plus the {@code PROCESS-ENTER-KEY} selection logic, and it
 * collaborates with no repository, entity or external resource. These are
 * therefore millisecond, isolated unit tests &mdash; there is <em>no</em> Spring
 * context, database, Testcontainers or AWS. The class is wired with
 * {@link MockitoExtension} and the system under test is supplied via
 * {@link InjectMocks}; because the service has no collaborators, Mockito simply
 * constructs it through its implicit no-arg constructor (no {@code @Mock} fields
 * are declared, so no stubbing and no strict-stub concerns arise).</p>
 *
 * <h2>Behavioural parity (AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <p>Every assertion encodes COBOL-identical behaviour with no added strictness
 * and no relaxation. The behaviour verified here is translated from the frozen
 * COBOL baseline at commit SHA {@code 27d6c6f}; the COBOL source is read-only
 * reference and is <strong>never copied</strong> into this repository &mdash;
 * only its observable contract is asserted. The high-value, fully-deterministic
 * checks are: the exact ordered 10-option table (all user-type {@code 'U'}),
 * {@link MainMenuService#getOptionCount()}{@code  == 10} (the
 * {@code COMEN02Y} {@code OCCURS 12 TIMES} array capacity is mere capacity, not
 * the option count), navigation to the correct program with the COMMAREA &rarr;
 * {@link CommArea} hand-off ({@code CM00} / {@code COMEN01C} / context {@code 0}
 * / target program, {@code COMEN01C} L147&ndash;155), and the exact verbatim
 * validation message on bad input ({@code COMEN01C} L131).</p>
 *
 * <h2>Reachability of the COBOL branches</h2>
 * <p>With the shipped 10-option table, {@link MainMenuService#selectOption} has
 * only two outcomes reachable through the public API &mdash; a
 * <em>validation error</em> and a <em>navigation</em>. The other two
 * {@code PROCESS-ENTER-KEY} branches cannot be exercised through the public API
 * (and reflection is forbidden), so they are characterized indirectly via the
 * option-table invariants below rather than hard-asserted:</p>
 * <ul>
 *   <li>The <strong>user-type gate</strong> ({@code COMEN01C} L137&ndash;140,
 *       message {@code "No access - Admin Only option... "}) fires only when a
 *       {@code USER} selects an {@code ADMIN}-only option; every shipped option
 *       is {@code UserType.USER}, so it is dormant &mdash; pinned by
 *       {@link #getMenuOptions_allOptionsAreUserType()}.</li>
 *   <li>The <strong>DUMMY "coming soon"</strong> branch ({@code COMEN01C}
 *       L146/L162, modernized message {@code "This option <name> is coming soon
 *       ..."}) fires only for a placeholder target program prefixed
 *       {@code "DUMMY"}; no shipped option targets one, so it is dormant &mdash;
 *       pinned by {@link #getMenuOptions_noOptionTargetsDummyProgram()}.</li>
 * </ul>
 *
 * @see MainMenuService
 * @see com.cardemo.model.dto.CommArea
 * @see com.cardemo.model.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MainMenuService — COBOL COMEN01C / COMEN02Y (tran CM00) parity")
class MainMenuServiceTest {

    /**
     * System under test. The service has no collaborators, so {@link InjectMocks}
     * instantiates it via its implicit no-arg constructor; no {@code @Mock}
     * fields are declared.
     */
    @InjectMocks
    private MainMenuService service;

    // ------------------------------------------------------------------
    // Option table — getOptionCount() / getMenuOptions()
    // COBOL: app/cpy/COMEN02Y.cpy CARDDEMO-MAIN-MENU-OPTIONS.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOptionCount() == 10 (CDEMO-MENU-OPT-COUNT VALUE 10; OCCURS 12 is capacity, not count)")
    void getOptionCount_returnsTen() {
        // COBOL COMEN02Y L21: 05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10. The
        // REDEFINES table's OCCURS 12 TIMES (L88) is array capacity only; slots
        // 11 and 12 carry no data and are not selectable options.
        assertThat(service.getOptionCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("getMenuOptions() returns exactly 10 options")
    void getMenuOptions_returnsExactlyTenOptions() {
        var options = service.getMenuOptions();
        assertThat(options).hasSize(10);
    }

    @Test
    @DisplayName("getMenuOptions() returns all 10 COMEN02Y options in exact order with exact metadata")
    void getMenuOptions_returnsAllOptionsInExactOrder() {
        var options = service.getMenuOptions();
        // Implementation-sensitive: number, trimmed name, target program AND
        // allowed user type must all match the COMEN02Y table verbatim, in
        // option-number order, with nothing added, dropped or reordered.
        // Lambda extractors (not method references) keep the inferred element
        // type the nested record without importing it; containsExactly enforces
        // both content and order.
        assertThat(options)
                .extracting(o -> o.number(), o -> o.name().trim(), o -> o.targetProgram().trim(),
                        o -> o.allowedUserType())
                .containsExactly(
                        tuple(1, "Account View", "COACTVWC", UserType.USER),
                        tuple(2, "Account Update", "COACTUPC", UserType.USER),
                        tuple(3, "Credit Card List", "COCRDLIC", UserType.USER),
                        tuple(4, "Credit Card View", "COCRDSLC", UserType.USER),
                        tuple(5, "Credit Card Update", "COCRDUPC", UserType.USER),
                        tuple(6, "Transaction List", "COTRN00C", UserType.USER),
                        tuple(7, "Transaction View", "COTRN01C", UserType.USER),
                        // Option 8: COMEN02Y L69 has the '(Admin Only)' name commented
                        // out; the ACTIVE value is plain "Transaction Add", usrtype 'U'.
                        tuple(8, "Transaction Add", "COTRN02C", UserType.USER),
                        tuple(9, "Transaction Reports", "CORPT00C", UserType.USER),
                        tuple(10, "Bill Payment", "COBIL00C", UserType.USER));
    }

    @Test
    @DisplayName("every option is user-type USER, so the COBOL admin-only gate is dormant (unreachable)")
    void getMenuOptions_allOptionsAreUserType() {
        var options = service.getMenuOptions();
        // Characterizes the UNREACHABLE user-type gate of COMEN01C PROCESS-ENTER-KEY
        // (L137-140): IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        // -> "No access - Admin Only option... " (trailing space). Every shipped
        // COMEN02Y option carries usrtype 'U', so a USER can never select an
        // ADMIN-only option through the public API; the gate's message is therefore
        // never emitted and is intentionally NOT hard-asserted.
        assertThat(options).allMatch(o -> o.allowedUserType() == UserType.USER);
    }

    @Test
    @DisplayName("no option targets a DUMMY* program, so the COBOL 'coming soon' branch is dormant (unreachable)")
    void getMenuOptions_noOptionTargetsDummyProgram() {
        var options = service.getMenuOptions();
        // Characterizes the UNREACHABLE DUMMY placeholder branch of COMEN01C
        // PROCESS-ENTER-KEY (L146/L162): IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5)
        // NOT = 'DUMMY' ... else build the green "This option <name> is coming soon
        // ..." message. No shipped COMEN02Y option targets a DUMMY* program, so this
        // branch is never taken and its message is intentionally NOT hard-asserted.
        assertThat(options).noneMatch(o -> o.targetProgram().trim().startsWith("DUMMY"));
    }

    // ------------------------------------------------------------------
    // selectOption() — navigation (reachable). COBOL: COMEN01C PROCESS-ENTER-KEY
    // navigate branch (L146-176) + COMMAREA hand-off (L147-155).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "1,COACTVWC", "2,COACTUPC", "3,COCRDLIC", "4,COCRDSLC", "5,COCRDUPC",
            "6,COTRN00C", "7,COTRN01C", "8,COTRN02C", "9,CORPT00C", "10,COBIL00C"
    })
    @DisplayName("selectOption(): each valid option navigates and stamps the COMMAREA hand-off (XCTL replacement)")
    void selectOption_validOption_navigatesAndUpdatesCommArea(String input, String expectedProgram) {
        // Fresh context per run (no shared mutable state across parameterized runs).
        CommArea commArea = new CommArea();
        commArea.setUserType(UserType.USER);
        commArea.setProgramContext(1); // non-zero so we can prove the service resets it to 0

        // Capture with var: never name or construct MenuSelectionResult; only read
        // its three documented accessors navigate()/targetProgram()/message().
        var result = service.selectOption(input, commArea);

        assertThat(result.navigate()).isTrue();
        assertThat(result.targetProgram().trim()).isEqualTo(expectedProgram);
        assertThat(result.message()).isNull();

        // COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID, WS-PGMNAME -> CDEMO-FROM-PROGRAM,
        // ZEROS -> CDEMO-PGM-CONTEXT, then EXEC CICS XCTL PROGRAM(target). The
        // in-process XCTL transfer + COMMAREA threading is replaced by mutating the
        // propagated CommArea context and returning the target (COMEN01C L147-155).
        assertThat(commArea.getFromTranId()).isEqualTo("CM00");
        assertThat(commArea.getFromProgram()).isEqualTo("COMEN01C");
        assertThat(commArea.getProgramContext()).isEqualTo(0);
        assertThat(commArea.getToProgram().trim()).isEqualTo(expectedProgram);
    }

    @Test
    @DisplayName("service constants mirror COMEN01C working storage (WS-TRANID 'CM00' / WS-PGMNAME 'COMEN01C')")
    void selectOption_usesServiceConstants() {
        // COBOL COMEN01C L36-37: WS-PGMNAME VALUE 'COMEN01C', WS-TRANID VALUE 'CM00'.
        assertThat(MainMenuService.TRANSACTION_ID).isEqualTo("CM00");
        assertThat(MainMenuService.PROGRAM_NAME).isEqualTo("COMEN01C");
    }

    // ------------------------------------------------------------------
    // selectOption() — validation error (reachable). COBOL: COMEN01C
    // PROCESS-ENTER-KEY validation (L127-131).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "00", "11", "12", "99", "abc", "xyz"})
    @DisplayName("selectOption(): invalid / out-of-range / non-numeric input yields the verbatim COBOL error and does not navigate")
    void selectOption_invalidOption_returnsValidationError(String input) {
        // Chosen inputs are unambiguously invalid under any faithful PROCESS-ENTER-KEY
        // implementation (COMEN01C L127-129: IS NOT NUMERIC OR > CDEMO-MENU-OPT-COUNT
        // OR = ZEROS):
        //   null, "", " " -> COBOL right-justifies + INSPECT REPLACING ' ' BY '0' -> "00" -> 0 (= ZEROS, invalid);
        //   "0", "00"      -> zero (= ZEROS, invalid);
        //   "11", "12"     -> > count 10 (also proves the OCCURS 12 capacity is NOT the option count);
        //   "99"           -> far out of range (invalid);
        //   "abc", "xyz"   -> non-numeric (IS NOT NUMERIC, invalid).
        // (Negative / embedded-digit inputs like "-1"/"1a"/"1.5" are deliberately
        // omitted as parse-implementation dependent.)
        CommArea commArea = new CommArea();
        commArea.setUserType(UserType.USER);

        var result = service.selectOption(input, commArea);

        assertThat(result.navigate()).isFalse();
        // Verbatim COBOL text (COMEN01C L131): MOVE 'Please enter a valid option
        // number...' TO WS-MESSAGE — three trailing dots, no trailing space.
        assertThat(result.message()).isEqualTo("Please enter a valid option number...");
        assertThat(result.targetProgram()).isNull();
        // The validation early-return precedes any COMMAREA mutation (COBOL paragraph
        // fall-through eliminated per AAP §0.7.4), so the context is left untouched.
        assertThat(commArea.getToProgram()).isNull();
    }
}
