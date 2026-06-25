package com.carddemo.unit.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.dto.MenuDto;
import com.carddemo.service.MenuService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit tests for {@link MenuService}.
 *
 * <p>{@code MenuService} is the Java realization of the CICS pseudo-conversational
 * menu programs {@code COMEN01C} (regular-user main menu, transaction {@code CM00})
 * and {@code COADM01C} (administrator menu, transaction {@code CA00}) @
 * {@code 27d6c6f}. The option tables are translated from the copybooks
 * {@code app/cpy/COMEN02Y.cpy} (ten main options, {@code CDEMO-MENU-OPT-COUNT = 10})
 * and {@code app/cpy/COADM02Y.cpy} (four admin options,
 * {@code CDEMO-ADMIN-OPT-COUNT = 4}); the headings are taken from the BMS mapsets
 * {@code COMEN01.bms} / {@code COADM01.bms}.</p>
 *
 * <p>The service is stateless and has no collaborators, so it is instantiated
 * directly with {@code new MenuService()} &mdash; there is no Spring context, no
 * database, and no Mockito. The assertions lock the contract exactly against the
 * compiled service: ten / four options, the option numbers emitted as
 * {@link String}s {@code "1".."10"} and {@code "1".."4"} (one- or two-digit codes,
 * <em>not</em> zero-padded), the {@code COMEN02Y} / {@code COADM02Y} option names in
 * order, and the {@code "Main Menu"} / {@code "Admin Menu"} headings.</p>
 *
 * <p>The option lists are exposed as immutable {@code List.of(...)} instances, which
 * the service documents as part of its thread-safety contract; the immutability
 * tests assert that mutation attempts raise {@link UnsupportedOperationException}.
 * {@code getMenuForUserType} mirrors the legacy sign-on routing where the
 * {@code SEC-USR-TYPE} value {@code "A"} (case-sensitive) lands on the admin menu and
 * every other value &mdash; including {@code null} &mdash; lands on the main menu.</p>
 */
@DisplayName("MenuService - COMEN01C (CM00) / COADM01C (CA00) static menu definitions @ 27d6c6f")
class MenuServiceTest {

    /** The service under test holds no state, so a single instance is reused per method. */
    private final MenuService service = new MenuService();

    // -----------------------------------------------------------------
    // Main menu (COMEN01C / COMEN02Y, transaction CM00)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getMainMenu: heading is 'Main Menu' (COMEN01.bms TITLE01)")
    void getMainMenuHasMainMenuTitle() {
        assertThat(service.getMainMenu().title()).isEqualTo("Main Menu");
    }

    @Test
    @DisplayName("getMainMenu: returns the ten regular-user options (COMEN02Y count = 10)")
    void getMainMenuHasTenOptions() {
        assertThat(service.getMainMenu().options()).hasSize(10);
    }

