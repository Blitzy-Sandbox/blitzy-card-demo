package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * A single CardDemo online menu option.
 *
 * <p>Java equivalent of one row of the COBOL menu-option tables that this record
 * unifies: the main-menu table {@code CARDDEMO-MAIN-MENU-OPTIONS}
 * ({@code app/cpy/COMEN02Y.cpy}) and the admin-menu table
 * {@code CARDDEMO-ADMIN-MENU-OPTIONS} ({@code app/cpy/COADM02Y.cpy}). Lineage is
 * preserved by reference to source repository commit {@code 27d6c6f}; the original
 * COBOL is not copied into this project.
 *
 * <p>This is an immutable data-transfer object backing the menu-routing services
 * ({@code service.menu.MainMenuService}, {@code service.menu.AdminMenuService}) and
 * the {@code MenuResponse} payload; it carries no persistence, validation, or
 * framework annotations.
 *
 * <p>{@code userType} is {@code null} for admin-menu options, because the COBOL
 * admin table has no user-type column (only the main-menu rows carry the
 * {@code CDEMO-MENU-OPT-USRTYPE} field, whose value is always {@code 'U'}).
 *
 * @param optionNumber the menu/routing selection number, 1..n
 *                     (COBOL {@code *-OPT-NUM}, {@code PIC 9(02)})
 * @param optionName   the human-readable option label, up to 35 characters
 *                     (COBOL {@code *-OPT-NAME}, {@code PIC X(35)})
 * @param programName  the legacy target program identifier, up to 8 characters
 *                     (COBOL {@code *-OPT-PGMNAME}, {@code PIC X(08)}, e.g. {@code COACTVWC})
 * @param userType     the user authorization type required for the option;
 *                     {@code null} for admin-menu options
 *                     (COBOL {@code CDEMO-MENU-OPT-USRTYPE}, {@code PIC X(01)})
 */
public record MenuOption(
        int optionNumber,
        String optionName,
        String programName,
        UserType userType) {
}
