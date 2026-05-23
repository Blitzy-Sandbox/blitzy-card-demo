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
/*
 * OnlineTransactionE2ETest — End-to-End online transaction management journey.
 *
 * Migration parity for the chained CICS conversation:
 *
 *   COSGN00C (CC00) ──XCTL──▶ COMEN01C (CM00)
 *   COMEN01C (CM00) ──XCTL──▶ COTRN00C (CT00)   [list]
 *   COTRN00C (CT00) ──XCTL──▶ COTRN01C (CT01)   [view]
 *   COTRN00C (CT00) ──XCTL──▶ COTRN02C (CT02)   [add]
 *
 * Each COBOL transaction maps to a REST endpoint on the migrated controllers:
 *
 *   POST   /api/auth/sign-on              ← COSGN00C
 *   GET    /api/menu/main                 ← COMEN01C
 *   GET    /api/transactions?page=0       ← COTRN00C  (10 rows/page)
 *   GET    /api/transactions/{id}         ← COTRN01C  (16-char TRAN-ID lookup)
 *   POST   /api/transactions              ← COTRN02C  (auto-generated TRAN-ID)
 *
 * AAP references:
 *   §0.5.1  — End-to-End User Journey Tests row "OnlineTransactionE2ETest"
 *   §0.4.1  — Test strategy: full @SpringBootTest with TestRestTemplate
 *   §0.10.1 — Require Test Coverage rule: every assertion drives production code
 *             (TRAN-ID generation is asserted by length only — never re-derived
 *             by re-implementing the COBOL STARTBR-to-end + 1 algorithm in the
 *             test body)
 *   §0.10.3 — Financial precision: BigDecimal scale-2 asserted on every monetary
 *             field round-trip (CVTRA05Y TRAN-AMT PIC S9(09)V99)
 *   §0.10.4 — Immutable boundaries: TRAN-ID length parity with COBOL PIC X(16)
 *   §0.10.5 — Security: no plaintext password leak; PAN masking in list responses
 *   §0.10.7 — Framework constraint: JUnit 5 only (no JUnit 4, no PowerMock)
 *   §0.10.9 — Test independence: @Container is class-scoped (PER_CLASS lifecycle)
 *
 * Session continuity:
 *   The COBOL chain uses CICS XCTL with a shared commarea (CARDDEMO-COMMAREA
 *   from COCOM01Y.cpy) to carry user-identity and previous-program context
 *   across the five-program conversation. The Java migration replaces the
 *   commarea with HTTP session continuity — captured in step 1 via Set-Cookie
 *   header and/or session.token field and reused in every subsequent step
 *   through the {@link #sessionHeaders} instance field.
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *
 *   - The agent-prompt's request body for POST /api/transactions used field
 *     names "typeCode"/"categoryCode"/"originalDate"/"processedDate"/"confirm".
 *     The ACTUAL production TransactionAddRequest (verified by inspecting
 *     src/main/java/com/aws/carddemo/service/TransactionAddRequest.java)
 *     declares getters and setters for accountId, cardNumber,
 *     transactionTypeCode, transactionCategoryCode, source, description,
 *     amount, originDate, processDate, merchantId, merchantName,
 *     merchantCity, merchantZip — no "confirm" field. The test body uses the
 *     production field names. (Per AAP §0.10.4 immutable boundaries: the
 *     COBOL CONFIRMI BMS field gated screen-level confirmation in the
 *     mainframe UI; the Java migration relies on the REST POST semantics
 *     to capture the same intent, with no body-level confirm flag.)
 *
 *   - The agent-prompt's expected sign-on response shape used a top-level
 *     "userId" field and an optional "token". The ACTUAL production
 *     AuthenticationResult (verified by inspecting
 *     src/main/java/com/aws/carddemo/dto/auth/AuthenticationResult.java)
 *     returns { success, message, session: { userId, userType, loginTime,
 *     nextRoute } } — userId is nested inside session. Step 1 asserts the
 *     production shape and captures the session via Set-Cookie header and/or
 *     session.token field with both paths supported for forward compatibility.
 *
 *   - The agent-prompt's POST response was expected to expose "transactionId"
 *     as a top-level JSON field. The ACTUAL production TransactionAddJsonResponse
 *     (verified by inspecting src/main/java/com/aws/carddemo/controller/
 *     TransactionController.java line 603) carries { success, accountId,
 *     cardNumber, message } — the new TRAN-ID is embedded in the message
 *     text via MSG_TRANSACTION_ADD_SUCCESS_FORMAT
 *     ("Transaction added successfully. Your Transaction ID is %s.").
 *     Step 5 parses the 16-character TRAN-ID out of the success message
 *     via {@link #extractTransactionIdFromMessage(String)} — purely
 *     parsing the production HTTP surface, never re-deriving the algorithm
 *     that produced the value (AAP §0.10.1 Require Test Coverage rule).
 *
 *   - The agent-prompt's TransactionSummary expected a "tranId" field. The
 *     ACTUAL production projection (TransactionController.TransactionSummary)
 *     uses "transactionId" and "transactionType" (not "tranId" / "tranTypeCd").
 *     The test accepts either spelling for forward compatibility.
 */
package com.aws.carddemo.e2e;

// ---------------------------------------------------------------------------
// Internal project imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for the fixture user identifier
//     (REGULAR_USER_ID="USRTST01"), the BCrypt-paired plaintext password
//     (TEST_PASSWORD_PLAINTEXT="TESTPASS"), the sample account identifier
//     (Accounts.SAMPLE_ACCOUNT_ID_10="00000000010"), and the sample card
//     number (Cards.SAMPLE_CARD_NUMBER_01="4111111111111101"). Per AAP
//     §0.10.5 the plaintext is a FIXTURE credential, not a real secret —
//     it is documented openly because it cannot authenticate against any
//     production system.
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// Jackson JSON tree model — JsonNode parses every HTTP response body
// (sign-on, main menu, transaction list, transaction detail, add response)
// so the test can call has(field) and get(field).asText()/asLong() without
// binding to concrete DTO classes — keeping the test resilient to
// controller-side DTO field-naming variations (e.g., transactionId vs
// tranId, transactionType vs tranTypeCd, amount vs tranAmt).
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.JsonNode;

// ---------------------------------------------------------------------------
// JUnit Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test marks each test method.
//   * @DisplayName provides human-readable test names.
//   * @Disabled defers execution until the cross-folder production
//     prerequisites enumerated in the class Javadoc are satisfied by
//     subsequent migration agents. JUnit 5 reports @Disabled tests as
//     "skipped" (not "failed") so the Surefire build remains green; the
//     reactivation criteria appear in the annotation's value attribute and
//     in the class-level Javadoc "Reactivation Checklist" section.
//   * @Order + @TestMethodOrder(MethodOrderer.OrderAnnotation.class) drive
//     the sequential 6-step journey: sign-on → menu → list → view → add →
//     verify.
//   * @TestInstance(Lifecycle.PER_CLASS) enables non-static instance fields
//     (sessionHeaders, capturedTransactionId, addedTransactionId) that
//     persist across the ordered test methods — required because the
//     journey shares state between steps (the COBOL commarea equivalent).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

// ---------------------------------------------------------------------------
// Spring Framework — DI annotation + HTTP primitives (AAP §0.6.2).
//
//   * @Autowired injects the TestRestTemplate bean.
//   * HttpEntity / HttpHeaders / HttpMethod / HttpStatus / MediaType /
//     ResponseEntity drive every restTemplate.exchange(...) call.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// ---------------------------------------------------------------------------
// Spring Boot Test — @SpringBootTest boots the full application context on
// an ephemeral port; TestRestTemplate is the HTTP client used to drive
// every endpoint in the 6-step journey.
// ---------------------------------------------------------------------------
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

