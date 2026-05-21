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
package com.aws.carddemo.testsupport;

// NO imports beyond java.lang.* — TestFixtures is a pure-constants class
// (Agent Action Plan §0.10.1 Require Test Coverage rule: no business logic,
// no derivation, no encoding/decoding — string literals only).

/**
 * Shared test constants for the CardDemo migration test suite.
 *
 * <p>Centralises every literal value that appears in two or more test classes: sample
 * account identifiers, card numbers, customer identifiers, user identifiers, fixture
 * file paths, COBOL reject-reason strings, discount-group identifiers, COBOL date
 * stamps, bank branding literals, sign-overpunch character sentinels, VSAM file-status
 * codes, and fixed-width record lengths from the {@code app/cpy/} copybooks.
 *
 * <p>Constants are grouped into static nested classes (for example {@link Accounts},
 * {@link Cards}, {@link Paths}, {@link Dates}) for cleaner call-site disambiguation
 * (for example {@code TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10} versus a flat
 * constant naming scheme where dozens of unrelated literals would share a single
 * namespace).
 *
 * <h2>Security</h2>
 *
 * <p>Per AAP §0.10.5 ("No plaintext credentials in any configuration file"): this class
 * carries no real PII and no production credentials.
 * <ul>
 *   <li>Sample card numbers come from the publicly documented Visa test PAN range
 *       ({@code 4111 1111 1111 11xx}) and are explicitly synthetic.</li>
 *   <li>User identifiers use generic placeholders ({@code USRTST01}, {@code ADMTST01}).</li>
 *   <li>{@link Users#TEST_PASSWORD_BCRYPT_HASH} is a real BCrypt hash of the fixture
 *       plaintext value {@link Users#TEST_PASSWORD_PLAINTEXT} ({@code "TESTPASS"}). The
 *       plaintext is a FIXTURE credential, not a real password — it is documented
 *       openly here because it carries no security value beyond test scope and cannot
 *       authenticate against any production system.</li>
 * </ul>
 *
 * <h2>Business logic</h2>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule): {@code TestFixtures} contains NO
 * methods (other than the private constructors that block instantiation), NO
 * arithmetic, NO encoding/decoding. It only holds {@code public static final} string,
 * integer, and character constants. Production code under {@code com.aws.carddemo.*}
 * is responsible for sign-overpunch decoding, BigDecimal arithmetic, and record
 * parsing — tests must drive production code and never re-implement that logic inside
 * the test body or inside {@code TestFixtures}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (Test Support Utilities), §0.5.5 (Cross-File Test Dependencies),
 * §0.10.5 (Security Constraints), §0.10.1 (Require Test Coverage rule),
 * §0.10.4 (Immutable Boundaries — reject codes 100–103 + 109).
 */
public final class TestFixtures {

    /**
     * Private constructor — {@code TestFixtures} is a pure-constants holder and must not
     * be instantiated. Throws {@link UnsupportedOperationException} to fail loudly if
     * reflection or a test misuse attempts to construct it.
     */
    private TestFixtures() {
        throw new UnsupportedOperationException(
            "TestFixtures is a constants holder and cannot be instantiated.");
    }

    // ================================================================
    // Paths — classpath and fixture filenames
    // ================================================================

    /**
     * Classpath directories and fixture filenames for canonical baseline inputs,
     * captured COBOL reference outputs, and edge-case CSVs.
     *
     * <p>Path-style values are absolute classpath paths (leading {@code /}) so callers
     * can pass them directly to {@code Class.getResourceAsStream(...)} or
     * {@code ClassLoader.getSystemResourceAsStream(...)} without having to prepend a
     * separator. Filename-style values are bare basenames so callers can concatenate
     * them with the directory constant of their choice.
     *
     * <p>The actual on-disk locations under {@code src/test/resources/} are:
     * <pre>
     *   {@code src/test/resources/baseline/input/}       -- canonical golden inputs
     *   {@code src/test/resources/baseline/expected/}    -- captured COBOL reference outputs
     *   {@code src/test/resources/fixtures/edge/}        -- edge-case CSVs for @CsvFileSource
     * </pre>
     *
     * <p>Per AAP §0.4.4 (Test Data and Fixtures Design).
     */
    public static final class Paths {
        private Paths() {
            throw new UnsupportedOperationException(
                "Paths is a constants holder and cannot be instantiated.");
        }

        // ----- Classpath directory roots (loaded by FixtureLoader) -----

        /** Classpath directory containing canonical golden input files (ASCII fixtures). */
        public static final String CLASSPATH_BASELINE_INPUT_DIR = "/baseline/input/";
        /** Classpath directory containing captured COBOL reference output files. */
        public static final String CLASSPATH_BASELINE_EXPECTED_DIR = "/baseline/expected/";
        /** Classpath directory containing edge-case CSV fixtures for {@code @CsvFileSource}. */
        public static final String CLASSPATH_EDGE_FIXTURES_DIR = "/fixtures/edge/";

        // ----- Baseline input filenames -----

        /** Account master records — backs {@code AccountRepository} / {@code AccountFileProcessor}. */
        public static final String FIXTURE_ACCTDATA = "acctdata.txt";
        /** Card master records — backs {@code CardRepository} / {@code CardFileProcessor}. */
        public static final String FIXTURE_CARDDATA = "carddata.txt";
        /** Card cross-reference — backs {@code CardXrefRepository} / {@code CardXrefFileProcessor}. */
        public static final String FIXTURE_CARDXREF = "cardxref.txt";
        /** Customer master records — backs {@code CustomerRepository} / {@code CustomerFileProcessor}. */
        public static final String FIXTURE_CUSTDATA = "custdata.txt";
        /** Daily transactions — backs posting, validation, and report processors. */
        public static final String FIXTURE_DAILYTRAN = "dailytran.txt";
        /** Disclosure groups (including DEFAULT and ZEROAPR) — backs {@code InterestCalculationProcessor}. */
        public static final String FIXTURE_DISCGRP = "discgrp.txt";
        /** Transaction-category balances — backs interest and combine processors. */
        public static final String FIXTURE_TCATBAL = "tcatbal.txt";
        /** Transaction categories (reference data, 18 categories). */
        public static final String FIXTURE_TRANCATG = "trancatg.txt";
        /** Transaction types (reference data, 7 types). */
        public static final String FIXTURE_TRANTYPE = "trantype.txt";

