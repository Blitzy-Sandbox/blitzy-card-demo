/*
 * ******************************************************************
 * Program     : AuthController.java
 * Application : CardDemo
 * Type        : Spring Boot REST Controller
 * Function    : Sign-on endpoint. Issues a bearer token in place of
 *               populating the CICS COMMAREA.
 * Source      : app/csd/CARDDEMO.CSD transaction CC00
 *               -> app/cbl/COSGN00C.cbl (260 lines), mapset COSGN00
 *               -> app/cpy-bms/COSGN00.CPY (11 input fields) @ 7756d89
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.auth.AuthenticationService;

import jakarta.validation.Valid;

/**
 * REST entry point for CICS transaction {@code CC00}, the sign-on transaction, replacing
 * {@code app/cbl/COSGN00C.cbl} (260 lines) and its mapset {@code COSGN00} @ 7756d89.
 *
 * <p><strong>This is the only controller in the application whose operation is permitted
 * unauthenticated.</strong> Every other operation on the REST surface requires a valid bearer token, and
 * {@code SecurityConfig} grants anonymous access to the one exact route below rather than to the
 * {@code /api/auth} namespace, so no future authentication operation can become anonymous by accident.
 *
 * <h2>What it does</h2>
 *
 * <p>It accepts a sign-on form, hands it unchanged to {@code AuthenticationService}, and returns the issued
 * bearer token. That is the whole of its behaviour. It carries <strong>one</strong> request-mapped handler -
 * operation 1 of the seventeen the REST surface exposes - and no second operation, alias, refresh, sign-out,
 * probe or diagnostic route.
 *
 * <p>The legacy program did four things this class does not. It received a 3270 map
 * ({@code EXEC CICS RECEIVE MAP('COSGN0A')} at {@code app/cbl/COSGN00C.cbl:L110-L115}); it folded both
 * credentials to upper case ({@code :L132-L136}); it read the {@code USRSEC} dataset directly
 * ({@code :L211-L219}); and it transferred control to the next program
 * ({@code EXEC CICS XCTL} at {@code :L231} and {@code :L236}). The first has no counterpart because the
 * transport is JSON. The second and third belong to the service tier and its delegate. The fourth is
 * replaced by URL-based navigation: the response carries the user class and the client chooses its own next
 * request.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and test the module with {@code ./mvnw clean verify}, which compiles under
 * {@code -Xlint:all -Werror}, runs the unit tier under Surefire, runs the integration and end-to-end tiers
 * under Failsafe, enforces the JaCoCo line-coverage gate and runs the Javadoc doclint gate. Run the
 * application with {@code java -jar target/carddemo-1.0.0.jar}, or bring the whole topology up with
 * {@code docker compose up}.
 *
 * <p>Exercise this operation with an HTTP {@code POST} to {@code /api/auth/signon} carrying a JSON body whose
 * {@code userId} and {@code password} are populated. A success answers {@code 200 OK} with a token; the
 * failure answers are tabulated under the troubleshooting heading below. Tests for this controller live in
 * {@code src/test/java/com/cardemo/unit/controller} and {@code src/test/java/com/cardemo/e2e}, never in this
 * package: the handler holds no state of any kind, so it is reachable through {@code MockMvc} against a
 * single stubbed service and the suite is order-independent.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>This class reads no property, no environment variable and no system property.</strong> Its base
 * path is the compile-time constant {@value #BASE_PATH} and its route suffix is {@value #SIGN_ON_PATH}; both
 * are literals in this file so that the class-level annotation, the method-level annotation and this
 * documentation cannot drift apart.
 *
 * <p>Everything that shapes the issued token is owned elsewhere and named here only so an operator knows
 * where to look. {@code src/main/resources/application.yml} declares
 * {@code carddemo.security.jwt.signing-key}, bound to the environment variable {@code JWT_SIGNING_KEY}
 * <strong>with no default, no example and no committed value</strong>, so an absent key leaves the
 * placeholder unresolvable and the application fails to start rather than booting with a signing key an
 * attacker already knows. Alongside it, {@code carddemo.security.jwt.issuer} defaults to {@code carddemo}
 * and {@code carddemo.security.jwt.expiration-minutes} defaults to {@code 30} - the unit is
 * <strong>minutes</strong>, and it is part of the contract. {@code SecurityConfig} builds the decoder and
 * declares the authorisation rules; {@code JwtTokenProvider} mints the token and converts the lifetime to a
 * duration exactly once.
 *
 * <p><strong>No response header is set here, and that omission is deliberate rather than an oversight.</strong>
 * The security filter chain writes the header set for this route, including the no-store cache triple that
 * keeps a token out of intermediate caches, and it writes those headers eagerly as the published mitigation
 * for an advisory whose triggering condition is an application that sets response headers of its own.
 * Adding a header here would re-create that condition and would duplicate a contract the chain already owns
 * and the repository's own header test already asserts against this exact route.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every failure answer is an RFC 7807 {@code ProblemDetail} carrying an {@code errorCode} and the request's
 * {@code correlationId}. Quote that correlation identifier when reading the logs: it is the value the
 * structured log line for the same request carries, and it is the intended starting point for every
 * diagnosis below.
 *
 * <dl>
 *   <dt>{@code 400 Bad Request}, {@code errorCode} {@value #ERROR_CODE_VALIDATION}</dt>
 *   <dd>An operative input was absent or blank. The response names which one, reproducing the legacy
 *       captions {@code 'Please enter User ID ...'} at {@code app/cbl/COSGN00C.cbl:L120} and
 *       {@code 'Please enter Password ...'} at {@code :L125}. Populate the named field and retry. A body
 *       that is absent, malformed or over-long for a declared field width is also refused with this status,
 *       by the framework's own converters and bean validation rather than by this class.</dd>
 *   <dt>{@code 401 Unauthorized}, {@code errorCode} {@value #ERROR_CODE_AUTHENTICATION}</dt>
 *   <dd>The credential did not verify. <strong>This single answer covers both an unknown identifier and a
 *       wrong password, deliberately and indistinguishably</strong> - see the user-enumeration section below.
 *       There is nothing to diagnose from the response, and that is the point. The only surviving trace of
 *       which condition occurred is the cause chain of the exception the service raises, which this class
 *       emits at {@code DEBUG} and nowhere else: raise the level for this logger to inspect it, and no
 *       caller can see it at any level.</dd>
 *   <dt>{@code 503 Service Unavailable}, {@code errorCode} {@value #ERROR_CODE_UNAVAILABLE}</dt>
 *   <dd>The user security store could not be opened. This is the modern reading of file status {@code '35'},
 *       the legacy not-open condition, and of the recoverable half of the {@code WHEN OTHER} arm at
 *       {@code app/cbl/COSGN00C.cbl:L252-L256}, captioned {@code 'Unable to verify the User ...'}. Check
 *       that the database is reachable and that the migrations have applied, then retry. The condition is
 *       transient, so the request is safe to repeat.</dd>
 *   <dt>{@code 503 Service Unavailable}, {@code errorCode} {@value #ERROR_CODE_IO_FAILURE}</dt>
 *   <dd>The store was reachable but the read failed - the {@code '9x'} status family. Same arm of the same
 *       {@code EVALUATE}, same caption, same advice: inspect the store, then retry.</dd>
 *   <dt>{@code 500 Internal Server Error}, {@code errorCode} {@value #ERROR_CODE_ABEND}</dt>
 *   <dd>The operation ended the way {@code CALL 'CEE3ABD'} ended the legacy task, carrying abend code
 *       {@code 999} and return code {@code 12}. This is a defect, not a transient condition. Retrying will
 *       not help; read the {@code ERROR} log line for the correlation identifier, which carries the abend
 *       code, the culprit program and the preserved root cause.</dd>
 *   <dt>{@code 500 Internal Server Error}, {@code errorCode} {@value #ERROR_CODE_INTERNAL}</dt>
 *   <dd>A typed failure reached this class that none of the mappers above claims. Treat it exactly as the
 *       abend above: it is a defect in the tier that raised it, and the log line carries the type.</dd>
 * </dl>
 *
 * <h2>Mechanism substitutions, each labelled</h2>
 *
 * <ul>
 *   <li><strong>The pseudo-conversational return becomes one stateless call.</strong> Under Transformation
 *       Rule 7, {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
 *       {@code app/cbl/COSGN00C.cbl:L98-L102} becomes a stateless request carrying its own credential and a
 *       response carrying a token. <strong>No server-side session state exists.</strong> Nothing here creates
 *       or consults a session, and the stateless session policy is declared centrally in
 *       {@code SecurityConfig}.</li>
 *   <li><strong>The COMMAREA becomes token claims.</strong> {@code CDEMO-USER-ID PIC X(08)} at
 *       {@code app/cpy/COCOM01Y.cpy:L25} becomes the token's subject claim, and
 *       {@code CDEMO-USER-TYPE PIC X(01)} at {@code :L26}, whose condition names are
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
 *       {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}, becomes the role claim. The claim set is
 *       exactly the subject, the role, the issuer, the issued-at instant and the expiry, and it is minted by
 *       {@code JwtTokenProvider}, not here.</li>
 *   <li><strong>Program transfer becomes URL-based navigation.</strong> The two
 *       {@code EXEC CICS XCTL} sites at {@code app/cbl/COSGN00C.cbl:L231} and {@code :L236} - two of only
 *       four in the whole corpus - chose {@code COADM01C} for an administrator and {@code COMEN01C} for a
 *       standard user. The response conveys the user class and the client navigates.
 *       <strong>No program name, mapset name or transaction identifier is emitted as a routing
 *       instruction.</strong></li>
 *   <li><strong>The plaintext password comparison becomes a BCrypt verification.</strong>
 *       {@code IF SEC-USR-PWD = WS-USER-PWD} at {@code app/cbl/COSGN00C.cbl:L223} compared the eight
 *       characters of {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} literally. The
 *       target stores only hashes. Neither the comparison nor the hash is visible from this class.</li>
 *   <li><strong>Screen captions become problem details.</strong> The four literals the program moved into
 *       {@code WS-MESSAGE} survive as the detail text of the answers tabulated above, with the one
 *       deliberate exception recorded as a deviation below.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not carry</h2>
 *
 * <ul>
 *   <li><strong>No credential normalisation.</strong> The source folds both values to upper case, the
 *       identifier at {@code app/cbl/COSGN00C.cbl:L132-L134} - one {@code MOVE} with two receiving fields,
 *       {@code WS-USER-ID} and {@code CDEMO-USER-ID} - and the password at {@code :L135-L136}. Reproducing
 *       that here would put a second normalisation authority in the application, so the request body is
 *       passed through untouched and the folding happens once, in {@code CardDemoUserDetailsService}.</li>
 *   <li><strong>No validation ordering and no business rule.</strong> The source tests the identifier before
 *       the password at {@code :L117-L130} and its {@code EVALUATE} falls through to {@code :L132}
 *       regardless, with {@code IF NOT ERR-FLG-ON} at {@code :L138} the sole gate on the read. That ordering
 *       lives in the service. Bean validation on the request record constrains only the declared field
 *       widths, so it cannot pre-empt or reorder those checks, and this class adds nothing that could
 *       contradict them.</li>
 *   <li><strong>No navigation, re-entry or screen state.</strong> {@code app/cpy/COCOM01Y.cpy} declares
 *       {@code CDEMO-FROM-TRANID X(04)} at {@code :L21}, {@code CDEMO-FROM-PROGRAM X(08)} at {@code :L22},
 *       {@code CDEMO-TO-TRANID X(04)} at {@code :L23}, {@code CDEMO-TO-PROGRAM X(08)} at {@code :L24},
 *       {@code CDEMO-PGM-CONTEXT 9(01)} at {@code :L29} with {@code 88 CDEMO-PGM-ENTER VALUE 0} at
 *       {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31},
 *       {@code CDEMO-LAST-MAP X(7)} at {@code :L43} and {@code CDEMO-LAST-MAPSET X(7)} at {@code :L44} -
 *       a width of seven, not eight. <strong>None of them appears in any request or response.</strong> The
 *       enter-versus-re-enter flag collapses into stateless request handling and has no field anywhere.</li>
 *   <li><strong>No customer, account or card identity.</strong> {@code CDEMO-CUST-ID} at {@code :L33}, the
 *       three {@code CDEMO-CUST-*NAME} fields at {@code :L34-L36}, {@code CDEMO-ACCT-ID} at {@code :L38},
 *       {@code CDEMO-ACCT-STATUS} at {@code :L39} and {@code CDEMO-CARD-NUM} at {@code :L41} are neither
 *       emitted here nor carried as claims. A token is signed, not encrypted, so anything placed in it is
 *       readable by its bearer; those fields belong to the data transfer objects of the operations that own
 *       them.</li>
 *   <li><strong>No function-key dispatcher.</strong> {@code app/cpy/CVCRD01Y.cpy} action state and the
 *       procedural {@code YYYY-STORE-PFKEY.} paragraph at {@code app/cpy/CSSTRPFY.cpy:L17}, an
 *       {@code EVALUATE TRUE} over {@code EIBAID}, map onto an explicit route per action. This transaction
 *       has exactly one action, so the route is the mapping and there is no attention-key parameter.
 *       {@code CSSTRPFY} is a procedure-division copybook and therefore has no type, no field and no import.
 *       The CICS-supplied {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} copied at
 *       {@code app/cbl/COSGN00C.cbl:L57-L59} are absent from this repository and likewise have no
 *       import.</li>
 *   <li><strong>No cursor semantics.</strong> {@code MOVE -1 TO USERIDL} at {@code :L121} and
 *       {@code MOVE -1 TO PASSWDL} at {@code :L126} and {@code :L244} positioned the 3270 cursor.
 *       <strong>Cursor repositioning has no Java counterpart and is not implemented.</strong> The field name
 *       the problem body carries is the surviving, transport-appropriate half of that intent.</li>
 *   <li><strong>No advice class and no shared base.</strong> Status selection is contextual, so it is
 *       performed by the controller that owns the operation. There is no {@code @ControllerAdvice}, no
 *       {@code ResponseEntityExceptionHandler} subclass and no abstract controller anywhere in this
 *       package, and the nine typed exceptions carry no {@code @ResponseStatus}.</li>
 *   <li><strong>No monetary value and no temporal parsing.</strong> This operation carries neither. The
 *       eleven inputs are fixed-width character fields; the two that matter are credentials and the rest is
 *       terminal-header metadata the legacy screen supplied.</li>
 *   <li><strong>No metric and no MDC mutation.</strong> {@code MetricsConfig} owns the counters, including
 *       the authentication-attempt counter this transaction feeds, and the service records it.
 *       {@code CorrelationIdFilter} owns the MDC keys {@code correlationId}, {@code traceId} and
 *       {@code spanId}. This class registers no instrument and only <em>reads</em> the correlation
 *       identifier; it never adds, renames, overwrites, removes or clears an MDC key.</li>
 *   <li><strong>No developer or diagnostic route.</strong> The CSD defines
 *       {@code DEFINE TRANSACTION(CDV1)} at {@code app/csd/CARDDEMO.CSD:L388-L391}, described
 *       {@code DEVELOPER TRANSACTION - 1}, whose {@code PROGRAM(COCRDSEC)} at {@code :L390} has
 *       <strong>Not available</strong> as its source: the name occurs at exactly two places repository-wide
 *       and both are the CSD definition itself. Closing that gap would need the {@code COCRDSEC} source,
 *       which does not exist here, so nothing is translated and no endpoint is invented.</li>
 * </ul>
 *
 * <h2>Access control</h2>
 *
 * <p>Authorisation is declared centrally in {@code SecurityConfig}, which grants anonymous access to
 * {@code POST} on this one exact route and refuses every unnamed path through a terminal deny-all rule.
 * <strong>This class carries no method-level authorisation annotation</strong>, so it cannot contradict that
 * declaration, and it neither reads nor requires an {@code Authentication}: by construction there is no
 * principal yet when this operation runs. Establishing one is what the operation does.
 */
