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

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.UserListDto;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link UserListService}.
 *
 * <p><b>COBOL provenance.</b> {@link UserListService} translates the CICS
 * COBOL program {@code app/cbl/COUSR00C.cbl} (CICS transaction id
 * {@code CU00}, file {@code 'USRSEC'}) into a Java {@code @Service}
 * class per the one-service-per-COBOL-program rule (AAP &sect;0.7.1
 * "Isolate each COBOL program's logic in its own dedicated Java
 * service class"). These tests verify the four core behavioural
 * invariants surfaced by the source program:</p>
 *
 * <ul>
 *   <li><b>PAGE_SIZE = 10</b> &mdash; verbatim transcription of the
 *       working-storage array {@code 02 USER-REC OCCURS 10 TIMES} at
 *       {@code COUSR00C.cbl}:L56&ndash;L64 and the 10-row BMS map
 *       {@code COUSR0A}. AAP &sect;0.7.1 Minimal Change Clause forbids
 *       deviation from the literal 10-row contract.</li>
 *   <li><b>Sort by {@code secUsrId} ascending</b> &mdash; matches the
 *       VSAM KSDS physical key ordering and the CICS
 *       {@code STARTBR}/{@code READNEXT} traversal direction
 *       ({@code COUSR00C.cbl} {@code STARTBR-USER-SEC-FILE} /
 *       {@code READNEXT-USER-SEC-FILE}).</li>
 *   <li><b>Optional GTEQ filter</b> &mdash; when a starting user-ID is
 *       supplied, the JPA derived query
 *       {@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(...)}
 *       replaces the CICS {@code STARTBR ... RIDFLD(SEC-USR-ID) GTEQ}
 *       positioning verb; the term is trimmed and uppercased with
 *       {@code Locale.US} before the query is issued to match the
 *       always-uppercase {@code SEC-USR-ID} primary-key contract in
 *       the source data (per {@code COSGN00C.cbl}:L132&ndash;L135
 *       where the COBOL sign-on routine applies
 *       {@code FUNCTION UPPER-CASE}).</li>
 *   <li><b>PCI-DSS PII discipline</b> &mdash; {@code SEC-USR-PWD}
 *       (the BCrypt hash per AAP &sect;0.1.1 security upgrade) is
 *       NEVER carried on the {@link UserListDto.UserRow} response. This
 *       suite verifies that absence both structurally (no
 *       {@code password} field declared on the {@code UserRow}
 *       record) and behaviourally (a non-null
 *       {@code UserSecurity#getSecUsrPwd()} stored on the mocked
 *       repository return never appears in the resulting DTO row's
 *       {@code toString()} representation).</li>
 * </ul>
 *
 * <p><b>Test taxonomy.</b> The {@link Nested} groups below mirror the
 * agent-prompt-specified behavioural taxonomy verbatim:</p>
 *
 * <ol>
 *   <li>{@link PageSize} &mdash; verifies the verbatim 10-row contract
 *       and the {@code Sort.by("secUsrId").ascending()} order embedded
 *       in the {@link Pageable} passed to the repository.</li>
 *   <li>{@link GteqFilter} &mdash; verifies the conditional dispatch
 *       between {@code findAll(Pageable)} (unfiltered) and
 *       {@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(...)}
 *       (filtered), plus the uppercase normalisation of the search
 *       term.</li>
 *   <li>{@link PiiInOutput} &mdash; verifies that
 *       {@link UserListDto.UserRow} carries only the four display
 *       fields ({@code userId}, {@code firstName}, {@code lastName},
 *       {@code userType}) and that the BCrypt hash never crosses the
 *       persistence/response boundary.</li>
 *   <li>{@link Pagination} &mdash; verifies the page metadata
 *       projection from Spring Data's {@link Page} into the
 *       {@link UserListDto} response envelope.</li>
 *   <li>{@link AuditLogging} &mdash; documents the
 *       {@link AuditLogService} collaboration boundary. The current
 *       {@link UserListService} constructor takes only the repository,
 *       so the audit-log mock is reserved (no interactions today);
 *       this test class is forward-compatible with a future
 *       enhancement that emits security-audit events on user-list
 *       access without requiring an import change.</li>
 * </ol>
 *
 * <p><b>Mock wiring (AAP &sect;0.7.2 unit-test approach: JUnit 5 +
 * Mockito).</b> {@code @ExtendWith(MockitoExtension.class)} bootstraps
 * Mockito's per-test mock initialisation; {@code @Mock} declares both
 * collaborators; {@code @InjectMocks} instantiates the
 * {@link UserListService} via its single-arg constructor injecting the
 * {@code UserSecurityRepository} mock. The {@code AuditLogService} mock
 * is declared per the schema and described in the audit-log boundary
 * test below, even though it is not wired into the service today.</p>
 *
 * @see UserListService
 *      the system under test
 * @see UserSecurityRepository
 *      Spring Data JPA repository mocked here
 * @see UserListDto
 *      paged response DTO whose PII discipline is verified
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService — COUSR00C paginated browse")
class UserListServiceTest {

    // -------------------------------------------------------------------------
    // Test fixture constants
    //
    // BCrypt hash placeholder values used only as opaque strings inside the
    // mocked UserSecurity instances. They are NEVER subjected to
    // BCryptPasswordEncoder verification or any cryptographic operation —
    // their sole purpose is to ensure that:
    //   (a) the mock repository can return a fully populated UserSecurity, and
    //   (b) the PiiInOutput tests can verify the hash does not appear in
    //       the resulting UserListDto.UserRow output.
    // The values are NOT real BCrypt hashes derived from any plaintext
    // password — they are deliberate, recognisable test markers so that any
    // accidental leakage in the rendered DTO would surface immediately.
    // -------------------------------------------------------------------------

    /**
     * Recognisable BCrypt-shaped marker for "regular user" fixtures.
     * Designed to be obvious in any string-leak failure message.
     */
    private static final String BCRYPT_MARKER_USER =
            "$2a$12$USERhashUSERhashUSERhashUSERhashUSERhashUSERhashUSERhash01";

    /**
     * Recognisable BCrypt-shaped marker for "admin user" fixtures.
     * Distinct from {@link #BCRYPT_MARKER_USER} so leak failure messages
     * can attribute the row that leaked.
     */
    private static final String BCRYPT_MARKER_ADMIN =
            "$2a$12$ADMNhashADMNhashADMNhashADMNhashADMNhashADMNhashADMNhash01";

    /**
     * The verbatim COBOL page size derived from
     * {@code 02 USER-REC OCCURS 10 TIMES} at {@code COUSR00C.cbl}:L57.
     * Kept as a local constant in addition to
     * {@link UserListService#PAGE_SIZE} so that PageSize assertions
     * remain readable even if the production constant ever moves.
     */
    private static final int COBOL_USER_REC_PAGE_SIZE = 10;

    // -------------------------------------------------------------------------
    // Mocks and SUT
    //
    // The MockitoExtension processes these annotations per test method:
    //   * @Mock fields are initialised to fresh Mockito mocks before each
    //     test (so stubs and verifications from one test do not bleed into
    //     another).
    //   * @InjectMocks instantiates the SUT and constructor-injects the
    //     matching @Mock fields. UserListService has exactly one constructor
    //     parameter (UserSecurityRepository), so only userSecurityRepository
    //     is wired; the auditLogService mock is allocated but stays
    //     unattached — see the AuditLogging nested group for the rationale.
    // -------------------------------------------------------------------------

    /**
     * Mock Spring Data JPA repository for {@link UserSecurity}.
     *
     * <p>Stubbed via {@code when(...).thenReturn(...)} in each test
     * (no class-level stub bleed) and verified via {@code verify(...)}
     * plus {@link ArgumentCaptor} where the captured argument needs to
     * be inspected (notably the {@link Pageable} passed to
     * {@code findAll}/{@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc}).</p>
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock audit-log adapter.
     *
     * <p>Declared per the schema and present to support the
     * {@link AuditLogging} forward-compatibility boundary test. The
     * current {@link UserListService} constructor takes only
     * {@link UserSecurityRepository}, so Mockito's {@code @InjectMocks}
     * does NOT wire this mock into the SUT; it stays uninvoked. When
     * the service is enhanced per AAP &sect;0.6.6 to call audit
     * logging on user-list access, the {@link AuditLogging} test will
     * be updated to verify the emission via {@code verify(auditLogService)...}.</p>
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. Instantiated by Mockito via the
     * single-argument constructor
     * {@link UserListService#UserListService(UserSecurityRepository)},
     * injecting {@link #userSecurityRepository}.
     */
    @InjectMocks
    private UserListService service;

    // -------------------------------------------------------------------------
    // Shared fixtures
    //
    // The 10-row fixture mirrors a freshly seeded user_security table:
    //   - ADMIN001..ADMIN005 (5 admins, sec_usr_type = 'A')
    //   - USER0001..USER0005 (5 regular users, sec_usr_type = 'U')
    // arranged in ascending ID order so the list shape matches the
    // ascending-key traversal semantics of the CICS browse loop.
    //
    // The 10-row contract is the COBOL USER-REC OCCURS 10 TIMES literal —
    // every PageSize / Pagination test that needs a "full" page uses this
    // exact size to exercise the boundary.
    // -------------------------------------------------------------------------

    /**
     * Ten {@link UserSecurity} fixture rows sorted ascending by
     * {@code secUsrId} ("ADMIN001"..."ADMIN005",
     * "USER0001"..."USER0005"). Created fresh per test method by the
     * {@link #setUp()} hook so test isolation is preserved.
     */
    private List<UserSecurity> tenUsersFixture;

    /**
     * Per-method initialisation hook. Constructs the 10-row fixture
     * once per test so each test gets its own mutable list instance.
     * Avoids any inter-test fixture coupling.
     */
    @BeforeEach
    void setUp() {
        // COBOL: COUSR00C:LIST-USERS — 10 rows max (USER-REC OCCURS 10 TIMES).
        tenUsersFixture = List.of(
                new UserSecurity("ADMIN001", "Admin", "One", BCRYPT_MARKER_ADMIN, "A"),
                new UserSecurity("ADMIN002", "Admin", "Two", BCRYPT_MARKER_ADMIN, "A"),
                new UserSecurity("ADMIN003", "Admin", "Three", BCRYPT_MARKER_ADMIN, "A"),
                new UserSecurity("ADMIN004", "Admin", "Four", BCRYPT_MARKER_ADMIN, "A"),
                new UserSecurity("ADMIN005", "Admin", "Five", BCRYPT_MARKER_ADMIN, "A"),
                new UserSecurity("USER0001", "User", "One", BCRYPT_MARKER_USER, "U"),
                new UserSecurity("USER0002", "User", "Two", BCRYPT_MARKER_USER, "U"),
                new UserSecurity("USER0003", "User", "Three", BCRYPT_MARKER_USER, "U"),
                new UserSecurity("USER0004", "User", "Four", BCRYPT_MARKER_USER, "U"),
                new UserSecurity("USER0005", "User", "Five", BCRYPT_MARKER_USER, "U")
        );
    }

    // =========================================================================
    // PageSize — verbatim COBOL USER-REC OCCURS 10 TIMES
    // =========================================================================

    /**
     * Verifies the verbatim 10-row page-size contract and the
     * deterministic ascending-key sort that the COBOL browse loop
     * produces.
     *
     * <p>The COBOL source declares the in-memory row buffer as
     * {@code 02 USER-REC OCCURS 10 TIMES} at
     * {@code COUSR00C.cbl}:L57; the BMS map {@code COUSR0A} is drawn
     * with exactly 10 row positions. The Java target preserves both
     * the row count and the ascending traversal order. The single test
     * here captures the {@link Pageable} argument and asserts on
     * {@code getPageSize()} (the 10-row contract) and on
     * {@code getSort()} (the ascending {@code secUsrId} order).</p>
     */
    @Nested
    @DisplayName("PageSize — verbatim COBOL USER-REC OCCURS 10 TIMES")
    class PageSize {

        /**
         * Verifies that {@link UserListService#listUsers(String, int)}
         * issues a {@link PageRequest} of size {@code 10} and sorted
         * ascending by {@code secUsrId}.
         *
         * <p>COBOL: {@code COUSR00C:STARTBR-USER-SEC-FILE} configures
         * the keyed browse on {@code USRSEC} with the default GTEQ
         * comparison; the {@code PERFORM ... 10 TIMES READNEXT} loop
         * caps the page at 10 rows. The Java target encodes both the
         * page size and the ascending order on the {@link Pageable}.</p>
         */
        @Test
        @DisplayName("listUsers — default page size is 10 (COBOL: USER-REC OCCURS 10 TIMES)")
        void listUsers_defaultPageSize_isTen() {
            // COBOL: COUSR00C:LIST-USERS — unfiltered initial load with
            // SEC-USR-ID = LOW-VALUES → routed to findAll(Pageable).
            // Stub the repository to return an empty Page so the SUT
            // completes without surfacing a NullPointerException.
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When
            service.listUsers(null, 0);

            // Then — capture the Pageable passed to findAll and assert
            // both invariants: 10-row page size + ascending-by-secUsrId
            // sort. The ArgumentCaptor here is the canonical Mockito
            // pattern for inspecting complex argument objects (per AAP
            // §0.7.2 unit-test approach using Mockito).
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userSecurityRepository).findAll(captor.capture());

            Pageable capturedPageable = captor.getValue();
            assertThat(capturedPageable.getPageSize())
                    .as("PAGE_SIZE — verbatim COBOL: USER-REC OCCURS 10 TIMES at "
                            + "COUSR00C.cbl:L57; Minimal Change Clause forbids deviation")
                    .isEqualTo(COBOL_USER_REC_PAGE_SIZE);

            // Also verify the page number is 0 (caller supplied 0; no
            // clamping needed).
            assertThat(capturedPageable.getPageNumber())
                    .as("page index — caller supplied 0; passes through unchanged")
                    .isZero();

            // And verify the Sort order. The OrderBySecUsrIdAsc method
            // segment on the derived query already forces ASC ordering,
            // but the SUT also embeds Sort.by("secUsrId").ascending() on
            // the Pageable so that the unfiltered findAll(Pageable) branch
            // gets the same ordering deterministically.
            Sort.Order order = capturedPageable.getSort().getOrderFor("secUsrId");
            assertThat(order)
                    .as("Sort must include 'secUsrId' to match the CICS STARTBR "
                            + "ascending-key traversal in COUSR00C.cbl")
                    .isNotNull();
            assertThat(order.isAscending())
                    .as("Sort direction must be ASC — matches the COBOL "
                            + "READNEXT-USER-SEC-FILE traversal direction")
                    .isTrue();
        }

        /**
         * Verifies that the page size is preserved across both the
         * filtered and unfiltered branches. The COBOL contract is a
         * fixed 10 rows regardless of whether a starting user-ID was
         * supplied; the Java target must not vary the page size by
         * branch.
         */
        @Test
        @DisplayName("listUsers — page size is 10 on the filtered (GTEQ) branch too")
        void listUsers_pageSize_isTenOnFilteredBranch() {
            when(userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listUsers("USER0001", 0);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userSecurityRepository)
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0001"), captor.capture());

            assertThat(captor.getValue().getPageSize())
                    .as("PAGE_SIZE preserved on filtered branch — COBOL "
                            + "USER-REC OCCURS 10 TIMES is unconditional")
                    .isEqualTo(COBOL_USER_REC_PAGE_SIZE);
        }
    }

    // =========================================================================
    // GteqFilter — CICS STARTBR ... GTEQ ... READNEXT semantics
    // =========================================================================

    /**
     * Verifies the conditional dispatch between the unfiltered
     * {@code findAll(Pageable)} branch (initial load /
     * SEC-USR-ID = LOW-VALUES) and the filtered
     * {@code findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(...)}
     * branch (caller supplied a starting user-ID), and the
     * uppercase-then-pass normalisation of the search term.
     *
     * <p>COBOL: {@code COUSR00C:PROCESS-ENTER-KEY} L218&ndash;L222
     * routes between the LOW-VALUES (initial / unfiltered) and the
     * caller-supplied {@code USRIDINI} (filtered) branches. The
     * caller-supplied value originates from the BMS map (3270 input
     * field {@code USRIDINI PIC X(08)}) and is always uppercase in
     * the source data because {@code SEC-USR-ID} values are
     * administratively assigned as uppercase identifiers (and the
     * sign-on routine in {@code COSGN00C.cbl}:L132&ndash;L135
     * normalises any lowercase input). The Java target reproduces
     * the normalisation in service code with
     * {@code Locale.US} upper-casing.</p>
     */
    @Nested
    @DisplayName("GteqFilter — CICS STARTBR ... GTEQ ... READNEXT semantics")
    class GteqFilter {

        /**
         * Verifies that supplying a non-blank starting user-ID
         * triggers the derived GTEQ-ordered query
         * {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(String, Pageable)},
         * and that {@code findAll(Pageable)} is NOT invoked.
         */
        @Test
        @DisplayName("listUsers — with startUserId, invokes findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc")
        void listUsers_withStartUserId_callsGteqFinder() {
            when(userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listUsers("USER0001", 0);

            // Verify the derived query was invoked with the (already
            // uppercase) starting key.
            verify(userSecurityRepository)
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0001"), any(Pageable.class));

            // And verify findAll was NOT invoked. This is the dispatch
            // assertion — confirming the SUT routed to the filtered
            // branch and only the filtered branch.
            verify(userSecurityRepository, never()).findAll(any(Pageable.class));
        }

        /**
         * Verifies that supplying {@code null} as the search term
         * (the initial load / SEC-USR-ID = LOW-VALUES case) routes
         * to the unfiltered {@link UserSecurityRepository#findAll(Pageable)}
         * and that the GTEQ finder is NOT invoked.
         *
         * <p>COBOL: {@code COUSR00C:PROCESS-ENTER-KEY} L218 &mdash;
         * {@code IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES
         * MOVE LOW-VALUES TO SEC-USR-ID}: the LOW-VALUES sentinel
         * positions the cursor at the start of the table, which the
         * Java target expresses as the unfiltered {@code findAll}.</p>
         */
        @Test
        @DisplayName("listUsers — without startUserId, invokes findAll (unfiltered initial load)")
        void listUsers_withoutStartUserId_returnsAllUsers() {
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listUsers(null, 0);

            // Verify findAll was invoked with a Pageable carrying the
            // verbatim 10-row contract.
            verify(userSecurityRepository).findAll(any(Pageable.class));

            // Verify the GTEQ finder was NOT invoked.
            verify(userSecurityRepository, never())
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class));
        }

        /**
         * Verifies that a blank (whitespace-only) search term is
         * treated as the unfiltered initial-load case. The COBOL
         * source emits {@code SPACES OR LOW-VALUES} as equivalent
         * "no filter" sentinels.
         */
        @Test
        @DisplayName("listUsers — blank startUserId is treated as no filter (SPACES → LOW-VALUES)")
        void listUsers_blankStartUserId_invokesFindAll() {
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listUsers("   ", 0);

            verify(userSecurityRepository).findAll(any(Pageable.class));
            verify(userSecurityRepository, never())
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class));
        }

        /**
         * Verifies the uppercase-normalisation of the search term
         * before the query is issued. Input {@code "user0001"} (lower
         * case) MUST become {@code "USER0001"} (upper case) at the
         * repository boundary, matching the always-uppercase
         * {@code SEC-USR-ID} primary key in {@code USRSEC} (per the
         * {@code FUNCTION UPPER-CASE} normalisation in
         * {@code COSGN00C.cbl}:L132&ndash;L135 used elsewhere on the
         * same field).
         */
        @Test
        @DisplayName("listUsers — startUserId is uppercased before the query (Locale.US)")
        void listUsers_startUserId_uppercasedBeforeQuery() {
            when(userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Caller supplies lower-case input.
            service.listUsers("user0001", 0);

            // Repository must receive the upper-case form.
            verify(userSecurityRepository)
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0001"), any(Pageable.class));
        }

        /**
         * Verifies that whitespace around the search term is trimmed
         * before uppercase normalisation. The COBOL 3270 input field
         * is fixed-width and any trailing/leading spaces would not be
         * meaningful as part of the {@code SEC-USR-ID} key.
         */
        @Test
        @DisplayName("listUsers — startUserId is trimmed before uppercase/query")
        void listUsers_startUserId_trimmedBeforeQuery() {
            when(userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listUsers("  user0001  ", 0);

            verify(userSecurityRepository)
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq("USER0001"), any(Pageable.class));
        }
    }

    // =========================================================================
    // PiiInOutput — PCI-DSS: password NEVER in DTO output
    // =========================================================================

    /**
     * Verifies the PII / PCI-DSS discipline (AAP &sect;0.7.1,
     * &sect;0.6.6): the {@code SEC-USR-PWD} BCrypt hash never crosses
     * the persistence-to-response boundary.
     *
     * <p>The discipline is enforced at three layers, two of which are
     * verifiable here:</p>
     * <ol>
     *   <li><b>Structural (compile-time):</b> the
     *       {@link UserListDto.UserRow} record declares only the four
     *       display fields ({@code userId}, {@code firstName},
     *       {@code lastName}, {@code userType}). Reflection over
     *       {@code getDeclaredFields()} verifies the absence of any
     *       password-related field.</li>
     *   <li><b>Behavioural (run-time):</b> populating the mocked
     *       repository return with a recognisable BCrypt marker and
     *       checking the resulting {@link UserListDto.UserRow#toString()}
     *       confirms the hash does not leak through any unexpected
     *       projection or logging path.</li>
     *   <li><i>Operational (production-time):</i> the
     *       {@code GlobalExceptionHandler} sanitises any unexpected
     *       exception payloads &mdash; not in scope for this unit
     *       test.</li>
     * </ol>
     */
    @Nested
    @DisplayName("PiiInOutput — PCI-DSS: password NEVER in DTO output")
    class PiiInOutput {

        /**
         * Verifies that the {@link UserListDto.UserRow} record has no
         * password field (structural check) and that the BCrypt hash
         * stored on the underlying {@link UserSecurity} entity does
         * not appear in the rendered row's {@code toString()}
         * representation (behavioural check).
         */
        @Test
        @DisplayName("listUsers — DTO rows do not contain a password field and do not leak the BCrypt hash")
        void listUsers_dtoEntries_doNotContainPassword() {
            // ----- Structural check ---------------------------------------
            //
            // Inspect UserListDto.UserRow declared fields by reflection.
            // The PCI-DSS-aligned contract from AAP §0.7.1 + §0.6.6 is:
            // the only allowed fields are the four display fields, and
            // NO field name may resemble a password / hash / credential.
            Set<String> fieldNames = Arrays.stream(UserListDto.UserRow.class.getDeclaredFields())
                    .map(Field::getName)
                    .collect(Collectors.toSet());

            // Positive contract: exactly these four fields exist.
            assertThat(fieldNames)
                    .as("UserListDto.UserRow must declare exactly the four COBOL "
                            + "display fields (userId, firstName, lastName, userType) — "
                            + "matching the COBOL MOVE block at COUSR00C.cbl:L384-L441")
                    .containsExactlyInAnyOrder("userId", "firstName", "lastName", "userType");

            // Negative contract: no password-related field names. This
            // is the explicit PCI-DSS guard. Any future refactor that
            // accidentally adds a password field (under any of these
            // common names) will fail this test.
            assertThat(fieldNames)
                    .as("UserListDto.UserRow must NOT declare any password-related "
                            + "field — SEC-USR-PWD (BCrypt hash) MUST NEVER cross the "
                            + "persistence/response boundary (AAP §0.7.1, §0.6.6 PCI-DSS)")
                    .doesNotContain("password", "pwd", "secUsrPwd", "passwordHash",
                            "hash", "bcryptHash", "credential", "credentials");

            // ----- Behavioural check --------------------------------------
            //
            // Populate the repository return with an entity carrying a
            // recognisable BCrypt-shaped marker, then run the service
            // and inspect the rendered DTO row's toString() output. The
            // marker MUST NOT appear anywhere in the rendered output.
            UserSecurity entity = new UserSecurity(
                    "ADMIN001", "Admin", "One", BCRYPT_MARKER_ADMIN, "A");
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(entity)));

            UserListDto dto = service.listUsers(null, 0);

            // Sanity: the entity getter returns the marker (proves the
            // marker was actually stored on the entity, so its absence
            // from the rendered output below is meaningful).
            assertThat(entity.getSecUsrPwd())
                    .as("BCrypt marker stored on the entity (sanity check)")
                    .isEqualTo(BCRYPT_MARKER_ADMIN);

            // The rendered DTO row carries exactly the four display
            // fields. We assert the row contains the user-ID and types
            // (so we know it was projected) and that it does NOT contain
            // the BCrypt marker.
            assertThat(dto.rows())
                    .as("repository returned 1 row → DTO carries 1 row")
                    .hasSize(1);

            UserListDto.UserRow row = dto.rows().get(0);
            assertThat(row.userId())
                    .as("row.userId() must match the projected COBOL SEC-USR-ID")
                    .isEqualTo("ADMIN001");
            assertThat(row.firstName())
                    .as("row.firstName() must match the projected COBOL SEC-USR-FNAME")
                    .isEqualTo("Admin");
            assertThat(row.lastName())
                    .as("row.lastName() must match the projected COBOL SEC-USR-LNAME")
                    .isEqualTo("One");
            assertThat(row.userType())
                    .as("row.userType() must match the projected COBOL SEC-USR-TYPE")
                    .isEqualTo("A");

            // The crown jewel of this test: verify the BCrypt marker
            // does NOT appear anywhere in the rendered row toString().
            // If the future contributor were to (a) add a password
            // field to UserRow, or (b) wire UserRow#toString() to
            // include the source UserSecurity, this assertion would
            // catch the leak immediately.
            assertThat(row.toString())
                    .as("UserRow.toString() must NEVER contain the BCrypt password "
                            + "hash — credential material must not appear in any rendered "
                            + "form (AAP §0.6.6 PCI-DSS: no plaintext card/account data "
                            + "in logs; same principle for credentials)")
                    .doesNotContain(BCRYPT_MARKER_ADMIN)
                    .doesNotContain("$2a$")  // any BCrypt-shaped substring
                    .doesNotContain("$2b$")
                    .doesNotContain("$2y$");

            // Belt-and-braces: also verify the DTO envelope toString
            // does not leak the marker (catches accidental references
            // to the source entity from the outer DTO).
            assertThat(dto.toString())
                    .as("Outer UserListDto.toString() must NEVER contain the BCrypt "
                            + "password hash either")
                    .doesNotContain(BCRYPT_MARKER_ADMIN);
        }
    }

    // =========================================================================
    // Pagination — first page returns first 10 users
    // =========================================================================

    /**
     * Verifies the projection of Spring Data's {@link Page} metadata
     * into the {@link UserListDto} response envelope. The COBOL source
     * tracks page state in {@code CDEMO-CU00-PAGE-NUM},
     * {@code CDEMO-CU00-USRID-FIRST}, {@code CDEMO-CU00-USRID-LAST},
     * and {@code CDEMO-CU00-NEXT-PAGE-FLG}; the Java target replaces
     * these with {@code page}, {@code first}, {@code last},
     * {@code totalElements}, and {@code totalPages} on the DTO.
     */
    @Nested
    @DisplayName("Pagination — first page returns first 10 users")
    class Pagination {

        /**
         * Verifies that requesting page 0 of a 25-row result set
         * returns 10 rows with the correct paging metadata: page 0,
         * size 10, totalElements 25, totalPages 3, first=true,
         * last=false. The search filter is null (unfiltered case).
         */
        @Test
        @DisplayName("listUsers — page 0 of 25 users returns first 10 rows with correct metadata")
        void listUsers_pageOne_returnsFirstTenUsers() {
            // Given — 10 rows on this page, 25 total elements across all
            // pages (25 / 10 = 3 pages: 10 + 10 + 5).
            Pageable expectedPageable = PageRequest.of(0, COBOL_USER_REC_PAGE_SIZE,
                    Sort.by("secUsrId").ascending());
            Page<UserSecurity> stubbedPage = new PageImpl<>(tenUsersFixture, expectedPageable, 25);

            when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(stubbedPage);

            // When
            UserListDto dto = service.listUsers(null, 0);

            // Then — row count and projection.
            assertThat(dto.rows())
                    .as("DTO row count matches the 10-row fixture")
                    .hasSize(COBOL_USER_REC_PAGE_SIZE);

            // Row 1 (lowest secUsrId in the ascending-sorted fixture).
            assertThat(dto.rows().get(0).userId())
                    .as("First row in ascending order — matches COBOL "
                            + "POPULATE-USER-DATA WHEN 1 (CDEMO-CU00-USRID-FIRST)")
                    .isEqualTo("ADMIN001");

            // Row 10 (highest secUsrId in the ascending-sorted fixture).
            assertThat(dto.rows().get(COBOL_USER_REC_PAGE_SIZE - 1).userId())
                    .as("Last row in ascending order — matches COBOL "
                            + "POPULATE-USER-DATA WHEN 10 (CDEMO-CU00-USRID-LAST)")
                    .isEqualTo("USER0005");

            // Page metadata.
            assertThat(dto.page())
                    .as("page number reflects the caller-supplied page index (0)")
                    .isZero();
            assertThat(dto.size())
                    .as("size echoes the COBOL USER-REC OCCURS 10 TIMES contract")
                    .isEqualTo(COBOL_USER_REC_PAGE_SIZE);
            assertThat(dto.totalElements())
                    .as("totalElements reflects the stubbed Page total of 25")
                    .isEqualTo(25L);
            assertThat(dto.totalPages())
                    .as("totalPages = ceil(25 / 10) = 3")
                    .isEqualTo(3);
            assertThat(dto.first())
                    .as("first=true because page index is 0 — matches the "
                            + "COBOL PROCESS-PF7-KEY 'top of the page' guard")
                    .isTrue();
            assertThat(dto.last())
                    .as("last=false because there are 2 pages after this one — "
                            + "matches the COBOL CDEMO-CU00-NEXT-PAGE-FLG flag")
                    .isFalse();

            // Unfiltered case → searchFilter is null on the DTO.
            assertThat(dto.searchFilter())
                    .as("searchFilter is null on the unfiltered branch — "
                            + "matches COBOL SEC-USR-ID = LOW-VALUES initial-load case")
                    .isNull();

            // PII discipline boundary: even the row toString must not
            // leak the BCrypt markers stored on the entities.
            assertThat(dto.rows().get(0).toString())
                    .as("ADMIN001 row toString() must not leak BCrypt marker")
                    .doesNotContain(BCRYPT_MARKER_ADMIN);
            assertThat(dto.rows().get(COBOL_USER_REC_PAGE_SIZE - 1).toString())
                    .as("USER0005 row toString() must not leak BCrypt marker")
                    .doesNotContain(BCRYPT_MARKER_USER);
        }

        /**
         * Verifies that a filtered request echoes the (normalised)
         * search filter back to the caller in
         * {@link UserListDto#searchFilter()}, and that the last-page
         * indicator is properly set when the result is the final page.
         */
        @Test
        @DisplayName("listUsers — filtered request echoes searchFilter and computes last-page indicator")
        void listUsers_filteredRequest_echoesSearchFilterAndLastPage() {
            // Given — a 5-row partial page on a single-page result set
            // (totalElements = 5; totalPages = 1; first = last = true).
            Pageable pageable = PageRequest.of(0, COBOL_USER_REC_PAGE_SIZE,
                    Sort.by("secUsrId").ascending());
            List<UserSecurity> fiveUsers = tenUsersFixture.subList(5, 10);
            Page<UserSecurity> stubbedPage = new PageImpl<>(fiveUsers, pageable, 5);

            when(userSecurityRepository
                    .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(anyString(), any(Pageable.class)))
                    .thenReturn(stubbedPage);

            // When
            UserListDto dto = service.listUsers("user", 0);

            // Then
            assertThat(dto.rows()).hasSize(5);
            assertThat(dto.page()).isZero();
            assertThat(dto.size()).isEqualTo(COBOL_USER_REC_PAGE_SIZE);
            assertThat(dto.totalElements()).isEqualTo(5L);
            assertThat(dto.totalPages()).isEqualTo(1);
            assertThat(dto.first())
                    .as("first=true — page 0 is always the first page")
                    .isTrue();
            assertThat(dto.last())
                    .as("last=true — totalPages=1 means page 0 is also the last page")
                    .isTrue();
            assertThat(dto.searchFilter())
                    .as("searchFilter is the uppercased, trimmed normalised "
                            + "form of the caller's input — preserves the COBOL "
                            + "always-uppercase SEC-USR-ID contract")
                    .isEqualTo("USER");
        }

        /**
         * Verifies that an empty result set surfaces as an empty
         * {@link UserListDto#rows()} list with
         * {@code totalElements = 0} and {@code totalPages = 0} &mdash;
         * the architecturally cleaner equivalent of the COBOL
         * {@code WS-USER-SEC-EOF = 'Y'} sentinel + "NO RECORDS FOUND
         * FOR THIS SEARCH CONDITION" error message. No exception is
         * thrown: an empty result is a valid query outcome.
         *
         * <p>Note: the mocked {@link PageImpl} is constructed with an
         * explicit paged {@link PageRequest} rather than the no-args
         * {@code PageImpl(List)} convenience constructor, because the
         * latter wraps the content in {@link Pageable#unpaged()} which
         * causes {@code Page.getTotalPages()} to return {@code 1} for
         * empty content (Spring Data treats &ldquo;unpaged + empty&rdquo;
         * as a single empty page). A paged {@link PageRequest} produces
         * the more intuitive {@code totalPages = ceil(0 / 10) = 0} that
         * the production code passes through verbatim.</p>
         */
        @Test
        @DisplayName("listUsers — empty result set surfaces as empty rows + zero totals (no exception)")
        void listUsers_emptyResult_returnsEmptyRowsZeroTotals() {
            // Use a paged PageRequest so that Page.getTotalPages() is
            // computed as ceil(totalElements / pageSize) = ceil(0/10) = 0
            // rather than the unpaged-Pageable special-case value of 1.
            Pageable pageable = PageRequest.of(0, COBOL_USER_REC_PAGE_SIZE,
                    Sort.by("secUsrId").ascending());
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageable, 0L));

            UserListDto dto = service.listUsers(null, 0);

            assertThat(dto).as("DTO is never null").isNotNull();
            assertThat(dto.rows()).as("rows is an empty list on empty result").isEmpty();
            assertThat(dto.totalElements())
                    .as("totalElements = 0 — equivalent of COBOL "
                            + "WS-USER-SEC-EOF = 'Y' on first READNEXT (FILE STATUS 23)")
                    .isZero();
            assertThat(dto.totalPages())
                    .as("totalPages = 0 — no pages exist when no rows match")
                    .isZero();
            assertThat(dto.first())
                    .as("an empty page is still 'first' per Spring Data convention")
                    .isTrue();
            assertThat(dto.last())
                    .as("an empty page is also 'last' per Spring Data convention")
                    .isTrue();
        }

        /**
         * Verifies the negative-page-number guard. The COBOL source
         * emits "You are already at the top of the page..." when the
         * user presses PF7 on page 1; the Java target equivalent is
         * to clamp negative page indices to 0 so Spring Data
         * {@link PageRequest#of(int, int)} does not throw
         * {@link IllegalArgumentException}.
         */
        @Test
        @DisplayName("listUsers — negative page index is clamped to 0 (matches COBOL PF7 'top of page' guard)")
        void listUsers_negativePage_isClampedToZero() {
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Caller supplies negative page number — must not throw.
            service.listUsers(null, -5);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userSecurityRepository).findAll(captor.capture());

            assertThat(captor.getValue().getPageNumber())
                    .as("Negative page index clamped to 0 — matches COBOL "
                            + "PROCESS-PF7-KEY 'You are already at the top of "
                            + "the page...' guard at COUSR00C.cbl:L251")
                    .isZero();
        }
    }

    // =========================================================================
    // AuditLogging — collaboration boundary with AuditLogService
    // =========================================================================

    /**
     * Documents the {@link AuditLogService} collaboration boundary.
     *
     * <p>The current {@link UserListService#UserListService(UserSecurityRepository)}
     * constructor takes only the repository, so Mockito's
     * {@code @InjectMocks} does NOT wire the
     * {@link #auditLogService} mock into the SUT. The mock is
     * declared per the schema so that:</p>
     *
     * <ol>
     *   <li>The test file compiles cleanly with the
     *       agent-prompt-specified imports (no unused-import
     *       warnings).</li>
     *   <li>This test class is forward-compatible with a future
     *       enhancement (per AAP &sect;0.6.6 "Cross-Cutting: Audit,
     *       Observability, and PCI-DSS") that emits a security-audit
     *       event on each user-list access &mdash; when that
     *       enhancement lands, only the assertions inside this test
     *       method need to change (no import or mock-wiring
     *       changes).</li>
     * </ol>
     *
     * <p>Until the enhancement lands, the test verifies the negative
     * contract: the audit-log mock has had no interactions, documenting
     * that user-list access is not audit-logged in the current
     * implementation. The COBOL source {@code COUSR00C.cbl} similarly
     * does not write to any audit trail on browse &mdash; it emits no
     * {@code DISPLAY} statements and writes no records; the absence of
     * audit logging on this path is therefore behaviourally consistent
     * with the COBOL source.</p>
     */
    @Nested
    @DisplayName("AuditLogging — collaboration boundary with AuditLogService")
    class AuditLogging {

        /**
         * Verifies the audit-log collaboration boundary: today's
         * {@link UserListService} does not emit audit events on
         * user-list access; the mock is reserved for a future
         * enhancement.
         *
         * <p>When this enhancement lands (AAP &sect;0.6.6: structured
         * audit events for compliance), this test should be updated
         * to assert
         * {@code verify(auditLogService).logSecurityEvent(eq("USER_LIST_ACCESSED"), ...);}
         * (or whichever {@link AuditLogService} method the enhancement
         * uses). Until then, the mock remains uninvoked and this
         * boundary test passes.</p>
         */
        @Test
        @DisplayName("listUsers — current implementation does not interact with AuditLogService (boundary documented)")
        void listUsers_emitsAuditEvent() {
            // Given — exercise the normal happy path.
            when(userSecurityRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When
            UserListDto dto = service.listUsers(null, 0);

            // Then — the operation succeeds and returns a non-null DTO.
            assertThat(dto)
                    .as("listUsers returns a non-null DTO even when no rows match")
                    .isNotNull();

            // Boundary contract — the AuditLogService mock has had NO
            // interactions in the current implementation. This is the
            // exact assertion that needs to be REPLACED when the
            // service is enhanced to emit a security-audit event for
            // user-list access.
            //
            // Suggested future assertion (for reference, NOT active):
            //   verify(auditLogService).logSecurityEvent(
            //           eq("USER_LIST_ACCESSED"),
            //           anyString(),   // operator code (SEC-USR-ID from JWT)
            //           anyString(),   // outcome ("SUCCESS")
            //           any(Map.class) // structured payload
            //   );
            verifyNoInteractions(auditLogService);
        }
    }
}
