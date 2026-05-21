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
 * Result DTO for {@link AdminMenuService#dispatch(AdminMenuRequest)} — the Java
 * replacement for the {@code COADM1AO} BMS-mapped output record emitted by
 * {@code app/cbl/COADM01C.cbl} (TRANID {@code CA00}). Encodes either a
 * <em>success</em> outcome carrying the next-route identifier (consumed by the
 * controller layer to dispatch the chosen admin sub-program) or a
 * <em>failure</em> outcome carrying the COBOL-equivalent reject message
 * verbatim.
 *
 * <h2>COBOL Provenance — COADM01C.cbl</h2>
 *
 * <p>The success outcome corresponds to the COBOL
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))} dispatch
 * (lines 142–145): the next-route identifier on this DTO is the Java
 * equivalent of the COBOL program name the option resolves to. The failure
 * outcome corresponds to the COBOL {@code MOVE 'Please enter a valid option
 * number...' TO WS-MESSAGE} reject path (lines 131–134) plus the
 * Java-migration-added authorisation and empty-option reject paths
 * (see {@link AdminMenuService} reject message constants).
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code success} — {@code true} when the option resolved to a valid
 *       route, {@code false} otherwise.</li>
 *   <li>{@code nextRoute} — the route identifier (e.g.
 *       {@code "USER_LIST"}, {@code "USER_DELETE"}) the controller layer
 *       interprets; non-{@code null} only when {@link #isSuccess()} returns
 *       {@code true}.</li>
 *   <li>{@code message} — the reject message (e.g.
 *       {@code "You are not authorized for Admin functions..."}); non-{@code null}
 *       only when {@link #isSuccess()} returns {@code false}.</li>
 * </ul>
 *
 * <h2>Factory Methods</h2>
 *
 * <p>Two static factory methods enforce the invariant that {@code nextRoute}
 * and {@code message} are mutually exclusive: {@link #success(String)} returns
 * a populated success outcome with a {@code null} message;
 * {@link #failure(String)} returns a populated failure outcome with a
 * {@code null} next-route. The no-args constructor is preserved for Spring MVC
 * view-model binding (if the REST controller layer ever needs to serialise
 * this object directly), but production code paths inside
 * {@link AdminMenuService} always invoke a factory method.
 *
 * <h2>Package — {@code service}</h2>
 *
 * <p>This response DTO lives alongside {@link AdminMenuService} and
 * {@link AdminMenuRequest} in {@code com.aws.carddemo.service} (matching the
 * {@link MainMenuResponse} package layout). The dispatch pair is tightly
 * coupled to the service contract and there is no controller-layer adapter
 * yet that would justify a separate package boundary.
 *
 * @see AdminMenuService
 * @see AdminMenuRequest
 */
public class AdminMenuResponse {

    /**
     * {@code true} when the option resolved to a valid route; {@code false}
     * for any reject branch (non-admin caller, empty option, invalid option,
     * out-of-range option, non-numeric option).
     *
     * <p>Tests assert via {@link #isSuccess()} ({@code .isTrue()} for happy
     * paths, {@code .isFalse()} for reject paths).
     */
    private boolean success;

    /**
     * The next-route identifier produced by the dispatcher when
     * {@link #success} is {@code true}; {@code null} when {@link #success}
     * is {@code false}.
     *
     * <p>Per {@link AdminMenuService} route constants, valid values include:
     * {@code "USER_LIST"} (option 1 → {@code COUSR00C}), {@code "USER_ADD"}
     * (option 2 → {@code COUSR01C}), {@code "USER_UPDATE"} (option 3 →
     * {@code COUSR02C}), and {@code "USER_DELETE"} (option 4 →
     * {@code COUSR03C}). The controller layer interprets these to issue the
     * appropriate next HTTP response (redirect, render, or sub-dispatch).
     */
    private String nextRoute;

    /**
     * The reject message produced by the dispatcher when {@link #success} is
     * {@code false}; {@code null} when {@link #success} is {@code true}.
     *
     * <p>Per {@link AdminMenuService} message constants, valid values include:
     * {@code "You are not authorized for Admin functions..."} (non-admin
     * caller), {@code "Please select an option..."} (empty input), and
     * {@code "Please enter a valid option number..."} (range, parse, or
     * non-numeric failures — direct port of the COADM01C reject literal).
     */
    private String message;

    /**
     * No-args constructor — preserved for Spring MVC view-model binding and
     * for any test that needs to construct a custom response shape without
     * the factory methods. Production code paths inside {@link AdminMenuService}
     * always invoke {@link #success(String)} or {@link #failure(String)} so
     * the invariant between {@link #nextRoute} and {@link #message} is
     * preserved.
     */
    public AdminMenuResponse() {
        // Intentionally empty — factory methods below populate fields.
    }

    /**
     * Builds a success response carrying the supplied next-route identifier.
     *
     * @param nextRoute the route identifier the controller layer will use to
     *                  dispatch the chosen admin sub-program (e.g.
     *                  {@code "USER_LIST"}); must not be {@code null}
     * @return a fresh {@code AdminMenuResponse} with {@code success = true},
     *         {@code nextRoute = nextRoute}, and {@code message = null}
     */
    public static AdminMenuResponse success(String nextRoute) {
        AdminMenuResponse r = new AdminMenuResponse();
        r.success = true;
        r.nextRoute = nextRoute;
        r.message = null;
        return r;
    }

    /**
     * Builds a failure response carrying the supplied reject message.
     *
     * @param message the reject message verbatim (matches COBOL message
     *                literals from {@code app/cbl/COADM01C.cbl} or their
     *                Java-migration equivalents); must not be {@code null}
     * @return a fresh {@code AdminMenuResponse} with {@code success = false},
     *         {@code nextRoute = null}, and {@code message = message}
     */
    public static AdminMenuResponse failure(String message) {
        AdminMenuResponse r = new AdminMenuResponse();
        r.success = false;
        r.nextRoute = null;
        r.message = message;
        return r;
    }

    /**
     * @return {@code true} when the option resolved to a valid route;
     *         {@code false} otherwise.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Sets the success flag — provided for completeness; production code
     * paths use {@link #success(String)} / {@link #failure(String)} factories.
     *
     * @param success the new success flag
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * @return the next-route identifier when {@link #isSuccess()} is
     *         {@code true}; {@code null} otherwise.
     */
    public String getNextRoute() {
        return nextRoute;
    }

    /**
     * Sets the next-route identifier — provided for completeness; production
     * code paths use {@link #success(String)} factory.
     *
     * @param nextRoute the new next-route identifier
     */
    public void setNextRoute(String nextRoute) {
        this.nextRoute = nextRoute;
    }

    /**
     * @return the reject message when {@link #isSuccess()} is {@code false};
     *         {@code null} otherwise.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the reject message — provided for completeness; production code
     * paths use {@link #failure(String)} factory.
     *
     * @param message the new reject message
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