    @Test
    @DisplayName("getMainMenu: option numbers are '1'..'10' in order (String codes, not zero-padded)")
    void getMainMenuOptionNumbersAreOneThroughTen() {
        assertThat(service.getMainMenu().options())
                .extracting(MenuDto.MenuOption::optionNumber)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    @DisplayName("getMainMenu: option names match COMEN02Y in order")
    void getMainMenuOptionNamesMatchCopybook() {
        assertThat(service.getMainMenu().options())
                .extracting(MenuDto.MenuOption::optionName)
                .containsExactly(
                        "Account View",
                        "Account Update",
                        "Credit Card List",
                        "Credit Card View",
                        "Credit Card Update",
                        "Transaction List",
                        "Transaction View",
                        "Transaction Add",
                        "Transaction Reports",
                        "Bill Payment");
    }

    @Test
    @DisplayName("getMainMenu: full (number, name) contract for all ten options")
    void getMainMenuFullOptionContract() {
        assertThat(service.getMainMenu().options()).containsExactly(
                new MenuDto.MenuOption("1", "Account View"),
                new MenuDto.MenuOption("2", "Account Update"),
                new MenuDto.MenuOption("3", "Credit Card List"),
                new MenuDto.MenuOption("4", "Credit Card View"),
                new MenuDto.MenuOption("5", "Credit Card Update"),
                new MenuDto.MenuOption("6", "Transaction List"),
                new MenuDto.MenuOption("7", "Transaction View"),
                new MenuDto.MenuOption("8", "Transaction Add"),
                new MenuDto.MenuOption("9", "Transaction Reports"),
                new MenuDto.MenuOption("10", "Bill Payment"));
    }

    @Test
    @DisplayName("getMainMenu: first option is '1'/Account View and last is '10'/Bill Payment")
    void getMainMenuFirstAndLastOptionsLockContract() {
        var options = service.getMainMenu().options();
        assertThat(options.get(0)).isEqualTo(new MenuDto.MenuOption("1", "Account View"));
        assertThat(options.get(9)).isEqualTo(new MenuDto.MenuOption("10", "Bill Payment"));
    }

    // -----------------------------------------------------------------
    // Admin menu (COADM01C / COADM02Y, transaction CA00)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getAdminMenu: heading is 'Admin Menu' (COADM01.bms TITLE01)")
    void getAdminMenuHasAdminMenuTitle() {
        assertThat(service.getAdminMenu().title()).isEqualTo("Admin Menu");
    }

    @Test
    @DisplayName("getAdminMenu: returns the four security options (COADM02Y count = 4)")
    void getAdminMenuHasFourOptions() {
        assertThat(service.getAdminMenu().options()).hasSize(4);
    }

    @Test
    @DisplayName("getAdminMenu: option numbers are '1'..'4' in order")
    void getAdminMenuOptionNumbersAreOneThroughFour() {
        assertThat(service.getAdminMenu().options())
                .extracting(MenuDto.MenuOption::optionNumber)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("getAdminMenu: the four (Security) labels match COADM02Y in order")
    void getAdminMenuOptionNamesMatchCopybook() {
        assertThat(service.getAdminMenu().options())
                .extracting(MenuDto.MenuOption::optionName)
                .containsExactly(
                        "User List (Security)",
                        "User Add (Security)",
                        "User Update (Security)",
                        "User Delete (Security)");
    }

    @Test
    @DisplayName("getAdminMenu: full (number, name) contract for all four options")
    void getAdminMenuFullOptionContract() {
        assertThat(service.getAdminMenu().options()).containsExactly(
                new MenuDto.MenuOption("1", "User List (Security)"),
                new MenuDto.MenuOption("2", "User Add (Security)"),
                new MenuDto.MenuOption("3", "User Update (Security)"),
                new MenuDto.MenuOption("4", "User Delete (Security)"));
    }

    // -----------------------------------------------------------------
    // Determinism: the menus are static, so repeated calls are equal
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getMainMenu: deterministic - repeated calls produce equal content")
    void getMainMenuIsDeterministic() {
        MenuDto.MenuResponse first = service.getMainMenu();
        MenuDto.MenuResponse second = service.getMainMenu();
        assertThat(second).isEqualTo(first);
        assertThat(second.options()).containsExactlyElementsOf(first.options());
    }

    @Test
    @DisplayName("getAdminMenu: deterministic - repeated calls produce equal content")
    void getAdminMenuIsDeterministic() {
        MenuDto.MenuResponse first = service.getAdminMenu();
        MenuDto.MenuResponse second = service.getAdminMenu();
        assertThat(second).isEqualTo(first);
        assertThat(second.options()).containsExactlyElementsOf(first.options());
    }

    // -----------------------------------------------------------------
    // Immutability: option tables are List.of(...) (documented immutable)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getMainMenu: option list is immutable (add raises UnsupportedOperationException)")
    void mainMenuOptionsAreImmutable() {
        assertThatThrownBy(() ->
                service.getMainMenu().options().add(new MenuDto.MenuOption("99", "Injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getAdminMenu: option list is immutable (add raises UnsupportedOperationException)")
    void adminMenuOptionsAreImmutable() {
        assertThatThrownBy(() ->
                service.getAdminMenu().options().add(new MenuDto.MenuOption("99", "Injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -----------------------------------------------------------------
    // getMenuForUserType: sign-on routing on SEC-USR-TYPE
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getMenuForUserType('A'): administrators route to the four-option admin menu")
    void getMenuForUserTypeAdminReturnsAdminMenu() {
        MenuDto.MenuResponse menu = service.getMenuForUserType("A");
        assertThat(menu.title()).isEqualTo("Admin Menu");
        assertThat(menu.options()).hasSize(4);
    }

    @Test
    @DisplayName("getMenuForUserType('U'): regular users route to the ten-option main menu")
    void getMenuForUserTypeUserReturnsMainMenu() {
        MenuDto.MenuResponse menu = service.getMenuForUserType("U");
        assertThat(menu.title()).isEqualTo("Main Menu");
        assertThat(menu.options()).hasSize(10);
    }

    @Test
    @DisplayName("getMenuForUserType(null): a null user type defaults to the main menu")
    void getMenuForUserTypeNullReturnsMainMenu() {
        MenuDto.MenuResponse menu = service.getMenuForUserType(null);
        assertThat(menu.title()).isEqualTo("Main Menu");
        assertThat(menu.options()).hasSize(10);
    }

    @Test
    @DisplayName("getMenuForUserType(unknown): any non-'A' value defaults to the main menu")
    void getMenuForUserTypeUnknownReturnsMainMenu() {
        MenuDto.MenuResponse menu = service.getMenuForUserType("Z");
        assertThat(menu.title()).isEqualTo("Main Menu");
        assertThat(menu.options()).hasSize(10);
    }

    @Test
    @DisplayName("getMenuForUserType is case-sensitive: lowercase 'a' is not the admin type")
    void getMenuForUserTypeIsCaseSensitive() {
        MenuDto.MenuResponse menu = service.getMenuForUserType("a");
        assertThat(menu.title()).isEqualTo("Main Menu");
        assertThat(menu.options()).hasSize(10);
    }
}
