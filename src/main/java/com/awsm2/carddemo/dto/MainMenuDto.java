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
 * Main-menu (regular-user) response DTO.
 *
 * <p>Replaces the 3270 main-menu screen rendered by the CICS/COBOL program
 * {@code COMEN01C.cbl} (CICS transaction id {@code CM00}).  In the source,
 * {@code COMEN01C} reads the {@code COMEN02Y.cpy} working-storage option
 * table ({@code CDEMO-MENU-OPTIONS-DATA} &mdash; 10 entries gated by
 * user-type), invokes {@code POPULATE-HEADER-INFO} to copy the application
 * title from {@code COTTL01Y.cpy} (constants {@code CCDA-TITLE01} /
 * {@code CCDA-TITLE02}) and the current date/time into the BMS output
 * buffer, then iterates the option table inside {@code BUILD-MENU-OPTIONS}
 * to populate {@code OPTN001O} through {@code OPTN012O} on the
 * {@code COMEN01} mapset (map {@code COMEN1A}).  Options whose
 * {@code CDEMO-MENU-OPT-USRTYPE} does not match the signed-on user's
 * {@code CDEMO-USRTYPE-*} flag are skipped (the legacy
 * {@code EVALUATE USER-TYPE} block in {@code COMEN01C}).
 *
 * <p>In the Java target, {@code MenuService.buildMainMenu(userType)}
 * returns this DTO via {@code GET /api/menu/main}.  The COBOL
 * pseudo-conversational dispatch via
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)} is replaced by
 * client-side navigation: clients pick an option and the embedded
 * {@link MenuOptionDto#targetEndpoint()} provides the REST route for the
 * next screen.
 *
 * <p>This endpoint is available to any authenticated user (regular
 * {@code 'U'} <i>or</i> admin {@code 'A'}).  The COBOL gate where
 * {@code COSGN00C} routed users with {@code SEC-USR-TYPE = 'A'} to
 * {@code COADM01C} and all others to {@code COMEN01C} is preserved at
 * the controller layer; an admin requesting {@code GET /api/menu/main}
 * still receives the regular-user option set (the original
 * {@code COMEN01C} also rendered correctly regardless of the requestor's
 * type, filtering only the option list).  All option filtering happens
 * server-side in {@code MenuService}; this DTO itself contains no
 * filtering or role-checking logic &mdash; the {@link #options()} list
 * arrives pre-filtered.
 *
 * <p><b>BMS field origin &mdash; mapping from {@code COMEN01.bms} to this
 * DTO's components</b> (see {@code app/cpy-bms/COMEN01.CPY} for the
 * symbolic map's exact byte offsets):
 * <pre>{@code
 *   BMS field    Length  COBOL output target    DTO component
 *   ----------   ------  ---------------------  -------------------------
 *   TRNNAME      X(04)   TRNNAMEO ('CM00')      -- (transaction id; not
 *                                                  surfaced; clients use
 *                                                  the URL path instead)
 *   TITLE01      X(40)   TITLE01O (CCDA-TITLE01) title (concatenated)
 *   TITLE02      X(40)   TITLE02O (CCDA-TITLE02) title (concatenated)
 *   PGMNAME      X(08)   PGMNAMEO ('COMEN01C')  -- (program id; preserved
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
 * <p><b>Option-table origin &mdash; {@code COMEN02Y.cpy}</b> declares the
 * main-menu working-storage table.  The {@code OCCURS 12 TIMES} upper
 * bound is reserved for future growth and is never exceeded by the
 * COBOL program because the loop terminates on
 * {@code CDEMO-MENU-OPT-COUNT = 10}:
 * <pre>{@code
 *   Option  COBOL CDEMO-MENU-OPT-NAME              targetProgram  userType
 *   ------  ------------------------------------   -------------  --------
 *     01    "Account View"                         COACTVWC       U
 *     02    "Account Update"                       COACTUPC       U
 *     03    "Credit Card List"                     COCRDLIC       U
 *     04    "Credit Card View"                     COCRDSLC       U
 *     05    "Credit Card Update"                   COCRDUPC       U
 *     06    "Transaction List"                     COTRN00C       U
 *     07    "Transaction View"                     COTRN01C       U
 *     08    "Transaction Add"                      COTRN02C       U
 *     09    "Transaction Reports"                  CORPT00C       U
 *     10    "Bill Payment"                         COBIL00C       U
 * }</pre>
 * Each row maps to a {@link MenuOptionDto} in {@link #options()}.  All
 * 10 entries are gated by {@code USER-TYPE = 'U'} in the source table,
 * so a regular user sees all 10.  An admin user (when explicitly
 * requesting the main menu rather than the admin menu) sees the same
 * 10 entries because the COBOL filter logic
 * ({@code IF CDEMO-MENU-OPT-USRTYPE = CDEMO-USRTYPE OR LOW-VALUES})
 * also admits admin requestors against {@code 'U'}-gated options.
 *
 * <p><b>Relationship to {@code AdminMenuDto}.</b>  This DTO is the
 * regular-user sibling of {@code AdminMenuDto}.  Both share
 * {@link MenuOptionDto} as the row type so a single client component can
 * render either menu.  The structural difference is the additional
 * {@link #userType()} field on this DTO: because {@code GET /api/menu/main}
 * may be invoked by either a regular user or an admin user, the server
 * echoes back the requestor's role so the client can correctly badge
 * the screen (e.g., displaying an &ldquo;Admin View&rdquo; banner when
 * an admin reaches the regular menu).  {@code AdminMenuDto} omits this
 * field because {@code GET /api/menu/admin} is gated by
 * {@code @PreAuthorize("hasRole('ADMIN')")} &mdash; the user type is
 * implicitly {@code 'A'}.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COMEN01.bms} (mapset {@code COMEN01},
 *       map {@code COMEN1A}, 24x80 main-menu screen)</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COMEN01.CPY}
 *       ({@code COMEN1AI}/{@code COMEN1AO})</li>
 *   <li>Program: {@code app/cbl/COMEN01C.cbl} (CICS transaction
 *       {@code CM00})</li>
 *   <li>Option Table: {@code app/cpy/COMEN02Y.cpy}
 *       ({@code CARDDEMO-MAIN-MENU-OPTIONS})</li>
 *   <li>Title constants: {@code app/cpy/COTTL01Y.cpy}
 *       ({@code CCDA-TITLE01} / {@code CCDA-TITLE02})</li>
 *   <li>Consumed by: {@code MenuService#buildMainMenu(String)}</li>
 *   <li>Returned by: {@code MenuController#getMainMenu()} via
 *       {@code GET /api/menu/main}</li>
 * </ul>
 *
 * <p><b>Logging / PCI-DSS handling (AAP &sect;0.6.6, &sect;0.7.2):</b>
 * <ul>
 *   <li>This record carries no PCI/PII/credential material and no
 *       monetary values &mdash; the user-identity fields
 *       ({@code userId}, {@code firstName}, {@code lastName}) are
 *       non-sensitive display attributes already echoed to the legacy
 *       3270 screen, and the option list is menu metadata.</li>
 *   <li>The default {@code toString()} generated by the {@code record}
 *       contract is therefore safe to log via the structured-JSON
 *       Logback appender shipped to CloudWatch Logs.</li>
 *   <li>Password material ({@code SEC-USR-PWD} from {@code CSUSR01Y.cpy})
 *       is <b>never</b> propagated into this DTO &mdash; the BCrypt
 *       hash stays in the {@code UserSecurity} JPA entity and never
 *       leaves the service layer (deliberate security upgrade per
 *       AAP &sect;0.1.1).</li>
 * </ul>
 *
 * <p>This record is immutable and response-only.  Following the same
 * convention as {@link MenuOptionDto} and {@code AdminMenuDto}, it uses
 * {@code @JsonProperty} for stable JSON field naming (decoupling the
 * Jackson wire contract from any future Java-side rename) and
 * {@code @Schema} for springdoc-openapi-generated Swagger UI
 * documentation at {@code /swagger-ui.html} and {@code /v3/api-docs}.
 *
 * @param userId    the authenticated user ID (1-8 chars); maps to
 *                  {@code SEC-USR-ID PIC X(08)} from
 *                  {@code app/cpy/CSUSR01Y.cpy} (deposited in the
 *                  COMMAREA by {@code COSGN00C} before {@code XCTL} to
 *                  {@code COMEN01C})
 * @param userType  the user-type flag from the COBOL
 *                  {@code SEC-USR-TYPE PIC X(01)} field &mdash;
 *                  {@code "U"} for regular user, {@code "A"} for
 *                  administrator.  Echoed back so the client can render
 *                  role-appropriate badging on the main menu
 * @param firstName the user's display first name (up to 20 chars);
 *                  maps to {@code SEC-USR-FNAME PIC X(20)} from
 *                  {@code app/cpy/CSUSR01Y.cpy}
 * @param lastName  the user's display last name (up to 20 chars);
 *                  maps to {@code SEC-USR-LNAME PIC X(20)} from
 *                  {@code app/cpy/CSUSR01Y.cpy}
 * @param options   the immutable list of menu options visible to this
 *                  user, sourced from {@code COMEN02Y.cpy} and already
 *                  filtered server-side by {@code userType} (the
 *                  COBOL filter logic was {@code IF CDEMO-MENU-OPT-USRTYPE
 *                  = CDEMO-USRTYPE OR LOW-VALUES} inside the
 *                  {@code BUILD-MENU-OPTIONS} paragraph in
 *                  {@code COMEN01C.cbl}).  Each entry is a
 *                  {@link MenuOptionDto} corresponding to one row in
 *                  the {@code CDEMO-MENU-OPTIONS-DATA} working-storage
 *                  table
 * @param title     the page title displayed to the client, typically
 *                  the concatenation of {@code CCDA-TITLE01}
 *                  (Application Modernization tagline) and
 *                  {@code CCDA-TITLE02} ({@code "CardDemo"}) with the
 *                  screen-specific suffix {@code " - Main Menu"}
 *                  appended; up to 40 chars to align with the legacy
 *                  {@code TITLE01}/{@code TITLE02} field widths in
 *                  {@code COMEN01.bms}
 *
 * @see MenuOptionDto
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "MainMenuDto",
        description = "Main-menu response carrying the authenticated user's display "
                + "identity, the list of menu options sourced from the COBOL "
                + "COMEN02Y.cpy working-storage table (already filtered server-side "
                + "by user type), and the page title. Returned by GET /api/menu/main "
                + "and available to any authenticated user (regular 'U' or admin "
                + "'A'); the option list is identical for both because every entry "
                + "in COMEN02Y.cpy is gated by user-type 'U' (regular), which the "
                + "COBOL filter also admits for admin requestors.")
public record MainMenuDto(

        @Schema(description = "Authenticated user ID (sourced from "
                + "SEC-USR-ID PIC X(08) in app/cpy/CSUSR01Y.cpy).",
                example = "USER0001",
                maxLength = 8)
        @JsonProperty("userId")
        String userId,

        @Schema(description = "User-type flag echoed from the authenticated session "
                + "(sourced from SEC-USR-TYPE PIC X(01) in app/cpy/CSUSR01Y.cpy). "
                + "'U' = regular user, 'A' = administrator. Used by the client for "
                + "role-appropriate badging; the server has already filtered the "
                + "option list by this value.",
                example = "U",
                allowableValues = {"A", "U"})
        @JsonProperty("userType")
        String userType,

        @Schema(description = "User display first name (sourced from "
                + "SEC-USR-FNAME PIC X(20) in app/cpy/CSUSR01Y.cpy).",
                example = "John",
                maxLength = 20)
        @JsonProperty("firstName")
        String firstName,

        @Schema(description = "User display last name (sourced from "
                + "SEC-USR-LNAME PIC X(20) in app/cpy/CSUSR01Y.cpy).",
                example = "Doe",
                maxLength = 20)
        @JsonProperty("lastName")
        String lastName,

        /*
         * Menu options visible to this user.  Already filtered by
         * USER-TYPE gate from COMEN02Y.cpy via MenuService.  In the
         * source program (COMEN01C.cbl, BUILD-MENU-OPTIONS paragraph)
         * the filter was implemented as
         *
         *   IF CDEMO-MENU-OPT-USRTYPE = CDEMO-USRTYPE
         *       OR CDEMO-MENU-OPT-USRTYPE = LOW-VALUES
         *       MOVE CDEMO-MENU-OPT-NAME TO OPTN(WS-IDX)O
         *   END-IF
         *
         * The Java replacement uses an equivalent stream filter inside
         * MenuService#buildMainMenu(String) before populating this list.
         */
        @Schema(description = "Menu options sourced from app/cpy/COMEN02Y.cpy "
                + "(Account View, Account Update, Credit Card List/View/Update, "
                + "Transaction List/View/Add, Transaction Reports, Bill Payment). "
                + "Already filtered server-side by the authenticated user's role; "
                + "the userType field on each MenuOptionDto is preserved for "
                + "client-side badging and parallel-run traceability against the "
                + "original COBOL behavior.")
        @JsonProperty("options")
        List<MenuOptionDto> options,

        @Schema(description = "Page title displayed at the top of the menu. "
                + "Typically derived from the COBOL CCDA-TITLE01 / "
                + "CCDA-TITLE02 constants in app/cpy/COTTL01Y.cpy with the "
                + "screen-specific suffix appended.",
                example = "AWS CardDemo - Main Menu",
                maxLength = 40)
        @JsonProperty("title")
        String title
) {
}
