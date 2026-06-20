package com.carddemo.service.menu;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.MenuOption;
import com.carddemo.model.enums.UserType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Admin menu option-routing service for the CardDemo application.
 *
 * <p>Translated from COBOL program {@code COADM01C} and its embedded admin
 * option table {@code COADM02Y} (source commit {@code 27d6c6f}). Exposes the
 * four admin-menu options and resolves a selection to its target program
 * identifier. The admin option table has no per-option user-type column; the
 * entire menu is restricted to {@link UserType#ADMIN} callers.</p>
 */
@Service
public class AdminMenuService {

    private static final Logger log = LoggerFactory.getLogger(AdminMenuService.class);

    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option...";

    private static final List<MenuOption> ADMIN_OPTIONS = List.of(
            new MenuOption(1, "User List (Security)", "GET /api/admin/users", "COUSR00C", null),
            new MenuOption(2, "User Add (Security)", "POST /api/admin/users", "COUSR01C", null),
            new MenuOption(3, "User Update (Security)", "PUT /api/admin/users/{id}", "COUSR02C", null),
            new MenuOption(4, "User Delete (Security)", "DELETE /api/admin/users/{id}", "COUSR03C", null));

    /**
     * Returns the four admin-menu options.
     *
     * @return the immutable list of admin-menu options
     */
    public List<MenuOption> getMenuOptions() {
        return ADMIN_OPTIONS;
    }

    /**
     * Validates an admin-menu selection and resolves it to its target option.
     *
     * @param selection the raw option text supplied by the caller
     * @param userType  the caller's user type; must be {@link UserType#ADMIN}
     * @return the resolved option; its {@code programName()} is the routing target
     * @throws ValidationException if the caller is not an admin or the selection is invalid
     */
    public MenuOption resolveOption(String selection, UserType userType) {
        if (userType != UserType.ADMIN) {
            throw new ValidationException(ADMIN_ONLY_MESSAGE);
        }
        int option = parseOption(selection);
        MenuOption selected = ADMIN_OPTIONS.get(option - 1);
        log.debug("Admin menu option {} resolved to program {}", option, selected.programName());
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
        if (option < 1 || option > ADMIN_OPTIONS.size()) {
            throw new ValidationException(INVALID_OPTION_MESSAGE);
        }
        return option;
    }
}
