/*
 * ******************************************************************
 * Program     : MenuController.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 REST Controller
 * Function    : Main-menu and admin-menu option retrieval.
 * Source      : app/csd/CARDDEMO.CSD transactions CM00, CA00
 *               -> app/cbl/COMEN01C.cbl (282 lines), mapset COMEN01
 *               -> app/cbl/COADM01C.cbl (268 lines), mapset COADM01
 *               -> app/cpy/COMEN02Y.cpy (10 options), app/cpy/COADM02Y.cpy (4 options) @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.controller;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.enums.UserType;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;

/**
 * The two menu retrieval operations of the CardDemo REST surface: the Java replacement for CICS
 * transactions {@code CM00} and {@code CA00} and the two programs they front.
 *
 * <p>Every legacy claim below cites a path and a line or line range in the frozen corpus under
 * {@code app/}, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the
 * {@code Source} lines in the file header record. The anchor is stated once here rather than
 * repeated on every citation.</p>
 *
 * <h2>1. What it does</h2>
 *
 * <p>Two CSD transactions, two COBOL programs, two mapsets and two independent option tables:</p>
 *
 * <table>
 *   <caption>The two sourced transactions this controller replaces</caption>
 *   <tr><th>Operation</th><th>Transaction</th><th>Program</th><th>Mapset</th><th>Option table</th></tr>
 *   <tr>
 *     <td>{@link #getMainMenu(Authentication)}</td>
 *     <td>{@code CM00} - {@code DEFINE TRANSACTION(CM00)} at {@code app/csd/CARDDEMO.CSD:L399}</td>
 *     <td>{@code COMEN01C} - {@code PROGRAM(COMEN01C)} at {@code :L400},
 *         {@code DEFINE PROGRAM(COMEN01C)} at {@code :L235}, 282 lines, 7 paragraph labels</td>
 *     <td>{@code COMEN01} - {@code DEFINE MAPSET(COMEN01)} at {@code :L133}</td>
 *     <td>{@code app/cpy/COMEN02Y.cpy} - <strong>10</strong> populated options</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #getAdminMenu(Authentication)}</td>
 *     <td>{@code CA00} - {@code DEFINE TRANSACTION(CA00)} at {@code app/csd/CARDDEMO.CSD:L327}</td>
 *     <td>{@code COADM01C} - {@code PROGRAM(COADM01C)} at {@code :L328},
 *         {@code DEFINE PROGRAM(COADM01C)} at {@code :L189}, 268 lines, 7 paragraph labels</td>
 *     <td>{@code COADM01} - {@code DEFINE MAPSET(COADM01)} at {@code :L110}</td>
 *     <td>{@code app/cpy/COADM02Y.cpy} - <strong>4</strong> populated options</td>
 *   </tr>
 * </table>
 *
 * <p>Each operation renders one menu as JSON. Neither dispatches a selected option:
 * {@code docs/technical-specifications.md:1177} records this file's contract as "Menu retrieval;
 * option dispatch replaced by URL navigation", so the two {@code EXEC CICS XCTL PROGRAM(...)} sites
 * at {@code app/cbl/COMEN01C.cbl:L153} and {@code app/cbl/COADM01C.cbl:L143} become URL-based
 * navigation performed by the client. There is deliberately no dispatch endpoint, no option-selection
 * endpoint and no server-side transfer of control anywhere in this class. See section 6.</p>
 *
 * <p><strong>Side effects: none beyond the HTTP response.</strong> No persistence, no messaging, no
 * object storage, no session creation and no mutation of any injected, static or ambient state. Both
 * fields are final references to stateless singleton beans, so this controller is immutable after
 * construction and therefore thread safe. The only observable effect other than the returned value is
 * log output, at {@code DEBUG} for the accepted paths and {@code WARN} or {@code ERROR} for the
 * refused ones.</p>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Build with the repository's pinned Maven wrapper, which resolves Maven 3.9.11 and compiles at
 * release 25 under {@code -Xlint:all -Werror} with {@code failOnWarning} enabled:</p>
 *
 * <pre>
 * ./mvnw -B -ntp clean compile
 * ./mvnw -B -ntp test        # Surefire 3.5.4, unit tree only
 * ./mvnw -B -ntp verify      # adds Failsafe 3.5.4 and the JaCoCo 80% line gate
 * </pre>
 *
 * <p>Where no JDK is installed on the host, the identical build runs in the pinned container image:</p>
 *
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -B -DskipTests compile
 * </pre>
 *
 * <p>Running it means running the application, which serves both operations under the base path
 * {@value #BASE_PATH}. Neither operation touches a database, a queue or a cloud emulator, so both are
 * exercised most cheaply by a Spring MVC standalone test over a constructor-injected instance - no
 * application context, no PostgreSQL and no LocalStack required. Tests belong in
 * {@code src/test/java/com/cardemo/unit} and never in this package.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p><strong>This class reads no property, no profile and no classpath resource.</strong> It declares
 * no {@code @Value}, no {@code Environment} lookup and no {@code System.getenv} call, so there is no
 * key here that can be misconfigured or mistyped. The two paths and the two authority names below are
 * compile-time constants, and the option counts are fixed by the source copybooks rather than by
 * configuration.</p>
 *
 * <p>Three settings owned elsewhere nonetheless govern the observable behaviour of both operations,
 * and are named here so that a diagnosis starts in the right file:</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} declares the authorisation rules for {@value #BASE_PATH} centrally,
 *       together with {@code SessionCreationPolicy.STATELESS}. Nothing in this class contradicts
 *       those rules, and no method-level security annotation is declared here.</li>
 *   <li>{@code server.port}, defaulting to 8080, fixes the port both operations answer on.</li>
 *   <li>{@code carddemo.security.jwt.issuer} and {@code carddemo.security.jwt.expiration-minutes}
 *       govern the bearer token that both operations require. The lifetime is expressed in minutes and
 *       defaults to 30; the signing key, bound from {@code JWT_SIGNING_KEY}, has no default at all,
 *       by design.</li>
 *   </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <table>
 *   <caption>Every status either operation can produce, and what to do about it</caption>
 *   <tr><th>Status</th><th>Cause and remedy</th></tr>
 *   <tr>
 *     <td>{@code 200 OK}</td>
 *     <td>The menu payload: {@code menuType}, {@code options} and {@code optionCount}. The count is
 *         10 for {@value #MAIN_MENU_PATH} and 4 for {@value #ADMIN_MENU_PATH}. A different count means
 *         the option table was bound to its {@code OCCURS} capacity rather than to its {@code COUNT}
 *         field; see section 5.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 401 Unauthorized}, empty body</td>
 *     <td>No bearer token, an expired token or an anonymous authentication reached the operation. Only
 *         sign-on, transaction {@code CC00}, is unauthenticated. This is the stateless counterpart of
 *         {@code RETURN-TO-SIGNON-SCREEN}; see section 5. Obtain a token from the sign-on endpoint and
 *         present it as a bearer credential.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 403 Forbidden}, empty body</td>
 *     <td>The token authenticated but granted no recognised user class, or granted only
 *         {@value #USER_AUTHORITY} where {@value #ADMIN_AUTHORITY} is required. A token carrying
 *         neither authority is a token-issuing defect rather than operator error: check that the
 *         issuer maps {@code CDEMO-USER-TYPE} onto exactly {@value #ADMIN_AUTHORITY} or
 *         {@value #USER_AUTHORITY}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 400 Bad Request} with a problem detail</td>
 *     <td>A {@code ValidationException} surfaced. Its message is relayed byte for byte, so a legacy
 *         literal such as {@code Please enter a valid option number...} or
 *         {@code No access - Admin Only option... } arrives exactly as the 3270 screen displayed it,
 *         trailing space included. Neither retrieval operation accepts an option, so this status is
 *         reachable only if a menu service rejects the request itself.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 500 Internal Server Error} with a problem detail</td>
 *     <td>A {@code CardDemoException} surfaced, or an unexpected runtime failure was wrapped as a
 *         {@code FatalProcessingException} whose abend code {@code 999} and return code {@code 12} are
 *         logged rather than returned. The problem detail is deliberately fixed and reveals nothing;
 *         the cause is preserved on the exception and logged at {@code ERROR} with the correlation
 *         identifier, so correlate by that identifier rather than by response body.</td>
 *   </tr>
 * </table>
 *
 * <p>If a standard user sees fewer than ten main-menu options, that is the least-privilege display
 * filter in {@code MainMenuService}, not a defect in this class - although against the frozen table it
 * withholds nothing, because all ten entries of {@code app/cpy/COMEN02Y.cpy:L25-L84} carry
 * {@code 'U'}.</p>
 *
 * <h2>5. Legacy constructs with no counterpart, and why</h2>
 *
 * <p>Transformation rule 7 is binding: {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus
 * JWT claims, with no server-side session state. This class creates no session, never touches
 * {@code HttpSession}, and adds no CSRF state. Identity is taken only from the authenticated
 * principal - the subject claim carrying the upper-cased {@code CDEMO-USER-ID PIC X(08)} of
 * {@code app/cpy/COCOM01Y.cpy:L25}, and the role authority replacing
 * {@code CDEMO-USER-TYPE PIC X(01)} at {@code :L26} whose only two values are declared by
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}. It is never taken from a request body, a
 * query parameter, a header of this class's own devising or a session.</p>
 *
 * <p>The following COMMAREA fields of {@code app/cpy/COCOM01Y.cpy} have <strong>no Java
 * counterpart</strong> and appear in no request and no response of either operation:
 * {@code CDEMO-FROM-TRANID PIC X(04)} at {@code :L21}, {@code CDEMO-FROM-PROGRAM PIC X(08)} at
 * {@code :L22}, {@code CDEMO-TO-TRANID PIC X(04)} at {@code :L23},
 * {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code :L24}, {@code CDEMO-PGM-CONTEXT PIC 9(01)} at
 * {@code :L29} with {@code 88 CDEMO-PGM-ENTER VALUE 0} at {@code :L30} and
 * {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31}, {@code CDEMO-LAST-MAP PIC X(7)} at
 * {@code :L43} and {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code :L44} - both seven characters wide,
 * not eight. The pseudo-conversational enter-versus-re-enter distinction collapses into stateless
 * request handling: every request is a first entry.</p>
 *
 * <p>{@code RETURN-TO-SIGNON-SCREEN}, at {@code app/cbl/COMEN01C.cbl:L170} and
 * {@code app/cbl/COADM01C.cbl:L160}, substituted {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM}
 * whenever that field held {@code LOW-VALUES} or {@code SPACES} and then transferred control there.
 * <strong>That fallback has no Java counterpart.</strong> Its stateless equivalent is a
 * {@code 401 Unauthorized}, which drives the client back to sign-on without this class naming a
 * program, a mapset or a transaction identifier in the response.</p>
 *
 * <p>{@code app/cpy/COCOM01Y.cpy} declares no page-number field and no next-page flag, so no
 * pagination is attributed to the COMMAREA here. Neither menu is paginated at all: each option table
 * is returned whole, bounded by its own count field.</p>
 *
 * <p>The action-identifier state of {@code app/cpy/CVCRD01Y.cpy} and the procedural
 * {@code YYYY-STORE-PFKEY.} paragraph of {@code app/cpy/CSSTRPFY.cpy:L17}, whose
 * {@code EVALUATE TRUE} over {@code EIBAID} spans {@code :L21-L78}, map onto controller-level action
 * mapping: a distinct URL per action, which is exactly what the two paths below are. There is no
 * generic action-identifier parameter and no reimplemented function-key dispatcher.
 * {@code app/cpy/CSSTRPFY.cpy} is a {@code PROCEDURE DIVISION} copybook, so it yields no entity, no
 * data transfer object and no import; and {@code CCARD-LAST-PROG} at {@code app/cpy/CVCRD01Y.cpy:L20},
 * {@code CCARD-RETURN-TO-PROG} at {@code :L22}, {@code CCARD-RETURN-FLAG} at {@code :L25} and
 * {@code CCARD-FUNCTION} at {@code :L31} are commented out in the source and therefore have no
 * counterpart at all. {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are supplied by the
 * transaction monitor, are absent from this repository, and are imported nowhere.</p>
 *
 * <h2>6. Deviations from the source, with severities</h2>
 *
 * <p>Owed an entry in the planned {@code DECISION_LOG.md} under the entries named below. Nothing in this list is a
 * silent improvement.</p>
 *
 * <ul>
 *   <li><strong>Medium - the administrator menu is role-gated, which the source program is not.</strong>
 *       {@code app/cbl/COADM01C.cbl} contains no user-type test anywhere: no
 *       {@code CDEMO-USRTYP-USER} predicate, no {@code CDEMO-ADMIN-OPT-USRTYPE} field - the option
 *       table at {@code app/cpy/COADM02Y.cpy:L45-L48} declares only a number, a caption and a program
 *       name - and {@code DEFINE TRANSACTION(CA00)} carries {@code RESSEC(NO) CMDSEC(NO)} at
 *       {@code app/csd/CARDDEMO.CSD:L334}. Access to {@code CA00} was therefore a region-level
 *       concern, not a program-level one. {@link #getAdminMenu(Authentication)} requires
 *       {@value #ADMIN_AUTHORITY} because Rule 1 Clause D requires least privilege and because the
 *       four options this menu offers target {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and
 *       {@code COUSR03C} at {@code app/cpy/COADM02Y.cpy:L27}, {@code :L32}, {@code :L37} and
 *       {@code :L42} - the four user-administration programs that {@code SecurityConfig} restricts to
 *       the administrator role. The gate agrees with the central rule rather than competing with it.
 *       Decision log entry: <em>admin menu role gate</em>.</li>
 *   <li><strong>Low - the response carries the option payload only.</strong> Both menu services also
 *       compose the rendered 3270 option lines, each padded to the forty characters of
 *       {@code OPTN001I PIC X(40)}, and {@code MainMenuService} additionally resolves the sign-on
 *       program that {@code RETURN-TO-SIGNON-SCREEN} transferred to. Neither is surfaced: the padded
 *       lines are terminal rendering, and 3270 emulation is out of scope, while the sign-on program
 *       name is precisely the routing instruction this class must not emit. Both remain available on
 *       the service return values for a caller that has a legitimate need. Decision log entry:
 *       <em>menu payload without screen furniture</em>.</li>
 *   <li><strong>Low - option dispatch is not an endpoint.</strong> The selection contract of
 *       {@code PROCESS-ENTER-KEY}, at {@code app/cbl/COMEN01C.cbl:L115} and
 *       {@code app/cbl/COADM01C.cbl:L115}, is implemented in the two menu services and reached by URL
 *       navigation rather than by a server-side transfer. Consequently the option-input boundary
 *       conditions - a value below 1, a value above the count field, a non-numeric value, a blank or
 *       absent field, and a target program whose first five characters are {@code 'DUMMY'} at
 *       {@code app/cbl/COMEN01C.cbl:L146} and {@code app/cbl/COADM01C.cbl:L138} - are enforced there
 *       and not here. This class relays the resulting {@code ValidationException} byte for byte rather
 *       than restating those rules, because a second statement of them could drift from the first.
 *       Decision log entry: <em>URL navigation replaces XCTL dispatch</em>.</li>
 *   </ul>
 *
 * <h2>7. The two coming-soon messages differ, and are not unified</h2>
 *
 * <p>This is the single most important parity fact about the pair of programs, and the divergence is
 * genuine rather than an oversight:</p>
 *
 * <ul>
 *   <li><strong>Main menu.</strong> {@code app/cbl/COMEN01C.cbl:L158} moves {@code DFHGREEN} into
 *       {@code ERRMSGC OF COMEN1AO} and {@code :L159-L163} then assembles
 *       {@code STRING 'This option ' DELIMITED BY SIZE, CDEMO-MENU-OPT-NAME(WS-OPTION) DELIMITED BY
 *       SPACE, 'is coming soon ...' DELIMITED BY SIZE INTO WS-MESSAGE}. The message therefore
 *       <strong>includes the option caption</strong>, truncated at its first space by the
 *       {@code DELIMITED BY SPACE} phrase.</li>
 *   <li><strong>Admin menu.</strong> {@code app/cbl/COADM01C.cbl:L148} moves {@code DFHGREEN} into
 *       {@code ERRMSGC OF COADM1AO} and {@code :L149-L153} assembles the same {@code STRING} - except
 *       that the two option-caption lines are <strong>commented out</strong> at {@code :L150-L151}.
 *       The message therefore <strong>omits the option caption</strong> entirely and reads
 *       {@code This option is coming soon ...}, which is what
 *       {@code AdminMenuService.COMING_SOON_MESSAGE} holds.</li>
 *   </ul>
 *
 * <p><strong>The two must not be unified.</strong> Unifying them would change observable behaviour and
 * would breach the parity contract; the commented-out lines are not dead Java code, they are the
 * reason the two messages differ, and that difference is live behaviour. Decision log entry:
 * <em>divergent coming-soon messages preserved</em>.</p>
 *
 * <h2>8. Scope statements</h2>
 *
 * <p>This class declares exactly two request-mapped operations. It exposes no developer, diagnostic or
 * debug endpoint: transaction {@code CDV1} fronts {@code COCRDSEC}, whose source is
 * <strong>Not available</strong> anywhere in this repository - the name occurs only in the two resource
 * definitions at {@code app/csd/CARDDEMO.CSD:L211} and {@code :L390} - so nothing is invented for it.
 * Health, information and metrics endpoints belong to Actuator and are not aliased here. The four
 * batch-only datasets {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} are
 * absent from the CSD's eight {@code DEFINE FILE} entries and get no surface here either.</p>
 *
 * <p>Observability follows the established mechanisms rather than adding new ones.
 * {@code CorrelationIdFilter} supplies the mapped diagnostic context keys {@code correlationId},
 * {@code traceId} and {@code spanId}; this class adds none, renames none, overwrites none, removes
 * none and never clears the context, and it registers no additional Micrometer instrument. Log
 * statements carry the transaction identifier and the resolved user class only: no credential, no
 * bearer token, no password hash, no account or card identifier and no personally identifiable field
 * is ever logged or serialised by this class, in accordance with Rule 1 Clause D.</p>
 */
