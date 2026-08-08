/*
 * ******************************************************************
 * Program     : AdminController.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 REST Controller
 * Function    : User administration endpoints (list, add, update, delete) under /api/admin/*,
 *               restricted to the administrator role.
 * Source      : app/csd/CARDDEMO.CSD transactions CU00, CU01, CU02, CU03
 *               -> app/cbl/COUSR00C.cbl (695), COUSR01C.cbl (299), COUSR02C.cbl (414),
 *                  COUSR03C.cbl (359), mapsets COUSR00, COUSR01, COUSR02, COUSR03 @ 7756d89
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.dto.UserCreateResponse;
import com.cardemo.model.dto.UserListResponse;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.dto.UserUpdateResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;

/**
 * The user-administration resource group: the four CICS user transactions reached over HTTP as four
 * operations, and the only administrator-restricted controller in this package.
 *
 * <h2>What it does</h2>
 *
 * <p>Replaces four CICS transactions defined in {@code app/csd/CARDDEMO.CSD}, each with a program and a
 * mapset, and adds nothing whatever beyond them:</p>
 *
 * <ul>
 *   <li>{@code CU00} at {@code app/csd/CARDDEMO.CSD:L449} fronting {@code PROGRAM(COUSR00C)} at
 *       {@code :L450} - {@code app/cbl/COUSR00C.cbl}, 695 lines and 16 paragraph labels, painting mapset
 *       {@code COUSR00} ten rows at a time. Reached by {@link #listUsers}.</li>
 *   <li>{@code CU01} at {@code app/csd/CARDDEMO.CSD:L459} fronting {@code PROGRAM(COUSR01C)} at
 *       {@code :L460} - {@code app/cbl/COUSR01C.cbl}, 299 lines and 9 paragraph labels, painting mapset
 *       {@code COUSR01}. Reached by {@link #addUser}.</li>
 *   <li>{@code CU02} at {@code app/csd/CARDDEMO.CSD:L469} fronting {@code PROGRAM(COUSR02C)} at
 *       {@code :L470} - {@code app/cbl/COUSR02C.cbl}, 414 lines and 11 paragraph labels, painting mapset
 *       {@code COUSR02}. Reached by {@link #updateUser}.</li>
 *   <li>{@code CU03} at {@code app/csd/CARDDEMO.CSD:L479} fronting {@code PROGRAM(COUSR03C)} at
 *       {@code :L480} - {@code app/cbl/COUSR03C.cbl}, 359 lines and 11 paragraph labels, painting mapset
 *       {@code COUSR03}. Reached by {@link #deleteUser}.</li>
 *   </ul>
 *
 * <p>These four are operations 14, 15, 16 and 17 of the seventeen this package publishes, and they close
 * the inventory: one for authentication, two for the menus, two for accounts, three for cards, three for
 * transactions, one for billing, one for reports and four here is
 * {@code 1 + 2 + 2 + 3 + 3 + 1 + 1 + 4 = 17}. There is no fifth operation on this class and no fifth
 * transaction behind one: the CSD defines eighteen transactions, and the eighteenth is {@code CDV1},
 * described "DEVELOPER TRANSACTION - 1" at {@code app/csd/CARDDEMO.CSD:L388-L391}, whose program
 * {@code COCRDSEC} occurs at exactly two places repository-wide - {@code :L211} and {@code :L390}, both of
 * them the definition itself. Its source member is <b>Not available</b>; what would be needed to close that
 * gap is the member itself, which is absent at every commit in this checkout. A "developer" transaction
 * reads like an administrative function and it is not one, so no endpoint is invented for it here.</p>
 *
 * <p>All four operations read or write the {@code USRSEC} cluster, one of the eight
 * {@code DEFINE FILE} entries in the CSD and catalogued at {@code app/catlg/LISTCAT.txt:L3846} and
 * {@code :L3883}, defined in job control as {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} at
 * {@code app/jcl/DUSRSECJ.jcl:L65-L66}. The four datasets absent from those eight entries -
 * {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} - are batch-only by that very
 * absence, and this class deliberately publishes no operation against any of them. An administrative
 * controller is the likeliest place for such a surface to appear, so its absence is recorded rather than
 * left to inference.</p>
 *
 * <p>This class performs no business logic of any kind. Each operation validates what HTTP itself cannot
 * express, delegates exactly once to the service that owns the transcribed paragraph map, and translates
 * the typed failure hierarchy into a status code. There is no arithmetic here, no repository, no
 * {@code EntityManager}, no {@code JdbcTemplate} and no password encoder.</p>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and unit-test with {@code ./mvnw -B -ntp test}; the full gate is
 * {@code ./mvnw -B -ntp clean verify}, which compiles under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} set, so a single warning in this file fails the build, and which then runs the
 * {@code doclint-gate} execution of the Javadoc plugin with {@code doclint=all} and
 * {@code failOnWarnings=true} over private members as well as public ones. Run the application with
 * {@code java -jar target/carddemo-1.0.0.jar}, or bring the whole topology up with
 * {@code docker compose up -d} first so that PostgreSQL is reachable. Tests for these operations live
 * under {@code src/test/java/com/cardemo/unit} and {@code src/test/java/com/cardemo/e2e} and never in this
 * package; the contract gate drives all four through {@code MockMvc} against a real application context,
 * which this class supports because it holds no static and no per-request state.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This class reads no property and declares none. The one configuration value the user list depends on
 * is the screen depth, {@code carddemo.pagination.user-list-page-size}, declared as {@code 10} in
 * {@code src/main/resources/application.yml} and bound by {@code UserListService} rather than here - that
 * service carries no default for it, so a missing value fails startup instead of substituting a number.
 * <strong>No page-size literal appears anywhere in this file and no client may supply one</strong>; the
 * value in force is reported back on every page through the paging metadata of {@code PageResponse}.
 * BCrypt at strength 10 is likewise configured centrally and applied by {@code UserAddService} and
 * {@code UserUpdateService}, never here: this class never sees a digest and never computes one.
 * Authentication and authorisation are configured centrally in {@code SecurityConfig}, request-parameter
 * conversion in {@code WebConfig}, JSON in the Jackson block of {@code application.yml}, and log masking in
 * {@code logback-spring.xml}; this class overrides none of them.</p>
 *
 * <h2>Access control</h2>
 *
 * <p>Every one of the four operations requires the administrator role, and none of the four is
 * anonymous - only sign-on {@code CC00} is. The rule is declared once, centrally, as a URL rule over
 * {@code /api/admin/**} in {@code SecurityConfig}, which is the closest analogue of a transaction-level
 * CICS check and mirrors the user-type gate at {@code app/cbl/COSGN00C.cbl:L230} that routed only type
 * {@code 'A'} to {@code COADM01C}. The authority itself is the transcription of
 * {@code CDEMO-USER-TYPE PIC X(01)} at {@code app/cpy/COCOM01Y.cpy:L26} with its two condition names at
 * {@code :L27} and {@code :L28}, surfaced as {@code UserType} with {@code 'A'} becoming
 * {@code ROLE_ADMIN} and {@code 'U'} becoming {@code ROLE_USER}.</p>
 *
 * <p><strong>No method-level authorisation annotation appears here, and that is deliberate.</strong>
 * {@code @EnableMethodSecurity} is absent from the tree by design, so a {@code @PreAuthorize} on a handler
 * below would be inert - dead configuration that reads as protection while enforcing nothing, which is a
 * worse outcome than no annotation at all. The single enforcement point therefore stays external, where it
 * cannot be contradicted, widened or silently disabled from this file.</p>
 *
 * <p><strong>Identity is not read here at all.</strong> None of the four services accepts a user
 * identifier or a user type as an argument: the two commarea fields that survive,
 * {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:L25} and {@code CDEMO-USER-TYPE}, are
 * carried as token claims and consumed by the security layer. No handler below takes an
 * {@code Authentication}, none reads a principal out of a request body, and none consults a session -
 * there is none to consult.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every failure below is produced by a service and mapped to a status by an
 * {@code @ExceptionHandler} declared on this class. There is no {@code @ControllerAdvice} and no advice
 * type anywhere in this repository, and the nine exception types carry no annotations at all - in
 * particular no {@code @ResponseStatus} - because status selection is contextual and belongs to the
 * endpoint rather than to the exception.</p>
 *
 * <table border="1">
 *   <caption>Failure to status mapping for this resource group</caption>
 *   <tr><th>Condition</th><th>Type</th><th>Status</th></tr>
 *   <tr><td>A field or control token was rejected, or an action was not recognised</td>
 *       <td>{@code ValidationException}</td><td>400</td></tr>
 *   <tr><td>No such user</td><td>{@code RecordNotFoundException}</td><td>404</td></tr>
 *   <tr><td>The identifier is already taken</td><td>{@code DuplicateRecordException}</td><td>409</td></tr>
 *   <tr><td>File not open, status {@code '35'}</td><td>{@code FileUnavailableException}</td>
 *       <td>503</td></tr>
 *   <tr><td>I/O failure, the {@code '9x'} family</td><td>{@code FileAccessException}</td><td>502</td></tr>
 *   <tr><td>Unexpected status, abend 999</td><td>{@code FatalProcessingException}</td><td>500</td></tr>
 *   <tr><td>Any remaining typed failure</td><td>{@code CardDemoException}</td><td>500</td></tr>
 * </table>
 *
 * <p>The legacy file-status vocabulary behind that table is the universal guard idiom of the corpus:
 * {@code '00'} continues, {@code '10'} is end of file and terminates a loop rather than failing,
 * {@code '23'} is a record that does not exist, {@code '22'} is a duplicate key, {@code '35'} is a file
 * that is not open, the {@code '9x'} family is a physical or logical input-output failure, and anything
 * else abends with code {@code 999} and return code {@code 12}. The three sites at which {@code '23'} is
 * an accepted control path rather than an error - the transaction-category-balance upsert, the
 * disclosure-group default fallback and the file-service accepted secondary status - are all in the batch
 * tier, and <strong>none of them is reachable from this controller</strong>. Batch reject codes are
 * business outcomes that drive an exit status and are never thrown and never mapped to a status, so no
 * reject code appears anywhere in this file.</p>
 *
 * <p>To troubleshoot, start from the {@code correlationId} on the response body and the matching log line:
 * the filter that supplies it, {@code CorrelationIdFilter}, also supplies {@code traceId} and
 * {@code spanId}, and this class neither adds to, renames, overwrites nor clears that context. A 400
 * carries the rejected field name and the two-state failure kind, which is what keeps a field that was
 * left blank distinguishable from one that was supplied and wrong. A 409 on the add operation is the
 * identifier collision and nothing else. A 502 carries no expanded file status, no logical file and no
 * operation - all three are logged instead - and a 500 carries neither the abend code nor the return code,
 * for the same reason.</p>
 *
 * <p>Four boundary conditions are answers rather than faults, and are worth stating because a caller can
 * otherwise mistake them for defects. A page beyond the last one, and an empty result set, both return
 * {@code 200} with no rows and the next-page flag false; the source reports the same condition as a screen
 * message rather than as an error. Page number zero is inside the domain and meaningful, not a missing
 * value: {@code WS-PAGE-NUM PIC S9(04) COMP VALUE ZEROS} at {@code app/cbl/COUSR00C.cbl:L54} starts at
 * zero and {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code :L70} is left at zero by an empty browse, so
 * zero is what a first request sends and what an empty file sends back. Paging backward from the first page
 * and forward from the last both return the same page with the source's own advisory message. And a total
 * element count and a total page count are <b>Not available</b> by design, because the browse never
 * counts - it looks ahead exactly one record - so supplying either would need a query the legacy program
 * never issues.</p>
 *
 * <p><strong>Absent, blank and populated are three distinct states on every field, and none is ever
 * converted into another.</strong> A parameter that was not sent arrives as {@code null}; one sent empty
 * arrives as an empty string; the three-state model is the transcription of {@code app/cpy/CSSETATY.cpy},
 * whose {@code COPY ... REPLACING} template distinguishes not-ok from blank from valid, and it reaches a
 * client as the failure kind on a 400. Nothing here trims, pads, case-folds or defaults a value on its way
 * to a service.</p>
 *
 * <h2>Deviations from the source, and preserved quirks</h2>
 *
 * <p>Each item below is a deliberate difference from the system of record, or a source behaviour preserved
 * because parity is the contract. Nothing here is a silent substitution.</p>
 *
 * <ul>
 *   <li><strong>The presented credential used to travel beside the add request body on a bespoke
 *       {@code X-Presented-Password} header, and now travels inside the body alone.</strong> The header
 *       existed because {@code UserCreateRequest} published no read path to its own write-only credential
 *       member, so the body member was bound and then ignored. That traded one exposure for two worse ones:
 *       generic ingress, proxy and APM redaction recognises the standard authorization, cookie and
 *       body-password channels but not a project-invented header name, so the credential travelled through
 *       the channel least likely to be scrubbed; and the audited value - the body - could differ from the
 *       value actually hashed, because the two arrived independently. The header, its constant and its
 *       binding are removed outright, and {@code UserCreateRequest} publishes exactly one narrowly-scoped
 *       read path, {@code mapPassword(Function)}, which hands the value to a reader this class supplies
 *       rather than returning it - so it is neither a JavaBean getter nor a zero-argument method, and is
 *       therefore invisible to serializers, reflective bean mappers, {@code toString} generators and
 *       property-walking loggers alike. {@link #addUser} relays that single body value to the service, so the
 *       value hashed is by construction the value bound. Decision log entry: <em>credential transported
 *       inside the add body</em>.</li>
 *   <li><strong>High - both PF3 and PF5 perform the same update paragraph, and splitting them would
 *       invent an eighteenth operation.</strong> {@code app/cbl/COUSR02C.cbl:L112} and {@code :L122} both
 *       {@code PERFORM UPDATE-USER-INFO}; the arms differ only in what happens afterwards, and what happens
 *       afterwards is navigation, which has no Java counterpart because routing is URL-based under
 *       transformation rule 7. Remediation, applied: the two arms collapse into the single operation
 *       {@link #updateUser}. Decision log entry: <em>PF3 and PF5 collapse to one update</em>.</li>
 *   <li><strong>Medium, resolved - the update and the delete had no concurrency control of any kind, and
 *       now hold the row exactly as the source did.</strong> Both routes were a read followed by a write with
 *       nothing between them, so two administrators could each read the same {@code user_security} row and the
 *       second write would silently discard the first, losing a role change, a name change or a password
 *       digest. Remediation, applied: both services read through
 *       {@code com.cardemo.repository.UserSecurityRepository#findByIdForUpdate(String)}, a pessimistic write
 *       read that is the direct analogue of the {@code EXEC CICS READ ... UPDATE} both source programs issue -
 *       {@code app/cbl/COUSR02C.cbl:L322-L328} before its rewrite at {@code :L360}, and
 *       {@code app/cbl/COUSR03C.cbl:L269-L275} before its delete at {@code :L307} - against a file
 *       {@code app/csd/CARDDEMO.CSD:L88-L89} defines with {@code UPDATEMODEL(LOCKING)}. A version column was
 *       the alternative and is not available: {@code user_security} has exactly five columns under
 *       {@code ddl-auto: validate}, the migration set is fixed at three members, and a repository integration
 *       test asserts the table has no version column. What remains unengaged is the <em>business-level</em>
 *       snapshot layer, and only because no per-user read transaction exists to issue a sealed snapshot from;
 *       that is stated on {@link #NO_CLIENT_SNAPSHOT}. Decision log entry: <em>user update and delete hold the
 *       row for update</em>.</li>
 *   <li><strong>Medium - deleting a user is not guarded against deleting yourself, and adding a guard is
 *       forbidden.</strong> {@code app/cbl/COUSR03C.cbl} never compares the target identifier against the
 *       signed-on one; the proof is that {@code CDEMO-USER-ID} occurs zero times in all 359 of its lines.
 *       Parity is the contract, so the absence is preserved and no comparison against the token subject and
 *       no {@code 403} for a self-delete appears anywhere below.</li>
 *   <li><strong>A delete that fails for any reason other than "not found" reports
 *       {@code 'Unable to Update User...'}, naming the wrong verb.</strong> The literal is at
 *       {@code app/cbl/COUSR03C.cbl:L332}, on the {@code WHEN OTHER} arm reached at {@code :L329}. It is
 *       relayed byte for byte, because the parity comparison is made on text and correcting the verb would
 *       change output the gate measures.</li>
 *   <li><strong>A duplicate identifier on the add operation reports {@code 'User ID already exist...'},
 *       which is not grammatical.</strong> The literal is at {@code app/cbl/COUSR01C.cbl:L263}, reached from
 *       both {@code WHEN DFHRESP(DUPKEY)} at {@code :L260} and {@code WHEN DFHRESP(DUPREC)} at
 *       {@code :L261}. "exist" is relayed exactly as written and is not corrected to "exists".</li>
 *   <li><strong>Three screen behaviours have no counterpart and are omitted rather than
 *       approximated.</strong> {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} at
 *       {@code app/cbl/COUSR02C.cbl:L120} and at {@code app/cbl/COUSR03C.cbl:L119} blanks a terminal map,
 *       which a stateless client does by discarding its own form; cursor repositioning, the
 *       {@code MOVE -1 TO USERIDL} at {@code app/cbl/COUSR01C.cbl:L264}, the {@code MOVE -1 TO USRIDINL} at
 *       {@code app/cbl/COUSR03C.cbl:L326} and the {@code MOVE -1 TO FNAMEL} at {@code :L333}, names the
 *       field at fault, which the failure-kind and field-name members of a 400 carry instead; and row
 *       selection, {@code USER-SEL PIC X(01)} at {@code app/cbl/COUSR00C.cbl:L58}, becomes the caller
 *       naming an identifier on a subsequent call. <strong>No endpoint is added for any of the three</strong>,
 *       because each would be an eighteenth operation with no CSD transaction behind it.</li>
 *   </ul>
 *
 * <h2>Every response is API-owned, and the service records never reach a serializer</h2>
 *
 * <p>The four services return records that are faithful to the 3270 turn: they carry the six recurring header
 * fields, the attribute byte an {@code ERRMSGC} move set, the field a {@code MOVE -1} parked the cursor on, the
 * advisory program an {@code EXEC CICS XCTL} would have transferred to, the erase and send counters, the
 * control-transferred and transfer-requested flags, and the row selector. That fidelity is deliberate and it is
 * an <strong>in-process contract</strong>. Three of the four operations previously returned those records
 * directly, which published terminal and navigation state as the public REST contract - a High-severity
 * API-contract defect with a CWE-200 aspect, and an accidental long-term compatibility commitment to the
 * services' internal shape. Each of the three now projects onto an API-owned payload inside this class:
 * {@code UserListResponse}, {@code UserCreateResponse} and {@code UserUpdateResponse}, each publishing only
 * business fields, the source's own message and fixed page metadata, and each naming what it withholds in a
 * {@code WITHHELD_COMPONENTS} list a test asserts against. The delete operation already answered a payload from
 * {@code com.cardemo.model.dto}, {@code UserSecurityDto.UserDeleteScreen}, whose eleven components are the
 * symbolic map's own fields with no navigation, colour, cursor or selector member among them, so it is
 * unchanged.</p>
 *
 * <h2>State and thread safety</h2>
 *
 * <p>Four final collaborators, assigned once by the constructor, and no other field: no static mutable
 * state, no instance mutable state, no per-request field and no session. That is what transformation rule 7
 * requires - {@code EXEC CICS RETURN TRANSID ... COMMAREA} becomes stateless REST plus token claims - and it
 * is why every operation below is safe on any number of concurrent request threads. The paging cursor the
 * source held in {@code WS-COMMAREA} travels as request parameters and response metadata instead, and the
 * pre-selected identifier the update screen recovered from {@code CDEMO-CU02-USR-SELECTED} at
 * {@code app/cbl/COUSR02C.cbl:L99-L103} is simply the caller naming it on the request. The commarea fields
 * with no counterpart at all - {@code CDEMO-FROM-TRANID X(04)} at {@code app/cpy/COCOM01Y.cpy:L21},
 * {@code CDEMO-FROM-PROGRAM X(08)} at {@code :L22}, {@code CDEMO-TO-TRANID X(04)} at {@code :L23},
 * {@code CDEMO-TO-PROGRAM X(08)} at {@code :L24}, {@code CDEMO-PGM-CONTEXT 9(01)} at {@code :L29} with its
 * two condition names at {@code :L30} and {@code :L31}, {@code CDEMO-LAST-MAP X(7)} at {@code :L43} and
 * {@code CDEMO-LAST-MAPSET X(7)} at {@code :L44} - appear in no request and no response this class
 * declares. That bites hardest on the update, whose PF3 arm writes {@code CDEMO-TO-PROGRAM} at
 * {@code app/cbl/COUSR02C.cbl:L114} and {@code :L117}: that write has no Java counterpart.</p>
 */
