/*
 * AdminMenuServiceTest.java — Unit Tests for Admin Menu Routing Metadata Service
 *
 * Validates the Java migration of the COBOL admin menu option table from:
 *   - app/cbl/COADM01C.cbl (Admin Menu CICS program — option validation, routing)
 *   - app/cpy/COADM02Y.cpy (Compile-time VALUE table — 4 admin options)
 *   - app/cpy/COCOM01Y.cpy (COMMAREA — CDEMO-USRTYP-ADMIN routing context)
 *
 * These are pure unit tests with no mocks and no Spring context loading.
 * AdminMenuService is a stateless metadata provider with static data initialized
 * at class load time, mirroring the COBOL compile-time VALUE table semantics.
 *
 * Test coverage ensures:
 *   - Exact 4-option count matching CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4
 *   - Option names match COADM02Y.cpy VALUE literals (trimmed of trailing spaces)
 *   - COBOL program mappings match CDEMO-ADMIN-OPT-PGMNAME entries
 *   - Range validation preserves PROCESS-ENTER-KEY error-checking semantics
 *   - Admin-only access flag preserves COSGN00C routing restriction
 *   - List immutability preserves compile-time VALUE table read-only semantics
 *
 * Source repository commit SHA: 27d6c6f
 */
package com.cardemo.unit.service;

