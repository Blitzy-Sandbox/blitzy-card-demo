package com.carddemo.dto;

import java.util.List;

/**
 * Immutable response payload describing a role-appropriate CardDemo menu.
 *
 * <p>{@code MenuResponse} is the JSON contract returned by the menu endpoints that replace the
 * legacy 3270/BMS menu screens:</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; transaction {@code CM00}, program {@code COMEN01C}, screen shell
 *       {@code COMEN01}; the ten selectable options come from copybook {@code COMEN02Y}
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS}, {@code CDEMO-MENU-OPT-COUNT = 10}).</li>
 *   <li><b>Admin menu</b> &mdash; transaction {@code CA00}, program {@code COADM01C}, screen shell
 *       {@code COADM01}; the four selectable options come from copybook {@code COADM02Y}
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, {@code CDEMO-ADMIN-OPT-COUNT = 4}).</li>
 * </ul>
 *
 * <p><b>Stateless carrier.</b> The pseudo-conversational CICS {@code COMMAREA} that the original
 * programs used to remember screen state is not reproduced; this DTO carries no COMMAREA fields.
 * Role filtering is performed by the service layer, which reproduces the COBOL visibility rule
 * ({@code CDEMO-MENU-OPT-USRTYPE}: admin-only options are hidden from non-admin users) and passes an
 * already-filtered list here. This record simply transports that decision and never mutates or
 * re-orders it.</p>
 *
 * <p><b>Option ordering.</b> The {@code options} list preserves the exact 1..N ordering declared in
 * {@code COMEN02Y} / {@code COADM02Y}. Callers must supply options in menu order; the record keeps
 * that order intact.</p>
 *
 * <p><b>Immutability.</b> The compact canonical constructor null-guards {@code options} (a
 * {@code null} argument becomes an empty list) and stores an unmodifiable defensive copy via
 * {@link java.util.List#copyOf(java.util.Collection)}, so an instance can never be mutated through
 * the reference passed to the constructor nor through the {@link #options()} accessor. As with
 * {@code List.copyOf}, the supplied list must not contain {@code null} elements.</p>
 *
 * <p>When serialized, the component names become the JSON property names, yielding a
 * {@code {"menuType", "title", "options"[]}} document whose {@code options} elements are
 * {@link MenuOption} objects.</p>
 *
 * @param menuType the menu discriminator selecting the option set &mdash; {@code "MAIN"} for the
 *                 main menu ({@code COMEN01} / {@code CM00}) or {@code "ADMIN"} for the admin menu
 *                 ({@code COADM01} / {@code CA00})
 * @param title    the screen title shown at the top of the menu (legacy BMS {@code TITLE01I} /
 *                 {@code TITLE02I}, {@code PIC X(40)}; at most 40 characters)
 * @param options  the ordered, role-filtered list of selectable menu options
 *                 (from {@code COMEN02Y} / {@code COADM02Y}); never {@code null} after construction
 *                 &mdash; a {@code null} argument is normalized to an empty, unmodifiable list
 */
public record MenuResponse(String menuType, String title, List<MenuOption> options) {

    /**
     * Canonical constructor that normalizes {@code options} to a non-null, unmodifiable copy.
     *
     * <p>A {@code null} {@code options} argument is replaced with an empty immutable list
     * ({@link java.util.List#of()}); a non-null argument is copied defensively with
     * {@link java.util.List#copyOf(java.util.Collection)} to guarantee the response is immutable and
     * insulated from later changes to the caller's list.</p>
     */
    public MenuResponse {
        options = (options == null) ? List.of() : List.copyOf(options);
    }
}
