package com.carddemo.unit.service.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.menu.AdminMenuService;
import com.carddemo.service.menu.MainMenuService;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MainMenuService} (COBOL {@code COMEN01C}/{@code COMEN02Y}) and
 * {@link AdminMenuService} (COBOL {@code COADM01C}/{@code COADM02Y}): the routing tables,
 * parse/bounds edits, and admin gating.
 */
class MenuServicesTest {

    private final MainMenuService mainMenu = new MainMenuService();
    private final AdminMenuService adminMenu = new AdminMenuService();

    @Test
    void mainMenuExposesTenOptionsInDisplayOrder() {
        assertThat(mainMenu.getMenuOptions()).hasSize(10);
        assertThat(mainMenu.getMenuOptions().get(0).programName()).isEqualTo("COACTVWC");
        assertThat(mainMenu.getMenuOptions().get(9).programName()).isEqualTo("COBIL00C");
    }

    @Test
    void mainMenuResolvesValidSelectionForRegularUser() {
        MenuOption option = mainMenu.resolveOption("8", UserType.USER);
        assertThat(option.optionNumber()).isEqualTo(8);
        assertThat(option.programName()).isEqualTo("COTRN02C");
    }

    @Test
    void mainMenuRejectsBlankSelection() {
        assertThatThrownBy(() -> mainMenu.resolveOption(null, UserType.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please enter a valid option number...");
    }

    @Test
    void mainMenuRejectsNonNumericSelection() {
        assertThatThrownBy(() -> mainMenu.resolveOption("abc", UserType.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please enter a valid option number...");
    }

    @Test
    void mainMenuRejectsZeroAndOutOfRange() {
        assertThatThrownBy(() -> mainMenu.resolveOption("0", UserType.USER))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> mainMenu.resolveOption("11", UserType.USER))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void adminMenuExposesFourOptions() {
        assertThat(adminMenu.getMenuOptions()).hasSize(4);
        assertThat(adminMenu.getMenuOptions().get(0).programName()).isEqualTo("COUSR00C");
        assertThat(adminMenu.getMenuOptions().get(3).programName()).isEqualTo("COUSR03C");
    }

    @Test
    void adminMenuResolvesValidSelectionForAdmin() {
        MenuOption option = adminMenu.resolveOption("3", UserType.ADMIN);
        assertThat(option.programName()).isEqualTo("COUSR02C");
    }

    @Test
    void adminMenuRejectsNonAdminCaller() {
        assertThatThrownBy(() -> adminMenu.resolveOption("1", UserType.USER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No access - Admin Only option...");
    }

    @Test
    void adminMenuRejectsInvalidSelectionForAdmin() {
        assertThatThrownBy(() -> adminMenu.resolveOption("9", UserType.ADMIN))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Please enter a valid option number...");
        assertThatThrownBy(() -> adminMenu.resolveOption(" ", UserType.ADMIN))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> adminMenu.resolveOption("x", UserType.ADMIN))
                .isInstanceOf(ValidationException.class);
    }
}
