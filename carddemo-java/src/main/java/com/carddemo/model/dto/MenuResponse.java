package com.carddemo.model.dto;

import java.util.List;

/**
 * Response payload for the online menu screens.
 *
 * <p>Serialized to JSON and returned by {@code controller.MenuController} for
 * {@code GET /api/menu/*}; produced by {@code service.menu.MainMenuService}
 * (main menu) and {@code service.menu.AdminMenuService} (admin menu). A single
 * response type serves both menus: {@link #menuType()} distinguishes them
 * ({@code "MAIN"} or {@code "ADMIN"}) and the producing service supplies the
 * appropriate {@link #options() options} list (ten entries for the main menu,
 * four for the admin menu).</p>
 *
 * <p>Contract: see {@code docs/api-contracts.md} §5.8. In REST the menu is a
 * read-only catalog of navigable options; routing is performed by the client
 * calling the corresponding endpoint, so there is no server-side {@code OPTION}
 * dispatch echoed back and no error field on this success contract (validation
 * failures surface via the shared {@code GlobalExceptionHandler} envelope).</p>
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
 * @param menuType the menu discriminator: {@code "MAIN"} for the main menu or
 *                 {@code "ADMIN"} for the admin menu
 * @param options  the available menu options for the rendered menu, in display
 *                 order (main menu: ten; admin menu: four); a structured
 *                 replacement for the {@code OPTN001I..OPTN012I} ({@code PIC X(40)})
 *                 display lines, sourced from {@code COMEN02Y}/{@code COADM02Y}
 */
public record MenuResponse(
        String menuType,
        List<MenuOption> options) {
}
