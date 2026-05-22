/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Admin-menu response DTO.
 *
 * <p>Replaces the 3270 admin-menu screen rendered by the CICS/COBOL program
 * {@code COADM01C.cbl} (CICS transaction id {@code CA00}).  In the source,
 * {@code COADM01C} reads the {@code COADM02Y.cpy} working-storage option
 * table ({@code CDEMO-ADMIN-OPTIONS-DATA}), invokes
 * {@code POPULATE-HEADER-INFO} to copy the application title from
 * {@code COTTL01Y.cpy} (constants {@code CCDA-TITLE01} /
 * {@code CCDA-TITLE02}) and the current date/time into the BMS output buffer,
 * then iterates the option table inside {@code BUILD-MENU-OPTIONS} to
 * populate {@code OPTN001O} through {@code OPTN012O} on the
 * {@code COADM01} mapset (map {@code COADM1A}).
 *
 * <p>In the Java target, {@code MenuService.buildAdminMenu()} returns this
 * DTO via {@code GET /api/menu/admin}.  The COBOL pseudo-conversational
 * dispatch via {@code EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME)} is
 * replaced by client-side navigation: clients pick an option and the
 * embedded {@link MenuOptionDto#targetEndpoint()} provides the REST route
 * for the next screen.
 *
 * <p>This endpoint is admin-only (Spring Security
 * {@code @PreAuthorize("hasRole('ADMIN')")}).  Regular users receive
 * {@code HTTP 403 Forbidden} if they attempt to access it &mdash; the
 * server-side equivalent of the COBOL gate where the sign-on program
 * ({@code COSGN00C}) routed users with {@code SEC-USR-TYPE = 'A'} to
 * {@code COADM01C} and all other users to {@code COMEN01C}.  All filtering
 * happens at the controller layer; this DTO itself contains no role-checking
 * logic.
 *
 * <p><b>BMS field origin &mdash; mapping from {@code COADM01.bms} to this
 * DTO's components</b> (see {@code app/cpy-bms/COADM01.CPY} for the symbolic
 * map's exact byte offsets):
 * <pre>{@code
 *   BMS field    Length  COBOL output target    DTO component
 *   ----------   ------  ---------------------  -------------------------
 *   TRNNAME      X(04)   TRNNAMEO ('CA00')      -- (transaction id; not
 *                                                  surfaced; clients use
 *                                                  the URL path instead)
 *   TITLE01      X(40)   TITLE01O (CCDA-TITLE01) title (concatenated)
 *   TITLE02      X(40)   TITLE02O (CCDA-TITLE02) title (concatenated)
 *   PGMNAME      X(08)   PGMNAMEO ('COADM01C')  -- (program id; preserved
 *                                                  on individual options
 *                                                  via MenuOptionDto's
 *                                                  targetProgram, not here)
 *   CURDATE      X(08)   CURDATEO (mm/dd/yy)    -- (server emits ISO date
 *                                                  in HTTP headers instead)
 *   CURTIME      X(08)   CURTIMEO (hh:mm:ss)    -- (idem)
 *   OPTN001..012 X(40)   OPTN001O..OPTN012O     options (List<MenuOptionDto>)
 *   OPTION       X(02)   OPTIONO (echoed input) -- (request-only; client
 *                                                  sends the option number
 *                                                  as part of the next call)
 *   ERRMSG       X(78)   ERRMSGO                -- (carried in
 *                                                  ApiResponse.message or
 *                                                  exception envelope)
 * }</pre>
 *
 * <p><b>Option-table origin &mdash; {@code COADM02Y.cpy}</b> declares the
 * admin-menu working-storage table (4 entries; the {@code OCCURS 9 TIMES}
 * upper bound is reserved for future growth and is never exceeded by the
 * COBOL program because the loop terminates on
 * {@code CDEMO-ADMIN-OPT-COUNT}):
 * <pre>{@code
 *   Option  COBOL CDEMO-ADMIN-OPT-NAME             targetProgram
 *   ------  ------------------------------------   -------------
 *     01    "User List (Security)"                 COUSR00C
 *     02    "User Add (Security)"                  COUSR01C
 *     03    "User Update (Security)"               COUSR02C
 *     04    "User Delete (Security)"               COUSR03C
 * }</pre>
 * Each row maps to a {@link MenuOptionDto} in {@link #options()}.  Because
 * the admin menu is gated by {@code @PreAuthorize("hasRole('ADMIN')")} at
 * the controller, no per-option {@code userType} filtering is required &mdash;
 * every option in {@code COADM02Y.cpy} is admin-only by construction
 * (the underlying {@code USRSEC} record-maintenance flows are unavailable
 * to regular users in the original COBOL program too).
 *
 * <p><b>Relationship to {@code MainMenuDto}.</b>  This DTO is the
 * <i>gated</i> sibling to {@code MainMenuDto}.  Its shape is functionally
 * identical except that no {@code userType} field is needed at the top
 * level &mdash; the implicit user type for callers of
 * {@code GET /api/menu/admin} is always {@code 'A'} (admin).  The two DTOs
 * share {@link MenuOptionDto} as the row type so the client can render
 * either menu through a single component.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COADM01.bms} (mapset {@code COADM01},
 *       map {@code COADM1A}, 24x80 admin-menu screen)</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COADM01.CPY}
 *       ({@code COADM1AI}/{@code COADM1AO})</li>
 *   <li>Program: {@code app/cbl/COADM01C.cbl} (CICS transaction
 *       {@code CA00})</li>
 *   <li>Option Table: {@code app/cpy/COADM02Y.cpy}
 *       ({@code CARDDEMO-ADMIN-MENU-OPTIONS})</li>
 *   <li>Title constants: {@code app/cpy/COTTL01Y.cpy}
 *       ({@code CCDA-TITLE01} / {@code CCDA-TITLE02})</li>
 *   <li>Consumed by: {@code MenuService#buildAdminMenu()}</li>
 *   <li>Returned by: {@code MenuController#getAdminMenu()} via
 *       {@code GET /api/menu/admin}</li>
 * </ul>
 *
 * <p><b>Logging / PCI-DSS handling (AAP &sect;0.6.6, &sect;0.7.2):</b>
 * <ul>
 *   <li>This record carries no PCI/PII/credential material and no monetary
 *       values &mdash; the user-identity fields ({@code userId},
 *       {@code firstName}, {@code lastName}) are non-sensitive display
 *       attributes already echoed to the legacy 3270 screen, and the
 *       option list is admin-menu metadata.</li>
 *   <li>The default {@code toString()} generated by the {@code record}
 *       contract is therefore safe to log via the structured-JSON Logback
 *       appender shipped to CloudWatch Logs.</li>
 *   <li>Password material ({@code SEC-USR-PWD} from {@code CSUSR01Y.cpy})
 *       is <b>never</b> propagated into this DTO &mdash; the BCrypt hash
 *       stays in the {@code UserSecurity} JPA entity and never leaves the
 *       service layer (deliberate security upgrade per AAP &sect;0.1.1).</li>
 * </ul>
 *
 * <p>This record is immutable and response-only.  Following the same
 * convention as {@link MenuOptionDto}, it uses {@code @JsonProperty} for
 * stable JSON field naming (decoupling the Jackson contract from any future
 * Java-side rename) and {@code @Schema} for springdoc-openapi-generated
 * Swagger UI documentation at {@code /swagger-ui.html} and
 * {@code /v3/api-docs}.
 *
 * @param userId    the authenticated admin user ID (1-8 chars); maps to
 *                  {@code SEC-USR-ID PIC X(08)} from
 *                  {@code app/cpy/CSUSR01Y.cpy} (deposited in the COMMAREA
 *                  by {@code COSGN00C} before {@code XCTL} to
 *                  {@code COADM01C})
 * @param firstName the admin user's display first name (up to 20 chars);
 *                  maps to {@code SEC-USR-FNAME PIC X(20)} from
 *                  {@code app/cpy/CSUSR01Y.cpy}
 * @param lastName  the admin user's display last name (up to 20 chars);
 *                  maps to {@code SEC-USR-LNAME PIC X(20)} from
 *                  {@code app/cpy/CSUSR01Y.cpy}
 * @param options   the immutable list of admin-only menu options sourced
 *                  from {@code COADM02Y.cpy}.  Each entry is a
 *                  {@link MenuOptionDto} corresponding to one row in the
 *                  {@code CDEMO-ADMIN-OPTIONS-DATA} working-storage table;
 *                  all entries are returned because the caller has already
 *                  proven the ADMIN role via {@code @PreAuthorize}
 * @param title     the page title displayed to the client, typically the
 *                  concatenation of {@code CCDA-TITLE01} (Application
 *                  Modernization tagline) and {@code CCDA-TITLE02}
 *                  ({@code "CardDemo"}) with the screen-specific suffix
 *                  {@code " - Admin Menu"} appended; up to 40 chars to
 *                  align with the legacy {@code TITLE01}/{@code TITLE02}
 *                  field widths in {@code COADM01.bms}
 *
 * @see MenuOptionDto
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "AdminMenuDto",
        description = "Admin-menu response carrying the authenticated admin's display "
                + "identity, the list of admin-only menu options sourced from the "
                + "COBOL COADM02Y.cpy working-storage table, and the page title. "
                + "Returned by GET /api/menu/admin and is gated server-side via "
                + "Spring Security @PreAuthorize(\"hasRole('ADMIN')\") &mdash; "
                + "regular users receive HTTP 403 Forbidden.")
public record AdminMenuDto(

        @Schema(description = "Authenticated admin user ID (sourced from "
                + "SEC-USR-ID PIC X(08) in app/cpy/CSUSR01Y.cpy).",
                example = "ADMIN001",
                maxLength = 8)
        @JsonProperty("userId")
        String userId,

        @Schema(description = "Admin display first name (sourced from "
                + "SEC-USR-FNAME PIC X(20) in app/cpy/CSUSR01Y.cpy).",
                example = "Administrator",
                maxLength = 20)
        @JsonProperty("firstName")
        String firstName,

        @Schema(description = "Admin display last name (sourced from "
                + "SEC-USR-LNAME PIC X(20) in app/cpy/CSUSR01Y.cpy).",
                example = "User",
                maxLength = 20)
        @JsonProperty("lastName")
        String lastName,

        /*
         * Admin-only menu options.  All entries from COADM02Y.cpy
         * appear in this list (no further filtering is required &mdash;
         * the caller has already proven the ADMIN role via Spring
         * Security @PreAuthorize at the controller layer).
         */
        @Schema(description = "Admin-only menu options sourced from "
                + "app/cpy/COADM02Y.cpy (User List, User Add, User Update, "
                + "User Delete). Each entry is a MenuOptionDto with the "
                + "option number, label, original COBOL target program, and "
                + "REST target endpoint preserved for traceability.")
        @JsonProperty("options")
        List<MenuOptionDto> options,

        @Schema(description = "Page title displayed at the top of the menu. "
                + "Typically derived from the COBOL CCDA-TITLE01 / "
                + "CCDA-TITLE02 constants in app/cpy/COTTL01Y.cpy with the "
                + "screen-specific suffix appended.",
                example = "AWS CardDemo - Admin Menu",
                maxLength = 40)
        @JsonProperty("title")
        String title
) {
}