@RestController
@RequestMapping(AuthController.BASE_PATH)
public class AuthController {

    /**
     * Base path for every operation on this controller, matching the {@code /api/auth} namespace the tree
     * already uses. A compile-time constant so the class-level {@code @RequestMapping} and the Javadoc that
     * cites the route cannot drift apart, and package-private so the test tier can assert the route without
     * duplicating the literal.
     */
    static final String BASE_PATH = "/api/auth";

    /**
     * Route suffix of the sign-on operation, giving the full route {@code /api/auth/signon}.
     *
     * <p>The value is not free: {@code SecurityConfig} grants anonymous access to this exact path, and every
     * other path beneath {@value #BASE_PATH} falls through to the chain's terminal deny-all rule. Renaming
     * this constant without renaming it there makes the operation unreachable rather than merely
     * misnamed.
     */
    static final String SIGN_ON_PATH = "/signon";

    /**
     * The CICS transaction this controller replaces: {@code WS-TRANID PIC X(04) VALUE 'CC00'} at
     * {@code app/cbl/COSGN00C.cbl:L37}, defined as {@code DEFINE TRANSACTION(CC00) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:L378}. Logged on every outcome so a log line can be tied back to the
     * legacy transaction it stands in for.
     */
    private static final String TRANSACTION_ID = "CC00";

