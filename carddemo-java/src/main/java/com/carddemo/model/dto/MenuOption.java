package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * <p>Wire contract (see {@code docs/api-contracts.md} §5.8): the JSON serialized
 * to clients exposes exactly {@link #optionNumber()}, {@link #label()}, and
 * {@link #target()}. The {@link #programName()} (legacy {@code XCTL} target) and
 * {@link #userType()} (per-option admin gate) are retained as internal routing
 * metadata and are <strong>excluded from the JSON</strong> via
 * {@link JsonIgnore}: in REST the client navigates by calling {@link #target()},
 * so the legacy program identifier is not part of the published menu catalog.</p>
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
 *                     (the 2-char {@code OPTION} selector value; mirrors
 *                     {@code CDEMO-*-OPT-NUM}, {@code PIC 9(02)})
 * @param label        the human-readable option label (string of up to 40
 *                     characters; mirrors the {@code OPTN0nn X(40)} display line,
 *                     sourced from {@code CDEMO-*-OPT-NAME})
 * @param target       the REST path the option navigates to (derived; the
 *                     stateless replacement for the legacy {@code XCTL} routing)
 * @param programName  the legacy target program identifier, e.g. {@code COACTVWC}
 *                     (mirrors {@code CDEMO-*-OPT-PGMNAME}, {@code PIC X(08)});
 *                     internal routing metadata, not serialized
 * @param userType     the user type permitted to invoke this option, or
 *                     {@code null} for admin-menu options (mirrors
 *                     {@code CDEMO-MENU-OPT-USRTYPE}, {@code PIC X(01)}; the admin
 *                     table has no user-type column); internal gate, not serialized
 */
public record MenuOption(
        int optionNumber,
        String label,
        String target,
        @JsonIgnore String programName,
        @JsonIgnore UserType userType) {
}