@RestController
@RequestMapping(AdminController.BASE_PATH)
public class AdminController {

    /**
     * Diagnostic logger. The four legacy programs have no instrumentation of any kind - their only output is
     * the 3270 screen and, on one arm, a {@code DISPLAY 'RESP:' ... 'REAS:'} to the region log at
     * {@code app/cbl/COUSR03C.cbl:L330} - so every use of this logger is new capability rather than a
     * transcription. No credential, no digest, no personal name and no user identifier value is written to
     * any of them: the diagnostics name fields, operations and outcomes only.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    /**
     * The resource-group path. One controller per resource group, so the four user-administration
     * transactions share this prefix and no other controller uses it.
     *
     * <p>The prefix is load bearing rather than cosmetic: {@code SecurityConfig} restricts
     * {@code /api/admin/**} to the administrator authority, so an operation published outside it would fall
     * under the rule for ordinary transactions and become reachable by a standard user.</p>
     */
    static final String BASE_PATH = "/api/admin/users";

    /**
     * The single-user path, relative to {@value #BASE_PATH}, carrying the eight-character key of
     * {@code SEC-USR-ID PIC X(08)} as a path variable.
     *
     * <p>The update and the delete address one record each, so the identifier belongs in the path rather
     * than in a query string or a body member: it is what is being acted upon, and putting it in the path
     * keeps the two operations addressable and cacheable-by-identity in the ordinary HTTP sense.</p>
     */
    static final String USER_PATH = "/{userId}";


    /** CSD transaction identifier for the user list, {@code app/csd/CARDDEMO.CSD:L449}. */
    private static final String USER_LIST_TRANSACTION_ID = "CU00";

    /** CSD transaction identifier for the user add, {@code app/csd/CARDDEMO.CSD:L459}. */
    private static final String USER_ADD_TRANSACTION_ID = "CU01";

    /** CSD transaction identifier for the user update, {@code app/csd/CARDDEMO.CSD:L469}. */
    private static final String USER_UPDATE_TRANSACTION_ID = "CU02";

    /** CSD transaction identifier for the user delete, {@code app/csd/CARDDEMO.CSD:L479}. */
    private static final String USER_DELETE_TRANSACTION_ID = "CU03";

    /** The user-list program. {@code PROGRAM(COUSR00C)} at {@code app/csd/CARDDEMO.CSD:L450}. */
    private static final String USER_LIST_PROGRAM = "COUSR00C";

    /** The user-add program. {@code PROGRAM(COUSR01C)} at {@code app/csd/CARDDEMO.CSD:L460}. */
    private static final String USER_ADD_PROGRAM = "COUSR01C";

    /** The user-update program. {@code PROGRAM(COUSR02C)} at {@code app/csd/CARDDEMO.CSD:L470}. */
    private static final String USER_UPDATE_PROGRAM = "COUSR02C";

    /** The user-delete program. {@code PROGRAM(COUSR03C)} at {@code app/csd/CARDDEMO.CSD:L480}. */
    private static final String USER_DELETE_PROGRAM = "COUSR03C";

    /** The mapset the user list painted, ten rows at a time. */
    private static final String USER_LIST_MAPSET = "COUSR00";

    /** The request-parameter name of the navigation intent, and the field name reported when it is wrong. */
    private static final String ACTION_PARAMETER = "action";

    /**
     * The request-parameter name of the identifier filter, {@code USRIDINI PIC X(8)} of
     * {@code app/cpy-bms/COUSR00.CPY:78}, which the source uses to start the browse from a named user.
     */
    private static final String USER_ID_FILTER_PARAMETER = "userId";

    /**
     * The request-parameter name of the page cursor being echoed back, the transcription of
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COUSR00C.cbl:L70}.
     */
    private static final String PAGE_PARAMETER = "page";

    /**
     * The request-parameter name of the first identifier displayed, {@code CDEMO-CU00-USRID-FIRST PIC X(08)}
     * at {@code app/cbl/COUSR00C.cbl:L68}, which is what a page-backward repositions from.
     */
    private static final String FIRST_KEY_PARAMETER = "firstKey";

    /**
     * The request-parameter name of the last identifier displayed, {@code CDEMO-CU00-USRID-LAST PIC X(08)}
     * at {@code app/cbl/COUSR00C.cbl:L69}, which is what a page-forward repositions from.
     */
    private static final String LAST_KEY_PARAMETER = "lastKey";

    /**
     * The first page the browse can serve: one.
     *
     * <p>Zero is the unstarted browse the service reports before any page has been sent, so the first
     * <em>served</em> page is one. Both are positions a space-filled key reaches without a cursor, which is
     * why {@link #requirePositioningCursor} admits them.
     */
    private static final int FIRST_PAGE_NUMBER = 1;

    /**
     * The request-parameter name of the next-page flag, {@code CDEMO-CU00-NEXT-PAGE-FLG} at
     * {@code app/cbl/COUSR00C.cbl:L71-L73}, which the source tests before it pages forward.
     */
    private static final String NEXT_PAGE_PARAMETER = "nextPageAvailable";

    /**
     * The request-parameter name of the number of rows the previous response displayed.
     *
     * <p>It is needed because {@code PROCESS-PF8-KEY} advances from the <em>last row of the displayed
     * page</em>, not from the head of the next block: the source knows how full the page was because the map
     * is still in its storage, and a stateless request has to say so. A client echoes back the size of the
     * row list it was given.</p>
     */
    private static final String ROW_COUNT_PARAMETER = "rowCount";

    /**
     * The request-parameter name of the delete confirmation, and the field name reported when it is absent.
     */
    private static final String CONFIRMED_PARAMETER = "confirmed";

    /** The path-variable and field name of the eight-character user identifier acted upon. */
    private static final String USER_ID_PATH_VARIABLE = "userId";

    /**
     * The absent business-level snapshot handed to the update service, named rather than written as a bare
     * {@code null} so that the reason it is absent is readable at the call site.
     *
     * <p>{@code app/cbl/COUSR02C.cbl} submitted no snapshot: it read the row with
     * {@code EXEC CICS READ ... UPDATE} at {@code :L322-L328} under {@code UPDATEMODEL(LOCKING)} and rewrote it
     * at {@code :L360}, so an exclusive record hold - not a comparison - was what stopped a second task
     * interleaving. The service reproduces that hold with a pessimistic write read inside its own transaction,
     * which is the store-level guard this operation relies on.
     *
     * <p>The business-level comparison layer remains available on the service and stays unused from here for a
     * structural reason worth stating rather than leaving to be inferred: a caller can only state what it was
     * last shown if some operation showed it, and {@code app/csd/CARDDEMO.CSD} defines no per-user read
     * transaction to be that operation - {@code CU00} lists, {@code CU01} adds, {@code CU02} updates and
     * {@code CU03} deletes. The account and card updates do carry a sealed as-displayed snapshot because their
     * own CSD transactions {@code CAVW} and {@code CCDL} issue one: each read publishes an opaque
     * {@code snapshot} string, and each write binds that same string as a declared member of its request body.
     * Adding an eighteenth route here to close that gap would exceed the declared endpoint set, so the gap is
     * covered by the lock and disclosed instead.
     */
    private static final UserUpdateService.UserSnapshot NO_CLIENT_SNAPSHOT = null;

    /** The first of the two tokens a boolean-valued parameter accepts, and one of only two. */
    private static final String TRUE_TOKEN = "true";

    /** The second of them. */
    private static final String FALSE_TOKEN = "false";

    /**
     * The longest page token accepted before the value is parsed at all.
     *
     * <p>Eight digits, which is the declared width of {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at
     * {@code app/cbl/COUSR00C.cbl:L70} and therefore the widest value the browse cursor could ever have
     * held. The bound exists so that an arbitrarily long digit run is refused as out of range rather than
     * overflowing a parse; the domain check itself belongs to {@code UserListService}, which states and cites
     * it, so it is not restated here. It is emphatically <strong>not</strong> a page size: no page size
     * appears in this file.</p>
     */
    private static final int MAXIMUM_PAGE_TOKEN_DIGITS = 8;

    /**
     * The longest row-count token accepted before the value is parsed at all.
     *
     * <p>Four digits, the declared width of {@code WS-IDX PIC S9(04) COMP} at
     * {@code app/cbl/COUSR00C.cbl:L53} - the row index that drives both the projection loop and the
     * initialisation loop, and therefore the widest row ordinal the program can hold. The bound is load
     * bearing for a second reason as well: the echoed row count decides how many placeholder rows are
     * materialised for the service, so an unbounded token would let one request ask for an unbounded
     * allocation. A count above the rows the screen paints is refused by {@code UserListService}, which owns
     * that limit and cites it; this bound only keeps an untrusted digit run from being believed on the way
     * there. It is <strong>not</strong> a page size.</p>
     */
    private static final int MAXIMUM_ROW_COUNT_TOKEN_DIGITS = 4;

    /** Problem-detail title for a rejected user-administration request. */
    private static final String VALIDATION_PROBLEM_TITLE = "User administration request rejected";

    /** Problem-detail title for a user record that does not exist. */
    private static final String NOT_FOUND_PROBLEM_TITLE = "User record not found";

    /** Problem-detail title for a user identifier that is already taken. */
    private static final String DUPLICATE_PROBLEM_TITLE = "User identifier already taken";

    /**
     * Problem title used when a constraint other than the primary key refused the write.
     *
     * <p>Deliberately distinct from {@value #DUPLICATE_PROBLEM_TITLE}. A KSDS could refuse a keyed write only
     * for a key that already existed, so {@code app/cbl/COUSR01C.cbl} has one duplicate arm and one catch-all;
     * a relational table refuses the same row for constraints VSAM never had, and reporting one of those as
     * "identifier already taken" states something false about an identifier that is in fact free.
     */
    private static final String INTEGRITY_PROBLEM_TITLE = "User write refused by a constraint";

    /** Problem-detail title for a security file that is not available. */
    private static final String UNAVAILABLE_PROBLEM_TITLE = "Security file unavailable";

    /** Problem-detail title for the {@code FILE STATUS '9x'} family, answered with {@code 502}. */
    private static final String IO_PROBLEM_TITLE = "Security data store input-output failure";

    /** Problem-detail title for any user-administration operation that failed outright. */
    private static final String FAILURE_PROBLEM_TITLE = "User administration operation failed";

    /**
     * The fixed detail returned when a user record does not exist.
     *
     * <p><strong>Fixed rather than relayed, on a type rule rather than a path argument.</strong> This
     * exception type is one of the five {@code FileStatusMapper} composes, and the message that mapper
     * composes names the operation, the logical file and the {@code COBOL FILE STATUS}. The screen literal a
     * caller wants - {@code 'User ID NOT found...'} from {@code app/cbl/COUSR03C.cbl:L323} - reaches it on
     * the screen result of a successful exchange, where the source put it, rather than on this body.</p>
     */
    private static final String NOT_FOUND_PROBLEM_DETAIL =
            "No user was found for the identifier supplied.";

    /** The detail returned when the security file is not open, file status {@code '35'}. */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "The security file is not currently available. Retry once the datastore is reachable.";

    /**
     * The detail returned for an input-output failure. It attributes the fault to the store rather than to
     * the request, which is what {@code 502} states, and it names no cause: the cause stays attached to the
     * exception and is logged.
     */
    private static final String IO_PROBLEM_DETAIL =
            "The security data store reported an input-output failure. The request was not completed.";

    /**
     * The exact user-administration captions of the frozen source that may be returned to a caller verbatim.
     *
     * <p><strong>Finding H-02, severity High.</strong> The user-administration services raise their
     * typed failures carrying the source's own caption as the message - {@code UserUpdateService} and
     * {@code UserDeleteService} both do - and a handler that discarded every message in favour of a
     * fixed generic detail would be sound as far as it went: these exception types are also composed
     * by {@code FileStatusMapper}, whose messages name the operation, the logical file and the
     * {@code COBOL FILE STATUS}, none of which a caller may see. But applying the rule by <em>type</em> throws
     * away the literal parity the migration is measured on, including the deliberately preserved wrong verb of
     * {@code app/cbl/COUSR02C.cbl:L386} and {@code app/cbl/COUSR03C.cbl:L332}, where a <em>delete</em> path
     * reports {@code 'Unable to Update User...'}.
     *
     * <p><strong>Why an exact-match allow-list rather than a filter.</strong> A filter that stripped keys or
     * dataset names from an arbitrary message would have to be right about every message any of five files
     * might ever compose. Membership of this set cannot leak: every element is a fixed literal transcribed
     * from the frozen source, none interpolates a value, and a message that is not character-for-character one
     * of them is replaced by the fixed detail. Adding a caption here is therefore a deliberate, reviewable act
     * rather than a consequence of how some other file happened to phrase an error.
     *
     * <p>The three captions are, with their source locations:</p>
     *
     * <ul>
     *   <li>{@code 'User ID NOT found...'} - {@code app/cbl/COUSR02C.cbl:L342} and {@code :L379},
     *       {@code app/cbl/COUSR03C.cbl:L289} and {@code :L325}.</li>
     *   <li>{@code 'Unable to lookup User...'} - the {@code WHEN OTHER} arm of the lookup on both programs.</li>
     *   <li>{@code 'Unable to Update User...'} - {@code app/cbl/COUSR02C.cbl:L386} and
     *       {@code app/cbl/COUSR03C.cbl:L332}. The verb is wrong on the delete path in the source and is
     *       preserved, not corrected.</li>
     * </ul>
     */
    private static final Set<String> RETURNABLE_SOURCE_CAPTIONS = Set.of(
            "User ID NOT found...",
            "Unable to lookup User...",
            "Unable to Update User...");