    /**
     * The COBOL program this controller's operation derives from: {@code WS-PGMNAME PIC X(08) VALUE
     * 'COSGN00C'} at {@code app/cbl/COSGN00C.cbl:L36}, named by {@code PROGRAM(COSGN00C)} at
     * {@code app/csd/CARDDEMO.CSD:L379}.
     */
    private static final String PROGRAM_NAME = "COSGN00C";

    /**
     * The BMS mapset the legacy transaction conversed through, from
     * {@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')} at
     * {@code app/cbl/COSGN00C.cbl:L110-L115}. Recorded for traceability only: no map is rendered, and the
     * mapset's eleven input fields survive as the shape of the request record, not as a screen.
     */
    private static final String MAPSET_NAME = "COSGN00";

    /**
     * The logical file the legacy program read: {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at
     * {@code app/cbl/COSGN00C.cbl:L39}. Named in the unavailability and access-failure log lines so an
     * operator knows which store to inspect, and never in a response body, where naming the backing store
     * would leak topology to an unauthenticated caller.
     */
    private static final String USER_SECURITY_FILE = "USRSEC";

    /**
     * Title of the {@code 400 Bad Request} problem body: an operative input was absent or blank.
     */
    private static final String VALIDATION_PROBLEM_TITLE = "Sign-on rejected";

