package com.carddemo.unit.service.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.menu.AdminMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AdminMenuService}, the Java migration of the CICS admin-menu router
 * COADM01C driven by the COADM02Y option table (source SHA 27d6c6f).
 */
@DisplayName("AdminMenuService - COADM02Y table + COADM01C admin-gate-first routing parity")
class AdminMenuServiceTest {

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option...";

    private AdminMenuService service;

    @BeforeEach
    void setUp() {
        service = new AdminMenuService();
    }

    @Nested
    @DisplayName("getMenuOptions() - COADM02Y table fidelity")
    class MenuTable {

        @Test
        @DisplayName("exposes exactly 4 options (CDEMO-ADMIN-OPT-COUNT = 4)")
        void exposesExactlyFourOptions() {
            assertThat(service.getMenuOptions()).hasSize(4);
        }

        @Test
        @DisplayName("returns the 4 options in exact COADM02Y order, label and target program (no user-type field)")
        void returnsAllOptionsInExactOrder() {
            assertThat(service.getMenuOptions()).containsExactly(
                    new MenuOption(1, "User List (Security)", "COUSR00C", null),
                    new MenuOption(2, "User Add (Security)", "COUSR01C", null),
                    new MenuOption(3, "User Update (Security)", "COUSR02C", null),
                    new MenuOption(4, "User Delete (Security)", "COUSR03C", null));
        }

        @Test
        @DisplayName("every COADM02Y entry has a null user-type (COADM02Y has no USRTYPE field)")
        void everyOptionHasNullUserType() {
            for (MenuOption option : service.getMenuOptions()) {
                assertThat(option.userType()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("resolveOption() - an ADMIN caller routes each valid option (COADM01C L137-155)")
    class AdminRouting {

        @ParameterizedTest(name = "admin option {0} routes to {2}")
        @CsvSource({
            "1, User List (Security), COUSR00C",
            "2, User Add (Security), COUSR01C",
            "3, User Update (Security), COUSR02C",
            "4, User Delete (Security), COUSR03C"
        })
        void adminResolvesEachValidOption(int optionNumber, String expectedName, String expectedProgram) {
            MenuOption resolved = service.resolveOption(String.valueOf(optionNumber), UserType.ADMIN);
            assertThat(resolved.optionNumber()).isEqualTo(optionNumber);
            assertThat(resolved.optionName()).isEqualTo(expectedName);
            assertThat(resolved.programName()).isEqualTo(expectedProgram);
        }

        @ParameterizedTest(name = "admin selection [{0}] normalizes to {1}")
        @CsvSource({
            "' 3 ', COUSR02C",
            "'04', COUSR03C",
            "' 1', COUSR00C",
            "'2 ', COUSR01C"
        })
        void adminToleratesSurroundingWhitespaceAndLeadingZeros(String selection, String expectedProgram) {
            MenuOption resolved = service.resolveOption(selection, UserType.ADMIN);
            assertThat(resolved.programName()).isEqualTo(expectedProgram);
        }
    }

    @Nested
    @DisplayName("resolveOption() - whole-menu admin gate fires FIRST (before parsing the selection)")
    class AdminGateFirst {

        @ParameterizedTest(name = "USER caller denied for any selection [{0}]")
        @ValueSource(strings = {"1", "2", "3", "4", "99", "0", "abc", " "})
        void userCallerAlwaysDeniedRegardlessOfSelection(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.USER))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ADMIN_ONLY_MESSAGE);
        }

        @ParameterizedTest(name = "USER caller denied even for null/blank selection [{0}] (gate precedes parse)")
        @NullAndEmptySource
        void userCallerDeniedBeforeBlankSelectionIsParsed(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.USER))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("a null user-type caller is denied with the Admin-Only message even for a valid selection")
        void nullUserTypeCallerDeniedForValidSelection() {
            assertThatThrownBy(() -> service.resolveOption("1", null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ADMIN_ONLY_MESSAGE);
        }
    }

    @Nested
    @DisplayName("resolveOption() - an ADMIN caller's invalid selection boundary mirrors COADM01C L127-134")
    class InvalidRoutingForAdmin {

        @ParameterizedTest(name = "admin out-of-range / non-numeric selection [{0}] is rejected")
        @ValueSource(strings = {"0", "00", "5", "6", "-1", "x", "9999999999"})
        void adminInvalidSelectionsThrowValidationException(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.ADMIN))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_OPTION_MESSAGE);
        }

        @ParameterizedTest(name = "admin null/blank selection [{0}] is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void adminNullOrBlankSelectionsThrowValidationException(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.ADMIN))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_OPTION_MESSAGE);
        }
    }
}