@RestController
@RequestMapping(MenuController.BASE_PATH)
public class MenuController {

    /**
     * The base path both operations are mounted under. Fixed by {@code docs/project-guide.md:431}, which
     * records the invocation {@code curl -s http://localhost:8080/api/menu/main} - reproduced here with the
     * header that command omits, {@code -H "Authorization: Bearer $TOKEN"}, because
     * {@code com.cardemo.config.SecurityConfig} requires either authority on {@code GET /api/menu/main} and
     * the administrator authority on {@code GET /api/menu/admin}. Without the header either path returns
     * 401, so the recorded command as written no longer reproduces the documented response.
     */
    static final String BASE_PATH = "/api/menu";

    /**
     * The main-menu path, relative to {@value #BASE_PATH}. The full path {@code /api/menu/main} is the
     * one recorded as verified at {@code docs/project-guide.md:162}.
     */
    static final String MAIN_MENU_PATH = "/main";

    /**
     * The administrator-menu path, relative to {@value #BASE_PATH}. Deliberately distinct from the
     * {@code /api/admin} prefix, which belongs to the four user-administration operations fronted by
     * transactions {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}.
     */
    static final String ADMIN_MENU_PATH = "/admin";

    /**
     * The granted authority that carries {@code CDEMO-USER-TYPE} value {@code 'A'}, declared by
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code app/cpy/COCOM01Y.cpy:L27}. The mapping from
     * the one-character code to this name is owned by the security and configuration packages, which
     * is why {@code UserType} itself exposes no authority constant.
     */
    static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * The granted authority that carries {@code CDEMO-USER-TYPE} value {@code 'U'}, declared by
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code app/cpy/COCOM01Y.cpy:L28}.
     */
    static final String USER_AUTHORITY = "ROLE_USER";

    /**
     * The CSD transaction identifier of the main menu, {@code DEFINE TRANSACTION(CM00)} at
     * {@code app/csd/CARDDEMO.CSD:L399}. Logged as the diagnostic counterpart of {@code EIBTRNID}.
     */
    private static final String MAIN_MENU_TRANSACTION_ID = "CM00";

    /**
     * The CSD transaction identifier of the administrator menu, {@code DEFINE TRANSACTION(CA00)} at
     * {@code app/csd/CARDDEMO.CSD:L327}.
     */
    private static final String ADMIN_MENU_TRANSACTION_ID = "CA00";

    /**
     * The abend culprit for the main-menu path, carrying {@code ABEND-CULPRIT PIC X(8)} of
     * {@code app/cpy/CSMSG02Y.cpy}. Exactly eight characters, matching the source program name.
     */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * The abend culprit for the administrator-menu path, carrying {@code ABEND-CULPRIT PIC X(8)}.
     * Exactly eight characters, matching the source program name.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * The abend code that {@code 9999-ABEND-PROGRAM} moved into {@code ABEND-CODE} before calling the
     * language-environment abend service, rendered from the single shared constant so that the value
     * {@code 999} is declared once in the Java tree.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /**
     * The abend reason for the main-menu path, carrying {@code ABEND-REASON PIC X(50)}. Thirty-nine
     * characters, within the declared width.
     */
    private static final String MAIN_MENU_ABEND_REASON = "MAIN MENU RETRIEVAL FAILED UNEXPECTEDLY";

    /**
     * The abend reason for the administrator-menu path, carrying {@code ABEND-REASON PIC X(50)}.
     * Forty characters, within the declared width.
     */
    private static final String ADMIN_MENU_ABEND_REASON = "ADMIN MENU RETRIEVAL FAILED UNEXPECTEDLY";

    /**
     * The abend message for the main-menu path, carrying {@code ABEND-MSG PIC X(72)}. Forty-five
     * characters, within the declared width.
     */
    private static final String MAIN_MENU_ABEND_MESSAGE = "UNEXPECTED ERROR IN CM00 MAIN MENU RETRIEVAL.";

    /**
     * The abend message for the administrator-menu path, carrying {@code ABEND-MSG PIC X(72)}.
     * Forty-six characters, within the declared width.
     */
    private static final String ADMIN_MENU_ABEND_MESSAGE = "UNEXPECTED ERROR IN CA00 ADMIN MENU RETRIEVAL.";

    /**
     * The problem-detail title for a rejected request. Deliberately generic: the byte-exact legacy
     * literal travels in the detail, not the title.
     */
    private static final String VALIDATION_PROBLEM_TITLE = "Menu request rejected";

    /**
     * The problem-detail title for a failed request.
     */
    private static final String FAILURE_PROBLEM_TITLE = "Menu retrieval failed";

    /**
     * The fixed problem detail emitted for every server-side failure. It names no class, no message,
     * no column and no value, because a Hibernate message can echo column values and a stack trace
     * discloses internal structure - the same reasoning that sets {@code server.error.include-message}
     * to {@code never}. Diagnosis proceeds from the correlation identifier in the logs.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The menu could not be retrieved. Retry the request, and quote the correlation identifier "
                    + "if the failure persists.";

    /**
     * The problem-detail property carrying the rejected field name, present only when the
     * {@code ValidationException} named one.
     */
    private static final String FIELD_PROPERTY = "field";

    /**
     * The problem-detail property carrying the stable, machine-readable code for the failure class.
     * <p>
     * Every error body this controller returns carries exactly one of the {@code ERROR_CODE_*} constants
     * below. A client branches on that code, never on the wording of {@code detail} and never on a property
     * naming an internal resource: the code is the supported contract, so the internal detail that used to
     * travel beside it could be withdrawn without breaking any caller.
     */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * The problem-detail property carrying the correlation identifier of the failing request.
     * <p>
     * This is the hinge of the {@code CWE-209} fix. The relation, constraint, logical file, operation and
     * file-status values that used to be returned to the client are now written only to the log, and this
     * identifier is what lets a caller reporting a failure be joined to those log records: it is the same
     * value {@code CorrelationIdFilter} placed in the diagnostic context and echoed on the
     * {@code X-Correlation-Id} response header, so support can retrieve the internal detail while an
     * attacker holding the response body cannot.
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value substituted when no correlation identifier is in the diagnostic context.
     * <p>
     * {@code CorrelationIdFilter} runs at {@code HIGHEST_PRECEDENCE} and every request that reaches a
     * handler here has passed through it, so this is unreachable in the server. It exists because a
     * standalone unit test may invoke a handler directly, and because a null property would serialise as a
     * {@code null} member and make the body's shape depend on how it was produced.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * Stable error code meaning that the request was refused by a field-level validation rule.
     */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /**
     * Stable error code meaning that processing terminated abnormally.
     */
    private static final String ERROR_CODE_ABEND = "CARDDEMO-PROCESSING-ABEND";

    /**
     * Stable error code meaning that an unexpected typed failure occurred.
     */
    private static final String ERROR_CODE_INTERNAL = "CARDDEMO-INTERNAL-FAILURE";

    /**
     * Diagnostic log destination. Static and final: a logger is neither mutable state nor per-request
     * state, and holding it once avoids a lookup on every request.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MenuController.class);

    /**
     * The main-menu service, replacing {@code app/cbl/COMEN01C.cbl} over the option table
     * {@code app/cpy/COMEN02Y.cpy}. Final and never reassigned.
     */
    private final MainMenuService mainMenuService;

    /**
     * The administrator-menu service, replacing {@code app/cbl/COADM01C.cbl} over the option table
     * {@code app/cpy/COADM02Y.cpy}. Final and never reassigned.
     */
    private final AdminMenuService adminMenuService;

    /**
     * Creates the controller over the two menu services.
     *
     * <p>Constructor injection is the only injection form used: there is no field or setter injection
     * and no {@code @Autowired}, so both collaborators are non-null and final for the lifetime of the
     * bean and the class holds no global mutable state. Both arguments are validated rather than
     * trusted, because a null collaborator would otherwise surface as a failure on the first request
     * instead of at context refresh.</p>
     *
     * @param mainMenuService the main-menu service replacing {@code app/cbl/COMEN01C.cbl}; must not be
     * null.
     * @param adminMenuService the administrator-menu service replacing {@code app/cbl/COADM01C.cbl};
     * must not be null.
     * @throws IllegalArgumentException if either service is null, which is a bean-wiring defect rather
     * than a request-time condition
     */
    public MenuController(final MainMenuService mainMenuService, final AdminMenuService adminMenuService) {

        if (mainMenuService == null) {
            throw new IllegalArgumentException(
                    "mainMenuService must not be null; it is the replacement for app/cbl/COMEN01C.cbl");
        }
        if (adminMenuService == null) {
            throw new IllegalArgumentException(
                    "adminMenuService must not be null; it is the replacement for app/cbl/COADM01C.cbl");
        }

        this.mainMenuService = mainMenuService;
        this.adminMenuService = adminMenuService;
    }

    /**
     * Operation 1 of 2 - the regular user's main menu. Replaces CICS transaction {@code CM00} and the
     * program it fronts, {@code app/cbl/COMEN01C.cbl} (282 lines, 7 paragraph labels), which painted
     * mapset {@code COMEN01}.
     *
     * <p><strong>Purpose.</strong> {@code MAIN-PARA} at {@code app/cbl/COMEN01C.cbl:L75} sent the menu
     * map on first entry and received it on re-entry. A stateless request is always a first entry, so
     * this operation always sends: it returns the option payload that
     * {@code SEND-MENU-SCREEN} at {@code :L182} and {@code BUILD-MENU-OPTIONS} at {@code :L236}
     * composed for the 3270 terminal.</p>
     *
     * <p><strong>Inputs.</strong> None from the request line, the query string or a body - the menu is
     * a pure read. The sole input is the authenticated principal, from which the user class is derived.
     * Identity is never taken from a body, a bespoke header or a session.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200 OK} carrying {@code menuType}, {@code options} and
     * {@code optionCount}. Each option carries its number, its caption at the declared
     * {@code PIC X(35)} width, its eight-character source program name and its one-character
     * eligibility code. The option list is bounded by
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:L21} - so
     * <strong>ten</strong> options - and never by the {@code OCCURS 12 TIMES} capacity declared at
     * {@code :L88}, whose two spare subscripts overlay unrelated working storage and would surface
     * phantom options. The eight-character program name travels as the copybook's own
     * {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)} field at {@code :L91}, that is, as descriptive
     * provenance; it is not a routing instruction, and the client navigates by URL.</p>
     *
     * <p><strong>Side effects.</strong> None. No write of any kind, no session created, no mapped
     * diagnostic context key added or altered, and no state retained between requests.</p>
     *
     * <p><strong>Configuration and defaults.</strong> None read here. Authorisation for
     * {@value #BASE_PATH} is declared centrally in {@code SecurityConfig}, which this operation does
     * not contradict and does not duplicate with a method-level annotation.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 401} when no authenticated
     * principal reached the operation - the stateless counterpart of {@code RETURN-TO-SIGNON-SCREEN} at
     * {@code :L170}, whose {@code 'COSGN00C'} substitution has no Java counterpart. {@code 403} when
     * the principal carries neither {@value #ADMIN_AUTHORITY} nor {@value #USER_AUTHORITY}, which is a
     * token-issuing defect. {@code 400} when a {@code ValidationException} surfaces, whose message is
     * relayed byte for byte - which is how the two legacy literals reach a client intact: the
     * option-bounds refusal of {@code :L131}, and the user-type refusal
     * {@code 'No access - Admin Only option... '} of {@code :L140-L141}, three dots and the trailing
     * space inside the literal included. That user-type gate at {@code :L136-L137} fires when a
     * {@code 'U'} user selects an option whose {@code CDEMO-MENU-OPT-USRTYPE} is {@code 'A'}; against
     * the frozen table it cannot fire, because all ten entries of {@code app/cpy/COMEN02Y.cpy:L25-L84}
     * carry {@code 'U'}. The placeholder guard
     * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'} at {@code :L146} and the
     * coming-soon notice at {@code :L159-L163} - which <strong>includes</strong> the option caption,
     * truncated at its first space by {@code DELIMITED BY SPACE} - belong to the selection contract in
     * {@code MainMenuService} and are reached by URL navigation, not by this operation. That guard is
     * likewise live-but-never-firing against the frozen table: the literal {@code 'DUMMY'} occurs
     * nowhere in {@code app/cpy/COMEN02Y.cpy}, whose ten target programs are all real - the string
     * appears in the whole corpus only in this guard and its administrator twin at
     * {@code app/cbl/COADM01C.cbl:L138}. Finally
     * {@code 500} when retrieval fails unexpectedly, wrapped as an abend carrying code {@code 999} and
     * return code {@code 12} with the original throwable preserved as the cause.
     *
     * @param authentication the authenticated principal Spring Security resolved for this request,
     * which is null when the request reached the operation unauthenticated.
     * @return {@code 200 OK} with the ten-option main menu, {@code 401} when unauthenticated, or
     * {@code 403} when no recognised user class could be resolved
     */
    @GetMapping(MAIN_MENU_PATH)
    public ResponseEntity<MenuResponse<MenuResponse.MainMenuOption>> getMainMenu(
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of RETURN-TO-SIGNON-SCREEN at "
                    + "app/cbl/COMEN01C.cbl:L170", MAIN_MENU_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final UserType userType = resolveUserType(authentication);
        if (userType == null) {
            LOG.warn("Refused transaction {} with 403: the authenticated principal carries neither {} nor "
                    + "{}, so the CDEMO-USER-TYPE replacement could not be resolved",
                    MAIN_MENU_TRANSACTION_ID, ADMIN_AUTHORITY, USER_AUTHORITY);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        final MenuResponse<MenuResponse.MainMenuOption> menu = retrieveMainMenu(userType);

        LOG.debug("Served transaction {} program {} to user class {} with {} options",
                MAIN_MENU_TRANSACTION_ID, MAIN_MENU_PROGRAM, userType, menu.getOptionCount());

        return ResponseEntity.ok(menu);
    }

    /**
     * Operation 2 of 2 - the administrator's menu. Replaces CICS transaction {@code CA00} and the
     * program it fronts, {@code app/cbl/COADM01C.cbl} (268 lines, 7 paragraph labels), which painted
     * mapset {@code COADM01}.
     *
     * <p><strong>Purpose.</strong> The counterpart of {@code SEND-MENU-SCREEN} at
     * {@code app/cbl/COADM01C.cbl:L172} and {@code BUILD-MENU-OPTIONS} at {@code :L226}: it returns the
     * four user-administration options an administrator may choose from.</p>
     *
     * <p><strong>Inputs.</strong> None but the authenticated principal, exactly as for the main
     * menu.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200 OK} carrying {@code menuType}, {@code options} and
     * {@code optionCount}. The option list is bounded by
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} at {@code app/cpy/COADM02Y.cpy:L20} - so
     * <strong>four</strong> options - and never by the {@code OCCURS 9 TIMES} capacity declared at
     * {@code :L45}. Each entry carries only a number, a caption and a program name, because
     * {@code CDEMO-ADMIN-OPT} at {@code :L46-L48} declares no eligibility field at all - the
     * administrator table has no counterpart to the main table's
     * {@code CDEMO-MENU-OPT-USRTYPE}.</p>
     *
     * <p><strong>Side effects.</strong> None, on the same terms as the main menu.</p>
     *
     * <p><strong>Configuration and defaults.</strong> None read here. The administrator restriction is
     * declared centrally in {@code SecurityConfig} and additionally enforced in this operation, which
     * agrees with the central rule rather than competing with it; see the deviation recorded on this
     * class under <em>admin menu role gate</em>.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 401} when unauthenticated - the
     * stateless counterpart of {@code RETURN-TO-SIGNON-SCREEN} at {@code :L160}. {@code 403} either
     * when no recognised user class could be resolved, or when a {@value #USER_AUTHORITY} principal
     * asked for an administrator surface; the body is empty in both cases, because
     * {@code app/cbl/COADM01C.cbl} declares no refusal message for this condition and none is invented.
     * The main menu's {@code 'No access - Admin Only option... '} literal is deliberately <em>not</em>
     * reused here: it belongs to the option-eligibility gate of
     * {@code app/cbl/COMEN01C.cbl:L136-L141}, not to an administrator screen request. {@code 400} when
     * a {@code ValidationException} surfaces from the option-bounds refusal of {@code :L131}, relayed
     * byte for byte. {@code 500} when retrieval fails unexpectedly, wrapped as an abend carrying code
     * {@code 999} and return code {@code 12} with the cause preserved.
     *
     * <p><strong>The coming-soon notice differs from the main menu's and is not unified with it.</strong>
     * {@code app/cbl/COADM01C.cbl:L149-L153} assembles {@code 'This option ' + 'is coming soon ...'}
     * with the two option-caption lines <strong>commented out</strong> at {@code :L150-L151}, so the
     * administrator message <strong>omits</strong> the caption that
     * {@code app/cbl/COMEN01C.cbl:L159-L163} <strong>includes</strong>. That is live behaviour, not an
     * oversight, and {@code AdminMenuService.COMING_SOON_MESSAGE} holds the shorter form verbatim.
     * Neither notice can fire against the frozen table, because the placeholder guard at {@code :L138}
     * tests for a {@code 'DUMMY'} program prefix that no entry of {@code app/cpy/COADM02Y.cpy} carries -
     * its four targets {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} are
     * all real. The divergence is preserved rather than unified all the same, because parity is the
     * contract and the two literals are observable wherever the tables are ever extended.</p>
     *
     * @param authentication the authenticated principal Spring Security resolved for this request,
     * which is null when the request reached the operation unauthenticated.
     * @return {@code 200 OK} with the four-option administrator menu, {@code 401} when unauthenticated,
     * or {@code 403} when the principal is not an administrator
     */
    @GetMapping(ADMIN_MENU_PATH)
    public ResponseEntity<MenuResponse<MenuResponse.AdminMenuOption>> getAdminMenu(
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of RETURN-TO-SIGNON-SCREEN at "
                    + "app/cbl/COADM01C.cbl:L160", ADMIN_MENU_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final UserType userType = resolveUserType(authentication);
        if (userType == null) {
            LOG.warn("Refused transaction {} with 403: the authenticated principal carries neither {} nor "
                    + "{}, so the CDEMO-USER-TYPE replacement could not be resolved",
                    ADMIN_MENU_TRANSACTION_ID, ADMIN_AUTHORITY, USER_AUTHORITY);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (userType != UserType.ADMIN) {
            LOG.warn("Refused transaction {} with 403: user class {} is not {}, and the four options of "
                    + "app/cpy/COADM02Y.cpy target the user-administration programs",
                    ADMIN_MENU_TRANSACTION_ID, userType, UserType.ADMIN);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        final MenuResponse<MenuResponse.AdminMenuOption> menu = retrieveAdminMenu();

        LOG.debug("Served transaction {} program {} to user class {} with {} options",
                ADMIN_MENU_TRANSACTION_ID, ADMIN_MENU_PROGRAM, userType, menu.getOptionCount());

        return ResponseEntity.ok(menu);
    }

    /**
     * Maps a rejected menu request onto {@code 400 Bad Request}.
     *
     * <p>This is not a {@code @ControllerAdvice} method and this class is not an advice type: HTTP
     * status selection is contextual, so each controller performs its own. The exception types
     * themselves carry no {@code @ResponseStatus}, which is why the mapping is stated here.</p>
     *
     * <p>The exception's message is relayed <strong>byte for byte</strong> as the problem detail, which
     * is what allows a legacy literal such as {@code 'No access - Admin Only option... '} from
     * {@code app/cbl/COMEN01C.cbl:L140-L141} to reach a client exactly as the 3270 screen displayed it,
     * trailing space included. No message is fabricated: when the exception carries none, the response
     * carries no detail member either. These literals are public screen captions - they disclose no
     * credential, no identifier and no personally identifiable field - so relaying them is consistent
     * with Rule 1 Clause D.</p>
     *
     * @param rejection the validation failure raised by a menu service, never null when Spring MVC
     * dispatches here.
     * @return {@code 400 Bad Request} carrying a problem detail, and the rejected field name when the
     * exception named one
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure(final ValidationException rejection) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        final String rejectionMessage = rejection.getMessage();
        if (rejectionMessage != null) {
            problem.setDetail(rejectionMessage);
        }
        if (rejection.hasFieldName()) {
            problem.setProperty(FIELD_PROPERTY, rejection.getFieldName());
        }

        LOG.warn("Refused a menu request with 400 for field {} and failure kind {}",
                rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract on the
     * diagnostic log.
     *
     * <p>{@code 9999-ABEND-PROGRAM} displayed an abend message, moved {@code 999} into
     * {@code ABEND-CODE} and called the language-environment abend service, and the batch stream
     * reported return code {@code 12}. <strong>Both values are logged and neither is returned.</strong>
     * They are operational internals of the terminating path: a client cannot act on {@code 999} or on
     * {@code 12}, while a client that can read them learns which internal termination route a crafted
     * request reached. The body carries the fixed detail, {@value #ERROR_CODE_ABEND} and the correlation
     * identifier, which is the same posture as {@code server.error.include-message} being set to
     * {@code never}. The cause remains attached to the exception and is logged at {@code ERROR}, so
     * diagnosis proceeds from the correlation identifier rather than from the response body.</p>
     *
     * @param abend the fatal failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and
     * the correlation identifier
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        // The abend code and the batch return code are logged, not returned. Both are mainframe operational
        // internals: 999 and 12 tell a caller nothing it can act on, while telling an attacker which
        // termination path was taken. The stable error code is what a client branches on.
        LOG.error("Menu retrieval abended: code {} returnCode {} culprit {} reason {}",
                abend.getAbendCode() == null ? ABEND_CODE : abend.getAbendCode(),
                FatalProcessingException.BATCH_RETURN_CODE, abend.getAbendCulprit(), abend.getAbendReason(),
                abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps every remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>Spring MVC resolves the most specific handler, so a {@code ValidationException} or a
     * {@code FatalProcessingException} never reaches this method - it covers the rest of the nine-type
     * hierarchy, each of which stands for a legacy {@code FILE STATUS} value or response code. The
     * detail is the same fixed string used for an abend, for the same reason, and the cause is logged
     * rather than returned.</p>
     *
     * @param failure the typed failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("Menu retrieval failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Delegates to the main-menu service exactly once and returns its option payload.
     *
     * <p>Extracted from {@link #getMainMenu(Authentication)} so that the handler reads as a sequence of
     * decisions and the abend translation is stated once. The two catch clauses are both load bearing
     * and neither swallows anything: {@code CardDemoException} is rethrown unchanged so that the
     * declared status mapping applies to it, and because that type extends {@code RuntimeException} the
     * first clause is what stops the second from re-wrapping an already-typed failure. Every other
     * runtime failure becomes the abend of {@code 9999-ABEND-PROGRAM}, with the original throwable
     * preserved as the cause.</p>
     *
     * <p>Only {@code MainMenuScreen.menu()} is surfaced. Its {@code optionLabels} are the rendered 3270
     * lines, each padded to the forty characters of {@code OPTN001I PIC X(40)}, and its
     * {@code signOnTarget} is the program {@code RETURN-TO-SIGNON-SCREEN} transferred to - terminal
     * furniture and a routing instruction respectively, and neither belongs on a JSON menu.</p>
     *
     * @param userType the resolved user class, which the service uses to apply its least-privilege
     * display filter.
     * @return the option payload for {@code userType}, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed
     * CardDemo failure
     */
    private MenuResponse<MenuResponse.MainMenuOption> retrieveMainMenu(final UserType userType) {

        try {
            return this.mainMenuService.getMainMenu(userType).menu();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, MAIN_MENU_PROGRAM, MAIN_MENU_ABEND_REASON,
                    MAIN_MENU_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Delegates to the administrator-menu service exactly once and returns its option payload.
     *
     * <p>The service's screen operation takes no argument, because {@code app/cbl/COADM01C.cbl} applies
     * no eligibility filter to its table - a fact independently confirmed by
     * {@code app/cpy/COADM02Y.cpy:L46-L48}, which declares no user-type field. The catch clauses behave
     * exactly as described on {@link #retrieveMainMenu(UserType)}.</p>
     *
     * <p>Only {@code AdminMenuView.menu()} is surfaced. Its {@code optionLabels} are rendered 3270
     * lines, and its {@code message} is empty by construction on the retrieval path - the notice field
     * is populated only by the selection contract, which this class does not expose.</p>
     *
     * @return the four-option administrator payload, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed
     * CardDemo failure
     */
    private MenuResponse<MenuResponse.AdminMenuOption> retrieveAdminMenu() {

        try {
            return this.adminMenuService.getMenuScreen().menu();
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, ADMIN_MENU_PROGRAM, ADMIN_MENU_ABEND_REASON,
                    ADMIN_MENU_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Decides whether a request arrived without a usable identity.
     *
     * <p>Three states are rejected explicitly rather than collapsed: a null principal, which is what a
     * request that bypassed the security filter chain presents; a principal that reports itself
     * unauthenticated; and an anonymous token, which reports itself <em>authenticated</em> and would
     * therefore slip past a bare {@code isAuthenticated()} test. Distinguishing them from a principal
     * that merely lacks a recognised authority is what keeps {@code 401} and {@code 403} meaningful.</p>
     *
     * @param authentication the principal Spring Security resolved, which may be null.
     * @return true when the request carries no usable identity
     */
    private static boolean isUnauthenticated(final Authentication authentication) {
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * Resolves the user class the request is entitled to, from the granted authorities alone.
     *
     * <p>This is the stateless replacement for {@code CDEMO-USER-TYPE PIC X(01)} of
     * {@code app/cpy/COCOM01Y.cpy:L26}, whose only two values are declared by
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}. The administrator authority is tested
     * first so that a principal holding both resolves to the wider class, matching the source, in which
     * the {@code 'U'}-only gate at {@code app/cbl/COMEN01C.cbl:L136} never applies to an administrator.
     * Nothing is read from the request body, the query string or a session, and no claim key is
     * assumed: the two authority names are the contract.</p>
     *
     * @param authentication the authenticated principal, which this method is only ever called with
     * once {@link #isUnauthenticated(Authentication)} has returned false.
     * @return the resolved user class, or null when the principal carries neither recognised authority
     */
    private static UserType resolveUserType(final Authentication authentication) {

        if (hasAuthority(authentication, ADMIN_AUTHORITY)) {
            return UserType.ADMIN;
        }
        if (hasAuthority(authentication, USER_AUTHORITY)) {
            return UserType.USER;
        }
        return null;
    }

    /**
     * Reports whether a principal carries a named authority.
     *
     * <p>The comparison is exact and case sensitive, which mirrors the source: the condition names at
     * {@code app/cpy/COCOM01Y.cpy:L27-L28} test {@code 'A'} and {@code 'U'} literally, so {@code 'a'}
     * is not an administrator. The authority collection and its elements are both treated as
     * potentially absent, because a custom authentication implementation is free to supply either.</p>
     *
     * @param authentication the authenticated principal, never null here.
     * @param authorityName the authority to look for, never null here.
     * @return true when the principal carries exactly that authority
     */
    private static boolean hasAuthority(final Authentication authentication, final String authorityName) {

        final Collection<? extends GrantedAuthority> grantedAuthorities = authentication.getAuthorities();
        if (grantedAuthorities == null) {
            return false;
        }

        for (final GrantedAuthority grantedAuthority : grantedAuthorities) {
            if (grantedAuthority != null && authorityName.equals(grantedAuthority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stamps the two properties every error body carries, and returns the same instance for chaining.
     *
     * <p>Called by each {@code @ExceptionHandler} above as the last thing it does to the body, so a future
     * handler cannot omit the envelope by accident: the {@code return} statement reads
     * {@code body(withPublicEnvelope(problem, ...))}, and a handler written without it does not compile
     * into that shape.
     *
     * <p><strong>This is the whole of the {@code CWE-209} posture.</strong> What the body carries is the
     * status, the title, a detail that is either a legacy screen literal or a fixed sentence, the error code
     * and the correlation identifier. What it no longer carries is the relation, the constraint name, the
     * logical file or dataset name, the input-output operation, the expanded file status, the record type,
     * the abend code and the batch return code. Every one of those is still emitted - at {@code WARN} or
     * {@code ERROR}, on a log stream the caller cannot read - so no diagnostic capability is lost and
     * nothing is swallowed.
     *
     * @param problem the body under construction; must not be null.
     * @param errorCode one of the {@code ERROR_CODE_*} constants.
     * @return {@code problem}, so the call can be inlined into the {@code body(...)} argument
     */
    private static ProblemDetail withPublicEnvelope(final ProblemDetail problem, final String errorCode) {

        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        problem.setProperty(CORRELATION_ID_PROPERTY,
                correlationId == null || correlationId.isEmpty() ? CORRELATION_ID_UNAVAILABLE : correlationId);

        return problem;
    }
}
