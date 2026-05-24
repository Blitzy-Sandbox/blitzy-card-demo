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
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link CardDetailService}.
 *
 * <p><b>COBOL provenance.</b> {@link CardDetailService} is the Java
 * target for the COBOL/CICS program {@code app/cbl/COCRDSLC.cbl}
 * (CICS transaction id {@code CCDL}, mapset {@code COCRDSL}, map
 * {@code CCRDSLA}). The COBOL source performs a single read-only
 * {@code EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)
 * INTO(CARD-RECORD)} (it is {@em not} a {@code READ UPDATE}) against
 * the {@code CARDDAT} VSAM KSDS cluster, populates the
 * {@code CCRDSLAO} output redefine of the {@code CCRDSLA} BMS
 * symbolic map, and issues {@code EXEC CICS SEND MAP} to render the
 * record as a read-only display to a 3270 terminal. In the Java
 * target this becomes:</p>
 * <ol>
 *   <li>field-level validation of the supplied card number (16-digit
 *       numeric edit; COBOL paragraph {@code 2220-EDIT-CARD} at
 *       {@code COCRDSLC.cbl} lines 685&ndash;724);</li>
 *   <li>cache-aside lookup against ElastiCache Redis via
 *       {@link CacheService} (AAP &sect;0.7.1 net-new capability);</li>
 *   <li>JPA {@code findById} lookup against {@link CardRepository}
 *       (replaces COBOL paragraph {@code 9100-GETCARD-BYACCTCARD}
 *       at COCRDSLC.cbl lines 736&ndash;777);</li>
 *   <li>assembly of a {@link CardDetailDto} response (replaces COBOL
 *       paragraph {@code 1200-SETUP-SCREEN-VARS});</li>
 *   <li>cache populate-on-miss; return DTO to the controller layer
 *       which serializes it as the JSON body of
 *       {@code GET /api/cards/{cardNumber}}.</li>
 * </ol>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Happy path</b> &mdash; a valid 16-digit card number returns
 *       a {@link CardDetailDto} populated from the {@link Card}
 *       entity. (COBOL: {@code 9100-GETCARD-BYACCTCARD} +
 *       {@code 1200-SETUP-SCREEN-VARS}.)</li>
 *   <li><b>PCI-DSS: PAN masking</b> &mdash; the unmasked PAN may live
 *       in {@link CardDetailDto#cardNumber()} as part of the in-memory
 *       request payload, but {@link CardDetailDto#toString()} MUST
 *       mask it to {@code ************XXXX} so that any accidental
 *       logging of the DTO never exposes the PAN (AAP &sect;0.6.6
 *       PCI-DSS v4.0 Requirement 3.4.1).</li>
 *   <li><b>PCI-DSS: CVV exclusion</b> &mdash; {@link CardDetailDto}
 *       MUST NOT carry the CVV as a record component (verified via
 *       reflection over the record components); the
 *       {@link Card#getCardCvvCd()} value MUST NOT appear anywhere
 *       in {@link CardDetailDto#toString()} (AAP &sect;0.6.6 PCI-DSS
 *       v4.0 Requirement 3.2 &mdash; Sensitive Authentication Data
 *       MUST NEVER be exposed).</li>
 *   <li><b>Cache-aside hit</b> &mdash; a cache hit short-circuits the
 *       database read; {@link CardRepository#findById} is NEVER
 *       invoked (AAP &sect;0.7.1).</li>
 *   <li><b>Cache-aside miss</b> &mdash; a cache miss falls through to
 *       {@link CardRepository#findById}; on success the DTO is
 *       populated into the cache via
 *       {@link CacheService#put(String, String, Object, Duration)}
 *       under namespace {@code "card-detail"}.</li>
 *   <li><b>Record not found</b> &mdash; missing card row surfaces as
 *       {@link RecordNotFoundException} (HTTP 404 via
 *       {@code GlobalExceptionHandler}; preserves COBOL
 *       {@code DFHRESP(NOTFND)} / {@code FILE STATUS '23'} semantic
 *       from COCRDSLC.cbl).</li>
 *   <li><b>Validation</b> &mdash; null/blank, wrong-length, and
 *       non-numeric card numbers surface as
 *       {@link ValidationException} BEFORE any cache or repository
 *       lookup (COBOL: {@code 2220-EDIT-CARD}; "Card number if
 *       supplied must be a 16 digit number").</li>
 *   <li><b>Audit isolation</b> &mdash; {@link CardDetailService} is
 *       intentionally read-only and does NOT inject
 *       {@link AuditLogService} (its 2-arg constructor takes only
 *       {@link CardRepository} and {@link CacheService}). The
 *       AuditLogging tests therefore verify that no audit events are
 *       emitted from view operations, matching the COBOL
 *       {@code COCRDSLC.cbl} which performs a single read with no
 *       audit-trail write. The {@link AuditLogService} {@code @Mock}
 *       is declared so that any future addition of audit emission to
 *       this service is caught by the negative
 *       {@link org.mockito.Mockito#verifyNoInteractions} check.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack or Testcontainers are
 * involved.</p>
 *
 * @see CardDetailService
 * @see CardDetailDto
 * @see CardRepository
 * @see CacheService
 * @see RecordNotFoundException
 */
// COBOL: COCRDSLC.cbl — single-card detail view (TRANID 'CCDL', map 'CCRDSLA')
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService — COCRDSLC single-card view")
class CardDetailServiceTest {

    // ==================================================================
    // Test constants — shared across every nested group
    //
    // Per AAP §0.7.3 traceability discipline, every constant is named
    // after the COBOL field it represents:
    //   CARD_NUMBER      ← CVACT02Y.cpy:L5 CARD-NUM PIC X(16)
    //   LAST4            ← last 4 digits used by PAN-masking assertions
    //   ACCOUNT_ID       ← CVACT02Y.cpy:L6 CARD-ACCT-ID PIC 9(11)
    //   CVV              ← CVACT02Y.cpy:L7 CARD-CVV-CD PIC 9(03)
    //                       (PCI-DSS sensitive — never appears in DTO)
    //   EMBOSSED_NAME    ← CVACT02Y.cpy:L8 CARD-EMBOSSED-NAME PIC X(50)
    //   EXPIRATION_DATE  ← CVACT02Y.cpy:L9 CARD-EXPIRAION-DATE PIC X(10)
    //                       (COBOL typo corrected to "expiration" per AAP §0.4.1)
    //   ACTIVE_STATUS    ← CVACT02Y.cpy:L10 CARD-ACTIVE-STATUS PIC X(01)
    // ==================================================================

    private static final String CARD_NUMBER = "4111111111111111";
    private static final String LAST4 = "1111";
    private static final String MASKED_PAN = "************" + LAST4;
    private static final Long ACCOUNT_ID = 11_111_111_111L;
    private static final Integer CVV = 123;
    private static final String EMBOSSED_NAME = "JOHN DOE";
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2030, 12, 31);
    private static final String ACTIVE_STATUS = "Y";

    /**
     * Cache namespace used by {@link CardDetailService} for the
     * cache-aside protocol. Mirrors the {@code CACHE_NAMESPACE}
     * constant inside the service. Per AAP &sect;0.7.1 ("ElastiCache
     * (Redis) used for account balance caching &mdash; cache-aside
     * pattern with TTL aligned to transaction frequency").
     */
    private static final String CACHE_NAMESPACE = "card-detail";

    // ==================================================================
    // Mocks and System Under Test (SUT)
    //
    // CardDetailService.constructor(CardRepository, CacheService) takes
    // only two collaborators per AAP §0.4.1 (one-service-per-COBOL-
    // program mapping; COCRDSLC is a read-only view).
    //
    // The AuditLogService mock is declared but NOT injected by
    // @InjectMocks because CardDetailService doesn't accept it as a
    // constructor parameter. Mockito's @InjectMocks tolerates extra
    // @Mock fields — the AuditLogService mock is used by the
    // AuditLogging @Nested group to assert via verifyNoInteractions
    // that view operations emit NO audit events (matching the COBOL
    // COCRDSLC.cbl which performs a single CICS READ with no
    // audit-trail write).
    // ==================================================================

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CardDetailService service;

    /**
     * Card test fixture populated in {@link #setUp()} and returned by
     * the {@code cardRepository.findById(...)} mock for happy-path and
     * cache-miss tests. Carries the full PAN, ACCOUNT-ID, CVV (CVV is
     * present on the entity per the COBOL source layout, but MUST NOT
     * appear in any DTO emitted by {@link CardDetailService} per AAP
     * &sect;0.6.6 PCI-DSS rules), embossed name, expiration date, and
     * active status, plus a deterministic {@code version=0L} for the
     * @Version optimistic-lock field.
     */
    private Card card;

    /**
     * Initialize the test fixture before each test method.
     *
     * <p>Constructs a {@link Card} entity with every persistent field
     * set to a known value &mdash; the test fixture must include the
     * CVV ({@link #CVV}) so that the {@code CardDetailService} has the
     * opportunity (and the failing tests confirm it does NOT take the
     * opportunity) to leak the CVV into the DTO. The COBOL field name
     * is {@code CARD-EXPIRAION-DATE} (with the source-original "typo"
     * preserved at the storage layer); the Java setter uses the
     * AAP-mandated corrected spelling {@code setCardExpirationDate}
     * per AAP &sect;0.4.1.</p>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COCRDSLC:1200-SETUP-SCREEN-VARS — populate the in-memory
        // CARD-RECORD that the service will hydrate from CardRepository.
        // The fixture mirrors CVACT02Y.cpy:L4-L11 byte layout.
        card = new Card();
        // COBOL: CVACT02Y.cpy:L5 CARD-NUM PIC X(16) — PK; PCI-sensitive
        card.setCardNum(CARD_NUMBER);
        // COBOL: CVACT02Y.cpy:L6 CARD-ACCT-ID PIC 9(11) — FK to accounts
        card.setCardAcctId(ACCOUNT_ID);
        // COBOL: CVACT02Y.cpy:L7 CARD-CVV-CD PIC 9(03) — SAD per PCI-DSS;
        // included on fixture so the CVV-exclusion tests have a real value
        // to detect should the service ever leak it into the DTO/audit.
        card.setCardCvvCd(CVV);
        // COBOL: CVACT02Y.cpy:L8 CARD-EMBOSSED-NAME PIC X(50)
        card.setCardEmbossedName(EMBOSSED_NAME);
        // COBOL: CVACT02Y.cpy:L9 CARD-EXPIRAION-DATE PIC X(10) —
        // setter uses corrected "expiration" spelling per AAP §0.4.1.
        // java.time.LocalDate replaces COBOL LE CEEDAYS date primitives
        // per AAP §0.5.2 dependency-removal table.
        card.setCardExpirationDate(EXPIRATION_DATE);
        // COBOL: CVACT02Y.cpy:L10 CARD-ACTIVE-STATUS PIC X(01)
        card.setCardActiveStatus(ACTIVE_STATUS);
        // JPA @Version (no COBOL equivalent) — initialized to 0L for
        // a freshly persisted row per Card.java JavaDoc.
        card.setVersion(0L);
    }

    // ====================================================================
    // @Nested test groups (per the agent_prompt Phase 3 structure)
    // ====================================================================

    /**
     * Happy-path tests &mdash; valid 16-digit card number resolves to
     * a fully populated {@link CardDetailDto} returned by
     * {@code CardDetailService.getCardDetail(cardNumber)}.
     *
     * <p>COBOL provenance: corresponds to the success path through
     * {@code COCRDSLC.cbl} paragraphs {@code 0000-MAIN} &rarr;
     * {@code 2220-EDIT-CARD} (pass) &rarr;
     * {@code 9100-GETCARD-BYACCTCARD} (CICS READ returns
     * {@code DFHRESP(NORMAL)}) &rarr; {@code 1200-SETUP-SCREEN-VARS}
     * &rarr; {@code SEND-MAP CCRDSLA}.</p>
     */
    @Nested
    @DisplayName("Happy path — valid card returns populated DTO")
    class HappyPath {

        /**
         * Verifies the canonical success path: a 16-digit numeric card
         * number stubs through cache-miss + repository-hit and returns
         * a non-null {@link CardDetailDto} with the full set of fields
         * populated from the {@link Card} entity.
         *
         * <p>The mock for {@link CacheService#get(String, String, Class)}
         * is stubbed to return {@link Optional#empty()} (cache miss);
         * the mock for {@link CardRepository#findById(Object)} is
         * stubbed to return {@code Optional.of(card)}. The service is
         * expected to return a DTO with the corresponding values.</p>
         */
        // COBOL: COCRDSLC:9100-GETCARD-BYACCTCARD — DFHRESP(NORMAL) branch
        @Test
        @DisplayName("getCardDetail returns DTO for valid card number")
        void getCardDetail_validCardNum_returnsDto() {
            // Arrange — cache miss → repo hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            CardDetailDto result = service.getCardDetail(CARD_NUMBER);

            // Assert — non-null DTO with every business field present
            assertThat(result).isNotNull();
            assertThat(result.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(result.expirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(result.activeStatus()).isEqualTo(ACTIVE_STATUS);
        }

        /**
         * Verifies that the DTO returned for a valid card has its PAN
         * masked when rendered as text. The DTO record holds the
         * unmasked PAN in {@link CardDetailDto#cardNumber()} for the
         * duration of the request (matching the COBOL working-storage
         * behavior where the unmasked {@code CARD-NUM} is held in
         * memory during request processing), but
         * {@link CardDetailDto#toString()} MUST mask it to the form
         * {@code ************XXXX} so that any accidental log emission
         * of the DTO never exposes the regulated PAN per AAP
         * &sect;0.6.6 PCI-DSS v4.0 Requirement 3.4.1.
         */
        // COBOL: AAP §0.6.6 — PCI-DSS v4.0 Requirement 3.4.1 mandate
        @Test
        @DisplayName("returned DTO has masked PAN in toString() output")
        void getCardDetail_returnedDto_hasMaskedPan() {
            // Arrange — happy path
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            CardDetailDto result = service.getCardDetail(CARD_NUMBER);

            // Assert — toString() must mask the PAN (last 4 visible).
            // The masking pattern matches PCI-DSS v4.0 Requirement
            // 3.4.1 (display only the last 4 digits). The leading 12
            // characters MUST be asterisks.
            String stringForm = result.toString();
            assertThat(stringForm).contains(MASKED_PAN);
            // Defense in depth: the unmasked PAN MUST NOT appear in
            // the toString() output. This catches any accidental
            // regression where the masking is dropped while leaving
            // the toString override in place.
            assertThat(stringForm).doesNotContain(CARD_NUMBER);
        }

        /**
         * Verifies the most critical PCI-DSS rule (AAP &sect;0.6.6,
         * AAP &sect;0.7.2): the CVV MUST NEVER appear anywhere on the
         * {@link CardDetailDto}.
         *
         * <p>This is verified in three complementary ways:</p>
         * <ol>
         *   <li><b>Structural</b> &mdash; reflection over the record
         *       components confirms no component is named "cvv",
         *       "cardCvv", "cardCvvCd", or any similar variant.</li>
         *   <li><b>Textual</b> &mdash; the {@link CardDetailDto#toString()}
         *       rendering does NOT include the numeric CVV value
         *       ({@code "123"}) anywhere.</li>
         *   <li><b>Source fixture verification</b> &mdash; the
         *       {@link Card} fixture passed via the mock has its CVV
         *       set to {@link #CVV} so a service-side leak would
         *       cause the textual check to fail; the repository mock
         *       is verified to have been invoked, confirming the CVV
         *       was actually loaded into the entity that the service
         *       processed.</li>
         * </ol>
         */
        // COBOL: AAP §0.6.6 — PCI-DSS v4.0 Requirement 3.2 (CVV/SAD)
        @Test
        @DisplayName("returned DTO does not expose the CVV (PCI-DSS Req 3.2)")
        void getCardDetail_returnedDto_doesNotContainCvv() {
            // Arrange — happy path with CVV-bearing Card
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            CardDetailDto result = service.getCardDetail(CARD_NUMBER);

            // Assert 1 — structural check via reflection: no record
            // component is named "cvv", "cardCvv", "cardCvvCd".
            // CardDetailDto is a record; getRecordComponents()
            // exposes every component declared on the canonical
            // constructor.
            RecordComponent[] components = CardDetailDto.class
                    .getRecordComponents();
            assertThat(components).isNotNull();
            // Defense-in-depth: log-friendly listing of components so
            // a failure shows which fields the DTO does carry.
            String[] componentNames = Arrays.stream(components)
                    .map(RecordComponent::getName)
                    .toArray(String[]::new);
            assertThat(componentNames)
                    .doesNotContain("cvv", "cardCvv", "cardCvvCd",
                            "cardCVV", "CVV", "Cvv");

            // Assert 2 — textual check: the numeric CVV value MUST
            // NOT appear in the toString() rendering of the DTO. The
            // CVV value 123 is intentionally distinct from any other
            // numeric field on the DTO; if it leaked into the DTO's
            // textual form this assertion would fail.
            assertThat(result.toString()).doesNotContain(CVV.toString());

            // Assert 3 — the repository was invoked, confirming the
            // CVV-bearing entity was actually loaded by the service
            // (a leak would otherwise be missed if the repo were
            // never called).
            verify(cardRepository).findById(CARD_NUMBER);
        }
    }

    /**
     * Cache-aside tests &mdash; verify the read-path of the cache-aside
     * protocol mandated by AAP &sect;0.7.1 ("ElastiCache (Redis) used
     * for account balance caching &mdash; cache-aside pattern with TTL
     * aligned to transaction frequency"):
     * <ul>
     *   <li>cache hit short-circuits the database read;</li>
     *   <li>cache miss falls through to the database read and
     *       populates the cache afterwards.</li>
     * </ul>
     *
     * <p>This protocol is net-new to the Java target; the COBOL
     * COCRDSLC.cbl has no caching whatsoever (every read hits VSAM
     * CARDDAT directly). The cache layer absorbs hot-account read
     * load on the AWS target per AAP &sect;0.6.6 ("ElastiCache Redis
     * reduces RDS read load by caching high-frequency account balance
     * lookups").</p>
     */
    @Nested
    @DisplayName("Cache-aside — read-through with populate-on-miss")
    class CacheAside {

        /**
         * Verifies the cache-hit short-circuit: when the cache
         * returns {@link Optional#of} a previously-stored DTO, the
         * service returns that DTO immediately without ever
         * consulting the repository.
         *
         * <p>This is the primary cost-reduction outcome of the
         * cache-aside design &mdash; the high-frequency read traffic
         * is served by Redis rather than RDS.</p>
         */
        // COBOL: AAP §0.7.1 — cache-aside hit path (net-new vs COCRDSLC)
        @Test
        @DisplayName("cache hit returns cached DTO without repository call")
        void getCardDetail_cacheHit_returnsFromCache() {
            // Arrange — pre-built DTO stored "in cache"
            CardDetailDto cachedDto = new CardDetailDto(
                    CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                    EXPIRATION_DATE, ACTIVE_STATUS);
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.of(cachedDto));

            // Act
            CardDetailDto result = service.getCardDetail(CARD_NUMBER);

            // Assert — DTO returned is exactly the cached one
            assertThat(result).isSameAs(cachedDto);
            // Assert — the database was NEVER queried (the headline
            // cost-reduction guarantee of cache-aside)
            verify(cardRepository, never()).findById(anyString());
            // Assert — the service did not attempt to re-populate the
            // cache on a hit (would otherwise be redundant write
            // traffic to Redis on every read)
            verify(cacheService, never()).put(anyString(), anyString(),
                    any(), any(Duration.class));
        }

        /**
         * Verifies the cache-miss fall-through: when the cache
         * returns {@link Optional#empty}, the service loads the
         * entity from the repository, builds the DTO, and stores it
         * in the cache via
         * {@link CacheService#put(String, String, Object, Duration)}
         * with the correct namespace and a non-null TTL.
         *
         * <p>The TTL value (5 minutes per
         * {@code CardDetailService.CACHE_TTL}) is verified to be
         * non-null and positive but the exact value is intentionally
         * not asserted &mdash; the test verifies the contract, not
         * the choice of constant, so a future TTL adjustment for
         * operational tuning does not break tests.</p>
         */
        // COBOL: AAP §0.7.1 — cache-aside miss path (net-new vs COCRDSLC)
        @Test
        @DisplayName("cache miss loads from repository and populates cache")
        void getCardDetail_cacheMiss_loadsFromRepoAndCaches() {
            // Arrange — cache miss + repository hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            CardDetailDto result = service.getCardDetail(CARD_NUMBER);

            // Assert — DTO returned has the expected field values
            assertThat(result).isNotNull();
            assertThat(result.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);

            // Assert — the repository was invoked (cache miss path)
            verify(cardRepository).findById(CARD_NUMBER);
            // Assert — the cache was populated with the freshly-built
            // DTO under the correct namespace + key + non-null TTL
            verify(cacheService).put(eq(CACHE_NAMESPACE),
                    eq(CARD_NUMBER), any(CardDetailDto.class),
                    any(Duration.class));
        }
    }

    /**
     * Record-not-found tests &mdash; verify that a missing card row
     * surfaces as a typed {@link RecordNotFoundException} (HTTP 404
     * via {@code GlobalExceptionHandler}) that preserves the COBOL
     * {@code FILE STATUS '23'} (NOTFND) semantic from
     * {@code COCRDSLC.cbl}.
     *
     * <p>COBOL provenance: corresponds to the
     * {@code WHEN DFHRESP(NOTFND)} branch in COCRDSLC.cbl paragraph
     * {@code 9100-GETCARD-BYACCTCARD} at lines 755&ndash;761 which
     * sets the {@code DID-NOT-FIND-ACCTCARD-COMBO} message and
     * returns to the user with the input still on the screen.</p>
     */
    @Nested
    @DisplayName("Record not found — COBOL FILE STATUS '23' → HTTP 404")
    class RecordNotFound {

        /**
         * Verifies the not-found path: when the repository returns
         * {@link Optional#empty}, the service throws
         * {@link RecordNotFoundException} via the
         * {@code .orElseThrow(...)} chain.
         */
        // COBOL: COCRDSLC:9100-GETCARD-BYACCTCARD — DFHRESP(NOTFND) branch
        @Test
        @DisplayName("missing card raises RecordNotFoundException")
        void getCardDetail_cardNotFound_throwsRecordNotFound() {
            // Arrange — cache miss + repository miss
            String unknownCardNumber = "4000400040004000";
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(unknownCardNumber),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(unknownCardNumber))
                    .thenReturn(Optional.empty());

            // Act + Assert — the typed domain exception is thrown
            assertThatThrownBy(() -> service.getCardDetail(unknownCardNumber))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert — the cache was NOT populated on a miss (a
            // not-found row must never be cached — that would
            // produce stale negative results and defeat the purpose
            // of cache-aside)
            verify(cacheService, never()).put(anyString(), anyString(),
                    any(), any(Duration.class));
        }

        /**
         * Verifies PCI-DSS-safe error rendering: the exception message
         * produced by the not-found path MUST contain only the masked
         * PAN, never the raw card number. The
         * {@link CardDetailService#getCardDetail(String)} implementation
         * uses {@code maskPan(trimmed)} in the exception message
         * &mdash; the unmasked PAN must NOT propagate to the
         * {@code RecordNotFoundException.getMessage()} text per AAP
         * &sect;0.6.6.
         */
        // COBOL: AAP §0.6.6 — PAN must be masked in error envelopes
        @Test
        @DisplayName("not-found exception message contains only masked PAN")
        void getCardDetail_cardNotFound_messageHasMaskedPan() {
            // Arrange — cache miss + repository miss
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            // Act + Assert — exception's message embeds the MASKED PAN
            assertThatThrownBy(() -> service.getCardDetail(CARD_NUMBER))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(MASKED_PAN)
                    .hasMessageNotContaining(CARD_NUMBER);
        }
    }

    /**
     * Validation tests &mdash; verify the input edits that mirror the
     * COBOL paragraph {@code 2220-EDIT-CARD} in {@code COCRDSLC.cbl}
     * at lines 685&ndash;724 ("CARD ID FILTER, IF SUPPLIED MUST BE A
     * 16 DIGIT NUMBER"). Every failed edit must surface as a
     * {@link ValidationException} BEFORE any cache or repository
     * lookup &mdash; an invalid input must never burn cache or DB
     * read budget.
     *
     * <p>COBOL provenance: corresponds to
     * {@code COCRDSLC.cbl:2220-EDIT-CARD} which sets
     * {@code SET INPUT-ERROR TO TRUE} and emits the
     * {@code SEARCHED-CARD-NOT-NUMERIC} return message on any failed
     * edit.</p>
     */
    @Nested
    @DisplayName("Validation — COCRDSLC:2220-EDIT-CARD input edits")
    class Validation {

        /**
         * Null input must surface as {@link ValidationException}
         * (corresponds to COBOL {@code IF CC-CARD-NUM EQUAL
         * LOW-VALUES}).
         */
        // COBOL: COCRDSLC:2220-EDIT-CARD — IF CC-CARD-NUM EQUAL LOW-VALUES
        @Test
        @DisplayName("null cardNumber raises ValidationException")
        void getCardDetail_nullCardNumber_throwsValidation() {
            assertThatThrownBy(() -> service.getCardDetail(null))
                    .isInstanceOf(ValidationException.class);
            // Neither cache nor repository should be touched.
            verify(cacheService, never()).get(anyString(), anyString(),
                    any(Class.class));
            verify(cardRepository, never()).findById(anyString());
        }

        /**
         * Blank input must surface as {@link ValidationException}
         * (corresponds to COBOL {@code IF CC-CARD-NUM EQUAL SPACES}).
         */
        // COBOL: COCRDSLC:2220-EDIT-CARD — IF CC-CARD-NUM EQUAL SPACES
        @Test
        @DisplayName("blank cardNumber raises ValidationException")
        void getCardDetail_blankCardNumber_throwsValidation() {
            assertThatThrownBy(() -> service.getCardDetail("                "))
                    .isInstanceOf(ValidationException.class);
            verify(cacheService, never()).get(anyString(), anyString(),
                    any(Class.class));
            verify(cardRepository, never()).findById(anyString());
        }

        /**
         * 15-digit input must surface as {@link ValidationException}
         * (corresponds to COBOL "Card number if supplied must be a
         * 16 digit number" — strict length check on
         * {@code CC-CARD-NUM PIC X(16)}).
         */
        // COBOL: COCRDSLC:2220-EDIT-CARD — strict 16-digit length check
        @Test
        @DisplayName("short cardNumber (15 digits) raises ValidationException")
        void getCardDetail_shortCardNumber_throwsValidation() {
            assertThatThrownBy(() -> service.getCardDetail("411111111111111"))
                    .isInstanceOf(ValidationException.class);
            verify(cacheService, never()).get(anyString(), anyString(),
                    any(Class.class));
            verify(cardRepository, never()).findById(anyString());
        }

        /**
         * Non-numeric input must surface as {@link ValidationException}
         * (corresponds to COBOL {@code IF CC-CARD-NUM IS NOT NUMERIC}
         * &rarr; {@code SET FLG-CARDFILTER-NOT-OK TO TRUE}; "CARD ID
         * FILTER, IF SUPPLIED MUST BE A 16 DIGIT NUMBER").
         */
        // COBOL: COCRDSLC:2220-EDIT-CARD — IF CC-CARD-NUM IS NOT NUMERIC
        @Test
        @DisplayName("non-numeric cardNumber raises ValidationException")
        void getCardDetail_nonNumericCardNumber_throwsValidation() {
            // 16 chars, 15 digits plus one letter — passes length but
            // fails numeric edit.
            assertThatThrownBy(() -> service.getCardDetail("411111111111111A"))
                    .isInstanceOf(ValidationException.class);
            verify(cacheService, never()).get(anyString(), anyString(),
                    any(Class.class));
            verify(cardRepository, never()).findById(anyString());
        }
    }

    /**
     * Audit-logging tests &mdash; verify that
     * {@link CardDetailService} does NOT emit audit events through
     * {@link AuditLogService} on either the success or the
     * record-not-found path.
     *
     * <p><b>Why a negative assertion.</b> {@link CardDetailService}'s
     * constructor takes only {@link CardRepository} and
     * {@link CacheService} per AAP &sect;0.4.1 ({@code COCRDSLC.cbl}
     * is a read-only single-card view). The COBOL source performs a
     * single {@code EXEC CICS READ FILE('CARDDAT')} with no
     * audit-trail write &mdash; the read-only view is not in the
     * COBOL audit scope. The Java target preserves that design: write
     * paths ({@code CardUpdateService}, {@code TransactionAddService},
     * {@code BillPaymentService}, etc.) emit audit events; read paths
     * do not.</p>
     *
     * <p>The {@link AuditLogService} {@code @Mock} is declared so that
     * any future regression that adds audit emission to this service
     * is caught immediately by these negative assertions &mdash; the
     * test fails the moment the service starts calling any
     * {@code log*} or {@code audit*} method on the audit log adapter.
     * This guards against an accidental "let's just add audit
     * everywhere" change that would silently flood
     * OpenSearch with high-cardinality read-event documents and
     * incur unnecessary cost.</p>
     */
    @Nested
    @DisplayName("Audit isolation — read-only view emits no audit events")
    class AuditLogging {

        /**
         * Verifies that the success path emits zero audit events.
         *
         * <p>The {@link Mockito#verifyNoInteractions(Object...)} call
         * is the strongest possible assertion &mdash; it fires if any
         * method (audit, security, transaction, batch) is invoked on
         * the mock; this protects against future signature changes on
         * {@link AuditLogService} that would otherwise need
         * per-method {@code never()} verifications.</p>
         */
        // COBOL: COCRDSLC.cbl — view path performs no audit-trail write
        @Test
        @DisplayName("happy-path emits no audit events (read-only view)")
        void getCardDetail_emitsNoAudit_onSuccess() {
            // Arrange — cache miss → repo hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            service.getCardDetail(CARD_NUMBER);

            // Assert — the AuditLogService mock had ZERO interactions
            verifyNoInteractions(auditLogService);
        }

        /**
         * Verifies that the record-not-found path emits zero audit
         * events. The COBOL source for the {@code DFHRESP(NOTFND)}
         * branch surfaces a screen-level error message but emits no
         * audit-trail entry; the Java target preserves that
         * behavior.
         */
        // COBOL: COCRDSLC.cbl — DFHRESP(NOTFND) path performs no audit emit
        @Test
        @DisplayName("not-found path emits no audit events")
        void getCardDetail_emitsNoAudit_onNotFound() {
            // Arrange — cache miss + repository miss
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            // Act — the service throws RecordNotFoundException;
            // catch-and-ignore so the test can verify the audit
            // interaction after the throw.
            assertThatThrownBy(() -> service.getCardDetail(CARD_NUMBER))
                    .isInstanceOf(RecordNotFoundException.class);

            // Assert — even on a NOTFND, no audit emission occurs
            verifyNoInteractions(auditLogService);
        }

        /**
         * Cross-check: the schema-required {@code log*} methods on
         * {@link AuditLogService} (the production API surface) MUST
         * never be invoked from any read path. This is a redundant
         * but explicit assertion that catches the case where
         * {@code verifyNoInteractions} is bypassed by some future
         * change.
         */
        // COBOL: AAP §0.6.6 — audit emission policy verified per-method
        @Test
        @DisplayName("specific audit methods are never invoked on success")
        void getCardDetail_specificAuditMethods_neverInvoked() {
            // Arrange — happy path
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(CARD_NUMBER),
                    eq(CardDetailDto.class))).thenReturn(Optional.empty());
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(card));

            // Act
            service.getCardDetail(CARD_NUMBER);

            // Assert — none of the schema-required emission methods
            // were called. Per-method verifications are intentionally
            // exhaustive: any future addition of audit emission to a
            // read path must update this test consciously.
            verify(auditLogService, never()).logAuditEvent(
                    anyString(), anyString(), anyString(),
                    anyString(), anyMap(), anyString());
            verify(auditLogService, never()).logTransactionEvent(
                    anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyMap(), anyString());
            verify(auditLogService, never()).logSecurityEvent(
                    anyString(), anyString(), anyString(),
                    anyString(), anyMap(), anyString());
            verify(auditLogService, never()).auditEvent(
                    anyString(), anyString(), anyMap());
            verify(auditLogService, never()).auditTransaction(
                    anyString(), anyString(), anyString(), anyMap());
        }
    }
}