    /**
     * Title of the {@code 401 Unauthorized} problem body, phrased so it reveals nothing about which half of
     * the credential was at fault.
     */
    private static final String AUTHENTICATION_PROBLEM_TITLE = "Authentication failed";

    /**
     * Title of the {@code 503 Service Unavailable} problem body raised when the user security store could not
     * be opened.
     */
    private static final String UNAVAILABLE_PROBLEM_TITLE = "User security store unavailable";

    /**
     * Title of the {@code 503 Service Unavailable} problem body raised when the store was reachable but the
     * read failed.
     */
    private static final String ACCESS_PROBLEM_TITLE = "User security store access failed";

    /**
     * Title of the {@code 500 Internal Server Error} problem body raised when the operation ended the way the
     * legacy abend routine ended the task.
     */
    private static final String FAILURE_PROBLEM_TITLE = "Sign-on failed";

    /**
     * The single detail published for every credential refusal, whatever its internal cause.
     *
     * <p>Fixed rather than derived from the exception's message, which carries the legacy screen literal and
     * would therefore distinguish {@code 'Wrong Password. Try again ...'} at
     * {@code app/cbl/COSGN00C.cbl:L242-L243} from {@code 'User not found. Try again ...'} at {@code :L249}.
     * Publishing that distinction is exactly the user-enumeration oracle the handler's documented
     * deviation removes.
     */
    private static final String AUTHENTICATION_PROBLEM_DETAIL =
            "The user identifier or the password is incorrect.";

    /**
     * Detail published when the user security store could not be opened. Names no dataset, no host and no
     * schema.
     */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "The user security store is unavailable. Retry the request.";

    /**
     * Detail published when the store was reachable but the read failed. Deliberately identical in shape to
     * the unavailability detail: both are transient, both are safe to retry, and neither should describe the
     * backing store to an unauthenticated caller.
     */
    private static final String ACCESS_PROBLEM_DETAIL =
            "The user security store could not be read. Retry the request.";

    /**
     * Detail published for an abend and for any typed failure no other mapper on this class claims. Carries
     * no internal message, because a caller who is not yet authenticated has no business reading one.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "Sign-on could not be completed. Contact support with the correlation identifier.";

    /**
     * The single detail published when the framework's bean validation refuses the request body.
     *
     * <p>Fixed, and deliberately naming no field. The framework's binding result carries a field error per
     * violation, and each of those retains the <em>submitted value</em> alongside its message - which on this
     * operation is a plaintext password. {@code UserCreateRequest} documents the same hazard for the
     * administration bodies and states the rule this constant enforces: a handler must never publish a
     * rejected value. Withholding the field name as well costs a caller nothing, because the schema that
     * declares the constraint is what the caller wrote the body against, and it buys determinism: Hibernate
     * Validator reports violations from an unordered set, so any single-field or first-field projection would
     * vary between two identical requests. The field names do reach the log line below, where they are
     * diagnosable without being disclosed.
     */
    private static final String BIND_FAILURE_PROBLEM_DETAIL =
            "One or more fields of the request body failed validation. Correct the body and resubmit.";

    /**
     * The single detail published when the request body could not be read at all.
     *
     * <p>Covers the three conditions the framework folds into one exception before this controller's method
     * body is ever entered: a body that is not well-formed JSON, a body carrying a property outside the
     * schema, and a request with no body where one is required. The parser's own message is not published,
     * because it names the deserialiser's internal stream class, the byte offset it stopped at and, for an
     * unrecognised property, the full list of properties the type accepts - a schema dump handed to an
     * unauthenticated caller.
     */
    private static final String UNREADABLE_BODY_PROBLEM_DETAIL =
            "The request body could not be read as JSON matching this operation's schema.";

    /**
     * Name of the problem-body property carrying the input a validation failure attaches to, matching the
     * property name the sibling controllers publish.
     */
    private static final String FIELD_PROPERTY = "field";

    /**
     * Name of the problem-body property carrying which of the two validation conditions applies, matching the
     * sibling controllers.
     */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * Name of the problem-body property carrying the stable machine-readable failure code.
     */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * Name of the problem-body property carrying the request's correlation identifier.
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * Value published for the correlation identifier when the request carried none, which can only happen if
     * this handler is invoked outside the filter chain that supplies it.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * Failure code for an absent or blank operative input.
     */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /**
     * Failure code for a refused credential.
     *
     * <p>New to this class rather than reused from a sibling, because this is the only operation in the
     * application that authenticates and therefore the only one that can refuse a credential. It follows the
     * same closed naming vocabulary as the codes the other controllers publish, and it is the code both the
     * wrong-password and the unknown-identifier paths carry, so the code itself cannot become the
     * distinguishing signal the response text refuses to be.
     */
    private static final String ERROR_CODE_AUTHENTICATION = "CARDDEMO-AUTHENTICATION-FAILED";

    /**
     * Failure code for a store that could not be opened.
     */
    private static final String ERROR_CODE_UNAVAILABLE = "CARDDEMO-RESOURCE-UNAVAILABLE";

    /**
     * Failure code for a store that could not be read.
     */
    private static final String ERROR_CODE_IO_FAILURE = "CARDDEMO-IO-FAILURE";

    /**
     * Failure code for the abend path, the counterpart of abend code {@code 999} with return code
     * {@code 12}.
     */
    private static final String ERROR_CODE_ABEND = "CARDDEMO-PROCESSING-ABEND";

    /**
     * Failure code for a typed failure no other mapper on this class claims.
     */
    private static final String ERROR_CODE_INTERNAL = "CARDDEMO-INTERNAL-FAILURE";

    /**
     * Failure code for a request body the framework could not read as JSON matching the schema.
     *
     * <p>Distinct from {@link #ERROR_CODE_VALIDATION} because the two conditions are distinct: a body that
     * parsed and then failed a rule is not the same as a body that never parsed, and a client that retries
     * automatically needs to tell them apart. The framework-level bean-validation refusal, by contrast,
     * publishes {@link #ERROR_CODE_VALIDATION} - the same code the service-tier refusal publishes - so a
     * caller sees one code per condition rather than one code per layer that happened to catch it.
     */
    private static final String ERROR_CODE_UNREADABLE_BODY = "CARDDEMO-REQUEST-BODY-UNREADABLE";

