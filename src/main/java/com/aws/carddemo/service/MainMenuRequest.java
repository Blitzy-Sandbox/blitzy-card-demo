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
package com.aws.carddemo.service;

/**
 * Mutable POJO request — the Java replacement for the {@code COMEN1AI} BMS-mapped
 * input record carrying {@code OPTIONI} (user-selected menu option) and the
 * caller's user-type code from {@code app/bms/COMEN01.bms} as read by
 * {@code app/cbl/COMEN01C.cbl} (TRANID {@code CM00}, the regular-user main menu).
 *
 * <h2>COBOL Provenance — COMEN01C.cbl</h2>
 *
 * <p>The {@code RECEIVE-MENU-SCREEN} paragraph (lines ~199–207) populates
 * {@code OPTIONI OF COMEN1AI} with the keystroke that the user typed into the
 * Option field. The {@code PROCESS-ENTER-KEY} paragraph (lines ~115–165) then
 * trims trailing spaces, replaces spaces with {@code '0'}, and moves the value
 * to {@code WS-OPTION PIC 9(02)} for numeric validation. This DTO carries the
 * raw user-typed string so the Java service can perform equivalent validation
 * without re-implementing CICS field-receive semantics in the test layer.
 *
 * <p>The {@code callerUserType} field carries the {@code SEC-USR-TYPE} value
 * for the authenticated user from the session context. COBOL fetches this via
 * the {@code CDEMO-USRTYP-USER} / {@code CDEMO-USRTYP-ADMIN} 88-level switch on
 * {@code CARDDEMO-COMMAREA} (COCOM01Y copybook); the Java service receives it
 * as an explicit field on the request DTO. In the regular-user main menu, this
 * field gates the {@code 'No access - Admin Only option...'} reject branch
 * (COMEN01C lines 136–143) — although all current COMEN02Y main menu entries
 * are marked {@code 'U'} (available to user) so this reject branch is dormant
 * in the live ruleset.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code callerUserType} — {@code "U"} (regular user, COBOL
 *       {@code CDEMO-USRTYP-USER}) or {@code "A"} (admin, COBOL
 *       {@code CDEMO-USRTYP-ADMIN}); single-character per
 *       {@code SEC-USR-TYPE PIC X(01)} in {@code app/cpy/CSUSR01Y.cpy}.</li>
 *   <li>{@code option} — raw, untrimmed user-typed option string (COBOL
 *       {@code OPTIONI OF COMEN1AI PIC X(02)}). Production service performs
 *       null-check, blank-check, numeric-parse, and range validation against
 *       {@link MainMenuService}.</li>
 * </ul>
 *
 * <h2>Mutable vs Immutable</h2>
 *
 * <p>This DTO is a mutable POJO with explicit getters and setters (rather than
 * the Java {@code record} idiom used by the authentication DTOs at
 * {@code com.aws.carddemo.dto.auth}). The setter-based shape allows the
 * controller layer to populate the request incrementally from Spring MVC
 * {@code @ModelAttribute} binding, matching Spring's default Java-Bean form
 * binding without requiring a custom converter. Tests construct via
 * no-args constructor and call setters — see
 * {@code MainMenuServiceTest.RegularUserDispatch}.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This dispatcher-DTO lives alongside {@link MainMenuService} in
 * {@code com.aws.carddemo.service} rather than under a {@code dto/menu}
 * subpackage. The menu dispatcher's request/response shape is tightly coupled
 * to the service contract (no controller-layer adapter exists for the menu
 * flow yet — the BMS-to-REST migration is staged for a later phase). Keeping
 * the DTO in the service package avoids a premature {@code dto/menu}
 * subpackage with only two members.
 *
 * @see MainMenuService
 * @see MainMenuResponse
 */
public class MainMenuRequest {

    /**
     * Caller's user-type code — {@code "U"} for regular users (COBOL
     * {@code CDEMO-USRTYP-USER}) or {@code "A"} for admin users (COBOL
     * {@code CDEMO-USRTYP-ADMIN}). Matches the {@code SEC-USR-TYPE PIC X(01)}
     * field from {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>The regular-user main menu (COMEN01C, this service) honours this
     * field for the {@code 'No access - Admin Only option...'} reject path
     * (COMEN01C lines 136–143). All current COMEN02Y main menu entries are
     * marked {@code 'U'} so this branch is dormant in the live ruleset — but
     * the field is carried through so the production service preserves the
     * COBOL workflow for future admin-only entries.
     */
    private String callerUserType;

    /**
     * Raw user-typed option string — the COBOL {@code OPTIONI OF COMEN1AI
     * PIC X(02)} field, untrimmed and unparsed. The production service
     * performs the equivalent of COBOL's trim + zero-fill + numeric-parse +
     * range-validation cascade before dispatch.
     *
     * <p>Tests in {@code MainMenuServiceTest} drive this field with:
     * <ul>
     *   <li>numeric strings {@code "1"} through {@code "10"} — the 10 valid
     *       options per {@code app/cpy/COMEN02Y.cpy}</li>
     *   <li>{@code ""} — empty input (Java migration distinguishes empty from
     *       invalid for clearer UX; see {@link MainMenuService} reject
     *       message constants)</li>
     *   <li>{@code "0"}, {@code "11"}, {@code "99"}, {@code "-1"} —
     *       out-of-range numeric inputs</li>
     *   <li>{@code "ABC"} — non-numeric input</li>
     * </ul>
     */
    private String option;

    /**
     * No-args constructor — required for Spring MVC {@code @ModelAttribute}
     * binding (the controller layer reflectively instantiates this DTO and
     * populates fields via the setters below). Tests in
     * {@code MainMenuServiceTest} also use this constructor followed by setter
     * calls.
     */
    public MainMenuRequest() {
        // Intentionally empty — Spring MVC and tests populate via setters.
    }

    /**
     * @return the caller's user-type code ({@code "U"} or {@code "A"}); may be
     *         {@code null} if the request was constructed without a user-type
     *         (in which case the production service falls through the
     *         admin-only check because the COBOL {@code CDEMO-USRTYP-USER}
     *         88-level test evaluates to {@code false}).
     */
    public String getCallerUserType() {
        return callerUserType;
    }

    /**
     * Sets the caller's user-type code.
     *
     * @param callerUserType {@code "U"} (regular user) or {@code "A"} (admin);
     *                       any other value is treated by the production
     *                       service as "not a regular user", short-circuiting
     *                       the admin-only reject branch (defence in depth —
     *                       the authentication service is responsible for
     *                       producing only {@code "U"} or {@code "A"}).
     */
    public void setCallerUserType(String callerUserType) {
        this.callerUserType = callerUserType;
    }

    /**
     * @return the raw user-typed option string; may be {@code null} or contain
     *         whitespace. The production service validates this value via the
     *         {@link MainMenuService} dispatcher cascade.
     */
    public String getOption() {
        return option;
    }

    /**
     * Sets the raw user-typed option string.
     *
     * @param option the option string as received from the input layer
     *               (controller, test, or BMS-compatibility shim); may be
     *               {@code null}, empty, whitespace, numeric in range,
     *               numeric out of range, or non-numeric.
     */
    public void setOption(String option) {
        this.option = option;
    }
}
