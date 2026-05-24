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
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.repository.CardRepository;
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

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link CardListService}.
 *
 * <p><b>COBOL provenance.</b> {@link CardListService} is the Java
 * target for the COBOL/CICS program {@code app/cbl/COCRDLIC.cbl}
 * (CICS transaction id {@code CCLI}, mapset {@code COCRDLI}, map
 * {@code CCRDLIA}, file {@code 'CARDDAT'} / AIX {@code 'CARDAIX'}).
 * The COBOL source performs a paged {@code STARTBR}/{@code READNEXT}
 * loop across either the {@code CARDDAT} VSAM KSDS (admin user, no
 * account context) or the {@code CARDAIX} alternate index on
 * {@code CARD-ACCT-ID} (non-admin user or admin-with-filter), filling
 * the 7-row {@code CCRDLIA} BMS map. The Java target replaces
 * {@code STARTBR}/{@code READNEXT} with Spring Data
 * {@link CardRepository#findAll(Pageable)} (admin / no filter) and
 * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 * Pageable)} (filtered) at a fixed page size of {@code 7} matching the
 * verbatim {@code WS-MAX-SCREEN-LINES VALUE 7} working-storage literal
 * at {@code COCRDLIC.cbl}:L177-L178 and the 7-row
 * {@code WS-EDIT-SELECT OCCURS 7 TIMES} array at L72-L82.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>PAGE_SIZE = 7</b> &mdash; verbatim transcription of the
 *       COBOL {@code WS-MAX-SCREEN-LINES VALUE 7} at
 *       {@code COCRDLIC.cbl}:L177-L178 and the
 *       {@code WS-EDIT-SELECT OCCURS 7 TIMES} 7-row array at
 *       L72-L82. AAP &sect;0.7.1 Minimal Change Clause forbids
 *       deviation from this literal contract.</li>
 *   <li><b>Sort by {@code cardNum} ascending</b> &mdash; matches the
 *       VSAM KSDS physical key ordering and the CICS
 *       {@code STARTBR}/{@code READNEXT} traversal direction; the
 *       derived query name {@code OrderByCardNumAsc} additionally
 *       forces ASC ordering at the SQL layer.</li>
 *   <li><b>Conditional dispatch on {@code (accountFilter, isAdmin)}</b>
 *       &mdash; admin with no filter routes to
 *       {@link CardRepository#findAll(Pageable)} (all-cluster browse);
 *       admin-with-filter and non-admin-with-filter both route to
 *       {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 *       Pageable)} (per-account AIX-replacement browse); non-admin
 *       without filter short-circuits to an empty DTO without any
 *       repository call (matches the COBOL
 *       {@code 88 WS-NO-RECORDS-FOUND} condition).</li>
 *   <li><b>PAN masking (PCI-DSS)</b> &mdash; each
 *       {@link CardListDto.CardRow} returned by the service must have
 *       its {@code cardNumber} masked to the
 *       {@code ************XXXX} format (12 asterisks + last 4 digits)
 *       per AAP &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1.</li>
 *   <li><b>CVV absence (PCI-DSS)</b> &mdash; the
 *       {@link CardListDto.CardRow} record MUST NOT carry the CVV as
 *       a record component (verified structurally via reflection over
 *       the record components and behaviourally via the
 *       {@code toString} representation). AAP &sect;0.6.6 PCI-DSS v4.0
 *       Requirement 3.2 &mdash; Sensitive Authentication Data MUST NOT
 *       cross the persistence/response boundary.</li>
 *   <li><b>Verbatim "NO RECORDS FOUND" message</b> &mdash; preserved
 *       verbatim from the COBOL
 *       {@code 88 WS-NO-RECORDS-FOUND VALUE 'NO RECORDS FOUND FOR THIS
 *       SEARCH CONDITION.'} at {@code COCRDLIC.cbl}:L121-L122.</li>
 *   <li><b>Audit-logging collaboration boundary</b> &mdash; the current
 *       {@link CardListService} constructor takes only the
 *       {@link CardRepository}, so the {@link AuditLogService} mock is
 *       reserved for forward compatibility per sibling-test convention
 *       (no interactions today).</li>
 * </ol>
 *
 * <p><b>Test taxonomy.</b> The {@link Nested} groups below mirror the
 * agent-prompt-specified behavioural taxonomy verbatim:</p>
 *
 * <ol>
 *   <li>{@link PageSize} &mdash; verifies the verbatim 7-row contract
 *       and the {@code Sort.by("cardNum").ascending()} order embedded
 *       in the {@link Pageable} passed to the repository.</li>
 *   <li>{@link AcctIdFilter} &mdash; verifies the conditional dispatch
 *       between {@code findAll(Pageable)} (admin / no filter) and
 *       {@code findByCardAcctIdOrderByCardNumAsc(Long, Pageable)}
 *       (filtered branches), plus the empty-result path.</li>
 *   <li>{@link PanMasking} &mdash; verifies that the full 16-digit PAN
 *       on the {@link Card} entity is masked to
 *       {@code ************XXXX} before being placed on the DTO row,
 *       and that the CVV never appears on the DTO surface.</li>
 *   <li>{@link Pagination} &mdash; verifies the page metadata
 *       projection from Spring Data's {@link Page} into the
 *       {@link CardListDto} response envelope.</li>
 *   <li>{@link AdminUserGate} &mdash; verifies role-based filtering
 *       (admin sees all; non-admin sees only their account; non-admin
 *       without filter sees nothing).</li>
 *   <li>{@link AuditLogging} &mdash; documents the
 *       {@link AuditLogService} collaboration boundary (no
 *       interactions today; forward-compatible with a future
 *       enhancement that emits audit events on list access).</li>
 * </ol>
 *
 * <p><b>Mock wiring (AAP &sect;0.7.2 unit-test approach: JUnit 5 +
 * Mockito).</b> {@code @ExtendWith(MockitoExtension.class)} bootstraps
 * Mockito's per-test mock initialisation; {@code @Mock} declares both
 * collaborators; {@code @InjectMocks} instantiates the
 * {@link CardListService} via its single-arg constructor injecting the
 * {@link CardRepository} mock. The {@link AuditLogService} mock is
 * declared per the schema and described in the audit-log boundary
 * test below, even though it is not wired into the SUT today.</p>
 *
 * @see CardListService
 *      the system under test
 * @see CardRepository
 *      Spring Data JPA repository mocked here
 * @see CardListDto
 *      paged response DTO whose PAN-masking + CVV-absence discipline
 *      is verified
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardListService — COCRDLIC paginated browse (PAGE_SIZE=7)")
class CardListServiceTest {

    // -------------------------------------------------------------------------
    // Test fixture constants
    //
    // The PAN test markers below are deliberately recognisable strings:
    //   - 4111111111111111 - the canonical test Visa-shape PAN, masks to
    //     "************1111".
    //   - 5500000000000004 - canonical test Mastercard-shape PAN, masks to
    //     "************0004".
    //   - 6011000000000004 - canonical test Discover-shape PAN, masks to
    //     "************0004".
    // These are NOT real card numbers; they are industry-standard
    // testing PANs published by the card networks (Visa "4111-1111-...")
    // and are SAFE to use in test code per PCI-DSS v4.0 Requirement 3.4.
    // Using recognisable markers makes any masking-leak failure obvious
    // in the resulting assertion error message.
    // -------------------------------------------------------------------------

    /**
     * The verbatim COBOL page size derived from
     * {@code WS-MAX-SCREEN-LINES VALUE 7} at
     * {@code COCRDLIC.cbl}:L177-L178 and the
     * {@code WS-EDIT-SELECT OCCURS 7 TIMES} 7-row array at L72-L82.
     * Kept as a local constant in addition to
     * {@link CardListService#PAGE_SIZE} so PageSize assertions
     * remain readable even if the production constant ever moves.
     */
    // COBOL: COCRDLIC:WS-MAX-SCREEN-LINES=7 (L177-L178)
    private static final int COBOL_WS_MAX_SCREEN_LINES = 7;

    /**
     * Recognisable industry-standard test PAN. Masks to
     * {@code ************1111} under the
     * {@link CardListService} PAN-masking helper.
     */
    private static final String TEST_PAN_PRIMARY = "4111111111111111";

    /**
     * Expected masked form of {@link #TEST_PAN_PRIMARY} per AAP
     * &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1 (12 leading
     * asterisks + trailing 4 digits).
     */
    private static final String TEST_PAN_PRIMARY_MASKED = "************1111";

    /**
     * Recognisable test CVV that must NEVER appear on the
     * {@link CardListDto.CardRow} output. Intentionally distinct from
     * any embossed-name or status character so a leak would be
     * obvious in any string-based assertion.
     */
    // COBOL: CVACT02Y.cpy:L7 CARD-CVV-CD PIC 9(03) — SAD per PCI-DSS 3.2
    private static final Integer TEST_CVV_MARKER = 999;

    /**
     * Test account ID used for the AcctIdFilter / AdminUserGate
     * test groups. Mirrors the {@code CARD-ACCT-ID PIC 9(11)} field
     * from {@code app/cpy/CVACT02Y.cpy}:L6 and the 11-digit
     * {@code ACCTSIDI} BMS field from {@code COCRDLI.bms}.
     */
    private static final long TEST_ACCOUNT_ID = 11_111_111_111L;

    // -------------------------------------------------------------------------
    // Mocks and SUT
    //
    // The MockitoExtension processes these annotations per test method:
    //   * @Mock fields are initialised to fresh Mockito mocks before each
    //     test (so stubs and verifications from one test do not bleed into
    //     another).
    //   * @InjectMocks instantiates the SUT and constructor-injects the
    //     matching @Mock fields. CardListService has exactly one
    //     constructor parameter (CardRepository), so only cardRepository
    //     is wired; the auditLogService mock is allocated but stays
    //     unattached - see the AuditLogging nested group for the
    //     rationale.
    // -------------------------------------------------------------------------

    /**
     * Mock Spring Data JPA repository for {@link Card}.
     *
     * <p>Stubbed via {@code when(...).thenReturn(...)} in each test
     * (no class-level stub bleed) and verified via {@code verify(...)}
     * plus {@link ArgumentCaptor} where the captured argument needs to
     * be inspected (notably the {@link Pageable} passed to
     * {@code findAll} /
     * {@code findByCardAcctIdOrderByCardNumAsc}).</p>
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mock audit-log adapter.
     *
     * <p>Declared per the schema and present to support the
     * {@link AuditLogging} forward-compatibility boundary test. The
     * current {@link CardListService} constructor takes only
     * {@link CardRepository}, so Mockito's {@code @InjectMocks}
     * does NOT wire this mock into the SUT; it stays uninvoked. When
     * the service is enhanced per AAP &sect;0.6.6 to emit a "card list
     * accessed" audit event, the {@link AuditLogging} test will be
     * updated to verify the emission via
     * {@code verify(auditLogService)...}.</p>
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test. Instantiated by Mockito via the
     * single-argument constructor
     * {@link CardListService#CardListService(CardRepository)},
     * injecting {@link #cardRepository}.
     */
    @InjectMocks
    private CardListService service;

    // -------------------------------------------------------------------------
    // Shared fixtures
    //
    // The 7-row fixture mirrors a freshly seeded cards table page filled
    // to its COBOL screen capacity. The card numbers are ascending so
    // the resulting fixture preserves the same ordering shape produced
    // by the SUT's Sort.by("cardNum").ascending() request, which matches
    // the CICS STARTBR/READNEXT ascending-key traversal in COCRDLIC.cbl.
    // -------------------------------------------------------------------------

    /**
     * Seven {@link Card} fixture rows sorted ascending by
     * {@code cardNum}. Created fresh per test method by the
     * {@link #setUp()} hook so test isolation is preserved.
     */
    private List<Card> sevenCardsFixture;

    /**
     * A single {@link Card} fixture for the PAN-masking happy-path
     * test. Carries the recognisable {@link #TEST_PAN_PRIMARY} PAN
     * and the {@link #TEST_CVV_MARKER} CVV.
     */
    private Card singleCardFixture;

    /**
     * Per-method initialisation hook. Constructs the 7-row fixture
     * once per test so each test gets its own list instance. Avoids
     * any inter-test fixture coupling.
     */
    @BeforeEach
    void setUp() {
        // COBOL: COCRDLIC:LIST-CARDS — 7 rows max per page
        // (WS-MAX-SCREEN-LINES VALUE 7).
        sevenCardsFixture = new ArrayList<>(7);
        sevenCardsFixture.add(buildCard("4111111111110001", "ALICE ADAMS"));
        sevenCardsFixture.add(buildCard("4111111111110002", "BOB BAKER"));
        sevenCardsFixture.add(buildCard("4111111111110003", "CAROL CLARK"));
        sevenCardsFixture.add(buildCard("4111111111110004", "DAVID DOE"));
        sevenCardsFixture.add(buildCard("4111111111110005", "EVE ELLIS"));
        sevenCardsFixture.add(buildCard("4111111111110006", "FRANK FORD"));
        sevenCardsFixture.add(buildCard("4111111111110007", "GAIL GREY"));

        // Dedicated single-card fixture for PAN-masking assertions —
        // carries the recognisable Visa-shape test PAN so any masking
        // regression manifests as "************1111" vs the raw
        // "4111111111111111" in the failure message.
        singleCardFixture = buildCard(TEST_PAN_PRIMARY, "TEST CARDHOLDER");
    }

    /**
     * Test-fixture builder for a {@link Card} entity.
     *
     * <p>Populates every field that the COBOL {@code CARD-RECORD}
     * record carries (per {@code app/cpy/CVACT02Y.cpy}) so that the
     * resulting fixture is shape-identical to a freshly hydrated JPA
     * entity off the {@code cards} table. The
     * {@link Card#setCardCvvCd(Integer)} setter is invoked with
     * {@link #TEST_CVV_MARKER} so the PCI-DSS CVV-absence assertion
     * has a recognisable leak target.</p>
     *
     * <p>The {@code cardActiveStatus} is set to {@code "Y"} (active)
     * by default; tests that need an inactive card may construct
     * additional fixtures inline.</p>
     *
     * @param cardNum       16-digit PAN (used as primary key)
     * @param embossedName  the embossed-name string (up to 50 chars)
     * @return a fully populated {@link Card} instance ready for
     *         placement into a {@link PageImpl}
     */
    private Card buildCard(String cardNum, String embossedName) {
        Card c = new Card();
        c.setCardNum(cardNum);
        c.setCardAcctId(TEST_ACCOUNT_ID);
        c.setCardCvvCd(TEST_CVV_MARKER);
        c.setCardEmbossedName(embossedName);
        // COBOL: CARD-EXPIRAION-DATE (typo in source preserved at
        // copybook level; Java field corrected to expirationDate per
        // AAP §0.4.1). Use a far-future expiry so the card is
        // unambiguously "active" in any consumer test.
        c.setCardExpirationDate(LocalDate.of(2030, 12, 31));
        c.setCardActiveStatus("Y");
        c.setVersion(0L);
        return c;
    }

    // =========================================================================
    // PageSize - verbatim COBOL WS-MAX-SCREEN-LINES=7
    // =========================================================================

    /**
     * Verifies the verbatim 7-row page-size contract and the
     * deterministic ascending-key sort that the COBOL browse loop
     * produces.
     *
     * <p>The COBOL source declares the in-memory row buffer as
     * {@code WS-EDIT-SELECT OCCURS 7 TIMES} at
     * {@code COCRDLIC.cbl}:L72-L82 and the explicit
     * {@code WS-MAX-SCREEN-LINES VALUE 7} working-storage literal at
     * L177-L178; the BMS map {@code CCRDLIA} is drawn with exactly
     * 7 row positions ({@code ACCTNO1..ACCTNO7},
     * {@code CRDNUM1..CRDNUM7}, {@code CRDSTS1..CRDSTS7}). The Java
     * target preserves both the row count and the ascending
     * traversal order on every code path. The tests here capture
     * the {@link Pageable} argument and assert on
     * {@code getPageSize()} (the 7-row contract) and on
     * {@code getSort()} (the ascending {@code cardNum} order).</p>
     */
    @Nested
    @DisplayName("PageSize — verbatim COBOL WS-MAX-SCREEN-LINES=7")
    class PageSize {

        /**
         * Verifies that {@link CardListService#PAGE_SIZE} is exactly
         * the COBOL literal 7. This is a compile-time / constant
         * assertion; if the constant is ever changed away from 7
         * without an explicit AAP-mandated migration, this test
         * surfaces the drift immediately.
         *
         * <p>COBOL: {@code WS-MAX-SCREEN-LINES VALUE 7} at
         * {@code COCRDLIC.cbl}:L177-L178.</p>
         */
        @Test
        @DisplayName("PAGE_SIZE constant equals 7 (verbatim COBOL WS-MAX-SCREEN-LINES)")
        void pageSizeConstant_equalsSeven() {
            // COBOL: COCRDLIC:WS-MAX-SCREEN-LINES=7 (L177-L178)
            assertThat(CardListService.PAGE_SIZE)
                    .as("PAGE_SIZE — verbatim COBOL: WS-MAX-SCREEN-LINES VALUE 7 "
                            + "at COCRDLIC.cbl:L177-L178; AAP §0.7.1 Minimal "
                            + "Change Clause forbids deviation")
                    .isEqualTo(COBOL_WS_MAX_SCREEN_LINES);
        }

        /**
         * Verifies that {@link CardListService#listCards(Long, boolean, int)}
         * issues a {@link PageRequest} of size {@code 7} and sorted
         * ascending by {@code cardNum} on the admin (unfiltered)
         * branch.
         *
         * <p>COBOL: {@code COCRDLIC:STARTBR-CARDDAT-FILE} +
         * {@code 9000-READ-FORWARD} (READNEXT loop, max 7 iterations).
         * The Java target encodes both the page size and the
         * ascending order on the {@link Pageable}.</p>
         */
        @Test
        @DisplayName("listCards — admin branch: page size is 7 and sort is cardNum ASC")
        void listCards_adminBranch_pageSizeIsSeven() {
            // COBOL: COCRDLIC:LIST-CARDS — admin + no filter → findAll(Pageable)
            // Stub the repository to return an empty Page so the SUT
            // completes without surfacing a NullPointerException.
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When — invoke the SUT with isAdmin=true, accountFilter=null,
            // page=0 (the admin unfiltered initial-load path).
            service.listCards(null, true, 0);

            // Then — capture the Pageable passed to findAll and assert
            // both invariants: 7-row page size + ascending-by-cardNum
            // sort. The ArgumentCaptor here is the canonical Mockito
            // pattern for inspecting complex argument objects (per AAP
            // §0.7.2 unit-test approach using Mockito).
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(cardRepository).findAll(captor.capture());

            Pageable capturedPageable = captor.getValue();
            assertThat(capturedPageable.getPageSize())
                    .as("PAGE_SIZE — verbatim COBOL: WS-MAX-SCREEN-LINES VALUE 7 at "
                            + "COCRDLIC.cbl:L177-L178; Minimal Change Clause forbids "
                            + "deviation")
                    .isEqualTo(COBOL_WS_MAX_SCREEN_LINES);

            // Also verify the page number is 0 (caller supplied 0; no
            // clamping needed because 0 >= 0).
            assertThat(capturedPageable.getPageNumber())
                    .as("page index — caller supplied 0; passes through unchanged")
                    .isZero();

            // And verify the Sort order. The findAll(Pageable) branch
            // does NOT have a method-name OrderBy segment, so the
            // ascending-by-cardNum order MUST be carried on the
            // Pageable's embedded Sort. The matching
            // OrderByCardNumAsc-suffixed derived query forces ASC
            // ordering at the SQL layer regardless, but the SUT
            // unconditionally also embeds the Sort to keep both
            // branches semantically symmetric.
            Sort.Order order = capturedPageable.getSort().getOrderFor("cardNum");
            assertThat(order)
                    .as("Sort must include 'cardNum' to match the CICS STARTBR "
                            + "ascending-key traversal in COCRDLIC.cbl")
                    .isNotNull();
            assertThat(order.isAscending())
                    .as("Sort direction must be ASC — matches the COBOL "
                            + "READNEXT-CARDDAT-FILE traversal direction")
                    .isTrue();
        }

        /**
         * Verifies that the page size is preserved across both the
         * admin-with-filter and non-admin-with-filter branches. The
         * COBOL contract is a fixed 7 rows regardless of whether the
         * operator scoped the browse by account; the Java target
         * must not vary the page size by branch.
         *
         * <p>COBOL: same {@code WS-MAX-SCREEN-LINES VALUE 7}
         * applies to the {@code STARTBR-CARDAIX-FILE} alternate-index
         * path as to the {@code STARTBR-CARDDAT-FILE} primary-key
         * path.</p>
         */
        @Test
        @DisplayName("listCards — filtered branch: page size is 7 too (per-account browse)")
        void listCards_filteredBranch_pageSizeIsSeven() {
            // COBOL: COCRDLIC:STARTBR-CARDAIX-FILE — per-account
            // browse via the CARDAIX alternate index on CARD-ACCT-ID.
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When — non-admin (isAdmin=false) with a filter MUST route
            // to the per-account finder.
            service.listCards(TEST_ACCOUNT_ID, false, 0);

            // Then — verify the per-account finder was invoked with
            // the COBOL-style page size on its Pageable.
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(cardRepository)
                    .findByCardAcctIdOrderByCardNumAsc(anyLong(), captor.capture());

            assertThat(captor.getValue().getPageSize())
                    .as("PAGE_SIZE preserved on filtered branch — COBOL "
                            + "WS-MAX-SCREEN-LINES VALUE 7 is unconditional "
                            + "regardless of CARDDAT vs CARDAIX traversal")
                    .isEqualTo(COBOL_WS_MAX_SCREEN_LINES);
        }
    }

    // =========================================================================
    // AcctIdFilter - dispatch between findAll and findByCardAcctIdOrderByCardNumAsc
    // =========================================================================

    /**
     * Verifies the conditional dispatch between the all-cluster
     * {@code findAll(Pageable)} branch (admin + no filter) and the
     * per-account
     * {@code findByCardAcctIdOrderByCardNumAsc(Long, Pageable)} branch
     * (admin-with-filter or non-admin-with-filter), plus the
     * short-circuit empty-result path (non-admin + no filter).
     *
     * <p>COBOL: {@code COCRDLIC:9000-READ-FORWARD} branches on the
     * {@code CDEMO-USRTYPE-ADMIN} / {@code FLG-ACCTFILTER-ISVALID}
     * combination:</p>
     * <ul>
     *   <li>Admin + no filter &rarr; {@code STARTBR FILE('CARDDAT')}
     *       (primary-key browse from LOW-VALUES)</li>
     *   <li>Admin + filter / non-admin + filter &rarr;
     *       {@code STARTBR FILE('CARDAIX')} (alternate-index browse
     *       keyed by CARD-ACCT-ID)</li>
     *   <li>Non-admin + no filter &rarr; never reached at runtime
     *       because the controller / sign-on routine forces an
     *       account-context for non-admin users; the Java target
     *       handles this defensively by short-circuiting to the
     *       verbatim {@code 88 WS-NO-RECORDS-FOUND} message.</li>
     * </ul>
     */
    @Nested
    @DisplayName("AcctIdFilter — dispatch between findAll and findByCardAcctIdOrderByCardNumAsc")
    class AcctIdFilter {

        /**
         * Verifies that admin + non-null account filter routes to the
         * per-account finder (replaces the CICS STARTBR on the
         * CARDAIX alternate index), and that the unfiltered
         * {@code findAll} is NOT invoked.
         */
        @Test
        @DisplayName("listCards — admin with acctId filter invokes findByCardAcctIdOrderByCardNumAsc")
        void listCards_adminWithAcctId_callsAcctFinder() {
            // COBOL: COCRDLIC:STARTBR-CARDAIX-FILE — admin with filter
            // routes to the per-account finder.
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            service.listCards(TEST_ACCOUNT_ID, true, 0);

            // Verify the derived query was invoked with the supplied
            // account filter.
            ArgumentCaptor<Long> acctCaptor = ArgumentCaptor.forClass(Long.class);
            verify(cardRepository)
                    .findByCardAcctIdOrderByCardNumAsc(acctCaptor.capture(), any(Pageable.class));
            assertThat(acctCaptor.getValue())
                    .as("accountFilter is passed verbatim to the per-account "
                            + "finder — replaces COBOL: STARTBR-CARDAIX-RID "
                            + "with RIDFLD(CARD-ACCT-ID) = " + TEST_ACCOUNT_ID)
                    .isEqualTo(TEST_ACCOUNT_ID);

            // And verify the all-cluster finder was NOT invoked — this
            // is the dispatch assertion, confirming the SUT routed
            // exclusively to the AIX-replacement query.
            verify(cardRepository, never()).findAll(any(Pageable.class));
        }

        /**
         * Verifies that non-admin + non-null account filter also
         * routes to the per-account finder. The non-admin caller's
         * account context (sourced from JWT claims in the Java
         * world; from {@code CDEMO-ACCT-ID} in the CICS COMMAREA in
         * the COBOL world) is the only legitimate way for a
         * non-admin user to see cards.
         */
        @Test
        @DisplayName("listCards — non-admin with acctId filter invokes findByCardAcctIdOrderByCardNumAsc")
        void listCards_nonAdminWithAcctId_callsAcctFinder() {
            // COBOL: COCRDLIC:CDEMO-USRTYPE-USER + CDEMO-ACCT-ID present
            // → STARTBR-CARDAIX-FILE.
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            service.listCards(TEST_ACCOUNT_ID, false, 0);

            verify(cardRepository)
                    .findByCardAcctIdOrderByCardNumAsc(anyLong(), any(Pageable.class));
            verify(cardRepository, never()).findAll(any(Pageable.class));
        }

        /**
         * Verifies that admin + null account filter routes to the
         * unfiltered {@code findAll(Pageable)} branch (replaces the
         * CICS STARTBR on the CARDDAT primary-key path).
         */
        @Test
        @DisplayName("listCards — admin without filter invokes findAll (all-cluster browse)")
        void listCards_adminWithoutFilter_callsFindAll() {
            // COBOL: COCRDLIC:CDEMO-USRTYPE-ADMIN + no CDEMO-ACCT-ID
            // → STARTBR-CARDDAT-FILE.
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            service.listCards(null, true, 0);

            verify(cardRepository).findAll(any(Pageable.class));
            verify(cardRepository, never())
                    .findByCardAcctIdOrderByCardNumAsc(anyLong(), any(Pageable.class));
        }

        /**
         * Verifies that an empty {@link Page} on the all-cluster
         * branch surfaces as a {@link CardListDto} with no rows,
         * {@code totalElements == 0}, and both {@code first} and
         * {@code last} flags set to {@code true}. The verbatim
         * "NO RECORDS FOUND FOR THIS SEARCH CONDITION." message is
         * not surfaced on the DTO body (it is logged at INFO level
         * per the service's documented contract), but clients can
         * infer the empty condition from {@code totalElements == 0}.
         *
         * <p>COBOL: {@code 88 WS-NO-RECORDS-FOUND VALUE 'NO RECORDS
         * FOUND FOR THIS SEARCH CONDITION.'} at
         * {@code COCRDLIC.cbl}:L121-L122.</p>
         */
        @Test
        @DisplayName("listCards — no results surfaces an empty DTO with totalElements=0")
        void listCards_noResults_returnsEmptyPage() {
            // COBOL: COCRDLIC:88 WS-NO-RECORDS-FOUND (L121-L122)
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, COBOL_WS_MAX_SCREEN_LINES)));

            CardListDto dto = service.listCards(null, true, 0);

            assertThat(dto).isNotNull();
            assertThat(dto.rows())
                    .as("empty result → empty rows list (no NPE, no nulls)")
                    .isNotNull()
                    .isEmpty();
            assertThat(dto.totalElements())
                    .as("totalElements == 0 on empty result")
                    .isZero();
            // Page.empty(pageable) yields totalPages == 0 because
            // there are no rows; this is the canonical Spring Data
            // empty-page representation.
            assertThat(dto.totalPages())
                    .as("totalPages == 0 on empty result")
                    .isZero();
            assertThat(dto.size())
                    .as("page size remains 7 even on empty result — "
                            + "the COBOL screen layout is unchanged")
                    .isEqualTo(COBOL_WS_MAX_SCREEN_LINES);
        }

        /**
         * Verifies that an empty {@link Page} on the per-account
         * branch (admin with a filter that yields no cards, or a
         * non-admin browsing an account that has no cards) surfaces
         * the same shape of empty DTO as the all-cluster branch,
         * preserving the {@code accountFilter} on the response so
         * the client can correlate the empty result with its
         * request.
         */
        @Test
        @DisplayName("listCards — filtered empty result echoes accountFilter on DTO")
        void listCards_filteredEmpty_echoesAccountFilter() {
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, COBOL_WS_MAX_SCREEN_LINES)));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, false, 0);

            assertThat(dto.rows()).isEmpty();
            assertThat(dto.totalElements()).isZero();
            // accountFilter MUST round-trip even on empty results so
            // the client can correlate response with request.
            assertThat(dto.accountFilter())
                    .as("accountFilter must be echoed back on empty result "
                            + "for client correlation")
                    .isEqualTo(TEST_ACCOUNT_ID);
        }

        /**
         * Verifies that a non-admin caller with no account filter
         * short-circuits to an empty DTO without invoking either
         * repository method. This matches the COBOL invariant: a
         * non-admin user's account context is mandatory; the
         * sign-on / COMMAREA-routing layer is responsible for
         * supplying it, but the Java service defends in depth by
         * returning the empty DTO + logging the
         * {@code 88 WS-NO-RECORDS-FOUND} message rather than risking
         * an unfiltered cross-account browse.
         */
        @Test
        @DisplayName("listCards — non-admin without filter short-circuits (no repo call)")
        void listCards_nonAdminWithoutFilter_shortCircuits() {
            // COBOL: defensive — non-admin without CDEMO-ACCT-ID
            // would never reach the STARTBR; the Java target enforces
            // the same invariant by short-circuiting.
            CardListDto dto = service.listCards(null, false, 0);

            assertThat(dto).isNotNull();
            assertThat(dto.rows()).isEmpty();
            assertThat(dto.totalElements()).isZero();
            assertThat(dto.totalPages()).isZero();
            // accountFilter is null on the response (was null on the
            // request) so the empty-DTO factory does not synthesize a
            // value.
            assertThat(dto.accountFilter()).isNull();

            // CRITICAL — verify NO repository call was issued. A
            // non-admin without filter MUST NOT cause an unfiltered
            // findAll, which would defeat the role-based access
            // control. This is the defensive guard that supplements
            // the controller-layer @PreAuthorize check.
            verifyNoInteractions(cardRepository);
        }
    }

    // =========================================================================
    // PanMasking - PCI-DSS PAN-masking + CVV-absence discipline (AAP §0.6.6)
    // =========================================================================

    /**
     * Verifies the PAN-masking discipline on the DTO output and the
     * structural / behavioural absence of the CVV.
     *
     * <p>PCI-DSS v4.0 Requirement 3.4.1 mandates masking of any PAN
     * when displayed; CardDemo's policy (AAP &sect;0.6.6) is to
     * display only the last 4 digits, with the leading 12 digits
     * replaced by {@code '*'} characters. PCI-DSS v4.0 Requirement
     * 3.2 mandates that Sensitive Authentication Data (SAD) -
     * including the CVV - MUST NOT be transmitted in any non-
     * authorization context. {@link CardListDto.CardRow} therefore
     * carries neither the unmasked PAN nor the CVV.</p>
     */
    @Nested
    @DisplayName("PanMasking — PCI-DSS PAN-masking + CVV-absence (AAP §0.6.6)")
    class PanMasking {

        /**
         * Verifies that every {@link CardListDto.CardRow} returned
         * by the SUT has its {@code cardNumber} masked to the
         * {@code ************XXXX} format. The fixture supplies a
         * recognisable Visa-shape test PAN
         * ({@link #TEST_PAN_PRIMARY}); the expected masked form
         * ({@link #TEST_PAN_PRIMARY_MASKED}) is asserted exactly.
         */
        @Test
        @DisplayName("listCards — output rows have PAN masked to ************XXXX")
        void listCards_outputCards_haveMaskedPan() {
            // COBOL: COCRDLIC:POPULATE-HEADER-INFO — the COBOL
            // version rendered the full PAN onto the 3270 screen
            // (the network was assumed physically isolated); the
            // Java target adds defense-in-depth PAN masking per
            // AAP §0.6.6 PCI-DSS v4.0 Requirement 3.4.1.
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(singleCardFixture)));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, false, 0);

            assertThat(dto.rows()).hasSize(1);
            CardListDto.CardRow row = dto.rows().get(0);

            // The CRITICAL PCI-DSS assertion: the row's cardNumber
            // is masked to ************XXXX, NOT the raw 16-digit
            // PAN that the fixture supplied.
            assertThat(row.cardNumber())
                    .as("PAN masked to 12 asterisks + last 4 digits per "
                            + "PCI-DSS v4.0 Requirement 3.4.1 (AAP §0.6.6)")
                    .isEqualTo(TEST_PAN_PRIMARY_MASKED);

            // And the un-masked PAN must NOT appear anywhere in the
            // row. This is the broader leak-prevention assertion —
            // if any future code path mistakenly placed the raw PAN
            // somewhere in the row, this catches it.
            assertThat(row.toString())
                    .as("Card row's toString MUST NOT contain the raw PAN")
                    .doesNotContain(TEST_PAN_PRIMARY);
        }

        /**
         * Verifies that all 7 rows in a full page are individually
         * masked (so the masking is per-row, not a happy-path
         * single-card behavior).
         */
        @Test
        @DisplayName("listCards — full 7-row page: every row's PAN is masked")
        void listCards_fullPage_allRowsMasked() {
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture,
                            PageRequest.of(0, COBOL_WS_MAX_SCREEN_LINES),
                            sevenCardsFixture.size()));

            CardListDto dto = service.listCards(null, true, 0);

            assertThat(dto.rows())
                    .as("the 7-row COBOL screen is filled exactly when 7 "
                            + "rows are available")
                    .hasSize(COBOL_WS_MAX_SCREEN_LINES);

            // For every row, verify the masked PAN starts with the
            // 12 asterisks and contains exactly the last 4 digits
            // of the corresponding fixture row's card number.
            for (int i = 0; i < dto.rows().size(); i++) {
                CardListDto.CardRow row = dto.rows().get(i);
                String fixturePan = sevenCardsFixture.get(i).getCardNum();
                String expectedMasked = "************"
                        + fixturePan.substring(fixturePan.length() - 4);
                assertThat(row.cardNumber())
                        .as("row %d cardNumber must be masked", i)
                        .isEqualTo(expectedMasked);
                // Also reaffirm the explicit pattern (12 asterisks
                // + 4 trailing chars) so the assertion remains
                // self-documenting.
                assertThat(row.cardNumber())
                        .as("row %d masked form has the 12-asterisk "
                                + "prefix", i)
                        .startsWith("************")
                        .hasSize(16);
            }
        }

        /**
         * Verifies that the {@link CardListDto.CardRow} record's
         * declared components do NOT include a {@code cvv}
         * (or any case-variant) field. This is a structural
         * PCI-DSS Requirement 3.2 assertion that protects against
         * future regressions where someone might add a CVV
         * component to the row.
         */
        @Test
        @DisplayName("CardRow record has no CVV component (PCI-DSS v4.0 Req 3.2)")
        void cardRow_recordComponents_doNotIncludeCvv() {
            RecordComponent[] components = CardListDto.CardRow.class.getRecordComponents();
            assertThat(components)
                    .as("CardRow must declare record components — verify it is a "
                            + "Java record per CardListDto contract")
                    .isNotNull()
                    .isNotEmpty();

            // PCI-DSS v4.0 Requirement 3.2 forbids persisting /
            // transmitting Sensitive Authentication Data (CVV)
            // outside the authorization request path. The list
            // response is NOT an authorization request, so the
            // CardRow MUST NOT carry the CVV.
            assertThat(Stream.of(components)
                    .map(RecordComponent::getName)
                    .map(String::toLowerCase)
                    .toList())
                    .as("CardRow MUST NOT declare any cvv-shaped record "
                            + "component (PCI-DSS v4.0 Requirement 3.2)")
                    .noneMatch(name -> name.contains("cvv")
                            || name.contains("cardCvv".toLowerCase())
                            || name.equals("securitycode"));
        }

        /**
         * Verifies that even when the underlying {@link Card} entity
         * carries the CVV ({@link #TEST_CVV_MARKER}), the CVV value
         * never appears in any string-representation of the DTO row.
         * This is the behavioural complement to the structural
         * {@link #cardRow_recordComponents_doNotIncludeCvv()} check.
         */
        @Test
        @DisplayName("listCards — output rows never expose the CVV value (PCI-DSS v4.0 Req 3.2)")
        void listCards_outputCards_doNotIncludeCvv() {
            // Fixture carries the recognisable TEST_CVV_MARKER (999).
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(singleCardFixture)));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, false, 0);

            // The CVV value must not appear in any rendering of the
            // row or its enclosing DTO. We assert across both
            // representations to make any future leak path visible.
            String rowString = dto.rows().get(0).toString();
            String dtoString = dto.toString();

            // The CVV is a 3-digit integer; render it the same way
            // the default Object#toString would, and assert absence.
            String cvvAsString = Integer.toString(TEST_CVV_MARKER);
            assertThat(rowString)
                    .as("CardRow.toString MUST NOT contain the CVV digits "
                            + "(PCI-DSS v4.0 Requirement 3.2)")
                    .doesNotContain(cvvAsString);
            assertThat(dtoString)
                    .as("CardListDto.toString MUST NOT contain the CVV "
                            + "digits (PCI-DSS v4.0 Requirement 3.2)")
                    .doesNotContain(cvvAsString);
        }

        /**
         * Verifies that the non-PCI-sensitive fields ({@code accountId},
         * {@code embossedName}, {@code expirationDate},
         * {@code activeStatus}) are projected from the {@link Card}
         * entity to the {@link CardListDto.CardRow} verbatim (no
         * transformation). The PAN is the only field that the SUT
         * transforms before placing on the DTO.
         */
        @Test
        @DisplayName("listCards — non-PCI fields project verbatim from Card to CardRow")
        void listCards_nonPciFields_projectVerbatim() {
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(singleCardFixture)));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, false, 0);

            CardListDto.CardRow row = dto.rows().get(0);

            // Non-PCI fields project verbatim — no masking, no
            // transformation. The COBOL source carried these on the
            // BMS map via the symbolic-map fields ACCTNOnI / (none) /
            // (none) / CRDSTSnI; the Java target surfaces all of
            // them on the DTO row for richer client rendering.
            assertThat(row.accountId())
                    .as("accountId projects verbatim from CARD-ACCT-ID")
                    .isEqualTo(singleCardFixture.getCardAcctId());
            assertThat(row.embossedName())
                    .as("embossedName projects verbatim from CARD-EMBOSSED-NAME")
                    .isEqualTo(singleCardFixture.getCardEmbossedName());
            assertThat(row.expirationDate())
                    .as("expirationDate projects verbatim from "
                            + "CARD-EXPIRAION-DATE (typo preserved at "
                            + "copybook level; Java field corrected per AAP)")
                    .isEqualTo(singleCardFixture.getCardExpirationDate());
            assertThat(row.activeStatus())
                    .as("activeStatus projects verbatim from CARD-ACTIVE-STATUS")
                    .isEqualTo(singleCardFixture.getCardActiveStatus());
        }
    }

    // =========================================================================
    // Pagination - page metadata projection from Spring Data Page to DTO
    // =========================================================================

    /**
     * Verifies the page-metadata projection from Spring Data's
     * {@link Page} envelope into the {@link CardListDto} response
     * envelope.
     *
     * <p>COBOL: {@code COCRDLIC} carries explicit working-storage
     * state for the page index ({@code WS-CA-SCREEN-NUM PIC 9(1)})
     * and the "last page" indicator
     * ({@code WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)}); the Java target
     * delegates all of this to the Spring Data {@link Page}
     * abstraction. The tests here verify that the projection from
     * the underlying {@link Page} into the DTO's {@code page} /
     * {@code totalElements} / {@code totalPages} / {@code first} /
     * {@code last} fields is faithful.</p>
     */
    @Nested
    @DisplayName("Pagination — page metadata projection from Spring Data Page")
    class Pagination {

        /**
         * Verifies that page 0 (the first page) is requested via
         * {@code PageRequest.of(0, 7)} and that the resulting DTO
         * reports {@code first == true}.
         */
        @Test
        @DisplayName("listCards — page=0 issues PageRequest.of(0, 7) and DTO.first == true")
        void listCards_pageZero_isFirstPage() {
            // Simulate a full first page that is NOT the last page
            // (more rows exist beyond this page).
            Page<Card> firstPage = new PageImpl<>(
                    sevenCardsFixture,
                    PageRequest.of(0, COBOL_WS_MAX_SCREEN_LINES),
                    14L);  // totalElements = 14, so 2 pages exist

            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(firstPage);

            CardListDto dto = service.listCards(null, true, 0);

            // Verify the page-index round-trip and the first/last flags.
            assertThat(dto.page())
                    .as("DTO.page reflects the Page#getNumber() of the "
                            + "underlying Spring Data Page envelope")
                    .isZero();
            assertThat(dto.first())
                    .as("page=0 → DTO.first == true (COBOL: WS-CA-FIRST-PAGE)")
                    .isTrue();
            assertThat(dto.last())
                    .as("page=0 with more pages → DTO.last == false")
                    .isFalse();
            assertThat(dto.totalElements())
                    .as("totalElements rounds trip from Page#getTotalElements")
                    .isEqualTo(14L);
            assertThat(dto.totalPages())
                    .as("totalPages rounds trip from Page#getTotalPages "
                            + "(14 rows / 7 page size = 2 pages)")
                    .isEqualTo(2);

            // Verify the Pageable that was actually passed to the
            // repository carried page=0.
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(cardRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber())
                    .as("page index 0 passes through unchanged to the "
                            + "repository's Pageable")
                    .isZero();
        }

        /**
         * Verifies that page 1 (the second page) is requested via
         * {@code PageRequest.of(1, 7)} and that the resulting DTO
         * reports {@code last == true} when no more pages exist
         * beyond this one.
         */
        @Test
        @DisplayName("listCards — page=1 issues PageRequest.of(1, 7) and DTO.last == true on final page")
        void listCards_pageOne_isLastPage() {
            // Simulate the final page (page 1 of 2): 7 rows on
            // page 0 + 3 rows on page 1 = 10 totalElements.
            List<Card> pageOneCards = Arrays.asList(
                    buildCard("4111111111110008", "HARRY HUNT"),
                    buildCard("4111111111110009", "IRIS IVES"),
                    buildCard("4111111111110010", "JACK JONES"));

            Page<Card> secondPage = new PageImpl<>(
                    pageOneCards,
                    PageRequest.of(1, COBOL_WS_MAX_SCREEN_LINES),
                    10L);  // totalElements = 10

            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(secondPage);

            CardListDto dto = service.listCards(null, true, 1);

            assertThat(dto.page())
                    .as("DTO.page reflects the Page#getNumber()")
                    .isEqualTo(1);
            assertThat(dto.first())
                    .as("page=1 → DTO.first == false (not the first page)")
                    .isFalse();
            assertThat(dto.last())
                    .as("page=1 with no more pages → DTO.last == true "
                            + "(COBOL: CA-LAST-PAGE-SHOWN)")
                    .isTrue();
            assertThat(dto.rows())
                    .as("final page has the 3 remaining rows")
                    .hasSize(3);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(cardRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber())
                    .as("page index 1 passes through unchanged")
                    .isEqualTo(1);
        }

        /**
         * Verifies that a negative {@code page} index is coerced to
         * {@code 0} (defensive normalisation per the SUT's
         * documented contract). The COBOL source declares
         * {@code WS-CA-SCREEN-NUM PIC 9(1)} (an unsigned 1-digit
         * field) so negative pages are impossible in the legacy
         * world; the Java target defends in depth.
         */
        @Test
        @DisplayName("listCards — negative page index is coerced to 0 (defensive)")
        void listCards_negativePage_coercedToZero() {
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // Caller supplies page = -3 — the SUT defensively clamps to 0.
            service.listCards(null, true, -3);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(cardRepository).findAll(captor.capture());

            assertThat(captor.getValue().getPageNumber())
                    .as("negative page index is coerced to 0 per the "
                            + "WS-CA-SCREEN-NUM PIC 9(1) unsigned contract")
                    .isZero();
        }

        /**
         * Verifies the rows-list projection: each {@link Card}
         * entity in the Page becomes exactly one
         * {@link CardListDto.CardRow} on the DTO, preserving the
         * order returned by the repository (ascending by
         * {@code cardNum} per the Sort embedded in the Pageable).
         */
        @Test
        @DisplayName("listCards — rows list preserves repository order")
        void listCards_rowsList_preservesOrder() {
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            CardListDto dto = service.listCards(null, true, 0);

            assertThat(dto.rows())
                    .as("rows list size matches Page content size")
                    .hasSize(sevenCardsFixture.size());

            // Verify the i-th row corresponds to the i-th fixture
            // card (by accountId since the PAN is masked on the row).
            for (int i = 0; i < dto.rows().size(); i++) {
                assertThat(dto.rows().get(i).accountId())
                        .as("row %d accountId preserved from fixture", i)
                        .isEqualTo(sevenCardsFixture.get(i).getCardAcctId());
                assertThat(dto.rows().get(i).embossedName())
                        .as("row %d embossedName preserved from fixture", i)
                        .isEqualTo(sevenCardsFixture.get(i).getCardEmbossedName());
            }
        }
    }

    // =========================================================================
    // AdminUserGate - role-based filtering (admin vs non-admin)
    // =========================================================================

    /**
     * Verifies the role-based filtering invariants:
     * <ul>
     *   <li><b>Admin sees all cards</b> &mdash; when {@code isAdmin}
     *       is {@code true} and no filter is supplied, the SUT
     *       performs an all-cluster browse via
     *       {@link CardRepository#findAll(Pageable)}.</li>
     *   <li><b>Non-admin sees only their account</b> &mdash; when
     *       {@code isAdmin} is {@code false}, the SUT MUST route to
     *       the per-account finder; the account ID is mandatory.</li>
     *   <li><b>Non-admin without account context</b> &mdash; the
     *       SUT short-circuits without any repository call, returning
     *       an empty DTO + logging the {@code 88 WS-NO-RECORDS-FOUND}
     *       message. This protects against an unintended
     *       cross-account browse if the controller layer's
     *       {@code @PreAuthorize} guard fails open.</li>
     * </ul>
     *
     * <p>COBOL: {@code COCRDLIC.cbl}:L4-L7 program header:</p>
     * <pre>
     *   * Function:    List Credit Cards
     *   *              a) All cards if no context passed and admin user
     *   *              b) Only the ones associated with ACCT in COMMAREA
     *   *                 if user is not admin
     * </pre>
     */
    @Nested
    @DisplayName("AdminUserGate — role-based filtering (admin vs non-admin)")
    class AdminUserGate {

        /**
         * Verifies that an admin user without any account context
         * sees all cards across all accounts — the COBOL
         * "a) All cards if no context passed and admin user" path.
         */
        @Test
        @DisplayName("listCards — admin user, no filter → sees all cards (findAll)")
        void listCards_adminUser_seesAllCards() {
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            CardListDto dto = service.listCards(null, true, 0);

            assertThat(dto.rows()).hasSize(sevenCardsFixture.size());
            verify(cardRepository).findAll(any(Pageable.class));
            verify(cardRepository, never())
                    .findByCardAcctIdOrderByCardNumAsc(anyLong(), any(Pageable.class));
        }

        /**
         * Verifies that a non-admin user with their account context
         * supplied sees only the cards owned by that account — the
         * COBOL "b) Only the ones associated with ACCT in COMMAREA"
         * path.
         */
        @Test
        @DisplayName("listCards — non-admin with acctId → sees only their account's cards")
        void listCards_standardUser_seesOnlyTheirAccount() {
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, false, 0);

            assertThat(dto.rows()).hasSize(sevenCardsFixture.size());
            // The filtered branch was invoked exclusively.
            verify(cardRepository)
                    .findByCardAcctIdOrderByCardNumAsc(anyLong(), any(Pageable.class));
            verify(cardRepository, never()).findAll(any(Pageable.class));
        }

        /**
         * Verifies that the {@code accountFilter} is preserved in
         * the response DTO regardless of the {@code isAdmin} flag.
         * Clients rely on this round-trip to correlate the response
         * with the original request.
         */
        @Test
        @DisplayName("listCards — accountFilter round-trips on the response DTO")
        void listCards_accountFilter_roundTrips() {
            when(cardRepository.findByCardAcctIdOrderByCardNumAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(singleCardFixture)));

            CardListDto dto = service.listCards(TEST_ACCOUNT_ID, true, 0);

            assertThat(dto.accountFilter())
                    .as("accountFilter MUST round-trip exactly as supplied")
                    .isEqualTo(TEST_ACCOUNT_ID);
        }
    }

    // =========================================================================
    // AuditLogging - documented collaboration boundary with AuditLogService
    // =========================================================================

    /**
     * Documents the {@link AuditLogService} collaboration boundary.
     *
     * <p>The current production {@link CardListService} constructor
     * takes <em>only</em> the {@link CardRepository}; the
     * {@link AuditLogService} mock is declared on this test class per
     * the sibling-test convention (see
     * {@code UserListServiceTest.AuditLogging}) so that:</p>
     *
     * <ol>
     *   <li>The test file's imports and {@code @Mock} field set are
     *       stable across the {@code service/} package and do not
     *       require change when sibling services are refactored to
     *       emit audit events.</li>
     *   <li>The audit-logging boundary is explicitly documented
     *       (and verified empty today) so any future enhancement
     *       that adds audit emission on list access has a known
     *       insertion point.</li>
     *   <li>The current "no interactions" assertion is a positive
     *       contract: this service is read-only and does not (yet)
     *       emit a "card list accessed" audit event.</li>
     * </ol>
     *
     * <p>When AAP &sect;0.6.6 is extended to require an audit emit
     * on list access (CloudTrail / OpenSearch indexing of card-list
     * read events), the assertions below become positive
     * {@code verify(auditLogService).logAuditEvent(...)} expectations.
     * Until then, this test holds the boundary at zero interactions
     * to flag any accidental coupling.</p>
     */
    @Nested
    @DisplayName("AuditLogging — collaboration boundary (no interactions today)")
    class AuditLogging {

        /**
         * Verifies that the SUT does NOT invoke any method on the
         * {@link AuditLogService} mock today. This is a positive
         * contract: the read-only paged-browse path is a simple
         * data-retrieval operation and is not in the
         * audit-emission scope per the current
         * {@link CardListService} contract.
         *
         * <p>When this service is enhanced to emit an audit event,
         * this test will be replaced by a
         * {@code verify(auditLogService).logAuditEvent(...)}
         * expectation with the {@code eventType} matching
         * "LIST" or "READ" and the resource type
         * "CARD".</p>
         */
        @Test
        @DisplayName("listCards — does not interact with AuditLogService today")
        void listCards_doesNotInteractWithAuditLogService() {
            // Stub the repository so the SUT can complete the
            // happy-path execution.
            when(cardRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(sevenCardsFixture));

            service.listCards(null, true, 0);

            // The boundary assertion: zero interactions on the
            // audit-log mock. If the SUT is changed to emit an
            // audit event, this test will fail with a clear
            // "wanted no interactions but got: ..." message, which
            // is the desired signal — the test must then be updated
            // to the positive verify(...) form documented above.
            verifyNoInteractions(auditLogService);
        }

        /**
         * Verifies that even the empty-result short-circuit path
         * (non-admin without account context) does not touch the
         * audit-log mock. This is a defense-in-depth check: even
         * the operationally interesting "denied" case does not
         * (yet) emit a security-audit event from this service.
         * (When AAP &sect;0.6.6 is extended to require a
         * SECURITY_DENIED audit event from this path, this test
         * will be updated.)
         */
        @Test
        @DisplayName("listCards — empty-result short-circuit also does not interact with AuditLogService")
        void listCards_emptyShortCircuit_doesNotInteractWithAuditLogService() {
            // Non-admin without filter → short-circuits without
            // any repository or audit-log interaction.
            service.listCards(null, false, 0);

            verifyNoInteractions(auditLogService);
            verifyNoInteractions(cardRepository);
        }
    }
}