        // ----- Baseline expected-output filenames (right-hand operand of BaselineDiffUtil) -----

        /** Captured COBOL reference output for POSTTRAN.jcl ({@code CBTRN02C}). */
        public static final String EXPECTED_POSTED = "posted.txt";
        /** Captured COBOL reference output for INTCALC.jcl ({@code CBACT04C}, TCATBAL after interest). */
        public static final String EXPECTED_TCATBAL_AFTER_INTEREST = "tcatbal_after_interest.txt";
        /** Captured COBOL reference output for COMBTRAN.jcl (DFSORT merge). */
        public static final String EXPECTED_COMBINED = "combined.txt";
        /** Captured COBOL reference output for CREASTMT.jcl — text statement form ({@code CBSTM03A}). */
        public static final String EXPECTED_STATEMENTS_TEXT = "statements_text.txt";
        /** Captured COBOL reference output for CREASTMT.jcl — HTML statement form ({@code CBSTM03A}). */
        public static final String EXPECTED_STATEMENTS_HTML = "statements_html.txt";
        /** Captured COBOL reference output for TRANREPT.jcl ({@code CBTRN03C}). */
        public static final String EXPECTED_TRANSACTION_REPORT = "transaction_report.txt";

        // ----- Edge-case CSV filenames (loaded by @ParameterizedTest @CsvFileSource) -----

        /** Edge cases proving HALF_EVEN rounding (the {@code 100.005} boundary). */
        public static final String EDGE_INTEREST_HALFEVEN_BOUNDARY = "interest_halfeven_boundary.csv";
        /** Edge cases proving zero balance yields zero interest. */
        public static final String EDGE_INTEREST_ZERO_BALANCE = "interest_zero_balance.csv";
        /** Edge cases proving ZEROAPR group skips interest calculation entirely. */
        public static final String EDGE_INTEREST_ZEROAPR_SKIP = "interest_zeroapr_skip.csv";
        /** Edge cases proving the DEFAULT group fallback when an account's group is missing. */
        public static final String EDGE_INTEREST_DEFAULT_FALLBACK = "interest_default_fallback.csv";
        /** Edge cases for posting reject codes 100–103 + 109 ({@code CBTRN02C}). */
        public static final String EDGE_POSTING_REJECT_CODES = "posting_reject_codes.csv";
        /** Edge cases for BigDecimal overflow at the {@code PIC 9(7)V99} boundary. */
        public static final String EDGE_OVERFLOW_BOUNDARY = "overflow_boundary.csv";
        /** Edge cases for {@code ValidationLookupService} (invalid area codes / states / ZIPs). */
        public static final String EDGE_LOOKUP_INVALID_KEYS = "lookup_invalid_keys.csv";
        /** Edge cases for {@code DateValidationService} (leap/century/format variants). */
        public static final String EDGE_DATE_VALIDATION_VARIANTS = "date_validation_variants.csv";
        /** Edge cases for {@code FileStatusMapper} (VSAM status codes 00, 02, 10, 22, 23, 35, 92, 97). */
        public static final String EDGE_STATUS_CODE_MAPPINGS = "status_code_mappings.csv";
        /** Edge cases for end-of-file boundary (last-record, empty-file, trailing-newline). */
        public static final String EDGE_EOF_BOUNDARY = "eof_boundary.csv";
    }

    // ================================================================
    // Accounts — sample 11-digit account identifiers per CVACT01Y
    // ================================================================

    /**
     * Sample account identifiers used across service tests, controller tests, and ITs.
     *
     * <p>All values are 11-digit zero-padded strings matching the COBOL
     * {@code ACCT-ID PIC 9(11)} field width declared in {@code app/cpy/CVACT01Y.cpy}.
     * IDs in the range {@code 00000000001}–{@code 00000000050} exist in the
     * {@code acctdata.txt} fixture; higher IDs ({@code 00000000051}–{@code 00000000060})
     * are reserved for synthetic-insert tests that do not depend on existing fixture
     * rows.
     *
     * <p>Per AAP §0.5.5: the canonical sample range cited in the AAP is
     * {@code 00000000010}–{@code 00000000060}; the fixture file currently materialises
     * only the 1–50 subset (50 records, AAP §0.4.4 fixture table). The
     * {@link #NEW_ACCOUNT_ID_60} constant is therefore reserved exclusively for
     * synthetic INSERT tests where the test seeds its own row.
     */
    public static final class Accounts {
        private Accounts() {
            throw new UnsupportedOperationException(
                "Accounts is a constants holder and cannot be instantiated.");
        }

        /** 11-digit {@code ACCT-ID} present in {@code acctdata.txt} record 10. */
        public static final String SAMPLE_ACCOUNT_ID_10 = "00000000010";
        /** 11-digit {@code ACCT-ID} present in {@code acctdata.txt} record 20. */
        public static final String SAMPLE_ACCOUNT_ID_20 = "00000000020";
        /** 11-digit {@code ACCT-ID} present in {@code acctdata.txt} record 30. */
        public static final String SAMPLE_ACCOUNT_ID_30 = "00000000030";
        /** 11-digit {@code ACCT-ID} present in {@code acctdata.txt} record 40. */
        public static final String SAMPLE_ACCOUNT_ID_40 = "00000000040";
        /** 11-digit {@code ACCT-ID} present in {@code acctdata.txt} record 50 (last fixture record). */
        public static final String SAMPLE_ACCOUNT_ID_50 = "00000000050";

        /**
         * 11-digit {@code ACCT-ID} reserved for synthetic-insert tests; does NOT exist
         * in the on-disk {@code acctdata.txt} fixture. Tests using this constant are
         * expected to seed the row themselves (for example via repository {@code save()}
         * in a {@code @DataJpaTest}).
         */
        public static final String NEW_ACCOUNT_ID_60 = "00000000060";

        /**
         * 11-digit {@code ACCT-ID} guaranteed not to exist in any fixture (used for
         * "not-found" and reject-code 101 tests).
         */
        public static final String NONEXISTENT_ACCOUNT_ID = "99999999999";

