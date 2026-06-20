package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * A single CardDemo online-menu option row.
 *
 * <p>Java equivalent of one row of the COBOL menu-option tables that drive the
 * CardDemo screen-routing menus: the main-menu table
 * {@code CARDDEMO-MAIN-MENU-OPTIONS} ({@code COMEN02Y}) and the admin-menu table
 * {@code CARDDEMO-ADMIN-MENU-OPTIONS} ({@code COADM02Y}). This single record
 * unifies both tables; behavioral lineage traces to the original COBOL source at
 * commit {@code 27d6c6f} (reference only &mdash; no COBOL is copied into this
 * project).</p>
 *
 * <p>The main-menu table carries a per-row user-type column ({@code 'U'} for
 * every row), whereas the admin-menu table has no such column. Accordingly
 * {@link #userType()} is <strong>nullable</strong> and is {@code null} for
 * admin-menu options.</p>
 *
 * <p>This is a plain, immutable, JSON-serializable {@code record}: it carries no
 * JPA, persistence, or bean-validation concerns and is decoupled from the JPA
 * entity layer. It backs the menu-routing services ({@code MainMenuService},
 * {@code AdminMenuService}) and the {@code MenuResponse} contract.</p>
 *
 * @param optionNumber the menu/routing option number, {@code 1..n}
 *                     (mirrors {@code CDEMO-*-OPT-NUM}, {@code PIC 9(02)})
 * @param optionName   the human-readable option label
 *                     (mirrors {@code CDEMO-*-OPT-NAME}, {@code PIC X(35)})
 * @param programName  the legacy target program identifier, e.g. {@code COACTVWC}
 *                     (mirrors {@code CDEMO-*-OPT-PGMNAME}, {@code PIC X(08)})
 * @param userType     the user type permitted to invoke this option, or
 *                     {@code null} for admin-menu options (mirrors
 *                     {@code CDEMO-MENU-OPT-USRTYPE}, {@code PIC X(01)}; the admin
 *                     table has no user-type column)
 */
public record MenuOption(
        int optionNumber,
        String optionName,
        String programName,
        UserType userType) {
}
