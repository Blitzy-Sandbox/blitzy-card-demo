package com.carddemo.unit.service.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.menu.MainMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link MainMenuService}, the Java migration of the CICS main-menu router
 * COMEN01C driven by the COMEN02Y option table (source SHA 27d6c6f).
 */
@DisplayName("MainMenuService - COMEN02Y table + COMEN01C EVALUATE routing parity")
class MainMenuServiceTest {

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    private MainMenuService service;

    @BeforeEach
    void setUp() {
        service = new MainMenuService();
    }

    @Nested
    @DisplayName("getMenuOptions() - COMEN02Y table fidelity")
    class MenuTable {

        @Test
        @DisplayName("exposes exactly 10 options (CDEMO-MENU-OPT-COUNT = 10)")
        void exposesExactlyTenOptions() {
            assertThat(service.getMenuOptions()).hasSize(10);
        }

        @Test
        @DisplayName("returns the 10 options in exact COMEN02Y order, label, target program and user-type")
        void returnsAllOptionsInExactOrder() {
            assertThat(service.getMenuOptions()).containsExactly(
                    new MenuOption(1, "Account View", "GET /api/accounts/{id}", "COACTVWC", UserType.USER),
                    new MenuOption(2, "Account Update", "PUT /api/accounts/{id}", "COACTUPC", UserType.USER),
                    new MenuOption(3, "Credit Card List", "GET /api/cards", "COCRDLIC", UserType.USER),
                    new MenuOption(4, "Credit Card View", "GET /api/cards/{cardNum}", "COCRDSLC", UserType.USER),
                    new MenuOption(5, "Credit Card Update", "PUT /api/cards/{cardNum}", "COCRDUPC", UserType.USER),
                    new MenuOption(6, "Transaction List", "GET /api/transactions", "COTRN00C", UserType.USER),
                    new MenuOption(7, "Transaction View", "GET /api/transactions/{id}", "COTRN01C", UserType.USER),
                    new MenuOption(8, "Transaction Add", "POST /api/transactions", "COTRN02C", UserType.USER),
                    new MenuOption(9, "Transaction Reports", "POST /api/reports/submit", "CORPT00C", UserType.USER),
                    new MenuOption(10, "Bill Payment", "POST /api/billing/pay", "COBIL00C", UserType.USER));
        }

        @Test
        @DisplayName("every COMEN02Y entry carries user-type USER ('U')")
        void everyOptionIsUserType() {
            for (MenuOption option : service.getMenuOptions()) {
                assertThat(option.userType()).isEqualTo(UserType.USER);
            }
        }
    }

    @Nested
    @DisplayName("resolveOption() - valid selections mirror the COMEN01C EVALUATE branch order")
    class ValidRouting {

        @ParameterizedTest(name = "option {0} routes to {2}")
        @CsvSource({
            "1, Account View, COACTVWC",
            "2, Account Update, COACTUPC",
            "3, Credit Card List, COCRDLIC",
            "4, Credit Card View, COCRDSLC",
            "5, Credit Card Update, COCRDUPC",
            "6, Transaction List, COTRN00C",
            "7, Transaction View, COTRN01C",
            "8, Transaction Add, COTRN02C",
            "9, Transaction Reports, CORPT00C",
            "10, Bill Payment, COBIL00C"
        })
        void routesEachValidOptionToExpectedProgram(int optionNumber, String expectedName, String expectedProgram) {
            MenuOption resolved = service.resolveOption(String.valueOf(optionNumber), UserType.USER);
            assertThat(resolved.optionNumber()).isEqualTo(optionNumber);
            assertThat(resolved.label()).isEqualTo(expectedName);
            assertThat(resolved.programName()).isEqualTo(expectedProgram);
            assertThat(resolved.userType()).isEqualTo(UserType.USER);
        }

        @ParameterizedTest(name = "selection [{0}] normalizes to option {1}")
        @CsvSource({
            "' 7 ', 7, COTRN01C",
            "'05', 5, COCRDUPC",
            "' 1', 1, COACTVWC",
            "'2 ', 2, COACTUPC",
            "'010', 10, COBIL00C"
        })
        void toleratesSurroundingWhitespaceAndLeadingZeros(String selection, int expectedNumber, String expectedProgram) {
            MenuOption resolved = service.resolveOption(selection, UserType.USER);
            assertThat(resolved.optionNumber()).isEqualTo(expectedNumber);
            assertThat(resolved.programName()).isEqualTo(expectedProgram);
        }

        @Test
        @DisplayName("an ADMIN caller bypasses the per-option USER gate and still resolves a main-menu option")
        void adminCallerResolvesMainMenuOption() {
            MenuOption resolved = service.resolveOption("1", UserType.ADMIN);
            assertThat(resolved.programName()).isEqualTo("COACTVWC");
        }
    }

    @Nested
    @DisplayName("resolveOption() - invalid selection boundary mirrors COMEN01C L127-134")
    class InvalidRouting {

        @ParameterizedTest(name = "out-of-range / non-numeric selection [{0}] is rejected")
        @ValueSource(strings = {"0", "00", "11", "12", "-1", "abc", "7a", "9999999999"})
        void invalidSelectionsThrowValidationException(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.USER))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_OPTION_MESSAGE);
        }

        @ParameterizedTest(name = "null/blank selection [{0}] is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void nullOrBlankSelectionsThrowValidationException(String selection) {
            assertThatThrownBy(() -> service.resolveOption(selection, UserType.USER))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("preserved parity: the per-option Admin-Only gate is unreachable because every COMEN02Y entry is USER")
        void perOptionAdminGateIsUnreachableForAllUserTable() {
            // COMEN01C L136-143 guards CDEMO-MENU-OPT-USRTYPE = 'A'; no entry is 'A', so a USER
            // caller resolves option 8 (the historically commented "Admin Only" candidate) successfully.
            MenuOption resolved = service.resolveOption("8", UserType.USER);
            assertThat(resolved.programName()).isEqualTo("COTRN02C");
            assertThat(resolved.userType()).isEqualTo(UserType.USER);
        }
    }
}