        /**
         * Canonical default group identifier ({@code A000000000}) carried in the
         * {@code ACCT-GROUP-ID PIC X(10)} field of every record in {@code acctdata.txt}
         * (see {@code app/cpy/CVACT01Y.cpy}). This is the literal stored on disk for
         * accounts that have no specialised disclosure group; it is distinct from the
         * {@link DiscountGroups#DEFAULT_GROUP} sentinel ({@code "DEFAULT   "}) which
         * appears as a row key in {@code discgrp.txt} and triggers CBACT04C's
         * DEFAULT-group fallback path.
         */
        public static final String DEFAULT_GROUP_ID = "A000000000";
    }

    // ================================================================
    // Cards — sample 16-digit card numbers per CVACT02Y
    // ================================================================

    /**
     * Sample card numbers used across card service tests, controller tests, posting
     * tests, and ITs.
     *
     * <p>All values are 16-digit Visa test PAN numbers drawn from the publicly
     * documented {@code 4111 1111 1111 11xx} range — these are reserved test
     * Personal Account Numbers that cannot be issued by any payment network and
     * therefore carry no PII.
     *
     * <p>Card numbers are 16-character per {@code CARD-NUM PIC X(16)} in
     * {@code app/cpy/CVACT02Y.cpy}. The {@code SAMPLE_CARD_NUMBER_*} constants
     * mirror the converted {@code carddata.txt} and {@code cardxref.txt} fixtures:
     * row N of each fixture file carries PAN {@code 4111111111111} + zero-padded
     * {@code (100 + N)}; so record 1 has {@code 4111111111111101}, record 10 has
     * {@code 4111111111111110}, and record 50 (the last) has {@code 4111111111111150}.
     * See {@code docs/testing/test-strategy.md} §11.2 for the documented PAN
     * conversion from the original {@code app/data/ASCII/*.txt} sources.
     *
     * <p>Per AAP §0.5.5 ("sample IDs ... card {@code 4111111111111101} through
     * {@code 4111111111111150}") and §0.10.5 ("No real PII").
     */
    public static final class Cards {
        private Cards() {
            throw new UnsupportedOperationException(
                "Cards is a constants holder and cannot be instantiated.");
        }

        /**
         * Visa test PAN — sample card number for the first fixture record.
         * Matches row 1 of the converted {@code carddata.txt} and {@code cardxref.txt}
         * fixtures (see {@code docs/testing/test-strategy.md} §11.2).
         */
        public static final String SAMPLE_CARD_NUMBER_01 = "4111111111111101";
        /**
         * Visa test PAN — sample card number for the 10th fixture record.
         * Matches row 10 of the converted {@code carddata.txt} and
         * {@code cardxref.txt} fixtures.
         */
        public static final String SAMPLE_CARD_NUMBER_10 = "4111111111111110";
        /**
         * Visa test PAN — sample card number for the 50th (last) fixture record.
         * Matches row 50 of the converted {@code carddata.txt} and
         * {@code cardxref.txt} fixtures.
         */
        public static final String SAMPLE_CARD_NUMBER_50 = "4111111111111150";

        /**
         * 16-digit card number guaranteed not to exist in the fixture; used to drive
         * {@code CardDetailService} "card-not-found" tests and the {@code CBTRN02C}
         * reject-code 100 path. Deliberately outside the converted fixture range
         * {@code 4111111111111101}-{@code 4111111111111150} so a lookup that misses
         * the in-fixture sequence cannot accidentally match a real fixture row.
         */
        public static final String NONEXISTENT_CARD_NUMBER = "4999999999999999";

        /**
         * {@code CARD-ACTIVE-STATUS} value {@code 'Y'} (active) from
         * {@code CVACT02Y.cpy}. Tests use this when asserting the active-status flag
         * carried on a {@code CardRecord} returned by the service layer.
         */
        public static final String ACTIVE_STATUS_YES = "Y";
        /**
         * {@code CARD-ACTIVE-STATUS} value {@code 'N'} (inactive) from
         * {@code CVACT02Y.cpy}; drives reject-code paths for closed-card transactions.
         */
        public static final String ACTIVE_STATUS_NO = "N";
    }

    // ================================================================
    // Customers — sample 9-digit customer identifiers per CUSTREC
    // ================================================================

    /**
     * Sample customer identifiers (9-digit per {@code CUST-ID PIC 9(09)} in
     * {@code app/cpy/CUSTREC.cpy}). The {@code custdata.txt} fixture contains 50
     * records keyed {@code 000000001}–{@code 000000050}; values above 50 are reserved
     * for synthetic-insert tests.
     */
    public static final class Customers {
        private Customers() {
            throw new UnsupportedOperationException(
                "Customers is a constants holder and cannot be instantiated.");
        }

        /** 9-digit {@code CUST-ID} present in {@code custdata.txt} record 1. */
        public static final String SAMPLE_CUSTOMER_ID_01 = "000000001";
        /** 9-digit {@code CUST-ID} present in {@code custdata.txt} record 10. */
        public static final String SAMPLE_CUSTOMER_ID_10 = "000000010";
        /** 9-digit {@code CUST-ID} present in {@code custdata.txt} record 50 (last fixture record). */
        public static final String SAMPLE_CUSTOMER_ID_50 = "000000050";

        /**
         * 9-digit {@code CUST-ID} guaranteed not to exist in any fixture (used for
         * customer "not-found" tests).
         */
        public static final String NONEXISTENT_CUSTOMER_ID = "999999999";
    }

    // ================================================================
    // Users — sample 8-char user identifiers + BCrypt password hash
    // ================================================================

    /**
     * Sample user identifiers and password fixture values for the
     * authentication / authorisation tests that replace {@code COSGN00C} (sign-on)
     * and the {@code COUSR0*C} family.
     *
     * <p>User identifiers are 8-character per {@code SEC-USR-ID PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy}. The fixture user records (in {@code USRSEC.txt} or
     * its migrated SQL seed in {@code V3__seed.sql}) carry
     * {@link #TEST_PASSWORD_BCRYPT_HASH} for {@code SEC-USR-PWD} so authentication
     * tests can verify the BCrypt-verify code path without re-hashing at
     * fixture-load time. (The agent that generated this hash burned the cost once,
     * offline; subsequent test runs pay nothing.)
     *
     * <p>Per AAP §0.10.5 ("No plaintext credentials in any configuration file"): the
     * plaintext value {@link #TEST_PASSWORD_PLAINTEXT} is a FIXTURE credential, not a
     * real password. It is documented openly here because it carries no security
     * value beyond test scope — it cannot authenticate against any production
     * system. The CBOL {@code SEC-USR-PWD PIC X(08)} field requires an 8-character
     * password; {@code "TESTPASS"} is exactly 8 characters, matching the field width.
     */
    public static final class Users {
        private Users() {
            throw new UnsupportedOperationException(
                "Users is a constants holder and cannot be instantiated.");
        }

