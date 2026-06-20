package com.carddemo.model.dto;

import java.util.List;

/**
 * Response payload for the online menu screens.
 *
 * <p>Serialized to JSON and returned by {@code controller.MenuController} for
 * {@code GET /api/menu/*}; produced by {@code service.menu.MainMenuService}
 * (main menu) and {@code service.menu.AdminMenuService} (admin menu). A single
 * response type serves both menus: the producing service supplies the
 * appropriate {@link #options() options} list (up to ten entries for the main
 * menu, four for the admin menu).</p>
 *
 * <p>The {@link #options() options} list is a structured replacement for the
 * twelve formatted option-display lines ({@code OPTN001I..OPTN012I},
 * {@code PIC X(40)}) of the {@code COMEN01} (main) and {@code COADM01} (admin)
 * BMS symbolic maps; the option content itself derives from the menu-option
 * tables {@code COMEN02Y} (main) and {@code COADM02Y} (admin). Terminal screen
 * chrome, PF-key legends, and 3270 attribute bytes are intentionally omitted.</p>
 *
 * <p>Lineage: AWS CardDemo COBOL source, commit {@code 27d6c6f}
 * ({@code app/cpy-bms/COMEN01.CPY}, {@code app/cpy-bms/COADM01.CPY}). Reference
 * only - no COBOL is copied into this project.</p>
 *
 * <p>This is a plain, immutable, JSON-serializable {@code record}: it carries no
 * JPA, persistence, or bean-validation concerns and is decoupled from the JPA
 * entity layer.</p>
 *
 * @param options        the available menu options for the rendered menu, in
 *                       display order (main menu: up to ten; admin menu: four);
 *                       a structured replacement for the {@code OPTN001I..OPTN012I}
 *                       ({@code PIC X(40)}) display lines, sourced from
 *                       {@code COMEN02Y}/{@code COADM02Y}
 * @param selectedOption the option selection echoed back to the client
 *                       (mirrors {@code OPTION}, {@code PIC X(2)}); {@code null}
 *                       when no selection applies
 * @param errorMessage   a human-readable error/status message for the screen
 *                       (mirrors {@code ERRMSG}, {@code PIC X(78)}); {@code null}
 *                       or blank when the menu rendered without error
 */
public record MenuResponse(
        List<MenuOption> options,
        String selectedOption,
        String errorMessage) {
}