    /**
     * The fixed detail returned for every 500. It names no cause, which is the same posture as
     * {@code server.error.include-message} set to {@code never}: the cause stays attached to the exception
     * and is logged, so diagnosis proceeds from the correlation identifier.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The request could not be completed. Quote the correlation identifier when reporting this.";

    /**
     * The single detail published when the framework's bean validation refuses the request body.
     *
     * <p>Fixed, and deliberately naming no field. The framework's binding result carries a field error per
     * violation, and each of those retains the <em>submitted value</em> alongside its message - which on the
     * add-user body is a plaintext password. {@code UserCreateRequest} documents that hazard as one of its own
     * error modes and states the rule this constant enforces: a handler must never publish a rejected value.
     * Withholding the field name as well costs a caller nothing, because the schema that declares the
     * constraint is what the caller wrote the body against, and it buys determinism: Hibernate Validator
     * reports violations from an unordered set, so any single-field or first-field projection would vary
     * between two identical requests. The field names do reach the log line, where they are diagnosable
     * without being disclosed.
     */
    private static final String BIND_FAILURE_PROBLEM_DETAIL =
            "One or more fields of the request body failed validation. Correct the body and resubmit.";

    /**
     * The single detail published when the request body could not be read at all.
     *
     * <p>Covers the three conditions the framework folds into one exception before either body-bearing
     * operation on this class is entered: a body that is not well-formed JSON, a body carrying a property
     * outside the schema, and a request with no body where one is required. The parser's own message is not
     * published, because it names the deserialiser's internal stream class, the byte offset it stopped at and,
     * for an unrecognised property, the full list of properties the type accepts - a schema dump handed to a
     * caller.
     */
    private static final String UNREADABLE_BODY_PROBLEM_DETAIL =
            "The request body could not be read as JSON matching this operation's schema.";

    /** Problem-detail member carrying the rejected field name. */
    private static final String FIELD_PROPERTY = "field";

    /** Problem-detail member carrying {@code INVALID} or {@code BLANK}, which keeps the two distinct. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * The problem-detail member carrying the stable, machine-readable code for the failure class.
     *
     * <p>Every error body this controller returns carries exactly one of the {@code ERROR_CODE_*} constants
     * below. A client branches on that code, never on the wording of {@code detail} and never on a member
     * naming an internal resource: the code is the supported contract, so an internal detail could be
     * withdrawn without breaking any caller.</p>
     */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * The problem-detail member carrying the correlation identifier of the failing request.
     *
     * <p>This is the hinge of the internal-disclosure posture. The logical file, operation, expanded file
     * status, colliding key, abend code, culprit and reason are written only to the log, and this identifier
     * is what lets a caller reporting a failure be joined to those records: it is the same value
     * {@code CorrelationIdFilter} placed in the diagnostic context and echoed on its response header, so
     * support can retrieve the internal detail while a caller holding the response body cannot.</p>
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value substituted when no correlation identifier is in the diagnostic context.
     *
     * <p>{@code CorrelationIdFilter} runs at the highest precedence and every request that reaches a handler
     * here has passed through it, so this is unreachable in the server. It exists because a standalone unit
     * test may invoke a handler directly, and because a null member would serialise as {@code null} and make
     * the body's shape depend on how it was produced.</p>
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /** Stable error code meaning that the request was refused by a field-level or token-level rule. */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /** Stable error code meaning that a record the operation needed does not exist. */
    private static final String ERROR_CODE_NOT_FOUND = "CARDDEMO-RECORD-NOT-FOUND";

    /** Stable error code meaning that the key the operation would have created is already present. */
    private static final String ERROR_CODE_DUPLICATE = "CARDDEMO-DUPLICATE-RECORD";

    /**
     * Stable error code meaning that a constraint of the user security schema refused the write.
     *
     * <p>The same code {@code AccountController} and {@code TransactionController} publish for the same
     * condition, and the one {@code docs/api-contracts.md} section 8.2 documents against a constraint refusal,
     * so every write surface answers it identically.
     */
    private static final String ERROR_CODE_CONSTRAINT = "CARDDEMO-CONSTRAINT-REFUSED";

    /** Stable error code meaning that a required data store could not be reached; the request is retryable. */
    private static final String ERROR_CODE_UNAVAILABLE = "CARDDEMO-RESOURCE-UNAVAILABLE";

    /** Stable error code meaning that the store behind this service reported an input-output failure. */
    private static final String ERROR_CODE_IO_FAILURE = "CARDDEMO-IO-FAILURE";

    /** Stable error code meaning that processing terminated abnormally. */
    private static final String ERROR_CODE_ABEND = "CARDDEMO-PROCESSING-ABEND";

    /** Stable error code meaning that an unexpected typed failure occurred. */
    private static final String ERROR_CODE_INTERNAL = "CARDDEMO-INTERNAL-FAILURE";

    /**
     * Stable error code meaning that the request body could not be read as JSON matching the schema.
     *
     * <p>Distinct from {@link #ERROR_CODE_VALIDATION} because the two conditions are distinct: a body that
     * parsed and then failed a rule is not the same as a body that never parsed, and a client that retries
     * automatically needs to tell them apart. The framework-level bean-validation refusal, by contrast,
     * publishes {@link #ERROR_CODE_VALIDATION} - the same code the service-tier refusal publishes - so a
     * caller sees one code per condition rather than one code per layer that happened to catch it.</p>
     */
    private static final String ERROR_CODE_UNREADABLE_BODY = "CARDDEMO-REQUEST-BODY-UNREADABLE";

    /** The abend code as text, for an abend raised here rather than carried in from a service. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Abend reason for an unexpected user-list failure. */
    private static final String USER_LIST_ABEND_REASON = "USER LIST RETRIEVAL FAILED UNEXPECTEDLY";

    /** Abend reason for an unexpected user-add failure. */
    private static final String USER_ADD_ABEND_REASON = "USER ADD FAILED UNEXPECTEDLY";

    /** Abend reason for an unexpected user-update failure. */
    private static final String USER_UPDATE_ABEND_REASON = "USER UPDATE FAILED UNEXPECTEDLY";

    /** Abend reason for an unexpected user-delete failure. */
    private static final String USER_DELETE_ABEND_REASON = "USER DELETE FAILED UNEXPECTEDLY";

    /** Abend message for an unexpected user-list failure. */
    private static final String USER_LIST_ABEND_MESSAGE = "UNEXPECTED ERROR IN CU00 USER LIST RETRIEVAL.";

    /** Abend message for an unexpected user-add failure. */
    private static final String USER_ADD_ABEND_MESSAGE = "UNEXPECTED ERROR IN CU01 USER ADD.";

    /** Abend message for an unexpected user-update failure. */
    private static final String USER_UPDATE_ABEND_MESSAGE = "UNEXPECTED ERROR IN CU02 USER UPDATE.";

    /** Abend message for an unexpected user-delete failure. */
    private static final String USER_DELETE_ABEND_MESSAGE = "UNEXPECTED ERROR IN CU03 USER DELETE.";

    /** The user-list service, the transcription of {@code app/cbl/COUSR00C.cbl}. */
    private final UserListService userListService;

    /** The user-add service, the transcription of {@code app/cbl/COUSR01C.cbl}. */
    private final UserAddService userAddService;

    /** The user-update service, the transcription of {@code app/cbl/COUSR02C.cbl}. */
    private final UserUpdateService userUpdateService;

    /** The user-delete service, the transcription of {@code app/cbl/COUSR03C.cbl}. */
    private final UserDeleteService userDeleteService;