        /**
         * 8-character regular-user fixture identifier — {@code SEC-USR-TYPE = 'U'} per
         * {@code CSUSR01Y.cpy}.
         */
        public static final String REGULAR_USER_ID = "USRTST01";

        /**
         * 8-character admin-user fixture identifier — {@code SEC-USR-TYPE = 'A'} per
         * {@code CSUSR01Y.cpy}; admin-only endpoints (user list, user add, user delete,
         * report submission) require this role.
         */
        public static final String ADMIN_USER_ID = "ADMTST01";

        /**
         * 8-character fixture user identifier guaranteed not to exist in
         * {@code USRSEC.txt} or {@code V3__seed.sql}; drives the unknown-user
         * authentication-failure path in {@code AuthenticationService} tests.
         */
        public static final String NONEXISTENT_USER_ID = "NOTAUSER";

        /**
         * Plaintext password fixture value used <strong>solely</strong> as the input
         * to {@code AuthenticationService.authenticate(...)} when a test needs to
         * exercise the BCrypt-verify code path against {@link #TEST_PASSWORD_BCRYPT_HASH}.
         *
         * <p><strong>NOT a stored credential.</strong> This is the literal 8-character
         * string that was hashed once (offline) to produce
         * {@link #TEST_PASSWORD_BCRYPT_HASH}. It carries zero security value because:
         * <ul>
         *   <li>It cannot authenticate against any production system — only against
         *       the fixture user records in test-scope seed data carrying
         *       {@link #TEST_PASSWORD_BCRYPT_HASH}.</li>
         *   <li>It is exposed in source openly so static analysis cannot
         *       inadvertently flag it as a leaked credential.</li>
         *   <li>It is provided <em>as input</em> to the authentication code path —
         *       never written to a database, configuration file, log, or persistent
         *       store. Stored fixture values remain BCrypt-only
         *       ({@link #TEST_PASSWORD_BCRYPT_HASH}).</li>
         * </ul>
         *
         * <p>Per AAP §0.10.5 (Security Constraints): the "no plaintext credentials"
         * rule applies to <em>configuration files</em> (e.g., application properties,
         * YAML, Kubernetes manifests) and to <em>persisted stores</em>. Test inputs
         * provided to a verification function are categorically different and are
         * the canonical Spring Security testing pattern.
         *
         * <p>Length: 8 characters — matches {@code SEC-USR-PWD PIC X(08)} field width.
         */
        public static final String TEST_PASSWORD_PLAINTEXT = "TESTPASS";

        /**
         * Pre-computed BCrypt hash of {@link #TEST_PASSWORD_PLAINTEXT} at strength 10
         * (the Spring Security {@code BCryptPasswordEncoder} default).
         *
         * <p>The fixture user records in {@code USRSEC.txt} (or its migrated SQL seed
         * in {@code V3__seed.sql}) carry this hash for {@code SEC-USR-PWD} so
         * authentication tests can verify the BCrypt-verify code path without
         * re-hashing at fixture-load time. At BCrypt strength 10 each hash takes
         * roughly 80 ms to compute; pre-computing once keeps the unit-test wall-clock
         * comfortably under the AAP §0.7.2 target of {@code < 60 s} for the entire
         * unit suite.
         *
         * <p>Format: {@code $2a$10$<22-char-salt><31-char-hash>} (exactly 60
         * characters). Generated offline by invoking
         * {@code new BCryptPasswordEncoder(10).encode("TESTPASS")} once and pasting
         * the result. Deterministic for the fixture in the sense that this exact
         * value verifies {@code "TESTPASS"} on every invocation (BCrypt is
         * deterministic given salt + plaintext); regenerating would produce a
         * different valid hash because BCrypt randomises the salt on each
         * {@code encode()} call.
         */
        public static final String TEST_PASSWORD_BCRYPT_HASH =
            "$2a$10$GcKjowWyfhezZtg4gS7f0.rUk1kaLR.B9PMSrCk3tZu69dQmnwTw.";
    }

    // ================================================================
    // Transactions — sample 16-char transaction identifiers per CVTRA05Y
    // ================================================================

    /**
     * Sample transaction identifiers, transaction-type codes, transaction-category
     * codes, and transaction-source literals from the COBOL data model.
     *
     * <p>{@code TRAN-ID} is 16-character per {@code CVTRA05Y.cpy}. Daily-transaction
     * IDs in {@code dailytran.txt} are zero-padded numeric strings; interest
     * transactions generated by {@code CBACT04C} are 16-character IDs prefixed with
     * the JCL PARM {@link Dates#INTCALC_PARM} ({@code "2022071800"}) plus a 6-digit
     * sequential suffix.
     *
     * <p>Transaction-type codes are 2-character per {@code TRAN-TYPE-CD PIC X(02)};
     * transaction-category codes are 4-digit per {@code TRAN-CAT-CD PIC 9(04)};
     * transaction sources are 10-character per {@code TRAN-SOURCE PIC X(10)}
     * (space-padded on the right).
     */
    public static final class Transactions {
        private Transactions() {
            throw new UnsupportedOperationException(
                "Transactions is a constants holder and cannot be instantiated.");
        }

        /**
         * Example daily-transaction identifier present in {@code dailytran.txt}
         * (record 1). 16-character zero-padded numeric.
         */
        public static final String SAMPLE_TRANSACTION_ID = "0000000000683580";

        /**
         * Example interest transaction identifier produced by {@code CBACT04C}: the
         * 10-character {@link Dates#INTCALC_PARM} ({@code "2022071800"}) concatenated
         * with a 6-digit sequential suffix yields the full 16-character {@code TRAN-ID}.
         */
        public static final String SAMPLE_INTEREST_TRANSACTION_ID = "2022071800000001";

        /** {@code TRAN-TYPE-CD = '01'} (Purchase) — first record in {@code trantype.txt}. */
        public static final String TRAN_TYPE_PURCHASE = "01";
        /** {@code TRAN-TYPE-CD = '02'} (Payment). */
        public static final String TRAN_TYPE_PAYMENT = "02";
        /** {@code TRAN-TYPE-CD = '03'} (Credit). */
        public static final String TRAN_TYPE_CREDIT = "03";

