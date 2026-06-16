package com.carddemo.model.dto;

import java.util.List;

/**
 * Immutable response payload for the CardDemo online menu screens.
 *
 * <p>Serialized to JSON and returned by {@code MenuController} for {@code GET /api/menu/*}, and
 * produced by {@code service.menu.MainMenuService} (main menu) and
 * {@code service.menu.AdminMenuService} (admin menu). A single response type serves both menus;
 * the producing service supplies the option list appropriate to the requested screen.</p>
 *
 * <p>The structured {@link #options()} list replaces the twelve formatted 40-character option
 * lines ({@code OPTN001I..OPTN012I}, PIC X(40)) of the {@code COMEN01} (main) and {@code COADM01}
 * (admin) BMS symbolic maps; the option content itself derives from the COBOL menu-option tables
 * {@code app/cpy/COMEN02Y.cpy} (up to ten main entries) and {@code app/cpy/COADM02Y.cpy} (four
 * admin entries). Screen chrome (titles, program name, date/time) and PF-key legends from the BMS
 * maps are intentionally omitted. Behavioral lineage is preserved by reference to source commit
 * {@code 27d6c6f}; no COBOL is copied.</p>
 *
 * @param options        the available menu options for this screen, up to ten for the main menu
 *                       and four for the admin menu
 *                       (&larr; the {@code OPTN001I..OPTN012I} display lines, content sourced from
 *                       {@code COMEN02Y}/{@code COADM02Y})
 * @param selectedOption the user's option selection echoed back, or {@code null} when none was
 *                       supplied
 *                       (&larr; {@code OPTIONI}, PIC X(2))
 * @param errorMessage   the screen-level message, or {@code null}/blank when none applies
 *                       (&larr; {@code ERRMSGI}, PIC X(78))
 */
public record MenuResponse(
        List<MenuOption> options,
        String selectedOption,
        String errorMessage) {
}
