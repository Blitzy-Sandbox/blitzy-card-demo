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
 *
 * <p>The service is a stateless singleton: the option table is an immutable
 * static list and no conversational ({@code COMMAREA}) state is retained
 * between invocations.</p>
 */
@Service
public class AdminMenuService {

    /** SLF4J logger for diagnostic tracing of resolved admin-menu selections. */
    private static final Logger log = LoggerFactory.getLogger(AdminMenuService.class);

    /** Error message shown for a blank, non-numeric, or out-of-range selection. */
    private static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /** Error message shown when a non-admin caller attempts to use the admin menu. */
    private static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option...";

    /**
     * The four admin-menu options, in display order, mirroring the
     * {@code COADM02Y} table. Each option carries a {@code null} user type
     * because the COBOL admin table has no per-option user-type column.
     */
    private static final List<MenuOption> ADMIN_OPTIONS = List.of(
            new MenuOption(1, "User List (Security)", "COUSR00C", null),
            new MenuOption(2, "User Add (Security)", "COUSR01C", null),
            new MenuOption(3, "User Update (Security)", "COUSR02C", null),
            new MenuOption(4, "User Delete (Security)", "COUSR03C", null));

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

    /**
     * Parses and bounds-checks a raw admin-menu selection.
     *
     * <p>Mirrors the {@code COADM01C} {@code PROCESS-ENTER-KEY} edit: a blank
     * selection, a non-numeric value, or a value outside {@code 1..4} is
     * rejected with {@link #INVALID_OPTION_MESSAGE}.</p>
     *
     * @param selection the raw option text supplied by the caller
     * @return the validated option number in the range {@code 1..4}
     * @throws ValidationException if the selection is blank, non-numeric, or out of range
     */
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
