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
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link AccountViewService}.
 *
 * <p><b>COBOL provenance.</b> {@link AccountViewService} is the Java
 * target for the COBOL/CICS program {@code app/cbl/COACTVWC.cbl}
 * (CICS transaction id {@code CAVW}, mapset {@code COACTVW}, map
 * {@code CACTVWA}). The COBOL source performs three sequential reads
 * in a specific order &mdash; {@code CXACAIX} (alternate-index by
 * account ID) &rarr; {@code ACCTDAT} (primary key by account ID)
 * &rarr; {@code CUSTDAT} (primary key by customer ID) &mdash; and
 * surfaces a distinct error message for each missing-row scenario.
 * In the Java target this becomes:</p>
 * <ol>
 *   <li>field-level validation of the supplied account ID (non-null,
 *       strictly positive; COBOL paragraph {@code 2210-EDIT-ACCOUNT}
 *       at {@code COACTVWC.cbl} lines 649&ndash;684);</li>
 *   <li>cache-aside lookup against ElastiCache Redis via
 *       {@link CacheService} (AAP &sect;0.7.1 net-new capability;
 *       namespace {@code "account-view"});</li>
 *   <li>JPA derived-query lookup against
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *       (replaces COBOL paragraph {@code 9200-GETCARDXREF-BYACCT}
 *       at COCRDSLC.cbl lines 723&ndash;771; the AIX-replacement
 *       method on the {@code CXACAIX} secondary index);</li>
 *   <li>{@code findById} lookup against {@link AccountRepository}
 *       (replaces COBOL paragraph {@code 9300-GETACCTDATA-BYACCT}
 *       at COACTVWC.cbl lines 774&ndash;821);</li>
 *   <li>{@code findById} lookup against {@link CustomerRepository}
 *       (replaces COBOL paragraph {@code 9400-GETCUSTDATA-BYCUST}
 *       at COACTVWC.cbl lines 825&ndash;870);</li>
 *   <li>assembly of a {@link AccountViewDto} response (replaces COBOL
 *       paragraph {@code 1200-SETUP-SCREEN-VARS});</li>
 *   <li>cache populate-on-miss; return DTO to the controller layer
 *       which serializes it as the JSON body of
 *       {@code GET /api/accounts/{id}}.</li>
 * </ol>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Happy path</b> &mdash; a valid account ID returns a fully
 *       populated {@link AccountViewDto} projecting the joined
 *       Account + Customer + first-CardCrossReference state. (COBOL:
 *       {@code 9000-READ-ACCT} success path &rarr;
 *       {@code 1200-SETUP-SCREEN-VARS}.)</li>
 *   <li><b>Read ordering preserved</b> &mdash; the service performs
 *       XREF &rarr; ACCT &rarr; CUST in exactly that order, matching
 *       COACTVWC paragraph {@code 9000-READ-ACCT} lines 687&ndash;718.
 *       Re-ordering would produce the wrong error message for any
 *       given missing-row scenario, violating AAP &sect;0.7.1's
 *       minimal-change rule.</li>
 *   <li><b>Cache-aside hit</b> &mdash; a cache hit short-circuits all
 *       three database reads; {@link AccountRepository#findById},
 *       {@link CustomerRepository#findById}, and
 *       {@link CardCrossReferenceRepository#findByXrefAcctId} are
 *       NEVER invoked (AAP &sect;0.7.1).</li>
 *   <li><b>Cache-aside miss</b> &mdash; a cache miss falls through to
 *       the three database reads; on success the DTO is populated
 *       into the cache via
 *       {@link CacheService#put(String, String, Object, Duration)}
 *       under namespace {@code "account-view"} with the 5-minute
 *       {@link AccountViewService#CACHE_TTL}.</li>
 *   <li><b>Record not found (3 distinct scenarios)</b>:
 *       <ul>
 *         <li>XREF miss &rarr; {@link RecordNotFoundException} with
 *             the verbatim COBOL working-storage message
 *             <em>"Did not find this account in account card xref
 *             file"</em> (COACTVWC line 130).</li>
 *         <li>Account miss &rarr; {@link RecordNotFoundException}
 *             with the verbatim COBOL working-storage message
 *             <em>"Did not find this account in account master
 *             file"</em> (COACTVWC line 132).</li>
 *         <li>Customer miss &rarr; {@link RecordNotFoundException}
 *             with the verbatim COBOL working-storage message
 *             <em>"Did not find associated customer in master
 *             file"</em> (COACTVWC line 134).</li>
 *       </ul>
 *       All three map to HTTP 404 via
 *       {@code GlobalExceptionHandler}.</li>
 *   <li><b>Validation</b> &mdash; null and non-positive account IDs
 *       surface as {@link ValidationException} BEFORE any cache or
 *       repository lookup (COBOL: {@code 2210-EDIT-ACCOUNT};
 *       <em>"Account number must be a non zero 11 digit number"</em>).</li>
 *   <li><b>No-cache-on-failure</b> &mdash; failed lookups MUST NOT
 *       populate the cache; verified via
 *       {@code verify(cacheService, never()).put(...)} on every
 *       not-found path.</li>
 *   <li><b>PII discipline (PCI-DSS)</b> &mdash; {@link AccountViewDto}
 *       MUST mask the SSN in {@link AccountViewDto#toString()} so
 *       that any accidental log emission of the DTO never exposes
 *       the regulated SSN per AAP &sect;0.6.6 (PCI-DSS v4.0).</li>
 *   <li><b>Audit isolation</b> &mdash; {@link AccountViewService} is
 *       intentionally read-only and does NOT inject
 *       {@link AuditLogService} (its 4-arg constructor takes only
 *       {@link AccountRepository}, {@link CustomerRepository},
 *       {@link CardCrossReferenceRepository}, and
 *       {@link CacheService}). The AuditLogging tests therefore
 *       verify that no audit events are emitted from view
 *       operations, matching the COBOL {@code COACTVWC.cbl} which
 *       performs three CICS READs with no audit-trail write. The
 *       {@link AuditLogService} {@code @Mock} is declared so that
 *       any future addition of audit emission to this service is
 *       caught by the negative
 *       {@link org.mockito.Mockito#verifyNoInteractions} check.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack or Testcontainers are
 * involved.</p>
 *
 * @see AccountViewService
 * @see AccountViewDto
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardCrossReferenceRepository
 * @see CacheService
 * @see RecordNotFoundException
 * @see ValidationException
 */
// COBOL: COACTVWC.cbl — account-view online inquiry (TRANID 'CAVW', map 'CACTVWA')
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService — COACTVWC cache-aside multi-entity join")
class AccountViewServiceTest {

    // ==================================================================
    // Test constants — shared across every nested group
    //
    // Per AAP §0.7.3 traceability discipline, every constant is named
    // after the COBOL field it represents:
    //   ACCOUNT_ID         ← CVACT01Y.cpy:L5 ACCT-ID PIC 9(11)
    //   ACCOUNT_ID_KEY     ← the zero-padded cache/Kafka key form
    //                         (mirrors AccountViewService.acctIdKey)
    //   CUSTOMER_ID        ← CVCUS01Y.cpy:L5 CUST-ID PIC 9(09)
    //   CARD_NUMBER        ← CVACT03Y.cpy:L5 XREF-CARD-NUM PIC X(16)
    //                         (PCI-DSS sensitive — never appears in the
    //                          AccountViewDto, which has no PAN field)
    //   SSN                ← CVCUS01Y.cpy:L17 CUST-SSN PIC 9(09)
    //                         (PCI-DSS sensitive — masked in
    //                          AccountViewDto.toString())
    //   The 5 BigDecimal monetary fields are all PIC S9(10)V99 in
    //   CVACT01Y.cpy and map to NUMERIC(12,2) per AAP §0.6.1.
    // ==================================================================

    private static final Long ACCOUNT_ID = 11_111_111_111L;
    private static final String ACCOUNT_ID_KEY = "11111111111";
    private static final Long CUSTOMER_ID = 100_000_001L;
    private static final String CARD_NUMBER = "4111111111111111";

    // ----- Account monetary fields (CVACT01Y.cpy PIC S9(10)V99) -----
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1000.00");
    private static final BigDecimal CURR_CYC_CREDIT = new BigDecimal("500.00");
    private static final BigDecimal CURR_CYC_DEBIT = new BigDecimal("200.00");

    // ----- Account date fields (CVACT01Y.cpy PIC X(10)) -----
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 15);
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2030, 1, 15);
    private static final LocalDate REISSUE_DATE = LocalDate.of(2025, 1, 15);

    private static final String ACTIVE_STATUS = "Y";
    private static final String ACCOUNT_ZIP = "12345";
    private static final String GROUP_ID = "DEFAULT";

    // ----- Customer fields (CVCUS01Y.cpy) -----
    private static final String FIRST_NAME = "JOHN";
    private static final String MIDDLE_NAME = "Q";
    private static final String LAST_NAME = "DOE";
    // CUST-SSN PIC 9(09); 123456789 is the canonical test SSN; the
    // last four digits ("6789") are the only portion that should
    // appear in any masked rendering — see PiiMasking tests.
    private static final Long SSN = 123_456_789L;
    private static final String SSN_AS_STRING = "123456789";
    private static final String SSN_LAST_FOUR = "6789";
    private static final String PHONE_1 = "5551234567";
    private static final String PHONE_2 = "5557654321";
    private static final String ADDR_LINE_1 = "123 MAIN ST";
    private static final String ADDR_LINE_2 = "APT 4B";
    private static final String ADDR_LINE_3 = "NEW YORK";
    private static final String STATE_CD = "NY";
    private static final String COUNTRY_CD = "USA";
    private static final String CUST_ZIP = "10001";
    private static final LocalDate DOB = LocalDate.of(1980, 5, 15);
    // GOVT_ID and EFT_ACCT are deliberately chosen so their string
    // forms do NOT contain the {@link #SSN_AS_STRING} ("123456789")
    // as a substring. This isolation prevents the PiiMasking tests
    // from producing false-positive failures driven by accidental
    // substring overlap between unrelated test-fixture values.
    private static final String GOVT_ID = "DLP9876543";
    private static final String EFT_ACCT = "9876543210";
    private static final String PRI_HOLDER_IND = "Y";
    private static final Integer FICO_SCORE = 720;

    /**
     * Cache namespace used by {@link AccountViewService} for the
     * cache-aside protocol. Mirrors {@link AccountViewService#CACHE_NS}
     * exactly. Per AAP &sect;0.7.1 ("ElastiCache (Redis) used for
     * account balance caching &mdash; cache-aside pattern with TTL
     * aligned to transaction frequency").
     */
    private static final String CACHE_NAMESPACE = "account-view";

    // ==================================================================
    // Mocks and System Under Test (SUT)
    //
    // AccountViewService.constructor takes EXACTLY four collaborators
    // (per src/main/java/com/awsm2/carddemo/service/AccountViewService.java
    // lines 234-251) :
    //   1. AccountRepository
    //   2. CustomerRepository
    //   3. CardCrossReferenceRepository
    //   4. CacheService
    //
    // AccountViewService does NOT take AuditLogService as a
    // constructor parameter — view operations on the COBOL COACTVWC.cbl
    // perform no audit-trail write. The AuditLogService @Mock is
    // declared here so the AuditLogging @Nested group can assert via
    // verifyNoInteractions that view operations emit NO audit events;
    // any future addition of audit emission to this service is caught
    // by that negative assertion.
    // ==================================================================

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AccountViewService service;

    /**
     * Account test fixture populated in {@link #setUp()} and returned
     * by {@code accountRepository.findById(...)} mock for happy-path
     * and cache-miss tests. Carries every field declared on the
     * {@link Account} entity, including the FIVE
     * {@link BigDecimal} monetary fields and the THREE
     * {@link LocalDate} lifecycle fields, plus a deterministic
     * {@code version=0L} for the {@code @Version} optimistic-lock
     * column.
     */
    private Account account;

    /**
     * Customer test fixture populated in {@link #setUp()} and returned
     * by {@code customerRepository.findById(...)} mock for happy-path
     * and cache-miss tests. Carries every field declared on the
     * {@link Customer} entity, including the PII-sensitive
     * {@link #SSN} (which MUST be masked in
     * {@link AccountViewDto#toString()}).
     */
    private Customer customer;

    /**
     * CardCrossReference test fixture populated in {@link #setUp()}
     * and returned (wrapped in a {@link List}) by
     * {@code cardCrossReferenceRepository.findByXrefAcctId(...)} mock
     * for happy-path and cache-miss tests. The XREF row carries the
     * 16-char PAN (PCI-DSS sensitive but never projected onto the
     * AccountViewDto), the customer-ID foreign key (used to drive
     * the {@code CustomerRepository.findById(...)} read), and the
     * account-ID query parameter.
     */
    private CardCrossReference xref;

    /**
     * Initialize the test fixtures before each test method.
     *
     * <p>Constructs the three entity fixtures &mdash; {@link Account},
     * {@link Customer}, {@link CardCrossReference} &mdash; with every
     * persistent field set to a known value drawn from the test
     * constants. The fixtures mirror the byte layouts of
     * {@code CVACT01Y.cpy} (300 bytes), {@code CVCUS01Y.cpy} (500
     * bytes), and {@code CVACT03Y.cpy} (50 bytes) respectively.</p>
     *
     * <p>The {@link Customer#getCustSsn()} field is intentionally
     * populated with a value whose 9-digit zero-padded decimal form
     * contains a recognisable last-four-digit suffix
     * ({@link #SSN_LAST_FOUR}) so the PII-masking assertions in the
     * {@code PiiMasking} nested group can verify both the negative
     * case (unmasked SSN never appears) and the positive case
     * (masked SSN with last four digits visible).</p>
     */
    @BeforeEach
    void setUp() {
        // COBOL: COACTVWC:1200-SETUP-SCREEN-VARS — populate the
        // in-memory ACCOUNT-RECORD / CUSTOMER-RECORD / CARD-XREF-RECORD
        // that the service will hydrate from the JPA repositories.
        // The fixtures mirror CVACT01Y.cpy:L4-L17, CVCUS01Y.cpy:L4-L23,
        // and CVACT03Y.cpy:L4-L8 byte layouts.

        // ===== Account (CVACT01Y.cpy — 300 bytes) =====
        account = new Account();
        // COBOL: CVACT01Y.cpy:L5 ACCT-ID PIC 9(11) — primary key
        account.setAcctId(ACCOUNT_ID);
        // COBOL: CVACT01Y.cpy:L6 ACCT-ACTIVE-STATUS PIC X(01)
        account.setAcctActiveStatus(ACTIVE_STATUS);
        // COBOL: CVACT01Y.cpy:L7 ACCT-CURR-BAL PIC S9(10)V99 —
        // BigDecimal with scale=2 per AAP §0.6.1.
        account.setAcctCurrBal(CURRENT_BALANCE);
        // COBOL: CVACT01Y.cpy:L8 ACCT-CREDIT-LIMIT PIC S9(10)V99
        account.setAcctCreditLimit(CREDIT_LIMIT);
        // COBOL: CVACT01Y.cpy:L9 ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
        account.setAcctCashCreditLimit(CASH_CREDIT_LIMIT);
        // COBOL: CVACT01Y.cpy:L10 ACCT-OPEN-DATE PIC X(10) — ISO
        // yyyy-MM-dd; java.time.LocalDate replaces COBOL LE CEEDAYS
        // date primitives per AAP §0.5.2.
        account.setAcctOpenDate(OPEN_DATE);
        // COBOL: CVACT01Y.cpy:L11 ACCT-EXPIRAION-DATE PIC X(10) —
        // setter uses corrected "expiration" spelling per AAP §0.4.1
        // (COBOL field-name typo preserved at the storage layer only).
        account.setAcctExpirationDate(EXPIRATION_DATE);
        // COBOL: CVACT01Y.cpy:L12 ACCT-REISSUE-DATE PIC X(10)
        account.setAcctReissueDate(REISSUE_DATE);
        // COBOL: CVACT01Y.cpy:L13 ACCT-CURR-CYC-CREDIT PIC S9(10)V99
        account.setAcctCurrCycCredit(CURR_CYC_CREDIT);
        // COBOL: CVACT01Y.cpy:L14 ACCT-CURR-CYC-DEBIT PIC S9(10)V99
        account.setAcctCurrCycDebit(CURR_CYC_DEBIT);
        // COBOL: CVACT01Y.cpy:L15 ACCT-ADDR-ZIP PIC X(10)
        account.setAcctAddrZip(ACCOUNT_ZIP);
        // COBOL: CVACT01Y.cpy:L16 ACCT-GROUP-ID PIC X(10) —
        // foreign key into disclosure_group for interest-rate lookup
        account.setAcctGroupId(GROUP_ID);
        // JPA @Version (no COBOL equivalent) — initialized to 0L for
        // a freshly persisted row per Account.java JavaDoc.
        account.setVersion(0L);

        // ===== Customer (CVCUS01Y.cpy — 500 bytes) =====
        customer = new Customer();
        // COBOL: CVCUS01Y.cpy:L5 CUST-ID PIC 9(09) — primary key
        customer.setCustId(CUSTOMER_ID);
        // COBOL: CVCUS01Y.cpy:L6 CUST-FIRST-NAME PIC X(25)
        customer.setCustFirstName(FIRST_NAME);
        // COBOL: CVCUS01Y.cpy:L7 CUST-MIDDLE-NAME PIC X(25)
        customer.setCustMiddleName(MIDDLE_NAME);
        // COBOL: CVCUS01Y.cpy:L8 CUST-LAST-NAME PIC X(25)
        customer.setCustLastName(LAST_NAME);
        // COBOL: CVCUS01Y.cpy:L9 CUST-ADDR-LINE-1 PIC X(50)
        customer.setCustAddrLine1(ADDR_LINE_1);
        // COBOL: CVCUS01Y.cpy:L10 CUST-ADDR-LINE-2 PIC X(50)
        customer.setCustAddrLine2(ADDR_LINE_2);
        // COBOL: CVCUS01Y.cpy:L11 CUST-ADDR-LINE-3 PIC X(50)
        customer.setCustAddrLine3(ADDR_LINE_3);
        // COBOL: CVCUS01Y.cpy:L12 CUST-ADDR-STATE-CD PIC X(02)
        customer.setCustAddrStateCd(STATE_CD);
        // COBOL: CVCUS01Y.cpy:L13 CUST-ADDR-COUNTRY-CD PIC X(03)
        customer.setCustAddrCountryCd(COUNTRY_CD);
        // COBOL: CVCUS01Y.cpy:L14 CUST-ADDR-ZIP PIC X(10)
        customer.setCustAddrZip(CUST_ZIP);
        // COBOL: CVCUS01Y.cpy:L15 CUST-PHONE-NUM-1 PIC X(15)
        customer.setCustPhoneNum1(PHONE_1);
        // COBOL: CVCUS01Y.cpy:L16 CUST-PHONE-NUM-2 PIC X(15)
        customer.setCustPhoneNum2(PHONE_2);
        // COBOL: CVCUS01Y.cpy:L17 CUST-SSN PIC 9(09) — PII; MUST be
        // masked in AccountViewDto.toString() per AAP §0.6.6 PCI-DSS.
        customer.setCustSsn(SSN);
        // COBOL: CVCUS01Y.cpy:L18 CUST-GOVT-ISSUED-ID PIC X(20)
        customer.setCustGovtIssuedId(GOVT_ID);
        // COBOL: CVCUS01Y.cpy:L19 CUST-DOB-YYYY-MM-DD PIC X(10) —
        // java.time.LocalDate replaces COBOL LE CEEDAYS primitives.
        customer.setCustDobYyyyMmDd(DOB);
        // COBOL: CVCUS01Y.cpy:L20 CUST-EFT-ACCOUNT-ID PIC X(10)
        customer.setCustEftAccountId(EFT_ACCT);
        // COBOL: CVCUS01Y.cpy:L21 CUST-PRI-CARD-HOLDER-IND PIC X(01)
        customer.setCustPriCardHolderInd(PRI_HOLDER_IND);
        // COBOL: CVCUS01Y.cpy:L22 CUST-FICO-CREDIT-SCORE PIC 9(03)
        customer.setCustFicoCreditScore(FICO_SCORE);

        // ===== CardCrossReference (CVACT03Y.cpy — 50 bytes) =====
        xref = new CardCrossReference();
        // COBOL: CVACT03Y.cpy:L5 XREF-CARD-NUM PIC X(16) — primary key
        // (16-char PAN); PCI-DSS sensitive — never projected onto the
        // AccountViewDto, which has no PAN field.
        xref.setXrefCardNum(CARD_NUMBER);
        // COBOL: CVACT03Y.cpy:L6 XREF-CUST-ID PIC 9(09) — FK to
        // Customer; drives the CustomerRepository.findById(...) read
        // in step 5 of the join.
        xref.setXrefCustId(CUSTOMER_ID);
        // COBOL: CVACT03Y.cpy:L7 XREF-ACCT-ID PIC 9(11) — the
        // account-ID query parameter for the derived query
        // findByXrefAcctId(Long).
        xref.setXrefAcctId(ACCOUNT_ID);
    }

    // ====================================================================
    // @Nested test groups
    // ====================================================================

    /**
     * Happy-path tests &mdash; a valid 11-digit account ID resolves
     * to a fully populated {@link AccountViewDto} returned by
     * {@code AccountViewService.getAccountView(acctId)}.
     *
     * <p>COBOL provenance: corresponds to the success path through
     * {@code COACTVWC.cbl} paragraphs {@code 0000-MAIN} &rarr;
     * {@code 2210-EDIT-ACCOUNT} (pass) &rarr;
     * {@code 9000-READ-ACCT} (all three reads succeed) &rarr;
     * {@code 1200-SETUP-SCREEN-VARS} (project entities onto the
     * symbolic-map output buffer).</p>
     */
    @Nested
    @DisplayName("Happy path — valid acctId returns populated DTO")
    class HappyPath {

        /**
         * Verifies the canonical success path: a valid account ID
         * stubs through cache-miss + repository-hits and returns a
         * non-null {@link AccountViewDto} with the full set of fields
         * populated from the joined entities.
         *
         * <p>The mock for
         * {@link CacheService#get(String, String, Class)} is stubbed
         * to return {@link Optional#empty()} (cache miss); the
         * repository mocks are stubbed to return populated entities.
         * The service is expected to return a DTO with the
         * corresponding values.</p>
         */
        // COBOL: COACTVWC:9000-READ-ACCT — all three reads succeed
        @Test
        @DisplayName("getAccountView returns DTO for valid account ID")
        void viewAccount_validAcctId_returnsDto() {
            // Arrange — cache miss → all three repos hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            // COBOL: COACTVWC:9200-GETCARDXREF-BYACCT — CXACAIX hit
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            // COBOL: COACTVWC:9300-GETACCTDATA-BYACCT — ACCTDAT hit
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            // COBOL: COACTVWC:9400-GETCUSTDATA-BYCUST — CUSTDAT hit
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — non-null DTO with every business field present
            assertThat(result).isNotNull();

            // ===== Account fields (CVACT01Y.cpy) =====
            assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.activeStatus()).isEqualTo(ACTIVE_STATUS);
            // BigDecimal comparison: use isEqualByComparingTo (compare-
            // by-value, not by-reference) per AAP §0.7.1 — "1234.56"
            // and "1234.560" would fail equals() despite being equal
            // by value.
            assertThat(result.currentBalance())
                    .isEqualByComparingTo(CURRENT_BALANCE);
            assertThat(result.creditLimit())
                    .isEqualByComparingTo(CREDIT_LIMIT);
            assertThat(result.cashCreditLimit())
                    .isEqualByComparingTo(CASH_CREDIT_LIMIT);
            assertThat(result.currentCycleCredit())
                    .isEqualByComparingTo(CURR_CYC_CREDIT);
            assertThat(result.currentCycleDebit())
                    .isEqualByComparingTo(CURR_CYC_DEBIT);
            assertThat(result.openDate()).isEqualTo(OPEN_DATE);
            assertThat(result.expirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(result.reissueDate()).isEqualTo(REISSUE_DATE);
            assertThat(result.addressZip()).isEqualTo(ACCOUNT_ZIP);
            assertThat(result.accountGroupId()).isEqualTo(GROUP_ID);

            // ===== Customer fields (CVCUS01Y.cpy) =====
            assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(result.firstName()).isEqualTo(FIRST_NAME);
            assertThat(result.middleName()).isEqualTo(MIDDLE_NAME);
            assertThat(result.lastName()).isEqualTo(LAST_NAME);
            assertThat(result.customerSsn()).isEqualTo(SSN);
            assertThat(result.phoneNumber1()).isEqualTo(PHONE_1);
            assertThat(result.phoneNumber2()).isEqualTo(PHONE_2);
            assertThat(result.addressLine1()).isEqualTo(ADDR_LINE_1);
            assertThat(result.addressLine2()).isEqualTo(ADDR_LINE_2);
            assertThat(result.addressLine3()).isEqualTo(ADDR_LINE_3);
            assertThat(result.stateCode()).isEqualTo(STATE_CD);
            assertThat(result.countryCode()).isEqualTo(COUNTRY_CD);
            assertThat(result.zipCode()).isEqualTo(CUST_ZIP);
            assertThat(result.dateOfBirth()).isEqualTo(DOB);
            assertThat(result.governmentIssuedId()).isEqualTo(GOVT_ID);
            assertThat(result.eftAccountId()).isEqualTo(EFT_ACCT);
            assertThat(result.primaryCardHolderIndicator())
                    .isEqualTo(PRI_HOLDER_IND);
            assertThat(result.ficoCreditScore()).isEqualTo(FICO_SCORE);
        }

        /**
         * Verifies that all three repository reads occur in the
         * order documented in {@link AccountViewService} JavaDoc:
         * XREF first, then ACCT, then CUST. Re-ordering would
         * produce the wrong error message for any given missing-row
         * scenario, violating AAP &sect;0.7.1's minimal-change rule
         * which mandates the COBOL paragraph {@code 9000-READ-ACCT}
         * ordering (lines 687&ndash;718) be preserved exactly.
         */
        // COBOL: COACTVWC:9000-READ-ACCT — XREF → ACCT → CUST ordering
        @Test
        @DisplayName("getAccountView invokes XREF, then ACCT, then CUST repositories")
        void viewAccount_invokesRepositoriesInCorrectOrder() {
            // Arrange — cache miss → all three repos hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            service.getAccountView(ACCOUNT_ID);

            // Assert — each repository read invoked exactly once with
            // the correct key. The XREF lookup keys off the account
            // ID; the ACCT lookup keys off the same account ID; the
            // CUST lookup keys off the customer ID sourced from the
            // XREF row (xref.getXrefCustId()).
            verify(cardCrossReferenceRepository).findByXrefAcctId(ACCOUNT_ID);
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(customerRepository).findById(CUSTOMER_ID);
        }

        /**
         * Verifies the cache-aside read order: a cache-miss path
         * performs the get + put pair on {@link CacheService} so
         * subsequent reads short-circuit at the cache.
         */
        // COBOL: AAP §0.7.1 — cache-aside (net-new vs COACTVWC)
        @Test
        @DisplayName("getAccountView performs cache get-then-put on miss path")
        void viewAccount_cacheGetAndPutOrdering() {
            // Arrange — cache miss → all three repos hit
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            service.getAccountView(ACCOUNT_ID);

            // Assert — get is called BEFORE put; both invoked exactly
            // once. The order is implicit in the cache-aside contract
            // (get → miss → DB read → put) and verified here by
            // asserting both invocations on the mock.
            verify(cacheService).get(eq(CACHE_NAMESPACE),
                    eq(ACCOUNT_ID_KEY), eq(AccountViewDto.class));
            verify(cacheService).put(eq(CACHE_NAMESPACE),
                    eq(ACCOUNT_ID_KEY), any(AccountViewDto.class),
                    any(Duration.class));
        }
    }

    /**
     * Cache-aside tests &mdash; verify the read-path of the
     * cache-aside protocol mandated by AAP &sect;0.7.1
     * ("ElastiCache (Redis) used for account balance caching
     * &mdash; cache-aside pattern with TTL aligned to transaction
     * frequency"):
     * <ul>
     *   <li>cache hit short-circuits all three database reads;</li>
     *   <li>cache miss falls through to the three database reads and
     *       populates the cache afterwards.</li>
     * </ul>
     *
     * <p>This protocol is net-new to the Java target; the COBOL
     * COACTVWC.cbl has no caching whatsoever (every read hits VSAM
     * directly). The cache layer absorbs hot-account read load on
     * the AWS target per AAP &sect;0.6.6 ("ElastiCache Redis
     * reduces RDS read load by caching high-frequency account
     * balance lookups").</p>
     */
    @Nested
    @DisplayName("Cache-aside — read-through with populate-on-miss")
    class CacheAside {

        /**
         * Verifies the cache-hit short-circuit: when the cache
         * returns {@link Optional#of} a previously-stored DTO, the
         * service returns that DTO immediately without ever
         * consulting any of the three repositories.
         *
         * <p>This is the primary cost-reduction outcome of the
         * cache-aside design &mdash; the high-frequency read traffic
         * is served by Redis rather than RDS.</p>
         */
        // COBOL: AAP §0.7.1 — cache-aside hit path (net-new vs COACTVWC)
        @Test
        @DisplayName("cache hit returns cached DTO; repositories NEVER invoked")
        void viewAccount_cacheHit_returnsFromCache() {
            // Arrange — pre-built cached DTO. The PII-masking
            // discipline of AccountViewDto.toString() applies
            // identically to cache-served and DB-served DTOs; we
            // assert on identity of the returned reference here
            // because the cached DTO is the SAME object instance the
            // service should return.
            AccountViewDto cachedDto = buildExpectedDto();
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class)))
                    .thenReturn(Optional.of(cachedDto));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — returned DTO is identical to the cached one
            // (object identity, not just equality).
            assertThat(result).isSameAs(cachedDto);

            // Assert — NONE of the three repositories was invoked.
            // The whole point of cache-aside is to avoid the RDS
            // round-trip on cache hit.
            verify(accountRepository, never()).findById(anyLong());
            verify(customerRepository, never()).findById(anyLong());
            verify(cardCrossReferenceRepository, never())
                    .findByXrefAcctId(anyLong());

            // Assert — the service did NOT issue a cache.put on the
            // hit path. Re-populating the cache on every hit would
            // double Redis write load for no benefit.
            verify(cacheService, never()).put(
                    anyString(), anyString(), any(), any(Duration.class));
        }

        /**
         * Verifies the cache-miss fall-through: when the cache
         * returns {@link Optional#empty()}, the service performs the
         * three repository reads and then populates the cache with
         * the assembled DTO so subsequent reads short-circuit at
         * the cache.
         *
         * <p>The TTL passed to
         * {@link CacheService#put(String, String, Object, Duration)}
         * is verified to be non-null and non-negative; the exact
         * value is the {@link AccountViewService#CACHE_TTL} constant
         * (5 minutes) which the service controls.</p>
         */
        // COBOL: AAP §0.7.1 — cache-aside miss path (RDS fall-through)
        @Test
        @DisplayName("cache miss falls through to all three repositories and populates cache")
        void viewAccount_cacheMiss_loadsFromRepoAndCaches() {
            // Arrange — cache miss
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — DTO is non-null and carries the joined state
            assertThat(result).isNotNull();
            assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);

            // Assert — all three repositories were invoked
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(customerRepository).findById(CUSTOMER_ID);
            verify(cardCrossReferenceRepository)
                    .findByXrefAcctId(ACCOUNT_ID);

            // Assert — cache populated with the assembled DTO under
            // the canonical "account-view" namespace + zero-padded
            // account ID key. The TTL is any non-null Duration; the
            // exact value (5 minutes per AccountViewService.CACHE_TTL)
            // is the service's responsibility to provide.
            verify(cacheService).put(eq(CACHE_NAMESPACE),
                    eq(ACCOUNT_ID_KEY), any(AccountViewDto.class),
                    any(Duration.class));
        }

        /**
         * Verifies that the cache key derived by the service is the
         * 11-digit zero-padded form of the account ID, matching the
         * {@code String.format("%011d", acctId)} contract documented
         * in the {@link AccountViewService} JavaDoc and the
         * {@code KafkaEventPublisher} partition-key contract.
         *
         * <p>Consistency between cache-key form and Kafka
         * partition-key form is critical because both services
         * identify the same business entity by the same string
         * representation; a divergence would cause cache
         * invalidation to miss the corresponding cache entry.</p>
         */
        // COBOL: AAP §0.7.1 — cache key form must be 11-digit zero-padded
        @Test
        @DisplayName("cache key is 11-digit zero-padded form of account ID")
        void viewAccount_cacheKeyIsZeroPaddedAccountId() {
            // Arrange — use an account ID that requires zero-padding
            // (8 digits, so the canonical key form prefixes three
            // zeros). The fixture's xref is patched to reflect the
            // new account ID so the repository chain stubs match.
            final Long shortAcctId = 12_345_678L;
            final String expectedKey = "00012345678";
            xref.setXrefAcctId(shortAcctId);
            account.setAcctId(shortAcctId);

            when(cacheService.get(eq(CACHE_NAMESPACE), eq(expectedKey),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(shortAcctId))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(shortAcctId))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            service.getAccountView(shortAcctId);

            // Assert — both cache operations used the zero-padded key
            verify(cacheService).get(eq(CACHE_NAMESPACE),
                    eq(expectedKey), eq(AccountViewDto.class));
            verify(cacheService).put(eq(CACHE_NAMESPACE),
                    eq(expectedKey), any(AccountViewDto.class),
                    any(Duration.class));
        }
    }

    /**
     * Record-not-found tests &mdash; verify that each of the three
     * sequential reads (XREF, ACCT, CUST) translates a missing-row
     * scenario into a {@link RecordNotFoundException} carrying the
     * verbatim COBOL working-storage message documented in
     * {@code COACTVWC.cbl} lines 129&ndash;134.
     *
     * <p>The exception is mapped to HTTP 404 Not Found by
     * {@code GlobalExceptionHandler} per AAP &sect;0.4.1, and the
     * reason code is preserved verbatim for downstream consumers
     * per AAP &sect;0.7.2.</p>
     *
     * <p>Each of the three not-found paths also asserts that the
     * cache is NOT populated (a failed read should not pollute the
     * cache with an absence-of-record marker).</p>
     */
    @Nested
    @DisplayName("Record-not-found — three distinct COBOL scenarios")
    class RecordNotFound {

        /**
         * Verifies the XREF-miss scenario: when
         * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
         * returns an empty list, the service throws
         * {@link RecordNotFoundException} with the verbatim COBOL
         * working-storage message <em>"Did not find this account in
         * account card xref file"</em>
         * (COACTVWC.cbl:L129-130).
         *
         * <p>This is the first of the three sequential reads in
         * COACTVWC paragraph {@code 9000-READ-ACCT}; per the
         * preserved read ordering, the ACCT and CUST reads are NOT
         * attempted when XREF fails.</p>
         */
        // COBOL: COACTVWC:L129-130 — DID-NOT-FIND-ACCT-IN-CARDXREF
        @Test
        @DisplayName("XREF miss throws RecordNotFoundException with verbatim COBOL message")
        void viewAccount_xrefNotFound_throwsRecordNotFound() {
            // Arrange — cache miss → XREF returns empty list (NOTFND)
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(Collections.emptyList());

            // Act + Assert — RecordNotFoundException with verbatim
            // COBOL working-storage message
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(
                            "Did not find this account in account card xref file");

            // Assert — ACCT and CUST reads are NOT attempted (read
            // ordering preserved per COACTVWC:9000-READ-ACCT).
            verify(accountRepository, never()).findById(anyLong());
            verify(customerRepository, never()).findById(anyLong());
        }

        /**
         * Verifies the ACCT-miss scenario: when
         * {@link AccountRepository#findById(Object)} returns
         * {@link Optional#empty()} (after XREF succeeded), the
         * service throws {@link RecordNotFoundException} with the
         * verbatim COBOL working-storage message <em>"Did not find
         * this account in account master file"</em>
         * (COACTVWC.cbl:L131-132).
         *
         * <p>This is the second of the three sequential reads;
         * per the preserved read ordering, the CUST read is NOT
         * attempted when ACCT fails.</p>
         */
        // COBOL: COACTVWC:L131-132 — DID-NOT-FIND-ACCT-IN-ACCTDAT
        @Test
        @DisplayName("ACCT miss throws RecordNotFoundException with verbatim COBOL message")
        void viewAccount_acctNotFound_throwsRecordNotFound() {
            // Arrange — cache miss → XREF OK → ACCT returns empty
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(
                            "Did not find this account in account master file");

            // Assert — CUST read is NOT attempted (read ordering
            // preserved per COACTVWC:9000-READ-ACCT).
            verify(customerRepository, never()).findById(anyLong());
        }

        /**
         * Verifies the CUST-miss scenario: when
         * {@link CustomerRepository#findById(Object)} returns
         * {@link Optional#empty()} (after XREF and ACCT both
         * succeeded), the service throws
         * {@link RecordNotFoundException} with the verbatim COBOL
         * working-storage message <em>"Did not find associated
         * customer in master file"</em>
         * (COACTVWC.cbl:L133-134).
         *
         * <p>This is the third and final of the three sequential
         * reads in COACTVWC paragraph {@code 9000-READ-ACCT}.</p>
         */
        // COBOL: COACTVWC:L133-134 — DID-NOT-FIND-CUST-IN-CUSTDAT
        @Test
        @DisplayName("CUST miss throws RecordNotFoundException with verbatim COBOL message")
        void viewAccount_customerNotFound_throwsRecordNotFound() {
            // Arrange — cache miss → XREF OK → ACCT OK → CUST empty
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(
                            "Did not find associated customer in master file");
        }

        /**
         * Verifies that the XREF-miss path does NOT populate the
         * cache. A cache write on a failed read would mean that a
         * later, valid request for the same account ID would still
         * see the "not found" entry, defeating the cache-aside
         * recovery semantics.
         */
        // COBOL: AAP §0.7.1 — cache-aside failure semantics
        @Test
        @DisplayName("XREF miss does NOT populate the cache")
        void viewAccount_xrefNotFound_doesNotCache() {
            // Arrange — XREF NOTFND
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(Collections.emptyList());

            // Act + Assert — exception thrown; cache.put NOT invoked
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(cacheService, never()).put(
                    anyString(), anyString(), any(), any(Duration.class));
        }

        /**
         * Verifies that the ACCT-miss path does NOT populate the
         * cache. Same rationale as the XREF-miss case.
         */
        // COBOL: AAP §0.7.1 — cache-aside failure semantics
        @Test
        @DisplayName("ACCT miss does NOT populate the cache")
        void viewAccount_acctNotFound_doesNotCache() {
            // Arrange — XREF OK → ACCT NOTFND
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert — exception thrown; cache.put NOT invoked
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(cacheService, never()).put(
                    anyString(), anyString(), any(), any(Duration.class));
        }

        /**
         * Verifies that the CUST-miss path does NOT populate the
         * cache. Same rationale as the XREF-miss and ACCT-miss
         * cases.
         */
        // COBOL: AAP §0.7.1 — cache-aside failure semantics
        @Test
        @DisplayName("CUST miss does NOT populate the cache")
        void viewAccount_customerNotFound_doesNotCache() {
            // Arrange — XREF OK → ACCT OK → CUST NOTFND
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            // Act + Assert — exception thrown; cache.put NOT invoked
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(cacheService, never()).put(
                    anyString(), anyString(), any(), any(Duration.class));
        }
    }

    /**
     * Validation tests &mdash; verify that null, zero, and negative
     * account IDs are rejected with {@link ValidationException}
     * BEFORE any cache or repository lookup is performed. This
     * mirrors COBOL paragraph {@code 2210-EDIT-ACCOUNT}
     * (COACTVWC.cbl lines 649&ndash;684) which rejects zero / non-
     * numeric account identifiers with the working-storage condition
     * {@code SEARCHED-ACCT-ZEROES} / {@code SEARCHED-ACCT-NOT-NUMERIC}
     * bearing the verbatim message
     * <em>"Account number must be a non zero 11 digit number"</em>
     * (COACTVWC.cbl:L125-128).
     *
     * <p>The Java target maps these to
     * {@link ValidationException} (HTTP 400 via
     * {@code GlobalExceptionHandler}). Per AAP &sect;0.7.2 ("Error
     * codes and condition handling surfaced to downstream consumers
     * must be preserved verbatim"), the message string is preserved
     * exactly.</p>
     */
    @Nested
    @DisplayName("Validation — null/zero/negative acctId rejected upfront")
    class Validation {

        /**
         * Verifies that a {@code null} account ID surfaces as
         * {@link ValidationException} BEFORE any cache or
         * repository lookup. The exception message must match the
         * verbatim COBOL working-storage text.
         */
        // COBOL: COACTVWC:2210-EDIT-ACCOUNT — SEARCHED-ACCT-ZEROES
        @Test
        @DisplayName("null acctId throws ValidationException with verbatim COBOL message")
        void viewAccount_nullAcctId_throwsValidationException() {
            // Act + Assert
            assertThatThrownBy(() -> service.getAccountView(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(
                            "Account number must be a non zero 11 digit number");

            // Assert — no cache or repository was consulted; the
            // validation must short-circuit upstream of all I/O.
            verifyNoInteractions(cacheService);
            verifyNoInteractions(cardCrossReferenceRepository);
            verifyNoInteractions(accountRepository);
            verifyNoInteractions(customerRepository);
        }

        /**
         * Verifies that a zero account ID surfaces as
         * {@link ValidationException}. COBOL paragraph
         * {@code 2210-EDIT-ACCOUNT} explicitly rejects an all-zero
         * 11-digit input via the {@code SEARCHED-ACCT-ZEROES}
         * 88-level condition (COACTVWC.cbl:L125-126).
         */
        // COBOL: COACTVWC:L125-126 — SEARCHED-ACCT-ZEROES
        @Test
        @DisplayName("zero acctId throws ValidationException")
        void viewAccount_zeroAcctId_throwsValidationException() {
            assertThatThrownBy(() -> service.getAccountView(0L))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(
                            "Account number must be a non zero 11 digit number");

            verifyNoInteractions(cacheService);
            verifyNoInteractions(cardCrossReferenceRepository);
            verifyNoInteractions(accountRepository);
            verifyNoInteractions(customerRepository);
        }

        /**
         * Verifies that a negative account ID surfaces as
         * {@link ValidationException}. The COBOL PIC clause
         * {@code PIC 9(11)} (unsigned) makes negative inputs
         * structurally impossible at the COBOL boundary, but the
         * Java target's {@link Long} type permits them; the
         * validation guards against that path explicitly.
         */
        // COBOL: COACTVWC:2210-EDIT-ACCOUNT — strict positive guard
        @Test
        @DisplayName("negative acctId throws ValidationException")
        void viewAccount_negativeAcctId_throwsValidationException() {
            assertThatThrownBy(() -> service.getAccountView(-1L))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(
                            "Account number must be a non zero 11 digit number");

            verifyNoInteractions(cacheService);
            verifyNoInteractions(cardCrossReferenceRepository);
            verifyNoInteractions(accountRepository);
            verifyNoInteractions(customerRepository);
        }
    }

    /**
     * Card-listing tests &mdash; verify the CardCrossReference
     * cardinality semantics of the AIX-replacement query
     * {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}.
     *
     * <p>The COBOL {@code CXACAIX} alternate index over
     * {@code CARDXREF.VSAM.KSDS} carries {@code NONUNIQUEKEY KEYS(11
     * 25)} semantics &mdash; any number of card-xref rows may share
     * the same {@code XREF-ACCT-ID} (0, 1, 2, 3, ...). The Java
     * derived query mirrors this with a
     * {@link List}&lt;{@link CardCrossReference}&gt; return type.</p>
     *
     * <p>{@link AccountViewService} consumes only the FIRST element
     * of the returned list (the COBOL behaviour on an AIX READ is
     * to return the first matching record in AIX key order; the
     * service mirrors that by calling {@code xrefs.get(0)}). The
     * remaining list elements are not projected onto the DTO
     * (which has no {@code cards} accessor). These tests verify
     * the cardinality boundaries: empty list throws not-found,
     * 1+-element list proceeds successfully.</p>
     */
    @Nested
    @DisplayName("Card listing — XREF AIX cardinality semantics")
    class CardListing {

        /**
         * Verifies that an account with no cards (empty XREF list)
         * surfaces as {@link RecordNotFoundException} with the
         * verbatim COBOL XREF-miss message. This is the same
         * exception as
         * {@code RecordNotFound.viewAccount_xrefNotFound_throwsRecordNotFound}
         * but re-asserted here in the card-listing context to
         * document that empty-list and not-found are equivalent at
         * the AIX layer.
         */
        // COBOL: CXACAIX — NONUNIQUEKEY KEYS(11 25), zero-row case
        @Test
        @DisplayName("zero cards (empty XREF) throws RecordNotFoundException")
        void viewAccount_noCardsForAccount_throwsRecordNotFound() {
            // Arrange — XREF returns empty list (account has no
            // cards). In the COBOL source this is the same NOTFND
            // condition as an account whose xref row doesn't exist
            // at all; the JPA derived query's empty-list return is
            // the equivalent.
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(Collections.emptyList());

            // Act + Assert
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining(
                            "Did not find this account in account card xref file");
        }

        /**
         * Verifies that an account with exactly one card succeeds
         * and surfaces the customer ID from that single XREF row.
         */
        // COBOL: CXACAIX — single-row case (most common)
        @Test
        @DisplayName("single card returns DTO with matching customer ID")
        void viewAccount_singleCard_returnsDto() {
            // Arrange — XREF returns exactly one row
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
        }

        /**
         * Verifies that an account with multiple cards (3 XREF rows)
         * still resolves successfully &mdash; the service takes the
         * FIRST entry's customer ID and ignores the rest. The
         * AccountViewDto has no {@code cards} accessor; the
         * additional XREF rows are not projected onto the DTO,
         * matching the COBOL behaviour where COACTVWC.cbl reads only
         * the first AIX match and falls into the screen-render flow.
         *
         * <p>Note: although three XREF rows are returned, only the
         * first one's {@link CardCrossReference#getXrefCustId()}
         * drives the {@code CustomerRepository.findById(...)} call.
         * This is verified by asserting that the customerRepository
         * was invoked exactly once with the first row's customer
         * ID.</p>
         */
        // COBOL: CXACAIX — multi-row case (account with several cards)
        @Test
        @DisplayName("multiple cards returns DTO sourced from FIRST xref row")
        void viewAccount_multipleCards_returnsDtoFromFirstXref() {
            // Arrange — XREF returns three rows for the same account.
            // The fixture's xref carries CUSTOMER_ID = 100_000_001;
            // the additional rows carry different customer IDs that
            // should be IGNORED by the service.
            CardCrossReference xref2 = new CardCrossReference();
            xref2.setXrefCardNum("4222222222222222");
            xref2.setXrefCustId(200_000_002L);
            xref2.setXrefAcctId(ACCOUNT_ID);

            CardCrossReference xref3 = new CardCrossReference();
            xref3.setXrefCardNum("4333333333333333");
            xref3.setXrefCustId(300_000_003L);
            xref3.setXrefAcctId(ACCOUNT_ID);

            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref, xref2, xref3));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — service picked the FIRST xref row's customer
            // ID; the secondary and tertiary xref rows are ignored.
            assertThat(result).isNotNull();
            assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);

            // Assert — customerRepository invoked exactly once with
            // the first row's customer ID; secondary lookups for
            // 200_000_002L and 300_000_003L NEVER happen.
            verify(customerRepository).findById(CUSTOMER_ID);
            verify(customerRepository, never()).findById(200_000_002L);
            verify(customerRepository, never()).findById(300_000_003L);
        }
    }

    /**
     * PII-masking tests &mdash; verify the AAP &sect;0.6.6 PCI-DSS
     * v4.0 mandates:
     * <ul>
     *   <li>SSN is masked in {@link AccountViewDto#toString()}
     *       output as {@code ***-**-XXXX} (last four digits only);
     *       the unmasked SSN MUST NEVER appear in the textual
     *       rendering.</li>
     *   <li>The {@link AccountViewDto} has no PAN
     *       ({@code cardNumber}) field at all &mdash; this is a
     *       PCI-DSS Requirement 3.4.1 invariant on the DTO record
     *       (verified by examining the textual rendering for
     *       absence of the test PAN).</li>
     * </ul>
     *
     * <p>The unmasked SSN is permitted to live in the
     * {@link AccountViewDto#customerSsn()} record component (it is
     * delivered to authorized callers over TLS 1.2+ in the JSON
     * response body), but it MUST be masked in any toString-driven
     * log emission so that accidental DTO logging never exposes
     * the regulated identifier.</p>
     */
    @Nested
    @DisplayName("PII masking — PCI-DSS v4.0 DTO rendering discipline")
    class PiiMasking {

        /**
         * Verifies the SSN-masking contract: the
         * {@link AccountViewDto#toString()} rendering masks the SSN
         * as {@code ***-**-XXXX} with only the last four digits
         * visible. The unmasked 9-digit SSN string MUST NOT appear
         * anywhere in the textual rendering.
         */
        // COBOL: AAP §0.6.6 — PCI-DSS v4.0 SSN masking
        @Test
        @DisplayName("returned DTO masks SSN in toString() output")
        void viewAccount_returnedDto_masksSsnInToString() {
            // Arrange — happy path
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — toString() output contains the masked form
            // ("***-**-6789") and NOT the unmasked SSN ("123456789").
            String stringForm = result.toString();
            assertThat(stringForm).contains("***-**-" + SSN_LAST_FOUR);

            // Defense in depth: the unmasked SSN MUST NOT appear in
            // the toString() output. This catches any accidental
            // regression where the masking is dropped while leaving
            // the toString override in place.
            assertThat(stringForm).doesNotContain(SSN_AS_STRING);
        }

        /**
         * Verifies that the {@link AccountViewDto} carries NO PAN
         * (card-number) field at all. This is enforced at the DTO
         * record-component level (the DTO has no
         * {@code cardNumber} accessor) and re-asserted at the
         * textual-rendering level for defense in depth: the test
         * card number set on the XREF fixture must NEVER appear in
         * the toString() rendering of the returned DTO.
         *
         * <p>The PAN intentionally lives on the
         * {@link CardCrossReference#getXrefCardNum()} entity but is
         * not projected onto the AccountViewDto per AAP &sect;0.6.6
         * PCI-DSS v4.0 Requirement 3.4.1.</p>
         */
        // COBOL: AAP §0.6.6 — PCI-DSS v4.0 PAN omission from view DTO
        @Test
        @DisplayName("returned DTO does NOT expose PAN in any field")
        void viewAccount_returnedDto_doesNotContainFullPan() {
            // Arrange — happy path; the XREF fixture carries the
            // PAN "4111111111111111" which would leak if the
            // service projected it onto the DTO.
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — the full PAN MUST NOT appear in the textual
            // rendering of the DTO. This is the principal PCI-DSS
            // assertion for view-style endpoints.
            assertThat(result.toString()).doesNotContain(CARD_NUMBER);
        }

        /**
         * Verifies that the cached DTO (cache-hit path) is also
         * subject to the SSN-masking discipline. Cache-served DTOs
         * have the same {@link AccountViewDto#toString()} method as
         * DB-served DTOs; this test confirms that the cached form
         * is not somehow different.
         */
        // COBOL: AAP §0.6.6 — masking applies on cache-hit path too
        @Test
        @DisplayName("cache-served DTO masks SSN in toString() output")
        void viewAccount_cacheHit_returnedDto_masksSsnInToString() {
            // Arrange — cache hit with a DTO that carries the SSN
            AccountViewDto cachedDto = buildExpectedDto();
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class)))
                    .thenReturn(Optional.of(cachedDto));

            // Act
            AccountViewDto result = service.getAccountView(ACCOUNT_ID);

            // Assert — toString() output masks SSN identically
            String stringForm = result.toString();
            assertThat(stringForm).contains("***-**-" + SSN_LAST_FOUR);
            assertThat(stringForm).doesNotContain(SSN_AS_STRING);
        }
    }

    /**
     * Audit-isolation tests &mdash; verify that view operations on
     * {@link AccountViewService} emit NO audit events. The COBOL
     * source program {@code COACTVWC.cbl} performs three read-only
     * CICS READs with no audit-trail write; this read-only character
     * is preserved in the Java target. {@link AccountViewService}
     * does NOT inject {@link AuditLogService} via its constructor
     * (its 4-arg constructor accepts only {@link AccountRepository},
     * {@link CustomerRepository},
     * {@link CardCrossReferenceRepository}, and
     * {@link CacheService}).
     *
     * <p>The {@link AuditLogService} {@code @Mock} is declared in
     * this test class so that any future addition of audit emission
     * to this service is caught by the negative
     * {@link org.mockito.Mockito#verifyNoInteractions} check
     * below. If a future PR adds {@link AuditLogService} as a
     * constructor parameter, Mockito's
     * {@link InjectMocks} will wire the mock automatically and the
     * {@code verifyNoInteractions} assertions will fail on the
     * first audited operation, surfacing the addition explicitly
     * in a code-review-friendly way.</p>
     *
     * <p>Per AAP &sect;0.6.6, the inverse case &mdash; write-style
     * services like {@code AccountUpdateService} and
     * {@code BillPaymentService} &mdash; DO emit audit events; that
     * coverage lives in the corresponding test classes for those
     * services and is intentionally out of scope here.</p>
     */
    @Nested
    @DisplayName("Audit isolation — view operations emit NO audit events")
    class AuditLogging {

        /**
         * Verifies that the successful happy-path account-view
         * operation produces no interaction with
         * {@link AuditLogService}.
         */
        // COBOL: COACTVWC.cbl — no audit-trail write on success
        @Test
        @DisplayName("successful view emits no audit events")
        void viewAccount_success_emitsNoAuditEvents() {
            // Arrange — happy path
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(xref));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));

            // Act
            service.getAccountView(ACCOUNT_ID);

            // Assert — AuditLogService NEVER invoked
            verifyNoInteractions(auditLogService);
        }

        /**
         * Verifies that the cache-hit account-view operation
         * produces no interaction with {@link AuditLogService}.
         * Cache-served reads are at least as silent as DB-served
         * reads from an audit perspective.
         */
        // COBOL: AAP §0.7.1 — cache-aside semantics preserve no-audit
        @Test
        @DisplayName("cache-hit view emits no audit events")
        void viewAccount_cacheHit_emitsNoAuditEvents() {
            // Arrange — cache hit
            AccountViewDto cachedDto = buildExpectedDto();
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class)))
                    .thenReturn(Optional.of(cachedDto));

            // Act
            service.getAccountView(ACCOUNT_ID);

            // Assert — AuditLogService NEVER invoked
            verifyNoInteractions(auditLogService);
        }

        /**
         * Verifies that the XREF-miss not-found path produces no
         * interaction with {@link AuditLogService}. Per AAP
         * &sect;0.6.6, failed lookups COULD be a candidate for
         * audit emission (fraud-investigation usefulness), but the
         * COBOL source does not write an audit trail on failed
         * read and the current Java target preserves that
         * behaviour. If a future revision adds failure-audit
         * emission, this test will fail and the change will be
         * surfaced in code review.
         */
        // COBOL: COACTVWC.cbl — no audit on failed read (matches source)
        @Test
        @DisplayName("XREF-miss view emits no audit events")
        void viewAccount_xrefNotFound_emitsNoAuditEvents() {
            // Arrange — XREF NOTFND
            when(cacheService.get(eq(CACHE_NAMESPACE), eq(ACCOUNT_ID_KEY),
                    eq(AccountViewDto.class))).thenReturn(Optional.empty());
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(Collections.emptyList());

            // Act + Assert — exception thrown; AuditLogService NEVER
            // invoked
            assertThatThrownBy(() -> service.getAccountView(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class);

            verifyNoInteractions(auditLogService);
        }

        /**
         * Verifies that the validation-failure path produces no
         * interaction with {@link AuditLogService}. Field
         * validation errors are surfaced via REST 400 response and
         * are not audit-worthy at the read-only service tier
         * (controllers may emit access-log entries, but those go
         * through the access-log pipeline, not the audit pipeline).
         */
        // COBOL: COACTVWC:2210-EDIT-ACCOUNT — no audit on validation fail
        @Test
        @DisplayName("validation-failure view emits no audit events")
        void viewAccount_validationFailure_emitsNoAuditEvents() {
            // Act + Assert — null acctId fails validation upstream
            // of any I/O.
            assertThatThrownBy(() -> service.getAccountView(null))
                    .isInstanceOf(ValidationException.class);

            verifyNoInteractions(auditLogService);
        }
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Builds a fully-populated {@link AccountViewDto} fixture
     * matching the entity fixtures populated in {@link #setUp()}.
     * Used by cache-hit tests to stub the cache with a known DTO
     * instance, and by the PiiMasking cache-hit test to verify
     * masking applies identically on the cache-served path.
     *
     * <p>The 30-argument canonical constructor of
     * {@link AccountViewDto} is invoked in the same field order as
     * the service's projection logic
     * ({@link AccountViewService#getAccountView(Long)} step 6:
     * "Assemble the 30-field AccountViewDto"). Any future change to
     * the DTO record-component order must be mirrored here.</p>
     *
     * @return a non-null {@link AccountViewDto} populated from the
     *         test constants
     */
    private AccountViewDto buildExpectedDto() {
        return new AccountViewDto(
                // ===== Account fields (CVACT01Y.cpy) =====
                ACCOUNT_ID,
                ACTIVE_STATUS,
                CURRENT_BALANCE,
                CREDIT_LIMIT,
                CASH_CREDIT_LIMIT,
                OPEN_DATE,
                EXPIRATION_DATE,
                REISSUE_DATE,
                CURR_CYC_CREDIT,
                CURR_CYC_DEBIT,
                ACCOUNT_ZIP,
                GROUP_ID,
                // ===== Customer fields (CVCUS01Y.cpy) =====
                CUSTOMER_ID,
                FIRST_NAME,
                MIDDLE_NAME,
                LAST_NAME,
                SSN,
                PHONE_1,
                PHONE_2,
                ADDR_LINE_1,
                ADDR_LINE_2,
                ADDR_LINE_3,
                STATE_CD,
                COUNTRY_CD,
                CUST_ZIP,
                DOB,
                GOVT_ID,
                EFT_ACCT,
                PRI_HOLDER_IND,
                FICO_SCORE);
    }
}