    /**
     * Creates the controller over the four user-administration services.
     *
     * <p>Constructor injection is the only injection form used: there is no field injection, no setter
     * injection and no {@code @Autowired}, so all four collaborators are non-null and final for the lifetime
     * of the bean and the class holds no global mutable state. Each argument is validated rather than
     * trusted, because a null collaborator would otherwise surface as a failure on the first request instead
     * of at context refresh.</p>
     *
     * @param userListService the user-list service replacing {@code app/cbl/COUSR00C.cbl}; must not be null.
     * @param userAddService the user-add service replacing {@code app/cbl/COUSR01C.cbl}; must not be null.
     * @param userUpdateService the user-update service replacing {@code app/cbl/COUSR02C.cbl}, whose PF3 and
     * PF5 arms both reach the one update paragraph; must not be null.
     * @param userDeleteService the user-delete service replacing {@code app/cbl/COUSR03C.cbl}, which
     * deliberately carries no self-delete guard; must not be null.
     * @throws IllegalArgumentException if any collaborator is null, which is a bean-wiring defect rather
     * than a request-time condition
     */
    public AdminController(final UserListService userListService,
            final UserAddService userAddService,
            final UserUpdateService userUpdateService,
            final UserDeleteService userDeleteService) {

        if (userListService == null) {
            throw new IllegalArgumentException(
                    "userListService must not be null; it is the replacement for app/cbl/COUSR00C.cbl");
        }
        if (userAddService == null) {
            throw new IllegalArgumentException(
                    "userAddService must not be null; it is the replacement for app/cbl/COUSR01C.cbl");
        }
        if (userUpdateService == null) {
            throw new IllegalArgumentException(
                    "userUpdateService must not be null; it is the replacement for app/cbl/COUSR02C.cbl");
        }
        if (userDeleteService == null) {
            throw new IllegalArgumentException(
                    "userDeleteService must not be null; it is the replacement for app/cbl/COUSR03C.cbl");
        }

        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    /**
     * Operation 14 of 17 - one page of users. Replaces CICS transaction {@value #USER_LIST_TRANSACTION_ID}
     * ({@code app/csd/CARDDEMO.CSD:L449}) and the program it fronts, {@code app/cbl/COUSR00C.cbl} (695
     * lines, 16 paragraph labels), which painted mapset {@value #USER_LIST_MAPSET}.
     *
     * <p><strong>Purpose.</strong> Returns one screen of user rows together with the cursor needed to ask
     * for the adjacent screen. It is {@code MAIN-PARA} at {@code app/cbl/COUSR00C.cbl:L98} reached as a
     * single stateless request: the browse paragraphs {@code PROCESS-PAGE-FORWARD} at {@code :L282} and
     * {@code PROCESS-PAGE-BACKWARD} at {@code :L336}, their two guards {@code PROCESS-PF7-KEY} at
     * {@code :L238} and {@code PROCESS-PF8-KEY} at {@code :L260}, and the row projection
     * {@code POPULATE-USER-DATA} at {@code :L385}.
     *
     * <p><strong>Inputs.</strong> All optional, and every one of them a cursor or a filter - never a page
     * size, which no client may choose.</p>
     *
     * <ul>
     *   <li>{@code action} - the enumerated navigation intent, {@link UserListAction}. Defaults to
     *       {@link UserListAction#SUBMIT}, so a bare request returns the first page.</li>
     *   <li>{@code userId} - {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY:78}, the identifier
     *       the operator typed to start the browse from. Relayed to the service <em>exactly as
     *       received</em>: not trimmed, not padded, not case-converted and never coerced between absent and
     *       blank, because those are two different states and the third is a value.</li>
     *   <li>{@code page}, {@code firstKey}, {@code lastKey}, {@code nextPageAvailable} and
     *       {@code rowCount} - the cursor the source held in its communication area:
     *       {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COUSR00C.cbl:L70},
     *       {@code CDEMO-CU00-USRID-FIRST PIC X(08)} at {@code :L68},
     *       {@code CDEMO-CU00-USRID-LAST PIC X(08)} at {@code :L69} and
     *       {@code CDEMO-CU00-NEXT-PAGE-FLG} at {@code :L71-L73}, plus how full the displayed page was.
     *       Under transformation rule 7 they travel as request parameters and response metadata, because the
     *       target keeps no server-side session and no server-side cursor. A client echoes back what the
     *       previous response gave it. Note that {@code app/cpy/COCOM01Y.cpy} declares no page-number and no
     *       next-page member at all: this state lived in the program's own {@code WORKING-STORAGE} and in its
     *       own {@code CDEMO-CU00-INFO} block, so it is not shared commarea state and is not described as
     *       such.</li>
     *   </ul>
     *
     * <p><strong>Why the row count travels.</strong> {@code PROCESS-PF8-KEY} at
     * {@code app/cbl/COUSR00C.cbl:L260} advances from the <em>last row of the displayed page</em>, and the
     * source knows how far into its page that row sits because the received map is still in its storage. A
     * stateless request has to say so, so the client echoes the number of rows it was given and the browse
     * repositions identically. The three control tokens are matched exactly - {@code action} accepts only
     * the three declared spellings, {@code page} and {@code rowCount} only ASCII digits, and
     * {@code nextPageAvailable} only {@value #TRUE_TOKEN} and {@value #FALSE_TOKEN} - with no alias, no trim
     * and no case fold, because a normalising converter on a control token silently accepts instructions the
     * operation never declared.
     *
     * <p><strong>Outputs.</strong> {@code 200} with {@code UserListResponse}: the page's rows, the page
     * number a caller echoes back, the page size in force, whether a further record exists, and the two
     * boundary identifiers to page from. Rows arrive in browse order - ascending user identifier, the key
     * order of the {@code USRSEC} cluster and the order the repository's only finder imposes - so
     * <strong>the ordering is deterministic and repeatable for a given cursor</strong>. The response
     * additionally carries the source's own advisory message, which is the only place several outcomes are
     * reported at all: reaching either boundary, an empty result, or a refused identifier. A total element
     * count and a total page count are <b>Not available</b> by design, because the browse looks ahead exactly
     * one record and never counts.
     *
     * <p><strong>The service's screen record is not the wire contract.</strong> The record the service
     * assembles is faithful to the 3270 turn and therefore carries an advisory navigation target, a cursor
     * field, an error flag, an erase flag, a send counter, a control-transferred flag, the row selector and a
     * duplicated screen-and-page pair. Returning it directly published all of that as the public contract,
     * which was a High-severity API-contract defect with a CWE-200 aspect; the record is now strictly
     * in-process and this operation answers the API-owned projection instead. What is withheld is enumerated
     * on {@code UserListResponse} and asserted against its {@code WITHHELD_COMPONENTS} list.
     *
     * <p><strong>No credential and no digest can appear in this response.</strong> Neither the response nor
     * its row type declares a password member, so there is nothing to omit and nothing to remember to blank.
     * The personal data a row does carry - identifier, given name, family name and the one-character type
     * code - is exactly what the 3270 screen displayed and nothing more.
     *
     * <p><strong>Side effects.</strong> None. The operation reads and returns; it writes no row, publishes no
     * message and mutates no field of this class.
     *
     * <p><strong>Configuration and defaults.</strong> The page depth is
     * {@code carddemo.pagination.user-list-page-size}, declared as {@code 10} in
     * {@code src/main/resources/application.yml} - the single resolution of
     * {@code 02 USER-REC OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl:L57}, corroborated by the ten-row
     * field group {@code USRID01I} through {@code USRID10I} at {@code app/cpy-bms/COUSR00.CPY:78} and
     * {@code :348}. It is bound and range-checked by {@code UserListService}, never by this class and never
     * by a client, so no page-size literal appears in this file; the value in force is echoed on every
     * response.
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when a control token is malformed,
     * when the page number falls outside the {@code PIC 9(08)} domain, when the echoed row count exceeds the
     * rows the screen paints, or when the identifier filter is wider than the eight-character key; the body
     * names the field and the failure kind. {@code 404} when the browse cannot be positioned, which is the
     * {@code DFHRESP(NOTFND)} arm of {@code STARTBR} at {@code app/cbl/COUSR00C.cbl:L600-L606} - either the
     * file holds no record or the named user does not exist. {@code 502} when the read itself fails, the
     * {@code WHEN OTHER} arm at {@code :L607-L613}. {@code 500} for an abend carrying code {@code 999} and
     * return code {@code 12} with the original throwable preserved as the cause. A page past the end and an
     * empty result are not failures: both return {@code 200} with no rows and the next-page flag false,
     * which is the source reporting the same condition as a screen message.
     *
     * <p><strong>Labelled omission.</strong> The row selector {@code USER-SEL PIC X(01)} at
     * {@code app/cbl/COUSR00C.cbl:L58}, and the {@code SEL0001I PIC X(1)} field at
     * {@code app/cpy-bms/COUSR00.CPY:72} it was received into, have no Java counterpart: selecting a row in
     * order to reach the update or delete screen becomes the caller naming that identifier on
     * {@link #updateUser} or {@link #deleteUser}. The echoed rows this operation accepts therefore carry no
     * selector, and none is read.
     *
     * @param actionToken the navigation intent as typed, matched exactly against the three declared tokens;
     * null defaults to {@link UserListAction#SUBMIT}, which is what a bare request means.
     * @param userIdInput the identifier filter as typed, or null when the parameter was absent; relayed
     * verbatim, with absent, empty and populated kept as three distinct states.
     * @param pageToken the page number being echoed back, the transcription of
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}; null resolves to zero, which is what a first request sends.
     * Accepted only as ASCII digits.
     * @param firstKey the first identifier of the page displayed, used to page backward; null on a first
     * request, which the source models as {@code LOW-VALUES}.
     * @param lastKey the last identifier of the page displayed, used to page forward; null on a first
     * request, which the source models as {@code HIGH-VALUES} and which therefore positions past the end.
     * @param nextPageToken whether the previous response reported a further record; null resolves to false,
     * which is what a first request means, and only the two declared tokens are accepted.
     * @param rowCountToken how many rows the previous response carried; null resolves to zero. Accepted only
     * as ASCII digits, and refused by the service when it exceeds the rows the screen paints.
     * @return {@code 200 OK} with the API-owned page projection, its rows and the cursor for the adjacent
     * page; never null
     * @throws ValidationException if a control token is malformed, or if the service refuses the page
     * number, the echoed row count or the identifier filter
     * @throws RecordNotFoundException if the browse cannot be positioned
     * @throws FatalProcessingException if the browse fails for any reason other than a typed CardDemo
     * failure
     */
    @GetMapping
    public ResponseEntity<UserListResponse> listUsers(
            @RequestParam(name = ACTION_PARAMETER, required = false) final String actionToken,
            @RequestParam(name = USER_ID_FILTER_PARAMETER, required = false) final String userIdInput,
            @RequestParam(name = PAGE_PARAMETER, required = false) final String pageToken,
            @RequestParam(name = FIRST_KEY_PARAMETER, required = false) final String firstKey,
            @RequestParam(name = LAST_KEY_PARAMETER, required = false) final String lastKey,
            @RequestParam(name = NEXT_PAGE_PARAMETER, required = false) final String nextPageToken,
            @RequestParam(name = ROW_COUNT_PARAMETER, required = false) final String rowCountToken) {

        // Every control token is matched here, exactly, rather than converted: the framework's default
        // converters normalise - they trim an enum token, accept a signed integer and treat six spellings as
        // a boolean - and a normalising converter on a control token accepts instructions this operation
        // never declared.
        final UserListAction action = resolveAction(actionToken);

        // Validated BEFORE the enter-key short-circuit below, and the ordering is the point: a control
        // token this operation declares is either accepted or refused, never silently ignored. The
        // enter-key arm does not USE the page number - PROCESS-ENTER-KEY forces the counter to zero - but
        // ignoring a nine-digit value on that arm while refusing it on the other two published one domain
        // rule and enforced it on two thirds of the operation. The same reasoning the comment above gives
        // for not converting a token applies to not discarding one.
        final int page = requireDigits(pageToken, PAGE_PARAMETER, MAXIMUM_PAGE_TOKEN_DIGITS);

        if (action == UserListAction.SUBMIT) {
            // PROCESS-ENTER-KEY forces CDEMO-CU00-PAGE-NUM to zero at app/cbl/COUSR00C.cbl:227 and pages
            // forward, so the answer is always page one and the echoed cursor is irrelevant on this arm.
            // The dedicated entry point is what the service documents for exactly that, so no cursor is
            // fabricated in order to reach the general one.
            return ResponseEntity.ok(publishableList(openUserList(userIdInput)));
        }

        final UserListService.UserListRequest request = new UserListService.UserListRequest(
                page,
                requireBooleanToken(nextPageToken, NEXT_PAGE_PARAMETER),
                firstKey,
                lastKey,
                userIdInput,
                echoedRows(requireDigits(rowCountToken, ROW_COUNT_PARAMETER,
                        MAXIMUM_ROW_COUNT_TOKEN_DIGITS)));

        requirePositioningCursor(action, page, firstKey, lastKey);

        return ResponseEntity.ok(publishableList(pageUserList(action, request)));
    }

    /**
     * Refuses a navigation that names a page past the first without carrying the cursor that addresses it.
     *
     * <p><strong>Finding, severity Medium - remediated here.</strong>
     * {@code GET /api/admin/users?action=PAGE_FORWARD&page=5} with no cursor answered {@code 200}
     * reporting {@code pageNumber=5} with an <strong>empty</strong> row list, and the backward spelling did
     * likewise. A response that reports a page it did not serve cannot be acted on: a client cannot
     * distinguish "there is nothing on page five" from "you did not tell me where page five is".
     *
     * <p><strong>The page number is a counter, not an address.</strong> {@code CDEMO-CU00-PAGE-NUM} at
     * {@code app/cbl/COUSR00C.cbl} is what the heading displays; what positions the browse is the saved
     * first or last user identifier that the {@code DFHPF7} and {@code DFHPF8} arms move into the record key
     * before restarting it. The source cannot exhibit the disagreement, because the counter and both keys
     * are written into the COMMAREA by the same send. Transformation Rule 7 moves that state onto the wire,
     * where the parts arrive independently and can therefore disagree - which is what turns a COMMAREA
     * invariant into a request precondition that has to be checked.
     *
     * <p><strong>What is deliberately left admitted.</strong> {@code SUBMIT} never reaches here: it returns
     * above through the dedicated entry point, because {@code PROCESS-ENTER-KEY} forces the page counter to
     * zero at {@code app/cbl/COUSR00C.cbl:227} and always answers the first page. The first page and an
     * absent page are admitted for the same reason - a space-filled key positions at the start of the
     * browse, which is what the source intends.
     *
     * <p>Static and side-effect free.
     *
     * @param action the resolved navigation action; never null
     * @param page the resolved page number
     * @param firstKey the first-row cursor as bound from the request, or null when it was absent
     * @param lastKey the last-row cursor as bound from the request, or null when it was absent
     * @throws ValidationException with failure kind {@code BLANK}, naming the cursor the request omitted
     */
    private static void requirePositioningCursor(final UserListAction action, final int page,
            final String firstKey, final String lastKey) {

        if (action == UserListAction.SUBMIT || page <= FIRST_PAGE_NUMBER) {
            return;
        }

        if (action == UserListAction.PAGE_FORWARD) {
            if (lastKey == null || lastKey.isBlank()) {
                throw ValidationException.missingField(LAST_KEY_PARAMETER,
                        "lastKey must be supplied on a forward request past the first page, because it is"
                                + " the position being advanced from. Return the lastUserId the previous"
                                + " response reported, alongside its pageNumber");
            }
            return;
        }

        if (firstKey == null || firstKey.isBlank()) {
            throw ValidationException.missingField(FIRST_KEY_PARAMETER,
                    "firstKey must be supplied on a backward request past the first page, because it is the"
                            + " position being moved back from. Return the firstUserId the previous response"
                            + " reported, alongside its pageNumber");
        }
    }

    /**
     * Operation 15 of 17 - add one user. Replaces CICS transaction {@value #USER_ADD_TRANSACTION_ID}
     * ({@code app/csd/CARDDEMO.CSD:L459}) and the program it fronts, {@code app/cbl/COUSR01C.cbl} (299
     * lines, 9 paragraph labels), which painted mapset {@code COUSR01}.
     *
     * <p><strong>Purpose.</strong> Validates the presented fields in the source's own order and, when none is
     * empty, inserts one eighty-byte security record. It is the {@code DFHENTER} arm at
     * {@code app/cbl/COUSR01C.cbl:L91-L92}, which performs {@code PROCESS-ENTER-KEY} and then
     * {@code WRITE-USER-SEC-FILE} at {@code :L240-L248}.
     *
     * <p><strong>Inputs.</strong> The twelve fields of {@code app/cpy-bms/COUSR01.CPY} as a request body,
     * bound at the widths that map declares - {@code FNAMEI PIC X(20)} at {@code :60},
     * {@code LNAMEI PIC X(20)} at {@code :66}, {@code USERIDI PIC X(8)} at {@code :72},
     * {@code PASSWDI PIC X(8)} at {@code :78}, {@code USRTYPEI PIC X(1)} at {@code :84} and the six
     * recurring header fields plus {@code ERRMSGI PIC X(78)} at {@code :90}. The presented credential is one
     * of those twelve body members and arrives nowhere else. Nothing is widened, narrowed, renamed or
     * re-ordered, and the body type refuses any property outside the twelve rather than dropping it, so a
     * misspelled member is a {@code 400} and never a user created without the field the caller thought it
     * sent.
     *
     * <p><strong>The credential travels inside the body, on no other channel.</strong> This operation
     * declares no {@code @RequestHeader}, no {@code @RequestParam} and no additional parameter of any kind:
     * the body is the whole input. {@code UserCreateRequest} keeps the credential write-only for JSON and
     * publishes exactly one read path to it, {@code mapPassword(Function)}, which requires a reader as its
     * argument and therefore cannot be discovered by a serializer, a reflective bean mapper or any
     * property-walking renderer - all of which look for zero-argument methods. This class supplies that
     * reader and hands the value straight to the service, which hashes it immediately; the credential never
     * becomes a value this class holds, and it is never assigned to a field, logged, echoed, returned or
     * placed in an exception message. Credential-shaped masking rules in {@code logback-spring.xml} are a
     * backstop rather than the control. A request that omits the member, or supplies it blank, receives the
     * source's own empty-password rejection from {@code app/cbl/COUSR01C.cbl:L136-L141}. A request that
     * supplies the credential on a header instead is not served the credential it thought it sent: no header
     * is bound, and a regression test asserts that none ever will be.
     *
     * <p><strong>Validation order is preserved in the service, not here.</strong> The five checks run first
     * name, last name, user identifier, password, user type - {@code app/cbl/COUSR01C.cbl:L118},
     * {@code :L124}, {@code :L130}, {@code :L136} and {@code :L142} - and stop at the first field that is
     * empty, so a request with several empty fields is reported exactly as the screen reported it: one
     * message, for the first field in that order. This controller adds no check of its own and reorders
     * nothing. Note that the order is <em>not</em> the sibling update program's, which checks the identifier
     * first; the two must not be harmonised.
     *
     * <p><strong>Outputs.</strong> {@code 201 Created} with {@code UserCreateResponse}, whose four business
     * fields are blank on the success arm - the source clears its input fields once the record is written -
     * and whose message is {@code 'User '} plus the trimmed identifier plus {@code ' has been added ...'},
     * the {@code STRING} result of {@code app/cbl/COUSR01C.cbl:L255-L257}, relayed byte for byte. The status
     * is {@code 201} rather than {@code 200} because the operation creates a record, exactly as the sibling
     * transaction-add and bill-payment operations answer for the same reason. Neither the response nor the
     * service's own record declares a password member, so no credential and no digest can travel back.
     *
     * <p><strong>The service's screen record is not the wire contract.</strong> It carries the green message
     * attribute the source moved at {@code :L254}, the field the cursor was parked on and the advisory
     * navigation target, none of which is a business outcome; publishing them was a High-severity
     * API-contract defect. The record is now strictly in-process and this operation answers the API-owned
     * projection, whose withheld members are enumerated on {@code UserCreateResponse}.
     *
     * <p><strong>Side effects.</strong> On success exactly one row is inserted into the security table and the
     * presented credential is replaced by a BCrypt digest before it reaches that row. On any failure nothing
     * is written: the service method is transactional and rolls back for every exception, checked or
     * unchecked.
     *
     * <p><strong>Configuration and defaults.</strong> BCrypt at strength 10 is applied by the service and is
     * configured centrally with the rest of the security wiring; the eight-character
     * {@code SEC-USR-PWD PIC X(08)} field of {@code app/cpy/CSUSR01Y.cpy:L21} becomes a sixty-character hash
     * column, and <strong>the legacy eight-character ceiling is not imposed on the hash</strong>. No password
     * policy is invented: {@code app/cbl/COUSR01C.cbl:L136} tests emptiness and nothing else, so no strength,
     * length, expiry, reuse or lockout rule appears anywhere. This class reads no property.
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when one of the five fields is empty
     * - the {@code BLANK} failure kind - when a field is wider than the screen field it transcribes, or when
     * the user type is outside {@code 'A'} and {@code 'U'} - both the {@code INVALID} kind; the body names
     * the field and never its value. {@code 409} when the identifier is already taken, which is the
     * {@code WHEN DFHRESP(DUPKEY)} arm at {@code app/cbl/COUSR01C.cbl:L260} and the
     * {@code WHEN DFHRESP(DUPREC)} arm at {@code :L261}, both of which report
     * {@code 'User ID already exist...'} at {@code :L263} - <strong>relayed byte for byte, "exist" and not
     * "exists"</strong>, because the parity comparison is made on text. {@code 502} when the insert fails for
     * any other reason, the {@code WHEN OTHER} arm at {@code :L267-L273}. {@code 500} for an abend carrying
     * code {@code 999} and return code {@code 12}. The colliding key the duplicate failure can carry is
     * neither returned nor logged: a caller that asked to create an identifier already knows which one it
     * asked for.
     *
     * <p><strong>Labelled omissions.</strong> {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} at
     * {@code app/cbl/COUSR01C.cbl:L96-L97} blanks a terminal map and has no counterpart - a stateless client
     * discards its own form - and {@code MOVE -1 TO USERIDL} at {@code :L264} parked the cursor on the field
     * at fault, which the field name on a {@code 400} carries instead. Neither becomes an endpoint. Note also
     * that {@code WHEN DFHPF3} at {@code :L93-L95} exits <em>without</em> saving in this program, unlike the
     * sibling update program, so no arm of this operation writes on an exit.
     *
     * @param request the twelve declared fields of the add screen, validated at the map's widths before this
     * method is entered; must not be null, and every member is treated as untrusted. Its
     * {@code PASSWDI PIC X(8)} member is the presented plaintext credential and is the only channel by which
     * one is accepted; it may be null, empty or blank, each of which takes the source's own empty-password
     * arm, and it is never logged, echoed or returned.
     * @return {@code 201 Created} with the API-owned projection carrying the source's added message; never
     * null
     * @throws ValidationException if a field is empty, over-wide, or carries a user type outside the two the
     * source admits
     * @throws DuplicateRecordException if the identifier is already present
     * @throws FatalProcessingException if the insert fails for any reason other than a typed CardDemo failure
     */
    @PostMapping
    public ResponseEntity<UserCreateResponse> addUser(
            @Valid @RequestBody final UserCreateRequest request) {

        // The credential is read from the body it was bound and validated in, through the one-way reader the
        // payload publishes for exactly this purpose, so it never becomes a value this method holds.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(publishableAdd(request.mapPassword(presented -> applyUserAdd(request, presented))));
    }

    /**
     * Operation 16 of 17 - update one user. Replaces CICS transaction {@value #USER_UPDATE_TRANSACTION_ID}
     * ({@code app/csd/CARDDEMO.CSD:L469}) and the program it fronts, {@code app/cbl/COUSR02C.cbl} (414
     * lines, 11 paragraph labels), which painted mapset {@code COUSR02}.
     *
     * <p><strong>Purpose.</strong> Reads the named record, compares four fields against what was submitted,
     * and rewrites it when at least one differs. It is {@code UPDATE-USER-INFO} at
     * {@code app/cbl/COUSR02C.cbl:L176}, with the read at {@code :L322-L331} and the rewrite at
     * {@code :L360-L366}.
     *
     * <p><strong>Labelled mechanism substitution: the two attention identifiers that save collapse into this
     * one operation.</strong> {@code app/cbl/COUSR02C.cbl:L112} (PF3) and {@code :L122} (PF5) both
     * {@code PERFORM UPDATE-USER-INFO}; the two arms differ only in post-save navigation, which has no Java
     * counterpart because routing is URL-based. The two AID arms therefore collapse into this single
     * operation. Splitting them would introduce an eighteenth operation with no CSD transaction behind it.
     * What PF3 does afterwards is resolve a target program and transfer control - it writes
     * {@code CDEMO-TO-PROGRAM} at {@code :L114} or {@code :L117} and then performs
     * {@code RETURN-TO-PREV-SCREEN} at {@code :L119} - and every one of those is a commarea or CICS construct
     * with no counterpart at all. Note that PF3 saving is itself the program's documented quirk, established
     * as anomalous by four independent facts: this program's own on-screen hint at {@code :L336} names PF5,
     * {@code WHEN DFHPF12} at {@code :L124-L126} leaves without saving, and the sibling delete, list and add
     * programs all use PF3 conventionally. The quirk is preserved by the service, which maps both call sites;
     * this operation is the one REST surface both reach. Splitting them would invent an eighteenth
     * operation with no CSD transaction behind it.
     *
     * <p><strong>Inputs.</strong> The identifier as a path variable, and the twelve fields of
     * {@code app/cpy-bms/COUSR02.CPY} as a request body whose credential member is write-only. The
     * pre-population the source performed at {@code app/cbl/COUSR02C.cbl:L99-L103}, where
     * {@code CDEMO-CU02-USR-SELECTED} was moved into the identifier field on arrival from the list, is simply
     * the caller supplying that identifier here - <strong>it is not a server-side selection carried between
     * calls</strong>, and no such selection exists to carry.
     *
     * <p><strong>The identifier is named twice, and the two must agree.</strong> The path variable addresses
     * the record and {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:60} is a declared member of
     * the map, so both are present and neither is dropped. A body member that is present and not blank must
     * equal the path variable exactly - no trim and no case fold, because the source folds neither on this
     * transaction - and a disagreement is a {@code 400} naming the field, never a silent preference for one
     * over the other. A body member that is absent or blank is relayed unchanged, so the source's own
     * empty-identifier rejection at {@code app/cbl/COUSR02C.cbl:L180-L185} fires exactly as it did on the
     * screen; absent, blank and populated stay three distinct states here as everywhere else.
     *
     * <p><strong>Validation order is preserved in the service, not here.</strong> The five checks run user
     * identifier, first name, last name, password, user type - {@code app/cbl/COUSR02C.cbl:L180},
     * {@code :L186}, {@code :L192}, {@code :L198} and {@code :L204} - which is
     * <strong>identifier-first</strong> and deliberately not the add program's order. Four change predicates
     * at {@code :L219}, {@code :L223}, {@code :L227} and {@code :L231} then decide whether anything is
     * written at all.
     *
     * <p><strong>Outputs.</strong> {@code 200} with {@code UserUpdateResponse}. On a write its message is
     * {@code 'User '} plus the trimmed identifier plus {@code ' has been updated ...'}, the caption the source
     * paints green at {@code app/cbl/COUSR02C.cbl:L371}; when none of the four fields differs the source
     * instead emits {@code 'Please modify to update ...'} at {@code :L239} and abandons the write, and that
     * outcome is returned with the same {@code 200} rather than raised, because it is neither an error nor a
     * conflict. <strong>{@code updateApplied} is what distinguishes the two</strong> - the transcription of
     * {@code WS-USR-MODIFIED} as it stood at the write decision of {@code :L236} - and it is published for
     * exactly that reason, because a caller that cannot tell "saved" from "nothing to save" has lost
     * behaviour the screen conveyed. The response declares no password member, so the credential echo the
     * source performed at {@code :L169} is deliberately not reproduced and nothing here can carry a
     * credential, a digest or a masked-but-present value.
     *
     * <p><strong>The service's screen record is not the wire contract.</strong> It carries the three attribute
     * bytes, the cursor field, the transfer flag and the advisory navigation target, which describe a terminal
     * conversation rather than an outcome; returning it directly was a High-severity API-contract defect with a
     * CWE-200 aspect. The record is now strictly in-process, and the withheld members are enumerated on
     * {@code UserUpdateResponse} and asserted against its {@code WITHHELD_COMPONENTS} list.
     *
     * <p><strong>Side effects.</strong> One keyed read, then at most one rewrite. On any failure nothing is
     * written: the service method is transactional and rolls back for every exception, checked or unchecked.
     * Supplying the same password again is not a change, because the predicate asks the encoder, so the stored
     * digest stays byte-identical.
     *
     * <p><strong>Configuration and defaults.</strong> BCrypt at strength 10 is applied by the service, as on
     * the add operation, and this class reads no property. <strong>No optimistic-locking metadata is
     * referenced here, because {@code UserSecurity} declares no version column</strong> - the concurrency
     * guarantee is a pessimistic write read instead, which is what the source itself used.
     *
     * <p><strong>Concurrency: the row is held for update, exactly as the source held it.</strong>
     * {@code app/cbl/COUSR02C.cbl:L322-L328} reads with {@code EXEC CICS READ ... UPDATE} against a file
     * {@code app/csd/CARDDEMO.CSD:L88-L89} defines with {@code UPDATEMODEL(LOCKING)}, and rewrites at
     * {@code :L360}. The service reproduces that with
     * {@code com.cardemo.repository.UserSecurityRepository#findByIdForUpdate(String)} inside one transaction, so
     * the read, the change detection and the rewrite are indivisible and a second administrator's update cannot
     * interleave with this one. <strong>Finding, Medium severity:</strong> the
     * unlocked {@code findById} would leave the sequence an unguarded read-modify-write.
     *
     * <p><strong>Labelled deviation, Medium severity: no as-displayed snapshot travels.</strong> The source
     * carried none, and the business-level comparison layer therefore stays unengaged on this resource. The
     * reason is structural rather than an oversight - no per-user read transaction exists among the seventeen
     * {@code app/csd/CARDDEMO.CSD} defines, so nothing can issue a sealed snapshot for a caller to return, and
     * adding an eighteenth route would exceed the declared endpoint set. What that leaves exposed is narrower
     * than before: not a lost update, which the lock now prevents, but a caller composing a submission from a
     * view that has since moved. See {@link #NO_CLIENT_SNAPSHOT}.
     *
     * <p><strong>Finding CODE-009, severity Medium, RESOLVED here.</strong> This paragraph previously named the
     * wrong mechanism, describing the sibling surfaces as requiring their snapshot on a conditional-request
     * header. They do not, and no {@code If-Match} or {@code If-Unmodified-Since} handling exists anywhere in
     * this application. What the account and card updates actually do, and what a per-user read would have to
     * do if one were ever added: the read publishes an opaque {@code snapshot} string -
     * {@code com.cardemo.model.dto.AccountViewResponse#snapshot()} and
     * {@code com.cardemo.model.dto.CardResponse#snapshot()} - sealed by
     * {@code com.cardemo.security.SnapshotTokenService} and bound to the record it describes, the principal it
     * was issued to and an expiry; the write binds that same string as a declared member of its request
     * <em>body</em>. Presenting nothing where one is required is {@code 428}; presenting one that does not open
     * - tampered, issued to another principal, issued for another record, or expired - is {@code 412}. The
     * caller cannot read it, and cannot compose one.
     *
     * <p><strong>The identifier may be omitted from the body, but it may not be sent empty.</strong> Finding
     * API-003. Omit the member and the path identity fills it, which is the documented shape. Send it naming a
     * different user and the request is refused before the service is reached. Send it <em>empty</em> - as
     * {@code ""}, as blanks, or as a run of NUL bytes - and it travels unchanged to the service, which refuses
     * it with {@code 400} and {@code app/cbl/COUSR02C.cbl}'s own {@code 'User ID can NOT be empty...'} from
     * {@code :L146-L151} and {@code :L179-L185}. The three states are distinguished because the source
     * distinguishes them; an empty value used to be overwritten with the path identity, which made that
     * rejection unreachable through this surface.
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when one of the five fields is empty
     * - the {@code BLANK} failure kind - when a field is wider than its screen field, or when the user type is
     * outside {@code 'A'} and {@code 'U'} - both the {@code INVALID} kind. {@code 404} when the identifier is
     * not present, on the read at {@code app/cbl/COUSR02C.cbl:L340-L345} or on the rewrite at
     * {@code :L377-L382}. {@code 502} when either fails for another reason, at {@code :L346-L352} and
     * {@code :L383-L389}. {@code 500} for an abend carrying code {@code 999} and return code {@code 12}. An
     * unrecognised action has no route to this operation at all: the source's {@code WHEN OTHER} arm at
     * {@code :L126-L129}, which reported an invalid key, becomes the framework refusing an undeclared HTTP
     * method, and any control token this class does accept is refused as a {@code ValidationException}
     * carrying its field name.
     *
     * <p><strong>Labelled omissions.</strong> {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} at
     * {@code app/cbl/COUSR02C.cbl:L120} is screen behaviour with no Java counterpart, and
     * <strong>no clear-screen endpoint is added</strong> - that would be an eighteenth operation.
     * {@code WHEN DFHPF12} at {@code :L124} left without saving, which a client does by not calling this
     * operation. Cursor repositioning names the field at fault, which the field name on a {@code 400} carries
     * instead.
     *
     * @param userId the eight-character identifier of the record to rewrite, {@code SEC-USR-ID PIC X(08)};
     * supplied by the caller rather than recovered from a server-side selection, and width-checked by the
     * service against the cluster key.
     * @param request the twelve declared fields of the update screen, validated at the map's widths before
     * this method is entered; must not be null, and every member is treated as untrusted.
     * @return {@code 200 OK} with the API-owned projection, carrying either the source's updated message or
     * its "please modify" advisory together with the write decision; never null
     * @throws ValidationException if a field is empty, over-wide, or carries a user type outside the two the
     * source admits
     * @throws RecordNotFoundException if the identifier is not present on the read or on the rewrite
     * @throws FatalProcessingException if the update fails for any reason other than a typed CardDemo failure
     */
    @PutMapping(USER_PATH)
    public ResponseEntity<UserUpdateResponse> updateUser(
            @PathVariable(name = USER_ID_PATH_VARIABLE) final String userId,
            @Valid @RequestBody final UserUpdateRequest request) {

        return ResponseEntity.ok(publishableUpdate(applyUserUpdate(userId, request)));
    }

    /**
     * Operation 17 of 17 - delete one user, and the operation that closes this package's inventory. Replaces
     * CICS transaction {@value #USER_DELETE_TRANSACTION_ID} ({@code app/csd/CARDDEMO.CSD:L479}) and the
     * program it fronts, {@code app/cbl/COUSR03C.cbl} (359 lines, 11 paragraph labels), which painted mapset
     * {@code COUSR03}.
     *
     * <p><strong>Purpose.</strong> Reads the named record and destroys it. It is {@code DELETE-USER-INFO} at
     * {@code app/cbl/COUSR03C.cbl:L188}, reached from the {@code WHEN DFHPF5} arm at {@code :L121-L122}, with
     * the read at {@code :L275-L284} and the delete at {@code :L305-L312}.
     *
     * <p><strong>Preserved legacy quirk: there is no self-delete guard, and adding one is forbidden.</strong>
     * {@code app/cbl/COUSR03C.cbl} never compares the target identifier against the signed-on identifier, and
     * the proof is a census rather than an impression: {@code CDEMO-USER-ID} occurs <strong>zero</strong>
     * times in all 359 of its lines. Deleting the acting administrator's own record therefore
     * <strong>succeeds</strong>. That behaviour is preserved deliberately - parity is the contract - so no
     * comparison against the token subject appears anywhere in this class, no {@code 403} is returned for a
     * self-delete, and no confirmation beyond the one the source itself required is demanded. It is
     * disclosed as residual risk rather than repaired.
     *
     * <p><strong>Inputs.</strong> The identifier as a path variable, and the confirmation as a request
     * parameter. There is no request body: the source read the record and displayed it for confirmation
     * before destroying it, and the four detail fields it displayed are outputs rather than inputs.
     *
     * <p><strong>Labelled mechanism substitution: the confirmation is asserted, not remembered.</strong> The
     * source gated destruction on two distinct terminal interactions - the {@code DFHENTER} arm at
     * {@code app/cbl/COUSR03C.cbl:L109-L110} read the record and emitted the prompt at {@code :L283}, and a
     * different key, {@code DFHPF5}, then destroyed it. A stateless surface cannot remember the first
     * interaction, so the caller asserts it on the request instead. The token is matched exactly against
     * {@value #TRUE_TOKEN} and {@value #FALSE_TOKEN}, and anything else - including absence - is refused here
     * with a {@code 400} naming the {@value #CONFIRMED_PARAMETER} field, so a missing confirmation is
     * reported as the client-side omission it is rather than reaching the service and surfacing as a server
     * failure. Nothing is destroyed on that path.
     *
     * <p><strong>Outputs.</strong> {@code 200} with the assembled screen, whose four detail fields are blank -
     * exactly as {@code INITIALIZE-ALL-FIELDS} leaves them at {@code app/cbl/COUSR03C.cbl:L315} - and whose
     * message is {@code 'User '} plus the trimmed identifier plus {@code ' has been deleted ...'}, the
     * {@code STRING} result of {@code :L318-L320}, carried with the green message attribute of {@code :L317}.
     * The status is {@code 200} with that body rather than {@code 204} precisely because the source's own
     * message is part of the observable contract and a body-less status could not carry it.
     *
     * <p><strong>Side effects.</strong> One keyed read, then at most one delete. On any failure nothing is
     * removed: the service method is transactional and rolls back for every exception, checked or unchecked.
     * No audit record is written, no tombstone is left and nothing is cascaded, because the source does none
     * of those things and none is invented.
     *
     * <p><strong>Configuration and defaults.</strong> None. This operation reads no property, and this class
     * declares none.
     *
     * <p><strong>Failure modes and troubleshooting.</strong> Three outcomes are distinguishable, and each
     * carries the source's own text byte for byte:</p>
     *
     * <table border="1">
     *   <caption>The three arms of the delete evaluation at {@code app/cbl/COUSR03C.cbl:L313}</caption>
     *   <tr><th>Arm</th><th>Source</th><th>Text</th><th>Status</th></tr>
     *   <tr><td>{@code WHEN DFHRESP(NORMAL)}</td><td>{@code :L314-L322}</td>
     *       <td>{@code ' has been deleted ...'}</td><td>200</td></tr>
     *   <tr><td>{@code WHEN DFHRESP(NOTFND)}</td><td>{@code :L323-L328}</td>
     *       <td>{@code 'User ID NOT found...'}</td><td>404</td></tr>
     *   <tr><td>{@code WHEN OTHER}</td><td>{@code :L329-L335}</td>
     *       <td>{@code 'Unable to Update User...'}</td><td>502</td></tr>
     * </table>
     *
     * <p><strong>The third literal names the wrong verb, and it is returned verbatim.</strong>
     * {@code app/cbl/COUSR03C.cbl:L332} reports {@code 'Unable to Update User...'} on a path that was
     * deleting, not updating, and the source parks the cursor on {@code FNAMEL} at {@code :L333} - a
     * first-name field on a screen whose fault lay elsewhere. Neither is corrected: the verb is relayed as
     * written because the parity comparison is made on text, and the cursor has no Java counterpart at all.
     * Classified <strong>Low</strong>: a preserved source defect in wording, with no behavioural consequence.
     * Note also that the {@code WHEN OTHER} arm is the one place in these four programs that writes to the
     * region log, {@code DISPLAY 'RESP:' ... 'REAS:'} at {@code :L330}; the equivalent diagnostic is emitted
     * here at error level, naming the operation and never a key.
     *
     * <p>{@code 400} additionally answers an absent or malformed confirmation and an identifier wider than
     * the eight-character key. {@code 500} answers an abend carrying code {@code 999} and return code
     * {@code 12}. Deleting an identifier that was already deleted is a {@code 404} and not an error of this
     * operation: the read at {@code :L287-L292} reports it before the delete is attempted.
     *
     * <p><strong>Labelled omissions.</strong> {@code WHEN DFHPF4 PERFORM CLEAR-CURRENT-SCREEN} at
     * {@code app/cbl/COUSR03C.cbl:L119-L120} is screen behaviour with no counterpart, and both
     * {@code WHEN DFHPF3} at {@code :L111-L118} and {@code WHEN DFHPF12} at {@code :L123-L125} leave without
     * deleting - which a client does by not calling this operation. PF3 is conventional in this program,
     * unlike the sibling update program where the same key saves; the two are genuinely different and are not
     * harmonised.
     *
     * @param userId the eight-character identifier of the record to destroy, {@code SEC-USR-ID PIC X(08)};
     * width-checked by the service against the cluster key, and never compared against the acting
     * administrator's own identifier.
     * @param confirmedToken the caller's assertion that it was shown the record and is deliberately
     * proceeding, matched exactly against the two declared tokens; absence and any other spelling are refused
     * with a {@code 400} and destroy nothing.
     * @return {@code 200 OK} with the assembled screen carrying the source's deleted message and its blanked
     * detail fields; never null
     * @throws ValidationException if the confirmation is absent or malformed, or if the identifier is empty
     * or wider than the eight-character key
     * @throws RecordNotFoundException if the identifier is not present on the read or on the delete
     * @throws FatalProcessingException if the delete fails for any reason other than a typed CardDemo failure
     */
    @DeleteMapping(USER_PATH)
    public ResponseEntity<UserSecurityDto.UserDeleteScreen> deleteUser(
            @PathVariable(name = USER_ID_PATH_VARIABLE) final String userId,
            @RequestParam(name = CONFIRMED_PARAMETER, required = false) final String confirmedToken) {

        // The confirmation is refused here rather than passed through as false, because a false assertion
        // reaches the service as a concurrency outcome whose exception type is outside this file's dependency
        // contract, and answering a client-side omission with a 500 would be wrong. Nothing is destroyed on
        // this path.
        final boolean confirmed = requireConfirmation(confirmedToken);

        return ResponseEntity.ok(applyUserDelete(userId, confirmed));
    }

    /**
     * Returns the failure's own message when it is one of the source captions this class may publish, and the
     * supplied fixed detail otherwise.
     *
     * <p>See {@link #RETURNABLE_SOURCE_CAPTIONS} for why membership is decided by exact match. A {@code null}
     * message takes the fallback, which is the same outcome as an unrecognised one.
     *
     * @param message  the failure's message, possibly {@code null} and always treated as untrusted
     * @param fallback the fixed detail to publish when the message may not be, never {@code null}
     * @return the detail to publish, never {@code null}
     */
    private static String publishableDetail(final String message, final String fallback) {
        return RETURNABLE_SOURCE_CAPTIONS.contains(message) ? message : fallback;
    }

    /**
     * Projects the user-list service's screen record onto the response contract this operation publishes.
     *
     * <p><strong>Finding, severity High - remediated by this method and its two siblings.</strong> The three
     * body-bearing user-administration operations previously returned the service records themselves, so
     * {@code navigationTarget}, {@code cursorField}, {@code errorFlagOn}, {@code eraseRequested},
     * {@code sendCount}, {@code controlTransferred}, {@code selectionFlag}, {@code selectedUserId} and a
     * duplicated screen-and-page pair were serialized as the public REST contract. That is 3270 and
     * implementation state on the wire - a CWE-200-style exposure, and an accidental long-term compatibility
     * commitment to the service's internal shape. The service records are now strictly in-process: they cross
     * no serializer, and this class is the only thing that reads them.
     *
     * <p>What travels is what a caller can act on: the four business columns of each row, the paging cursor
     * the next request echoes, and the screen's own advisory message. What is withheld, and why, is enumerated
     * on {@code UserListResponse} and asserted against {@code UserListResponse.WITHHELD_COMPONENTS}, so the
     * boundary is machine-checked rather than merely described here.
     *
     * <p>The page number handed to the projection is the service's own {@code legacyPageNumber} rather than the
     * paging payload's floored one, because that is the value {@code CDEMO-CU00-PAGE-NUM} held and therefore
     * the value a caller must send back; the two differ only after a browse that found nothing, where the
     * source leaves zero.
     *
     * @param screen the record the user-list service assembled, already proven non-null; never serialized
     * @return the response contract, never null
     */
    private static UserListResponse publishableList(final UserListService.UserListScreen screen) {
        return UserListResponse.of(screen.page(), screen.legacyPageNumber(), screen.screen().errorMessage());
    }

    /**
     * Projects the user-add service's screen record onto the response contract this operation publishes.
     *
     * <p>The same boundary as {@link #publishableList(UserListService.UserListScreen)} and for the same
     * High-severity finding: the record carries the message-attribute byte, the cursor field, the advisory
     * navigation target and the six recurring header fields, none of which is a business outcome. The four
     * business fields and the source's own {@code ERRMSGI PIC X(78)} line survive, the latter relayed byte for
     * byte because on the success arm it carries the {@code ' has been added ...'} result of
     * {@code app/cbl/COUSR01C.cbl:L255-L257} and the parity comparison is made on text.
     *
     * <p>Neither the record nor the response declares a credential or digest component, so this projection has
     * nothing to blank and no way to leak one.
     *
     * @param screen the record the user-add service assembled, already proven non-null; never serialized
     * @return the response contract, never null
     */
    private static UserCreateResponse publishableAdd(final UserAddService.UserAddScreen screen) {
        return UserCreateResponse.of(screen.userId(), screen.firstName(), screen.lastName(),
                screen.userType(), screen.errorMessage());
    }

    /**
     * Projects the user-update service's screen record onto the response contract this operation publishes.
     *
     * <p>The same boundary again, with one addition that is deliberately <em>not</em> withheld:
     * {@code userModified} becomes {@code updateApplied}. It is the transcription of {@code WS-USR-MODIFIED} as
     * it stood at the write decision of {@code app/cbl/COUSR02C.cbl:L236}, so it distinguishes "saved" from
     * "nothing differed, so nothing was written" - an outcome of the operation rather than a description of a
     * screen, and behaviour the legacy screen conveyed that a caller would otherwise lose. The colour byte, the
     * cursor field, the transfer flag and the navigation target do not travel, because a client paints its own
     * screen and navigates by URL.
     *
     * @param screen the record the user-update service assembled, already proven non-null; never serialized
     * @return the response contract, never null
     */
    private static UserUpdateResponse publishableUpdate(final UserUpdateService.UserUpdateScreen screen) {
        return UserUpdateResponse.of(screen.userId(), screen.firstName(), screen.lastName(),
                screen.userType(), screen.errorMessage(), screen.userModified());
    }

    /**
     * Maps a rejected user-administration request onto {@code 400 Bad Request}.
     *
     * <p>This is not a {@code @ControllerAdvice} method and this class is not an advice type: status
     * selection is contextual, so each controller performs its own. The exception types carry no
     * {@code @ResponseStatus}, which is why the mapping is stated here rather than on them.</p>
     *
     * <p>The message is relayed <strong>byte for byte</strong>, which is what lets a legacy literal such as
     * {@code 'User ID can NOT be empty...'} from {@code app/cbl/COUSR02C.cbl:L182} reach a client exactly as
     * the 3270 screen displayed it. Nothing is fabricated: when the exception carries no message the response
     * carries no detail member either. These literals are screen captions and disclose no credential, no
     * digest and no protected field value, so relaying them is consistent with the secret-hygiene
     * standard.</p>
     *
     * <p>The failure kind is always reported, and that is the point: it is the two-state discriminator
     * transcribed from {@code app/cpy/CSSETATY.cpy}, and it is what keeps a field that was left blank
     * distinguishable from one that was supplied and wrong. Collapsing the two would erase the distinction
     * the three-state model exists to preserve. The field <em>name</em> is reported; the field
     * <em>value</em> never is, because on these operations it may be a personal name or a credential.</p>
     *
     * @param rejection the validation failure raised by this class or by a user-administration service, never
     * null when Spring MVC dispatches here.
     * @return {@code 400 Bad Request} carrying a problem detail, the rejected field name when the exception
     * named one, the failure kind, the stable error code and the correlation identifier
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
        problem.setProperty(FAILURE_KIND_PROPERTY, rejection.getFailureKind());

        LOG.warn("Refused a user administration request with 400 for field {} and failure kind {}",
                rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps an absent user onto {@code 404 Not Found}.
     *
     * <p>The translation of the {@code DFHRESP(NOTFND)} arms of {@code app/cbl/COUSR02C.cbl:L340} and
     * {@code app/cbl/COUSR03C.cbl:L287}, of the {@code STARTBR} arm at
     * {@code app/cbl/COUSR00C.cbl:L600-L606}, and of file status {@code '23'}. The detail is the source's own
     * caption when the failure carries one this class may publish, and {@value #NOT_FOUND_PROBLEM_DETAIL}
     * otherwise. The distinction is necessary because this type is one of the five {@code FileStatusMapper}
     * composes, and the messages it composes name the operation, the logical file and the
     * {@code COBOL FILE STATUS} - none of which a caller may see. Deciding by exact match against
     * {@link #RETURNABLE_SOURCE_CAPTIONS} keeps the literal parity without depending on how any other file
     * happens to phrase an error.</p>
     *
     * <p><strong>The record key is deliberately not reported.</strong> The exception can carry a record type
     * and a record key, and on this resource group the key is a user identifier. This handler reads neither. A
     * client that needs to know which user it asked about already knows: it supplied it in the path.</p>
     *
     * @param absent the not-found failure raised by a user-administration service, never null when Spring MVC
     * dispatches here.
     * @return {@code 404 Not Found} carrying the fixed detail, the stable error code and the correlation
     * identifier, and no record type and no record key
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absent) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);
        problem.setDetail(publishableDetail(absent.getMessage(), NOT_FOUND_PROBLEM_DETAIL));

        LOG.warn("Refused a user administration request with 404: no matching record."
                + " No key is logged or returned");

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(withPublicEnvelope(problem, ERROR_CODE_NOT_FOUND));
    }

    /**
     * Maps an identifier that is already taken onto {@code 409 Conflict}.
     *
     * <p>The translation of the two arms that share one outcome on the add operation:
     * {@code WHEN DFHRESP(DUPKEY)} at {@code app/cbl/COUSR01C.cbl:L260} and
     * {@code WHEN DFHRESP(DUPREC)} at {@code :L261}, and of file status {@code '22'}. That site is the
     * canonical duplicate-key site of the whole corpus.</p>
     *
     * <p>The message is relayed <strong>byte for byte</strong>, which is what puts
     * {@code 'User ID already exist...'} from {@code app/cbl/COUSR01C.cbl:L263} on the wire exactly as the
     * screen displayed it - <strong>"exist", not "exists"</strong>. The spelling is the source's and is not
     * corrected, because the parity comparison is made on text. Classified <strong>Low</strong>: a preserved
     * source defect in wording, with no behavioural consequence.</p>
     *
     * <p><strong>The logical file and the colliding key are neither returned nor logged.</strong> Together
     * they would tell a caller which internal dataset rejected which key, and separately neither is
     * actionable by a caller that already knows the identifier it asked to create. The status, the relayed
     * caption and the stable error code are the contract; the correlation identifier is what ties a report
     * back to its request.</p>
     *
     * @param collision the duplicate-key failure raised by the user-add service, never null when Spring MVC
     * dispatches here.
     * @return {@code 409 Conflict} carrying the source's own caption when the exception has one, the stable
     * error code and the correlation identifier, and neither the logical file nor the colliding key
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(final DuplicateRecordException collision) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle(DUPLICATE_PROBLEM_TITLE);

        final String collisionMessage = collision.getMessage();
        if (collisionMessage != null) {
            problem.setDetail(collisionMessage);
        }

        // The logical file is logged, not returned: naming the dataset tells a caller nothing it can act on.
        // The colliding key is neither logged nor returned, because it is a user identifier.
        LOG.warn("Refused a user administration request with 409: duplicate key on file {}",
                collision.getLogicalFile());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withPublicEnvelope(problem, ERROR_CODE_DUPLICATE));
    }

    /**
     * Maps a constraint refusal that is <em>not</em> a duplicate key onto {@code 409 Conflict}.
     *
     * <p><strong>Why this handler exists.</strong> {@code WRITE-USER-SEC-FILE} at
     * {@code app/cbl/COUSR01C.cbl}:250-274 has one duplicate arm - {@code DFHRESP(DUPKEY)} at {@code :260} and
     * {@code DFHRESP(DUPREC)} at {@code :261} sharing a body - and one {@code WHEN OTHER} at {@code :267}.
     * {@code V1__create_schema.sql} declares constraints on {@code user_security} that the KSDS did not,
     * {@code ck_user_security_type} among them, and a refusal by one of those is a condition the source could
     * not encounter. Folding it onto the duplicate arm answered {@code 'User ID already exist...'} for an
     * identifier that was free, with no row created - a statement about the store that was simply untrue. This
     * handler is where that ends.</p>
     *
     * <p>{@code 409} rather than {@code 400}: the body is well formed and its fields individually valid, and
     * what refuses the row is the state of the schema. {@code docs/api-contracts.md} section 8.2 documents
     * {@value #ERROR_CODE_CONSTRAINT} against exactly this condition, and both other write surfaces answer it
     * the same way.</p>
     *
     * <p><strong>The relation and the constraint name are logged, not returned.</strong> Together they map the
     * store, either may change with any migration, and a caller can act on neither. The operator has both at
     * {@code WARN} - the relation in the message and the constraint on the retained cause - joined to the
     * caller's report by the {@code correlationId} the body carries. No user identifier and no credential
     * reaches either the body or the log line.</p>
     *
     * @param violation the constraint refusal; its constraint name and relation may each be null.
     * @return {@code 409 Conflict} carrying the exception's own message, the stable error code and the
     *         correlation identifier
     */
    @ExceptionHandler(DataIntegrityException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(final DataIntegrityException violation) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle(INTEGRITY_PROBLEM_TITLE);

        final String violationMessage = violation.getMessage();
        if (violationMessage != null) {
            problem.setDetail(violationMessage);
        }

        // The relation is named and the constraint is not, for the reason given on the transaction surface's
        // counterpart: the retained cause carries the driver's own message, which names both the constraint
        // and the offending key, and it is logged with this entry.
        LOG.warn("Refused a user administration request with 409: a constraint on relation {} refused the "
                + "write. This is NOT a duplicate identifier and the request is not retryable as submitted",
                violation.getRelation(), violation);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withPublicEnvelope(problem, ERROR_CODE_CONSTRAINT));
    }

