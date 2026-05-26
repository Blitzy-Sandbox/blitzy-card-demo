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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * User list service (admin-only) &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR00C.cbl} (CICS transaction id {@code CU00}).
 *
 * <p>This service produces a paginated, admin-facing list of the rows in the
 * {@code USRSEC} dataset. In the COBOL source the program performs a keyed
 * CICS browse on the {@code USRSEC} VSAM KSDS to render 10 user rows at a
 * time on the {@code COUSR0A} BMS map, paged forward and backward via the
 * {@code DFHPF8} and {@code DFHPF7} AID keys respectively. In the Java
 * target the equivalent semantics are achieved through Spring Data
 * {@link Pageable} pagination plus a derived GTEQ-ordered query on
 * {@link UserSecurityRepository}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR00C.cbl} (CICS TRANID
 *       {@code 'CU00'}, file {@code 'USRSEC'}; AAP &sect;0.4.1
 *       online programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR00.bms} (mapset
 *       {@code COUSR00}, map {@code COUSR0A}, 10-row user table &mdash;
 *       see {@link UserListDto}).</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}
 *       (KEYS(8 0), RECORDSIZE(80 80)) &mdash; replaced by the
 *       PostgreSQL {@code user_security} table created in Flyway
 *       migration {@code V010__create_user_security.sql}.</li>
 *   <li><b>Repository:</b> {@link UserSecurityRepository} &mdash;
 *       replaces the CICS {@code STARTBR}/{@code READNEXT}/
 *       {@code READPREV}/{@code ENDBR} browse verbs.</li>
 *   <li><b>Page size 10:</b> verbatim transcription of the COBOL
 *       {@code 02 USER-REC OCCURS 10 TIMES} working-storage array
 *       declared at {@code COUSR00C.cbl}:L56&ndash;L64 (AAP &sect;0.7.1
 *       Minimal Change Clause: 10 is a literal carry-over, not an
 *       arbitrary REST convention).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COUSR00C.cbl &harr; UserListService.listUsers(...)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code STARTBR-USER-SEC-FILE} (GTEQ positioning)</td>
 *       <td>{@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(...)}
 *       (or {@code findAll(...)} for the unfiltered case)</td></tr>
 *   <tr><td>{@code READNEXT-USER-SEC-FILE} (ascending traversal)</td>
 *       <td>{@code Page.getContent()} iteration in ascending
 *       {@code secUsrId} order &mdash; enforced by both the
 *       {@code OrderBySecUsrIdAsc} method-name segment and the
 *       {@link Sort#by(String...)} argument on {@link PageRequest}</td></tr>
 *   <tr><td>{@code POPULATE-USER-DATA}</td>
 *       <td>Per-row projection of the {@link UserSecurity} entity to a
 *       {@link UserListDto.UserRow} (PII discipline: password hash
 *       intentionally omitted)</td></tr>
 *   <tr><td>{@code INITIALIZE-USER-DATA} (BMS field blanking)</td>
 *       <td>Not required &mdash; Java {@code List} carries exactly the
 *       rows returned and never participates in a fixed-width 10-slot
 *       array</td></tr>
 *   <tr><td>{@code ENDBR-USER-SEC-FILE}</td>
 *       <td>JPA transaction commits at method exit; no explicit
 *       cursor close required (Spring Data manages the cursor)</td></tr>
 *   <tr><td>{@code PROCESS-PF7-KEY} (page backward)</td>
 *       <td>Controller decrements {@code page} and re-invokes
 *       {@link #listUsers(String, int)}</td></tr>
 *   <tr><td>{@code PROCESS-PF8-KEY} (page forward)</td>
 *       <td>Controller increments {@code page} and re-invokes
 *       {@link #listUsers(String, int)}</td></tr>
 * </table>
 *
 * <h2>Security &amp; PCI-DSS (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 * <ul>
 *   <li>{@code SEC-USR-PWD} (the BCrypt hash) is <b>NEVER</b> read,
 *       returned, or logged by this service. The
 *       {@link UserListDto.UserRow} projection deliberately excludes
 *       the password field; reading
 *       {@link UserSecurity#getSecUsrPwd()} is not invoked anywhere
 *       in this class.</li>
 *   <li>The class-level {@link Logger} emits only the search term
 *       and page number &mdash; never raw user records, names, or
 *       credentials.</li>
 *   <li>Admin-only access is enforced one layer up at the controller
 *       via {@code @PreAuthorize("hasRole('ADMIN')")} on
 *       {@code UserAdminController.listUsers(...)} (per AAP
 *       &sect;0.3.4).</li>
 * </ul>
 *
 * <h2>Transactional semantics (AAP &sect;0.7.1)</h2>
 *
 * <p>The single public method {@link #listUsers(String, int)} is annotated
 * {@code @Transactional(readOnly = true)} so that the paged JPA reads run
 * inside a read-only RDS PostgreSQL transaction with consistent read
 * isolation (default {@code READ_COMMITTED}). This matches the implicit
 * CICS transaction context active during the
 * {@code STARTBR}/{@code READNEXT}/{@code ENDBR} browse sequence in
 * {@code COUSR00C.cbl}. The {@code readOnly = true} flag allows Hibernate
 * to skip dirty-checking on the returned entities and lets the JDBC
 * driver hint the connection pool/PostgreSQL planner about the
 * read-only nature of the workload.</p>
 *
 * <h2>Stateless service (no CICS COMMAREA carry-over)</h2>
 *
 * <p>The COBOL program maintains the &ldquo;first&rdquo; and
 * &ldquo;last&rdquo; user-ID of the current page in
 * {@code CDEMO-CU00-USRID-FIRST} / {@code CDEMO-CU00-USRID-LAST}
 * within the COMMAREA so that the next pseudo-conversation invocation
 * can resume the browse at the correct position. In the Java target
 * this is replaced by stateless REST &mdash; the controller passes the
 * current page index back to the client in
 * {@link UserListDto#page()}, and the client supplies the next
 * (incremented / decremented) page index on the subsequent request.
 * The service itself holds no per-user state between invocations.</p>
 *
 * <h2>Out-of-scope COBOL behavior</h2>
 *
 * <p>The following COBOL paragraphs in {@code COUSR00C.cbl} relate to
 * concerns that live above the service layer in the Java target and are
 * therefore NOT implemented here:</p>
 * <ul>
 *   <li>{@code PROCESS-ENTER-KEY} &mdash; per-row {@code U}/{@code D}
 *       selection that XCTLs to {@code COUSR02C} (update) or
 *       {@code COUSR03C} (delete). In the REST target the client invokes
 *       {@code UserUpdateService} / {@code UserDeleteService}
 *       endpoints directly.</li>
 *   <li>{@code SEND-USRLST-SCREEN} / {@code RECEIVE-USRLST-SCREEN}
 *       &mdash; BMS terminal I/O. Replaced by JSON serialisation
 *       (Jackson) of {@link UserListDto} at the controller layer.</li>
 *   <li>{@code POPULATE-HEADER-INFO} &mdash; screen title/date/time
 *       rendering. Headers are an HTTP/UI concern; this service emits
 *       only the data payload.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} (XCTL back to {@code COSGN00C} or
 *       {@code COADM01C}) &mdash; replaced by JWT/session navigation in
 *       Spring Security; not the service layer's concern.</li>
 * </ul>
 *
 * @see UserSecurity
 *      JPA entity mapped to the {@code user_security} table
 * @see UserSecurityRepository
 *      Spring Data JPA repository providing
 *      {@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(...)} and
 *      {@code findAll(Pageable)}
 * @see UserListDto
 *      Paged response DTO matching the {@code COUSR0A} BMS map
 *      contract (10 rows + paging metadata; no password)
 */
@Service
public class UserListService {

    // -------------------------------------------------------------------------
    // Class-level logger
    //
    // SLF4J facade routed through Logback + logstash-logback-encoder to
    // CloudWatch Logs per AAP §0.6.6 observability. PII discipline (AAP §0.7.1):
    // NEVER log raw user records, names, passwords, or BCrypt hashes — only
    // the (already non-PII) page index and the optional uppercased search
    // term that the client itself supplied.
    // -------------------------------------------------------------------------
    private static final Logger LOG = LoggerFactory.getLogger(UserListService.class);

    // -------------------------------------------------------------------------
    // Page size — verbatim COBOL transcription
    //
    // The COBOL program declares its in-memory row buffer as
    //     01 WS-USER-DATA.
    //       02 USER-REC OCCURS 10 TIMES.   (COUSR00C.cbl L56-L57)
    // and the BMS map COUSR0A is drawn with exactly 10 row positions
    // (USRID01I…USRID10I). The Java target preserves the 10-row page-size
    // contract VERBATIM per AAP §0.7.1 Minimal Change Clause: 10 is a
    // literal carry-over, not an arbitrary REST convention.
    // -------------------------------------------------------------------------
    /**
     * Fixed page size matching the COBOL {@code USER-REC OCCURS 10 TIMES}
     * working-storage array in {@code app/cbl/COUSR00C.cbl}:L56&ndash;L64 and
     * the 10-row {@code COUSR0A} BMS map in {@code app/bms/COUSR00.bms}.
     *
     * <p>The constant is {@code public} so callers (in particular
     * {@code UserAdminController}) can echo it back to clients as the
     * authoritative page-size value, and so test fixtures can rely on it
     * to compute expected page counts. Modifying this value would break
     * behavioural parity with the COBOL source and is forbidden under
     * AAP &sect;0.7.1 (Minimal Change Clause).</p>
     */
    public static final int PAGE_SIZE = 10; // COBOL: COUSR00C:USER-REC OCCURS 10

    // -------------------------------------------------------------------------
    // Collaborators (constructor-injected)
    //
    // AAP §0.7.1 / §0.3.3: constructor injection only. No @Autowired field
    // injection (it bypasses immutability and complicates testing). The
    // repository is the sole runtime dependency.
    // -------------------------------------------------------------------------

    /**
     * Spring Data JPA repository for the {@link UserSecurity} entity.
     * Provides the GTEQ-ordered derived query that replaces the CICS
     * {@code STARTBR ... GTEQ ... READNEXT} browse loop in
     * {@code app/cbl/COUSR00C.cbl}, plus {@code findAll(Pageable)} for
     * the unfiltered case.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructor for Spring's IoC container.
     *
     * <p>Constructor injection (rather than {@code @Autowired} field
     * injection) is mandated by AAP &sect;0.7.1 (&ldquo;Dependency
     * injection for loose coupling&rdquo;) because it:
     * <ul>
     *   <li>permits the {@code userSecurityRepository} field to be
     *       {@code final}, guaranteeing immutability after
     *       construction;</li>
     *   <li>makes the dependency graph explicit at compile time (no
     *       hidden runtime reflection requirements); and</li>
     *   <li>enables trivial unit testing with hand-rolled stubs or
     *       Mockito mocks without bringing up a Spring context
     *       (AAP &sect;0.7.2 testing approach).</li>
     * </ul>
     *
     * @param userSecurityRepository the JPA repository for the
     *                               {@link UserSecurity} aggregate;
     *                               must not be {@code null}
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns one page of users from the {@code user_security} table,
     * optionally narrowed by a starting user-ID filter.
     *
     * <p>This is the Java target for the entire COBOL paging cascade in
     * {@code app/cbl/COUSR00C.cbl}: {@code PROCESS-ENTER-KEY} (initial
     * load), {@code PROCESS-PF8-KEY} (page forward), and
     * {@code PROCESS-PF7-KEY} (page backward) all funnel into this
     * single method &mdash; PF-key dispatch is now a controller-layer
     * concern (the controller increments or decrements the
     * {@code page} argument).</p>
     *
     * <h4>Algorithm (preserves COBOL browse semantics)</h4>
     * <ol>
     *   <li><b>Normalise the page index</b> with {@code Math.max(0, page)}.
     *       Spring Data {@link PageRequest} requires a non-negative
     *       page; a negative value from a caller is silently clamped
     *       to {@code 0} (matching the COBOL guard
     *       {@code IF CDEMO-CU00-PAGE-NUM > 1 ... ELSE
     *       MOVE 'You are already at the top of the page...'} in
     *       {@code PROCESS-PF7-KEY}: the first page is always the
     *       lower bound).</li>
     *   <li><b>Build the {@link Pageable}</b> with
     *       {@link Sort#ascending()} on the entity property
     *       {@code "secUsrId"} so that the returned rows are in
     *       ascending key order &mdash; matching the VSAM KSDS
     *       physical ordering on {@code SEC-USR-ID} and the CICS
     *       {@code READNEXT} traversal direction.</li>
     *   <li><b>Normalise the search term</b>: a {@code null} or
     *       blank value disables the GTEQ filter; otherwise the term
     *       is {@link String#trim() trimmed} and uppercased with
     *       an explicit {@link Locale#US US locale} to match the
     *       uppercase keying of {@code USRSEC} records (the
     *       {@code SEC-USR-ID} primary key is always uppercase &mdash;
     *       see {@code COSGN00C.cbl}:L132&ndash;L135 where the COBOL
     *       sign-on routine applies {@code FUNCTION UPPER-CASE}).
     *       An explicit {@code Locale.US} avoids locale-sensitive
     *       bugs such as the Turkish-locale upper-casing of
     *       {@code 'i'}.</li>
     *   <li><b>Issue the query</b>: with a non-blank search term,
     *       call
     *       {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String, Pageable)}
     *       (the JPA replacement for CICS
     *       {@code STARTBR ... GTEQ ... READNEXT}); without a search
     *       term, call the inherited {@code JpaRepository.findAll(Pageable)}
     *       to traverse the whole table from the start.</li>
     *   <li><b>Project</b> each {@link UserSecurity} into a
     *       {@link UserListDto.UserRow}, copying only the four
     *       display fields ({@code secUsrId}, {@code secUsrFname},
     *       {@code secUsrLname}, {@code secUsrType}) &mdash;
     *       <b>NEVER the password hash</b> (PCI-DSS, AAP &sect;0.7.1).</li>
     *   <li><b>Assemble</b> the response by combining the projected
     *       rows with Spring Data's paging metadata
     *       ({@link Page#getNumber()}, {@link Page#getTotalElements()},
     *       {@link Page#getTotalPages()}, {@link Page#isFirst()},
     *       {@link Page#isLast()}). The size component is always
     *       reported as {@link #PAGE_SIZE} (10), echoing the COBOL
     *       fixed 10-row contract back to the client.</li>
     * </ol>
     *
     * <h4>Empty-result handling</h4>
     *
     * <p>The COBOL source surfaces the &ldquo;no records found&rdquo;
     * condition by setting {@code WS-USER-SEC-EOF = 'Y'} on the first
     * {@code READNEXT} returning {@code FILE STATUS 23} (NOTFND) and
     * emitting the message
     * &ldquo;{@code NO RECORDS FOUND FOR THIS SEARCH CONDITION.}&rdquo;
     * onto the BMS error line. In the REST target the equivalent
     * &mdash; and architecturally cleaner &mdash; signal is an empty
     * {@link UserListDto#rows()} list combined with
     * {@link UserListDto#totalElements() totalElements} {@code = 0}
     * and {@link UserListDto#totalPages() totalPages} {@code = 0}. No
     * exception is thrown: an empty result is a valid query outcome,
     * not an error. The caller (UI or downstream service) is free to
     * render its own &ldquo;no records&rdquo; banner from these
     * fields.</p>
     *
     * <h4>Thread safety</h4>
     *
     * <p>This method is fully thread-safe: it holds no instance state
     * besides the immutable, constructor-injected
     * {@link UserSecurityRepository}, and all local variables live in
     * the per-call activation record. Multiple concurrent admin clients
     * can invoke {@code listUsers(...)} simultaneously without any
     * coordination.</p>
     *
     * @param searchTerm  optional starting-user-ID filter (matches the
     *                    legacy BMS field {@code USRIDIN PIC X(08)});
     *                    {@code null} or blank lists all users from the
     *                    start of the table. Whitespace is trimmed and
     *                    the value is uppercased ({@link Locale#US})
     *                    before the query is issued, since user-IDs
     *                    are always uppercase per
     *                    {@code COSGN00C.cbl}:L132&ndash;L135.
     * @param page        zero-based page index. Negative values are
     *                    clamped to {@code 0} (matching the COBOL
     *                    &ldquo;top of the page&rdquo; guard in
     *                    {@code PROCESS-PF7-KEY}). Page indices beyond
     *                    the last page are not rejected &mdash; the
     *                    repository returns an empty
     *                    {@link Page#getContent() content} list with
     *                    accurate {@code totalElements} / {@code totalPages}
     *                    metadata so the caller can correct its
     *                    pagination state.
     * @return a non-{@code null} {@link UserListDto} containing up to
     *         {@link #PAGE_SIZE} (10) projected user rows, the
     *         paging metadata derived from Spring Data's {@link Page},
     *         and the echoed (normalised) {@code searchFilter}. The
     *         password hash is NEVER present in the response.
     */
    @Transactional(readOnly = true)
    public UserListDto listUsers(String searchTerm, int page) {
        // COBOL: COUSR00C:PROCESS-PAGE-FORWARD / PROCESS-PAGE-BACKWARD
        // — single entry point for both forward and backward paging.

        // Clamp page to a non-negative value. Mirrors COBOL guard:
        //   IF CDEMO-CU00-PAGE-NUM > 1 ... ELSE
        //       MOVE 'You are already at the top of the page...' TO WS-MESSAGE
        // (COUSR00C.cbl PROCESS-PF7-KEY L248-L255). Spring Data PageRequest
        // throws IllegalArgumentException on a negative page, so we clamp
        // here once rather than letting the framework throw later.
        final int normalizedPage = Math.max(0, page);

        // COBOL: COUSR00C:STARTBR-USER-SEC-FILE — RIDFLD(SEC-USR-ID) GTEQ.
        // The PageRequest below carries the page number, the verbatim
        // PAGE_SIZE = 10 (USER-REC OCCURS 10 TIMES), and an explicit
        // Sort.by("secUsrId").ascending() — even though the derived query
        // method ends in OrderBySecUsrIdAsc, the Sort is required for the
        // unfiltered findAll(Pageable) branch below which has no implicit
        // method-name ordering. Keeping the Sort on the Pageable for BOTH
        // branches guarantees the same ascending-key traversal semantics
        // as the CICS STARTBR/READNEXT cursor.
        final Pageable pageable = PageRequest.of(
                normalizedPage,
                PAGE_SIZE,
                Sort.by("secUsrId").ascending());

        // Normalise the optional search filter. Per COSGN00C.cbl L132-L135,
        // SEC-USR-ID values are always uppercase; therefore the GTEQ
        // positioning key must also be uppercased. Locale.US is used
        // explicitly to avoid locale-sensitive case folding (e.g. the
        // Turkish-locale upper-casing of dotted 'i'). A null or blank
        // search term routes us to the unfiltered findAll branch.
        final String normalizedSearch;
        if (searchTerm == null || searchTerm.isBlank()) {
            normalizedSearch = null;
        } else {
            normalizedSearch = searchTerm.trim().toUpperCase(Locale.US);
        }

        // Emit a structured trace event. PII discipline (AAP §0.7.1, §0.6.6):
        // we log ONLY the (already-non-PII) page index and the optional
        // uppercased search term the caller themselves supplied. We NEVER
        // log raw user records, names, passwords, BCrypt hashes, or the
        // result set itself.
        if (LOG.isDebugEnabled()) {
            LOG.debug("listUsers searchTerm={} page={}",
                    normalizedSearch == null ? "<none>" : normalizedSearch,
                    normalizedPage);
        }

        // COBOL: COUSR00C:READNEXT-USER-SEC-FILE loop — fetch up to 10 rows
        // in ascending SEC-USR-ID order, starting at the GTEQ position
        // (filtered branch) or at the start of the table (unfiltered
        // branch).
        final Page<UserSecurity> result;
        if (normalizedSearch != null) {
            // Filtered case: equivalent of CICS
            //   STARTBR DATASET('USRSEC') RIDFLD(SEC-USR-ID) GTEQ
            //   followed by READNEXT × 10.
            result = userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                            normalizedSearch, pageable);
        } else {
            // Unfiltered case: equivalent of CICS
            //   STARTBR DATASET('USRSEC') RIDFLD(LOW-VALUES) GTEQ
            //   (initial load with SEC-USR-ID = LOW-VALUES per
            //    COUSR00C.cbl PROCESS-ENTER-KEY L218-L222).
            result = userSecurityRepository.findAll(pageable);
        }

        // COBOL: COUSR00C:POPULATE-USER-DATA — project each SEC-USER-DATA
        // record to a BMS row (USRIDnnI / FNAMEnnI / LNAMEnnI / UTYPEnnI
        // for nn = 01..10). In the Java target the projection target is a
        // UserListDto.UserRow record, NOT a 10-slot fixed array — the
        // Java List sizes itself to the actual row count, so the COBOL
        // INITIALIZE-USER-DATA "blank the remaining slots" paragraph has
        // no Java equivalent.
        //
        // PII / PCI-DSS discipline (AAP §0.7.1, §0.6.6): we read ONLY the
        // four display fields below and INTENTIONALLY do NOT touch
        // UserSecurity#getSecUsrPwd(). The BCrypt hash never leaves the
        // persistence layer.
        final List<UserListDto.UserRow> rows = result.getContent().stream()
                .map(UserListService::toUserRow)
                .toList();

        // Emit a result-cardinality trace (not a row dump). This preserves
        // a useful operational signal — "the search produced N rows" —
        // while remaining PII-safe.
        if (LOG.isDebugEnabled()) {
            LOG.debug("listUsers returning {} of {} total rows on page {} of {}",
                    rows.size(),
                    result.getTotalElements(),
                    result.getNumber(),
                    Math.max(1, result.getTotalPages()));
        }

        // COBOL: equivalent of COUSR00C:ENDBR-USER-SEC-FILE + SEND-USRLST-SCREEN.
        // Assemble the response DTO carrying:
        //   - the projected rows (≤ 10, may be empty if no row matches);
        //   - the echoed page index (Spring Data Page#getNumber);
        //   - the verbatim PAGE_SIZE = 10 (matches the COBOL contract);
        //   - the total-element / total-page counts (used by the UI for the
        //     "Page n of m" footer that the COBOL POPULATE-HEADER-INFO
        //     paragraph would have rendered);
        //   - the first / last indicators (replace the COBOL
        //     "You are already at the top/bottom of the page..." messages
        //     emitted from PROCESS-PF7-KEY / PROCESS-PF8-KEY);
        //   - the echoed normalised search filter (matches the BMS field
        //     USRIDIN that COUSR00C copied to SEC-USR-ID before STARTBR).
        return new UserListDto(
                rows,
                result.getNumber(),
                PAGE_SIZE,
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast(),
                normalizedSearch);
    }

    // -------------------------------------------------------------------------
    // Internal projection helpers
    //
    // Kept as a private static method (rather than an inline lambda body)
    // for clarity in stack traces, to make the PII discipline visible
    // (one place documents which fields cross the persistence/response
    // boundary), and to keep listUsers() readable.
    // -------------------------------------------------------------------------

    /**
     * Projects a single {@link UserSecurity} JPA entity into a
     * {@link UserListDto.UserRow} response component.
     *
     * <p>COBOL: {@code POPULATE-USER-DATA} per-row {@code MOVE} block
     * ({@code COUSR00C.cbl}:L384&ndash;L441). The COBOL source moves
     * five fields onto the BMS map:
     * <pre>
     *     MOVE SEC-USR-ID    TO USRIDnnI OF COUSR0AI
     *     MOVE SEC-USR-FNAME TO FNAMEnnI OF COUSR0AI
     *     MOVE SEC-USR-LNAME TO LNAMEnnI OF COUSR0AI
     *     MOVE SEC-USR-TYPE  TO UTYPEnnI OF COUSR0AI
     * </pre>
     * The Java projection mirrors this verbatim &mdash; same four
     * fields, same order &mdash; with one deliberate omission:
     * {@code SEC-USR-PWD}. Even in the COBOL source the password is
     * never put onto the BMS map (the COBOL programmer never wrote a
     * {@code MOVE SEC-USR-PWD ...} statement for {@code COUSR0AI}); in
     * the Java target the equivalent omission is enforced here in code
     * for clarity and is reinforced by the absence of any password
     * field on {@link UserListDto.UserRow}.
     *
     * <p><b>PCI-DSS (AAP &sect;0.7.1, &sect;0.6.6):</b> this projector
     * does NOT invoke {@link UserSecurity#getSecUsrPwd()}. The BCrypt
     * hash never crosses the persistence/response boundary.
     *
     * @param entity a managed {@link UserSecurity} instance returned by
     *               the repository; must not be {@code null}
     * @return a {@link UserListDto.UserRow} carrying only the four
     *         display fields ({@code userId}, {@code firstName},
     *         {@code lastName}, {@code userType}); never {@code null}
     */
    private static UserListDto.UserRow toUserRow(UserSecurity entity) {
        // Defensive null-check: Spring Data JPA never returns a null
        // element inside a Page's content list (it returns an empty
        // list instead), so this branch is normally unreachable. It
        // is retained as a defence-in-depth guard in case a future
        // refactor introduces a stream operator (filter / map) that
        // could yield a null, so that we fail fast with a clear
        // diagnostic rather than producing a half-populated DTO.
        if (entity == null) {
            throw new IllegalStateException(
                    "UserSecurityRepository returned a null entity inside its Page content; "
                            + "this violates the Spring Data contract.");
        }

        // COBOL: COUSR00C:POPULATE-USER-DATA L384-L441 — exact field
        // order matches the original MOVE block (id, fname, lname, type).
        return new UserListDto.UserRow(
                entity.getSecUsrId(),     // COBOL: SEC-USR-ID    → USRIDnnI
                entity.getSecUsrFname(),  // COBOL: SEC-USR-FNAME → FNAMEnnI
                entity.getSecUsrLname(),  // COBOL: SEC-USR-LNAME → LNAMEnnI
                entity.getSecUsrType()    // COBOL: SEC-USR-TYPE  → UTYPEnnI
                // NOTE: SEC-USR-PWD intentionally OMITTED per PCI-DSS
                // (AAP §0.7.1, §0.6.6). The BCrypt hash never crosses the
                // persistence/response boundary.
        );
    }
}
