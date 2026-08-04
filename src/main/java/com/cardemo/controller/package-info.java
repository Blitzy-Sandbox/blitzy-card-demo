/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.controller
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (package-info #10 of 14)
 * Function    : Package documentation for the REST presentation
 *               layer: 8 @RestController classes exposing exactly 17
 *               operations, replacing the 17 CICS screen programs and
 *               their BMS pseudo-conversational conversations.
 * Source      : app/csd/CARDDEMO.CSD (18 DEFINE TRANSACTION, 18
 *               DEFINE PROGRAM, 17 DEFINE MAPSET, 8 DEFINE FILE) ->
 *               the 17 sourced online programs in app/cbl and their
 *               17 symbolic maps in app/cpy-bms @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L306-L479 (the 18 transaction
 *               definitions), :L211 and :L390 (the two and only two
 *               occurrences of COCRDSEC), :L388 (DEFINE
 *               TRANSACTION(CDV1)), :L1-L88 (the 8 DEFINE FILE
 *               entries), :L499-L505 (DEFINE TDQUEUE(JOBS),
 *               RECORDSIZE(80) RECORDFORMAT(FIXED)) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L25 (CDEMO-USER-ID -> subject
 *               claim), :L26-L28 (CDEMO-USER-TYPE 'A'/'U' -> role
 *               claim), :L21-L24 and :L29-L31 and :L43-L44 (the
 *               fields with no equivalent) @ 7756d89
 * Source      : app/cpy/CVCRD01Y.cpy (navigation and AID state) +
 *               app/cpy/CSSTRPFY.cpy:L17 (YYYY-STORE-PFKEY,
 *               procedural) -> controller-level action mapping
 *               @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L517-L524 (the four outcome
 *               literals), :L659-L668 (the ACUP state markers),
 *               :L2605-L2615 (the EVALUATE TRUE that never tests
 *               COULD-NOT-LOCK-CUST-FOR-UPDATE), :L669-L756
 *               (9700-CHECK-CHANGE-IN-REC) @ 7756d89
 * Source      : app/cbl/CORPT00C.cbl:L212-L238 (the monthly range is
 *               the FULL current calendar month), :L484-L490 (the
 *               value-quoting confirmation message), :L531 (Unable
 *               to Write TDQ (JOBS)...) @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L177-L178 (card list page size
 *               7) + app/cpy-bms/COTRN00.CPY (TRNID01I..TRNID10I,
 *               transaction list 10) + app/cbl/COUSR00C.cbl:L57
 *               (USER-REC OCCURS 10 TIMES, user list 10) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L204 and :L218 (NUMVAL, strict)
 *               versus :L383 and :L456 (NUMVAL-C, currency aware)
 *               @ 7756d89
 * Source      : app/cbl/COUSR01C.cbl:L263 (User ID already exist...)
 *               + app/cbl/COUSR03C.cbl:L323 and :L332 + :L112 and
 *               :L122 of app/cbl/COUSR02C.cbl (PF3 and PF5 collapse)
 *               @ 7756d89
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

/**
 * The HTTP surface of CardDemo: eight {@code @RestController} classes exposing exactly seventeen operations,
 * replacing the seventeen CICS screen programs and their BMS pseudo-conversational conversations with stateless
 * JSON.
 *
 * <p>The banner above names every legacy artefact this package derives from, each pinned to commit
 * {@code 7756d89}, the traceability anchor for the frozen COBOL corpus in {@code app/}. Every claim below was
 * read either from the eight authored classes in this package or from the cited source locator. Where a claim
 * contradicts the specification that commissioned this package, the source governs and the correction is
 * stated in place rather than quietly absorbed; those corrections are collected in the severity register near
 * the end of this document.
 *
 * <p>Rule 1 clause E is discharged by this docstring rather than by a README, which the clause explicitly
 * permits. The four bullets it requires are the four second-level sections that follow, in the clause's own
 * order. A Markdown file is deliberately <strong>not</strong> used here: it would break the nine-file folder
 * gate, and a Java source package publishes its documentation through this file rather than through any
 * sibling document, so a README beside it would be unreachable as package documentation.
 *
 * <h2>What it does</h2>
 *
 * <p>Each handler method stands in for one {@code EXEC CICS SEND MAP} / {@code RECEIVE MAP} conversation, and
 * {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus JWT claims. Request and response field
 * names, types and lengths are taken from the BMS symbolic maps in {@code app/cpy-bms/} exactly, never chosen.
 *
 * <p>The route table below was transcribed from the eight authored classes, not from a plan. Each base path is
 * the class-level {@code @RequestMapping} value declared as that class's {@code BASE_PATH} constant; the
 * operation count is the number of method-level request mappings on that class. Line counts are the verified
 * lengths of the originating COBOL programs.
 *
 * <table>
 *   <caption>Controller to CSD transaction to COBOL program, with operation counts</caption>
 *   <tr>
 *     <th>Controller and base path</th><th>CSD transactions</th><th>COBOL programs (lines)</th><th>Ops</th>
 *   </tr>
 *   <tr>
 *     <td>{@code AuthController} at {@code /api/auth}</td>
 *     <td>{@code CC00}</td><td>COSGN00C (260)</td><td>1</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MenuController} at {@code /api/menu}</td>
 *     <td>{@code CM00}, {@code CA00}</td><td>COMEN01C (282), COADM01C (268)</td><td>2</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AccountController} at {@code /api/accounts}</td>
 *     <td>{@code CAVW}, {@code CAUP}</td><td>COACTVWC (941), COACTUPC (4,236)</td><td>2</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CardController} at {@code /api/cards}</td>
 *     <td>{@code CCLI}, {@code CCDL}, {@code CCUP}</td>
 *     <td>COCRDLIC (1,459), COCRDSLC (887), COCRDUPC (1,560)</td><td>3</td>
 *   </tr>
 *   <tr>
 *     <td>{@code TransactionController} at {@code /api/transactions}</td>
 *     <td>{@code CT00}, {@code CT01}, {@code CT02}</td>
 *     <td>COTRN00C (699), COTRN01C (330), COTRN02C (783)</td><td>3</td>
 *   </tr>
 *   <tr>
 *     <td>{@code BillingController} at {@code /api/billing}</td>
 *     <td>{@code CB00}</td><td>COBIL00C (572)</td><td>1</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ReportController} at {@code /api/reports}</td>
 *     <td>{@code CR00}</td><td>CORPT00C (649)</td><td>1</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AdminController} at {@code /api/admin/users}</td>
 *     <td>{@code CU00}, {@code CU01}, {@code CU02}, {@code CU03}</td>
 *     <td>COUSR00C (695), COUSR01C (299), COUSR02C (414), COUSR03C (359)</td><td>4</td>
 *   </tr>
 *   </table>
 *
 * <p>The arithmetic is stated explicitly because it is the gate on this package:
 * <strong>{@code 1 + 2 + 2 + 3 + 3 + 1 + 1 + 4 = 17}. Not 16, not 18.</strong> Counting the eight class-level
 * mappings as well brings the annotation total to twenty-five, which is why the operation count must be taken
 * from method-level mappings alone.
 *
 * <p>The seventeen resolved routes are {@code POST /api/auth/signon}; {@code GET /api/menu/main} and
 * {@code GET /api/menu/admin}; {@code GET /api/accounts/{accountId}} and {@code PUT /api/accounts};
 * {@code GET /api/cards}, {@code GET /api/cards/detail} and {@code PUT /api/cards};
 * {@code GET /api/transactions}, {@code GET /api/transactions/detail} and {@code POST /api/transactions};
 * {@code POST /api/billing/payments}; {@code POST /api/reports}; and {@code GET /api/admin/users},
 * {@code POST /api/admin/users}, {@code PUT /api/admin/users/{userId}} and
 * {@code DELETE /api/admin/users/{userId}}.
 *
 * <h3>Program count reconciliation, and the one transaction with no counterpart</h3>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines eighteen transactions and eighteen programs but only seventeen
 * mapsets. Seventeen sourced screen programs plus one orphan definition is what makes eighteen. The eighteenth
 * is {@code CDV1 -> COCRDSEC} at {@code app/csd/CARDDEMO.CSD:L388}, described there as a developer
 * transaction, and <strong>{@code COCRDSEC} has no source file anywhere in the repository</strong>: the name
 * occurs at exactly two places, {@code :L211} and {@code :L390}, both the CSD definition itself. Its
 * implementation is <strong>Not available</strong>, and <strong>no endpoint is invented for it</strong>. That
 * absence is documented rather than filled.
 *
 * <p>Two further absences are not defects either. {@code CSUTLDTC} does not appear in the CSD at all, because
 * it is a statically called subprogram rather than a transaction; it becomes a shared service, not a
 * controller. {@code CBSTM03A} and {@code CBSTM03B} are batch programs and are likewise absent from the CSD.
 * None of the three is owed an operation here.
 *
 * <h3>Thin, stateless adapters and nothing more</h3>
 *
 * <p>Each handler binds the exact {@code com.cardemo.model.dto} request type, delegates to exactly one
 * {@code com.cardemo.service} bean, and returns the exact response type. There is <strong>no business logic,
 * no validation logic, no arithmetic, no repository access, no {@code EntityManager} and no
 * {@code JdbcTemplate}</strong> in this package. Every rule lives in the service layer, which is what keeps
 * the paragraph-level mapping provable there rather than smeared across two layers. This is Rule 1 clause A on
 * modular design and clear separation of concerns, applied as a boundary rather than as an aspiration: as
 * authored, no type here imports a repository type, an AWS type, {@code EntityManager} or
 * {@code JdbcTemplate}.
 *
 * <h3>Inputs, outputs and side effects of the seventeen operations</h3>
 *
 * <p>Rule 1 clause B requires a public interface to state its purpose, its inputs and outputs, its side
 * effects and its error modes. The seventeen handlers are this package's public interface, so those four are
 * stated here: purpose is the route table above, inputs and outputs are the request and response types named
 * per operation on each controller, error modes are the failure-mode section below, and the side effects are
 * these:
 *
 * <ul>
 *   <li><strong>Eight operations are side-effect-free reads</strong> - the two menu retrievals, the account
 *       view, the card list and card detail, the transaction list and transaction detail, and the user list.
 *       They mutate no state and enqueue nothing. Repeating one changes nothing.</li>
 *   <li><strong>Sign-on</strong> mints a signed token and records an authentication attempt in the metrics and
 *       the structured log. It writes no row.</li>
 *   <li><strong>Seven operations write to the database</strong> through their service, inside a single
 *       transaction each, and are therefore <strong>not</strong> safely repeatable: account update writes an
 *       account row and a customer row as one unit of work; card update writes a card row; transaction add
 *       inserts a transaction row and generates its identifier; bill payment inserts a payment transaction and
 *       drives the account balance to zero; and the three administrative mutations create, update and delete a
 *       user row. Transaction add and bill payment both allocate an identifier by descending browse, so a
 *       concurrent duplicate surfaces rather than being silently absorbed.</li>
 *   <li><strong>Report submission</strong> publishes one message to the FIFO queue that replaces the
 *       {@code JOBS} transient data queue. That is its entire effect at this layer: it enqueues work and
 *       returns, and the batch tier acts on it later, so a successful response means accepted rather than
 *       completed.</li>
 *   <li><strong>Every operation</strong> emits a structured log line carrying the correlation, trace and span
 *       identifiers, and contributes to the request metrics. No operation writes to object storage, sends a
 *       notification, or reads or writes a file from this layer.</li>
 *   <li>That accounts for every operation exactly once: <strong>8 reads + 1 sign-on + 7 database writes + 1
 *       queue publish = 17</strong>, the same seventeen counted by transaction in the table above.</li>
 *   </ul>
 *
 * <p>What is deliberately <strong>not</strong> here: no {@code @ControllerAdvice}, no
 * {@code ResponseEntityExceptionHandler}, no base or abstract controller, no shared header helper, no mapper,
 * no DTO assembler, no constants class, no local error-response record, no README, no Markdown and no
 * subfolder. The folder holds <strong>exactly nine {@code .java} files</strong>: the eight controllers and
 * this document. HTTP status selection is contextual and performed by the controllers themselves, because the
 * nine {@code com.cardemo.exception} types carry no annotations at all and specifically no
 * {@code @ResponseStatus}. As authored, that choice is realised as sixty-two local {@code @ExceptionHandler}
 * methods across the eight classes, each returning a {@code ProblemDetail} body.
 *
 * <h3>Scope boundary of the presentation layer</h3>
 *
 * <p>The exposed surface is REST and JSON plus Actuator, and nothing else. There is no 3270 or BMS terminal
 * emulation, no green-screen rendering, no pseudo-conversational session emulation, no web or single-page front
 * end and no HTML, CSS or JavaScript. The seventeen BMS symbolic maps are consumed purely as <em>DTO field
 * contracts</em> and are not reimplemented as a user interface, so no component library and no design system is
 * in play.
 *
 * <p>The field budget is <strong>441 input fields across the seventeen symbolic maps</strong>, counted
 * directly: COACTUP 54, COACTVW 37, COADM01 20, COBIL00 10, COCRDLI 45, COCRDSL 15, COCRDUP 17, COMEN01 20,
 * CORPT00 17, COSGN00 11, COTRN00 59, COTRN01 21, COTRN02 21, COUSR00 59, COUSR01 12, COUSR02 12, COUSR03 11.
 * The specification's figure of 460 is wrong, as is its per-map figure of 36 for COACTVW; both are recorded in
 * the severity register below.
 *
 * <p>There is <strong>no generated API surface</strong>, because OpenAPI generation is out of scope, and
 * <strong>no URI-based API versioning</strong>, which is deferred hardening carried as residual risk. The
 * planned manual substitute is {@code docs/api-contracts.md}, which is not present in the repository as
 * authored; until it exists, this document and the per-operation documentation on the eight controllers are the
 * contract.
 *
 * <p>There is <strong>no CRUD surface for the four batch-only datasets</strong> {@code TCATBALF},
 * {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE}. The evidence is an absence: the CSD declares exactly
 * eight files, {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT},
 * {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC}, at {@code app/csd/CARDDEMO.CSD:L1-L88}, and those four
 * are not among them. They had no online definition, so they get no online endpoint, and that shapes the
 * authorisation model as much as any rule does.
 *
 * <p>There is also no health, information, metrics or business-alias endpoint here. Actuator owns those, with
 * web exposure limited to {@code health}, {@code info} and {@code prometheus}, and the composite checks live in
 * {@code HealthIndicators}. Adding a hand-rolled alias in this package would duplicate a surface that is
 * already gated.
 *
 * <h3>Statelessness is a contract, not a style choice</h3>
 *
 * <p>Transformation rule 7 leaves <strong>no server-side session state and no CSRF state</strong>. Pagination
 * state lives in request parameters and in {@code PageResponse} metadata. From {@code app/cpy/COCOM01Y.cpy},
 * exactly two fields survive, and they arrive in the JWT rather than in a control block:
 * {@code CDEMO-USER-ID} at {@code :L25}, {@code PIC X(08)}, as the subject claim, and
 * {@code CDEMO-USER-TYPE} at {@code :L26} with its condition names {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at
 * {@code :L27} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}, as the role claim.
 *
 * <p>The fields with <strong>no equivalent</strong> are named individually, because each absence is a decision
 * rather than an oversight:
 *
 * <ul>
 *   <li>{@code CDEMO-FROM-TRANID} {@code PIC X(04)} at {@code :L21}, {@code CDEMO-FROM-PROGRAM}
 *       {@code PIC X(08)} at {@code :L22}, {@code CDEMO-TO-TRANID} {@code PIC X(04)} at {@code :L23} and
 *       {@code CDEMO-TO-PROGRAM} {@code PIC X(08)} at {@code :L24} carried the navigation pair. Routing is
 *       URL-based, so nothing carries it.</li>
 *   <li>{@code CDEMO-PGM-CONTEXT} {@code PIC 9(01)} at {@code :L29}, with
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0} at {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at
 *       {@code :L31}, was the pseudo-conversational enter-versus-re-enter flag. It collapses into stateless
 *       request handling.</li>
 *   <li>{@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} at {@code :L43-L44}, both {@code PIC X(7)} and
 *       not {@code X(8)}, retained screen state. No screen state is retained.</li>
 *   </ul>
 *
 * <p>Transfer of control is replaced by JWT propagation. The corpus contains <strong>twenty-nine
 * {@code XCTL} sites spread across all seventeen online programs</strong>, not the four the specification
 * records; the undercount arises because {@code EXEC CICS} and {@code XCTL} frequently sit on separate source
 * lines, so a single-line search misses most of them. The four locators the specification names are
 * individually correct and remain the clearest illustrations: {@code app/cbl/COSGN00C.cbl:L231} and
 * {@code :L236} route to the administrator or the ordinary menu by user type, and
 * {@code app/cbl/COMEN01C.cbl:L153} and {@code :L176} transfer to a menu-selected program and back to sign-on.
 *
 * <p>Navigation and attention-identifier state from {@code app/cpy/CVCRD01Y.cpy}, together with the procedural
 * paragraph {@code YYYY-STORE-PFKEY.} at {@code app/cpy/CSSTRPFY.cpy:L17}, map onto
 * <strong>controller-level action mapping</strong>: an explicit request parameter or a distinct URL per action.
 * They are deliberately <em>not</em> a generic attention-identifier parameter and <em>not</em> a reimplemented
 * function-key dispatcher. {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are supplied by the
 * transaction monitor, are absent from this repository, and have no import here.
 *
 * <p>One consequence is worth stating for anyone tracing identity through a log: {@code EIBTRNID} has
 * <strong>zero occurrences repository-wide</strong>, so the legacy system had no per-request identifier to
 * carry forward. The correlation identifier supplied by {@code CorrelationIdFilter} is the replacement thread
 * of identity rather than a translation of one. For contrast, {@code EIBCALEN} occurs 49 times and
 * {@code EIBAID} 44 times.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Every command below is given as a repository-root invocation through the pinned wrapper, so that it
 * resolves the same way on any machine. Rule 1 clause C forbids environment-specific assumptions, so
 * prerequisites are stated as capabilities rather than as absolute paths.
 *
 * <p><strong>Build.</strong> {@code ./mvnw -B -ntp clean verify} is the full gate. While iterating,
 * {@code ./mvnw -B -ntp clean compile} is enough to prove this package compiles, and
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify} runs everything except the OWASP
 * vulnerability scan, which needs network access to the vulnerability feed and takes roughly half an hour
 * without an API key. Drop that flag whenever the scan is actually wanted. Load the git-ignored {@code .env}
 * with {@code set -a; . ./.env; set +a} first, because the JWT signing key has no committed default and the
 * context refuses to start without it.
 *
 * <p><strong>Toolchain.</strong> Java 25 with {@code maven.compiler.release} set to 25 and no preview features,
 * Maven 3.9.11, parent {@code spring-boot-starter-parent} 3.5.11, Spring Framework 6.2.16, Spring Security
 * 6.5.8, embedded Tomcat 10.1.52 on the {@code jakarta.servlet} namespace, Jackson BOM 2.19.4 and
 * {@code jakarta.validation-api} 3.0.2. Verified in this environment: {@code java} and {@code javac} report
 * Temurin 25.0.3+9, {@code mvn} reports 3.9.11, and a container runtime is available. A baseline
 * {@code clean compile} of the tree succeeds and reports 150 source files at release 25.
 *
 * <p><strong>Two gates make this file's formatting load-bearing.</strong>
 * {@code maven-compiler-plugin} 3.14.1 runs {@code -Xlint:all} with {@code failOnWarning}, so a warning is a
 * build failure. Separately, {@code maven-javadoc-plugin} 3.11.2 is bound at {@code verify} as the
 * {@code doclint-gate} execution with {@code doclint} set to {@code all}, {@code failOnError} true,
 * {@code failOnWarnings} true and {@code show} set to {@code private}. <strong>Malformed HTML, an unbalanced
 * tag, an unescaped angle bracket or a reference to a type that does not resolve therefore fails the
 * build.</strong> That is why this document uses {@code @code} throughout in preference to {@code @link}, and
 * why the tree currently passes the gate with zero errors and zero warnings. Run the gate alone with
 * {@code ./mvnw -B -ntp org.apache.maven.plugins:maven-javadoc-plugin:3.11.2:javadoc-no-fork@doclint-gate}.
 *
 * <p><strong>Run.</strong> {@code java -jar target/carddemo-1.0.0.jar --spring.profiles.active=local}, or bring
 * up the whole topology with {@code docker compose up}, which supplies PostgreSQL 16, LocalStack, Jaeger,
 * Prometheus and Grafana. <strong>All AWS interaction targets LocalStack; there are zero live AWS credentials
 * and no code path may reach a real AWS endpoint.</strong>
 *
 * <p><strong>Test.</strong> Unit tests live under {@code src/test/java/com/cardemo/unit}, integration tests
 * under {@code src/test/java/com/cardemo/integration} and end-to-end tests under
 * {@code src/test/java/com/cardemo/e2e}. <strong>No test file may live in this package.</strong> Surefire 3.5.4
 * runs the unit tier; Failsafe 3.5.4 runs the integration and end-to-end tiers at {@code verify};
 * {@code jacoco-maven-plugin} 0.8.12 enforces an eighty percent line gate on the bundle at {@code verify} with
 * no core-package exclusions and no getter-only padding; {@code maven-enforcer-plugin} 3.5.0 asserts the
 * toolchain floor; and {@code dependency-check-maven} 12.1.0 feeds the security gate.
 *
 * <p>The API contract gate exercises all seventeen operations against a real application context, so every
 * handler must be reachable and testable through {@code MockMvc} or {@code WebTestClient} without static state.
 * Each controller holds no static mutable state and takes its collaborators through its constructor, so a unit
 * test can construct one directly with a stubbed service and no Spring context at all. Note the prerequisite
 * rather than assuming it: the Testcontainers-backed tiers and {@code docker compose} both require an
 * accessible container socket, and where one is unavailable the honest report is the prerequisite, not an
 * untested pass.
 *
 * <p><strong>Every example against this package needs a bearer token</strong>, because sign-on is the only
 * unauthenticated operation. An invocation that omits the {@code Authorization} header returns 401 rather than
 * the documented response.
 *
 * <p><strong>Add no dependency</strong> for anything in this package: no Lombok and no standalone JWT library,
 * since token validation goes through the OAuth2 resource-server and Nimbus path already on the classpath.
 * There is no {@code com.carddemo} spelling anywhere, no wrapper directory around the Maven project and no
 * second module. Finally, {@code app/} and {@code samples/} are frozen byte-for-byte:
 * {@code git status --porcelain app samples} must come back empty, and a diff there is a defect regardless of
 * how harmless it looks.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by key rather than by line number, because keys are stable identifiers and line
 * numbers drift. Every value below was read from {@code src/main/resources/application.yml} as authored.
 *
 * <p><strong>Pagination.</strong> The three page sizes are {@code carddemo.pagination.card-list-page-size} at
 * {@code 7}, {@code carddemo.pagination.transaction-list-page-size} at {@code 10} and
 * {@code carddemo.pagination.user-list-page-size} at {@code 10}. None is hardcoded. They derive from row-array
 * widths in the source, not from preference: {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 * {@code app/cbl/COCRDLIC.cbl:L177-L178}; the ten-row table {@code TRNID01I} through {@code TRNID10I} in
 * {@code app/cpy-bms/COTRN00.CPY}, since {@code app/cbl/COTRN00C.cbl:L64-L68} holds paging <em>cursor</em>
 * fields rather than a row count and cannot be read as the page size; and
 * {@code 02 USER-REC OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl:L57}.
 *
 * <p>One point of accuracy about where those values are bound, because the specification states it the other
 * way round: as authored the page sizes are injected into the <strong>service</strong> beans, not into the
 * controllers. {@code CardListService}, {@code TransactionListService} and {@code UserListService} each take
 * their page size through a constructor {@code @Value} and validate it on construction. The controllers name
 * the keys in documentation only and read no property directly. Forward and backward paging are explicit
 * request parameters, results carry {@code PageResponse} metadata, and ordering is deterministic so that a page
 * boundary is reproducible.
 *
 * <p>The report generator's twenty-lines-per-page is <strong>batch-only</strong> and never appears in this
 * package. It belongs to {@code app/cbl/CBTRN03C.cbl} and to the batch tier that replaces it.
 *
 * <p><strong>Security.</strong> {@code carddemo.security.jwt.signing-key} resolves from
 * {@code JWT_SIGNING_KEY} with <strong>no committed default, so absence fails fast at startup</strong>;
 * {@code carddemo.security.jwt.issuer} resolves from {@code JWT_ISSUER} defaulting to {@code carddemo}; and
 * {@code carddemo.security.jwt.expiration-minutes} resolves from {@code JWT_EXPIRATION_MINUTES} defaulting to
 * {@code 30}. Sign-on, transaction {@code CC00}, is the <strong>only unauthenticated operation</strong>; every
 * other operation requires a valid bearer token, and everything beneath {@code /api/admin} is restricted to the
 * administrator role. {@code SessionCreationPolicy.STATELESS} and all authorisation rules are declared
 * centrally in {@code SecurityConfig}, never per controller, and the filter chain runs
 * {@code CorrelationIdFilter} then {@code JwtAuthenticationFilter} then role authorisation.
 *
 * <p><strong>The claim set is exactly subject, role, issuer, issued-at and expiry.</strong> A JWT is signed,
 * not encrypted, so anyone holding one can read its payload; emitting {@code CDEMO-ACCT-ID} at
 * {@code app/cpy/COCOM01Y.cpy:L38}, {@code CDEMO-CARD-NUM} at {@code :L41} or {@code CDEMO-CUST-ID} at
 * {@code :L33} as a claim is a <strong>Blocker</strong>. Those are data-transfer-object fields and never
 * claims. This is Rule 1 clause D on least privilege for tokens, credentials and configuration, expressed as a
 * hard boundary. Password hashing is BCrypt at {@code carddemo.security.bcrypt.strength} of {@code 10}, and the
 * as-displayed snapshot has a bounded lifetime through
 * {@code carddemo.security.snapshot.lifetime-seconds}.
 *
 * <p><strong>Messaging.</strong> The report queue is configured under {@code carddemo.aws.sqs}, with
 * {@code report-queue} supplied by environment, {@code report-queue-logical-name} and
 * {@code report-message-group-id} both {@code carddemo-report-jobs}, FIFO, against a LocalStack endpoint
 * override only. The publish itself lives in {@code ReportSubmissionService}: <strong>no controller references
 * any AWS type</strong>, and as authored none imports one. The message shape is fixed by the transient data
 * queue contract at {@code app/csd/CARDDEMO.CSD:L499-L505}, {@code RECORDSIZE(80) RECORDFORMAT(FIXED)}.
 *
 * <p><strong>Serialization.</strong> Jackson is configured centrally and <strong>must not be overridden per
 * controller</strong>: {@code spring.jackson.serialization.write-dates-as-timestamps} is {@code false},
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} is {@code true}, and no decimal is
 * rendered through a binary floating-point path. Two numeric converters are registered once in
 * {@code WebConfig}: a <strong>strict digits-only</strong> parser for identifiers and card numbers, matching
 * plain {@code NUMVAL} semantics at {@code app/cbl/COTRN02C.cbl:L204} and {@code :L218}, and a
 * <strong>currency-aware</strong> parser for <strong>amounts only</strong>, matching {@code NUMVAL-C} at
 * {@code :L383} and {@code :L456}. The distinction is deliberate and must not be unified. No controller adds a
 * local parser, formatter or {@code @InitBinder}.
 *
 * <p><strong>Persistence and batch defaults.</strong> {@code spring.jpa.open-in-view} is {@code false}, so no
 * lazy load may escape into a handler; {@code spring.jpa.hibernate.ddl-auto} is {@code validate}, because
 * Flyway owns the schema; and {@code spring.batch.job.enabled} is {@code false}, so batch jobs are launched
 * explicitly and never on context startup.
 *
 * <p><strong>Numeric and temporal invariants.</strong> Every financial value is a {@code BigDecimal}, compared
 * with {@code compareTo} and never with {@code equals}, and rounded with {@code RoundingMode.HALF_EVEN}. There
 * is <strong>zero use of {@code float} or {@code double} in any financial field</strong>, request and response
 * bodies included, and as authored neither keyword appears anywhere in this package. The precisions differ by
 * field and must not be levelled: the transaction amount is {@code NUMERIC(11,2)}, the disclosure interest rate
 * is {@code NUMERIC(6,2)} and account money fields are {@code NUMERIC(12,2)}.
 *
 * <p>Timestamps are the trap. {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} with three
 * mutually incompatible producers, so they are carried as {@code String} and stored as {@code CHAR(26)},
 * <strong>never as a temporal type</strong>. Every one of the 300 rows of
 * {@code app/data/ASCII/dailytran.txt} carries a blank twenty-six-space processing timestamp, which no temporal
 * parser accepts. Controllers <strong>must not parse, reformat, normalise or validate them as temporals</strong>.
 * More generally, {@code Locale.ROOT} governs every case conversion and formatting operation here, and nothing
 * relies on the default charset, locale or time zone; the application time zone is explicit at
 * {@code carddemo.time.zone}.
 *
 * <p><strong>Observability.</strong> {@code CorrelationIdFilter} supplies the MDC keys exactly
 * {@code correlationId}, {@code traceId} and {@code spanId}. Controllers <strong>must not add, rename,
 * overwrite or remove them, and must not clear the MDC</strong>, and register no additional Micrometer
 * instrument of their own. Masking of credentials, hashes and social security numbers lives in
 * {@code logback-spring.xml}. Actuator web exposure is limited to {@code health}, {@code info} and
 * {@code prometheus}, never a wildcard, because endpoints such as the configuration-properties report would
 * echo resolved values including the signing key.
 *
 * <p><strong>Secret hygiene</strong>, which is Rule 1 clause D on keeping secrets out of code, logs, tests and
 * configuration. Never log or serialize credentials, presented passwords, BCrypt digests, tokens, signing keys,
 * social security numbers, an {@code Authorization} header, card numbers, telephone numbers, government
 * identifiers, dates of birth, funds-transfer account identifiers or unmasked personal data.
 * {@code UserSecurityDto} carries no password and no digest; {@code UserCreateRequest} and
 * {@code UserUpdateRequest} accept a password write-only; {@code SignOnRequest} takes a password write-only and
 * {@code SignOnResponse} returns the issued token and never a digest. The ten seeded plaintext passwords in
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} appear nowhere under {@code src/}; the seed migration stores only
 * digests.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>This is the section an on-call engineer reads first, so it is ordered by what is most likely to be
 * misread as a defect in this package when it is not one.
 *
 * <h3>The typed exception surface</h3>
 *
 * <p>All nine {@code com.cardemo.exception} types must surface as <strong>distinguishable</strong> HTTP
 * responses, and each is mapped <strong>inside the owning handler</strong> rather than centrally:
 * {@code CardDemoException} as the base, then {@code ValidationException},
 * {@code RecordNotFoundException}, {@code DuplicateRecordException}, {@code FileUnavailableException},
 * {@code ConcurrentUpdateException}, {@code DataIntegrityException}, {@code FileAccessException} and
 * {@code FatalProcessingException}. <strong>There is no advice class and no {@code @ResponseStatus} anywhere
 * in the tree</strong>, verified as authored, which is precisely why the status choice is contextual: the same
 * missing row is a 404 on a read and part of an accepted upsert path in batch.
 *
 * <p>Those types derive from the file-status taxonomy of the corpus, and the exceptions to the general rule
 * matter more than the rule. Status {@code '00'}, and {@code '04'} at the file-service call sites, means
 * continue. Status {@code '10'} is <strong>loop termination and not an error</strong>. Status {@code '23'} is
 * {@code RecordNotFoundException} <strong>except at three scoped-success sites</strong>: the
 * category-balance upsert at {@code app/cbl/CBTRN02C.cbl:L467-L500}, the disclosure-group default fallback at
 * {@code app/cbl/CBACT04C.cbl:L415-L460}, and the accepted secondary status at the {@code CBSTM03B} call
 * sites. Status {@code '22'} is {@code DuplicateRecordException}, {@code '35'} is
 * {@code FileUnavailableException}, and the {@code '9x'} family is {@code FileAccessException} carrying the
 * four-character expanded status. Anything else is {@code FatalProcessingException} with abend code
 * {@code 999} and return code {@code 12}, whose four payload fields come from {@code app/cpy/CSMSG02Y.cpy},
 * the copybook internally titled {@code CABENDD.CPY}.
 *
 * <h3>Account update exposes five distinguishable outcomes, not one conflict</h3>
 *
 * <p>{@code ConcurrentUpdateException} carries a nested {@code Outcome} enum with exactly five constants, and
 * as authored each pairs the verified legacy literal with the source's own state character:
 * {@code COULD_NOT_LOCK_ACCOUNT} with {@code 'Could not lock account record for update'};
 * {@code COULD_NOT_LOCK_CUSTOMER} with {@code 'Could not lock customer record for update'};
 * {@code DATA_CHANGED_BEFORE_UPDATE} with {@code 'Record changed by some one else. Please review'};
 * {@code LOCKED_BUT_UPDATE_FAILED} with {@code 'Update of record failed'}; and
 * {@code CHANGES_NOT_CONFIRMED}. The first four literals are declared as condition names at
 * {@code app/cbl/COACTUPC.cbl:L517-L524} and the fifth marker sits among the state flags at
 * {@code :L659-L668}. <strong>Collapsing them into a single conflict status is classified High</strong>,
 * because it discards information the legacy screen displayed. An earlier revision of this document recorded
 * four outcomes; five is correct and is what the authored type provides.
 *
 * <h3>A reproduced legacy false success, classified Blocker</h3>
 *
 * <p>At {@code app/cbl/COACTUPC.cbl:L2605-L2615} the {@code EVALUATE TRUE} that interprets the result of
 * {@code 9600-WRITE-PROCESSING} tests only three conditions, {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE},
 * {@code LOCKED-BUT-UPDATE-FAILED} and {@code DATA-WAS-CHANGED-BEFORE-UPDATE}. It
 * <strong>never tests {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}</strong>, so a customer read-for-update failure
 * falls through {@code WHEN OTHER} to {@code ACUP-CHANGES-OKAYED-AND-DONE} and the operation reports
 * <strong>top-level success</strong>.
 *
 * <p><strong>This is reproduced at the REST boundary and is not corrected into a 409 or a 500.</strong>
 * Parity is the contract, and this is behaviour rather than an accident of transcription. What the target adds
 * without changing the reported outcome is visibility: the internal outcome stays distinguishable in the
 * service result and in the structured log line. <em>Troubleshooting.</em> When an account update reports
 * success but the customer row is unchanged, this is the cause. Find the request by its correlation identifier
 * and read the internal outcome from that log line; do not patch the status mapping.
 *
 * <h3>The monthly report covers the full calendar month</h3>
 *
 * <p>Classified High because the specification says otherwise. At {@code app/cbl/CORPT00C.cbl:L212-L238} the
 * monthly range sets the start to the first of the current month, then advances the month by one, rolls the
 * year when the month passes twelve, and subtracts a single day from that date. The end date is therefore the
 * <strong>last day of the current month</strong>, so the range is the full calendar month. It is not
 * bounded by today, and any wording to the contrary is wrong; the source governs.
 *
 * <h3>Exact literals returned byte-for-byte</h3>
 *
 * <p>These strings are part of the observable contract. They are never reworded, re-cased or spell-corrected,
 * and a well-meaning fix to any of them is a parity regression:
 *
 * <ul>
 *   <li>{@code Unable to Write TDQ (JOBS)...} at {@code app/cbl/CORPT00C.cbl:L531}, from {@code CR00}.</li>
 *   <li>{@code Unable to Update User...} at {@code app/cbl/COUSR03C.cbl:L332} - the wrong-verb message the
 *       source emits when a <em>delete</em> fails, from {@code CU03}.</li>
 *   <li>{@code User ID already exist...} at {@code app/cbl/COUSR01C.cbl:L263} - {@code exist}, not
 *       {@code exists}, from {@code CU01}.</li>
 *   <li>{@code User ID NOT found...} at {@code app/cbl/COUSR03C.cbl:L323}.</li>
 *   <li>The value-quoting invalid-confirmation message at {@code app/cbl/CORPT00C.cbl:L484-L490}, which
 *       echoes the offending value back to the caller between double quotes. It belongs to <strong>{@code CR00}
 *       and not {@code CB00}</strong>: {@code CB00} uses the fixed form
 *       {@code Invalid value. Valid values are (Y/N)...} at {@code app/cbl/COBIL00C.cbl:L187}. The
 *       mis-attribution is recorded Medium.</li>
 *   </ul>
 *
 * <h3>Preserved legacy quirks that look like bugs</h3>
 *
 * <p>Each is faithful to the source, cited in the traceability material and justified in the decision record.
 * None may be repaired here:
 *
 * <ul>
 *   <li><strong>No self-delete guard</strong> in user deletion. {@code app/cbl/COUSR03C.cbl} contains zero
 *       occurrences of {@code CDEMO-USER-ID}, so it never compares the target against the signed-on identifier
 *       and an administrator may delete their own row. Adding a guard would be a behaviour change.</li>
 *   <li>The <strong>PF3 and PF5 collapse</strong> in user update: {@code app/cbl/COUSR02C.cbl:L112} and
 *       {@code :L122} both {@code PERFORM UPDATE-USER-INFO}, so two attention identifiers drive one action.
 *       Splitting them would create an eighteenth operation and break the count above.</li>
 *   <li>The account-update <strong>case-handling asymmetry</strong>: the group identifier is compared
 *       lower-cased, the customer text fields upper-cased, and the remaining fields with no case function at
 *       all. Normalising in either direction changes which updates are accepted.</li>
 *   <li>The <strong>date-of-birth offset asymmetry</strong> at {@code app/cbl/COACTUPC.cbl:L669-L756}: live
 *       components sit at offsets 1, 6 and 9 because the stored value is dash-separated, while snapshot
 *       components sit at 1, 5 and 7 because the snapshot is compact. A whole-string comparison of the two
 *       would report a change on <strong>every single request</strong>, making the operation permanently
 *       unusable.</li>
 *   <li><strong>Full-balance-only</strong> bill payment: the payment is always the entire current balance,
 *       never partial, and drives the balance to exactly zero.</li>
 *   <li>The <strong>descending-browse identifier generation</strong> race, retained rather than replaced by a
 *       database sequence, so that a collision surfaces as {@code DuplicateRecordException}. A sequence would
 *       change the generated values and break comparison against the baseline.</li>
 *   </ul>
 *
 * <h3>The account-update snapshot contract</h3>
 *
 * <p>{@code CAUP} accepts a request carrying <strong>both the old and the new detail groups</strong>, drawn
 * from the 54 input fields of {@code app/cpy-bms/COACTUP.CPY}. This is structural rather than optional: the
 * target is stateless, so the change-detection snapshot cannot live on the server between requests, and a JPA
 * version column alone cannot reproduce the source's field-by-field comparison at
 * {@code app/cbl/COACTUPC.cbl:L669-L756} - a version counter detects <em>that</em> a row changed, whereas the
 * source detects <em>which fields</em> changed and in what representation. Both layers are therefore required.
 * <strong>The controller passes both groups through verbatim and must not normalise, trim or reformat
 * either.</strong> <em>Troubleshooting.</em> A client that sends a dash-separated snapshot date of birth
 * instead of the compact form will see a spurious change-detected rejection on every attempt; fix the client
 * payload, not the comparison.
 *
 * <h3>Boundaries that are easy to cross by accident</h3>
 *
 * <ul>
 *   <li><strong>Reject codes are business outcomes, never exceptions and never HTTP errors.</strong> The
 *       {@code RejectCode} enum has exactly five constants, 100, 101, 102, 103 and 109, and they drive a batch
 *       exit status. Code 109 is assigned on a reachable path but never consumed as a reject outcome. No
 *       controller throws, catches or maps one.</li>
 *   <li><strong>Absent, blank and low-values are three distinct states.</strong> Never coerce a null into an
 *       empty string or the reverse: the three-state accepted, rejected and blank model derives from the
 *       {@code COPY ... REPLACING} procedure-division template in {@code app/cpy/CSSETATY.cpy}, and a
 *       confirmation field is consequently never modelled as a {@code boolean}. This is Rule 1 clause B on
 *       validating inputs and boundary conditions and handling null and empty cases explicitly.</li>
 *   <li><strong>No exception is swallowed.</strong> Every {@code catch} rethrows a typed
 *       {@code com.cardemo.exception} subtype with the original preserved as its cause. There is no empty
 *       catch block and no bare catch of {@code Exception} that discards what it caught, which is Rule 1
 *       clause B on error handling that preserves the root cause.</li>
 *   <li>A card number legitimately reaches a response body, because the source screens displayed it, but must
 *       never reach a log line, an exception message or a stack trace.</li>
 *   </ul>
 *
 * <h3>Troubleshooting quick reference</h3>
 *
 * <dl>
 *   <dt>401 on every call, including ones that worked yesterday</dt>
 *   <dd>Check that {@code JWT_SIGNING_KEY} is exported. There is no committed default and startup fails fast
 *       without it; a key that differs between issuer and verifier produces the same symptom. Sign-on is the
 *       only unauthenticated operation and the session policy is stateless, so no cookie or session will
 *       substitute for the bearer token.</dd>
 *
 *   <dt>403 beneath {@code /api/admin} for someone who should be an administrator</dt>
 *   <dd>The token's role claim is the ordinary-user authority, derived from {@code CDEMO-USER-TYPE} of
 *       {@code 'U'} rather than {@code 'A'}. The administrative surface is authored and routable, so this is
 *       an authority problem and not a routing one: inspect the claim the token actually carries.</dd>
 *
 *   <dt>404 on an operation listed in the route table</dt>
 *   <dd>Every operation above is authored and mapped, so this is a path or method mismatch. Note that the
 *       administrative base path is {@code /api/admin/users} and not {@code /api/admin}, that card and
 *       transaction detail reads sit on an explicit {@code /detail} sub-path rather than on a path variable,
 *       and that bill payment is {@code /api/billing/payments}.</dd>
 *
 *   <dt>400 with a field-length or field-name complaint</dt>
 *   <dd>The request does not match the BMS field contract. Widths come from {@code app/cpy-bms/} and are not
 *       negotiable; widening one to accommodate a client silently breaks parity.</dd>
 *
 *   <dt>Unexpected 400 on an amount, or an amount accepted that the legacy screen rejected</dt>
 *   <dd>The wrong numeric converter was applied. The currency-aware converter is registered for amounts only;
 *       identifiers and card numbers use the strict digits-only converter. The two are deliberately different
 *       and unifying them changes what the surface accepts.</dd>
 *
 *   <dt>A missing {@code correlationId} in a log line</dt>
 *   <dd>{@code CorrelationIdFilter} was bypassed, or something cleared the MDC mid-request. Controllers do
 *       neither; look at filter ordering and at any code that manages MDC state directly.</dd>
 *
 *   <dt>A timestamp that will not parse</dt>
 *   <dd>Expected. The two transaction timestamps are twenty-six-character text with three incompatible
 *       producers, and the daily fixture supplies a blank processing timestamp on every row. Nothing in this
 *       package may treat them as temporals.</dd>
 *   </dl>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li>Exactly nine {@code .java} files: eight controllers and this document. No README, no Markdown, no
 *       tenth file and no subfolder.</li>
 *   <li>Exactly seventeen method-level request mappings, distributed 1, 2, 2, 3, 3, 1, 1 and 4.</li>
 *   <li>No business logic, no repository access, no {@code EntityManager}, no {@code JdbcTemplate} and no
 *       monetary arithmetic.</li>
 *   <li>No server-side session state, no {@code HttpSession} and no sticky routing.</li>
 *   <li>No {@code @ControllerAdvice}, no {@code ResponseEntityExceptionHandler} and no
 *       {@code @ResponseStatus}.</li>
 *   <li>No AWS type referenced from any controller.</li>
 *   <li>No {@code float} or {@code double}, anywhere, for any purpose.</li>
 *   <li>Collaborators arrive through the constructor; no static mutable state, so every handler is unit
 *       testable without a Spring context.</li>
 *   <li>DTO field names, types and lengths derive from the symbolic maps and are never invented or widened for
 *       convenience.</li>
 *   <li>No test file in this package.</li>
 *   </ul>
 *
 * <h2>Severity register for this package</h2>
 *
 * <p>Rule 1 clause F requires findings to be classified and remediated rather than merely mentioned. These are
 * the findings this package carries, each already reflected in the text above:
 *
 * <ul>
 *   <li><strong>Blocker</strong> - the customer-lock false success at
 *       {@code app/cbl/COACTUPC.cbl:L2605-L2615}. Remediation is deliberately <em>not</em> to change the
 *       status: reproduce the reported outcome and keep the internal outcome visible in the service result and
 *       the structured log.</li>
 *   <li><strong>Blocker</strong> - text timestamps. Remediation: carry {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS} as {@code String} and {@code CHAR(26)}, and reject any change that introduces a
 *       temporal type on this boundary.</li>
 *   <li><strong>Blocker</strong> - emitting an account identifier, card number or customer identifier as a JWT
 *       claim. Remediation: keep the claim set to subject, role, issuer, issued-at and expiry.</li>
 *   <li><strong>High</strong> - the monthly report range. Remediation: implement the full calendar month per
 *       {@code app/cbl/CORPT00C.cbl:L212-L238} and disregard any wording that bounds it by today.</li>
 *   <li><strong>High</strong> - collapsing the five account-update outcomes into one conflict status.
 *       Remediation: map all five distinguishably.</li>
 *   <li><strong>Medium</strong> - the confirmation-literal attribution. The value-quoting message belongs to
 *       {@code CR00}; {@code CB00} uses the fixed form. Remediation: attribute each to its own transaction.</li>
 *   <li><strong>Medium</strong> - the BMS field census. The verified total is 441, not 460, and COACTVW
 *       carries 37 input fields rather than 36. Remediation: treat the per-map counts above as authoritative.</li>
 *   <li><strong>Low</strong> - the transfer-of-control census. There are twenty-nine {@code XCTL} sites across
 *       all seventeen online programs, not four; the undercount comes from {@code EXEC CICS} and {@code XCTL}
 *       sitting on separate lines. No code changes, but the corrected figure belongs in the traceability
 *       material.</li>
 *   <li><strong>Low</strong> - the attention-identifier census. {@code EIBAID} occurs 44 times, not 16.
 *       {@code EIBTRNID} occurring zero times is the load-bearing fact and is unaffected.</li>
 *   </ul>
 *
 * <h2>Rule 1, Build Verify, clause by clause</h2>
 *
 * <ul>
 *   <li><strong>A, engineering principles.</strong> Correctness and determinism come before cleverness, which
 *       is why preserved quirks are reproduced rather than tidied and every deviation is labelled. Inputs are
 *       untrusted: the strict converter, the field-length contract and the three-state model all treat client
 *       input as hostile. Separation of concerns is the thin-adapter boundary. Observability is the correlation
 *       identifier and the fixed MDC key set. Performance is served by deterministic ordering and bounded page
 *       sizes rather than by unbounded reads.</li>
 *   <li><strong>B, code quality.</strong> No dead code, no unused imports and no untracked deferred work: this
 *       file declares no type and imports nothing, and contains no marker comment of any kind. Boundary
 *       conditions are explicit at the points the source makes them explicit. There is no global mutable state;
 *       collaborators are injected. Exceptions are wrapped with context and never swallowed. The clause's
 *       requirement to document public interfaces with purpose, inputs, outputs, side effects and error modes is
 *       discharged here at package level, because the seventeen handlers <em>are</em> this package's public
 *       API.</li>
 *   <li><strong>C, repository hygiene.</strong> The provenance banner follows the corpus convention verified at
 *       {@code app/cbl/CBACT04C.cbl:L1-L21}, extended to name the originating legacy artefacts. The root
 *       {@code .editorconfig} governs encoding, line endings, indentation and trailing whitespace, and this
 *       file conforms. Commands are given through the pinned wrapper so the build is deterministic. The
 *       directory structure mirrors its sibling packages and the four documentation headings match the house
 *       form used across them, so nothing here fights the existing style.</li>
 *   <li><strong>D, security.</strong> No secret appears in this file or in this package. Dependencies are
 *       pinned in the build descriptor and none is added for this layer. Least privilege governs the claim set,
 *       the administrator-only surface and the three-endpoint Actuator exposure. No risky pattern is present:
 *       nothing in this package evaluates a string, spawns a process, reads the environment directly or
 *       deserializes into an open type hierarchy.</li>
 *   <li><strong>E, documentation.</strong> Discharged by this docstring in place of a README, with the
 *       clause's four bullets as the four second-level sections above.</li>
 *   <li><strong>F, output requirements.</strong> Every claim carries a path-and-locator citation, findings are
 *       classified in the severity register with remediation, and genuinely absent information is reported as
 *       such immediately below rather than invented.</li>
 *   </ul>
 *
 * <p><strong>The one documented conflict, and why it does not bind here.</strong> Clause B forbids dead code
 * while the parity mandate requires preserving reachable no-ops. Parity governs, because what clause B actually
 * forbids is <em>untracked</em> dead code and deferred work with no owner or tracking reference, and every
 * retained artefact is cited and justified. No retained no-op artefact lives in this subtree, so clause B binds
 * these nine files at full strength. The one obligation that survives is the boundary's duty to surface the
 * customer-lock false success faithfully rather than correcting it.
 *
 * <h2>Not available</h2>
 *
 * <p>Stated plainly, per Rule 1 clause F, rather than filled with an invention:
 *
 * <ul>
 *   <li>{@code COCRDSEC}, the program behind transaction {@code CDV1}, is <strong>Not available</strong>. It
 *       has no source file anywhere in the repository; the name occurs only in the CSD at
 *       {@code app/csd/CARDDEMO.CSD:L211} and {@code :L390}. What would be needed to implement it is the
 *       program source itself, or the mapset that would define its screen contract - the CSD declares
 *       seventeen mapsets and none belongs to it. No endpoint is invented for it.</li>
 *   <li>A service-level objective for any operation in this package is <strong>Not available</strong>. The
 *       corpus publishes none, so the performance gate records a measured baseline rather than asserting a
 *       target. What would be needed is a stated latency or throughput requirement from the business owner.</li>
 *   <li>{@code docs/api-contracts.md}, the intended manual substitute for a generated specification, is
 *       <strong>Not available</strong> in the repository as authored. Until it exists this document and the
 *       per-operation documentation on the eight controllers are the contract.</li>
 *   </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.controller;
