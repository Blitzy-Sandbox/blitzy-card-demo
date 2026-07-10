package com.carddemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Immutable representation of a single selectable option on a CardDemo menu.
 *
 * <p>Each {@code MenuOption} is one element of a {@code MenuResponse} and mirrors a single
 * occurrence of a legacy COBOL menu-option table:</p>
 * <ul>
 *   <li><b>Main menu</b> &mdash; {@code CARDDEMO-MAIN-MENU-OPTIONS} / {@code CDEMO-MENU-OPT}
 *       in copybook {@code COMEN02Y} (10 active options, each carrying a user-type flag).</li>
 *   <li><b>Admin menu</b> &mdash; {@code CARDDEMO-ADMIN-MENU-OPTIONS} / {@code CDEMO-ADMIN-OPT}
 *       in copybook {@code COADM02Y} (4 options, with no user-type field).</li>
 * </ul>
 *
 * <p><b>COBOL field mapping</b> (main menu {@code CDEMO-MENU-OPT} / admin menu
 * {@code CDEMO-ADMIN-OPT}):</p>
 * <ul>
 *   <li>{@code optionNumber}  &larr; {@code *-OPT-NUM}     {@code PIC 9(02)}</li>
 *   <li>{@code optionName}    &larr; {@code *-OPT-NAME}    {@code PIC X(35)}</li>
 *   <li>{@code targetProgram} &larr; {@code *-OPT-PGMNAME} {@code PIC X(08)}</li>
 *   <li>{@code userType}      &larr; {@code CDEMO-MENU-OPT-USRTYPE} {@code PIC X(01)} (main menu only)</li>
 * </ul>
 *
 * <p>The declared widths intentionally mirror the copybook pictures so the JSON contract stays
 * compatible with the legacy fixed-width layout: {@code optionName} is capped at 40 characters
 * (wide enough for both the 35-character label and the 40-character {@code OPTNnnn} screen line)
 * and {@code targetProgram} is capped at 8 characters.</p>
 *
 * <p>{@code userType} preserves the raw legacy visibility flag ({@code "U"} for a regular user,
 * {@code "A"} for an administrator) so the service layer can filter options by role exactly as the
 * COBOL menu logic did. It is {@code null} for admin-menu options, which have no user-type field
 * in {@code COADM02Y}.</p>
 *
 * <p><b>Traceability note:</b> {@code targetProgram} deliberately keeps the original COBOL program
 * name (for example {@code COACTVWC}) rather than a modern route, maintaining a direct link back to
 * the migrated source for the traceability matrix. A future enhancement may additionally map each
 * program name to its REST route while still retaining the program name here.</p>
 *
 * <p>Instances are stateless, immutable value holders with no behaviour beyond the accessors and the
 * {@code equals}/{@code hashCode}/{@code toString} implementations generated for the record. When
 * serialized, the component names become the JSON property names
 * ({@code optionNumber}, {@code optionName}, {@code targetProgram}, {@code userType}).</p>
 *
 * @param optionNumber  the 1-based option number shown to the user
 *                      (COBOL {@code *-OPT-NUM}, {@code PIC 9(02)})
 * @param optionName    the human-readable option label
 *                      (COBOL {@code *-OPT-NAME}, {@code PIC X(35)})
 * @param targetProgram the legacy COBOL program name the option dispatches to
 *                      (COBOL {@code *-OPT-PGMNAME}, {@code PIC X(08)}; for example {@code COACTVWC})
 * @param userType      the role-visibility flag from the main menu
 *                      (COBOL {@code CDEMO-MENU-OPT-USRTYPE}, {@code PIC X(01)}, {@code "U"}/{@code "A"});
 *                      {@code null} for admin-menu options
 */
public record MenuOption(

        @Min(1)
        @Max(99)
        int optionNumber,

        @Size(max = 40)
        String optionName,

        @Size(max = 8)
        String targetProgram,

        @Size(max = 1)
        String userType) {
}
