package com.cardemo.unit.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.service.menu.MainMenuService.MenuOption;
import com.cardemo.service.menu.MainMenuService.MenuSelectionResult;
import com.cardemo.service.menu.MainMenuService.MessageSeverity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for {@link MainMenuService}, the Java migration of the
 * legacy AWS CardDemo regular-user Main Menu CICS program {@code COMEN01C} (transaction
 * {@code CM00}) and its option table {@code app/cpy/COMEN02Y.cpy} (frozen baseline commit SHA
 * {@code 27d6c6f}).
 *
 * <p>The checkpoint requires menu-routing <em>parity</em>: the migrated service must reproduce the
 * 10 {@code COMEN01C}/{@code COMEN02Y} options exactly &mdash; same option numbers, labels and
 * target programs &mdash; with no extra or omitted options, and must reproduce the
 * {@code PROCESS-ENTER-KEY} validate &rarr; gate &rarr; navigate flow (AAP &sect;0.7.4 control-flow
 * preservation, &sect;0.7.2 external-interface preservation). These tests are intentionally
 * implementation-sensitive: they assert the exact option metadata, the exact option <em>count</em>
 * (10), the verbatim COBOL error text, and the COMMAREA hand-off stamped on navigation, so a
 * regression that added, dropped, reordered or relabelled an option, or that altered the
 * validation/navigation contract, would fail.</p>
 *
 * <h2>Note on the admin-only gate</h2>
 * <p>{@code COMEN01C} {@code PROCESS-ENTER-KEY} contains a user-type gate
 * ({@code IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}). In the shipped
 * {@code COMEN02Y} table every one of the 10 options carries {@code usrtype = 'U'}, so the gate is
 * never satisfied for production data. Rather than fabricate an unreachable scenario, this test
 * pins the invariant that makes the gate dormant &mdash; <em>no option is admin-only</em> &mdash;
 * which is the faithful, observable contract of the source.</p>
 *
 * <p>Fast and isolated: {@link MainMenuService} holds only compile-time-known reference data and
 * depends only on {@link CommArea} and {@link UserType}, so there is no Spring context, database or
 * Testcontainers (Surefire-compatible). The COBOL source is read-only reference and is never copied
 * into this repository (AAP &sect;0.7.2); only its behaviour is asserted.</p>
 */
class MainMenuServiceTest {

    /** The system under test. Stateless reference-data holder; a single instance suffices. */
    private final MainMenuService service = new MainMenuService();

    /**
     * Builds a fresh pseudo-conversational context carrying the given signed-in user type.
     *
     * @param type the signed-in user type to stamp on the context (may be {@code null})
     * @return a new {@link CommArea} whose {@code userType} is {@code type} and whose hand-off
     *         fields are unset
     */
    private static CommArea commAreaFor(UserType type) {
        CommArea ca = new CommArea();
        ca.setUserType(type);
        return ca;
    }

    @Nested
    @DisplayName("Option table (COMEN02Y CARDDEMO-MAIN-MENU-OPTIONS, count = 10)")
    class OptionTable {

        @Test
        @DisplayName("exposes exactly 10 options and a matching option count")
        void exactlyTenOptions() {
            assertThat(service.getOptionCount()).isEqualTo(10);
            assertThat(service.getMenuOptions()).hasSize(10);
        }

        @Test
        @DisplayName("reproduces all 10 COMEN01C/COMEN02Y options in order with exact metadata")
        void allTenOptionsExact() {
            // Implementation-sensitive: number, label, target program AND allowed user type must
            // all match the COMEN02Y table verbatim, in option-number order, with nothing added.
            assertThat(service.getMenuOptions()).containsExactly(
                    new MenuOption(1, "Account View", "COACTVWC", UserType.USER),
                    new MenuOption(2, "Account Update", "COACTUPC", UserType.USER),
                    new MenuOption(3, "Credit Card List", "COCRDLIC", UserType.USER),
                    new MenuOption(4, "Credit Card View", "COCRDSLC", UserType.USER),
                    new MenuOption(5, "Credit Card Update", "COCRDUPC", UserType.USER),
                    new MenuOption(6, "Transaction List", "COTRN00C", UserType.USER),
                    new MenuOption(7, "Transaction View", "COTRN01C", UserType.USER),
                    new MenuOption(8, "Transaction Add", "COTRN02C", UserType.USER),
                    new MenuOption(9, "Transaction Reports", "CORPT00C", UserType.USER),
                    new MenuOption(10, "Bill Payment", "COBIL00C", UserType.USER));
        }