        /** {@code TRAN-CAT-CD = '0001'} (Regular Sales Draft) — from {@code trancatg.txt}. */
        public static final String TRAN_CAT_REGULAR_SALES = "0001";

        /**
         * {@code TRAN-CAT-CD = '0005'} (Interest Amount). {@code CBACT04C} executes
         * {@code MOVE '05' TO TRAN-CAT-CD}; the receiving field is {@code PIC 9(04)}
         * so the numeric MOVE right-justifies with leading zeros to produce
         * {@code "0005"}.
         */
        public static final String TRAN_CAT_INTEREST = "0005";

        /**
         * {@code TRAN-SOURCE} value set on interest transactions generated by
         * {@code CBACT04C} ({@code MOVE 'System' TO TRAN-SOURCE} at line 484 of
         * {@code app/cbl/CBACT04C.CBL}). The receiving field is {@code PIC X(10)} so
         * the literal {@code 'System'} (6 characters) is space-padded on the right to
         * yield {@code "System    "} (10 characters total).
         *
         * <p>Per AAP §0.10.4 ("All financial calculation results MUST match COBOL
         * baseline output exactly"): the migrated {@code InterestCalculationProcessor}
         * must emit this exact 10-character literal for byte-identical parity with
         * {@code tcatbal_after_interest.txt}.
         */
        public static final String TRAN_SOURCE_INTEREST = "System    ";

        /**
         * {@code TRAN-SOURCE} value set on POS-originated transactions in
         * {@code dailytran.txt}: the literal {@code 'POS TERM'} (8 characters)
         * space-padded to {@code "POS TERM  "} (10 characters total) per the
         * {@code TRAN-SOURCE PIC X(10)} field width.
         */
        public static final String TRAN_SOURCE_POS = "POS TERM  ";
    }

    // ================================================================
    // RejectReasons — CBTRN02C validation-fail-reason literal strings
    // ================================================================

    /**
     * Reject-reason literal strings emitted by {@code CBTRN02C} (transaction posting
     * validation cascade).
     *
     * <p>These literal strings appear in the COBOL source as
     * {@code MOVE '<reason>' TO WS-VALIDATION-FAIL-REASON-DESC} statements. The
     * migration's {@code RejectException} subclasses must carry the same
     * human-readable text so the migrated reject records match the COBOL baseline
     * byte-for-byte wherever the reason appears in output (chiefly the
     * {@code DALYREJS-FILE} record trailer).
     *
     * <p>The corresponding reject codes (per AAP §0.10.4 and the COBOL source) are:
     * <ul>
     *   <li>{@code 100} — {@link #INVALID_CARD_NUMBER_FOUND}</li>
     *   <li>{@code 101} — {@link #ACCOUNT_RECORD_NOT_FOUND}</li>
     *   <li>{@code 102} — {@link #OVERLIMIT_TRANSACTION}</li>
     *   <li>{@code 103} — {@link #TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION}</li>
     * </ul>
     *
     * <p>See {@code app/cbl/CBTRN02C.CBL} lines 385–419 for the verbatim COBOL
     * literals these constants mirror.
     */
    public static final class RejectReasons {
        private RejectReasons() {
            throw new UnsupportedOperationException(
                "RejectReasons is a constants holder and cannot be instantiated.");
        }

        /**
         * Reject-reason for code {@link RejectCodes#INVALID_CARD} (100) — paired with
         * {@code WS-VALIDATION-FAIL-REASON = 100} in {@code CBTRN02C}. Verbatim COBOL
         * literal at {@code app/cbl/CBTRN02C.CBL} line 386.
         */
        public static final String INVALID_CARD_NUMBER_FOUND = "INVALID CARD NUMBER FOUND";

        /**
         * Reject-reason for code {@link RejectCodes#ACCOUNT_NOT_FOUND} (101) — paired
         * with {@code WS-VALIDATION-FAIL-REASON = 101} in {@code CBTRN02C}. Verbatim
         * COBOL literal at {@code app/cbl/CBTRN02C.CBL} line 398 (and re-used at line
         * 557 for the {@code 109} variant).
         */
        public static final String ACCOUNT_RECORD_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

        /**
         * Reject-reason for code {@link RejectCodes#OVERLIMIT} (102) — paired with
         * {@code WS-VALIDATION-FAIL-REASON = 102} in {@code CBTRN02C}. Verbatim COBOL
         * literal at {@code app/cbl/CBTRN02C.CBL} line 411.
         */
        public static final String OVERLIMIT_TRANSACTION = "OVERLIMIT TRANSACTION";

        /**
         * Reject-reason for code {@link RejectCodes#ACCOUNT_EXPIRED} (103) — paired
         * with {@code WS-VALIDATION-FAIL-REASON = 103} in {@code CBTRN02C}. Verbatim
         * COBOL literal at {@code app/cbl/CBTRN02C.CBL} line 418.
         */
        public static final String TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
    }

    // ================================================================
    // RejectCodes — CBTRN02C validation-fail-reason numeric codes
    // ================================================================

    /**
     * Numeric reject codes used in {@code CBTRN02C} output for posting failures.
     *
     * <p>The {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} field carries one of these
     * codes whenever a daily-transaction record fails any stage of the validation
     * cascade. The corresponding human-readable reason is in {@link RejectReasons}.
     *
     * <p>Per AAP §0.10.4: reject-code paths are mandatory edge-case coverage; each
     * code must have at least one {@code @ParameterizedTest} row in
     * {@code TransactionPostingProcessorTest}.
     */
    public static final class RejectCodes {
        private RejectCodes() {
            throw new UnsupportedOperationException(
                "RejectCodes is a constants holder and cannot be instantiated.");
        }

        /** Reject code 100 — invalid card number ({@link RejectReasons#INVALID_CARD_NUMBER_FOUND}). */
        public static final int INVALID_CARD = 100;
        /** Reject code 101 — account record not found ({@link RejectReasons#ACCOUNT_RECORD_NOT_FOUND}). */
        public static final int ACCOUNT_NOT_FOUND = 101;
        /** Reject code 102 — overlimit transaction ({@link RejectReasons#OVERLIMIT_TRANSACTION}). */
        public static final int OVERLIMIT = 102;
        /**
         * Reject code 103 — transaction received after account expiration
         * ({@link RejectReasons#TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION}).
         */
        public static final int ACCOUNT_EXPIRED = 103;
        /**
         * Reject code 109 — secondary "account record not found" code emitted by
         * {@code CBTRN02C} when the {@code TCATBAL} lookup yields no row for an
         * otherwise valid account (see {@code app/cbl/CBTRN02C.CBL} line 556). The
         * reason literal is shared with reject code 101
         * ({@link RejectReasons#ACCOUNT_RECORD_NOT_FOUND}); the distinct code lets
         * downstream telemetry distinguish the two paths.
         */
        public static final int OTHER_REJECT = 109;
    }