    /**
     * Logger for this controller. Static and final, so it is shared state that cannot be reassigned and is
     * not mutable application state.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /**
     * The sign-on service replacing {@code app/cbl/COSGN00C.cbl}. Final and constructor-injected, so this
     * controller holds no mutable state of any kind and every instance is safe to share across concurrent
     * requests.
     */
    private final AuthenticationService authenticationService;

    /**
     * Creates the controller with its single collaborator.
     *
     * <p>Constructor injection is the only injection this class supports: there is no field injection, no
     * setter injection and no service lookup, so an instance cannot exist in a half-wired state and a unit
     * test can build one with a single stub and no application context. The argument is validated rather than
     * trusted, because a null collaborator would otherwise surface as a failure on the first request instead
     * of at context refresh.
     *
     * @param authenticationService the sign-on service replacing {@code app/cbl/COSGN00C.cbl}; must not be
     *                              null.
     * @throws IllegalArgumentException if the service is null, which is a bean-wiring defect rather than a
     *                                  request-time condition
     */
    public AuthController(final AuthenticationService authenticationService) {

        if (authenticationService == null) {
            throw new IllegalArgumentException(
                    "authenticationService must not be null; it is the replacement for "
                            + "app/cbl/COSGN00C.cbl");
        }

        this.authenticationService = authenticationService;
    }

    /**
     * Operation 1 of 1 on this controller, and operation 1 of the seventeen the REST surface exposes. Signs a
     * user on and returns a bearer token.
     *
     * <h4>Provenance</h4>
     *
     * <p>CSD transaction {@code CC00}, defined at {@code app/csd/CARDDEMO.CSD:L378} with
     * {@code PROGRAM(COSGN00C)} at {@code :L379}; COBOL program {@code app/cbl/COSGN00C.cbl} (260 lines);
     * BMS mapset {@code COSGN00}, whose eleven input fields at {@code app/cpy-bms/COSGN00.CPY} fix the shape
     * of the request record @ 7756d89.
     *
     * <h4>Purpose</h4>
     *
     * <p>To establish an identity. It is the one operation a caller can reach without already holding one,
     * and the token it returns is what every other operation on the surface requires.
     *
     * <h4>Inputs</h4>
     *
     * <p>One JSON body deserialised into the request record. Of its eleven components only {@code userId} and
     * {@code password} are operative; the remaining nine are the terminal-header metadata the 3270 screen
     * supplied - the transaction name, two title lines, the program name, the current date, the current time
     * (the only field in the whole corpus declared {@code PIC X(9)} rather than {@code X(8)}, at
     * {@code app/cpy-bms/COSGN00.CPY:L54}), the application and system identifiers, and the error-message
     * line - and this operation consumes none of them.
     *
     * <p><strong>The body is passed to the service exactly as received.</strong> Nothing here trims, pads,
     * folds, defaults or rewrites a value. The legacy program folded both credentials to upper case, the
     * identifier at {@code app/cbl/COSGN00C.cbl:L132-L134} and the password at {@code :L135-L136}, and that
     * folding is reproduced once, in {@code CardDemoUserDetailsService}, which the service delegates to. A
     * second normalisation site here could disagree with the first, and a disagreement about case is a
     * disagreement about which credentials verify.
     *
     * <p>Absent, blank and low-values remain three distinct states all the way through. Nothing here coerces
     * a null into an empty string or the reverse, which is what lets the service reproduce the source's
     * distinction between an input that was not supplied and one that was supplied wrongly.
     *
     * <h4>Outputs</h4>
     *
     * <p>{@code 200 OK} with the issued token, the identifier as the application folded it, and the
     * single-character user class from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}.
     * That class is the surviving half of the transfer of control at {@code app/cbl/COSGN00C.cbl:L231} and
     * {@code :L236}: the client reads it and navigates to the administrator or the main menu itself.
     *
     * <p><strong>The response never carries a password, a hash or any {@code SEC-USR-PWD} value.</strong> The
     * request record's password component is write-only for serialisation, so it cannot be echoed even by
     * accident, and no field of the response is derived from it.
     *
     * <h4>Side effects</h4>
     *
     * <p>None that outlive the request. The operation is read-only against the user security store: it
     * writes no row, creates no session, sets no cookie and mutates no application state. Its only lasting
     * product is the token itself, which lives with the client. A token cannot be revoked before it expires,
     * which is why its lifetime is bounded by configuration rather than left open.
     *
     * <h4>Configuration and defaults</h4>
     *
     * <p>This method reads no property. Token issuance is governed by
     * {@code carddemo.security.jwt.signing-key}, which is bound to {@code JWT_SIGNING_KEY} with <strong>no
     * default and fail-fast startup</strong>, together with {@code carddemo.security.jwt.issuer} defaulting
     * to {@code carddemo} and {@code carddemo.security.jwt.expiration-minutes} defaulting to {@code 30}
     * minutes, all declared in {@code src/main/resources/application.yml} and consumed by
     * {@code SecurityConfig} and {@code JwtTokenProvider}.
     *
     * <h4>Failure modes and troubleshooting</h4>
     *
     * <p>Each typed failure is answered by the correspondingly named mapper on this class, and each answer
     * carries an {@code errorCode} and the request's {@code correlationId}. A blank identifier or password
     * answers {@code 400}; a refused credential answers {@code 401}; a store that cannot be opened or read
     * answers {@code 503}; an abend or an unclaimed typed failure answers {@code 500}. The class-level
     * documentation tabulates each with its legacy caption and the action to take.
     *
     * <h4>Deliberate deviation from the source - user-enumeration avoidance</h4>
     *
     * <p>The 3270 screen told the operator which half of the credential was wrong. It moved
     * {@code 'Wrong Password. Try again ...'} into the message field at
     * {@code app/cbl/COSGN00C.cbl:L242-L243} when the identifier resolved to a row but
     * {@code IF SEC-USR-PWD = WS-USER-PWD} at {@code :L223} failed, and
     * {@code 'User not found. Try again ...'} at {@code :L249} when the read returned response code 13 at
     * {@code :L247}.
     *
     * <p><strong>This endpoint does not differentiate them.</strong> Both answer {@code 401} with the same
     * title, the same fixed detail, the same {@code errorCode} and no field property, because a response
     * that distinguishes them lets an unauthenticated caller enumerate valid identifiers one request at a
     * time.
     *
     * <p>The collapse is complete rather than cosmetic, and it is worth being exact about how little
     * survives. The service folds both conditions into a single catch arm that raises one exception type
     * carrying one rendered literal - the wrong-password caption - and marks one field, and it performs the
     * same password-hashing work on both so that response timing cannot become the oracle the text refuses
     * to be. The not-found caption of {@code :L249} therefore appears nowhere in the target at all. What does
     * survive is the caught exception, retained as the raised exception's cause and emitted by the mapper on
     * this class at {@code DEBUG} only, so an operator who raises the level for this logger can still tell an
     * unknown identifier from a rejected password while no caller can at any level. The root cause is
     * therefore preserved rather than swallowed, without putting a stack trace in {@code WARN} on every
     * failed sign-on, which an unauthenticated caller could otherwise trigger at will. The decision is
     * stated here, in the docstring of the file it governs, which is the one place it cannot drift away
     * from the code.
     *
     * <p>One asymmetry in the source is worth recording beside it. The wrong-password branch at
     * {@code :L241-L246} never sets {@code WS-ERR-FLG}, while the not-found branch at {@code :L248} and the
     * catch-all branch at {@code :L253} both move {@code 'Y'} into it. In the source that flag gated only
     * the re-read at {@code :L138} within a single pass, so the omission changed nothing observable and is
     * not a defect to reproduce or repair; with the flag itself having no counterpart in a stateless
     * request, the asymmetry is documented here and nowhere else.
     *
     * @param request the sign-on form, bean-validated against the field widths the symbolic map declares.
     *                Its {@code userId} and {@code password} are the operative components; both may be
     *                absent or blank, and the service reports which one in the source's own order
     * @return {@code 200 OK} carrying the issued token, the folded identifier and the single-character user
     *         class
     * @throws ValidationException      if an operative input was absent or blank, or if the credential was
     *                                  refused; answered {@code 400} or {@code 401} respectively by the
     *                                  mapper on this class
     * @throws FileUnavailableException if the user security store could not be opened
     * @throws FileAccessException      if the store was reachable but could not be read
     * @throws FatalProcessingException if the operation ended the way the legacy abend routine ended the
     *                                  task
     */
    @PostMapping(SIGN_ON_PATH)
    public ResponseEntity<SignOnResponse> signOn(@Valid @RequestBody final SignOnRequest request) {

        final SignOnResponse response = authenticationService.signOn(request);

        LOG.info("Completed transaction {} program {} mapset {}: identity established for user class {}",
                TRANSACTION_ID, PROGRAM_NAME, MAPSET_NAME, response.userType());

        return ResponseEntity.ok(response);
    }