// ---------------------------------------------------------------------------
// Spring Test — @ActiveProfiles activates application-test.properties,
// @DynamicPropertySource + DynamicPropertyRegistry wire the Testcontainers
// PostgreSQL JDBC URL/credentials and disable spring.batch.job.enabled.
// ---------------------------------------------------------------------------
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// ---------------------------------------------------------------------------
// Testcontainers JUnit 5 integration — @Testcontainers enables container
// lifecycle management; @Container marks the static PostgreSQLContainer as
// managed (start before all tests, stop after).
// ---------------------------------------------------------------------------
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// ---------------------------------------------------------------------------
// Java standard library
//
//   * BigDecimal — Scale-2 assertions on monetary fields per AAP §0.10.3
//     (no float/double allowed for any monetary value).
//   * LinkedHashMap — Insertion-order preserved JSON body for add/list
//     requests so the on-the-wire shape mirrors the COBOL field declaration
//     order in CVTRA05Y.cpy.
//   * List — Set-Cookie header extraction.
//   * Map — Compact sign-on request body construction.
// ---------------------------------------------------------------------------
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// AssertJ — fluent assertion library (AAP §0.10.10).
// Static import enables the canonical `assertThat(...)` idiom.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sequential end-to-end test for the online transaction management journey.
 * Boots the full Spring Boot application context against a per-class
 * Testcontainers PostgreSQL 16-alpine instance and drives the 6-step
 * regular-user CRUD journey via {@link TestRestTemplate}.
 *
 * <h2>Journey Steps</h2>
 * <ol>
 *   <li>Regular user signs in via {@code POST /api/auth/sign-on}; captures
 *       session.</li>
 *   <li>Regular user loads the main menu via {@code GET /api/menu/main}.</li>
 *   <li>Regular user lists transactions via {@code GET /api/transactions?page=0}
 *       and captures the first row's 16-character {@code TRAN-ID}.</li>
 *   <li>Regular user views the captured transaction via
 *       {@code GET /api/transactions/{id}} and asserts BigDecimal scale-2
 *       parity with COBOL {@code TRAN-AMT PIC S9(09)V99}.</li>
 *   <li>Regular user adds a new transaction via {@code POST /api/transactions}
 *       and captures the auto-generated 16-character {@code TRAN-ID} from
 *       the {@code "Transaction added successfully. Your Transaction ID is
 *       XXX."} success message.</li>
 *   <li>Regular user verifies persistence via {@code GET /api/transactions/
 *       {newId}} and asserts every submitted field round-trips correctly
 *       (amount BigDecimal scale 2 preserved, description echoed verbatim,
 *       transaction-type code matches the submitted value).</li>
 * </ol>
 *
 * <h2>Test Isolation</h2>
 *
 * <p>{@link TestInstance.Lifecycle#PER_CLASS} keeps a single instance across
 * all six ordered test methods so that instance fields ({@link #sessionHeaders},
 * {@link #capturedTransactionId}, {@link #addedTransactionId}) carry state
 * between steps. The {@link PostgreSQLContainer} is class-scoped (single
 * static {@code @Container} field) so the database lifecycle aligns with the
 * test instance lifecycle — exactly the AAP §0.4.4 specification. Each test
 * class owns its own container; no shared state with sibling E2E classes.
 *
 * <h2>Why this class is currently {@code @Disabled}</h2>
 *
 * <p>The journey loads the full Spring Boot application context via
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} and then exercises
 * production REST endpoints with real HTTP traffic. That requires every
 * production class transitively reachable from the loaded context to be
 * Spring-managed and externally configured. As of this commit several of
 * those production-side prerequisites are <em>intentionally deferred</em>
 * by the REFACTOR-flavor migration agents.
 *
 * <p>This testing-flavor AAP (§0.8.1 "Cross-cutting files that the migration
 * creates and that this Action Plan exercises (but does not own)")
 * <strong>explicitly forbids</strong> modifying any production source under
 * {@code src/main/java/com/aws/carddemo/} from the testing flavor:
 * the testing flavor CREATEs tests against those classes but does NOT
 * modify them. The journey is therefore registered, compiled, and
 * preserved end-to-end, but the JUnit Jupiter {@code @Disabled} marker
 * below defers <em>runtime</em> execution until the production-side
 * migration agents complete their work.
 *
 * <h3>Reactivation Checklist (for the next agent)</h3>
 *
 * <p>Remove the {@code @Disabled} annotation (and the unused
 * {@code org.junit.jupiter.api.Disabled} import) once <em>all</em> of the
 * following production-side prerequisites are in place. The checklist
 * mirrors the "Cross-Folder Dependencies" section of this file's agent
 * prompt:
 *
 * <ol>
 *   <li><strong>{@code @Service} stereotype</strong> on every production
 *       service class under {@code src/main/java/com/aws/carddemo/service/}.
 *       The endpoints exercised by this journey require Spring's
 *       constructor injection of {@code AuthenticationService},
 *       {@code MainMenuService}, {@code TransactionListService},
 *       {@code TransactionDetailService}, and {@code TransactionAddService}
 *       (5 of the 17 service classes documented in
 *       {@code AdminUserManagementE2ETest}'s reactivation checklist).
 *       Without the stereotype, Spring's component scan never registers
 *       these classes as beans, so the {@code AuthController} /
 *       {@code MenuController} / {@code TransactionController} constructor
 *       injection fails on context refresh with
 *       {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}.
 *   </li>
 *   <li><strong>{@code SecurityConfig}</strong> wiring Spring Security with
 *       at least a permissive {@code SecurityFilterChain} that allows
 *       authenticated callers to reach {@code /api/menu/main} and
 *       {@code /api/transactions/**}. The {@code MenuController#getMainMenu}
 *       method receives a {@link org.springframework.security.core.Authentication}
 *       parameter that requires the Spring Security filter chain to populate
 *       the security context for every authenticated request. Without it,
 *       the menu and transaction endpoints reject every request with HTTP 401
 *       even when the sign-on succeeded.
 *   </li>
 *   <li><strong>Flyway migrations</strong> under
 *       {@code src/main/resources/db/migration/}:
 *       <ul>
 *         <li>{@code V1__schema.sql} — DDL for the {@code user_security}
 *             table, the {@code transactions} table (with TRAN-AMT as
 *             NUMERIC(11,2) for BigDecimal scale-2 parity), and the
 *             {@code accounts} / {@code cards} / {@code card_xref} tables;
 *             column types must match the JPA {@code @Entity} mappings in
 *             {@code com.aws.carddemo.entity.*}.</li>
 *         <li>{@code V3__seed.sql} — seed rows for the fixture users
 *             {@code TestFixtures.Users.REGULAR_USER_ID} (with
 *             {@code password_hash} populated from
 *             {@code TestFixtures.Users.TEST_PASSWORD_BCRYPT_HASH} so
 *             BCrypt verification of {@code TEST_PASSWORD_PLAINTEXT}
 *             succeeds), the 50-record account fixture (so
 *             {@code TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10} is
 *             present), the 50-record card fixture (so
 *             {@code TestFixtures.Cards.SAMPLE_CARD_NUMBER_01} is
 *             present), and at minimum one row in the transactions
 *             table (so step 3's first-page list returns at least one
 *             row to capture).</li>
 *       </ul>
 *   </li>
 *   <li><strong>{@code CardDemoApplication}</strong> already exists (verified
 *       at this commit) and is correctly annotated with
 *       {@code @SpringBootApplication}. No action required for this prereq.
 *   </li>
 * </ol>
 *
 * <p>None of the items above are owned by the testing flavor. They are
 * REFACTOR-flavor work that the AAP §0.5.1 row for this file references
 * indirectly via the "Cross-Folder Dependencies" section in the agent
 * prompt and via §0.8.1's carve-out for production source files.
 *
 * <h3>What is NOT a runtime blocker (already addressed)</h3>
 *
 * <p>The AWS Spring Cloud auto-configurations ({@code S3AutoConfiguration},
 * {@code SqsAutoConfiguration}, {@code SnsAutoConfiguration}, plus the
 * {@code S3CrtAsyncClientAutoConfiguration} and
 * {@code S3TransferManagerAutoConfiguration} satellites) <em>were</em> a
 * blocker prior to this commit — they fail context refresh with
 * {@code "Unable to load region from any of the providers"} when the test
 * JVM has no AWS region configured. The fix is centralised in
 * {@code src/test/resources/application-test.properties} via
 * {@code spring.autoconfigure.exclude=...} so every future E2E and IT
 * automatically inherits the exclusion without re-stating it.
 *
 * <p>The Testcontainers/Spring lifecycle ordering issue (where
 * {@code PER_CLASS} causes Spring's {@code postProcessTestInstance} to
 * run before the {@code @Testcontainers} extension's {@code beforeAll},
 * making {@code POSTGRES.getJdbcUrl()} fail with "Mapped port can only
 * be obtained after the container is started") is solved by the static
 * initialiser ({@code static} block calling {@code POSTGRES.start()})
 * declared below the {@code @Container} field.
 *
 * <h3>How to verify reactivation worked</h3>
 *
 * <pre>{@code
 * mvn -B -Dtest=OnlineTransactionE2ETest test
 * }</pre>
 *
 * <p>Expected outcome after reactivation: {@code Tests run: 6, Failures: 0,
 * Errors: 0, Skipped: 0}. Until then the same invocation produces
 * {@code Tests run: 6, Failures: 0, Errors: 0, Skipped: 6} — the build
 * stays green and the journey is preserved verbatim for future activation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// ---------------------------------------------------------------------------
// AAP §0.5.4 — Test fixture state. The seed under
// src/test/resources/db/seed/e2e-fixtures.sql carries the 252 INSERTs
// (2 security_users + 50 customers + 50 accounts + 50 cards + 50 card_xref
// + 50 transactions) that the E2E journey depends on. The seed is NOT
// bundled into the production V3__seed.sql Flyway migration because the
// IT layer's per-table repository tests (CustomerRepositoryIT etc.) INSERT
// their own synthetic records under the same primary-key ranges that the
// E2E seed uses — bundling would crash those ITs with PK violations.
//
// executionPhase = BEFORE_TEST_METHOD (not BEFORE_TEST_CLASS) — required
// because BEFORE_TEST_CLASS fires from SpringExtension.beforeAll BEFORE
// the TestcontainersExtension has started the @Container PostgreSQLContainer,
// causing @DynamicPropertySource's POSTGRES::getJdbcUrl lambda to throw
// "Mapped port can only be obtained after the container is started"
// during context load. BEFORE_TEST_METHOD defers script execution until
// after all @BeforeAll hooks complete (container started, context
// refreshed). The script is IDEMPOTENT (every INSERT carries
// ON CONFLICT DO NOTHING) so per-method invocation overhead is minimal
// and re-application is safe.
// ---------------------------------------------------------------------------
@org.springframework.test.context.jdbc.Sql(
        scripts = "/db/seed/e2e-fixtures.sql",
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("Online Transaction E2E — sign-on → menu → list → view → add round-trip parity with COBOL "
        + "COSGN00C→COMEN01C→COTRN00C→COTRN01C→COTRN02C")
class OnlineTransactionE2ETest {

    // =========================================================================
    // Test-scoped constants
    // =========================================================================

    /**
     * 2-character transaction-type code submitted in step 5 — {@code "01"}
     * (Purchase) per {@code trantype.txt}. Mirrored from
     * {@link TestFixtures.Transactions#TRAN_TYPE_PURCHASE} so the literal is
     * not duplicated across test files. Matches the COBOL
     * {@code TRAN-TYPE-CD PIC X(02)} field width per {@code CVTRA05Y.cpy}.
     */
    private static final String NEW_TXN_TYPE_CODE = TestFixtures.Transactions.TRAN_TYPE_PURCHASE;

    /**
     * 4-character transaction-category code submitted in step 5 —
     * {@code "0001"} (Regular Sales Draft) per {@code trancatg.txt}. Mirrored
     * from {@link TestFixtures.Transactions#TRAN_CAT_REGULAR_SALES}. Matches
     * the COBOL {@code TRAN-CAT-CD PIC 9(04)} field width per
     * {@code CVTRA05Y.cpy}.
     */
    private static final String NEW_TXN_CATEGORY_CODE = TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES;

    /**
     * 10-character transaction-source literal submitted in step 5 —
     * {@code "POS TERM  "} (POS-originated; space-padded to 10 characters per
     * the COBOL {@code TRAN-SOURCE PIC X(10)} field width). Mirrored from
     * {@link TestFixtures.Transactions#TRAN_SOURCE_POS}.
     */
    private static final String NEW_TXN_SOURCE = TestFixtures.Transactions.TRAN_SOURCE_POS;

    /**
     * Human-readable transaction description submitted in step 5. Used by the
     * step-6 verification assertion to confirm the description round-trips
     * verbatim. Length 17 ≤ 100 — fits the COBOL {@code TRAN-DESC PIC X(100)}
     * field width per {@code CVTRA05Y.cpy}. The literal is kept here (not in
     * {@link TestFixtures}) because it is consumed by only this single E2E
     * class — adding it to {@link TestFixtures} would broaden the shared
     * surface for no reuse benefit.
     */
    private static final String NEW_TXN_DESCRIPTION = "E2E TEST PURCHASE";

    /**
     * Monetary amount submitted in step 5, expressed as a {@link String}
     * literal so the on-the-wire JSON shape is a quoted string that Jackson
     * deserialises into a {@code BigDecimal} on the server side (per AAP
     * §0.10.3: "No float or double used for any monetary value — BigDecimal
     * exclusively"). Scale 2 matches the COBOL {@code TRAN-AMT PIC S9(09)V99}
     * field. Step 6 parses the round-tripped value back into a
     * {@link BigDecimal} and asserts both value equality (via
     * {@code isEqualByComparingTo}) and {@code .scale() == 2}.
     */
    private static final String NEW_TXN_AMOUNT = "25.50";

    /**
     * Pre-parsed {@link BigDecimal} form of {@link #NEW_TXN_AMOUNT}. Used as
     * the right-hand operand of step-6's
     * {@code assertThat(amt).isEqualByComparingTo(NEW_TXN_AMOUNT_BD)}
     * assertion so the test never re-derives the expected value at the
     * assertion site (AAP §0.10.1 Require Test Coverage rule: no arithmetic
     * or transformation in test bodies).
     */
    private static final BigDecimal NEW_TXN_AMOUNT_BD = new BigDecimal(NEW_TXN_AMOUNT);

    /**
     * 9-digit merchant-ID submitted in step 5 — matches the COBOL
     * {@code TRAN-MERCHANT-ID PIC 9(09)} field width per {@code CVTRA05Y.cpy}.
     * The chosen value is a synthetic test merchant ID that cannot collide
     * with the dailytran-derived seed data (every seed-row merchant ID is
     * in the 8-digit zero-padded numeric range; {@code "000000001"} fits the
     * width but is reserved for synthetic adds).
     */
    private static final String NEW_TXN_MERCHANT_ID = "000000001";

    /**
     * Merchant name submitted in step 5. Length 13 ≤ 50 — fits the COBOL
     * {@code TRAN-MERCHANT-NAME PIC X(50)} field width per {@code CVTRA05Y.cpy}.
     */
    private static final String NEW_TXN_MERCHANT_NAME = "TEST MERCHANT";

    /**
     * Merchant city submitted in step 5. Length 8 ≤ 50 — fits the COBOL
     * {@code TRAN-MERCHANT-CITY PIC X(50)} field width per {@code CVTRA05Y.cpy}.
     */
    private static final String NEW_TXN_MERCHANT_CITY = "TESTCITY";

    /**
     * Merchant ZIP submitted in step 5. Length 5 ≤ 10 — fits the COBOL
     * {@code TRAN-MERCHANT-ZIP PIC X(10)} field width per {@code CVTRA05Y.cpy}.
     */
    private static final String NEW_TXN_MERCHANT_ZIP = "00001";

    /**
     * Origin date submitted in step 5 in {@code YYYY-MM-DD} format. Matches
     * the {@code CSUTLDTC} (date validation utility) accepted format per AAP
     * §0.5.1 row "DateValidationServiceTest". Value chosen from the
     * dailytran-derived date range ({@code REPT-START-DATE} through
     * {@code REPT-END-DATE} per {@link TestFixtures.Dates}) so the seed
     * window applies.
     */
    private static final String NEW_TXN_ORIGIN_DATE = "2022-07-06";

    /**
     * Process date submitted in step 5 — same value as
     * {@link #NEW_TXN_ORIGIN_DATE} (same-day processing, mirroring the COBOL
     * pattern in {@code dailytran.txt} where every record carries identical
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}).
     */
    private static final String NEW_TXN_PROCESS_DATE = "2022-07-06";

    /**
     * Width in characters of the COBOL {@code TRAN-ID PIC X(16)} primary key
     * per {@code CVTRA05Y.cpy}. Used in step 3, step 4, step 5, and step 6
     * to assert that every transaction identifier the production layer
     * surfaces (whether captured from the list, returned from the detail
     * endpoint, or auto-generated by the add endpoint) matches the COBOL
     * field width verbatim. Mirrored from
     * {@link TestFixtures.RecordWidths#TRAN_ID_WIDTH} so the source of truth
     * remains the COBOL copybook width constant.
     */
    private static final int TRAN_ID_WIDTH = TestFixtures.RecordWidths.TRAN_ID_WIDTH;

    /**
     * Fixed page size for the {@code GET /api/transactions} endpoint —
     * 10 rows per page, matching the COBOL {@code WS-IDX > 10} loop bound
     * in {@code COTRN00C.cbl} and the COTRN0AO BMS map
     * {@code TRAN-REC OCCURS 10 TIMES} declaration. Step 3 asserts the
     * returned content array size is at most this value.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Minimum number of options on the regular-user main menu per
     * {@code COMEN02Y.cpy} (the COBOL menu-options copybook). The current
     * {@code MenuController#MAIN_MENU_OPTIONS} carries 10 entries
     * (ACCOUNT_VIEW, ACCOUNT_UPDATE, CARD_LIST, CARD_VIEW, CARD_UPDATE,
     * TRANSACTION_LIST, TRANSACTION_VIEW, TRANSACTION_ADD, REPORTS,
     * BILL_PAYMENT). Step 2 uses {@code >= 10} so a future addition cannot
     * regress; the AAP §0.10.4 Immutable Boundaries clause ensures the
     * order and the existing entries never change byte-for-byte, but new
     * entries may be appended in subsequent migration phases.
     */
    private static final int MIN_REGULAR_MENU_OPTIONS = 10;

    // =========================================================================
    // Testcontainers — per-class PostgreSQL 16-alpine
    // =========================================================================

    /**
     * Ephemeral PostgreSQL 16-alpine container. Lifecycle-managed by
     * {@code @Testcontainers}: started before the first @Test in the class,
     * stopped after the last. Container scope (class-level static) aligns
     * with {@link TestInstance.Lifecycle#PER_CLASS} so the journey shares a
     * single database instance across all 6 ordered steps.
     *
     * <p>Per AAP §0.4.4 (Test Data and Fixtures Design) and §0.9.1
     * (Environment setup requirements): the container is provisioned
     * automatically; the production Flyway migrations under
     * {@code src/main/resources/db/migration/} (V1__schema.sql,
     * V2__indexes.sql, V3__seed.sql) hydrate the schema and seed data
     * (including the regular fixture user whose BCrypt hash pairs with
     * {@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT} and the
     * dailytran-derived transactions table so the list-step has data to
     * return).
     *
     * <p><strong>Eager start.</strong> With {@link TestInstance.Lifecycle#PER_CLASS}
     * the Spring TestContext framework's {@code postProcessTestInstance}
     * runs before JUnit's {@code @BeforeAll} (and therefore before the
     * Testcontainers extension's {@code beforeAll} hook would normally
     * start the container) — yet {@code @DynamicPropertySource} requires a
     * started container to resolve {@code POSTGRES.getJdbcUrl()}. The
     * static initialiser below ({@code static { POSTGRES.start(); }})
     * starts the container at class-load time so the JDBC URL is
     * resolvable by the time Spring queries the dynamic property registry.
     * The container's {@code stop()} is invoked by Testcontainers'
     * JVM-shutdown hook (Ryuk reaper) — no explicit teardown is required
     * here.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("carddemo")
            .withPassword("carddemo");

    // Eagerly start the container at class-load time so that the
    // @DynamicPropertySource registration below resolves a real JDBC URL
    // even when @TestInstance(PER_CLASS) causes Spring's context-load to
    // run before the @Testcontainers JUnit extension's beforeAll callback.
    // The container is reaped at JVM shutdown by the Testcontainers Ryuk
    // sidecar, so no explicit teardown is needed.
    static {
        POSTGRES.start();
    }

    /**
     * Wires the Testcontainers-allocated PostgreSQL JDBC URL/credentials
     * into Spring's {@code Environment} before the application context
     * starts. {@code spring.batch.job.enabled=false} prevents Spring Batch
     * from auto-launching every {@code @Bean Job} on context start — the
     * AAP §0.5.4 directive (also enforced project-wide in
     * {@code application-test.properties}, repeated here for defence in
     * depth: an online E2E test should never trigger a batch job).
     *
     * @param registry Spring's dynamic-property registry, injected by the
     *                 Spring TestContext framework before context refresh
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.batch.job.enabled", () -> "false");
    }

    // =========================================================================
    // Injected collaborators + per-test-instance state
    // =========================================================================

    /**
     * Spring-Boot-provided HTTP client. Bound to the random server port
     * Spring Boot allocates for the embedded servlet container. Used by
     * every step in the journey to invoke production REST endpoints.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Session headers captured at Step 1 (sign-on) and reused across Steps
     * 2–6 to drive the journey as the same authenticated regular user.
     * Set in
     * {@link #step1_signOn_withSeededRegularUser_succeedsAndCapturesSession()}
     * and consumed by every subsequent step. The
     * {@link TestInstance.Lifecycle#PER_CLASS} test instance lifecycle
     * ensures this field's value persists across the ordered test methods.
     *
     * <p>The migrated session-continuity mechanism may surface as either:
     * <ul>
     *   <li>HTTP-cookie based (Set-Cookie header in the sign-on response,
     *       reused as a {@code Cookie} request header on subsequent
     *       requests), OR</li>
     *   <li>Bearer-token based (a {@code token} field on the sign-on response
     *       body, reused as an {@code Authorization: Bearer ...} header on
     *       subsequent requests).</li>
     * </ul>
     * Step 1 captures whichever mechanism the production layer surfaces;
     * subsequent steps reuse the captured headers without caring which
     * mechanism is in use (forward compatibility against either design).
     */
    private HttpHeaders sessionHeaders;

    /**
     * 16-character {@code TRAN-ID} captured from the first row of the Step 3
     * list response and consumed by Step 4 to drive the detail-lookup
     * endpoint. The value is opaque to the test — Step 3 captures whatever
     * the production layer returns as the first row, and Step 4 passes it
     * through verbatim. Per AAP §0.10.1 (Require Test Coverage rule), the
     * test never re-derives this identifier from the seeded fixture; it
     * exclusively consumes what the production list endpoint emits.
     */
    private String capturedTransactionId;

    /**
     * 16-character {@code TRAN-ID} captured from the Step 5 add response
     * (parsed from the success message
     * {@code "Transaction added successfully. Your Transaction ID is XXX."})
     * and consumed by Step 6 to verify post-add persistence. Per AAP §0.10.1
     * (Require Test Coverage rule), the test never re-derives this
     * identifier from the COBOL {@code STARTBR-to-end + 1} algorithm — it
     * exclusively consumes what the production add endpoint generates.
     */
    private String addedTransactionId;

    // =========================================================================
    // Step 1 — Sign-on (COSGN00C → POST /api/auth/sign-on)
    // =========================================================================

    /**
     * Verifies regular-user authentication: a {@code POST /api/auth/sign-on}
     * with the seeded regular-user credentials returns HTTP 200, surfaces a
     * session object carrying {@code userId=USRTST01} and (where exposed)
     * {@code userType="U"} (per {@code CSUSR01Y.cpy} SEC-USR-TYPE), and
     * produces session headers (cookie and/or bearer token) for subsequent
     * authenticated requests in the journey.
     *
     * <p>PCI assertions (AAP §0.10.5):
     * <ul>
     *   <li>The response body MUST NOT echo back the plaintext password
     *       ({@link TestFixtures.Users#TEST_PASSWORD_PLAINTEXT}).</li>
     *   <li>The response body MUST NOT carry any BCrypt hash prefix
     *       ({@code $2a$}, {@code $2b$}, {@code $2y$}) — the migrated
     *       {@code AuthenticationService} performs server-side BCrypt
     *       verification but never echoes the hash on the wire.</li>
     * </ul>
     */
    @Test
    @Order(1)
    @DisplayName("Step 1 — POST /api/auth/sign-on with seeded regular user credentials succeeds (parity with COSGN00C credential check)")
    void step1_signOn_withSeededRegularUser_succeedsAndCapturesSession() {
        // Arrange — small immutable body via Map.of (no mutation required).
        // The plaintext password value comes from TestFixtures
        // (TEST_PASSWORD_PLAINTEXT="TESTPASS") so the test contains no
        // duplicated credential literal — the BCrypt-paired value lives in
        // exactly one place and propagates here by reference.
        Map<String, String> body = Map.of(
                "userId", TestFixtures.Users.REGULAR_USER_ID,
                "password", TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, requestHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/sign-on", HttpMethod.POST, request, JsonNode.class);

        // Assert — happy-path HTTP status
        assertThat(response.getStatusCode())
                .as("Sign-on with valid seeded regular-user credentials must return HTTP 200 OK "
                        + "(parity with COSGN00C 'Welcome <FIRST> <LAST>...' success path)")
                .isEqualTo(HttpStatus.OK);

        JsonNode responseBody = response.getBody();
        assertThat(responseBody)
                .as("Sign-on response body must not be null on success")
                .isNotNull();

        // PCI (AAP §0.10.5) — plaintext password MUST NOT leak back in any
        // form. doesNotContain is the strict assertion; the auxiliary
        // doesNotContainIgnoringCase("password") catches both the password
        // VALUE and any incidentally serialised "password" field name.
        String bodyStr = responseBody.toString();
        assertThat(bodyStr)
                .as("Sign-on response body MUST NOT echo the plaintext password value (AAP §0.10.5)")
                .doesNotContain(TestFixtures.Users.TEST_PASSWORD_PLAINTEXT);
        // PCI — no BCrypt hash either; the encoded password lives only in
        // the user_security table, never on the wire.
        assertThat(bodyStr)
                .as("Sign-on response body MUST NOT contain any BCrypt hash prefix (AAP §0.10.5)")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$")
                .doesNotContain("$2y$");

        // Session/role surfacing — the production AuthenticationResult shape
        // is { success, message, session: { userId, userType, loginTime,
        // nextRoute } }. The test tolerates a flatter shape with top-level
        // userId / userType / role fields for forward compatibility against
        // schema variants (e.g., if a future migration phase moves userId to
        // the top level).
        boolean hasSession = responseBody.has("session") && !responseBody.get("session").isNull();
        boolean hasTopLevelUserId = responseBody.has("userId") && !responseBody.get("userId").isNull();
        boolean hasTopLevelRole = responseBody.has("role") && !responseBody.get("role").isNull();
        boolean hasTopLevelUserType = responseBody.has("userType") && !responseBody.get("userType").isNull();
        assertThat(hasSession || hasTopLevelUserId || hasTopLevelRole || hasTopLevelUserType)
                .as("Sign-on response must expose userId/userType via session.userId/session.userType, "
                        + "or via top-level userId/role/userType fields")
                .isTrue();

        if (hasSession) {
            JsonNode session = responseBody.get("session");
            assertThat(session.has("userId"))
                    .as("session.userId must be present on a successful sign-on (COSGN00C "
                            + "CDEMO-USER-ID parity per COCOM01Y.cpy)")
                    .isTrue();
            assertThat(session.get("userId").asText())
                    .as("session.userId must echo the signed-on regular user")
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
            // CSUSR01Y.cpy SEC-USR-TYPE='U' for regular user (NOT 'A').
            if (session.has("userType") && !session.get("userType").isNull()) {
                assertThat(session.get("userType").asText())
                        .as("session.userType must be 'U' for a regular signed-on user "
                                + "(CSUSR01Y.cpy SEC-USR-TYPE; NEVER 'A' which is admin)")
                        .isEqualTo("U");
            }
            // The COBOL COSGN00C XCTLs to COMEN01C for regular users; the
            // migration's session.nextRoute carries the equivalent hint
            // ("MAIN_MENU"). The assertion uses containsIgnoringCase to be
            // resilient to value variants (e.g., "MainMenu", "main_menu",
            // "MAIN_MENU").
            if (session.has("nextRoute") && !session.get("nextRoute").isNull()) {
                assertThat(session.get("nextRoute").asText())
                        .as("session.nextRoute must be MAIN_MENU for a regular user "
                                + "(COSGN00C XCTL COMEN01C parity)")
                        .containsIgnoringCase("MAIN");
            }
        } else if (hasTopLevelUserId) {
            assertThat(responseBody.get("userId").asText())
                    .as("top-level userId must match the signed-on regular user")
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
        }

        // Capture session credentials for reuse across Steps 2–6. The
        // sessionHeaders field carries the Cookie + Authorization context so
        // every subsequent restTemplate.exchange(...) call inherits the
        // authenticated identity — the Java equivalent of the COBOL
        // CARDDEMO-COMMAREA being passed via EXEC CICS XCTL.
        sessionHeaders = new HttpHeaders();
        sessionHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Forward any Set-Cookie header(s) returned by the sign-on as a single
        // Cookie request header on subsequent calls. The List.of-derived
        // pattern is the canonical way to round-trip multiple Set-Cookie
        // values through TestRestTemplate (the framework concatenates them
        // with "; " into a single Cookie header).
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null && !cookies.isEmpty()) {
            sessionHeaders.add(HttpHeaders.COOKIE, String.join("; ", cookies));
        }

        // If the production design uses bearer tokens (e.g., JWT), capture the
        // token from the response body and add an Authorization header. The
        // token may live at the top level (responseBody.token) or inside the
        // session object (responseBody.session.token); both are checked for
        // forward compatibility.
        if (responseBody.has("token") && !responseBody.get("token").isNull()) {
            sessionHeaders.setBearerAuth(responseBody.get("token").asText());
        } else if (hasSession) {
            JsonNode session = responseBody.get("session");
            if (session.has("token") && !session.get("token").isNull()) {
                sessionHeaders.setBearerAuth(session.get("token").asText());
            }
        }
    }

    // =========================================================================
    // Step 2 — Main menu (COMEN01C → GET /api/menu/main)
    // =========================================================================

    /**
     * Verifies the main menu surface: a {@code GET /api/menu/main} as an
     * authenticated regular user returns HTTP 200 and a non-empty
     * {@code options} array (per the COBOL {@code COMEN02Y.cpy}
     * {@code CDEMO-MENU-OPT-*} array structure). The
     * {@code MenuController#MAIN_MENU_OPTIONS} list currently publishes 10
     * entries (ACCOUNT_VIEW, ACCOUNT_UPDATE, CARD_LIST, CARD_VIEW,
     * CARD_UPDATE, TRANSACTION_LIST, TRANSACTION_VIEW, TRANSACTION_ADD,
     * REPORTS, BILL_PAYMENT) lifted verbatim from {@code COMEN02Y.cpy}; the
     * assertion uses {@code >= MIN_REGULAR_MENU_OPTIONS} so future option
     * additions cannot regress.
     *
     * <p>Per {@code COMEN02Y.cpy}, every regular-menu entry has
     * {@code CDEMO-MENU-OPT-USRTYPE = 'U'} — there are no admin-only items
     * in the main menu. The assertion verifies no entry carries
     * {@code userType="A"} (defence-in-depth against a future option
     * being incorrectly marked admin-only).
     */
    @Test
    @Order(2)
    @DisplayName("Step 2 — GET /api/menu/main returns regular-user menu options (parity with COMEN01C BUILD-MENU-OPTIONS)")
    void step2_loadMainMenu_authenticated_returnsMenuOptionsForRegularUser() {
        // Arrange
        assertThat(sessionHeaders)
                .as("Session headers must have been captured in step 1 — the journey is sequential")
                .isNotNull();
        HttpEntity<Void> request = new HttpEntity<>(sessionHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/menu/main", HttpMethod.GET, request, JsonNode.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("Authenticated regular user on GET /api/menu/main must receive HTTP 200")
                .isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        assertThat(body)
                .as("Main menu response body must not be null on success")
                .isNotNull();

        // The production MainMenuView carries fields { userId, options,
        // adminAccess }. The userId field, if present, must echo the
        // authenticated regular user (defence-in-depth against
        // cross-session contamination).
        if (body.has("userId") && !body.get("userId").isNull()) {
            assertThat(body.get("userId").asText())
                    .as("Main menu userId must echo the authenticated regular user")
                    .isEqualTo(TestFixtures.Users.REGULAR_USER_ID);
        }

        // Options array — the COBOL CDEMO-MENU-OPT-* array becomes the JSON
        // `options` array.
        assertThat(body.has("options"))
                .as("Main menu response must carry an 'options' array "
                        + "(CDEMO-MENU-OPT-* array from COMEN02Y.cpy)")
                .isTrue();
        JsonNode options = body.get("options");
        assertThat(options.isArray())
                .as("'options' must be a JSON array")
                .isTrue();
        assertThat(options.size())
                .as("Regular-user menu has at least " + MIN_REGULAR_MENU_OPTIONS
                        + " options per COMEN02Y.cpy (ACCOUNT_VIEW, ACCOUNT_UPDATE, CARD_LIST, "
                        + "CARD_VIEW, CARD_UPDATE, TRANSACTION_LIST, TRANSACTION_VIEW, "
                        + "TRANSACTION_ADD, REPORTS, BILL_PAYMENT)")
                .isGreaterThanOrEqualTo(MIN_REGULAR_MENU_OPTIONS);

        // Defence-in-depth — assert no entry leaks an admin-only userType.
        // COMEN02Y.cpy declares every entry CDEMO-MENU-OPT-USRTYPE='U';
        // a future entry incorrectly marked 'A' would surface here.
        for (JsonNode option : options) {
            if (option.has("userType") && !option.get("userType").isNull()) {
                assertThat(option.get("userType").asText())
                        .as("Regular-user main menu must not contain admin-only options "
                                + "(CDEMO-MENU-OPT-USRTYPE='A' is reserved for the admin menu in COADM02Y.cpy)")
                        .isNotEqualToIgnoringCase("A");
            }
        }
    }

    // =========================================================================
    // Step 3 — List transactions (COTRN00C → GET /api/transactions?page=0)
    // =========================================================================

    /**
     * Verifies the transaction list: a {@code GET /api/transactions?page=0}
     * as an authenticated user returns HTTP 200 + a paged response. The
     * {@code content} array contains up to 10 rows per the COBOL
     * {@code WS-IDX > 10} loop bound in {@code COTRN00C.cbl} (and the
     * COTRN0AO BMS map {@code TRAN-REC OCCURS 10 TIMES} declaration). The
     * test captures the first row's 16-character {@code TRAN-ID} into
     * {@link #capturedTransactionId} for use by Step 4's detail-lookup.
     *
     * <p>PCI assertion (AAP §0.10.5): the response MUST NOT expose any
     * unmasked 16-digit Visa test PAN (the {@code 4111111111111101}-{@code 4111111111111150}
     * range used by the seed data). The seed fixtures carry full PANs in
     * the database, but the migrated list-row projection
     * ({@code TransactionController.TransactionSummary}) is required by
     * AAP §0.10.5 to mask all but the last 4 digits before serialising.
     */
    @Test
    @Order(3)
    @DisplayName("Step 3 — GET /api/transactions returns first page (10 records) with TRAN-ID parity (COTRN00C)")
    void step3_listTransactions_firstPage_returnsTenRecordsAndCapturesFirstId() {
        // Arrange
        HttpEntity<Void> request = new HttpEntity<>(sessionHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/transactions?page=0&size=" + PAGE_SIZE,
                HttpMethod.GET, request, JsonNode.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("Authenticated user on GET /api/transactions must receive HTTP 200")
                .isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        assertThat(body)
                .as("Transaction list response body must not be null on success")
                .isNotNull();

        // Spring Data Page response shape: { content: [...], totalElements,
        // totalPages, number, size, ... }. The migrated
        // TransactionListJsonResponse uses { success, content, pageNumber,
        // pageSize, hasNext, hasPrevious, totalElements, message }; either
        // shape exposes the `content` array at the top level, so the
        // assertion is robust against both designs.
        assertThat(body.has("content"))
                .as("Transaction list response must carry a 'content' array (paged result contract)")
                .isTrue();
        JsonNode content = body.get("content");
        assertThat(content.isArray())
                .as("'content' must be a JSON array")
                .isTrue();
        assertThat(content.size())
                .as("First page must contain at most " + PAGE_SIZE
                        + " transactions (page size = 10 per COTRN00C WS-IDX > 10 loop bound)")
                .isLessThanOrEqualTo(PAGE_SIZE);
        assertThat(content.size())
                .as("Seeded transactions table (V3__seed.sql, hydrated from app/data/ASCII/dailytran.txt) "
                        + "must contain at least one row so the journey can capture a TRAN-ID for "
                        + "the step-4 detail lookup")
                .isGreaterThan(0);

        // Capture the first row's TRAN-ID for use by Step 4. The production
        // TransactionSummary record exposes the field as `transactionId`;
        // the test also accepts a legacy `tranId` variant for forward
        // compatibility against schema renames.
        JsonNode firstTxn = content.get(0);
        assertThat(firstTxn.has("transactionId") || firstTxn.has("tranId"))
                .as("First transaction row must carry a TRAN-ID field (transactionId or tranId per CVTRA05Y.cpy)")
                .isTrue();
        capturedTransactionId = firstTxn.has("transactionId")
                ? firstTxn.get("transactionId").asText()
                : firstTxn.get("tranId").asText();
        assertThat(capturedTransactionId)
                .as("Captured TRAN-ID must not be null or empty")
                .isNotNull()
                .isNotEmpty();
        assertThat(capturedTransactionId.length())
                .as("Captured TRAN-ID must be exactly " + TRAN_ID_WIDTH
                        + " characters (CVTRA05Y PIC X(16))")
                .isEqualTo(TRAN_ID_WIDTH);

        // PCI (AAP §0.10.5) — the list response MUST NOT expose any unmasked
        // 16-digit Visa test PAN from the seed range
        // (4111111111111101-4111111111111150). Card numbers in list responses
        // must be masked (last-4-only or fully masked) before serialisation.
        // The regex matches any 16-digit PAN whose first 13 digits are
        // 4111111111111 — i.e., the entire seeded range.
        String contentString = content.toString();
        assertThat(contentString)
                .as("Transaction list MUST NOT expose any unmasked 16-digit Visa test PAN from the "
                        + "seeded range 4111111111111101-4111111111111150 (PCI / AAP §0.10.5)")
                .doesNotContainPattern("\\b4111111111111\\d{3}\\b");
    }

    // =========================================================================
    // Step 4 — View single transaction (COTRN01C → GET /api/transactions/{id})
    // =========================================================================

    /**
     * Verifies the transaction-detail endpoint: a
     * {@code GET /api/transactions/{capturedTransactionId}} returns HTTP 200
     * with the full transaction record. The assertion verifies:
     * <ul>
     *   <li>{@code TRAN-ID} is echoed verbatim (16-character primary key
     *       round-trip).</li>
     *   <li>The CVTRA05Y record fields are all present
     *       ({@code transactionId}, transaction-type code, transaction-category
     *       code, amount, description, merchant ID).</li>
     *   <li>{@code TRAN-AMT} is exposed as a numeric value with
     *       {@code .scale() == 2}, matching the COBOL
     *       {@code PIC S9(09)V99} field per AAP §0.10.3 (financial precision
     *       constraint).</li>
     *   <li>If a card number is exposed, it is masked (last-4-only or
     *       fully masked) per AAP §0.10.5.</li>
     * </ul>
     */
    @Test
    @Order(4)
    @DisplayName("Step 4 — GET /api/transactions/{id} returns full transaction detail (COTRN01C parity, BigDecimal scale 2)")
    void step4_viewTransaction_byCapturedId_returnsFullDetailWithBigDecimalScale2() {
        // Arrange
        assertThat(capturedTransactionId)
                .as("Step 3 must have captured a transaction ID — the journey is sequential")
                .isNotNull();
        HttpEntity<Void> request = new HttpEntity<>(sessionHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/transactions/" + capturedTransactionId,
                HttpMethod.GET, request, JsonNode.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("GET /api/transactions/{capturedId} must return HTTP 200 (COTRN01C happy-path "
                        + "parity)")
                .isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        assertThat(body)
                .as("Transaction detail response body must not be null on success")
                .isNotNull();

        // Assert all CVTRA05Y fields are surfaced. The production
        // TransactionDetailJsonResponse uses the field names
        // `transactionId`/`transactionType`/`transactionCategoryCode`/`amount`
        // /`description`/`merchantId`; the test also accepts the legacy
        // `tranId`/`tranTypeCd`/`tranCatCd`/`tranAmt`/`tranDesc`/`tranMerchantId`
        // variants for forward compatibility.
        assertThat(body.has("transactionId") || body.has("tranId"))
                .as("Detail response must carry the TRAN-ID field")
                .isTrue();
        assertThat(body.has("transactionType") || body.has("typeCode") || body.has("tranTypeCd"))
                .as("Detail response must carry TRAN-TYPE-CD")
                .isTrue();
        assertThat(body.has("transactionCategoryCode") || body.has("categoryCode") || body.has("tranCatCd"))
                .as("Detail response must carry TRAN-CAT-CD")
                .isTrue();
        assertThat(body.has("amount") || body.has("tranAmt"))
                .as("Detail response must carry TRAN-AMT")
                .isTrue();
        assertThat(body.has("description") || body.has("tranDesc"))
                .as("Detail response must carry TRAN-DESC")
                .isTrue();
        assertThat(body.has("merchantId") || body.has("tranMerchantId"))
                .as("Detail response must carry TRAN-MERCHANT-ID")
                .isTrue();

        // Assert TRAN-ID echoed verbatim from the path variable.
        String returnedId = body.has("transactionId")
                ? body.get("transactionId").asText()
                : body.get("tranId").asText();
        assertThat(returnedId)
                .as("Detail response TRAN-ID must echo the path-variable verbatim "
                        + "(16-character primary-key round-trip)")
                .isEqualTo(capturedTransactionId);
        assertThat(returnedId.length())
                .as("Echoed TRAN-ID must remain " + TRAN_ID_WIDTH + " characters (CVTRA05Y PIC X(16))")
                .isEqualTo(TRAN_ID_WIDTH);

        // Assert BigDecimal scale-2 on monetary amount (CVTRA05Y TRAN-AMT
        // PIC S9(09)V99 → BigDecimal scale 2 per AAP §0.10.3). The
        // production layer surfaces the amount as a JSON number; the test
        // parses it back into a BigDecimal via the asText() round-trip
        // (Jackson preserves trailing zeros when the source type is
        // BigDecimal, so "25.50" deserialises as scale=2). Parsing as
        // BigDecimal (NEVER as double) is itself a §0.10.3 enforcement —
        // no monetary value flows through floating-point at any point in
        // this test.
        JsonNode amount = body.has("amount") ? body.get("amount") : body.get("tranAmt");
        assertThat(amount.isNumber())
                .as("TRAN-AMT must be a JSON number (NOT a string — AAP §0.10.3)")
                .isTrue();
        BigDecimal amt = new BigDecimal(amount.asText());
        assertThat(amt.scale())
                .as("TRAN-AMT scale must be 2 (PIC S9(09)V99 / AAP §0.10.3 financial precision); "
                        + "Jackson preserves trailing zeros when the source field is BigDecimal — a "
                        + "scale != 2 here surfaces a float/double regression on the production "
                        + "side")
                .isEqualTo(2);

        // PCI (AAP §0.10.5) — if a card-number field is present, it must be
        // masked. The migrated TransactionDetailJsonResponse currently
        // exposes the full PAN on detail responses (the projection
        // preserves the COBOL byte-for-byte for §0.10.4 immutable-
        // boundary parity); however the AAP §0.10.5 directive forbids
        // unmasked PAN exposure, so a future controller refactor will
        // introduce a Jackson serializer. The assertion is conditional
        // — it fires only IF a cardNumber field is present, and only
        // when the value matches the canonical PAN format. The
        // satisfiesAnyOf assertion accepts either fully masked
        // (asterisks-only) OR last-4-unmasked form.
        if (body.has("cardNumber") && !body.get("cardNumber").isNull()) {
            String cardNum = body.get("cardNumber").asText();
            // Only enforce masking when the value LOOKS like a 16-digit
            // numeric PAN; values that are already masked
            // (e.g., "************1234") would otherwise short-circuit
            // through this guard.
            if (cardNum.length() == 16 && cardNum.chars().allMatch(Character::isDigit)) {
                assertThat(cardNum)
                        .as("Detail response card number must be masked or last-4-only "
                                + "(PCI / AAP §0.10.5)")
                        .satisfiesAnyOf(
                                c -> assertThat(c).matches(".*\\*{4,}\\d{4}$"),
                                c -> assertThat(c).matches("\\*{12,16}"));
            }
        }
    }

    // =========================================================================
    // Step 5 — Add new transaction (COTRN02C → POST /api/transactions)
    // =========================================================================

    /**
     * Verifies the transaction-add endpoint: a {@code POST /api/transactions}
     * with a fully-populated request body returns HTTP 201 Created (or 200 OK
     * — both are acceptable REST conventions per the controller comment) and
     * a success message embedding the auto-generated 16-character
     * {@code TRAN-ID}.
     *
     * <p>The new TRAN-ID is extracted from the success message rather than
     * from a dedicated {@code transactionId} JSON field because the
     * production {@code TransactionAddJsonResponse} (in
     * {@code src/main/java/com/aws/carddemo/controller/TransactionController.java})
     * carries only {@code { success, accountId, cardNumber, message }} —
     * the new ID is embedded in the message via
     * {@code TransactionAddService.MSG_TRANSACTION_ADD_SUCCESS_FORMAT =
     * "Transaction added successfully. Your Transaction ID is %s."}.
     * Step 5 parses the 16-character TRAN-ID out of that message via
     * {@link #extractTransactionIdFromMessage(String)} — purely string
     * parsing of the production HTTP surface, never re-derivation of the
     * COBOL {@code STARTBR-to-end + 1} algorithm that produced the value
     * (AAP §0.10.1 Require Test Coverage rule).
     *
     * <p>Request body field names match the production
     * {@code TransactionAddRequest}:
     * {@code transactionTypeCode}/{@code transactionCategoryCode}/
     * {@code originDate}/{@code processDate} (NOT
     * {@code typeCode}/{@code categoryCode}/{@code originalDate}/
     * {@code processedDate}). Verified by inspecting
     * {@code src/main/java/com/aws/carddemo/service/TransactionAddRequest.java}
     * setters: {@code setTransactionTypeCode},
     * {@code setTransactionCategoryCode}, {@code setOriginDate},
     * {@code setProcessDate}. The body uses {@link LinkedHashMap} to
     * preserve insertion order so the on-the-wire JSON shape mirrors the
     * COBOL field declaration order in {@code CVTRA05Y.cpy}.
     */
    @Test
    @Order(5)
    @DisplayName("Step 5 — POST /api/transactions creates a new transaction; assigns a 16-char TRAN-ID (COTRN02C parity)")
    void step5_addTransaction_validRequest_returns201AndAssignsTransactionId() {
        // Arrange — build a TransactionAddRequest using fixture constants
        // and the test-scoped NEW_TXN_* literals. The field-name set
        // matches the production TransactionAddRequest setters (verified
        // by code inspection); see Javadoc above for the rationale.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        body.put("cardNumber", TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        body.put("transactionTypeCode", NEW_TXN_TYPE_CODE);
        body.put("transactionCategoryCode", NEW_TXN_CATEGORY_CODE);
        body.put("source", NEW_TXN_SOURCE);
        body.put("description", NEW_TXN_DESCRIPTION);
        body.put("amount", NEW_TXN_AMOUNT);
        body.put("originDate", NEW_TXN_ORIGIN_DATE);
        body.put("processDate", NEW_TXN_PROCESS_DATE);
        body.put("merchantId", NEW_TXN_MERCHANT_ID);
        body.put("merchantName", NEW_TXN_MERCHANT_NAME);
        body.put("merchantCity", NEW_TXN_MERCHANT_CITY);
        body.put("merchantZip", NEW_TXN_MERCHANT_ZIP);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(sessionHeaders);
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, requestHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/transactions", HttpMethod.POST, request, JsonNode.class);

        // Assert — 201 Created (REST convention) or 200 OK (some controller
        // designs return 200 on POST; the production TransactionController
        // returns 201 per its current implementation but the test tolerates
        // either to remain robust against future status-code refactors).
        assertThat(response.getStatusCode().value())
                .as("Valid POST /api/transactions must succeed: 201 Created or 200 OK; "
                        + "NEVER 4xx for a fully-populated valid input")
                .isIn(200, 201);

        JsonNode responseBody = response.getBody();
        assertThat(responseBody)
                .as("Add-transaction response body must not be null on success")
                .isNotNull();

        // Extract the new 16-character TRAN-ID. The production response
        // shape is { success, accountId, cardNumber, message } where the
        // message embeds the new ID via the
        // MSG_TRANSACTION_ADD_SUCCESS_FORMAT pattern. The test accepts a
        // forward-compatible alternative shape where transactionId/tranId
        // is a top-level field.
        if (responseBody.has("transactionId") && !responseBody.get("transactionId").isNull()) {
            addedTransactionId = responseBody.get("transactionId").asText();
        } else if (responseBody.has("tranId") && !responseBody.get("tranId").isNull()) {
            addedTransactionId = responseBody.get("tranId").asText();
        } else {
            // Canonical path — the new TRAN-ID is embedded in the message.
            assertThat(responseBody.has("message"))
                    .as("Add response must carry either a transactionId/tranId field or a "
                            + "message that embeds the new ID (per "
                            + "TransactionAddService.MSG_TRANSACTION_ADD_SUCCESS_FORMAT)")
                    .isTrue();
            String message = responseBody.get("message").asText();
            addedTransactionId = extractTransactionIdFromMessage(message);
        }

        assertThat(addedTransactionId)
                .as("New TRAN-ID must have been captured (either via top-level field or via "
                        + "message extraction)")
                .isNotNull()
                .isNotEmpty();
        assertThat(addedTransactionId.length())
                .as("Assigned TRAN-ID must be exactly " + TRAN_ID_WIDTH + " characters "
                        + "(auto-generated per COTRN02C STARTBR-to-end + 1 algorithm; "
                        + "asserted by length only — never re-derived in the test body per "
                        + "AAP §0.10.1 Require Test Coverage rule)")
                .isEqualTo(TRAN_ID_WIDTH);

        // PCI (AAP §0.10.5) — the add response must not echo the plaintext
        // card number when the request submitted one. The production layer
        // is required to mask PANs at the wire boundary; the assertion
        // catches a regression where the response echoes the input card
        // number verbatim. The pattern matches any 16-digit PAN starting
        // with 4111111111111 (the seeded Visa test PAN prefix).
        String responseBodyStr = responseBody.toString();
        assertThat(responseBodyStr)
                .as("Add-transaction response MUST NOT expose any unmasked 16-digit Visa test PAN "
                        + "from the seeded range 4111111111111101-4111111111111150 (PCI / AAP §0.10.5)")
                .doesNotContainPattern("\\b4111111111111\\d{3}\\b");
    }

    // =========================================================================
    // Step 6 — Verify added transaction (round-trip persistence verification)
    // =========================================================================

    /**
     * Verifies that the transaction added in Step 5 is persisted and
     * readable: a {@code GET /api/transactions/{addedTransactionId}}
     * returns HTTP 200 with all submitted fields round-trip correctly. The
     * assertions verify:
     * <ul>
     *   <li>{@code TRAN-ID} is echoed verbatim (the auto-generated 16-character
     *       primary key).</li>
     *   <li>{@code TRAN-TYPE-CD} matches the submitted {@link #NEW_TXN_TYPE_CODE}
     *       ({@code "01"} Purchase).</li>
     *   <li>{@code TRAN-AMT} value equals {@link #NEW_TXN_AMOUNT_BD}
     *       ({@code 25.50}) <strong>and</strong> scale equals 2 — both
     *       checks are required by AAP §0.10.3 because
     *       {@code isEqualByComparingTo} ignores scale.</li>
     *   <li>{@code TRAN-DESC} contains the submitted description literal
     *       (substring match because the COBOL field is space-padded to
     *       100 characters; the migration may or may not trim trailing
     *       whitespace on serialisation).</li>
     * </ul>
     */
    @Test
    @Order(6)
    @DisplayName("Step 6 — GET /api/transactions/{newId} returns the just-added transaction with all submitted fields preserved")
    void step6_verifyAddedTransaction_byNewId_returnsPersistedFields() {
        // Arrange
        assertThat(addedTransactionId)
                .as("Step 5 must have produced a new TRAN-ID — the journey is sequential")
                .isNotNull();
        HttpEntity<Void> request = new HttpEntity<>(sessionHeaders);

        // Act
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/transactions/" + addedTransactionId,
                HttpMethod.GET, request, JsonNode.class);

        // Assert
        assertThat(response.getStatusCode())
                .as("Newly-added transaction must be readable via "
                        + "GET /api/transactions/{newId} (post-insert persistence)")
                .isEqualTo(HttpStatus.OK);

        JsonNode body = response.getBody();
        assertThat(body)
                .as("Detail response for the just-added transaction must not be null")
                .isNotNull();

        // TRAN-ID echo — the auto-generated 16-character ID must be
        // returned verbatim from the path variable.
        String returnedId = body.has("transactionId")
                ? body.get("transactionId").asText()
                : body.get("tranId").asText();
        assertThat(returnedId)
                .as("Detail response TRAN-ID must echo the path-variable verbatim "
                        + "(round-trip parity with the auto-generated ID)")
                .isEqualTo(addedTransactionId);

        // TRAN-TYPE-CD round-trip — the submitted "01" (Purchase) must be
        // preserved verbatim. Accept the canonical production field name
        // `transactionType` and the legacy alternatives for forward
        // compatibility.
        String typeCode = null;
        if (body.has("transactionType") && !body.get("transactionType").isNull()) {
            typeCode = body.get("transactionType").asText();
        } else if (body.has("typeCode") && !body.get("typeCode").isNull()) {
            typeCode = body.get("typeCode").asText();
        } else if (body.has("tranTypeCd") && !body.get("tranTypeCd").isNull()) {
            typeCode = body.get("tranTypeCd").asText();
        }
        assertThat(typeCode)
                .as("TRAN-TYPE-CD must be present in the detail response")
                .isNotNull();
        assertThat(typeCode)
                .as("TRAN-TYPE-CD must round-trip the submitted value " + NEW_TXN_TYPE_CODE
                        + " (Purchase per trantype.txt)")
                .isEqualTo(NEW_TXN_TYPE_CODE);

        // TRAN-AMT round-trip — value equality (ignoring trailing-zero
        // semantics via isEqualByComparingTo) AND scale-2 enforcement
        // (since isEqualByComparingTo intentionally ignores scale per
        // BigDecimal semantics). BOTH assertions are required by AAP
        // §0.10.3: value parity proves the persistence round-trip is
        // correct; scale parity proves the production layer preserves
        // COBOL PIC S9(09)V99 precision and has not regressed to
        // float/double anywhere in the path.
        JsonNode amount = body.has("amount") ? body.get("amount") : body.get("tranAmt");
        assertThat(amount.isNumber())
                .as("TRAN-AMT must remain a JSON number after the round-trip (AAP §0.10.3)")
                .isTrue();
        BigDecimal amt = new BigDecimal(amount.asText());
        assertThat(amt)
                .as("TRAN-AMT value must round-trip the submitted " + NEW_TXN_AMOUNT
                        + " (post-persist read-back equality)")
                .isEqualByComparingTo(NEW_TXN_AMOUNT_BD);
        assertThat(amt.scale())
                .as("TRAN-AMT scale must be 2 (PIC S9(09)V99 / AAP §0.10.3 financial precision)")
                .isEqualTo(2);

        // TRAN-DESC round-trip — substring match because the COBOL field
        // is PIC X(100) space-padded; the migration may or may not strip
        // trailing whitespace on serialisation. The submitted literal
        // "E2E TEST PURCHASE" must appear as a contiguous substring of
        // the returned value (which may be either trimmed or
        // space-padded to the full 100 characters).
        String desc = null;
        if (body.has("description") && !body.get("description").isNull()) {
            desc = body.get("description").asText();
        } else if (body.has("tranDesc") && !body.get("tranDesc").isNull()) {
            desc = body.get("tranDesc").asText();
        }
        assertThat(desc)
                .as("TRAN-DESC must be present in the detail response")
                .isNotNull();
        assertThat(desc)
                .as("TRAN-DESC must contain the submitted description literal "
                        + "(round-trip; trailing whitespace tolerated per COBOL PIC X(100) padding)")
                .contains(NEW_TXN_DESCRIPTION);
    }

    // =========================================================================
    // Private test helpers
    // =========================================================================

    /**
     * Extracts the 16-character transaction identifier embedded in the
     * production add-success message
     * {@code "Transaction added successfully. Your Transaction ID is XXX."}
     * (the format string is
     * {@code TransactionAddService.MSG_TRANSACTION_ADD_SUCCESS_FORMAT}).
     *
     * <p>The helper performs pure string parsing — no business logic, no
     * derivation, no re-implementation of the COBOL
     * {@code STARTBR-to-end + 1} TRAN-ID-generation algorithm (AAP §0.10.1
     * Require Test Coverage rule). It scans the message for any run of
     * exactly {@value #TRAN_ID_WIDTH} digits and returns the first such run;
     * if no 16-digit run is present, returns {@code null} and the caller
     * fails the test via the assertion in step 5.
     *
     * <p>Why a digit-run scan and not a regex against the literal format
     * string: the production layer may format the message slightly
     * differently (trailing whitespace, capitalisation, optional period
     * placement) without violating the immutable-boundary contract (the
     * TRAN-ID itself must remain 16-character numeric per CVTRA05Y).
     * Scanning for the 16-digit run is robust to those variations.
     *
     * @param message the success message (may be {@code null} on a malformed
     *                response)
     * @return the 16-character TRAN-ID extracted from the message, or
     *         {@code null} if no 16-digit run is found
     */
    private String extractTransactionIdFromMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        // Scan for the first 16-digit run. The CVTRA05Y TRAN-ID is exactly
        // 16 digits (numeric); the production format string positions the
        // ID after the literal "Your Transaction ID is " but the scan does
        // not depend on that prefix — it depends only on the format width
        // of the TRAN-ID itself, which is the immutable boundary.
        int len = message.length();
        int run = 0;
        int runStart = -1;
        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);
            if (c >= '0' && c <= '9') {
                if (run == 0) {
                    runStart = i;
                }
                run++;
                if (run == TRAN_ID_WIDTH) {
                    // Check the next character is not also a digit — only
                    // accept runs of EXACTLY 16 digits (a 17-digit run
                    // would be a malformed ID).
                    if (i + 1 == len || !Character.isDigit(message.charAt(i + 1))) {
                        return message.substring(runStart, runStart + TRAN_ID_WIDTH);
                    }
                    // 17+ digit run — reset and continue scanning.
                    run = 0;
                    runStart = -1;
                }
            } else {
                run = 0;
                runStart = -1;
            }
        }
        return null;
    }
}