    // ================================================================
    // DiscountGroups — CBACT04C 10-char disclosure-group identifiers
    // ================================================================

    /**
     * Disclosure-group identifier constants from {@code CBACT04C} (interest
     * calculator).
     *
     * <p>All values are 10-character zero/space-padded strings matching the COBOL
     * {@code DIS-ACCT-GROUP-ID PIC X(10)} field width declared at line 79 of
     * {@code app/cbl/CBACT04C.CBL}. Two special-case groups drive the
     * interest-calculator branch logic:
     * <ul>
     *   <li>{@link #DEFAULT_GROUP} — fallback group used when an account's configured
     *       disclosure group is not found in {@code discgrp.txt}; {@code CBACT04C}
     *       executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} (line 437) and
     *       re-reads {@code DISCGRP-FILE} via paragraph
     *       {@code 1200-A-GET-DEFAULT-INT-RATE}.</li>
     *   <li>{@link #ZEROAPR_GROUP} — accounts with zero APR; the interest calculation
     *       inside paragraph {@code 1300-COMPUTE-INTEREST} is skipped via the
     *       {@code IF DIS-INT-RATE NOT = 0} guard.</li>
     * </ul>
     */
    public static final class DiscountGroups {
        private DiscountGroups() {
            throw new UnsupportedOperationException(
                "DiscountGroups is a constants holder and cannot be instantiated.");
        }

        /**
         * Fallback group key used when an account's configured group is not present
         * in {@code discgrp.txt}. The literal stored on disk is {@code "DEFAULT"}
         * (7 characters) plus 3 trailing spaces to fill the {@code PIC X(10)} field
         * width — total 10 characters.
         */
        public static final String DEFAULT_GROUP = "DEFAULT   ";

        /**
         * Zero-APR group key; interest calculation is skipped for accounts whose
         * disclosure-group lookup returns this row (every {@code DIS-INT-RATE} value
         * in {@code discgrp.txt} rows keyed by this group is zero). The literal
         * stored on disk is {@code "ZEROAPR"} (7 characters) plus 3 trailing spaces
         * to fill the {@code PIC X(10)} field width — total 10 characters.
         */
        public static final String ZEROAPR_GROUP = "ZEROAPR   ";
    }

    // ================================================================
    // Dates — date and JCL-PARM literal strings
    // ================================================================

    /**
     * Date and PARM constants for the batch jobs.
     *
     * <p>Values are intentionally kept as {@code String} (matching the COBOL
     * {@code PIC X(10)} field width where applicable) so this class can remain
     * import-free per the "pure constants" design directive (AAP §0.10.1). Callers
     * needing a typed value parse them at the call site with whichever
     * {@code java.time} type fits — {@code LocalDate.parse(...)},
     * {@code LocalDateTime.parse(...)}, or {@code Instant.parse(...)} as required.
     *
     * <p>Date values mirror the {@code REPT-START-DATE}/{@code REPT-END-DATE} captured
     * in {@code transaction_report.txt} (per {@code app/cpy/CVTRA07Y.cpy}) and the
     * INTCALC.jcl PARM string consumed by {@code CBACT04C}; changing these constants
     * requires re-capturing the corresponding baseline expected output files under
     * {@code src/test/resources/baseline/expected/}.
     */
    public static final class Dates {
        private Dates() {
            throw new UnsupportedOperationException(
                "Dates is a constants holder and cannot be instantiated.");
        }

        /**
         * {@code REPT-START-DATE} carried in the captured baseline
         * {@code transaction_report.txt} (per {@code CVTRA07Y.cpy} layout). 10-character
         * {@code YYYY-MM-DD} literal matching {@code PIC X(10)}.
         */
        public static final String REPORT_START_DATE = "2022-01-01";

        /**
         * {@code REPT-END-DATE} carried in the captured baseline
         * {@code transaction_report.txt}. 10-character {@code YYYY-MM-DD} literal.
         */
        public static final String REPORT_END_DATE = "2022-07-06";

        /**
         * INTCALC.jcl PARM string passed to {@code CBACT04C} at job-launch time;
         * becomes the 10-character prefix of every interest-transaction {@code TRAN-ID}
         * generated by paragraph {@code 1300-COMPUTE-INTEREST}. The remaining 6
         * characters of the 16-character {@code TRAN-ID} are a zero-padded sequential
         * counter starting at {@code "000001"}.
         */
        public static final String INTCALC_PARM = "2022071800";

        /**
         * Fixed clock instant for deterministic session-timestamp testing.
         *
         * <p>Per AAP §0.4.2 Blueprint A ({@code AuthenticationService}): "{@code Clock}
         * (fixed at {@code 2024-01-15T00:00:00Z} for deterministic session
         * timestamps)". Tests inject this value into the production class through a
         * {@code Clock.fixed(Instant.parse(FIXED_CLOCK_INSTANT), ZoneOffset.UTC)} bean
         * so {@code Instant.now(clock)} returns this exact value on every invocation.
         *
         * <p>Format: ISO-8601 instant ({@code yyyy-MM-ddTHH:mm:ssZ}) suitable for
         * {@link java.time.Instant#parse(CharSequence)}.
         */
        public static final String FIXED_CLOCK_INSTANT = "2024-01-15T00:00:00Z";
    }

    // ================================================================
    // Branding — CBSTM03A statement-header literal strings
    // ================================================================

    /**
     * Bank branding literals from {@code CBSTM03A} (statement generator).
     *
     * <p>These exact strings appear in the captured {@code statements_text.txt} and
     * {@code statements_html.txt} reference outputs (verbatim from
     * {@code app/cbl/CBSTM03A.CBL} lines 168–172). Migrated statement code must
     * produce the same literals for byte-identical baseline parity (AAP §0.10.4
     * "All financial calculation results MUST match COBOL baseline output exactly").
     *
     * <p>HTML output wraps each literal in a {@code <p>} element (and the bank-name in
     * a {@code <p style="font-size:16px">}); text output prints them un-wrapped.
     * Tests assert either form by composing the literal with the appropriate markup.
     */
    public static final class Branding {
        private Branding() {
            throw new UnsupportedOperationException(
                "Branding is a constants holder and cannot be instantiated.");
        }