    /**
     * Maps a validation failure onto {@code 400 Bad Request} when an input was missing and onto
     * {@code 401 Unauthorized} when a credential was refused.
     *
     * <p>The branch is not a guess about the caller's intent: the exception carries the discriminator, whose
     * two values transcribe the outer and inner conditions of the {@code app/cpy/CSSETATY.cpy} error-marker
     * template. The blank value corresponds to the two arms of the {@code EVALUATE} at
     * {@code app/cbl/COSGN00C.cbl:L117-L130}, which refuse an absent identifier at {@code :L118-L122} and an
     * absent password at {@code :L123-L127}; the invalid value corresponds to the credential refusal the
     * service folds together.
     *
     * <p>The two answers publish deliberately different amounts. The missing-input answer names the field and
     * the discriminator and republishes the legacy caption, because telling a caller which box to fill in
     * reveals nothing an unauthenticated caller could not already guess from the schema. The refusal answer
     * publishes a fixed detail, no field and no discriminator, so that it is byte-identical to the answer the
     * absent-record mapper below produces and cannot be told apart from it.
     *
     * <p>This method is not request-mapped and is not one of the seventeen operations: it carries
     * {@code @ExceptionHandler} only, is scoped to this controller alone, and exists because status selection
     * in this tree is contextual - the same typed failure means different things to different operations, so
     * no advice class or shared base decides it centrally.
     *
     * @param rejection the validation failure, carrying the discriminator and, for a missing input, the name
     *                  of the field that was absent
     * @return {@code 400 Bad Request} naming the absent field, or {@code 401 Unauthorized} with a fixed
     *         detail that names nothing
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure(final ValidationException rejection) {

        if (rejection.getFailureKind() == ValidationException.FailureKind.INVALID) {
            LOG.warn("Refused transaction {} program {} with 401: the presented credential did not verify. "
                    + "The unknown-identifier and wrong-password outcomes of app/cbl/COSGN00C.cbl:L241-L251 "
                    + "are answered identically by design", TRANSACTION_ID, PROGRAM_NAME);
            // The root cause is preserved rather than dropped, but it is emitted only at DEBUG. A refused
            // credential is expected traffic, not a defect, so a stack trace on every failed sign-on would
            // both flood WARN and hand an unauthenticated caller a way to inflate the log at will. At DEBUG
            // the cause chain - which is the one place the unknown-identifier and wrong-password conditions
            // remain distinguishable - is available to an operator who deliberately asks for it.
            LOG.debug("Cause chain of the refused credential for transaction {}", TRANSACTION_ID, rejection);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(authenticationFailureBody());
        }

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        final String rejectionMessage = rejection.getMessage();
        if (rejectionMessage != null) {
            problem.setDetail(rejectionMessage);
        }
        if (rejection.hasFieldName()) {
            problem.setProperty(FIELD_PROPERTY, rejection.getFieldName());
        }
        problem.setProperty(FAILURE_KIND_PROPERTY, rejection.getFailureKind().name());

        LOG.warn("Refused transaction {} program {} with 400 for field {} and failure kind {}",
                TRANSACTION_ID, PROGRAM_NAME, rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps an absent record onto {@code 401 Unauthorized} with the same body a refused credential produces.
     *
     * <p><strong>This mapper answers {@code 401} and not {@code 404}, which is a deliberate departure from
     * the rest of the package.</strong> Everywhere else a missing record means a caller asked for something
     * that is not there, and {@code 404} is the honest answer. Here the only record the operation reads is
     * the user security row for the identifier the caller just supplied, so a distinguishable
     * not-found answer would confirm that a given identifier does not exist - reinstating, through a
     * different status code, exactly the enumeration oracle the deviation on the handler removes. The body is
     * produced by the same helper the refusal path uses, so the two cannot drift apart into
     * distinguishable answers later.
     *
     * <p>The mapper is defence in depth rather than a path the service is expected to take. The service
     * folds the response-code-13 arm of {@code app/cbl/COSGN00C.cbl:L247-L251} into a credential refusal
     * itself, but it also rethrows any already-typed failure untouched, so an absent record raised deeper -
     * in a repository or in the credential verifier - would otherwise reach the framework's default handling
     * and be answered {@code 500} or, worse, {@code 404}. Neither is acceptable for this operation.
     *
     * <p>Neither the record type nor the record key is published, and the exception's message is discarded.
     * The key on this path is the submitted identifier, and the type names the backing store.
     *
     * @param absence the absent record, carrying a record type and key that are deliberately not published
     * @return {@code 401 Unauthorized} with a body indistinguishable from the credential-refusal answer
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absence) {

        LOG.warn("Refused transaction {} program {} with 401: a record required by sign-on was absent. "
                + "Answered as a credential refusal so the response cannot confirm whether the identifier "
                + "exists. Record type {}", TRANSACTION_ID, PROGRAM_NAME,
                absence.recordType().orElse(USER_SECURITY_FILE));
        // Preserved at DEBUG for the same reason as the refusal path above: the record key is the submitted
        // identifier, so the detail belongs where an operator must opt in to see it and no caller can.
        LOG.debug("Cause chain of the absent sign-on record for transaction {}", TRANSACTION_ID, absence);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(authenticationFailureBody());
    }

    /**
     * Maps an unopenable user security store onto {@code 503 Service Unavailable}.
     *
     * <p>File status {@code '35'}, the legacy not-open condition, and the recoverable reading of the
     * {@code WHEN OTHER} arm at {@code app/cbl/COSGN00C.cbl:L252-L256}, which moved
     * {@code 'Unable to verify the User ...'} into the message field at {@code :L254}. The legacy program
     * could not distinguish a store that was closed from one that failed mid-read, because both landed in the
     * same arm; the target does distinguish them, and both are answered with the same retryable status
     * because both are transient from the caller's point of view.
     *
     * <p>{@code 503} rather than {@code 500} is the substantive choice here: it tells the caller the request
     * is safe to repeat, which for a read-only operation against a temporarily unreachable store is true. The
     * detail names no dataset, host or schema, while the log line names the logical file so an operator knows
     * where to look.
     *
     * @param unavailable the unavailable resource, carrying a resource name that is logged and not published
     * @return {@code 503 Service Unavailable} with a retryable, topology-free detail
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);
        problem.setDetail(UNAVAILABLE_PROBLEM_DETAIL);

        LOG.error("Failed transaction {} program {} with 503: the {} store could not be opened",
                TRANSACTION_ID, PROGRAM_NAME, unavailable.resourceName().orElse(USER_SECURITY_FILE),
                unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNAVAILABLE));
    }

    /**
     * Maps a failed read of the user security store onto {@code 503 Service Unavailable}.
     *
     * <p>The {@code '9x'} status family, and the half of the {@code WHEN OTHER} arm at
     * {@code app/cbl/COSGN00C.cbl:L252-L256} that the service raises when the store was reachable but the
     * read did not complete. The legacy program abended on this condition; the target answers a retryable
     * status instead, because the operation wrote nothing and repeating it is safe - a substitution of
     * mechanism, not of outcome, since either way no identity is established.
     *
     * <p>The four-character expanded status the exception carries reaches the log and never the response. It
     * describes the storage layer, and an unauthenticated caller has no business reading a description of the
     * storage layer.
     *
     * @param failure the access failure, carrying the expanded status, the logical file and the operation
     * @return {@code 503 Service Unavailable} with a retryable, topology-free detail
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle(ACCESS_PROBLEM_TITLE);
        problem.setDetail(ACCESS_PROBLEM_DETAIL);

        LOG.error("Failed transaction {} program {} with 503: status {} on operation {} against the {} store",
                TRANSACTION_ID, PROGRAM_NAME, failure.getExpandedStatus(), failure.getOperation(),
                failure.getLogicalFileName(), failure);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_IO_FAILURE));
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}.
     *
     * <p>The counterpart of {@code CALL 'CEE3ABD'}, which the corpus reaches with abend code {@code 999} and
     * a return code of {@code 12}. Unlike the two mappers above, this condition is not transient and the
     * request is not safe to repeat: it means the operation met a state it does not describe, such as a user
     * security row carrying no user-class byte at all.
     *
     * <p>The abend code, the culprit program and the reason reach the log, together with the preserved root
     * cause. The response carries a fixed detail and none of them.
     *
     * @param abend the abend, carrying the code, the culprit program, the reason and the message
     * @return {@code 500 Internal Server Error} with a fixed detail and the correlation identifier
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(FAILURE_PROBLEM_TITLE);
        problem.setDetail(FAILURE_PROBLEM_DETAIL);

        LOG.error("Failed transaction {} program {} with 500: abend code {} culprit {} reason {}",
                TRANSACTION_ID, PROGRAM_NAME, abend.getAbendCode(), abend.getAbendCulprit(),
                abend.getAbendReason(), abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps any remaining typed failure onto {@code 500 Internal Server Error}.
     *
     * <p>The terminal mapper of the chain, claiming the base type of the nine-class hierarchy so that a
     * subtype no mapper above names cannot escape to the framework's default handling and answer with a body
     * that does not match this package's shape. Spring resolves the most specific mapper first, so this one
     * runs only for a failure none of the five above claims.
     *
     * <p>It is reachable rather than defensive padding: the hierarchy contains types this operation has no
     * reason to raise but which a shared collaborator legitimately can, and answering them consistently is
     * cheaper than proving a negative about every tier this operation touches. The concrete type reaches the
     * log; the response says only that sign-on could not be completed.
     *
     * @param failure the typed failure no more specific mapper on this class claims
     * @return {@code 500 Internal Server Error} with a fixed detail and the correlation identifier
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle(FAILURE_PROBLEM_TITLE);
        problem.setDetail(FAILURE_PROBLEM_DETAIL);

        LOG.error("Failed transaction {} program {} with 500: unmapped typed failure {}",
                TRANSACTION_ID, PROGRAM_NAME, failure.getClass().getSimpleName(), failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Maps a bean-validation failure the framework raised on the request body onto {@code 400 Bad Request}
     * with this controller's own envelope.
     *
     * <p><strong>Why this mapper exists.</strong> {@code @Valid} on the request body is enforced by the
     * framework <em>before</em> the mapped method is entered, so a violation never reaches the service and
     * never becomes the {@link ValidationException} the mapper above claims. Without this method the refusal
     * escaped to the framework's default handling and answered with a body of an entirely different shape -
     * no {@code title}, no {@code errorCode} and no {@code correlationId} - so one logical outcome, a rejected
     * input, looked like two unrelated failures depending on which layer noticed it. A review recorded that
     * inconsistency as a High-severity finding against this class.
     *
     * <p><strong>Why it is declared here rather than centrally.</strong> The envelope is per-controller by
     * design: the title names the resource, so a single advice class could not produce it without being told
     * which controller it was answering for. This package declares no {@code @ControllerAdvice} and no shared
     * base class, and this method keeps that property - it carries {@code @ExceptionHandler} only and is
     * scoped to this controller alone, exactly like the typed mappers above it.
     *
     * <p><strong>What the body does not contain.</strong> No rejected value, no field name, no constraint
     * message, no exception class and no discriminator; {@link #BIND_FAILURE_PROBLEM_DETAIL} records why each
     * is withheld. The discriminator in particular is omitted rather than invented: the framework supplies no
     * counterpart to the two-state marker of {@code app/cpy/CSSETATY.cpy}, so publishing one would misreport
     * it.
     *
     * <p>This method is not request-mapped and is not one of the seventeen operations.
     *
     * @param rejection the framework's binding result, read for the log line only - its field errors retain
     *                  the submitted values, so nothing is copied out of them into the body
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the rejection
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyBindFailure(
            final MethodArgumentNotValidException rejection) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);
        problem.setDetail(BIND_FAILURE_PROBLEM_DETAIL);

        LOG.warn("Refused transaction {} program {} with 400: the framework rejected {} field(s) of the "
                        + "request body before the operation was entered. Fields {}",
                TRANSACTION_ID, PROGRAM_NAME, rejection.getBindingResult().getFieldErrorCount(),
                rejection.getBindingResult().getFieldErrors().stream()
                        .map(FieldError::getField).distinct().sorted().toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps a request body the framework could not read onto {@code 400 Bad Request} with this controller's
     * own envelope.
     *
     * <p>Claims the one exception the framework folds three conditions into, all of which occur before the
     * mapped method is entered: a body that is not well-formed JSON, a body carrying a property outside the
     * schema, and a request with no body where {@code @RequestBody} requires one. Each was previously answered
     * by the framework's default handling, in a shape no client-side handler written against this package's
     * envelope could read - the second half of the same High-severity finding.
     *
     * <p>The status is {@code 400} rather than {@code 415} or {@code 422}: the caller addressed the right
     * operation with the right media type and sent something this operation cannot accept, which is precisely
     * a bad request. Answering it identically to a bean-validation refusal is deliberate, and the two are told
     * apart by {@link #ERROR_CODE_UNREADABLE_BODY} rather than by the status line.
     *
     * <p>This method is not request-mapped and is not one of the seventeen operations.
     *
     * @param unreadable the framework's read failure, whose message and cause chain are deliberately kept out
     *                   of the body and emitted at {@code DEBUG} only
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the parser
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            final HttpMessageNotReadableException unreadable) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);
        problem.setDetail(UNREADABLE_BODY_PROBLEM_DETAIL);

        LOG.warn("Refused transaction {} program {} with 400: the request body could not be read. Read "
                + "failure {}", TRANSACTION_ID, PROGRAM_NAME, unreadable.getClass().getSimpleName());
        // The cause chain is preserved rather than dropped, but it is emitted only at DEBUG, for the same
        // reason the refused-credential path above does the same. A parser message can quote the fragment of
        // the payload it stopped on, and the payload of this operation carries a plaintext password, so it
        // must not be written at a level that is enabled in every environment. At DEBUG an operator who
        // deliberately asks for the chain gets all of it.
        LOG.debug("Cause chain of the unreadable body for transaction {}", TRANSACTION_ID, unreadable);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNREADABLE_BODY));
    }

    /**
     * Builds the one body every credential refusal answers with, whatever raised it.
     *
     * <p>Both {@code 401} paths on this class call this method and neither builds a body of its own, which is
     * what makes the two answers identical by construction rather than by two implementations that happen to
     * agree today. It publishes a fixed title and a fixed detail, and it deliberately sets no field property
     * and no discriminator property, so nothing in the body varies with which condition occurred.
     *
     * @return a problem body for {@code 401 Unauthorized}, carrying the shared envelope and nothing that
     *         identifies the cause
     */
    private static ProblemDetail authenticationFailureBody() {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle(AUTHENTICATION_PROBLEM_TITLE);
        problem.setDetail(AUTHENTICATION_PROBLEM_DETAIL);

        return withPublicEnvelope(problem, ERROR_CODE_AUTHENTICATION);
    }