import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.AdminMenuService.AdminMenuOption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AdminMenuService} — verifies the 4-option admin menu
 * routing metadata migrated from {@code COADM01C.cbl} and {@code COADM02Y.cpy}.
 *
 * <p>No mocks are needed because {@code AdminMenuService} is a pure static data
 * service with no repository or external service dependencies. Each test instantiates
 * a fresh service instance via {@link #setUp()} to ensure isolation.</p>
 *
 * <p>Test naming follows the maven-surefire-plugin {@code *Test.java} convention.</p>
 */
class AdminMenuServiceTest {

    /**
     * The service under test — instantiated fresh before each test method.
     */
    private AdminMenuService adminMenuService;

    /**
     * Creates a fresh {@code AdminMenuService} instance before each test.
     * No dependency injection needed — pure static data service.
     */
    @BeforeEach
    void setUp() {
        adminMenuService = new AdminMenuService();
    }

    /**
     * Verifies that {@code getAdminMenuOptions()} returns exactly 4 items,
     * matching {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} from
     * {@code COADM02Y.cpy} line 20. Also cross-checks via {@code getOptionCount()}.
     */
    @Test
    void testGetAdminMenuOptions_returnsExactly4Options() {
        List<AdminMenuOption> options = adminMenuService.getAdminMenuOptions();

        assertThat(options).hasSize(4);
        assertThat(adminMenuService.getOptionCount()).isEqualTo(4);
    }

    /**
     * Verifies that each option name matches the exact VALUE literals from
     * {@code COADM02Y.cpy} lines 25-41 (trimmed of trailing COBOL spaces
     * from the PIC X(35) fields):
     * <ol>
     *   <li>"User List (Security)"</li>
     *   <li>"User Add (Security)"</li>
     *   <li>"User Update (Security)"</li>
     *   <li>"User Delete (Security)"</li>
     * </ol>
     */
    @Test
    void testGetAdminMenuOptions_correctOptionNames() {
        List<AdminMenuOption> options = adminMenuService.getAdminMenuOptions();

        assertThat(options.get(0).optionName()).isEqualTo("User List (Security)");
        assertThat(options.get(1).optionName()).isEqualTo("User Add (Security)");
        assertThat(options.get(2).optionName()).isEqualTo("User Update (Security)");
        assertThat(options.get(3).optionName()).isEqualTo("User Delete (Security)");
    }

    /**
     * Verifies that each option's COBOL program name matches the
     * {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} entries from {@code COADM02Y.cpy}:
     * COUSR00C, COUSR01C, COUSR02C, COUSR03C — the user management programs
     * targeted by CICS XCTL in {@code COADM01C.cbl PROCESS-ENTER-KEY} (line 143).
     */
    @Test
    void testGetAdminMenuOptions_correctPrograms() {
        List<AdminMenuOption> options = adminMenuService.getAdminMenuOptions();

        assertThat(options.get(0).cobolProgram()).isEqualTo("COUSR00C");
        assertThat(options.get(1).cobolProgram()).isEqualTo("COUSR01C");
        assertThat(options.get(2).cobolProgram()).isEqualTo("COUSR02C");
        assertThat(options.get(3).cobolProgram()).isEqualTo("COUSR03C");
    }

    /**
     * Verifies that {@code getAdminMenuOption(int)} returns the correct option
     * for valid 1-based option numbers, mirroring the COBOL array access pattern
     * {@code CDEMO-ADMIN-OPT(WS-OPTION)} in {@code COADM01C.cbl PROCESS-ENTER-KEY}.
     *
     * <p>Tests boundary valid values: option 1 (first) and option 4 (last).</p>
     */
    @Test
    void testGetAdminMenuOption_validOption() {
        // First option — "User List (Security)" / COUSR00C
        AdminMenuOption option1 = adminMenuService.getAdminMenuOption(1);
        assertThat(option1.optionNumber()).isEqualTo(1);
        assertThat(option1.optionName()).isEqualTo("User List (Security)");
        assertThat(option1.cobolProgram()).isEqualTo("COUSR00C");

        // Last option — "User Delete (Security)" / COUSR03C
        AdminMenuOption option4 = adminMenuService.getAdminMenuOption(4);
        assertThat(option4.optionNumber()).isEqualTo(4);
        assertThat(option4.optionName()).isEqualTo("User Delete (Security)");
        assertThat(option4.cobolProgram()).isEqualTo("COUSR03C");
    }

    /**
     * Verifies that requesting option 0 throws {@link IllegalArgumentException},
     * preserving the COBOL validation from {@code COADM01C.cbl} lines 127-133:
     * <pre>
     *   IF WS-OPTION = ZEROS
     *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * </pre>
     */
    @Test
    void testGetAdminMenuOption_invalidOption_zero() {
        assertThatThrownBy(() -> adminMenuService.getAdminMenuOption(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that requesting option 5 (one beyond the max) throws
     * {@link IllegalArgumentException}, preserving the COBOL validation
     * from {@code COADM01C.cbl} lines 127-133:
     * <pre>
     *   IF WS-OPTION &gt; CDEMO-ADMIN-OPT-COUNT
     *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * </pre>
     */
    @Test
    void testGetAdminMenuOption_invalidOption_outOfRange() {
        assertThatThrownBy(() -> adminMenuService.getAdminMenuOption(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that requesting a negative option number throws
     * {@link IllegalArgumentException}. While COBOL's {@code PIC 9(02)}
     * cannot represent negative values, the Java range check
     * ({@code optionNumber < 1}) handles this case defensively.
     */
    @Test
    void testGetAdminMenuOption_invalidOption_negative() {
        assertThatThrownBy(() -> adminMenuService.getAdminMenuOption(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that {@code isAdminOnly()} returns {@code true}, confirming
     * that this menu is restricted to admin users only.
     *
     * <p>In the COBOL architecture, only users with
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} (from {@code COCOM01Y.cpy} line 27)
     * are routed to the admin menu program {@code COADM01C} by the sign-on
     * program {@code COSGN00C}.</p>
     */
    @Test
    void testIsAdminOnly_returnsTrue() {
        assertThat(adminMenuService.isAdminOnly()).isTrue();
    }

    /**
     * Verifies that {@code getOptionCount()} returns exactly 4, matching
     * the COBOL constant {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}
     * from {@code COADM02Y.cpy} line 20.
     */
    @Test
    void testGetOptionCount_returnsFour() {
        assertThat(adminMenuService.getOptionCount()).isEqualTo(4);
    }

    /**
     * Verifies that the list returned by {@code getAdminMenuOptions()} is
     * unmodifiable, preserving the read-only semantics of the COBOL
     * compile-time VALUE table in {@code COADM02Y.cpy}.
     *
     * <p>The COBOL VALUE table is defined at compile time and cannot be
     * modified at runtime. The Java equivalent uses {@code List.of()},
     * which produces an unmodifiable list that throws
     * {@link UnsupportedOperationException} on any mutation attempt.</p>
     */
    @Test
    void testGetAdminMenuOptions_listIsImmutable() {
        List<AdminMenuOption> options = adminMenuService.getAdminMenuOptions();

        assertThatThrownBy(() -> options.add(
                new AdminMenuOption(5, "Dummy", "DUMMYPGM", "/api/dummy")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
