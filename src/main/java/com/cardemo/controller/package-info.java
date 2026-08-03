/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.controller
 * Application : CardDemo
 * Type        : Java package documentation (REST presentation layer)
 * Function    : Documents the com.cardemo.controller package, which holds
 *               the @RestController classes that replace the CICS
 *               pseudo-conversational screen programs. Each HTTP
 *               operation stands in for one EXEC CICS SEND MAP /
 *               RECEIVE MAP conversation, with field names, types and
 *               lengths taken from the BMS symbolic maps exactly.
 * Source      : app/csd/CARDDEMO.CSD (18 DEFINE TRANSACTION, of which 17
 *                 have both a source program and a mapset)
 *               + app/cpy-bms/** (17 symbolic maps, 441 input fields)
 *               + app/cpy/COCOM01Y.cpy (COMMAREA the token replaces)
 *               + app/cpy/CVCRD01Y.cpy (navigation and AID state)
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
 * The HTTP surface of CardDemo: {@code @RestController} classes that replace the CICS screen conversations of
 * the frozen corpus with stateless JSON operations.
 *
 * <h2>What it does</h2>
 *
 * <p>Each controller method stands in for one {@code EXEC CICS SEND MAP} / {@code RECEIVE MAP} conversation.
 * The controllers are deliberately thin: they bind and validate the request, delegate to exactly one service
 * in {@code com.cardemo.service}, and map the outcome to a status code. No controller contains business
 * logic, reads a repository, or performs arithmetic on a monetary value.
 *
 * <p><strong>There is no user interface here.</strong> No 3270 emulation, no green-screen rendering, no
 * pseudo-conversational session, no HTML and no single-page front end. The seventeen BMS symbolic maps are
 * consumed purely as <em>DTO field contracts</em> - all 441 input fields - which is why request and response
 * field names, types and lengths are taken from {@code app/cpy-bms/**} exactly rather than chosen.
 *
 * <h2>Statelessness is a contract, not a style choice</h2>
 *
 * <p>The COMMAREA has no server-side successor. {@code CDEMO-USER-ID} becomes the token subject claim and
 * {@code CDEMO-USER-TYPE} becomes the role claim; pagination state moves into request parameters and response
 * metadata. Four groups of COMMAREA fields have <strong>no equivalent at all</strong> and must not be
 * reintroduced: the from/to transaction and program identifiers, because routing is URL-based; the
 * program-context enter-versus-re-enter flag, because request handling is stateless; and the last-map and
 * last-mapset fields, because no screen state is retained.
 *
 * <h2>Operations present today</h2>
 *
 * <p>Six controllers expose <strong>twelve</strong> HTTP operations:
 *
 * <ul>
 *   <li>{@link com.cardemo.controller.AccountController} at {@code /api/accounts} - CSD {@code CAVW} and
 *       {@code CAUP}. A {@code GET} by account identifier, and a {@code PUT} whose body carries
 *       <strong>both</strong> the old and new detail groups, because a stateless request cannot otherwise
 *       reproduce the source's snapshot comparison.</li>
 *   <li>{@link com.cardemo.controller.CardController} at {@code /api/cards} - CSD {@code CCLI},
 *       {@code CCDL}, {@code CCUP}. A paged list at page size 7, a detail read and an update.</li>
 *   <li>{@link com.cardemo.controller.TransactionController} at {@code /api/transactions} - CSD
 *       {@code CT00}, {@code CT01}, {@code CT02}. A paged list at page size 10, a detail read and an
 *       add.</li>
 *   <li>{@link com.cardemo.controller.BillingController} at {@code /api/billing} - CSD {@code CB00}. A
 *       payment operation that pays the full balance.</li>
 *   <li>{@link com.cardemo.controller.MenuController} at {@code /api/menu} - CSD {@code CM00} and
 *       {@code CA00}. The main and admin option tables; option dispatch is replaced by URL navigation.</li>
 *   <li>{@link com.cardemo.controller.ReportController} at {@code /api/reports} - CSD {@code CR00}. Report
 *       submission, publishing to the FIFO queue that replaces the {@code JOBS} transient data queue.</li>
 *   </ul>
 *
 * <h2>Current contents versus the target set</h2>
 *
 * <p>The Agent Action Plan specifies <strong>8</strong> controllers exposing <strong>17</strong> operations.
 * <strong>Six controllers and twelve operations exist today.</strong> Two controllers carrying the remaining
 * five operations are <strong>planned and not yet authored</strong>:
 *
 * <ul>
 *   <li>{@code AuthController} - <strong>Not available.</strong> Planned for CSD {@code CC00} from
 *       {@code app/cbl/COSGN00C.cbl}, contributing <strong>one</strong> operation: sign-on, which is the only
 *       unauthenticated operation in the whole surface. The service it will delegate to,
 *       {@code AuthenticationService}, is already authored.</li>
 *   <li>{@code AdminController} - <strong>Not available.</strong> Planned at {@code /api/admin/*} for CSD
 *       {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}, contributing <strong>four</strong>
 *       operations restricted to the administrator role. All four of the services it will delegate to are
 *       already authored, {@code UserDeleteService} among them, so the controller is the only missing part.</li>
 *   </ul>
 *
 * <p>The reconciliation is therefore <strong>12 + 1 + 4 = 17</strong>. Volatile counts are not restated
 * elsewhere in this documentation set; the authoritative dated inventory is section 0.4.5.1 of
 * {@code docs/technical-specifications.md}.
 *
 * <h2>One CSD transaction has no Java counterpart, deliberately</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines 18 transactions but only 17 mapsets. The eighteenth is
 * {@code CDV1 -> COCRDSEC}, and <strong>{@code COCRDSEC} has no source file anywhere in the
 * repository</strong> - the only occurrence of the name is the CSD definition itself. It is a dangling legacy
 * definition with nothing to translate, so no endpoint is invented for it. That absence is documented rather
 * than filled.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B -ntp clean compile}; unit tests with {@code ./mvnw -B -ntp test}; the full
 * gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. That flag skips only the OWASP
 * vulnerability scan, which needs network access to the vulnerability feed; drop it when the scan is wanted.
 * Compilation runs {@code -Xlint:all -Werror} with {@code failOnWarning} at release 25, so a raw type,
 * unchecked cast or dangling documentation comment here fails the build; an unused import does <em>not</em>,
 * because {@code javac} 25 publishes no {@code unused} lint key, and malformed Javadoc does not either,
 * because no Javadoc plugin is bound in {@code pom.xml} - doclint is a separate explicit gate.
 *
 * <p>Prerequisites are capabilities rather than paths: a JDK 25 toolchain on {@code PATH} with
 * {@code JAVA_HOME} set, and Maven from the pinned repository wrapper. Load the git-ignored {@code .env} with
 * {@code set -a; . ./.env; set +a} before invoking Maven.
 *
 * <p>Run the application under the {@code local} profile against the Compose topology, which supplies
 * PostgreSQL and the LocalStack endpoint. Unit tests for these classes belong in
 * {@code src/test/java/com/cardemo/unit} and drive them through {@code MockMvc} with a stubbed service; the
 * end-to-end contract checks belong in {@code src/test/java/com/cardemo/e2e} and exercise the operations
 * against a real application context. Because every controller holds no static mutable state and takes its
 * one collaborator through its constructor, both tiers can construct one directly without a context.
 *
 * <p><strong>Every {@code curl} example in this package requires a bearer token</strong>, since sign-on is
 * the only unauthenticated operation. An example that omits {@code -H "Authorization: Bearer $TOKEN"} will
 * return 401 rather than the documented response. Where an example is shown for an operation whose controller
 * is not yet authored, it is labelled as target-only.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by key, never by line number in {@code application.yml}, because keys are stable
 * identifiers and line numbers drift.
 *
 * <ul>
 *   <li>{@code carddemo.pagination.card-list-page-size} is {@code 7}. The transaction and user list page
 *       sizes are 10. These come from the row-array widths of the source programs, not from preference.</li>
 *   <li>{@code carddemo.security.jwt.signing-key} - no default, bound from {@code JWT_SIGNING_KEY}. Every
 *       operation except sign-on requires a valid bearer token.</li>
 *   <li>{@code server.servlet.context-path} is not set, so the {@code /api} prefix in each
 *       {@code BASE_PATH} constant is the whole prefix.</li>
 *   <li>URI-based API versioning and generated OpenAPI documentation are explicitly <strong>out of
 *       scope</strong> and recorded as residual risk. The planned manual substitute is
 *       {@code docs/api-contracts.md}, which has not been authored yet; until it exists, the route table
 *       and per-operation documentation in this package and its controllers are the contract.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>401 on every request</dt>
 *   <dd>No bearer token, an expired token, or a signing key mismatch between issuer and verifier. Sign-on is
 *       the only unauthenticated operation and the session policy is {@code STATELESS}, so no cookie or
 *       session will substitute.</dd>
 *
 *   <dt>403 on an administrative operation for a user who should be an administrator</dt>
 *   <dd>The token's role claim does not carry the authority derived from {@code CDEMO-USER-TYPE} {@code 'A'}.
 *       Note that the administrative surface is planned rather than authored, so a 404 here is expected
 *       today.</dd>
 *
 *   <dt>404 on an operation documented above</dt>
 *   <dd>Check whether the controller is one of the two still planned. {@code AuthController} and
 *       {@code AdminController} do not exist yet, so sign-on and the four administrative operations are not
 *       routable.</dd>
 *
 *   <dt>400 with a field-length or field-name complaint</dt>
 *   <dd>The request does not match the BMS field contract. Widths come from {@code app/cpy-bms/**} and are
 *       not negotiable - widening one to accept a client's payload breaks parity silently.</dd>
 *
 *   <dt>An amount is accepted that the legacy screen would have rejected, or vice versa</dt>
 *   <dd>The wrong numeric parser was used. Identifiers and card numbers use a strict digits-only parser;
 *       amounts use a currency-tolerant parser that accepts symbols and thousands separators. The two are
 *       deliberately different and must not be unified.</dd>
 *
 *   <dt>An account update returns a generic 409 for every distinct failure</dt>
 *   <dd>The source distinguishes four outcomes - account lock failure, customer lock failure, data changed
 *       before update, and locked-but-update-failed. Collapsing them into one status loses information the
 *       legacy screen displayed; each must map to a distinguishable response.</dd>
 *
 *   <dt>A card number appears in a log line or an exception message</dt>
 *   <dd>A defect. Card numbers legitimately reach response payloads, because the source screens display
 *       them, but must never reach a log, an exception message or a stack trace.</dd>
 *   </dl>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li>No business logic, no repository access and no monetary arithmetic in this package.</li>
 *   <li>No server-side session state, no {@code HttpSession}, no sticky routing.</li>
 *   <li>One service collaborator per controller, taken through the constructor.</li>
 *   <li>DTO field names, types and lengths derive from the symbolic maps; they are never invented or
 *       widened for convenience.</li>
 *   <li>No controller may return an unmasked card number in anything other than a response payload the
 *       source screen also displayed.</li>
 *   </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.controller;