    /**
     * Adds the two properties every problem body on this controller publishes, matching the envelope the
     * sibling controllers produce so that one client-side error handler works across the whole surface.
     *
     * <p>Called by each mapper above as the last thing it does to the body, so a future mapper that forgets
     * the envelope is visibly inconsistent with the others rather than subtly so.
     *
     * <p>The correlation identifier is <em>read</em> from the diagnostic context that
     * {@code CorrelationIdFilter} populates for the request. This method does not add, rename, overwrite,
     * remove or clear any key in that context: the filter owns the key set, and reading it is what makes a
     * failure response tie back to the structured log line for the same request. When the value is absent -
     * which can only happen if this handler runs outside that filter - the literal
     * {@value #CORRELATION_ID_UNAVAILABLE} is published so the property is always present and a client never
     * has to branch on its absence.
     *
     * @param problem   the problem body to enrich; must not be null
     * @param errorCode the stable machine-readable failure code for the condition being answered
     * @return the same problem body, enriched with the failure code and the correlation identifier
     */
    private static ProblemDetail withPublicEnvelope(final ProblemDetail problem, final String errorCode) {

        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        problem.setProperty(CORRELATION_ID_PROPERTY,
                correlationId == null || correlationId.isEmpty() ? CORRELATION_ID_UNAVAILABLE : correlationId);

        return problem;
    }
}
