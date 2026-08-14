/*
 ******************************************************************************
 * Program     : PostingParityOracle.java
 * Application : CardDemo
 * Type        : JAVA (test support - parity oracle, not a suite)
 * Function    : Independent re-derivation of the POSTTRAN daily-posting outcome
 *               from app/cbl/CBTRN02C.cbl and the frozen app/data/ASCII
 *               fixtures, for Gate 1 boundary-parity comparison.
 * Derived from: app/cbl/CBTRN02C.cbl (731 lines), app/jcl/POSTTRAN.jcl,
 *               app/cpy/CVTRA06Y.cpy, app/cpy/CVTRA05Y.cpy,
 *               app/cpy/CVACT01Y.cpy, app/cpy/CVACT03Y.cpy,
 *               app/cpy/CVTRA01Y.cpy
 ******************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 ******************************************************************************/
package com.cardemo.e2e;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.cardemo.unit.model.FixtureLoader;

/**
 * The Gate 1 parity oracle: what {@code CBTRN02C} produces from the frozen fixtures, derived from the COBOL
 * rather than from the Java that replaced it.
 *
 * <h2>What it does</h2>
 *
 * <p>Replays {@code app/cbl/CBTRN02C.cbl} over {@code app/data/ASCII/dailytran.txt},
 * {@code app/data/ASCII/cardxref.txt}, {@code app/data/ASCII/acctdata.txt} and
 * {@code app/data/ASCII/tcatbal.txt} and returns the four outputs the program writes plus the two counters and
 * the return code it publishes. Paragraph by paragraph, in the order the source performs them, with each
 * private method citing the paragraph label and line range it re-derives.
 *
 * <h2>Why it exists, and why it is not circular</h2>
 *
 * <p>Gate 1 is boundary parity: the assertion that the Java implementation produces what the COBOL produced.
 * An expected value captured from the Java implementation's own output could only ever confirm the
 * implementation against itself, so it would prove nothing. This class is the other side of that comparison and
 * is <b>independent by construction, not by assertion</b>:
 *
 * <ul>
 *   <li><b>It imports no production class.</b> Not one type from {@code com.cardemo.batch},
 *       {@code com.cardemo.service}, {@code com.cardemo.model} or {@code com.cardemo.repository}. The only
 *       non-JDK import is {@link FixtureLoader}, a test-tree fixed-width reader with its own suite, so the two
 *       sides of the comparison cannot share a decoding bug and call it agreement.</li>
 *   <li><b>It touches no database, no object store and no Spring context.</b> Pure functions over four
 *       classpath fixtures. It can therefore be run and reviewed without the container tier.</li>
 *   <li><b>Every rule in it is traceable to a line of COBOL</b> that a reviewer can open in the frozen tree
 *       and check by eye. Where the source is surprising - the unguarded 102-then-103 sequence, the
 *       unnormalised cycle-debit accumulation, the not-found-is-success upsert - the surprise is reproduced
 *       and cited rather than tidied.</li>
 * </ul>
 *
 * <p><b>What it is not.</b> It is not a captured mainframe run. A real POSTTRAN execution against a real VSAM
 * cluster remains the strongest possible evidence and remains unavailable; that gap is stated in
 * {@code docs/validation-gates.md} rather than papered over. What this class does establish is that the
 * expected outcome <b>is derivable</b> from the frozen corpus.
 *
 * <h3>Why a stateless single-pass reading is not a second faithful model</h3>
 *
 * <p>One reading of Gate 1 holds that no expected total can be asserted at all, on the argument that "two
 * faithful models of {@code app/cbl/CBTRN02C.cbl} disagree over these fixtures, because {@code :L393-L395}
 * re-reads the account per transaction while {@code :L545-L560} mutates its accumulators, so any hand-derived
 * total is model-sensitive rather than an oracle." That argument does not hold.
 *
 * <p><b>The two models do not both exist.</b> {@code 2800-UPDATE-ACCOUNT-REC} at {@code :L561} ends in
 * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}, and a VSAM {@code REWRITE} replaces the record in the
 * cluster, so the next {@code READ} of that key at {@code :L394} returns the mutated accumulators.
 * {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L527} does the same for the category balance. The stateless
 * single-pass reading is therefore not a faithful model of this program at all - it is a misreading of what
 * {@code REWRITE} means. There is exactly one faithful model, the stateful one, and it is what this class
 * implements. Whether the Java tier agrees with it is a question of fact that Gate 1 now answers.
 *
 * <h2>How to run, build and test it</h2>
 *
 * <p>It is deliberately <b>not</b> a suite: the file name ends in {@code Oracle}, which neither the Surefire
 * nor the Failsafe include pattern matches, and it declares no test method, so
 * {@code TestTierContractTest.nonSuiteTypesDeclareNoTestMethod} passes rather than reporting hidden
 * assertions. It is exercised through its two callers:
 *
 * <pre>{@code
 * ./mvnw -B -ntp -Dit.test='GateVerificationTest#gateOne*' -DfailIfNoTests=false verify
 * ./mvnw -B -ntp -Dit.test='com.cardemo.e2e.BatchPipelineE2ETest' -DfailIfNoTests=false verify
 * }</pre>
 *
 * <p>The first needs no container. The second needs a Docker daemon.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None. There is no property, no profile and no environment variable: the answer is a function of four
 * frozen fixtures, so a configurable oracle would be a contradiction. The fixtures are resolved as bare
 * classpath resources from {@code target/test-classes}, which is where {@code src/test/resources} puts them.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>{@link IllegalStateException} naming a fixture and a column</b> - a fixture has been reflowed,
 *       re-signed or truncated, so a column span is reading the wrong bytes. The fixtures are frozen; the
 *       cause is a change to {@code src/test/resources}, not to this class.</li>
 *   <li><b>{@link IllegalStateException} reporting a duplicate key</b> - {@code cardxref.txt} or
 *       {@code acctdata.txt} holds two rows with one key. The oracle refuses to guess which wins, because
 *       VSAM would not have accepted the second.</li>
 *   <li><b>A golden mismatch in Gate 1</b> - the committed expectation and this class disagree. One of them
 *       changed. Re-read the cited paragraph before touching either.</li>
 *   <li><b>An execution mismatch in {@code BatchPipelineE2ETest}</b> - the Java implementation and the COBOL
 *       disagree. That is a parity defect in the implementation and the reason this class exists.</li>
 * </ul>
 */
public final class PostingParityOracle {

    // ====================================================================================================
    // Column spans. Every one is taken from the copybook PIC clause, because a span that is off by one reads
    // a neighbouring column as an overpunched sign and decodes a plausible wrong number.
    // ====================================================================================================

    /** {@code DALYTRAN-ID PIC X(16)}, {@code app/cpy/CVTRA06Y.cpy:L5}. */
    private static final int DALYTRAN_ID_START = 1;