    /**
     * Maps an unavailable security file onto {@code 503 Service Unavailable}.
     *
     * <p>The translation of file status {@code '35'} and of {@code DFHRESP(NOTOPEN)}. The condition is
     * transient by nature - the datastore is not reachable rather than the request being wrong - which is
     * what {@code 503} states, and it is the status every other controller in this package answers for the
     * same exception.</p>
     *
     * <p><strong>The resource name is not returned.</strong> It names the dataset behind the failure, which a
     * caller cannot act on and an attacker can map. It is logged at error level and reachable through the
     * correlation identifier the body carries.</p>
     *
     * @param unavailable the unavailable-file failure, never null when Spring MVC dispatches here.
     * @return {@code 503 Service Unavailable} carrying a fixed problem detail, the stable error code and the
     * correlation identifier
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);

        LOG.error("A user administration operation could not reach file {}",
                unavailable.resourceName().orElse("unnamed"), unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNAVAILABLE));
    }

    /**
     * Maps a security-file I/O failure onto {@code 502 Bad Gateway}.
     *
     * <p>The translation of the {@code '9x'} file-status family and of the {@code WHEN OTHER} arms of the
     * read, write, rewrite and delete paragraphs - {@code app/cbl/COUSR00C.cbl:L607},
     * {@code app/cbl/COUSR01C.cbl:L267}, {@code app/cbl/COUSR02C.cbl:L346} and {@code :L383}, and
     * {@code app/cbl/COUSR03C.cbl:L293} and {@code :L329}. A physical or logical input-output failure is a
     * fault in the store behind this service rather than in the request or in this service, which is exactly
     * what a gateway status describes, and it is the status the rest of this package already answers for the
     * identical exception.</p>
     *
     * <p>The four-character expanded status the legacy status renderer produced, the logical file and the
     * operation are all <strong>logged rather than returned</strong>. Individually none discloses data;
     * together they tell a caller which internal dataset failed which verb with which status. The body carries
     * {@value #ERROR_CODE_IO_FAILURE} and the correlation identifier that joins it to the log record holding
     * the three withheld values, and the cause is logged rather than returned. This is also where the source's
     * own {@code DISPLAY 'RESP:' ... 'REAS:'} at {@code app/cbl/COUSR03C.cbl:L330} ends up: on a log stream,
     * never on the screen.</p>
     *
     * <p>The detail is the source's own caption when the failure carries one this class may publish -
     * {@code 'Unable to Update User...'} from {@code app/cbl/COUSR02C.cbl:L386} and
     * {@code app/cbl/COUSR03C.cbl:L332} reaches a caller through this handler, wrong verb and all - and
     * {@value #IO_PROBLEM_DETAIL} otherwise. Membership is decided by exact match against
     * {@link #RETURNABLE_SOURCE_CAPTIONS}, so a message composed by {@code FileStatusMapper} naming the
     * dataset and the status can never be published by this path.</p>
     *
     * @param failure the I/O failure, never null when Spring MVC dispatches here.
     * @return {@code 502 Bad Gateway} carrying a publishable problem detail, the stable error code and the
     * correlation identifier
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                publishableDetail(failure.getMessage(), IO_PROBLEM_DETAIL));
        problem.setTitle(IO_PROBLEM_TITLE);

        LOG.error("Answered a user administration request with 502: status {} on file {} during {}",
                failure.getExpandedStatus(), failure.getLogicalFileName(), failure.getOperation(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(withPublicEnvelope(problem, ERROR_CODE_IO_FAILURE));
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract on the
     * diagnostic log.
     *
     * <p>The abend routine moved {@code 999} into {@code ABEND-CODE} and called the language-environment
     * abend service, and the batch stream reported return code {@code 12}; both figures come from
     * {@code app/cpy/CSMSG02Y.cpy}, whose four work areas - {@code ABEND-CODE X(4)},
     * {@code ABEND-CULPRIT X(8)}, {@code ABEND-REASON X(50)} and {@code ABEND-MSG X(72)} - are carried by the
     * exception rather than rebuilt here. <strong>Both figures are logged and neither is returned:</strong>
     * they describe an internal termination path, so a caller can act on neither while a caller who can read
     * them learns which path a crafted request reached. The detail is fixed, the body carries
     * {@value #ERROR_CODE_ABEND} and the correlation identifier, and the cause remains attached to the
     * exception and is logged at error level.</p>
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

        LOG.error("A user administration operation abended: code {} returnCode {} culprit {} reason {}",
                abend.getAbendCode() == null ? ABEND_CODE : abend.getAbendCode(),
                FatalProcessingException.BATCH_RETURN_CODE, abend.getAbendCulprit(), abend.getAbendReason(),
                abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps every remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>Spring MVC resolves the most specific handler, so none of the six types mapped above reaches this
     * method. It covers the rest of the nine-type hierarchy, each of which stands for a legacy file status or
     * response code. Two of them are worth naming because they are reachable in principle and are not part of
     * this file's dependency contract: the concurrency translation, which the delete service raises when a
     * confirmation is not asserted and which cannot arrive here because {@link #deleteUser} refuses an
     * unconfirmed request before delegating, and the referential-integrity translation, which no
     * user-administration path raises because the security cluster carries no foreign key. Both resolve here
     * rather than through a mapping of their own. The detail is the fixed string used for an abend, for the
     * same reason, and the cause is logged rather than returned.</p>
     *
     * @param failure the typed failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and
     * the correlation identifier
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("A user administration operation failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Maps a bean-validation failure the framework raised on the request body onto {@code 400 Bad Request}
     * with this controller's own envelope.
     *
     * <p><strong>Why this mapper exists.</strong> {@code @Valid} on the add-user and update-user bodies is
     * enforced by the framework <em>before</em> the mapped method is entered, so a violation never reaches the
     * service and never becomes the {@link ValidationException} the mapper above claims. Without this method
     * the refusal escaped to the framework's default handling and answered with a body of an entirely
     * different shape - no {@code title}, no {@code errorCode} and no {@code correlationId} - so one logical
     * outcome, a rejected input, looked like two unrelated failures depending on which layer noticed it. A
     * review recorded that inconsistency as a High-severity finding against this class.
     *
     * <p><strong>Why it is declared here rather than centrally.</strong> Stated for the whole package in
     * {@code com.cardemo.controller}'s package documentation; this handler keeps that property, carrying
     * {@code @ExceptionHandler} only and answering for this controller alone.
     *
     * <p><strong>What the body does not contain.</strong> No rejected value, no field name, no constraint
     * message, no exception class and no discriminator; {@link #BIND_FAILURE_PROBLEM_DETAIL} records why each
     * is withheld. The discriminator in particular is omitted rather than invented: the framework supplies no
     * counterpart to the two-state marker of {@code app/cpy/CSSETATY.cpy}, so publishing one would misreport
     * it.
     *
     * <p>This method is not request-mapped and is none of the four operations.
     *
     * @param rejection the framework's binding result, read for the log line only - its field errors retain
     * the submitted values, so nothing is copied out of them into the body
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the rejection
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyBindFailure(
            final MethodArgumentNotValidException rejection) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, BIND_FAILURE_PROBLEM_DETAIL);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        LOG.warn("Refused a user administration request with 400: the framework rejected {} field(s) of the "
                        + "request body before the operation was entered. Fields {}",
                rejection.getBindingResult().getFieldErrorCount(),
                rejection.getBindingResult().getFieldErrors().stream()
                        .map(FieldError::getField).distinct().sorted().toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps a request body the framework could not read onto {@code 400 Bad Request} with this controller's
     * own envelope.
     *
     * <p>Which three framework conditions fold into this one exception, why the status is {@code 400}
     * rather than {@code 415} or {@code 422}, and why the handler is declared per controller rather than
     * centrally are stated for the whole package in {@code com.cardemo.controller}'s package
     * documentation. The two refusals are told apart by {@link #ERROR_CODE_UNREADABLE_BODY} rather than
     * by the status line, and this method is not request-mapped, so it is none of the four operations.
     *
     * @param unreadable the framework's read failure, whose message and cause chain are deliberately kept out
     * of the body and emitted at {@code DEBUG} only
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the parser
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            final HttpMessageNotReadableException unreadable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNREADABLE_BODY_PROBLEM_DETAIL);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        LOG.warn("Refused a user administration request with 400: the request body could not be read. Read "
                + "failure {}", unreadable.getClass().getSimpleName());
        // The cause chain is preserved rather than dropped, but it is emitted only at DEBUG. A parser message
        // can quote the fragment of the payload it stopped on, and the add-user payload carries a plaintext
        // password, so it must not be written at a level that is enabled in every environment. At DEBUG an
        // operator who deliberately asks for the chain gets all of it.
        LOG.debug("Cause chain of the unreadable user administration body", unreadable);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNREADABLE_BODY));
    }

    /**
     * Opens the user list at its first page, delegating exactly once.
     *
     * <p>Extracted from {@link #listUsers} so that the handler reads as a sequence of decisions and the abend
     * translation is stated once. Both catch clauses are load bearing and neither swallows anything:
     * {@code CardDemoException} is rethrown unchanged so that the declared status mapping applies to it, and
     * because that type extends {@code RuntimeException} the first clause is what stops the second from
     * re-wrapping an already-typed failure. Every other runtime failure becomes an abend with the original
     * throwable preserved as the cause.</p>
     *
     * @param userIdInput the identifier filter as received, relayed verbatim and never normalised.
     * @return the assembled screen with its page of rows, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns nothing
     */
    private UserListService.UserListScreen openUserList(final String userIdInput) {

        final UserListService.UserListScreen screen;
        try {
            screen = this.userListService.listUsers(userIdInput);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, USER_LIST_PROGRAM, USER_LIST_ABEND_REASON,
                    USER_LIST_ABEND_MESSAGE, unexpected);
        }
        return requireScreen(screen, USER_LIST_PROGRAM, USER_LIST_ABEND_REASON, USER_LIST_ABEND_MESSAGE);
    }

    /**
     * Pages the user list forward or backward, delegating exactly once.
     *
     * <p>The action is translated into the attention identifier the service dispatches on, which is the whole
     * of the controller-level action mapping: {@code app/cpy/CSSTRPFY.cpy} is a {@code PROCEDURE DIVISION}
     * copybook whose paragraph {@code YYYY-STORE-PFKEY.} at {@code :L17} evaluates {@code EIBAID} and stores
     * the result into the action state of {@code app/cpy/CVCRD01Y.cpy}; it declares no data of its own, so it
     * has no entity, no data transfer object and no import. What replaces it is an explicit, enumerated
     * request parameter - never a generic attention-identifier string and never a reimplemented function-key
     * dispatcher.</p>
     *
     * @param action the navigation intent, already resolved to one of the declared constants and never the
     * default arm, which {@link #listUsers} answers from the dedicated entry point instead.
     * @param request the echoed cursor and the identifier filter.
     * @return the assembled screen with its page of rows, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns nothing
     */
    private UserListService.UserListScreen pageUserList(final UserListAction action,
            final UserListService.UserListRequest request) {

        final UserListService.UserListScreen screen;
        try {
            screen = this.userListService.submitScreen(action.attentionIdentifier(), request);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, USER_LIST_PROGRAM, USER_LIST_ABEND_REASON,
                    USER_LIST_ABEND_MESSAGE, unexpected);
        }
        return requireScreen(screen, USER_LIST_PROGRAM, USER_LIST_ABEND_REASON, USER_LIST_ABEND_MESSAGE);
    }

    /**
     * Adds one user, delegating exactly once.
     *
     * <p>The credential is read from the body exactly once, in the argument expression of the delegation
     * below, and is handed straight through as the service's explicit argument. It is not assigned to any
     * field, not copied into any local variable or structure, and not included in the abend payload built
     * below - the reason, the message and the culprit name the operation and the program, never a value.
     * Reading it in the call expression is what makes "the value audited is the value hashed" structural
     * rather than conventional: there is no second channel it could have arrived on and no intermediate copy
     * that could diverge from what was bound.</p>
     *
     * @param request the twelve declared fields of the add screen, one of which is the presented plaintext
     * credential; may carry that credential as null, empty or blank.
     * @param presentedPassword the credential the body's one-way reader surrendered for this call alone; may
     * be null, empty or blank, each of which takes the source's own empty-password arm
     * @return the assembled screen carrying the source's added message, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns nothing
     */
    private UserAddService.UserAddScreen applyUserAdd(final UserCreateRequest request,
            final String presentedPassword) {

        final UserAddService.UserAddScreen screen;
        try {
            screen = this.userAddService.addUser(request, presentedPassword);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, USER_ADD_PROGRAM, USER_ADD_ABEND_REASON,
                    USER_ADD_ABEND_MESSAGE, unexpected);
        }
        return requireScreen(screen, USER_ADD_PROGRAM, USER_ADD_ABEND_REASON, USER_ADD_ABEND_MESSAGE);
    }

    /**
     * Updates one user, delegating exactly once, after proving that the identifier was named consistently.
     *
     * <p><strong>The store-level guard is the source's own, and it is why no snapshot is presented here.</strong>
     * {@code app/cbl/COUSR02C.cbl:L322-L328} reads with {@code EXEC CICS READ ... UPDATE} against a file defined
     * {@code UPDATEMODEL(LOCKING)}, and rewrites at {@code :L360}; the service's read now goes through
     * {@code com.cardemo.repository.UserSecurityRepository#findByIdForUpdate(String)}, which acquires the same
     * exclusive hold and keeps it until the service's transaction ends. The read, the change detection and the
     * rewrite are therefore indivisible, and two administrators can no longer interleave them.
     *
     * <p>The business-level snapshot argument is passed as {@link #NO_CLIENT_SNAPSHOT}, which the service
     * documents as reproducing the source's own submission - the source carried no snapshot beyond the screen
     * itself. That is a deliberate consequence of the surface rather than an omission: the seventeen CSD
     * transactions fix the endpoint set, there is no per-user read among them from which a sealed as-displayed
     * snapshot could be issued, and inventing an eighteenth route would exceed the migration's declared scope.
     * The sibling account and card updates do carry a sealed snapshot precisely because
     * {@code app/csd/CARDDEMO.CSD} gives each of them a read transaction to issue one from. This limitation, and
     * the pessimistic lock that covers it, are stated on {@link #updateUser} rather than hidden here.</p>
     *
     * @param userId the identifier from the path, which addresses the record.
     * @param request the twelve declared fields of the update screen. An absent identifier member is filled
     * from the path; one that is present but empty is carried through unchanged, so that the service raises the
     * source's own empty-identifier rejection rather than this class silently supplying a value - finding
     * API-003.
     * @return the assembled screen, carrying either the source's updated message or its "please modify"
     * advisory, never null
     * @throws ValidationException if the body names a user other than the one the path addresses, or - raised
     * by the service rather than here - if the body names the identifier member emptily
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns nothing
     */
    private UserUpdateService.UserUpdateScreen applyUserUpdate(final String userId,
            final UserUpdateRequest request) {

        requireIdentifiersAgree(userId, request.userId());

        final UserUpdateService.UserUpdateScreen screen;
        try {
            screen = this.userUpdateService.updateUser(withIdentityFrom(request, userId),
                    NO_CLIENT_SNAPSHOT);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, USER_UPDATE_PROGRAM, USER_UPDATE_ABEND_REASON,
                    USER_UPDATE_ABEND_MESSAGE, unexpected);
        }
        return requireScreen(screen, USER_UPDATE_PROGRAM, USER_UPDATE_ABEND_REASON,
                USER_UPDATE_ABEND_MESSAGE);
    }

    /**
     * Deletes one user, delegating exactly once.
     *
     * <p><strong>No identity is consulted, deliberately.</strong> The acting administrator's own identifier is
     * neither read nor compared, because {@code app/cbl/COUSR03C.cbl} contains no such comparison; see the
     * preserved-quirk heading on {@link #deleteUser}.</p>
     *
     * @param userId the identifier from the path, which addresses the record to destroy.
     * @param confirmed the caller's assertion, already proven true by {@link #deleteUser} before this method
     * is reached, and relayed rather than re-derived so that the value the caller asserted is the value the
     * service sees.
     * @return the assembled screen carrying the source's deleted message, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns nothing
     */
    private UserSecurityDto.UserDeleteScreen applyUserDelete(final String userId, final boolean confirmed) {

        final UserSecurityDto.UserDeleteScreen screen;
        try {
            screen = this.userDeleteService.deleteUser(userId, confirmed);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, USER_DELETE_PROGRAM, USER_DELETE_ABEND_REASON,
                    USER_DELETE_ABEND_MESSAGE, unexpected);
        }
        return requireScreen(screen, USER_DELETE_PROGRAM, USER_DELETE_ABEND_REASON,
                USER_DELETE_ABEND_MESSAGE);
    }

    /**
     * Refuses a screen a service did not assemble.
     *
     * <p>All four services document their result as never null, so this is a broken service contract rather
     * than a request outcome and is reported as an abend. It is checked rather than assumed because a null
     * would otherwise be serialised as an empty body with a success status, which is the one failure mode a
     * caller cannot distinguish from a genuine answer.</p>
     *
     * @param <T> the screen projection type the operation returns.
     * @param screen the value the service returned, possibly null.
     * @param program the originating COBOL program, recorded as the abend culprit.
     * @param reason the abend reason, which names the operation and never a value.
     * @param message the abend message, which names the transaction and never a value.
     * @return {@code screen}, proven non-null
     * @throws FatalProcessingException if {@code screen} is null
     */
    private static <T> T requireScreen(final T screen, final String program, final String reason,
            final String message) {

        if (screen == null) {
            throw new FatalProcessingException(ABEND_CODE, program, reason, message);
        }
        return screen;
    }

    /**
     * Supplies the addressed identifier to a body that did not name one, reproducing
     * {@code app/cbl/COUSR02C.cbl:L102-L103}.
     *
     * <p><strong>Finding, severity High - remediated here.</strong> The body was previously relayed
     * unchanged, so a request carrying the documented shape - the identifier in the path only - was refused
     * with {@code 'User ID can NOT be empty...'} and the operation could be reached only by duplicating the
     * identifier inside the body, which nothing documents.
     *
     * <p><strong>Why the path is the right source, from the source.</strong> On first entry the program does
     * not wait for an operator to type the identifier: {@code :L100-L107} tests
     * {@code CDEMO-CU02-USR-SELECTED}, the identifier the user-list screen put in the commarea, moves it into
     * {@code USRIDINI OF COUSR2AI} and only then performs {@code PROCESS-ENTER-KEY}. The screen field is
     * therefore a <em>carrier</em> of the navigation context, not its origin. In this surface the navigation
     * context is the path segment, which is why {@code USRIDINI} may be omitted from the body and why
     * {@link #requireIdentifiersAgree} still refuses a body that names a different user.
     *
     * <p><strong>FINDING API-003, severity HIGH, RESOLVED here: absent and empty are different states, and
     * only the absent one is filled.</strong> The substitution previously fired for a blank body value as well
     * as for an absent one, which let a caller walk straight past a validation branch the source enforces
     * twice. {@code app/cbl/COUSR02C.cbl} rejects {@code USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES} with
     * {@code 'User ID can NOT be empty...'} at {@code :L146-L151} and again at {@code :L179-L185}; a request
     * naming {@code "userId": ""}, {@code "   "} or a run of NUL bytes reached neither, because the path value
     * had already been written over it. It now travels exactly as sent and the service refuses it in the
     * source's own words.
     *
     * <p>The distinction is the source's, not an invention. Substitution happens on ONE leg only: at
     * {@code :L95-L104}, on first entry, and only when {@code CDEMO-CU02-USR-SELECTED NOT = SPACES AND
     * LOW-VALUES} - so even there an empty selection was never moved onto the screen. On re-entry, the leg a
     * write corresponds to, {@code :L106-L111} performs {@code RECEIVE-USRUPD-SCREEN} and validates what the
     * terminal actually sent, with no back-fill from the commarea at all. Filling an explicitly empty member
     * would therefore be substituting on a leg that never substituted, from a value the source's own guard
     * excluded.
     *
     * <p>Emptiness is judged the way COBOL judged it, by
     * {@link #namesNoIdentifier(String)}: blanks and control bytes both count, because
     * {@code = SPACES OR LOW-VALUES} covers both fills and {@code String#isBlank()} covers only the first -
     * a NUL-filled member used to escape the blank test entirely and be refused as a <em>mismatch</em>, which
     * is the wrong branch and the wrong message.
     *
     * <p>What this does not weaken: a request that names no identifier at all - the documented shape, with the
     * identifier in the path only - is still filled from the path and still reaches the operation. That is the
     * case {@code :L102-L103} models, and it is the case the earlier remediation existed to admit.
     *
     * <p>Only the identifier is substituted. The other eleven declared members travel exactly as sent,
     * because {@code :L102-L103} is one MOVE into one field and touches nothing else - in particular it does
     * not clear the four data fields, which {@code :L158-L161} does separately and only after the edit
     * passes.
     *
     * @param request the twelve declared fields of the update screen, as submitted.
     * @param userId the identifier from the path, which addresses the record.
     * @return the same request whenever the body named the member at all, even emptily; a copy naming the
     * addressed identifier only when the member was absent, never null
     */
    private static UserUpdateRequest withIdentityFrom(final UserUpdateRequest request,
            final String userId) {

        if (request.userId() != null) {
            return request;
        }
        return new UserUpdateRequest(request.transactionName(), request.title01(), request.currentDate(),
                request.programName(), request.title02(), request.currentTime(), userId,
                request.firstName(), request.lastName(), request.password(), request.userType(),
                request.errorMessage());
    }

    /**
     * Reports whether a submitted identifier names no user, on the source's own terms.
     *
     * <p>Finding API-003. {@code app/cbl/COUSR02C.cbl:L146} and {@code :L180} both test
     * {@code USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES}, so a field of blanks and a field of low values are
     * equally empty. {@code String#isBlank()} reproduces only the first of those: {@code Character.isWhitespace}
     * is false for {@code U+0000}, so a NUL-filled member is not blank to Java while being unambiguously empty
     * to the source. Control bytes are therefore tested alongside whitespace, which is the same predicate
     * {@code com.cardemo.service.admin.UserUpdateService} applies before raising
     * {@code 'User ID can NOT be empty...'} - the two must agree, or this class would classify a value one way
     * and the service another.
     *
     * <p>Note what this is <em>not</em> used for: it does not decide whether to fill the identifier from the
     * path. {@link #withIdentityFrom} fills only an absent member, because absent and empty are different
     * states and only one of them is silence. This predicate distinguishes the empty state from a genuine
     * identifier, which is what {@link #requireIdentifiersAgree} needs to avoid reporting an empty value as a
     * disagreement about which user is meant.
     *
     * @param identifier the value the body carried, possibly {@code null}
     * @return {@code true} when the value is absent, empty, all whitespace or all control bytes
     */
    private static boolean namesNoIdentifier(final String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return true;
        }
        for (int index = 0; index < identifier.length(); index++) {
            final char character = identifier.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a request whose body names a different user than its path does.
     *
     * <p>Both places are part of the contract - the path addresses the record and
     * {@code USRIDINI PIC X(8)} is a declared member of the map - so neither is dropped and neither silently
     * wins. The comparison is exact: nothing is trimmed and nothing is case-folded, because
     * {@code app/cbl/COUSR02C.cbl} folds neither on this transaction, and folding here would accept a pairing
     * the source would have treated as two different keys.</p>
     *
     * <p><strong>Three states, and finding API-003 turns on the difference between two of them.</strong>
     * Neither an absent member nor an empty one is a disagreement, but they are admitted here for different
     * reasons and they end differently.</p>
     *
     * <ul>
     *   <li><strong>Absent</strong> - the body never named the member. Admitted, and
     *       {@link #withIdentityFrom} then fills it from the path exactly as
     *       {@code app/cbl/COUSR02C.cbl:L102-L103} fills {@code USRIDINI} from the commarea selection. The
     *       operation proceeds against the addressed record.</li>
     *   <li><strong>Present but empty</strong> - blanks, or control bytes, per {@link #namesNoIdentifier}.
     *       Admitted <em>here</em>, because an empty value makes no claim about <em>which</em> user is meant
     *       and refusing it as a mismatch would report the wrong failure in the wrong words. It is not filled
     *       from the path, and it reaches
     *       {@code com.cardemo.service.admin.UserUpdateService}, which refuses it with the source's own
     *       {@code 'User ID can NOT be empty...'} from {@code :L146-L151} and {@code :L179-L185}. Before
     *       finding API-003 was resolved this state was indistinguishable from the absent one and the source's
     *       guard was unreachable through this surface.</li>
     *   <li><strong>Present and naming a user</strong> - must equal the path exactly, or the request is
     *       refused here.</li>
     * </ul>
     *
     * @param pathIdentifier the identifier from the path, which addresses the record.
     * @param bodyIdentifier the identifier the body carried, possibly null, empty or blank.
     * @throws ValidationException if the body named a user other than the one the path addresses
     */
    private static void requireIdentifiersAgree(final String pathIdentifier, final String bodyIdentifier) {

        // Both non-naming states pass through, and the distinction between them belongs to withIdentityFrom:
        // an absent member is filled from the path, an empty one is carried to the service to be refused in
        // the source's own words.
        if (namesNoIdentifier(bodyIdentifier)) {
            return;
        }
        if (!bodyIdentifier.equals(pathIdentifier)) {
            throw ValidationException.invalidField(USER_ID_PATH_VARIABLE,
                    "the user identifier in the request body must match the one in the request path, because"
                            + " the path addresses the record being updated and the body declares the same"
                            + " field; neither is preferred over the other and neither is discarded");
        }
    }

    /**
     * Parses an unsigned decimal token, accepting ASCII digits and nothing else.
     *
     * <p>Deliberately not the framework's integer conversion, which accepts a leading sign and would let
     * {@code -1} be presented as a page cursor. A cursor and a row count are unsigned by declaration -
     * {@code CDEMO-CU00-PAGE-NUM} is {@code PIC 9(08)} - so a sign is not a negative value here, it is a
     * malformed token.</p>
     *
     * @param token the token as received, or null when the parameter was absent.
     * @param fieldName the field name to name in a failure.
     * @param maximumDigits the widest digit run accepted, taken from the declared width of the field the token
     * echoes.
     * @return the parsed value, or zero when the token was absent or empty - which is what a first request
     * means, since the source's own cursor starts at zeros
     * @throws ValidationException if the token holds anything other than ASCII digits, or is longer than
     * {@code maximumDigits}
     */
    private static int requireDigits(final String token, final String fieldName, final int maximumDigits) {

        if (token == null || token.isEmpty()) {
            return 0;
        }
        if (token.length() > maximumDigits) {
            throw ValidationException.invalidField(fieldName,
                    fieldName + " must be at most " + maximumDigits
                            + " digits, which is the declared width of the field it echoes");
        }
        for (int position = 0; position < token.length(); position++) {
            final char digit = token.charAt(position);
            if (digit < '0' || digit > '9') {
                throw ValidationException.invalidField(fieldName,
                        fieldName + " must hold decimal digits only, with no sign, no separator and no"
                                + " surrounding space");
            }
        }
        return Integer.parseInt(token);
    }

    /**
     * Parses a boolean token, accepting exactly {@value #TRUE_TOKEN} and {@value #FALSE_TOKEN}.
     *
     * <p>Deliberately not the framework's boolean conversion, which additionally accepts {@code on},
     * {@code off}, {@code yes}, {@code no}, {@code 1} and {@code 0}. This token decides whether the browse
     * pages forward at all, so the accepted set is closed and stated rather than inherited.</p>
     *
     * @param token the token as received, or null when the parameter was absent.
     * @param fieldName the field name to name in a failure.
     * @return the parsed value, or false when the token was absent or empty - which is what a first request
     * means, since the source starts its turn with the next-page flag off at
     * {@code app/cbl/COUSR00C.cbl:L102}
     * @throws ValidationException if the token is neither of the two accepted spellings
     */
    private static boolean requireBooleanToken(final String token, final String fieldName) {

        if (token == null || token.isEmpty()) {
            return false;
        }
        if (TRUE_TOKEN.equals(token)) {
            return true;
        }
        if (FALSE_TOKEN.equals(token)) {
            return false;
        }
        throw ValidationException.invalidField(fieldName,
                fieldName + " must be exactly '" + TRUE_TOKEN + "' or '" + FALSE_TOKEN
                        + "', with no alias and no case fold");
    }

    /**
     * Requires the delete confirmation to be asserted, and refuses the request otherwise.
     *
     * <p>The mechanism substitution for the source's two-interaction gate: the {@code DFHENTER} arm read the
     * record and emitted the prompt of {@code app/cbl/COUSR03C.cbl:L283}, and a different key then destroyed
     * it. A stateless surface cannot remember the first interaction, so the caller asserts it. An absent
     * assertion is refused as the blank state and a present-but-wrong one as the invalid state, which keeps
     * "you did not confirm" distinguishable from "you sent something that is not a confirmation".</p>
     *
     * @param token the confirmation token as received, or null when the parameter was absent.
     * @return true, always, since every other outcome is refused
     * @throws ValidationException if the token is absent, empty, malformed, or explicitly false
     */
    private static boolean requireConfirmation(final String token) {

        if (token == null || token.isEmpty()) {
            throw ValidationException.missingField(CONFIRMED_PARAMETER,
                    CONFIRMED_PARAMETER + " must be supplied as '" + TRUE_TOKEN + "' to delete a user,"
                            + " which is the stateless equivalent of the confirmation the delete screen"
                            + " required before it destroyed a record; nothing was deleted");
        }
        if (!requireBooleanToken(token, CONFIRMED_PARAMETER)) {
            throw ValidationException.invalidField(CONFIRMED_PARAMETER,
                    CONFIRMED_PARAMETER + " must be '" + TRUE_TOKEN + "' to delete a user; nothing was"
                            + " deleted");
        }
        return true;
    }

    /**
     * Rebuilds the echoed page as the row list the service's request record declares.
     *
     * <p>Only the size of that list is read by the browse - it is how far the last displayed row sits into its
     * page, which is what {@code PROCESS-PF8-KEY} at {@code app/cbl/COUSR00C.cbl:L260} advances from - so the
     * rows carry no data. That is not a shortcut: the one per-row value the source did read is the selector
     * {@code USER-SEL PIC X(01)} at {@code :L58}, and row selection has no Java counterpart at all, because
     * selecting a row in order to reach the update or delete screen becomes the caller naming that identifier
     * on {@link #updateUser} or {@link #deleteUser}. Sending identifiers and names back only to have them
     * ignored would put personal data on a query string for no purpose.</p>
     *
     * @param rowCount how many rows the previous response carried, already proven to be a bounded
     * non-negative decimal.
     * @return an unmodifiable list of that many rows, each carrying no value; empty when the count is zero
     */
    private static List<UserSecurityDto.UserRow> echoedRows(final int rowCount) {

        if (rowCount == 0) {
            return List.of();
        }
        final List<UserSecurityDto.UserRow> rows = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            rows.add(new UserSecurityDto.UserRow(null, null, null, null, null));
        }
        return List.copyOf(rows);
    }

    /**
     * Resolves the navigation intent from its token, accepting exactly the three declared spellings.
     *
     * <p>Matched rather than converted, for the same reason the numeric and boolean tokens are: the
     * framework's enum conversion trims its input, and a trimmed control token is an instruction this
     * operation never declared. An unrecognised token is the transcription of the source's own
     * {@code WHEN OTHER} arm at {@code app/cbl/COUSR00C.cbl:L133-L136}, which reported an invalid key and
     * redisplayed; here it is a rejected field.</p>
     *
     * @param token the token as received, or null when the parameter was absent.
     * @return the resolved action, defaulting to {@link UserListAction#SUBMIT} when the token was absent or
     * empty, which is what a bare request means
     * @throws ValidationException if the token is none of the three declared spellings
     */
    private static UserListAction resolveAction(final String token) {

        if (token == null || token.isEmpty()) {
            return UserListAction.SUBMIT;
        }
        for (final UserListAction candidate : UserListAction.values()) {
            if (candidate.token().equals(token)) {
                return candidate;
            }
        }
        throw ValidationException.invalidField(ACTION_PARAMETER,
                "action must be exactly 'SUBMIT', 'PAGE_BACKWARD' or 'PAGE_FORWARD';"
                        + " no alias, no padding and no other case is accepted. The same three tokens spell"
                        + " the same three navigations on every list operation of this API");
    }

    /**
     * Stamps the two members every error body carries, and returns the same instance for chaining.
     *
     * <p>Called by each {@code @ExceptionHandler} above as the last thing it does to the body, so a future
     * handler cannot omit the envelope by accident: the {@code return} statement reads
     * {@code body(withPublicEnvelope(problem, ...))}, and a handler written without it does not compile into
     * that shape.</p>
     *
     * <p><strong>This is the whole of the internal-disclosure posture.</strong> What the body carries is the
     * status, the title, a detail that is either a legacy screen literal or a fixed sentence, the error code
     * and the correlation identifier. What it never carries is the logical file or dataset name, the
     * input-output operation, the expanded file status, the colliding key, the record type, the record key,
     * the abend code or the batch return code. Every one of those is still emitted - at warning or error
     * level, on a log stream the caller cannot read - so no diagnostic capability is lost and nothing is
     * swallowed.</p>
     *
     * @param problem the body under construction; must not be null.
     * @param errorCode one of the {@code ERROR_CODE_*} constants.
     * @return {@code problem}, so the call can be inlined into the body argument
     */
    private static ProblemDetail withPublicEnvelope(final ProblemDetail problem, final String errorCode) {

        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        problem.setProperty(CORRELATION_ID_PROPERTY,
                correlationId == null || correlationId.isEmpty()
                        ? CORRELATION_ID_UNAVAILABLE : correlationId);

        return problem;
    }

    /**
     * The three actions the user list accepts, and the only three it accepts.
     *
     * <p>This is the controller-level action mapping that replaces the terminal attention identifier.
     * {@code app/cpy/CSSTRPFY.cpy} is a {@code PROCEDURE DIVISION} copybook whose paragraph
     * {@code YYYY-STORE-PFKEY.} at {@code :L17} evaluates {@code EIBAID} over a multi-arm
     * {@code EVALUATE TRUE} and stores the result into the action state of {@code app/cpy/CVCRD01Y.cpy}; it
     * declares no data of its own, so it has no entity, no data transfer object and no import. What replaces
     * it is an <em>explicit, enumerated request parameter</em> - not a generic attention-identifier string and
     * not a reimplemented function-key dispatcher. Several members of {@code app/cpy/CVCRD01Y.cpy} -
     * {@code CCARD-LAST-PROG}, {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG} and
     * {@code CCARD-FUNCTION} - are commented out in the source and have no counterpart at all, and
     * {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are supplied by the transaction monitor, are absent
     * from this repository, and are imported by nothing.</p>
     *
     * <p>Only the three arms {@code app/cbl/COUSR00C.cbl} dispatches on for this operation are modelled. Its
     * {@code EVALUATE EIBAID} at {@code :L122} also admits {@code DFHPF3} at {@code :L125-L127}, which exits
     * to the administrative menu, and under transformation rule 7 that is client-side URL navigation rather
     * than an operation of this resource group; its {@code WHEN OTHER} arm at {@code :L133-L136} reported an
     * invalid key, which becomes a rejected field.</p>
     */
    public enum UserListAction {

        /**
         * Return the first page, optionally starting from a named user. The transcription of the
         * {@code DFHENTER} arm at {@code app/cbl/COUSR00C.cbl:L123-L124}, which performs
         * {@code PROCESS-ENTER-KEY} at {@code :L225} - and that paragraph forces the page number to zero at
         * {@code :L227} and pages forward, so the answer is always page one whatever cursor was echoed. This
         * is the default, so a client that supplies no action at all gets the first page.
         */
        SUBMIT("SUBMIT", UserListService.AttentionIdentifier.ENTER),

        /**
         * Page backward. The transcription of the {@code DFHPF7} arm at
         * {@code app/cbl/COUSR00C.cbl:L128-L129}, which performs {@code PROCESS-PF7-KEY} at {@code :L238} and
         * repositions from the first identifier of the displayed page. On the first page the source instead
         * reports its top-of-page advisory at {@code :L250-L252} and returns the same page, and so does this
         * action.
         */
        PAGE_BACKWARD("PAGE_BACKWARD", UserListService.AttentionIdentifier.PF7),

        /**
         * Page forward. The transcription of the {@code DFHPF8} arm at
         * {@code app/cbl/COUSR00C.cbl:L130-L131}, which performs {@code PROCESS-PF8-KEY} at {@code :L260} and
         * repositions from the last identifier of the displayed page. With the next-page flag off the source
         * reports its bottom-of-page advisory at {@code :L272-L274} without reading anything, and so does
         * this action.
         */
        PAGE_FORWARD("PAGE_FORWARD", UserListService.AttentionIdentifier.PF8);

        /** The token a client presents for this action, matched exactly. */
        private final String requestToken;

        /** The attention identifier this action presents to the user-list service. */
        private final UserListService.AttentionIdentifier aid;

        /**
         * Binds a constant to its request token and to the attention identifier the service dispatches on.
         *
         * @param token the spelling a client presents, matched exactly with no alias and no case fold.
         * @param attentionIdentifier the service-side attention identifier this action stands for.
         */
        UserListAction(final String token, final UserListService.AttentionIdentifier attentionIdentifier) {
            this.requestToken = token;
            this.aid = attentionIdentifier;
        }

        /**
         * Returns the token a client presents for this action.
         *
         * @return the spelling; never null and never blank
         */
        public String token() {
            return this.requestToken;
        }

        /**
         * Returns the attention identifier the user-list service dispatches on for this action.
         *
         * @return the service-side attention identifier; never null
         */
        public UserListService.AttentionIdentifier attentionIdentifier() {
            return this.aid;
        }
    }
}
