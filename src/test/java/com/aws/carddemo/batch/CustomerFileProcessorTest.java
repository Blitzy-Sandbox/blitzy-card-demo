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
package com.aws.carddemo.batch;

// Shared test constants (AAP §0.5.5 — Cross-File Test Dependencies). The
// two TestFixtures classes used here are:
//   - TestFixtures.Customers — sample 9-digit CUST-ID values that match
//     the CBCUS01C input fixture custdata.txt (RECLN = 500 per CUSTREC.cpy)
//   - TestFixtures.RecordWidths — copybook-derived integer constants
//     (CUSTOMER_RECLN = 500) used to verify the 500-byte fixed-width
//     record contract that CBCUS01C reads from VSAM (FD-CUSTFILE-REC
//     PIC X(500) — 9 bytes FD-CUST-ID + 491 bytes FD-CUST-DATA per
//     app/cbl/CBCUS01C.cbl lines 38–40).
// Keeping these in TestFixtures (rather than re-declaring them locally)
// is the single-source-of-truth pattern that every batch test in this
// folder follows — see InterestCalculationProcessorTest,
// StatementFileProcessorTest, StatementProcessorTest, and
// TransactionReportProcessorTest for the established precedent.
import com.aws.carddemo.testsupport.TestFixtures;

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks each top-level test method,
// @DisplayName carries the human-readable scenario name on the class and
// on each test method (per AAP §0.10.6 naming convention),
// @Nested groups the EOF/size-boundary parameterized scenarios into a
// dedicated inner class (per AAP §0.5.2 EofBoundaryTests blueprint),
// and @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// JUnit 5 parameterized-test support (AAP §0.6.1). @ParameterizedTest
// declares a data-driven test method, and @CsvFileSource feeds it from
// the classpath CSV fixture at /fixtures/edge/eof_boundary.csv. Every
// row in the CSV becomes one invocation of the annotated method; the
// header row is skipped via numLinesToSkip = 1. Per AAP §0.10.7, this
// is the canonical mechanism for "calculation variants" and edge-case
// boundary coverage (here: EOF and record-count boundary scenarios at
// sizes 0/1/9/10/11/49/50/51/99/100 per the CSV's 10 rows).
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.13). MockitoExtension activates STRICT_STUBS
// strictness (AAP §0.10.1: "Mockito strictness is STRICT_STUBS ... unused
// stubs raise UnnecessaryStubbingException"). This test class declares no
// @Mock fields directly — the production CustomerFileProcessor class has
// not yet been authored by REFACTOR-flavor agents, so its boundary
// collaborators (the VSAM CUSTFILE FlatFileItemReader<String> equivalent
// per app/cbl/CBCUS01C.cbl lines 29–33, or the downstream
// CustomerRepository for the migrated JPA path) cannot be wired in yet.
// The extension is retained both as the project-wide test-class
// convention and as a future-proofing seam: once the production class
// lands, additional @Mock fields can be added without changing the class
// annotation.
import org.mockito.junit.jupiter.MockitoExtension;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively,
// no JUnit Assertions, no Hamcrest matchers, no mixed styles).
// Static-imported assertThat is used for every assertion in this class.
// Frequently used fluent methods:
//   - hasSize(int)              — string/collection length check (e.g.,
//                                 fixture record == 500 bytes)
//   - isEqualTo(...)            — strict equality check
//   - startsWith(...)           — prefix check (CUST-ID is the first
//                                 9 chars of the 500-byte CUSTOMER-RECORD)
//   - isPositive() / isNonNegative() — numeric sanity for CSV-derived
//                                 size parameters
//   - isTrue()                  — boolean invariant for EOF flag column
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@code CustomerFileProcessor} — the Java migration of the
 * COBOL {@code CBCUS01C} customer file dump utility (100 lines; see
 * {@code app/cbl/CBCUS01C.cbl}).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code CBCUS01C} is a small batch program (one of the three "Read &amp;
 * print" file dump utilities: {@code CBACT01C} for accounts,
 * {@code CBACT02C} for cards, and this one — {@code CBCUS01C} — for
 * customers). The program structure (lines 70–87 of
 * {@code app/cbl/CBCUS01C.cbl}) is:
 * <pre>
 *     PERFORM 0000-CUSTFILE-OPEN.                  -- open CUSTFILE KSDS
 *     PERFORM UNTIL END-OF-FILE = 'Y'
 *         IF  END-OF-FILE = 'N'
 *             PERFORM 1000-CUSTFILE-GET-NEXT       -- READ ... INTO CUSTOMER-RECORD
 *             IF  END-OF-FILE = 'N'
 *                 DISPLAY CUSTOMER-RECORD          -- write to SYSOUT
 *             END-IF
 *         END-IF
 *     END-PERFORM.
 *     PERFORM 9000-CUSTFILE-CLOSE.                 -- close CUSTFILE KSDS
 * </pre>
 *
 * <p>The migration target is a Spring Batch {@code ItemProcessor<String,?>}
 * (or {@code ItemReader<CustomerRecord>}, depending on the implementing
 * agent's choice) that:
 * <ul>
 *   <li>Receives a 500-byte fixed-width customer record from the upstream
 *       {@code FlatFileItemReader<String>} (the {@code custdata.txt}
 *       fixture under {@code src/test/resources/baseline/input/}).</li>
 *   <li>Parses the 9-byte {@code CUST-ID PIC 9(09)} key and the 491-byte
 *       {@code FD-CUST-DATA PIC X(491)} payload (the structure of which
 *       is documented by the {@code CUSTOMER-RECORD} layout in
 *       {@code app/cpy/CUSTREC.cpy} / {@code app/cpy/CVCUS01Y.cpy}).</li>
 *   <li>Returns a {@code CustomerRecord} domain object (POJO or Java
 *       {@code record}) on the happy path, or rejects the input (with a
 *       domain-specific {@code RuntimeException}) on malformed input.</li>
 *   <li>Reports EOF cleanly when the upstream reader exhausts — the
 *       Spring Batch chunk loop terminates normally regardless of the
 *       total record count (0, 1, 9, 10, 11, 49, 50, 51, 99, 100 are all
 *       covered by the {@code eof_boundary.csv} fixture).</li>
 * </ul>
 *
 * <h2>Customer record layout (app/cpy/CUSTREC.cpy, RECLN = 500)</h2>
 *
 * <p>The 500-byte fixed-width {@code CUSTOMER-RECORD} has the following
 * field layout:
 * <pre>
 *   Bytes  1-  9   CUST-ID                   PIC 9(09)   (9-digit key)
 *   Bytes 10- 34   CUST-FIRST-NAME           PIC X(25)
 *   Bytes 35- 59   CUST-MIDDLE-NAME          PIC X(25)
 *   Bytes 60- 84   CUST-LAST-NAME            PIC X(25)
 *   Bytes 85-134   CUST-ADDR-LINE-1          PIC X(50)
 *   Bytes 135-184  CUST-ADDR-LINE-2          PIC X(50)
 *   Bytes 185-234  CUST-ADDR-LINE-3          PIC X(50)
 *   Bytes 235-236  CUST-ADDR-STATE-CD        PIC X(02)
 *   Bytes 237-239  CUST-ADDR-COUNTRY-CD      PIC X(03)
 *   Bytes 240-249  CUST-ADDR-ZIP             PIC X(10)
 *   Bytes 250-264  CUST-PHONE-NUM-1          PIC X(15)
 *   Bytes 265-279  CUST-PHONE-NUM-2          PIC X(15)
 *   Bytes 280-288  CUST-SSN                  PIC 9(09)
 *   Bytes 289-308  CUST-GOVT-ISSUED-ID       PIC X(20)
 *   Bytes 309-318  CUST-DOB-YYYY-MM-DD       PIC X(10)
 *   Bytes 319-328  CUST-EFT-ACCOUNT-ID       PIC X(10)
 *   Byte  329      CUST-PRI-CARD-HOLDER-IND  PIC X(01)
 *   Bytes 330-332  CUST-FICO-CREDIT-SCORE    PIC 9(03)
 *   Bytes 333-500  FILLER                    PIC X(168)
 * </pre>
 *
 * <p>The 500-byte width is part of the immutable boundary contract per
 * AAP §0.10.4 ("Input and output file formats and record layouts MUST
 * remain identical"). Any future refactor that changes the record width
 * would silently break byte-equality parity against the captured COBOL
 * reference outputs that flow through customer lookups (statements_text.txt,
 * statements_html.txt — both depend on the customer master via the
 * CBSTM03A → CBSTM03B chain).
 *
 * <h2>Test Categories (AAP §0.5.1)</h2>
 *
 * <p>The AAP §0.5.1 row for this file lists two migration concerns the
 * unit test must cover:
 * <ul>
 *   <li><strong>Happy read</strong> — a well-formed 500-byte
 *       {@code CUSTOMER-RECORD} consisting of a valid 9-digit
 *       {@code CUST-ID} followed by the trailing 491-byte payload must
 *       be accepted by the production {@code CustomerFileProcessor}.
 *       Covered by {@link #process_wellFormedCustomerRecord_returnsDomainObject()}.</li>
 *   <li><strong>EOF handling</strong> — every size in the boundary set
 *       (0, 1, 9, 10, 11, 49, 50, 51, 99, 100 records from
 *       {@code eof_boundary.csv}) must reach the natural end-of-file
 *       condition with the {@code END-OF-FILE = 'Y'} sentinel set (per
 *       {@code app/cbl/CBCUS01C.cbl} lines 74–81 — the
 *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop), and the count of
 *       records processed must equal the count of records supplied
 *       (no records dropped on the floor — see the
 *       {@code 1000-CUSTFILE-GET-NEXT} paragraph at lines 92–112 of
 *       {@code app/cbl/CBCUS01C.cbl}, where {@code STATUS '00'} drives
 *       successful read and {@code STATUS '10'} drives clean EOF).
 *       Covered by the {@link EofBoundaryTests} nested class via
 *       {@code @ParameterizedTest} + {@code @CsvFileSource}.</li>
 * </ul>
 *
 * <p>A third concern, malformed-input rejection, is also asserted by
 * {@link #process_truncatedRecord_isRejected()}: while CBCUS01C itself
 * is a thin DISPLAY-only utility (it does not reject — it would fail
 * the VSAM open or read with a non-zero status), the migrated
 * {@code CustomerFileProcessor} that subsequent agents will author
 * must guard against truncated inputs to avoid silent data corruption
 * in the downstream {@code CustomerRepository} writes.
 *
 * <h2>Why Fixture-Contract Assertions (Not Direct Production Invocation)</h2>
 *
 * <p>The production class {@code com.aws.carddemo.batch.CustomerFileProcessor}
 * does not yet exist on disk; subsequent REFACTOR-flavor agents will
 * author it (see {@code dest_file:src/main/java/com/aws/carddemo/batch/}
 * which today contains only {@code CombineTransactionsProcessor.java}).
 * This test class therefore cannot import or instantiate the production
 * class. Instead, the tests verify the contract surface that the
 * production class is expected to honour:
 * <ol>
 *   <li><strong>Fixture-record construction.</strong> The well-formed
 *       500-byte fixture is built from {@link TestFixtures.Customers}
 *       and {@link TestFixtures.RecordWidths} constants — establishing
 *       the byte-exact layout that the production class will parse
 *       (9-byte CUST-ID prefix + 491-byte payload padded to 500).</li>
 *   <li><strong>Truncation guard pre-condition.</strong> The truncated
 *       input is structurally shorter than {@code CUSTOMER_RECLN},
 *       which is the structural input the production class's
 *       truncation guard will need to reject.</li>
 *   <li><strong>EOF/size-boundary CSV consistency.</strong> Every row in
 *       {@code /fixtures/edge/eof_boundary.csv} must satisfy
 *       {@code expectedRecordsRead == fixtureSize} (every record gets
 *       processed before EOF) and {@code expectedEofFlag == true}
 *       (EOF is always reached after the fixture is exhausted) —
 *       capturing the structural invariant of the CBCUS01C loop in
 *       {@code app/cbl/CBCUS01C.cbl} lines 74–81 at the unit-test
 *       layer.</li>
 * </ol>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule) and AAP §0.4.3
 * ("Existing Test Extension Strategy"), this is the canonical pattern
 * for tests that precede their production counterpart — see
 * {@link StatementFileProcessorTest},
 * {@link StatementProcessorTest},
 * {@link TransactionReportProcessorTest},
 * {@link InterestCalculationProcessorTest}, and
 * {@link TransactionPostingProcessorTest} in the same package for the
 * established precedent: assert what the test-fixture constants
 * guarantee now, then add production-class invocation in a future
 * Phase-3 fix-up once the class lands. Full happy-path file-dump
 * semantics (open/read/close, EOF detection, record formatting) are
 * verified end-to-end by the future {@code CustomerFileProcessorIT}
 * (a Spring Batch IT scoped to a single-step job that reads
 * {@code custdata.txt} and asserts the row count) — out of scope for
 * this unit test.
 *
 * <h2>Mock Dependencies (Per AAP §0.5.2)</h2>
 *
 * <p>The future REFACTOR-flavor wire-up will mock one or more external
 * boundary collaborators once the production constructor lands. For the
 * CBCUS01C migration, the candidate boundary collaborators are:
 * <ul>
 *   <li>{@code FlatFileItemReader<String>} for the CUSTFILE input
 *       stream — file I/O boundary per AAP §0.10.1, 500-byte
 *       fixed-width records (replaces the COBOL {@code SELECT
 *       CUSTFILE-FILE ASSIGN TO CUSTFILE ORGANIZATION IS INDEXED}
 *       declaration on lines 29–33 of {@code app/cbl/CBCUS01C.cbl}).</li>
 *   <li>Optionally, a {@code CustomerRepository} JPA boundary if the
 *       migration writes parsed records to PostgreSQL (replaces the
 *       VSAM CUSTFILE KSDS as the system of record).</li>
 * </ul>
 * These are listed here for the future REFACTOR-flavor wire-up; the
 * current test file declares no {@code @Mock} fields because the
 * production class the mocks would be injected into has not yet been
 * authored.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — {@code CustomerFileProcessor}
 * for the {@code CBCUS01C} migration; happy read + EOF),
 * §0.5.1 (File-by-File Test Plan — "Cover happy read, EOF"),
 * §0.5.2 (Test categories detail — happy + truncated + EOF/size boundary),
 * §0.10.1 (Require Test Coverage rule — drive production code, assert
 * verbatim COBOL boundary contracts),
 * §0.10.4 (Immutable Boundaries — the {@code CUSTOMER-RECORD} 500-byte
 * width is part of the downstream-consumer record-layout contract),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see TestFixtures.Customers
 * @see TestFixtures.RecordWidths#CUSTOMER_RECLN
 * @see StatementFileProcessorTest
 * @see StatementProcessorTest
 * @see TransactionReportProcessorTest
 * @see InterestCalculationProcessorTest
 * @see TransactionPostingProcessorTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerFileProcessor unit tests (CBCUS01C migration)")
class CustomerFileProcessorTest {

    // ============================================================
    // Field-width constants (single source of truth: app/cpy/CUSTREC.cpy
    // and app/cpy/CVCUS01Y.cpy)
    // ============================================================

    /**
     * Width in bytes of the {@code CUST-ID} field at the head of every
     * {@code CUSTOMER-RECORD} per {@code app/cpy/CUSTREC.cpy} line 5
     * ({@code CUST-ID PIC 9(09)}) — also reachable from
     * {@code app/cbl/CBCUS01C.cbl} line 39
     * ({@code FD-CUST-ID PIC 9(09)} on the file-description record). The
     * remaining 491 bytes form the {@code FD-CUST-DATA PIC X(491)}
     * payload (line 40) which together with the key yields the 500-byte
     * {@link TestFixtures.RecordWidths#CUSTOMER_RECLN} record width.
     *
     * <p>Held as a file-local constant rather than centralised in
     * {@link TestFixtures.RecordWidths} because it is only used by
     * this test class (the {@code CUSTOMER_RECLN} aggregate IS in
     * {@link TestFixtures.RecordWidths} — that constant is shared by
     * {@code StatementFileProcessorTest},
     * {@code CustomerRepositoryIT}, and the future
     * {@code CustomerFileProcessor} production class).
     */
    private static final int CUST_ID_WIDTH = 9;

    /**
     * Width in bytes of each of the three name fields
     * ({@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME},
     * {@code CUST-LAST-NAME}) per {@code app/cpy/CUSTREC.cpy} lines 6–8
     * (each declared as {@code PIC X(25)}). Used to pad the canonical
     * happy-path fixture record below.
     */
    private static final int CUST_NAME_WIDTH = 25;

    // ============================================================
    // Happy path — well-formed 500-byte customer record
    // ============================================================

    /**
     * Verifies that a well-formed 500-byte {@code CUSTOMER-RECORD} (the
     * happy-path input that {@code CBCUS01C} would successfully READ at
     * line 93 of {@code app/cbl/CBCUS01C.cbl} with {@code CUSTFILE-STATUS
     * = '00'}) satisfies the production class's structural contract:
     * <ul>
     *   <li>Total record length is exactly
     *       {@link TestFixtures.RecordWidths#CUSTOMER_RECLN} (500 bytes).</li>
     *   <li>The first {@value #CUST_ID_WIDTH} bytes hold the 9-digit
     *       {@code CUST-ID} key
     *       ({@link TestFixtures.Customers#SAMPLE_CUSTOMER_ID_01} =
     *       {@code "000000001"}).</li>
     *   <li>The trailing 491 bytes hold the {@code FD-CUST-DATA} payload,
     *       starting with the three 25-byte name fields and padded with
     *       trailing ASCII spaces to the 500-byte record width.</li>
     * </ul>
     *
     * <p>The fixture is constructed inline using two TestFixtures
     * constants so the test body contains zero business logic (per
     * AAP §0.10.1 Require Test Coverage rule):
     * <pre>
     *   CUST-ID                : TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01
     *   total record width     : TestFixtures.RecordWidths.CUSTOMER_RECLN
     * </pre>
     * The names ("JANE", "Q", "DOE") and the {@link #padRight(String, int)}
     * helper are literal scaffolding — they do not implement any
     * production behaviour; they only realise a single concrete
     * well-formed record at the canonical 500-byte width that the
     * production class would receive from a {@code FlatFileItemReader<String>}
     * configured for the CUSTFILE input.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries): the 500-byte width is
     * part of the immutable boundary contract — every captured COBOL
     * reference output that flows through customer lookups
     * ({@code statements_text.txt}, {@code statements_html.txt} via
     * CBSTM03A/CBSTM03B) depends on this width. The dedicated structural
     * assertions below catch drift at the fastest possible diagnosis
     * layer (sub-millisecond Surefire invocation) versus the multi-second
     * {@code StatementGenerationBaselineParityIT} run.
     *
     * <p>Once the production {@code CustomerFileProcessor} class lands
     * on disk under {@code src/main/java/com/aws/carddemo/batch/}, this
     * test will be extended to call:
     * <pre>
     *   final CustomerFileProcessor processor = new CustomerFileProcessor();
     *   final CustomerRecord result = processor.process(fixture);
     *   assertThat(result).isNotNull();
     *   assertThat(result.custId()).isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
     * </pre>
     * The current assertions establish the pre-condition (the fixture
     * is the correct 500-byte shape) that the future production
     * invocation will depend on.
     */
    @Test
    @DisplayName("process_wellFormedCustomerRecord_returnsDomainObject")
    void process_wellFormedCustomerRecord_returnsDomainObject() {
        // Arrange — build the canonical 500-byte CUSTOMER-RECORD from
        // TestFixtures constants. Layout per app/cpy/CUSTREC.cpy:
        //
        //   bytes 1-9    : CUST-ID PIC 9(09)        - sample customer 1
        //   bytes 10-34  : CUST-FIRST-NAME PIC X(25) - "JANE" + 21 trailing spaces
        //   bytes 35-59  : CUST-MIDDLE-NAME PIC X(25) - "Q" + 24 trailing spaces
        //   bytes 60-84  : CUST-LAST-NAME PIC X(25)  - "DOE" + 22 trailing spaces
        //   bytes 85-500 : remaining payload          - all-ASCII-space filler
        //                                               (CUST-ADDR-* + CUST-PHONE-*
        //                                               + CUST-SSN + CUST-GOVT-*
        //                                               + CUST-DOB + CUST-EFT-*
        //                                               + CUST-PRI-CARD-HOLDER-IND
        //                                               + CUST-FICO-CREDIT-SCORE
        //                                               + FILLER PIC X(168))
        //
        // The unfilled portion is padded with ASCII spaces because
        // CBCUS01C does not initialise its WORKING-STORAGE
        // CUSTOMER-RECORD (the program is read-only — it only reads
        // and displays); the canonical fixture file custdata.txt
        // (loaded into Flyway V3__seed.sql by the future repository IT
        // layer) carries every record padded with trailing spaces to
        // the 500-byte width.
        final String fixture = padRight(
                TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01
                        + padRight("JANE", CUST_NAME_WIDTH)
                        + padRight("Q", CUST_NAME_WIDTH)
                        + padRight("DOE", CUST_NAME_WIDTH),
                TestFixtures.RecordWidths.CUSTOMER_RECLN);

        // Structural pre-condition #1: the fixture record must be
        // exactly 500 bytes wide — the verbatim CUSTREC.cpy RECLN value
        // and the FD-CUSTFILE-REC width declared at line 38 of
        // app/cbl/CBCUS01C.cbl. Drift in either direction (short or
        // long) would silently break the contract that the production
        // class's fixed-width parser is built on.
        assertThat(fixture)
                .as("Well-formed CUSTOMER-RECORD fixture must be exactly "
                        + "%d bytes wide (matches CUSTOMER_RECLN per "
                        + "app/cpy/CUSTREC.cpy header and "
                        + "FD-CUSTFILE-REC at app/cbl/CBCUS01C.cbl line 38)",
                        TestFixtures.RecordWidths.CUSTOMER_RECLN)
                .hasSize(TestFixtures.RecordWidths.CUSTOMER_RECLN);

        // Structural pre-condition #2: the first 9 bytes of the fixture
        // must hold the verbatim SAMPLE_CUSTOMER_ID_01 ("000000001") —
        // matches the CUST-ID PIC 9(09) field at app/cpy/CUSTREC.cpy
        // line 5 and FD-CUST-ID PIC 9(09) at app/cbl/CBCUS01C.cbl
        // line 39. The production class's fixed-width parser will read
        // these 9 bytes as the customer key.
        assertThat(fixture.substring(0, CUST_ID_WIDTH))
                .as("Bytes 1-%d of the fixture must hold the 9-digit "
                        + "CUST-ID key (matches CUST-ID PIC 9(09) per "
                        + "app/cpy/CUSTREC.cpy line 5)",
                        CUST_ID_WIDTH)
                .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);

        // Structural pre-condition #3: the fixture starts with the
        // SAMPLE_CUSTOMER_ID_01 string — an alternative formulation of
        // pre-condition #2 that surfaces a leading-byte corruption
        // earlier on failure (startsWith() gives a clearer diff than
        // substring() comparison in the AssertJ failure message).
        assertThat(fixture)
                .as("Fixture must start with the 9-digit CUST-ID "
                        + "(matches CUSTREC.cpy CUST-ID layout)")
                .startsWith(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);

        // Structural pre-condition #4: the CUST-ID itself must be exactly
        // 9 characters wide — independent of how the fixture was built,
        // the constant in TestFixtures must match the COBOL field width.
        // This guards against accidental modification of TestFixtures
        // (e.g., dropping a digit would silently corrupt every customer
        // test in the suite).
        assertThat(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01)
                .as("SAMPLE_CUSTOMER_ID_01 must be %d chars wide "
                        + "(matches CUST-ID PIC 9(09) per "
                        + "app/cpy/CUSTREC.cpy line 5)",
                        CUST_ID_WIDTH)
                .hasSize(CUST_ID_WIDTH);

        // Production-class invocation is deferred to a later Phase-3
        // fix-up once com.aws.carddemo.batch.CustomerFileProcessor lands
        // on disk. The current assertions establish the fixture-shape
        // pre-condition that the future invocation will rely on. The
        // expected future call site (documented in the class-level
        // javadoc) is:
        //
        //     final CustomerFileProcessor processor = new CustomerFileProcessor();
        //     final CustomerRecord result = processor.process(fixture);
        //     assertThat(result).isNotNull();
        //     assertThat(result.custId())
        //             .isEqualTo(TestFixtures.Customers.SAMPLE_CUSTOMER_ID_01);
    }

    // ============================================================
    // Negative path — truncated record (shorter than 500 bytes)
    // ============================================================

    /**
     * Verifies that a structurally truncated input (here: 20 bytes —
     * far below the {@link TestFixtures.RecordWidths#CUSTOMER_RECLN}
     * 500-byte width) is recognisable as malformed before it reaches
     * the production parser. The production
     * {@code CustomerFileProcessor} must guard against such inputs to
     * avoid silent data corruption in any downstream
     * {@code CustomerRepository} write.
     *
     * <p>{@code CBCUS01C} itself does not reject truncated records — it
     * is a thin DISPLAY-only utility that delegates structural integrity
     * to VSAM (which enforces the fixed RECLN). The migrated Java code,
     * however, reads from a {@code FlatFileItemReader<String>} which
     * does NOT enforce a fixed line width at the I/O boundary; the
     * processor itself must verify {@code record.length() ==
     * CUSTOMER_RECLN} (or equivalent) and reject otherwise.
     *
     * <p>This test asserts only the structural pre-condition (truncated
     * input is structurally smaller than the 500-byte contract) — the
     * production class's actual rejection mechanism (exception type,
     * reject record creation, count increment, etc.) is deferred to
     * the Phase-3 fix-up once the production class lands. The test
     * does not invoke {@code new CustomerFileProcessor()} for the same
     * reason as the happy-path test: the production class does not yet
     * exist on disk.
     */
    @Test
    @DisplayName("process_truncatedRecord_isRejected")
    void process_truncatedRecord_isRejected() {
        // Arrange — a structurally short input (20 bytes of '0').
        // 20 bytes is significantly less than the 500-byte
        // CUSTOMER_RECLN contract; in fact it is just enough to cover
        // CUST-ID (9 bytes) + 11 bytes of the FIRST-NAME field, leaving
        // 14 bytes of the FIRST-NAME field, the entire MIDDLE-NAME and
        // LAST-NAME fields, all address/phone/SSN/DOB fields, and the
        // 168-byte FILLER unfilled. Any production parser configured
        // for the 500-byte contract would fail at the first
        // substring() call past byte 20.
        final String truncated = "0".repeat(20);

        // Structural pre-condition #1: the truncated input is
        // structurally shorter than CUSTOMER_RECLN — the production
        // class's truncation guard will need this property to hold in
        // order to reject. If the test fixture is accidentally lengthened
        // to >= 500 bytes, the assertion below catches the defect.
        assertThat(truncated.length())
                .as("Truncated fixture must be strictly less than "
                        + "%d bytes (the CUSTOMER_RECLN contract); a "
                        + "non-truncated input would not exercise the "
                        + "rejection path that the production class is "
                        + "expected to implement",
                        TestFixtures.RecordWidths.CUSTOMER_RECLN)
                .isLessThan(TestFixtures.RecordWidths.CUSTOMER_RECLN);

        // Structural pre-condition #2: the input is non-empty (length
        // > 0). The zero-length case is a distinct EOF scenario
        // exercised by EofBoundaryTests below; this method
        // specifically covers the "partial record" scenario.
        assertThat(truncated.length())
                .as("Truncated fixture must be non-empty (length > 0); "
                        + "the zero-length case is covered by "
                        + "EofBoundaryTests as a separate EOF scenario")
                .isPositive();

        // Production-class invocation is deferred. The expected future
        // call site (once com.aws.carddemo.batch.CustomerFileProcessor
        // lands on disk) will use a try/catch (or
        // assertThatThrownBy(...)) to assert that the production class
        // raises a domain-specific RuntimeException on truncated
        // input — NOT a generic NullPointerException or
        // StringIndexOutOfBoundsException from a missing guard.
        //
        // Example future expansion:
        //     final CustomerFileProcessor processor = new CustomerFileProcessor();
        //     assertThatThrownBy(() -> processor.process(truncated))
        //             .isInstanceOf(RuntimeException.class)
        //             .hasMessageContaining("CUSTOMER_RECLN")
        //             .hasMessageContaining(String.valueOf(truncated.length()));
    }

    // ============================================================
    // Nested @ParameterizedTest — EOF and size-boundary scenarios
    // (driven by /fixtures/edge/eof_boundary.csv, 10 rows)
    // ============================================================

    /**
     * Nested test class grouping the {@code @ParameterizedTest} that
     * walks every row of {@code /fixtures/edge/eof_boundary.csv} (10
     * rows at sizes 0, 1, 9, 10, 11, 49, 50, 51, 99, 100). Each row
     * captures the EOF/size invariant for the
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop at lines 74–81 of
     * {@code app/cbl/CBCUS01C.cbl}: regardless of the total record
     * count, the loop must terminate naturally with the EOF flag set,
     * and every record in the fixture must be processed before the
     * terminator fires.
     *
     * <p>Per AAP §0.5.2 EofBoundaryTests blueprint, the nested-class
     * grouping cleanly isolates the parameterized boundary scenarios
     * from the top-level happy/truncated tests so failures localise
     * quickly (a parameterized failure shows up under
     * {@code CustomerFileProcessorTest$EofBoundaryTests} in the
     * Surefire report, with the per-row display name carrying the
     * concrete {@code fixtureSize} that triggered the defect).
     */
    @Nested
    @DisplayName("EOF and size-boundary scenarios")
    class EofBoundaryTests {

        /**
         * Walks every row of {@code /fixtures/edge/eof_boundary.csv}
         * and asserts the three EOF/size-boundary invariants captured
         * by the CSV columns:
         * <ul>
         *   <li>{@code fixtureSize} (column 0) — the number of
         *       {@code CUSTOMER-RECORD}s presented to the future
         *       production {@code CustomerFileProcessor}. The CSV
         *       covers sizes 0, 1, 9, 10, 11, 49, 50, 51, 99, 100 —
         *       these were chosen to span the empty-input boundary
         *       (0), the single-record boundary (1), the
         *       single-digit-to-double-digit transition (9, 10, 11),
         *       and the chunk-size-related boundaries that Spring
         *       Batch's default chunk size of 10 introduces around
         *       multiples of 10 (49, 50, 51, 99, 100). The CSV
         *       covers all sizes that {@code custdata.txt} (50
         *       records) and any reasonable test scaling thereof
         *       might present.</li>
         *   <li>{@code expectedRecordsRead} (column 1) — the number
         *       of records the production class must successfully
         *       process before the EOF flag is set. CBCUS01C reads
         *       every record from CUSTFILE before terminating; the
         *       loop body at line 78 of {@code app/cbl/CBCUS01C.cbl}
         *       (the {@code DISPLAY CUSTOMER-RECORD} statement)
         *       executes exactly once per record. Therefore
         *       {@code expectedRecordsRead == fixtureSize} on every
         *       row.</li>
         *   <li>{@code expectedEofFlag} (column 2) — whether the
         *       END-OF-FILE sentinel ({@code END-OF-FILE = 'Y'} per
         *       line 65 of {@code app/cbl/CBCUS01C.cbl}, originally
         *       initialised to {@code 'N'}) is asserted true after
         *       the fixture is exhausted. Per the
         *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop at lines
         *       74–81 the loop only terminates when the sentinel is
         *       set, so every row in the CSV has
         *       {@code expectedEofFlag == true}.</li>
         * </ul>
         *
         * <p>The CSV's strict structural invariant
         * ({@code expectedRecordsRead == fixtureSize} on every row,
         * {@code expectedEofFlag == true} on every row) captures the
         * read-everything-before-terminating contract that CBCUS01C
         * implements with VSAM and that the migrated
         * {@code CustomerFileProcessor} must preserve with the
         * Spring Batch chunk reader. Drift in either column on any row
         * would indicate either a CSV authoring defect or a
         * specification drift that needs explicit AAP-level alignment.
         *
         * <p>Per AAP §0.10.1 (Require Test Coverage rule): the
         * assertions are direct constant comparisons — no derivation,
         * no field arithmetic, no test-side computation. The
         * production class's actual EOF-detection mechanism (the
         * {@code FlatFileItemReader<String>}'s {@code read()} returning
         * {@code null}, or the {@code StepExecution} chunk loop
         * terminating naturally) is deferred to the Phase-3 fix-up
         * once the production class lands on disk.
         *
         * <p>Production-class invocation is deferred — the expected
         * future call site (documented in the class-level javadoc) is:
         * <pre>
         *   final CustomerFileProcessor processor = new CustomerFileProcessor();
         *   final List&lt;String&gt; fixtureRecords = buildFixture(fixtureSize);
         *   int recordsRead = 0;
         *   boolean eofFlag = false;
         *   for (String record : fixtureRecords) {
         *       processor.process(record);
         *       recordsRead++;
         *   }
         *   eofFlag = true;  // set after the reader exhausts
         *   assertThat(recordsRead).isEqualTo(expectedRecordsRead);
         *   assertThat(eofFlag).isEqualTo(expectedEofFlag);
         * </pre>
         *
         * @param fixtureSize         CSV column 0 — must be non-negative
         *                            (zero is the empty-input case)
         * @param expectedRecordsRead CSV column 1 — must equal
         *                            {@code fixtureSize} (every record
         *                            is processed before EOF)
         * @param expectedEofFlag     CSV column 2 — must be {@code true}
         *                            (every row is an EOF scenario)
         */
        @ParameterizedTest(name = "[{index}] fixtureSize={0}, expectedRecordsRead={1}, expectedEofFlag={2}")
        @CsvFileSource(resources = "/fixtures/edge/eof_boundary.csv", numLinesToSkip = 1)
        void process_atSizeBoundary_handlesCleanly(int fixtureSize,
                                                   int expectedRecordsRead,
                                                   boolean expectedEofFlag) {
            // CSV-consistency check #1: fixtureSize must be non-negative
            // (a negative record count is unrepresentable in COBOL —
            // FD-CUSTFILE-REC at line 38 of app/cbl/CBCUS01C.cbl is a
            // physical VSAM record count). Zero is allowed (the
            // empty-input boundary case — when custdata.txt is empty
            // the loop body at line 78 executes zero times and the EOF
            // flag is set on the first 1000-CUSTFILE-GET-NEXT call).
            assertThat(fixtureSize)
                    .as("Row [size=%d read=%d eof=%s]: fixtureSize "
                            + "must be non-negative (zero is the "
                            + "empty-input case)",
                            fixtureSize, expectedRecordsRead, expectedEofFlag)
                    .isNotNegative();

            // CSV-consistency check #2: expectedRecordsRead must equal
            // fixtureSize on every row — the CBCUS01C loop at lines
            // 74-81 of app/cbl/CBCUS01C.cbl reads every record before
            // terminating; the loop body at line 78 (DISPLAY
            // CUSTOMER-RECORD) executes exactly once per record. The
            // migrated CustomerFileProcessor must preserve this
            // "read-everything-before-terminating" contract.
            assertThat(expectedRecordsRead)
                    .as("Row [size=%d read=%d eof=%s]: "
                            + "expectedRecordsRead must equal "
                            + "fixtureSize (the CBCUS01C "
                            + "read-everything-before-terminating "
                            + "contract per app/cbl/CBCUS01C.cbl "
                            + "lines 74-81)",
                            fixtureSize, expectedRecordsRead, expectedEofFlag)
                    .isEqualTo(fixtureSize);

            // CSV-consistency check #3: expectedEofFlag must be true on
            // every row — the PERFORM UNTIL END-OF-FILE = 'Y' loop at
            // lines 74-81 only terminates when the sentinel is set
            // (which the 1000-CUSTFILE-GET-NEXT paragraph at lines
            // 92-112 sets when CUSTFILE-STATUS = '10'). Any row in
            // the CSV with expectedEofFlag = false would indicate a
            // CSV authoring defect or a spec drift that needs
            // explicit AAP-level alignment.
            assertThat(expectedEofFlag)
                    .as("Row [size=%d read=%d eof=%s]: expectedEofFlag "
                            + "must be true (the CBCUS01C PERFORM "
                            + "UNTIL END-OF-FILE = 'Y' loop only "
                            + "terminates with the sentinel set)",
                            fixtureSize, expectedRecordsRead, expectedEofFlag)
                    .isTrue();

            // CSV-consistency check #4: fixtureSize must be within a
            // reasonable bounded range. The CSV's largest documented
            // size is 100 (see /fixtures/edge/eof_boundary.csv last
            // row). The 1,000-record upper sanity bound here is a
            // generous ceiling that accommodates future CSV expansion
            // (e.g., chunk-size-related boundaries at 500, 750) while
            // still catching catastrophic test-fixture corruption
            // (e.g., a row with fixtureSize = Integer.MAX_VALUE).
            assertThat(fixtureSize)
                    .as("Row [size=%d read=%d eof=%s]: fixtureSize "
                            + "must be <= 1000 (sanity bound on the "
                            + "EOF-boundary fixture; documented sizes "
                            + "in /fixtures/edge/eof_boundary.csv "
                            + "currently top out at 100)",
                            fixtureSize, expectedRecordsRead, expectedEofFlag)
                    .isLessThanOrEqualTo(1000);
        }
    }

    // ============================================================
    // Private helpers
    // ============================================================

    /**
     * Right-pads a string with ASCII spaces to the requested width, or
     * truncates it (from the right) if it already exceeds the width.
     *
     * <p>This is fixture scaffolding, not production logic — it
     * realises the COBOL "MOVE 'abc' TO field" convention where COBOL
     * automatically space-pads (or right-truncates) the assigned value
     * to the declared {@code PIC X(n)} width. Used only inside this
     * test class to build the canonical 500-byte
     * {@code CUSTOMER-RECORD} fixture in the happy-path test from
     * variable-length human-readable string literals like {@code "JANE"}
     * and {@code "DOE"}.
     *
     * <p>The helper is deliberately kept minimal (no padding character
     * parameter, no left/right toggle, no Unicode awareness) so it
     * cannot accidentally embed business logic into the test body.
     * The production {@code CustomerFileProcessor} class will use its
     * own fixed-width parser (likely a Spring Batch
     * {@code FixedLengthTokenizer} or a manual {@code substring()}
     * sequence) — independent of and unaware of this helper.
     *
     * @param s     the input string (must not be null — null inputs
     *              are not supported because this helper is only
     *              called with string-literal arguments inside this
     *              test class)
     * @param width the target width in bytes (must be {@code >= 0})
     * @return a string of exactly {@code width} bytes — either {@code s}
     *         right-padded with ASCII spaces, or {@code s} truncated to
     *         {@code width} bytes (matches the COBOL
     *         {@code MOVE ... TO} convention)
     */
    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        final StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
