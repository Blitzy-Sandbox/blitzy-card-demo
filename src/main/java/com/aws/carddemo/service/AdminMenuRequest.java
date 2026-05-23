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
 * Request DTO for {@link AdminMenuService#dispatch(AdminMenuRequest)} — the Java
 * replacement for the {@code COADM1AI} BMS-mapped input record consumed by
 * {@code app/cbl/COADM01C.cbl} (TRANID {@code CA00}). Carries the two fields the
 * COBOL dispatcher reads from the BMS map: the caller user-type code
 * (the COBOL {@code CDEMO-USER-TYPE} field of {@code CARDDEMO-COMMAREA}) and
 * the raw user-typed option string (the COBOL {@code OPTIONI OF COADM1AI}
 * field, {@code PIC X(02)}).
 *
 * <h2>COBOL Provenance — COADM01C.cbl</h2>
 *
 * <p>{@code COADM01C} reads {@code OPTIONI OF COADM1AI} (a {@code PIC X(02)}
 * field declared in the COBOL BMS mapset) and the {@code CDEMO-USER-TYPE}
 * field from {@code CARDDEMO-COMMAREA} (see {@code app/cpy/COCOM01Y.cpy}
 * lines 26–28 — 88-level conditions {@code CDEMO-USRTYP-ADMIN VALUE 'A'}
 * and {@code CDEMO-USRTYP-USER VALUE 'U'}). The Java migration replaces
 * the BMS receive + commarea access with a single immutable request DTO
 * populated by the controller layer (or by tests directly via the setter
 * pair).
 *
 * <h2>Authorization Note</h2>
 *
 * <p>{@code COADM01C} is the admin-only menu dispatcher. The COBOL
 * source-of-truth (the {@code RETURN-TO-SIGNON-SCREEN} paragraph at lines
 * ~160–167) inherits the user-type assumption from {@code COSGN00C} — the
 * sign-on program only XCTLs to {@code COADM01C} when {@code CDEMO-USRTYP-ADMIN}
 * is true. The Java migration tightens this implicit contract by requiring
 * the dispatcher itself to re-verify the user-type so the service is safe
 * to invoke from any caller (REST controller, test, batch initiator).
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This DTO lives alongside {@link AdminMenuService} and
 * {@link AdminMenuResponse} in {@code com.aws.carddemo.service} (matching
 * the {@link MainMenuService} / {@link MainMenuRequest} / {@link MainMenuResponse}
 * package layout). The dispatch pair is tightly coupled to the service contract
 * and there is no controller-layer adapter yet that would justify a separate
 * package boundary.
 *
 * @see AdminMenuService
 * @see AdminMenuResponse
 */
public class AdminMenuRequest {

    /**
     * The caller's user-type code — the COBOL {@code CDEMO-USER-TYPE
     * PIC X(01)} field from {@code app/cpy/COCOM01Y.cpy}, populated upstream
     * by {@code COSGN00C} (or its Java migration {@code AuthenticationService}).
     *
     * <p>Per {@code COCOM01Y.cpy} lines 26–28 the recognised values are:
     * <ul>
     *   <li>{@code "A"} — {@code CDEMO-USRTYP-ADMIN}; admin users are authorised
     *       to dispatch any of the 4 admin menu options (User List, User Add,
     *       User Update, User Delete).</li>
     *   <li>{@code "U"} — {@code CDEMO-USRTYP-USER}; regular users are NOT
     *       authorised for the admin menu and the dispatcher returns the
     *       authorisation reject path.</li>
     * </ul>
     *
     * <p>Any other value (including {@code null}, whitespace, or unknown
     * letters such as {@code "X"}) is treated as "not an admin", short-circuiting
     * the option-dispatch logic with the authorisation reject — defence in
     * depth against an upstream authentication service that fails to populate
     * the field correctly.
     */
    private String callerUserType;

    /**
     * Raw user-typed option string — the COBOL {@code OPTIONI OF COADM1AI
     * PIC X(02)} field, untrimmed and unparsed. The production
     * {@link AdminMenuService} performs the equivalent of COBOL's trim +
     * zero-fill + numeric-parse + range-validation cascade (the
     * {@code PROCESS-ENTER-KEY} paragraph at COADM01C lines 115–155)
     * before dispatch.
     *
     * <p>Tests in {@code AdminMenuServiceTest} drive this field with:
     * <ul>
     *   <li>numeric strings {@code "1"} through {@code "4"} — the 4 valid
     *       options per {@code app/cpy/COADM02Y.cpy}</li>
     *   <li>{@code ""} — empty input (Java migration distinguishes empty from
     *       invalid for clearer UX; see {@link AdminMenuService} reject
     *       message constants)</li>
     *   <li>{@code "0"}, {@code "5"}, {@code "99"}, {@code "-1"} —
     *       out-of-range numeric inputs</li>
     *   <li>{@code "ABC"} — non-numeric input</li>
     * </ul>
     */
    private String option;

    /**
     * No-args constructor — required for Spring MVC {@code @ModelAttribute}
     * binding (the controller layer reflectively instantiates this DTO and
     * populates fields via the setters below). Tests in
     * {@code AdminMenuServiceTest} also use this constructor followed by setter
     * calls (matching the {@link MainMenuRequest} pattern).
     */
    public AdminMenuRequest() {
        // Intentionally empty — Spring MVC and tests populate via setters.
    }

    /**
     * @return the caller's user-type code ({@code "A"} or {@code "U"}); may be
     *         {@code null} if the request was constructed without a user-type
     *         (in which case the production service rejects the dispatch
     *         because the COBOL {@code CDEMO-USRTYP-ADMIN} 88-level test
     *         evaluates to {@code false}).
     */
    public String getCallerUserType() {
        return callerUserType;
    }

    /**
     * Sets the caller's user-type code.
     *
     * @param callerUserType {@code "A"} (admin) or {@code "U"} (regular user);
     *                       any other value (including {@code null}) is treated
     *                       by the production service as "not an admin" and
     *                       triggers the authorisation reject path.
     */
    public void setCallerUserType(String callerUserType) {
        this.callerUserType = callerUserType;
    }

    /**
     * @return the raw user-typed option string; may be {@code null} or contain
     *         whitespace. The production service validates this value via the
     *         {@link AdminMenuService} dispatcher cascade.
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
