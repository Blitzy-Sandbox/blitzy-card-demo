package com.cardemo.unit.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.AdminMenuService.AdminMenuOption;
import com.cardemo.service.menu.AdminMenuService.MenuSelectionResult;
import com.cardemo.service.menu.AdminMenuService.MessageSeverity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for {@link AdminMenuService}, the Java migration of the
 * legacy AWS CardDemo administrator Admin Menu CICS program {@code COADM01C} (transaction
 * {@code CA00}) and its option table {@code app/cpy/COADM02Y.cpy} (frozen baseline commit SHA
 * {@code 27d6c6f}).
 *
 * <p>The checkpoint requires admin-menu routing <em>parity</em>: the migrated service must
 * reproduce the 4 {@code COADM01C}/{@code COADM02Y} options exactly &mdash; same option numbers,
 * labels (including the canonical "(Security)" suffix) and target programs &mdash; with no extra or
 * omitted options, expose the menu's ADMIN-only access expectation, and reproduce the
 * {@code PROCESS-ENTER-KEY} validate &rarr; navigate flow (AAP &sect;0.7.4 control-flow
 * preservation, &sect;0.7.2 external-interface preservation). These tests are intentionally
 * implementation-sensitive: they assert the exact option metadata, the exact option <em>count</em>
 * (4), the verbatim COBOL error text, the {@code isAccessibleBy} contract, and the COMMAREA hand-off
 * stamped on navigation.</p>
 *
 * <h2>Two faithful differences from the regular-user menu</h2>
 * <ul>
 *   <li><strong>No per-option user-type gate.</strong> {@code COADM01C}
 *       {@code PROCESS-ENTER-KEY} performs no per-option user-type check (the whole menu is
 *       ADMIN-only by virtue of sign-on routing, enforced upstream at the controller/security
 *       boundary). This test pins that contract directly: {@link #selectOption} navigates even for
 *       a context carrying {@link UserType#USER}. A regression that smuggled a gate into the
 *       service flow would fail here.</li>
 *   <li><strong>Menu-level access is documented metadata.</strong>
 *       {@link AdminMenuService#isAccessibleBy(UserType)} exposes the ADMIN-only expectation for
 *       callers (a controller pre-check); it is asserted here but is not invoked inside
 *       {@link #selectOption}.</li>
 * </ul>
 *
 * <p>Fast and isolated: {@link AdminMenuService} holds only compile-time-known reference data and
 * depends only on {@link CommArea} and {@link UserType}, so there is no Spring context, database or
 * Testcontainers (Surefire-compatible). The COBOL source is read-only reference and is never copied
 * into this repository (AAP &sect;0.7.2); only its behaviour is asserted.</p>
 */
class AdminMenuServiceTest {

    /** The system under test. Stateless reference-data holder; a single instance suffices. */
    private final AdminMenuService service = new AdminMenuService();

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
    @DisplayName("Option table (COADM02Y CARDDEMO-ADMIN-MENU-OPTIONS, count = 4)")
    class OptionTable {

        @Test
        @DisplayName("exposes exactly 4 options and a matching option count")
        void exactlyFourOptions() {
            assertThat(service.getOptionCount()).isEqualTo(4);
            assertThat(service.getMenuOptions()).hasSize(4);
        }

        @Test
        @DisplayName("reproduces all 4 COADM01C/COADM02Y options in order with exact metadata")
        void allFourOptionsExact() {
            // Implementation-sensitive: number, label (with the "(Security)" suffix) and target
            // program must all match the COADM02Y table verbatim, in order, with nothing added.
            assertThat(service.getMenuOptions()).containsExactly(
                    new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
                    new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
                    new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
                    new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));
        }

        @Test
        @DisplayName("option numbers are exactly 1..4 with no gaps or duplicates")
        void optionNumbersAreSequential() {
            assertThat(service.getMenuOptions()).extracting(AdminMenuOption::number)
                    .containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("program identity and required user type match COADM01C (CA00 / COADM01C / ADMIN)")
        void programIdentity() {
            assertThat(AdminMenuService.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuService.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuService.REQUIRED_USER_TYPE).isEqualTo(UserType.ADMIN);
        }
    }

    @Nested
    @DisplayName("isAccessibleBy(UserType) — menu-level ADMIN-only metadata")
    class AccessControl {

        @Test
        @DisplayName("only an ADMIN user is accessible; USER and null are denied")
        void onlyAdminIsAccessible() {
            assertThat(service.isAccessibleBy(UserType.ADMIN)).isTrue();
            assertThat(service.isAccessibleBy(UserType.USER)).isFalse();
            assertThat(service.isAccessibleBy(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("selectOption(...) navigation (PROCESS-ENTER-KEY happy path)")
    class Navigation {

        @Test
        @DisplayName("a valid option navigates and stamps the COMMAREA hand-off fields (XCTL replacement)")
        void validOptionNavigatesAndStampsCommArea() {
            CommArea ca = commAreaFor(UserType.ADMIN);

            MenuSelectionResult result = service.selectOption("1", ca);

            assertThat(result.navigate()).isTrue();
            assertThat(result.targetProgram()).isEqualTo("COUSR00C");
            assertThat(result.message()).isNull();
            assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);

            // COBOL: MOVE WS-TRANID -> CDEMO-FROM-TRANID, WS-PGMNAME -> CDEMO-FROM-PROGRAM,
            //   ZEROS -> CDEMO-PGM-CONTEXT, then XCTL PROGRAM(target).
            assertThat(ca.getFromTranId()).isEqualTo("CA00");
            assertThat(ca.getFromProgram()).isEqualTo("COADM01C");
            assertThat(ca.getProgramContext()).isZero();
            assertThat(ca.getToProgram()).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("every one of the 4 options routes to its declared target program")
        void everyOptionRoutesToItsDeclaredTarget() {
            for (AdminMenuOption opt : service.getMenuOptions()) {
                CommArea ca = commAreaFor(UserType.ADMIN);

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
        @DisplayName("no in-flow user-type gate: a USER context still navigates (faithful to COADM01C)")
        void userContextStillNavigates() {
            // COADM01C PROCESS-ENTER-KEY has no per-option user-type check; access is enforced
            // upstream, not inside selectOption. A USER-typed context therefore still navigates.
            CommArea ca = commAreaFor(UserType.USER);

            MenuSelectionResult result = service.selectOption("4", ca);

            assertThat(result.navigate()).isTrue();
            assertThat(result.targetProgram()).isEqualTo("COUSR03C");
            assertThat(ca.getToProgram()).isEqualTo("COUSR03C");
        }
    }

    @Nested
    @DisplayName("selectOption(...) validation (PROCESS-ENTER-KEY error path)")
    class Validation {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "0", "00", "5", "99", "abc", "2b", "-1", "2.5"})
        @DisplayName("invalid / out-of-range / non-numeric input yields the verbatim COBOL error and does not navigate")
        void invalidInputDoesNotNavigate(String raw) {
            CommArea ca = commAreaFor(UserType.ADMIN);

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