        /** Bank name printed on every statement header — verbatim from CBSTM03A line 168. */
        public static final String BANK_NAME = "Bank of XYZ";
        /** Bank address line 1 — verbatim from CBSTM03A line 170. */
        public static final String BANK_ADDRESS_LINE_1 = "410 Terry Ave N";
        /** Bank address line 2 — verbatim from CBSTM03A line 172. */
        public static final String BANK_ADDRESS_LINE_2 = "Seattle WA 99999";
    }

    // ================================================================
    // SignOverpunch — COBOL signed-numeric overpunch character sentinels
    // ================================================================

    /**
     * Sign-overpunch character constants from COBOL signed numeric
     * ({@code Sn(...)V99}) fields.
     *
     * <p>The COBOL {@code S9(n)V99 USAGE DISPLAY} encoding stores the sign in the
     * rightmost digit via "overpunch": the digit character is replaced with a
     * specific letter or special character that encodes BOTH the digit value AND
     * the sign. This is the IBM EBCDIC convention preserved in the ASCII fixtures
     * under {@code app/data/ASCII/} (the EBCDIC-to-ASCII conversion maps the
     * overpunched bytes to the corresponding ASCII characters below).
     *
     * <p>Tests use these constants when asserting on the raw byte content of fixture
     * records (for example: the trailing character of a balance field in
     * {@code acctdata.txt} is {@code '{'} for {@code +0}, {@code 'A'} for {@code +1},
     * and so on).
     *
     * <p>Mapping table (positive sign uses {@code '{'} family; negative sign uses
     * {@code '}'} family):
     * <table>
     *   <caption>COBOL sign-overpunch character mapping (IBM EBCDIC → ASCII)</caption>
     *   <tr><th>Digit</th><th>Positive</th><th>Negative</th></tr>
     *   <tr><td>0</td><td>{@code '{'}</td><td>{@code '}'}</td></tr>
     *   <tr><td>1</td><td>{@code 'A'}</td><td>{@code 'J'}</td></tr>
     *   <tr><td>2</td><td>{@code 'B'}</td><td>{@code 'K'}</td></tr>
     *   <tr><td>3</td><td>{@code 'C'}</td><td>{@code 'L'}</td></tr>
     *   <tr><td>4</td><td>{@code 'D'}</td><td>{@code 'M'}</td></tr>
     *   <tr><td>5</td><td>{@code 'E'}</td><td>{@code 'N'}</td></tr>
     *   <tr><td>6</td><td>{@code 'F'}</td><td>{@code 'O'}</td></tr>
     *   <tr><td>7</td><td>{@code 'G'}</td><td>{@code 'P'}</td></tr>
     *   <tr><td>8</td><td>{@code 'H'}</td><td>{@code 'Q'}</td></tr>
     *   <tr><td>9</td><td>{@code 'I'}</td><td>{@code 'R'}</td></tr>
     * </table>
     */
    public static final class SignOverpunch {
        private SignOverpunch() {
            throw new UnsupportedOperationException(
                "SignOverpunch is a constants holder and cannot be instantiated.");
        }

        /** Overpunch sentinel for {@code +0} — open-brace ({@code 0x7B}). */
        public static final char POSITIVE_ZERO = '{';
        /** Overpunch sentinel for {@code +1} — uppercase A. */
        public static final char POSITIVE_ONE = 'A';
        /** Overpunch sentinel for {@code +2} — uppercase B. */
        public static final char POSITIVE_TWO = 'B';
        /** Overpunch sentinel for {@code +3} — uppercase C. */
        public static final char POSITIVE_THREE = 'C';
        /** Overpunch sentinel for {@code +4} — uppercase D. */
        public static final char POSITIVE_FOUR = 'D';
        /** Overpunch sentinel for {@code +5} — uppercase E. */
        public static final char POSITIVE_FIVE = 'E';
        /** Overpunch sentinel for {@code +6} — uppercase F. */
        public static final char POSITIVE_SIX = 'F';
        /** Overpunch sentinel for {@code +7} — uppercase G. */
        public static final char POSITIVE_SEVEN = 'G';
        /** Overpunch sentinel for {@code +8} — uppercase H. */
        public static final char POSITIVE_EIGHT = 'H';
        /** Overpunch sentinel for {@code +9} — uppercase I. */
        public static final char POSITIVE_NINE = 'I';

        /** Overpunch sentinel for {@code -0} — close-brace ({@code 0x7D}). */
        public static final char NEGATIVE_ZERO = '}';
        /** Overpunch sentinel for {@code -1} — uppercase J. */
        public static final char NEGATIVE_ONE = 'J';
        /** Overpunch sentinel for {@code -2} — uppercase K. */
        public static final char NEGATIVE_TWO = 'K';
        /** Overpunch sentinel for {@code -3} — uppercase L. */
        public static final char NEGATIVE_THREE = 'L';
        /** Overpunch sentinel for {@code -4} — uppercase M. */
        public static final char NEGATIVE_FOUR = 'M';
        /** Overpunch sentinel for {@code -5} — uppercase N. */
        public static final char NEGATIVE_FIVE = 'N';
        /** Overpunch sentinel for {@code -6} — uppercase O. */
        public static final char NEGATIVE_SIX = 'O';
        /** Overpunch sentinel for {@code -7} — uppercase P. */
        public static final char NEGATIVE_SEVEN = 'P';
        /** Overpunch sentinel for {@code -8} — uppercase Q. */
        public static final char NEGATIVE_EIGHT = 'Q';
        /** Overpunch sentinel for {@code -9} — uppercase R. */
        public static final char NEGATIVE_NINE = 'R';
    }

    // ================================================================
    // VsamStatus — VSAM file-status code sentinels for FileStatusMapper
    // ================================================================

