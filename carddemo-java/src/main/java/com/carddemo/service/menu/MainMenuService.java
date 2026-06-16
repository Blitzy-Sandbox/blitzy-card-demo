package com.carddemo.service.menu;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Main menu option-routing service for the CardDemo application.
 *
 * <p>Translated from COBOL program {@code COMEN01C} and its embedded menu
 * option table {@code COMEN02Y} (source commit {@code 27d6c6f}). Exposes the
 * ten main-menu options and resolves a caller selection to its target program
 * identifier, preserving the {@code PROCESS-ENTER-KEY} evaluation order.</p>
 *
 * <p>The service is stateless and thread-safe: the option table is an immutable
 * static constant and every call to {@link #resolveOption(String, UserType)}
 * receives its inputs as arguments, so the COBOL {@code CARDDEMO-COMMAREA}
 * conversational state is not held on the server.</p>
 */
@Service
public class MainMenuService {

    private static final Logger log = LoggerFactory.getLogger(MainMenuService.class);

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option...";

    private static final List<MenuOption> MENU_OPTIONS = List.of(
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

    /**
     * Returns the ten main-menu options.
     *
     * @return the immutable list of main-menu options
     */
    public List<MenuOption> getMenuOptions() {
        return MENU_OPTIONS;
    }

    /**
     * Validates a menu selection and resolves it to its target option.
     *
     * @param selection the raw option text supplied by the caller
     * @param userType  the caller's user type, used for admin-only gating
     * @return the resolved option; its {@code programName()} is the routing target
     * @throws ValidationException if the selection is invalid or admin-only
     */
    public MenuOption resolveOption(String selection, UserType userType) {
        int option = parseOption(selection);
        MenuOption selected = MENU_OPTIONS.get(option - 1);
        if (userType == UserType.USER && selected.userType() == UserType.ADMIN) {
            throw new ValidationException(ADMIN_ONLY_MESSAGE);
        }
        log.debug("Main menu option {} resolved to program {}", option, selected.programName());
        return selected;
    }

    private int parseOption(String selection) {
        String normalized = (selection == null) ? "" : selection.trim();
        if (normalized.isEmpty()) {
            throw new ValidationException(INVALID_OPTION_MESSAGE);
        }
        int option;
        try {
            option = Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            throw new ValidationException(INVALID_OPTION_MESSAGE, ex);
        }
        if (option < 1 || option > MENU_OPTIONS.size()) {
            throw new ValidationException(INVALID_OPTION_MESSAGE);
        }
        return option;
    }
}