    /** Width of {@code DALYTRAN-ID}. */
    private static final int DALYTRAN_ID_WIDTH = 16;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA06Y.cpy:L6}. */
    private static final int DALYTRAN_TYPE_CD_START = 17;

    /** Width of {@code DALYTRAN-TYPE-CD}. */
    private static final int DALYTRAN_TYPE_CD_WIDTH = 2;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)}, {@code app/cpy/CVTRA06Y.cpy:L7}. */
    private static final int DALYTRAN_CAT_CD_START = 19;

    /** Width of {@code DALYTRAN-CAT-CD}. */
    private static final int DALYTRAN_CAT_CD_WIDTH = 4;

    /** {@code DALYTRAN-SOURCE PIC X(10)}, {@code app/cpy/CVTRA06Y.cpy:L8}. */
    private static final int DALYTRAN_SOURCE_START = 23;

    /** Width of {@code DALYTRAN-SOURCE}. */
    private static final int DALYTRAN_SOURCE_WIDTH = 10;

    /** {@code DALYTRAN-DESC PIC X(100)}, {@code app/cpy/CVTRA06Y.cpy:L9}. */
    private static final int DALYTRAN_DESC_START = 33;

    /** Width of {@code DALYTRAN-DESC}. */
    private static final int DALYTRAN_DESC_WIDTH = 100;

    /** {@code DALYTRAN-AMT PIC S9(09)V99}, {@code app/cpy/CVTRA06Y.cpy:L10}. Eleven characters, not twelve. */
    private static final int DALYTRAN_AMT_START = 133;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)}, {@code app/cpy/CVTRA06Y.cpy:L11}. */
    private static final int DALYTRAN_MERCHANT_ID_START = 144;

    /** Width of {@code DALYTRAN-MERCHANT-ID}. */
    private static final int DALYTRAN_MERCHANT_ID_WIDTH = 9;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)}, {@code app/cpy/CVTRA06Y.cpy:L12}. */
    private static final int DALYTRAN_MERCHANT_NAME_START = 153;

    /** Width of the two fifty-character merchant text fields. */
    private static final int MERCHANT_TEXT_WIDTH = 50;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)}, {@code app/cpy/CVTRA06Y.cpy:L13}. */
    private static final int DALYTRAN_MERCHANT_CITY_START = 203;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}, {@code app/cpy/CVTRA06Y.cpy:L14}. */
    private static final int DALYTRAN_MERCHANT_ZIP_START = 253;

    /** Width of {@code DALYTRAN-MERCHANT-ZIP}. */
    private static final int DALYTRAN_MERCHANT_ZIP_WIDTH = 10;

    /** {@code DALYTRAN-CARD-NUM PIC X(16)}, {@code app/cpy/CVTRA06Y.cpy:L15}. */
    private static final int DALYTRAN_CARD_NUM_START = 263;

    /** Width of a card number, in the staging record and the cross reference alike. */
    private static final int CARD_NUM_WIDTH = 16;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)}, {@code app/cpy/CVTRA06Y.cpy:L16}. */
    private static final int DALYTRAN_ORIG_TS_START = 279;

    /** Width of a timestamp field. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** {@code XREF-CARD-NUM PIC X(16)}, {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int XREF_CARD_NUM_START = 1;

    /** {@code XREF-ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int XREF_ACCT_ID_START = 26;

    /** Width of an account identifier. */
    private static final int ACCT_ID_WIDTH = 11;

    /** {@code ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT01Y.cpy:L5}. */
    private static final int ACCT_ID_START = 1;

    /** {@code ACCT-CURR-BAL PIC S9(10)V99}, {@code app/cpy/CVACT01Y.cpy:L7}. */
    private static final int ACCT_CURR_BAL_START = 13;

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}, {@code app/cpy/CVACT01Y.cpy:L8}. */
    private static final int ACCT_CREDIT_LIMIT_START = 25;

    /** {@code ACCT-EXPIRAION-DATE PIC X(10)}, {@code app/cpy/CVACT01Y.cpy:L11}. Misspelled in the source. */
    private static final int ACCT_EXPIRAION_DATE_START = 59;

    /** Width of the expiry date, and of the {@code DALYTRAN-ORIG-TS} prefix it is compared against. */
    private static final int EXPIRY_WIDTH = 10;

    /** {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}, {@code app/cpy/CVACT01Y.cpy:L13}. */
    private static final int ACCT_CURR_CYC_CREDIT_START = 79;

    /** {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}, {@code app/cpy/CVACT01Y.cpy:L14}. */
    private static final int ACCT_CURR_CYC_DEBIT_START = 91;

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}, {@code app/cpy/CVTRA01Y.cpy:L6}. */
    private static final int TCATBAL_ACCT_ID_START = 1;

    /** {@code TRANCAT-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA01Y.cpy:L7}. */
    private static final int TCATBAL_TYPE_CD_START = 12;

    /** {@code TRANCAT-CD PIC 9(04)}, {@code app/cpy/CVTRA01Y.cpy:L8}. */
    private static final int TCATBAL_CAT_CD_START = 14;

    /** {@code TRAN-CAT-BAL PIC S9(09)V99}, {@code app/cpy/CVTRA01Y.cpy:L9}. Eleven characters. */
    private static final int TCATBAL_BALANCE_START = 18;

    // ====================================================================================================
    // Reject-record geometry and the five literals. All from CBTRN02C's own DATA DIVISION and paragraphs.
    // ====================================================================================================

    /** {@code REJECT-TRAN-DATA PIC X(350)}, {@code app/cbl/CBTRN02C.cbl:L177}. */
    private static final int REJECT_DATA_WIDTH = 350;

    /** {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}, {@code app/cbl/CBTRN02C.cbl:L181}. */
    private static final int REJECT_REASON_WIDTH = 4;

    /** {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}, {@code app/cbl/CBTRN02C.cbl:L182}. */
    private static final int REJECT_DESC_WIDTH = 76;

    /**
     * Total reject-record width, {@value}.
     *
     * <p>{@code REJECT-TRAN-DATA PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:L176-L178}, independently confirmed by the {@code LRECL=430} on the
     * {@code DALYREJS} DD in {@code app/jcl/POSTTRAN.jcl}.
     */
    public static final int REJECT_RECORD_WIDTH = REJECT_DATA_WIDTH + REJECT_REASON_WIDTH + REJECT_DESC_WIDTH;

    /** {@code MOVE 100} at {@code app/cbl/CBTRN02C.cbl:L385}. */
    private static final int REASON_INVALID_CARD = 100;

    /** {@code MOVE 101} at {@code app/cbl/CBTRN02C.cbl:L397}. */
    private static final int REASON_ACCOUNT_NOT_FOUND = 101;

    /** {@code MOVE 102} at {@code app/cbl/CBTRN02C.cbl:L410}. */
    private static final int REASON_OVERLIMIT = 102;

    /** {@code MOVE 103} at {@code app/cbl/CBTRN02C.cbl:L418}. */
    private static final int REASON_AFTER_EXPIRATION = 103;

    /** The pass-through reason, {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code :L209}. */
    private static final int REASON_NONE = 0;

    /** {@code 'INVALID CARD NUMBER FOUND'}, {@code app/cbl/CBTRN02C.cbl:L386}. */
    private static final String DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** {@code 'ACCOUNT RECORD NOT FOUND'}, {@code app/cbl/CBTRN02C.cbl:L398}. */
    private static final String DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** {@code 'OVERLIMIT TRANSACTION'}, {@code app/cbl/CBTRN02C.cbl:L411}. */
    private static final String DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /** {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}, {@code app/cbl/CBTRN02C.cbl:L419}. */
    private static final String DESC_AFTER_EXPIRATION = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** {@code MOVE 4 TO RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L230}. */
    private static final int RETURN_CODE_COMPLETED_WITH_REJECTS = 4;

    /** The fall-through return code at {@code app/cbl/CBTRN02C.cbl:L229}. */
    private static final int RETURN_CODE_COMPLETED = 0;

    /** Implied decimal places of every {@code V99} clause in these copybooks. */
    private static final int MONEY_SCALE = 2;

    /**
     * Field and terminator character in the committed expectation, {@value}.
     *
     * <p>Safe as a separator because no fixture contains it: a scan of all nine files under
     * {@code app/data/ASCII} finds zero occurrences, so no {@code DALYTRAN-DESC},
     * {@code DALYTRAN-MERCHANT-NAME} or {@code DALYTRAN-MERCHANT-CITY} value can be mistaken for a boundary.
     */
    public static final String FIELD_SEPARATOR = "|";

    /**
     * Repository-relative directory holding the committed expectation.
     *
     * <p>Under {@code src}, not {@code target}: the expectation is reviewable evidence that travels with the
     * repository, not build output. Both suites that read it pin {@code workingDirectory} to
     * {@code ${project.basedir}} in {@code pom.xml}, so this relative path resolves identically in each.
     */
    public static final String EXPECTATION_DIRECTORY = "src/test/resources/expected/posttran";

    /**
     * The six files of the committed expectation, one per output the run writes.
     *
     * <p>Six files rather than one because they are six different datasets in the source -
     * {@code TRANSACT}, {@code ACCTDATA}, {@code TCATBALF} and {@code DALYREJS}, plus the counters and the
     * created-key ledger - and a reviewer checking one against {@code app/cbl/CBTRN02C.cbl} should not have to
     * read past the other five.
     */
    public static final List<String> EXPECTATION_FILES = List.of(
            "counters.txt",
            "transactions.txt",
            "accounts.txt",
            "category-balances.txt",
            "created-category-balances.txt",
            "rejects.txt");

    /** Not instantiable: every entry point is static. */
    private PostingParityOracle() {
        throw new AssertionError("PostingParityOracle is a static oracle and is never instantiated");
    }

    // ====================================================================================================
    // The result shape. Records, so a caller cannot mutate an oracle answer after reading it.
    // ====================================================================================================

    /**
     * One row {@code 2900-WRITE-TRANSACTION-FILE} writes, as {@code 2000-POST-TRANSACTION} built it.
     *
     * <p>Twelve of the fourteen {@code CVTRA05Y} fields, in copybook order. {@code TRAN-PROC-TS} is
     * deliberately absent: {@code app/cbl/CBTRN02C.cbl:L438-L439} fills it from
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}, which reads the clock, so it is a property of <em>when</em> the run
     * happened rather than of what the run computed. Its width and its four trailing zeros are asserted
     * separately against the fixed test clock; asserting a clock reading here would make the oracle
     * time-dependent and so useless as an expectation.
     *
     * @param tranId {@code TRAN-ID}, from {@code DALYTRAN-ID}, {@code :L426}
     * @param typeCode {@code TRAN-TYPE-CD}, {@code :L427}
     * @param categoryCode {@code TRAN-CAT-CD}, {@code :L428}
     * @param source {@code TRAN-SOURCE}, {@code :L429}
     * @param description {@code TRAN-DESC}, {@code :L430}
     * @param amount {@code TRAN-AMT}, {@code :L431}
     * @param merchantId {@code TRAN-MERCHANT-ID}, {@code :L432}
     * @param merchantName {@code TRAN-MERCHANT-NAME}, {@code :L433}
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, {@code :L434}
     * @param merchantZip {@code TRAN-MERCHANT-ZIP}, {@code :L435}
     * @param cardNumber {@code TRAN-CARD-NUM}, {@code :L436}
     * @param originTimestamp {@code TRAN-ORIG-TS}, {@code :L437}
     */
    public record PostedTransaction(
            String tranId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originTimestamp) {
    }

    /**
     * The three {@code CVACT01Y} accumulators {@code 2800-UPDATE-ACCOUNT-REC} mutates, after the last update.
     *
     * @param currentBalance {@code ACCT-CURR-BAL} after {@code :L546}
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT} after {@code :L548}
     * @param cycleDebit {@code ACCT-CURR-CYC-DEBIT} after {@code :L550}, which holds negative values
     */
    public record AccountAccumulators(
            BigDecimal currentBalance,
            BigDecimal cycleCredit,
            BigDecimal cycleDebit) {
    }

    /**
     * The {@code CVTRA01Y} composite key, in copybook field order.
     *
     * @param accountId {@code TRANCAT-ACCT-ID PIC 9(11)}
     * @param typeCode {@code TRANCAT-TYPE-CD PIC X(02)}
     * @param categoryCode {@code TRANCAT-CD PIC 9(04)}
     */
    public record CategoryBalanceKey(String accountId, String typeCode, String categoryCode)
            implements Comparable<CategoryBalanceKey> {

        /**
         * Renders the key as {@code acct|type|cat}, the form both callers compare on.
         *
         * <p>Inputs: none. Output: the rendered key. Side effects: none. Never fails.
         *
         * @return the rendered key, never {@code null}
         */
        public String rendered() {
            return accountId + FIELD_SEPARATOR + typeCode + FIELD_SEPARATOR + categoryCode;
        }

        /**
         * Orders keys by the rendered form, so a golden file has one deterministic layout.
         *
         * <p>This is a presentation order for the committed expectation. It is <b>not</b> a claim about VSAM
         * key order: the seventeen-byte concatenation the cluster orders on is
         * {@code acct(11) + type(2) + cat(4)} with no separator, which the oracle never needs to reproduce
         * because it holds the balances in a keyed map rather than a browse.
         *
         * @param other the key to compare against; must not be {@code null}
         * @return the sign of the rendered-form comparison
         */
        @Override
        public int compareTo(final CategoryBalanceKey other) {
            return rendered().compareTo(other.rendered());
        }
    }

    /**
     * One 430-byte record {@code 2500-WRITE-REJECT-REC} writes, with its parts kept separable.
     *
     * @param stagingImage the 350-character {@code DALYTRAN-RECORD} copy, {@code :L502}
     * @param reasonCode the numeric reason, before zero padding
     * @param description the reason description, before space padding
     * @param record the assembled 430-character record, exactly as the DD receives it
     */
    public record RejectRecord(
            String stagingImage,
            int reasonCode,
            String description,
            String record) {
    }

    /**
     * Everything one POSTTRAN run produces, in the four places it produces it.
     *
     * @param postedTransactions rows written to {@code TRANSACT}, in emission order
     * @param accountAccumulators final {@code ACCTDATA} accumulators, keyed by account identifier, ordered by
     *     key so the golden layout is deterministic
     * @param categoryBalances final {@code TCATBALF} balances, ordered by key, including rows the run created
     * @param rejectRecords 430-byte {@code DALYREJS} records, in emission order
     * @param processedCount {@code WS-TRANSACTION-COUNT} at {@code :L207}
     * @param rejectCount {@code WS-REJECT-COUNT} at {@code :L215}
     * @param returnCode 4 when {@code rejectCount} exceeded zero, else 0, per {@code :L229-L231}
     * @param createdCategoryBalanceKeys keys the run created through {@code 2700-A-CREATE-TCATBAL-REC},
     *     ordered by key
     */
    public record Result(
            List<PostedTransaction> postedTransactions,
            Map<String, AccountAccumulators> accountAccumulators,
            Map<CategoryBalanceKey, BigDecimal> categoryBalances,
            List<RejectRecord> rejectRecords,
            long processedCount,
            long rejectCount,
            int returnCode,
            List<CategoryBalanceKey> createdCategoryBalanceKeys) {
    }

    // ====================================================================================================
    // Mutable replay state. Package private so nothing outside this file can reach into a partial replay.
    // ====================================================================================================

    /** One account's mutable {@code ACCOUNT-RECORD} image, as the cluster holds it between reads. */
    private static final class AccountImage {

        /** {@code ACCT-CURR-BAL}. */
        private BigDecimal currentBalance;

        /** {@code ACCT-CREDIT-LIMIT}; never mutated by this program. */
        private final BigDecimal creditLimit;

        /** {@code ACCT-EXPIRAION-DATE}; never mutated by this program. */
        private final String expiryDate;

        /** {@code ACCT-CURR-CYC-CREDIT}. */
        private BigDecimal cycleCredit;

        /** {@code ACCT-CURR-CYC-DEBIT}. */
        private BigDecimal cycleDebit;

        /**
         * Seeds one image from its {@code acctdata.txt} row.
         *
         * @param currentBalance seeded {@code ACCT-CURR-BAL}
         * @param creditLimit seeded {@code ACCT-CREDIT-LIMIT}
         * @param expiryDate seeded {@code ACCT-EXPIRAION-DATE}
         * @param cycleCredit seeded {@code ACCT-CURR-CYC-CREDIT}
         * @param cycleDebit seeded {@code ACCT-CURR-CYC-DEBIT}
         */
        AccountImage(final BigDecimal currentBalance, final BigDecimal creditLimit, final String expiryDate,
                final BigDecimal cycleCredit, final BigDecimal cycleDebit) {
            this.currentBalance = currentBalance;
            this.creditLimit = creditLimit;
            this.expiryDate = expiryDate;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }
    }

    /**
     * One staging record's decoded fields, read once per record as {@code 1000-DALYTRAN-GET-NEXT} does.
     * @param rawRecord the 350-byte staging image, kept verbatim for the reject trailer.
     * @param tranId {@code DALYTRAN-ID}, sixteen characters.
     * @param typeCode {@code DALYTRAN-TYPE-CD}, two characters.
     * @param categoryCode {@code DALYTRAN-CAT-CD}, four characters.
     * @param source {@code DALYTRAN-SOURCE}, ten characters.
     * @param description {@code DALYTRAN-DESC}, one hundred characters.
     * @param amount {@code DALYTRAN-AMT}, decoded from its overpunch sign.
     * @param merchantId {@code DALYTRAN-MERCHANT-ID}, nine characters.
     * @param merchantName {@code DALYTRAN-MERCHANT-NAME}, fifty characters.
     * @param merchantCity {@code DALYTRAN-MERCHANT-CITY}, fifty characters.
     * @param merchantZip {@code DALYTRAN-MERCHANT-ZIP}, ten characters.
     * @param cardNumber {@code DALYTRAN-CARD-NUM}, sixteen characters.
     * @param originTimestamp {@code DALYTRAN-ORIG-TS}, twenty-six characters.
     */
    private record StagingRecord(
            String rawRecord,
            String tranId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originTimestamp) {
    }

    // ====================================================================================================
    // Entry point.
    // ====================================================================================================

    /**
     * Replays {@code CBTRN02C} over the frozen fixtures and returns everything it produces.
     *
     * <p><b>Inputs.</b> None. The four fixtures are resolved from the classpath by {@link FixtureLoader}, so
     * the answer is a function of the frozen corpus and nothing else - not of a database, not of a profile,
     * not of a clock.
     *
     * <p><b>Output.</b> An immutable {@link Result}. Map and list ordering is deterministic: the two maps are
     * key ordered so a serialised golden has one layout, and the two lists are in emission order because the
     * order rejects are written in is itself part of the contract.
     *
     * <p><b>Side effects.</b> None. Four classpath reads; nothing is written, cached in static state or
     * mutated outside the call.
     *
     * <p><b>Error modes.</b> {@link IllegalStateException} when a fixture is absent, malformed, of the wrong
     * geometry, or holds a duplicate key. It never returns a partial answer: a fixture problem makes every
     * downstream value meaningless, so it stops rather than reporting a misleading expectation.
     *
     * @return the expected POSTTRAN outcome, never {@code null}
     * @throws IllegalStateException if any fixture is unusable, naming the fixture and the column
     */
    public static Result replay() {
        final Map<String, String> accountIdByCardNumber = loadCrossReference();
        final Map<String, AccountImage> accountsById = loadAccounts();
        final Map<CategoryBalanceKey, BigDecimal> categoryBalances = loadCategoryBalances();
        final List<StagingRecord> staged = loadStagingRecords();

        final List<PostedTransaction> posted = new ArrayList<>();
        final List<RejectRecord> rejected = new ArrayList<>();
        final List<CategoryBalanceKey> created = new ArrayList<>();
        long processedCount = 0L;
        long rejectCount = 0L;

        // app/cbl/CBTRN02C.cbl:L204-L221 - PERFORM UNTIL END-OF-FILE = 'Y'. One pass, in fixture order,
        // which is the sequential order the DALYTRAN DD presents.
        for (final StagingRecord record : staged) {
            processedCount++;

            // :L209-L210 - the reason and its description are reset per record, before validation. This is
            // why reject code 109, assigned inside the posting path at :L559, never survives to a reject
            // record: the next iteration clears it.
            int reasonCode = REASON_NONE;
            String description = "";

            // :L211 PERFORM 1500-VALIDATE-TRAN
            final Validation validation = validateTransaction(record, accountIdByCardNumber, accountsById);
            reasonCode = validation.reasonCode();
            description = validation.description();

            if (reasonCode == REASON_NONE) {
                // :L212-L213 PERFORM 2000-POST-TRANSACTION
                posted.add(postTransaction(record));
                final String accountId = accountIdByCardNumber.get(record.cardNumber());
                created.addAll(updateCategoryBalance(record, accountId, categoryBalances));
                updateAccountRecord(record, accountsById.get(accountId));
            } else {
                // :L214-L216 - ADD 1 TO WS-REJECT-COUNT then PERFORM 2500-WRITE-REJECT-REC, in that order.
                rejectCount++;
                rejected.add(writeRejectRecord(record, reasonCode, description));
            }
        }

        return new Result(
                List.copyOf(posted),
                finalAccountAccumulators(accountsById),
                new TreeMap<>(categoryBalances),
                List.copyOf(rejected),
                processedCount,
                rejectCount,
                // :L229-L231 - IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE. Nothing else sets it.
                rejectCount > 0L ? RETURN_CODE_COMPLETED_WITH_REJECTS : RETURN_CODE_COMPLETED,
                created.stream().sorted().toList());
    }

    // ====================================================================================================
    // The committed expectation: how it is rendered, and how it is read back. Both callers share these so
    // the format is defined in exactly one place and the two sides cannot drift into different spellings of
    // the same value.
    // ====================================================================================================

    /**
     * Renders the whole expectation, file by file, in the exact form the committed files hold.
     *
     * <p><b>Why the rendering lives here rather than in either caller.</b> Two suites compare against these
     * files - one checking the oracle against them, one checking a real run against them - and if each
     * rendered rows its own way, a disagreement about padding or scale would present as a parity failure. One
     * renderer means a formatting difference is impossible by construction, so any failure is a real
     * difference in the values.
     *
     * <p><b>Every line ends in a {@value #FIELD_SEPARATOR}.</b> That is not decoration. {@code description} is
     * space padded to 100 characters and {@code merchantName} to 50, so without a terminator those pad spaces
     * would sit at end of line where a whitespace-trimming tool would silently eat them and the comparison
     * would then fail for a reason that has nothing to do with the batch logic. {@code rejects.txt} is the
     * deliberate exception: its rows are the raw 430-byte records with nothing appended, because byte fidelity
     * against a real {@code DALYREJS} dataset is the entire point of that file. Its trailing spaces are
     * protected instead by {@code .gitattributes} and {@code .editorconfig}, and a stripped row fails the
     * width assertion in Gate 1 with an explanation.
     *
     * <p>Inputs: the replayed result. Output: file name to rows, in {@link #EXPECTATION_FILES} order. Side
     * effects: none.
     *
     * @param result the result to render; must not be {@code null}
     * @return the rendered expectation, never {@code null}
     */
    public static Map<String, List<String>> renderExpectation(final Result result) {
        Objects.requireNonNull(result, "result must not be null");

        final Map<String, List<String>> rendered = new LinkedHashMap<>();
        rendered.put("counters.txt", List.of(
                "processedCount=" + result.processedCount(),
                "rejectCount=" + result.rejectCount(),
                "returnCode=" + result.returnCode(),
                "postedTransactionCount=" + result.postedTransactions().size(),
                "accountCount=" + result.accountAccumulators().size(),
                "categoryBalanceCount=" + result.categoryBalances().size(),
                "createdCategoryBalanceCount=" + result.createdCategoryBalanceKeys().size(),
                "rejectRecordWidth=" + REJECT_RECORD_WIDTH));
        rendered.put("transactions.txt", result.postedTransactions().stream()
                .map(PostingParityOracle::renderTransaction)
                .toList());
        rendered.put("accounts.txt", result.accountAccumulators().entrySet().stream()
                .map(entry -> renderAccount(entry.getKey(), entry.getValue()))
                .sorted()
                .toList());
        rendered.put("category-balances.txt", result.categoryBalances().entrySet().stream()
                .map(entry -> renderCategoryBalance(entry.getKey(), entry.getValue()))
                .toList());
        rendered.put("created-category-balances.txt", result.createdCategoryBalanceKeys().stream()
                .map(key -> key.rendered() + FIELD_SEPARATOR)
                .toList());
        rendered.put("rejects.txt", result.rejectRecords().stream()
                .map(RejectRecord::record)
                .toList());
        // Collections.unmodifiableMap over the LinkedHashMap, deliberately, rather than Map.copyOf: the
        // latter returns an UNORDERED immutable map, which would make the returned key set arrive in an
        // arbitrary order and defeat the caller's check that it is exactly EXPECTATION_FILES.
        return Collections.unmodifiableMap(rendered);
    }

    /**
     * Renders one posted transaction as a committed-expectation row.
     *
     * <p>Twelve fields in {@code CVTRA05Y} order. Text fields carry their {@code PIC X(n)} padding verbatim,
     * which is what makes a comparison against a {@code CHAR(n)} column a byte comparison rather than a
     * trimmed one; the amount is rendered by {@link BigDecimal#toPlainString()} at its own scale, so a scale
     * drift shows as a difference rather than being normalised away.
     *
     * <p>Inputs: the transaction. Output: the row. Side effects: none.
     *
     * @param transaction the transaction to render; must not be {@code null}
     * @return the rendered row, never {@code null}
     */
    public static String renderTransaction(final PostedTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return String.join(FIELD_SEPARATOR,
                transaction.tranId(),
                transaction.typeCode(),
                transaction.categoryCode(),
                transaction.source(),
                transaction.amount().toPlainString(),
                transaction.merchantId(),
                transaction.merchantName(),
                transaction.merchantCity(),
                transaction.merchantZip(),
                transaction.cardNumber(),
                transaction.originTimestamp(),
                transaction.description()) + FIELD_SEPARATOR;
    }

    /**
     * Renders one account's accumulators as a committed-expectation row.
     *
     * <p>Inputs: the eleven-character account identifier and its accumulators. Output: the row. Side effects:
     * none.
     *
     * @param accountId the account identifier, already at its {@code PIC 9(11)} width
     * @param accumulators the accumulators; must not be {@code null}
     * @return the rendered row, never {@code null}
     */
    public static String renderAccount(final String accountId, final AccountAccumulators accumulators) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accumulators, "accumulators must not be null");
        return String.join(FIELD_SEPARATOR,
                accountId,
                accumulators.currentBalance().toPlainString(),
                accumulators.cycleCredit().toPlainString(),
                accumulators.cycleDebit().toPlainString()) + FIELD_SEPARATOR;
    }

    /**
     * Renders one category balance as a committed-expectation row.
     *
     * <p>Inputs: the composite key and its balance. Output: the row. Side effects: none.
     *
     * @param key the composite key; must not be {@code null}
     * @param balance the balance; must not be {@code null}
     * @return the rendered row, never {@code null}
     */
    public static String renderCategoryBalance(final CategoryBalanceKey key, final BigDecimal balance) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(balance, "balance must not be null");
        return key.rendered() + FIELD_SEPARATOR + balance.toPlainString() + FIELD_SEPARATOR;
    }

    /**
     * Reads one committed expectation file as its rows, preserving every space.
     *
     * <p>Decoded through {@link StandardCharsets#ISO_8859_1} so one byte maps to one character and a measured
     * character width is a measured <em>byte</em> width - which is what makes the 430-character assertion on
     * {@code rejects.txt} a byte-level assertion. Split with a negative limit rather than through
     * {@link String#lines()} so that neither a trailing empty row nor a genuinely blank row is invented or
     * dropped.
     *
     * <p>Inputs: the bare file name within {@link #EXPECTATION_DIRECTORY}. Output: the rows in file order, or
     * an empty list for an empty file. Side effects: one file read.
     *
     * @param fileName the bare file name; must not be {@code null}
     * @return the rows, never {@code null}
     * @throws IllegalStateException if the file cannot be read, naming it. It never returns an empty list for
     *     an unreadable file: an empty expectation compares equal to an empty run, so a read failure that
     *     presented as emptiness would turn the gate vacuous - exactly the condition it exists to end
     */
    public static List<String> readCommittedExpectation(final String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        final Path file = Path.of(EXPECTATION_DIRECTORY, fileName);
        final String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("The committed Gate 1 expectation " + EXPECTATION_DIRECTORY + "/"
                    + fileName + " could not be read. It is resolved relative to the working directory, which "
                    + "pom.xml pins to ${project.basedir} for both test plugins. Gate 1 fails closed rather "
                    + "than treating an unreadable expectation as an empty one.", unreadable);
        }
        if (content.isEmpty()) {
            return List.of();
        }
        final String withoutFinalNewline =
                content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        return List.of(withoutFinalNewline.split("\n", -1));
    }

    // ====================================================================================================
    // Paragraph re-derivations. One private method per COBOL paragraph, in source order.
    // ====================================================================================================

    /**
     * The outcome of {@code 1500-VALIDATE-TRAN}: a reason code and its description.
     * @param reasonCode the reason code, zero when the record validates.
     * @param description the reason description, blank when the record validates.
     */
    private record Validation(int reasonCode, String description) {
    }

    /**
     * {@code 1500-VALIDATE-TRAN}, {@code app/cbl/CBTRN02C.cbl:L370-L378}.
     *
     * <p>Performs the cross-reference lookup, and performs the account lookup <b>only</b> when the reason is
     * still zero. Two lookup paragraphs, not four: the source's trailing
     * {@code * ADD MORE VALIDATIONS HERE} comment at {@code :L377} is an invitation that was never taken up,
     * and inventing a third validation here would be a behaviour change.
     *
     * <p>Inputs: the staging record and the two keyed images. Output: the reason and description. Side
     * effects: none - validation reads, it never mutates.
     *
     * @param record the staging record under validation
     * @param accountIdByCardNumber the {@code XREF-FILE} image
     * @param accountsById the mutable {@code ACCOUNT-FILE} image
     * @return the validation outcome, never {@code null}
     */
    private static Validation validateTransaction(final StagingRecord record,
            final Map<String, String> accountIdByCardNumber, final Map<String, AccountImage> accountsById) {

        final Validation crossReference = lookupCrossReference(record, accountIdByCardNumber);
        if (crossReference.reasonCode() != REASON_NONE) {
            return crossReference;
        }
        return lookupAccount(record, accountIdByCardNumber.get(record.cardNumber()), accountsById);
    }

    /**
     * {@code 1500-A-LOOKUP-XREF}, {@code app/cbl/CBTRN02C.cbl:L380-L392}.
     *
     * <p>A keyed read of {@code XREF-FILE} on {@code DALYTRAN-CARD-NUM}. {@code INVALID KEY} sets reason 100
     * with its literal; {@code NOT INVALID KEY} is an explicit {@code CONTINUE}.
     *
     * <p>Inputs: the staging record and the cross-reference image. Output: reason 100 or zero. Side effects:
     * none.
     *
     * @param record the staging record
     * @param accountIdByCardNumber the cross-reference image
     * @return reason 100 with its description on a miss, or a zero reason on a hit
     */
    private static Validation lookupCrossReference(final StagingRecord record,
            final Map<String, String> accountIdByCardNumber) {

        if (accountIdByCardNumber.containsKey(record.cardNumber())) {
            return new Validation(REASON_NONE, "");
        }
        return new Validation(REASON_INVALID_CARD, DESC_INVALID_CARD);
    }

    /**
     * {@code 1500-B-LOOKUP-ACCT}, {@code app/cbl/CBTRN02C.cbl:L393-L422}.
     *
     * <p><b>Three things here are surprising and all three are reproduced rather than corrected.</b>
     *
     * <ol>
     *   <li><b>The over-limit and expiry tests are sequential and unguarded.</b> {@code :L407-L414} sets 102
     *       and {@code :L415-L421} then runs regardless, so when both conditions fail <b>103 overwrites
     *       102</b> and exactly one reject record bearing 103 is written. There is no {@code ELSE} between
     *       them and no early {@code EXIT}. Guarding the second test, or emitting two rejects, would diverge.
     *   </li>
     *   <li><b>The over-limit expression is transcribed, not simplified.</b>
     *       {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} at
     *       {@code :L404-L406}. Subtracting the debit accumulator is correct precisely because
     *       {@code 2800-UPDATE-ACCOUNT-REC} accumulates negative amounts into it unnormalised; an algebraic
     *       rewrite that "fixed" the sign would change the result.</li>
     *   <li><b>The expiry test is an alphanumeric comparison against the originating timestamp.</b>
     *       {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} at {@code :L415} - the first ten
     *       characters of {@code DALYTRAN-ORIG-TS}, never {@code DALYTRAN-PROC-TS}, and compared as text
     *       rather than parsed as a date. The field name's misspelling is part of the contract.</li>
     * </ol>
     *
     * <p>Inputs: the staging record, the account identifier the cross reference yielded, and the mutable
     * account image. Output: reason 101, 102, 103 or zero. Side effects: none; the accumulators it reads were
     * mutated by earlier records, which is the whole point, but this method mutates nothing itself.
     *
     * @param record the staging record
     * @param accountId the account identifier from the cross reference
     * @param accountsById the mutable account image
     * @return the reason and description
     */
    private static Validation lookupAccount(final StagingRecord record, final String accountId,
            final Map<String, AccountImage> accountsById) {

        final AccountImage account = accountsById.get(accountId);
        if (account == null) {
            return new Validation(REASON_ACCOUNT_NOT_FOUND, DESC_ACCOUNT_NOT_FOUND);
        }

        int reasonCode = REASON_NONE;
        String description = "";

        final BigDecimal temporaryBalance = account.cycleCredit
                .subtract(account.cycleDebit)
                .add(record.amount());
        if (account.creditLimit.compareTo(temporaryBalance) < 0) {
            reasonCode = REASON_OVERLIMIT;
            description = DESC_OVERLIMIT;
        }

        // Unguarded on purpose. See the second point in this method's documentation.
        final String originDatePrefix = record.originTimestamp().substring(0, EXPIRY_WIDTH);
        if (account.expiryDate.compareTo(originDatePrefix) < 0) {
            reasonCode = REASON_AFTER_EXPIRATION;
            description = DESC_AFTER_EXPIRATION;
        }

        return new Validation(reasonCode, description);
    }

    /**
     * {@code 2000-POST-TRANSACTION}, the thirteen moves at {@code app/cbl/CBTRN02C.cbl:L425-L439}.
     *
     * <p>Twelve fields copied straight across, in copybook order. The thirteenth,
     * {@code TRAN-PROC-TS}, is a clock reading and is excluded for the reason given on
     * {@link PostedTransaction}.
     *
     * <p>Inputs: the staging record. Output: the transaction image. Side effects: none.
     *
     * @param record the staging record
     * @return the posted transaction image, never {@code null}
     */
    private static PostedTransaction postTransaction(final StagingRecord record) {
        return new PostedTransaction(
                record.tranId(),
                record.typeCode(),
                record.categoryCode(),
                record.source(),
                record.description(),
                record.amount(),
                record.merchantId(),
                record.merchantName(),
                record.merchantCity(),
                record.merchantZip(),
                record.cardNumber(),
                record.originTimestamp());
    }

    /**
     * {@code 2700-UPDATE-TCATBAL} and its two branches, {@code app/cbl/CBTRN02C.cbl:L467-L541}.
     *
     * <p><b>A not-found status is success here, not an error.</b> {@code :L480-L487} accepts <b>either</b>
     * {@code '00'} <b>or</b> {@code '23'} before dispatching, so an absent category balance is a create path
     * rather than an abend. This is one of only three sites in the corpus where that is true, and a blanket
     * not-found-is-an-exception rule would abend the run here.
     *
     * <p>Both branches do the same arithmetic - {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at {@code :L508} in
     * the create branch and {@code :L527} in the update branch. The create branch reaches it after
     * {@code INITIALIZE TRAN-CAT-BAL-RECORD}, so the addend starts at zero.
     *
     * <p>Inputs: the staging record, its account identifier and the mutable balance map. Output: a singleton
     * list holding the key when this call created it, empty otherwise. Side effects: mutates one entry of the
     * balance map.
     *
     * @param record the staging record
     * @param accountId the account identifier from the cross reference
     * @param categoryBalances the mutable balance map, updated in place
     * @return the created key as a singleton, or an empty list when the row already existed
     */
    private static List<CategoryBalanceKey> updateCategoryBalance(final StagingRecord record,
            final String accountId, final Map<CategoryBalanceKey, BigDecimal> categoryBalances) {

        final CategoryBalanceKey key =
                new CategoryBalanceKey(accountId, record.typeCode(), record.categoryCode());
        final BigDecimal existing = categoryBalances.get(key);

        if (existing == null) {
            categoryBalances.put(key, scaled(record.amount()));
            return List.of(key);
        }
        categoryBalances.put(key, scaled(existing.add(record.amount())));
        return List.of();
    }

    /**
     * {@code 2800-UPDATE-ACCOUNT-REC}, {@code app/cbl/CBTRN02C.cbl:L545-L560}.
     *
     * <p><b>The sign branch is not normalised, and must not be.</b> {@code :L547-L552} adds the amount to
     * {@code ACCT-CURR-CYC-CREDIT} when it is non-negative and to {@code ACCT-CURR-CYC-DEBIT} otherwise -
     * so a negative amount is added to the debit accumulator and the debit accumulator holds negative values.
     * That is exactly why {@code 1500-B-LOOKUP-ACCT} subtracts it. Taking an absolute value anywhere on this
     * path would corrupt the over-limit test for every subsequent record on the same account.
     *
     * <p>Reject code 109 at {@code :L559} is not modelled as an outcome. It is assigned on the
     * {@code REWRITE} {@code INVALID KEY} path, which is reachable only on an I/O failure; over these
     * fixtures every rewrite of a record that was just read succeeds, no reject record is written for it, and
     * {@code :L209} clears it on the next iteration.
     *
     * <p>Inputs: the staging record and the mutable account image. Output: none. Side effects: mutates three
     * accumulators of one account image.
     *
     * @param record the staging record
     * @param account the mutable account image, never {@code null} on this path
     */
    private static void updateAccountRecord(final StagingRecord record, final AccountImage account) {
        account.currentBalance = scaled(account.currentBalance.add(record.amount()));
        if (record.amount().signum() >= 0) {
            account.cycleCredit = scaled(account.cycleCredit.add(record.amount()));
        } else {
            account.cycleDebit = scaled(account.cycleDebit.add(record.amount()));
        }
    }

    /**
     * {@code 2500-WRITE-REJECT-REC}, {@code app/cbl/CBTRN02C.cbl:L500-L502}.
     *
     * <p>{@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} then
     * {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER}. Both are same-width group moves, so the
     * result is the 350-byte staging image followed by the 80-byte trailer, byte for byte, with no
     * realignment.
     *
     * <p>The trailer's two fields pad as their pictures dictate: {@code PIC 9(04)} zero-fills on the left, so
     * reason 100 renders {@code 0100}; {@code PIC X(76)} space-fills on the right, so the longest description
     * - the forty-two characters of {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} - is followed by
     * thirty-four spaces.
     *
     * <p>Inputs: the staging record, the reason and its description. Output: the reject record with its parts
     * kept separable. Side effects: none.
     *
     * @param record the rejected staging record
     * @param reasonCode the reason code, 100 through 103
     * @param description the reason description
     * @return the 430-character reject record, never {@code null}
     * @throws IllegalStateException if the assembled record is not exactly {@value #REJECT_RECORD_WIDTH}
     *     characters, which would mean a span or a picture had been mis-transcribed
     */
    private static RejectRecord writeRejectRecord(final StagingRecord record, final int reasonCode,
            final String description) {

        final String trailer = zeroPadLeft(reasonCode, REJECT_REASON_WIDTH)
                + spacePadRight(description, REJECT_DESC_WIDTH);
        final String assembled = record.rawRecord() + trailer;

        if (assembled.length() != REJECT_RECORD_WIDTH) {
            throw new IllegalStateException("A reject record must be exactly " + REJECT_RECORD_WIDTH
                    + " characters - 350 from REJECT-TRAN-DATA plus 80 from VALIDATION-TRAILER per "
                    + "app/cbl/CBTRN02C.cbl:L176-L178 - but one assembled to " + assembled.length()
                    + " for transaction " + record.tranId());
        }
        return new RejectRecord(record.rawRecord(), reasonCode, description, assembled);
    }

    // ====================================================================================================
    // Fixture loading. Position aware from the PIC clauses, and refusing a duplicate key rather than
    // silently letting one row win.
    // ====================================================================================================

    /**
     * Loads {@code cardxref.txt} as the {@code XREF-FILE} image.
     *
     * <p>Inputs: none. Output: card number to account identifier. Side effects: one classpath read.
     *
     * @return the cross-reference image, never {@code null}
     * @throws IllegalStateException if two rows share a card number, which VSAM would have refused
     */
    private static Map<String, String> loadCrossReference() {
        final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
        final Map<String, String> image = new LinkedHashMap<>();
        for (int row = 0; row < data.recordCount(); row++) {
            final String cardNumber = data.field(row, XREF_CARD_NUM_START, CARD_NUM_WIDTH);
            final String accountId = data.field(row, XREF_ACCT_ID_START, ACCT_ID_WIDTH);
            requireFirstOccurrence(image.put(cardNumber, accountId), "cardxref.txt", "XREF-CARD-NUM",
                    cardNumber);
        }
        return image;
    }

    /**
     * Loads {@code acctdata.txt} as the mutable {@code ACCOUNT-FILE} image.
     *
     * <p>Inputs: none. Output: account identifier to a mutable image. Side effects: one classpath read.
     *
     * @return the account image, never {@code null}
     * @throws IllegalStateException if two rows share an account identifier
     */
    private static Map<String, AccountImage> loadAccounts() {
        final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
        final Map<String, AccountImage> image = new LinkedHashMap<>();
        for (int row = 0; row < data.recordCount(); row++) {
            final String accountId = data.field(row, ACCT_ID_START, ACCT_ID_WIDTH);
            final AccountImage account = new AccountImage(
                    data.signedDecimal(row, ACCT_CURR_BAL_START, FixtureLoader.MONEY_FIELD_WIDTH),
                    data.signedDecimal(row, ACCT_CREDIT_LIMIT_START, FixtureLoader.MONEY_FIELD_WIDTH),
                    data.field(row, ACCT_EXPIRAION_DATE_START, EXPIRY_WIDTH),
                    data.signedDecimal(row, ACCT_CURR_CYC_CREDIT_START, FixtureLoader.MONEY_FIELD_WIDTH),
                    data.signedDecimal(row, ACCT_CURR_CYC_DEBIT_START, FixtureLoader.MONEY_FIELD_WIDTH));
            requireFirstOccurrence(image.put(accountId, account), "acctdata.txt", "ACCT-ID", accountId);
        }
        return image;
    }

    /**
     * Loads {@code tcatbal.txt} as the mutable {@code TCATBAL-FILE} image.
     *
     * <p>Inputs: none. Output: composite key to balance. Side effects: one classpath read.
     *
     * @return the category-balance image, never {@code null}
     * @throws IllegalStateException if two rows share a composite key
     */
    private static Map<CategoryBalanceKey, BigDecimal> loadCategoryBalances() {
        final FixtureLoader.FixtureData data =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);
        final Map<CategoryBalanceKey, BigDecimal> image = new LinkedHashMap<>();
        for (int row = 0; row < data.recordCount(); row++) {
            final CategoryBalanceKey key = new CategoryBalanceKey(
                    data.field(row, TCATBAL_ACCT_ID_START, ACCT_ID_WIDTH),
                    data.field(row, TCATBAL_TYPE_CD_START, DALYTRAN_TYPE_CD_WIDTH),
                    data.field(row, TCATBAL_CAT_CD_START, DALYTRAN_CAT_CD_WIDTH));
            final BigDecimal balance =
                    data.signedDecimal(row, TCATBAL_BALANCE_START, FixtureLoader.AMOUNT_FIELD_WIDTH);
            requireFirstOccurrence(image.put(key, balance), "tcatbal.txt", "the CVTRA01Y composite key",
                    key.rendered());
        }
        return image;
    }

    /**
     * Loads {@code dailytran.txt} as the sequential {@code DALYTRAN-FILE} input.
     *
     * <p>Every field is taken from its own span, so the amount's overpunched sign is read at column 143 and
     * nowhere else. A global substitution over the record would be wrong rather than merely crude:
     * {@code A} through {@code R} occur legitimately inside {@code DALYTRAN-DESC},
     * {@code DALYTRAN-MERCHANT-NAME} and {@code DALYTRAN-MERCHANT-CITY}.
     *
     * <p>The raw record is retained alongside the decoded fields because
     * {@code 2500-WRITE-REJECT-REC} copies the record image verbatim; re-rendering it from the decoded fields
     * would risk reproducing the fields rather than the bytes.
     *
     * <p>Inputs: none. Output: the staged records in DD order. Side effects: one classpath read.
     *
     * @return the staged records, never {@code null} and never empty
     */
    private static List<StagingRecord> loadStagingRecords() {
        final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
        final List<StagingRecord> staged = new ArrayList<>(data.recordCount());
        for (int row = 0; row < data.recordCount(); row++) {
            staged.add(new StagingRecord(
                    data.recordAt(row),
                    data.field(row, DALYTRAN_ID_START, DALYTRAN_ID_WIDTH),
                    data.field(row, DALYTRAN_TYPE_CD_START, DALYTRAN_TYPE_CD_WIDTH),
                    data.field(row, DALYTRAN_CAT_CD_START, DALYTRAN_CAT_CD_WIDTH),
                    data.field(row, DALYTRAN_SOURCE_START, DALYTRAN_SOURCE_WIDTH),
                    data.field(row, DALYTRAN_DESC_START, DALYTRAN_DESC_WIDTH),
                    data.signedDecimal(row, DALYTRAN_AMT_START, FixtureLoader.AMOUNT_FIELD_WIDTH),
                    data.field(row, DALYTRAN_MERCHANT_ID_START, DALYTRAN_MERCHANT_ID_WIDTH),
                    data.field(row, DALYTRAN_MERCHANT_NAME_START, MERCHANT_TEXT_WIDTH),
                    data.field(row, DALYTRAN_MERCHANT_CITY_START, MERCHANT_TEXT_WIDTH),
                    data.field(row, DALYTRAN_MERCHANT_ZIP_START, DALYTRAN_MERCHANT_ZIP_WIDTH),
                    data.field(row, DALYTRAN_CARD_NUM_START, CARD_NUM_WIDTH),
                    data.field(row, DALYTRAN_ORIG_TS_START, TIMESTAMP_WIDTH)));
        }
        return List.copyOf(staged);
    }

    // ====================================================================================================
    // Small shared helpers.
    // ====================================================================================================

    /**
     * Freezes the mutable account images into the immutable, key-ordered result view.
     *
     * <p>Inputs: the mutable images. Output: a key-ordered immutable map. Side effects: none.
     *
     * @param accountsById the mutable images after the last update
     * @return the final accumulators, key ordered
     */
    private static Map<String, AccountAccumulators> finalAccountAccumulators(
            final Map<String, AccountImage> accountsById) {

        final Map<String, AccountAccumulators> frozen = new TreeMap<>();
        accountsById.forEach((accountId, account) -> frozen.put(accountId, new AccountAccumulators(
                account.currentBalance, account.cycleCredit, account.cycleDebit)));
        return Map.copyOf(frozen);
    }

    /**
     * Refuses a duplicate key rather than letting the later row win silently.
     *
     * <p>Inputs: the value the map displaced, the fixture name, the field name and the key. Output: none.
     * Side effects: none.
     *
     * @param displaced whatever {@code Map.put} returned; {@code null} means the key was new
     * @param fixtureName the fixture being loaded, for the message
     * @param fieldName the key field, for the message
     * @param key the offending key, for the message
     * @throws IllegalStateException if {@code displaced} is not {@code null}
     */
    private static void requireFirstOccurrence(final Object displaced, final String fixtureName,
            final String fieldName, final String key) {

        if (displaced != null) {
            throw new IllegalStateException("Fixture " + fixtureName + " holds two rows with " + fieldName
                    + " '" + key + "'. A VSAM cluster would have refused the second with FILE STATUS '22', so "
                    + "the oracle refuses to guess which one the program would have seen.");
        }
    }

    /**
     * Applies the two implied decimal places every {@code V99} clause in these copybooks declares.
     *
     * <p>{@link RoundingMode#UNNECESSARY} is deliberate. Every value on this path is already at scale two -
     * the fixtures decode at scale two and addition of two scale-two values stays at scale two - so a rounding
     * mode that could ever round would be hiding an arithmetic mistake rather than accommodating one. If this
     * ever throws, the cause is a scale error upstream and must be fixed there.
     *
     * <p>Inputs: the value to scale. Output: the same value at scale {@value #MONEY_SCALE}. Side effects:
     * none.
     *
     * @param value the value to normalise; must not be {@code null}
     * @return the value at scale {@value #MONEY_SCALE}
     * @throws ArithmeticException if scaling would lose a digit
     */
    private static BigDecimal scaled(final BigDecimal value) {
        return Objects.requireNonNull(value, "value must not be null")
                .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Renders an integer as a {@code PIC 9(n)} field: right aligned, zero filled on the left.
     *
     * <p>Inputs: the value and the picture width. Output: the rendered field. Side effects: none.
     *
     * @param value the value to render; always non-negative on this path
     * @param width the picture width
     * @return the rendered field, exactly {@code width} characters
     * @throws IllegalStateException if the value does not fit, since a {@code PIC 9(4)} field cannot hold it
     */
    private static String zeroPadLeft(final int value, final int width) {
        final String digits = Integer.toString(value);
        if (digits.length() > width) {
            throw new IllegalStateException("Reason code " + value + " does not fit the PIC 9(" + width
                    + ") field WS-VALIDATION-FAIL-REASON declares at app/cbl/CBTRN02C.cbl:L181");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders text as a {@code PIC X(n)} field: left aligned, space filled on the right.
     *
     * <p>Inputs: the text and the picture width. Output: the rendered field. Side effects: none.
     *
     * @param text the text to render; must not be {@code null}
     * @param width the picture width
     * @return the rendered field, exactly {@code width} characters
     * @throws IllegalStateException if the text is longer than the picture, since COBOL would have truncated
     *     it and a silent truncation here would hide a mis-transcribed literal
     */
    private static String spacePadRight(final String text, final int width) {
        if (text.length() > width) {
            throw new IllegalStateException("The description '" + text + "' is " + text.length()
                    + " characters and does not fit the PIC X(" + width + ") field "
                    + "WS-VALIDATION-FAIL-REASON-DESC declares at app/cbl/CBTRN02C.cbl:L182");
        }
        return text + " ".repeat(width - text.length());
    }
}