    /**
     * VSAM file-status codes referenced across {@code CBACT01C}, {@code CBACT04C},
     * {@code CBTRN02C}, and the other batch programs.
     *
     * <p>These two-character codes are returned by VSAM I/O operations and stored in
     * the {@code FILE-STATUS} field of the program's working storage. The migrated
     * {@code com.aws.carddemo.io.FileStatusMapper} translates each code to a
     * specific Java exception subclass; the parameterised test
     * {@code FileStatusMapperTest} drives each code through the mapper and asserts
     * on the expected exception type.
     *
     * <p>Per AAP §0.10.4 (Immutable Boundaries): every documented VSAM status code
     * must have at least one mapping test row.
     */
    public static final class VsamStatus {
        private VsamStatus() {
            throw new UnsupportedOperationException(
                "VsamStatus is a constants holder and cannot be instantiated.");
        }

        /** {@code 00} — successful operation. */
        public static final String OK = "00";
        /** {@code 02} — duplicate key detected on write (KSDS unique-key violation). */
        public static final String DUPLICATE_KEY = "02";
        /** {@code 10} — end-of-file reached on sequential read. */
        public static final String EOF = "10";
        /** {@code 22} — sequence error on sequential write (out-of-order key). */
        public static final String SEQUENCE_ERROR = "22";
        /** {@code 23} — record not found on keyed read. */
        public static final String RECORD_NOT_FOUND = "23";
        /** {@code 35} — OPEN failed because the named file does not exist. */
        public static final String FILE_NOT_FOUND = "35";
        /** {@code 92} — logic error: operation conflicts with current file state. */
        public static final String LOGIC_ERROR = "92";
        /** {@code 97} — OPEN succeeded but VERIFY is required before any I/O. */
        public static final String OPEN_VERIFY_REQUIRED = "97";
    }

    // ================================================================
    // RecordWidths — fixed-width COBOL record lengths from app/cpy/
    // ================================================================

    /**
     * Fixed-width COBOL record lengths from the {@code app/cpy/} copybooks. Useful
     * for assertions on raw-record length (for example
     * {@code assertThat(record.length()).isEqualTo(TestFixtures.RecordWidths.ACCOUNT_RECLN)}).
     *
     * <p>Each constant matches the {@code RECLN} value documented in the
     * corresponding copybook header comment, which equals the sum of all
     * {@code PIC} clause widths in the copybook's {@code 01} record layout.
     */
    public static final class RecordWidths {
        private RecordWidths() {
            throw new UnsupportedOperationException(
                "RecordWidths is a constants holder and cannot be instantiated.");
        }

        /**
         * {@code ACCOUNT-RECORD} RECLN from {@code app/cpy/CVACT01Y.cpy} header
         * ({@code 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300}).
         */
        public static final int ACCOUNT_RECLN = 300;

        /**
         * {@code CARD-RECORD} RECLN from {@code app/cpy/CVACT02Y.cpy} header
         * ({@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150}).
         */
        public static final int CARD_RECLN = 150;

        /**
         * {@code CARD-XREF-RECORD} RECLN from {@code app/cpy/CVACT03Y.cpy} header
         * ({@code 16 + 9 + 11 + 14 = 50}).
         *
         * <p>The test fixture {@code src/test/resources/baseline/input/cardxref.txt}
         * carries records at this full 50-byte width: 16 bytes XREF-CARD-NUM
         * (Visa test PAN), 9 bytes XREF-CUST-ID, 11 bytes XREF-ACCT-ID, and a
         * 14-byte ASCII-space FILLER. The original ASCII source under
         * {@code app/data/ASCII/cardxref.txt} carries only the 36-byte payload
         * (without the trailing filler); the conversion that produces the test
         * fixture adds the filler so the on-disk record matches the copybook
         * declaration and this constant. See {@code docs/testing/test-strategy.md}
         * §11.2 for the documented conversion procedure.
         */
        public static final int CARD_XREF_RECLN = 50;

        /**
         * {@code CUSTOMER-RECORD} RECLN from {@code app/cpy/CUSTREC.cpy} header. The
         * sum of all visible {@code PIC} widths in the copybook plus the trailing
         * {@code PIC X(168)} filler equals 500.
         */
        public static final int CUSTOMER_RECLN = 500;

        /**
         * {@code TRAN-RECORD} RECLN from {@code app/cpy/CVTRA05Y.cpy} header
         * ({@code 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350}).
         */
        public static final int TRANSACTION_RECLN = 350;

        /**
         * Width in bytes of the {@code TRAN-ID} field within each
         * {@code TRAN-RECORD} per {@code app/cpy/CVTRA05Y.cpy}
         * ({@code PIC X(16)}, positions 1–16).
         *
         * <p>This is the sort key declared in {@code app/jcl/COMBTRAN.jcl}
         * SYMNAMES ({@code TRAN-ID,1,16,CH}) and used by every batch test
         * that needs to extract the sort key from a {@code TRAN-RECORD}
         * (e.g., {@code CombineTransactionsProcessorTest}). Exposing it
         * as a {@code RecordWidths} constant keeps the per-field widths
         * co-located with the per-record widths so test code has a
         * single source of truth.
         */
        public static final int TRAN_ID_WIDTH = 16;

        /**
         * {@code TRAN-CAT-BAL-RECORD} RECLN from {@code app/cpy/CVTRA01Y.cpy} header
         * ({@code 11 + 2 + 4 + 11 + 22 = 50}).
         */
        public static final int TCATBAL_RECLN = 50;

        /**
         * {@code SEC-USER-DATA} RECLN from {@code app/cpy/CSUSR01Y.cpy}
         * ({@code 8 + 20 + 20 + 8 + 1 + 23 = 80}).
         */
        public static final int USER_RECLN = 80;

        /**
         * {@code DISCGRP-RECORD} RECLN from the {@code FD-DISCGRP-REC} layout in
         * {@code app/cbl/CBACT04C.CBL} ({@code 10 + 2 + 4 + 34 = 50}).
         */
        public static final int DISCGRP_RECLN = 50;

        /**
         * {@code TRANCATG-RECORD} RECLN; the on-disk record length of
         * {@code app/data/ASCII/trancatg.txt} is 60 (one record per line including
         * the trailing newline byte the migration code strips on read).
         */
        public static final int TRANCATG_RECLN = 60;

        /**
         * {@code TRANTYPE-RECORD} RECLN; the on-disk record length of
         * {@code app/data/ASCII/trantype.txt} is 60 (one record per line including
         * the trailing newline byte the migration code strips on read).
         */
        public static final int TRANTYPE_RECLN = 60;
    }
}