        @Test
        @DisplayName("option numbers are exactly 1..10 with no gaps or duplicates")
        void optionNumbersAreSequential() {
            assertThat(service.getMenuOptions()).extracting(MenuOption::number)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        @Test
        @DisplayName("no option is admin-only (every COMEN02Y usrtype byte is 'U'), so the admin gate stays dormant")
        void noOptionIsAdminOnly() {
            assertThat(service.getMenuOptions())
                    .allSatisfy(opt -> assertThat(opt.allowedUserType()).isEqualTo(UserType.USER));
        }

        @Test
        @DisplayName("program identity matches COMEN01C working storage (CM00 / COMEN01C)")
        void programIdentity() {
            assertThat(MainMenuService.TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MainMenuService.PROGRAM_NAME).isEqualTo("COMEN01C");
        }
    }

    @Nested
    @DisplayName("selectOption(...) navigation (PROCESS-ENTER-KEY happy path)")
    class Navigation {

        @Test
        @DisplayName("a valid option navigates and stamps the COMMAREA hand-off fields (XCTL replacement)")
        void validOptionNavigatesAndStampsCommArea() {
            CommArea ca = commAreaFor(UserType.USER);

            MenuSelectionResult result = service.selectOption("1", ca);

            assertThat(result.navigate()).isTrue();
            assertThat(result.targetProgram()).isEqualTo("COACTVWC");
            assertThat(result.message()).isNull();
            assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);

            // COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID, WS-PGMNAME -> CDEMO-FROM-PROGRAM,
            //   ZEROS -> CDEMO-PGM-CONTEXT, then XCTL PROGRAM(target).
            assertThat(ca.getFromTranId()).isEqualTo("CM00");
            assertThat(ca.getFromProgram()).isEqualTo("COMEN01C");
            assertThat(ca.getProgramContext()).isZero();
            assertThat(ca.getToProgram()).isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("every one of the 10 options routes to its declared target program")
        void everyOptionRoutesToItsDeclaredTarget() {
            for (MenuOption opt : service.getMenuOptions()) {
                CommArea ca = commAreaFor(UserType.USER);

                MenuSelectionResult result = service.selectOption(Integer.toString(opt.number()), ca);

                assertThat(result.navigate())
                        .as("option %d navigates", opt.number()).isTrue();
                assertThat(result.targetProgram())
                        .as("option %d target", opt.number()).isEqualTo(opt.targetProgram());
                assertThat(ca.getToProgram())
                        .as("option %d COMMAREA to-program", opt.number()).isEqualTo(opt.targetProgram());
            }
        }

        @Test
        @DisplayName("an admin user may also select a regular option (the gate only blocks USER picking ADMIN-only)")
        void adminUserNavigatesRegularOption() {
            CommArea ca = commAreaFor(UserType.ADMIN);

            MenuSelectionResult result = service.selectOption("10", ca);

            assertThat(result.navigate()).isTrue();
            assertThat(result.targetProgram()).isEqualTo("COBIL00C");
        }
    }

    @Nested
    @DisplayName("selectOption(...) validation (PROCESS-ENTER-KEY error path)")
    class Validation {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "0", "00", "11", "99", "abc", "1a", "-1", "1.5"})
        @DisplayName("invalid / out-of-range / non-numeric input yields the verbatim COBOL error and does not navigate")
        void invalidInputDoesNotNavigate(String raw) {
            CommArea ca = commAreaFor(UserType.USER);

            MenuSelectionResult result = service.selectOption(raw, ca);

            assertThat(result.navigate()).isFalse();
            assertThat(result.targetProgram()).isNull();
            // Verbatim COBOL text: MOVE 'Please enter a valid option number...' TO WS-MESSAGE.
            assertThat(result.message()).isEqualTo("Please enter a valid option number...");
            assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
            // The error path returns before the COMMAREA hand-off, so no target is stamped.
            assertThat(ca.getToProgram()).isNull();
        }
    }
}
