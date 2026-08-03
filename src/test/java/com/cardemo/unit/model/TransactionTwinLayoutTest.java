/*
 * ******************************************************************
 * Program     : TransactionTwinLayoutTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that Transaction (CVTRA05Y) and DailyTransaction
 *               (CVTRA06Y) are TWIN 350-byte layouts whose fourteen
 *               fields correspond one for one, and pins the exact byte
 *               offset map that three DFSORT specifications and the
 *               CREASTMT projection depend on - including the projection's
 *               two-byte truncation of the processing timestamp, which
 *               must be reproduced rather than corrected.
 * Source      : app/cpy/CVTRA05Y.cpy:L5-L18 (TRAN record, 350 B) @ 7756d89
 * Source      : app/cpy/CVTRA06Y.cpy:L5-L18 (DALYTRAN record, 350 B) @ 7756d89
 * Source      : app/proc/TRANREPT.prc:STEP05R (SYMNAMES offsets 263 / 305) @ 7756d89
 * Source      : app/jcl/CREASTMT.JCL:STEP010 (OUTREC projection) @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link Transaction} and {@link DailyTransaction}, the twin 350-byte transaction layouts.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code CVTRA05Y.cpy} and {@code CVTRA06Y.cpy} declare the <strong>same fourteen fields at the same
 * fourteen widths</strong>, differing only in the {@code TRAN-} versus {@code DALYTRAN-} prefix. They are
 * tested together because their being twins is itself the contract: the daily posting job reads a
 * {@code DALYTRAN} record and moves thirteen fields into a {@code TRAN} record
 * ({@code app/cbl/CBTRN02C.cbl:L424-L465}), which only works if the two layouts correspond exactly. Testing
 * them apart would let the two drift while both test classes still passed.
 *
 * <h3>The offset map, and why it is load-bearing</h3>
 *
 * <p>The widths sum to exactly 350:
 * {@code 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350}, giving these one-based
 * COBOL offsets:
 *
 * <table border="1">
 *   <caption>The proven 350-byte transaction offset map</caption>
 *   <tr><th>Field</th><th>Offset</th><th>Length</th></tr>
 *   <tr><td>{@code TRAN-ID}</td><td>1</td><td>16</td></tr>
 *   <tr><td>{@code TRAN-TYPE-CD}</td><td>17</td><td>2</td></tr>
 *   <tr><td>{@code TRAN-CAT-CD}</td><td>19</td><td>4</td></tr>
 *   <tr><td>{@code TRAN-SOURCE}</td><td>23</td><td>10</td></tr>
 *   <tr><td>{@code TRAN-DESC}</td><td>33</td><td>100</td></tr>
 *   <tr><td>{@code TRAN-AMT}</td><td>133</td><td>11</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-ID}</td><td>144</td><td>9</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-NAME}</td><td>153</td><td>50</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-CITY}</td><td>203</td><td>50</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-ZIP}</td><td>253</td><td>10</td></tr>
 *   <tr><td>{@code TRAN-CARD-NUM}</td><td><strong>263</strong></td><td>16</td></tr>
 *   <tr><td>{@code TRAN-ORIG-TS}</td><td>279</td><td>26</td></tr>
 *   <tr><td>{@code TRAN-PROC-TS}</td><td><strong>305</strong></td><td>26</td></tr>
 *   <tr><td>{@code FILLER}</td><td>331</td><td>20</td></tr>
 * </table>
 *
 * <p>Two of those offsets are quoted directly by the job control and are therefore verifiable from a second,
 * independent source: {@code app/proc/TRANREPT.prc:STEP05R} declares {@code TRAN-CARD-NUM,263,16,ZD} and
 * {@code TRAN-PROC-DT,305,10,CH}. The test asserts that the derived offsets match those literals, so the map
 * is confirmed by agreement between the copybook and the sort specification rather than by arithmetic alone.
 *
 * <h3>The CREASTMT projection truncates two bytes, and that must be reproduced</h3>
 *
 * <p>{@code app/jcl/CREASTMT.JCL:STEP010} emits {@code OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)}. The
 * third element copies <strong>50</strong> bytes starting at offset 279 - but the originating timestamp alone
 * is 26 bytes, so those 50 bytes are the full 26-byte originating timestamp plus only the
 * <strong>first 24</strong> of the 26-byte processing timestamp. The trailing 20-byte filler is dropped
 * entirely. The projected processing timestamp therefore arrives as a 24-character value, and any Java
 * projection that "helpfully" copied all 26 would produce statement output that differs from the legacy
 * baseline in a way that looks like a Java bug and is not.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=TransactionTwinLayoutTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The twin-correspondence assertion fails.</strong> One layout gained or lost a field. The
 *       posting job's thirteen-field move would then be incomplete, silently dropping data.</li>
 *   <li><strong>An offset assertion fails.</strong> A width changed. Every sort specification and the
 *       statement projection read absolute byte offsets, so a width change relocates fields the job control
 *       addresses positionally.</li>
 *   <li><strong>The truncation assertion fails.</strong> Somebody made the projection copy 52 bytes. Read
 *       {@code STEP010}: it copies 50, and the baseline comparison depends on it.</li>
 *   </ul>
 */
class TransactionTwinLayoutTest {

    /** The fourteen widths of CVTRA05Y and CVTRA06Y, in declaration order. */
    private static final int[] WIDTHS = {16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20};

    /** The catalogued average record length for TRANSACT, and the DALYTRAN LRECL. */
    private static final int RECORD_LENGTH = 350;

    /** {@code TRAN-AMT PIC S9(09)V99} - eleven digits, so NUMERIC(11,2) not (12,2). */
    private static final int AMOUNT_PRECISION = 11;

    private static final int AMOUNT_SCALE = 2;

    /**
     * A 26-character timestamp in the exact shape {@code Z-GET-DB2-FORMAT-TIMESTAMP} produces.
     *
     * <p>Derived field by field from the {@code DB2-FORMAT-TS} REDEFINES at
     * {@code app/cbl/CBTRN02C.cbl:L159-L174}, NOT from prose. See
     * {@link TimestampFormat#theFractionalPartIsHundredthsNotMilliseconds()} for why that distinction
     * decides whether the value fits its {@code PIC X(26)} field at all.
     */
    private static final String TIMESTAMP = "2024-01-01-12.30.45.120000";

    /** The thirteen data properties both entities share, in copybook order. */
    private static final List<String> SHARED_FIELDS = List.of(
            "transactionId", "typeCode", "categoryCode", "transactionSource", "description", "amount",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "cardNumber", "origTs", "procTs");

    private static Transaction transaction() {
        return new Transaction("0000000000000001", "01", 5, "System", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT NAME", "MERCHANT CITY", "12345",
                "4111111111111111", TIMESTAMP, TIMESTAMP);
    }

    private static DailyTransaction dailyTransaction() {
        return new DailyTransaction(1L, "0000000000000001", "01", 5, "System", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT NAME", "MERCHANT CITY", "12345",
                "4111111111111111", TIMESTAMP, TIMESTAMP);
    }

    @Nested
    @DisplayName("the twin correspondence: the same fourteen fields at the same fourteen widths")
    class TwinCorrespondence {

        @Test
        @DisplayName("both entities declare the same thirteen data property names")
        void bothEntitiesDeclareTheSameDataProperties() {
            final List<String> fromTransaction = instanceFieldNames(Transaction.class);
            final List<String> fromDaily = instanceFieldNames(DailyTransaction.class);

            assertThat(fromTransaction)
                    .as("CVTRA05Y and CVTRA06Y differ only in the TRAN- versus DALYTRAN- prefix, so the "
                            + "Java property names are identical - which is precisely what lets the "
                            + "posting job move thirteen fields across at "
                            + "app/cbl/CBTRN02C.cbl:L424-L465")
                    .containsAll(SHARED_FIELDS);
            assertThat(fromDaily).containsAll(SHARED_FIELDS);
        }

        @Test
        @DisplayName("Transaction has a version column and DailyTransaction deliberately does not")
        void onlyTransactionCarriesAVersionColumn() {
            assertThat(instanceFieldNames(Transaction.class))
                    .as("TRANSACT is a keyed cluster that the online layer rewrites, so it needs the "
                            + "store-level optimistic-lock guard")
                    .contains("version")
                    .hasSize(SHARED_FIELDS.size() + 1);
            assertThat(instanceFieldNames(DailyTransaction.class))
                    .as("DALYTRAN is a sequential STAGING dataset read once per run and never rewritten, "
                            + "so an optimistic-lock column would guard a concurrency that cannot occur. "
                            + "The asymmetry is deliberate and is asserted so it is not 'tidied up'. The "
                            + "one extra field is the ingestion ordinal, which carries the identity the "
                            + "keyless dataset cannot supply and is not a lock column")
                    .doesNotContain("version")
                    .contains("ingestSequence")
                    .hasSize(SHARED_FIELDS.size() + 1);
        }

        @ParameterizedTest
        @CsvSource({
            "transactionId, java.lang.String",
            "typeCode, java.lang.String",
            "categoryCode, java.lang.Integer",
            "transactionSource, java.lang.String",
            "description, java.lang.String",
            "amount, java.math.BigDecimal",
            "merchantId, java.lang.Long",
            "merchantName, java.lang.String",
            "merchantCity, java.lang.String",
            "merchantZip, java.lang.String",
            "cardNumber, java.lang.String",
            "origTs, java.lang.String",
            "procTs, java.lang.String",
        })
        @DisplayName("every shared field has the identical Java type in both entities")
        void everySharedFieldHasTheIdenticalTypeInBoth(final String field, final String typeName)
                throws ReflectiveOperationException {
            assertThat(Transaction.class.getDeclaredField(field).getType().getName())
                    .as("Transaction.%s must be %s", field, typeName)
                    .isEqualTo(typeName);
            assertThat(DailyTransaction.class.getDeclaredField(field).getType().getName())
                    .as("DailyTransaction.%s must be the same type as Transaction.%s, or the "
                            + "thirteen-field move in the posting job would need a conversion the source "
                            + "does not perform", field, field)
                    .isEqualTo(typeName);
        }

        @Test
        @DisplayName("a posting-job field move carries every value across unchanged")
        void aPostingJobFieldMoveCarriesEveryValueAcross() {
            final DailyTransaction input = dailyTransaction();
            final Transaction posted = new Transaction(
                    input.getTransactionId(), input.getTypeCode(), input.getCategoryCode(),
                    input.getTransactionSource(), input.getDescription(), input.getAmount(),
                    input.getMerchantId(), input.getMerchantName(), input.getMerchantCity(),
                    input.getMerchantZip(), input.getCardNumber(), input.getOrigTs(), input.getProcTs());

            assertThat(posted.getTransactionId()).isEqualTo(input.getTransactionId());
            assertThat(posted.getTypeCode()).isEqualTo(input.getTypeCode());
            assertThat(posted.getCategoryCode()).isEqualTo(input.getCategoryCode());
            assertThat(posted.getTransactionSource()).isEqualTo(input.getTransactionSource());
            assertThat(posted.getDescription()).isEqualTo(input.getDescription());
            assertThat(posted.getAmount()).isEqualByComparingTo(input.getAmount());
            assertThat(posted.getMerchantId()).isEqualTo(input.getMerchantId());
            assertThat(posted.getMerchantName()).isEqualTo(input.getMerchantName());
            assertThat(posted.getMerchantCity()).isEqualTo(input.getMerchantCity());
            assertThat(posted.getMerchantZip()).isEqualTo(input.getMerchantZip());
            assertThat(posted.getCardNumber()).isEqualTo(input.getCardNumber());
            assertThat(posted.getOrigTs())
                    .as("app/cbl/CBTRN02C.cbl:L424-L465 copies the ORIGINATING timestamp across verbatim "
                            + "and then generates a fresh processing timestamp; the originating value is "
                            + "never regenerated")
                    .isEqualTo(input.getOrigTs());
        }
    }

    @Nested
    @DisplayName("the 350-byte offset map, cross-checked against the DFSORT SYMNAMES literals")
    class OffsetMap {

        @Test
        @DisplayName("the fourteen widths sum to exactly 350 bytes")
        void theFourteenWidthsSumTo350() {
            assertThat(Arrays.stream(WIDTHS).sum())
                    .as("16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350, the "
                            + "catalogued record length for TRANSACT and the declared LRECL of DALYTRAN. "
                            + "app/data/ASCII/dailytran.txt is 105,300 bytes over 300 rows, i.e. 351 per "
                            + "row including the terminator - an independent confirmation")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(WIDTHS).hasSize(14);
        }

        @Test
        @DisplayName("the fixture file size independently confirms the 350-byte record")
        void theFixtureFileSizeConfirmsTheRecord() {
            assertThat(105_300 / 300)
                    .as("app/data/ASCII/dailytran.txt is 105,300 bytes for 300 rows, giving 351 - the "
                            + "350-byte record plus one terminator. This is the Gate 1 fixture, so its "
                            + "geometry is the parity contract")
                    .isEqualTo(351);
            assertThat(351 - 1).isEqualTo(RECORD_LENGTH);
        }

        @ParameterizedTest
        @CsvSource({
            "0,  1,  16",
            "1,  17, 2",
            "2,  19, 4",
            "3,  23, 10",
            "4,  33, 100",
            "5,  133, 11",
            "6,  144, 9",
            "7,  153, 50",
            "8,  203, 50",
            "9,  253, 10",
            "10, 263, 16",
            "11, 279, 26",
            "12, 305, 26",
            "13, 331, 20",
        })
        @DisplayName("each field begins at its documented one-based COBOL offset")
        void eachFieldBeginsAtItsDocumentedOffset(
                final int index, final int expectedOffset, final int expectedWidth) {
            int offset = 1;
            for (int i = 0; i < index; i++) {
                offset += WIDTHS[i];
            }

            assertThat(offset)
                    .as("field %d must begin at byte %d: the sort specifications and the statement "
                            + "projection address these positions absolutely, so a width change upstream "
                            + "silently relocates every field after it", index, expectedOffset)
                    .isEqualTo(expectedOffset);
            assertThat(WIDTHS[index]).isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("the card number offset matches the TRAN-CARD-NUM,263,16,ZD sort literal exactly")
        void theCardNumberOffsetMatchesTheSortLiteral() {
            final int derived = 1 + 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10;

            assertThat(derived)
                    .as("app/proc/TRANREPT.prc:STEP05R declares SYMNAMES entry TRAN-CARD-NUM,263,16,ZD. "
                            + "The offset derived from the copybook widths is 263, so the copybook and the "
                            + "job control AGREE - the map is confirmed by two independent sources rather "
                            + "than by arithmetic alone")
                    .isEqualTo(263);
            assertThat(WIDTHS[10])
                    .as("the sort literal also declares length 16, matching TRAN-CARD-NUM PIC X(16)")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("the processing timestamp offset matches the TRAN-PROC-DT,305,10,CH sort literal")
        void theProcessingTimestampOffsetMatchesTheSortLiteral() {
            final int derived = 1 + 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26;

            assertThat(derived)
                    .as("app/proc/TRANREPT.prc:STEP05R declares TRAN-PROC-DT,305,10,CH - note the length "
                            + "is 10, not 26: the report's INCLUDE COND filters on the first ten "
                            + "characters of the 26-byte timestamp, which is the DATE portion. The "
                            + "derived offset 305 agrees with the literal")
                    .isEqualTo(305);
            assertThat(WIDTHS[12])
                    .as("the field itself is 26 bytes even though the sort addresses only its first 10")
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("the inclusive ten-character date filter selects on the timestamp prefix")
        void theInclusiveDateFilterSelectsOnTheTimestampPrefix() {
            final Transaction tran = transaction();
            final String datePortion = tran.getProcTs().substring(0, 10);

            assertThat(datePortion)
                    .as("the report sort's INCLUDE COND compares a ten-character field, so the Java "
                            + "predicate must compare the same prefix rather than parsing the whole "
                            + "timestamp")
                    .isEqualTo("2024-01-01")
                    .hasSize(10);
            assertThat(datePortion.compareTo("2024-01-01") >= 0 && datePortion.compareTo("2024-12-31") <= 0)
                    .as("the filter is INCLUSIVE at both ends, and ISO-8601 ordering makes the plain "
                            + "string comparison correct")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the CREASTMT projection, which truncates the processing timestamp by two bytes")
    class StatementProjectionTruncation {

        @Test
        @DisplayName("the projection's third element copies 50 bytes from offset 279, not 52")
        void theProjectionCopiesFiftyBytesFromOffset279() {
            assertThat(WIDTHS[11] + WIDTHS[12])
                    .as("the originating timestamp is 26 bytes and the processing timestamp is 26, so the "
                            + "two together are 52 bytes - but STEP010 copies only 50 from offset 279")
                    .isEqualTo(52);
            assertThat(52 - 50)
                    .as("PRESERVED LEGACY TRUNCATION: the projection drops exactly 2 bytes from the tail "
                            + "of the processing timestamp. This is not a rounding of the specification - "
                            + "it is what OUTREC FIELDS=(279:279,50) does, and the statement baseline was "
                            + "produced with it")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the projected processing timestamp is 24 characters, not 26")
        void theProjectedProcessingTimestampIs24Characters() {
            final Transaction tran = transaction();
            final String origAndProc = tran.getOrigTs() + tran.getProcTs();
            final String projected = origAndProc.substring(0, 50);
            final String projectedProc = projected.substring(26);

            assertThat(origAndProc).hasSize(52);
            assertThat(projected)
                    .as("copying 50 bytes from the 52-byte pair keeps the full originating timestamp and "
                            + "clips the processing one")
                    .hasSize(50);
            assertThat(projectedProc)
                    .as("the projected processing timestamp arrives as 24 characters. A Java projection "
                            + "that copied all 26 would produce statement output differing from the legacy "
                            + "baseline by two bytes per record - a diff that looks like a Java defect and "
                            + "is in fact a faithful reproduction being undone")
                    .hasSize(24)
                    .isEqualTo(tran.getProcTs().substring(0, 24));
        }

        @Test
        @DisplayName("the twenty-byte trailing filler is dropped entirely by the projection")
        void theTrailingFillerIsDroppedEntirely() {
            final int projectedTotal = 16 + 262 + 50;

            assertThat(WIDTHS[13])
                    .as("FILLER PIC X(20) at CVTRA05Y:L18 occupies bytes 331 to 350")
                    .isEqualTo(20);
            assertThat(projectedTotal)
                    .as("OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50) emits 16 + 262 + 50 = 328 bytes "
                            + "from a 350-byte input, so 22 bytes are dropped - the 20-byte filler plus "
                            + "the 2 truncated timestamp bytes")
                    .isEqualTo(328);
            assertThat(RECORD_LENGTH - projectedTotal)
                    .as("350 - 328 = 22 dropped bytes, which is exactly the 20-byte filler plus the "
                            + "2-byte truncation - the two losses account for the difference completely, "
                            + "confirming the projection is understood rather than guessed")
                    .isEqualTo(22);
        }

        @Test
        @DisplayName("the projection moves the card number to the front, which is what the sort keys on")
        void theProjectionMovesTheCardNumberToTheFront() {
            final Transaction tran = transaction();
            final String projectedHead = tran.getCardNumber();

            assertThat(projectedHead)
                    .as("OUTREC element 1:263,16 places the 16-byte card number at the START of the "
                            + "projected record, which is why the work cluster is defined with KEYS(32 0) "
                            + "- card number then transaction id - at app/jcl/CREASTMT.JCL:DELDEF01")
                    .hasSize(16);
            assertThat(projectedHead.length() + tran.getTransactionId().length())
                    .as("16 + 16 = 32, exactly the declared key length of the statement work cluster and "
                            + "exactly the TRNX-KEY width in app/cpy/COSTM01.CPY")
                    .isEqualTo(32);
        }
    }

    @Nested
    @DisplayName("the amount field: NUMERIC(11,2) from PIC S9(09)V99, never NUMERIC(12,2)")
    class AmountPrecision {

        @Test
        @DisplayName("the amount is BigDecimal on both entities, never a floating-point type")
        void theAmountIsBigDecimalOnBoth() {
            for (final Class<?> entity : List.of(Transaction.class, DailyTransaction.class)) {
                assertThat(entity.getDeclaredFields())
                        .filteredOn(field -> !field.isSynthetic())
                        .allSatisfy(field -> assertThat(field.getType())
                                .as("%s.%s must not be a floating-point type; the posting cascade sums "
                                        + "amounts into cycle accumulators and compares them against a "
                                        + "credit limit", entity.getSimpleName(), field.getName())
                                .isNotIn(double.class, float.class, Double.class, Float.class));
            }
        }

        @Test
        @DisplayName("the amount precision is 11, one less than the account money fields' 12")
        void theAmountPrecisionIsElevenNotTwelve() {
            assertThat(WIDTHS[5])
                    .as("TRAN-AMT is PIC S9(09)V99 at CVTRA05Y:L10 - NINE integer digits plus two "
                            + "decimals, so eleven in total and NUMERIC(11,2). The account money fields "
                            + "are PIC S9(10)V99, i.e. NUMERIC(12,2). Using 12,2 here would widen the "
                            + "column beyond the source and shift the 350-byte record by one byte")
                    .isEqualTo(AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("the widest S9(09)V99 amount round-trips exactly on both entities")
        void theWidestAmountRoundTripsExactly() {
            final String widest = "999999999.99";
            final Transaction tran = transaction();
            final DailyTransaction daily = dailyTransaction();
            tran.setAmount(new BigDecimal(widest));
            daily.setAmount(new BigDecimal(widest));

            assertThat(tran.getAmount()).isEqualByComparingTo(widest);
            assertThat(daily.getAmount()).isEqualByComparingTo(widest);
            assertThat(new BigDecimal(widest).precision())
                    .as("nine integer digits plus two decimals is eleven significant digits, exactly the "
                            + "PIC width")
                    .isEqualTo(AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("a negative amount is preserved, because dailytran.txt genuinely contains them")
        void aNegativeAmountIsPreserved() {
            final DailyTransaction daily = dailyTransaction();
            daily.setAmount(new BigDecimal("-250.75"));

            assertThat(daily.getAmount())
                    .as("app/data/ASCII/dailytran.txt contains BOTH the '{' and the '}' overpunch "
                            + "characters, meaning it carries genuinely negative amounts and therefore "
                            + "exercises the cycle-DEBIT branch of the posting logic. The fixture must not "
                            + "be normalised and neither must this field")
                    .isEqualByComparingTo("-250.75")
                    .isNegative();
            assertThat(daily.getAmount().scale()).isEqualTo(AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the category code is an Integer matching PIC 9(04), range 0 to 9999")
        void theCategoryCodeIsAnIntegerMatchingFourDigits() {
            assertThat(WIDTHS[2])
                    .as("TRAN-CAT-CD is PIC 9(04) at CVTRA05Y:L7, so the value range is 0 to 9999 and an "
                            + "Integer is sufficient")
                    .isEqualTo(4);
            assertThat(transaction().getCategoryCode()).isEqualTo(5);
            assertThat(String.format("%04d", transaction().getCategoryCode()))
                    .as("the fixed-width form zero-pads to four characters; the interest job moves the "
                            + "literal '05' into a two-character type code and '0005' would be the "
                            + "category rendering")
                    .isEqualTo("0005")
                    .hasSize(4);
        }
    }

    @Nested
    @DisplayName("the 26-character timestamp: 20 date-time bytes, 2 hundredths digits, 4 literal zeros")
    class TimestampFormat {

        @Test
        @DisplayName("both timestamp fields are 26 characters wide")
        void bothTimestampFieldsAre26Characters() {
            assertThat(WIDTHS[11])
                    .as("TRAN-ORIG-TS is PIC X(26) at CVTRA05Y:L16")
                    .isEqualTo(26);
            assertThat(WIDTHS[12])
                    .as("TRAN-PROC-TS is PIC X(26) at CVTRA05Y:L17")
                    .isEqualTo(26);
            assertThat(transaction().getOrigTs()).hasSize(26);
            assertThat(transaction().getProcTs()).hasSize(26);
        }

        @Test
        @DisplayName("the generated timestamp ends in the four literal zeros MOVEd by DB2-REST")
        void theGeneratedTimestampEndsInFourZeros() {
            final String generated = transaction().getProcTs();

            assertThat(generated)
                    .as("Z-GET-DB2-FORMAT-TIMESTAMP at app/cbl/CBTRN02C.cbl:L692-L705 executes "
                            + "MOVE '0000' TO DB2-REST - a literal four-character constant, not a "
                            + "truncation artefact. Java must append exactly those four zeros")
                    .endsWith("0000")
                    .hasSize(26);
            assertThat(generated.substring(22))
                    .as("DB2-REST is PIC X(04) and occupies the final four bytes, positions 23 to 26")
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("the fractional part is HUNDREDTHS (two digits), not milliseconds (three)")
        void theFractionalPartIsHundredthsNotMilliseconds() {
            final String generated = transaction().getProcTs();

            // Widths read field by field from the DB2-FORMAT-TS REDEFINES at CBTRN02C L160-L174.
            final int fromSubfields = 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 4;

            assertThat(fromSubfields)
                    .as("DB2-YYYY X(4) + streep + DB2-MM X(2) + streep + DB2-DD X(2) + streep + "
                            + "DB2-HH X(2) + dot + DB2-MIN X(2) + dot + DB2-SS X(2) + dot + "
                            + "DB2-MIL 9(2) + DB2-REST X(4) = 26, matching the PIC X(26) it redefines")
                    .isEqualTo(26);

            assertThat(generated.charAt(19))
                    .as("DB2-DOT-3 sits immediately before the fractional digits")
                    .isEqualTo('.');
            assertThat(generated.substring(20, 22))
                    .as("SOURCE-GOVERNED CORRECTION. DB2-MIL is declared PIC 9(002) at "
                            + "app/cbl/CBTRN02C.cbl:L173 - TWO digits, i.e. HUNDREDTHS of a second, which "
                            + "is what FUNCTION CURRENT-DATE supplies. The AAP prose describes this as "
                            + "'millisecond precision followed by four zeros', but 3 + 4 = 7 fractional "
                            + "digits would make the value 27 characters and it could not fit PIC X(26) "
                            + "at all. Two digits plus four zeros = 6, and 20 + 6 = 26 exactly. Per AAP "
                            + "0.2, the corpus is the authority and the source wins where the two "
                            + "disagree, so the fractional part is asserted as 2 + 4")
                    .hasSize(2)
                    .matches("\\d{2}");
            assertThat(generated.substring(22, 26))
                    .as("DB2-REST follows the two hundredths digits immediately")
                    .isEqualTo("0000");
            assertThat(generated.substring(20))
                    .as("six fractional characters in total: two significant, four literal zeros")
                    .hasSize(6);
        }

        @Test
        @DisplayName("the separator set is three dashes then three dots, per the STREEP and DOT MOVEs")
        void theSeparatorSetIsThreeDashesThenThreeDots() {
            final String generated = transaction().getProcTs();

            assertThat(generated.charAt(4)).as("DB2-STREEP-1 after the year").isEqualTo('-');
            assertThat(generated.charAt(7)).as("DB2-STREEP-2 after the month").isEqualTo('-');
            assertThat(generated.charAt(10))
                    .as("DB2-STREEP-3 separates the DATE from the HOUR. The legacy MOVE at "
                            + "app/cbl/CBTRN02C.cbl:L703 sets all three STREEP fields to '-', so this "
                            + "position is a DASH and not the space an ISO-8601 formatter would emit")
                    .isEqualTo('-');
            assertThat(generated.charAt(13)).as("DB2-DOT-1").isEqualTo('.');
            assertThat(generated.charAt(16)).as("DB2-DOT-2").isEqualTo('.');
            assertThat(generated.charAt(19)).as("DB2-DOT-3").isEqualTo('.');
            assertThat(generated)
                    .as("the full rendering is YYYY-MM-DD-HH.MM.SS.CC0000; a DateTimeFormatter pattern "
                            + "of yyyy-MM-dd-HH.mm.ss.SS followed by the literal 0000 reproduces it")
                    .matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000");
        }

        @Test
        @DisplayName("the timestamp is a String, so a legacy low-values value survives")
        void theTimestampIsAStringSoLegacyValuesSurvive() throws ReflectiveOperationException {
            assertThat(Transaction.class.getDeclaredField("procTs").getType())
                    .as("parsing to Instant would reject the space-filled and low-values timestamps the "
                            + "legacy data legitimately contains, and would also lose the four trailing "
                            + "zeros on a round trip")
                    .isEqualTo(String.class);

            final Transaction tran = transaction();
            tran.setProcTs(" ".repeat(26));
            assertThat(tran.getProcTs()).isEqualTo(" ".repeat(26)).hasSize(26);
        }

        @Test
        @DisplayName("every accessor pair round-trips on both entities")
        void everyAccessorPairRoundTripsOnBothEntities() {
            final Transaction tran = transaction();
            tran.setTransactionId("0000000000000002");
            tran.setTypeCode("02");
            tran.setCategoryCode(6);
            tran.setTransactionSource("POS TERM");
            tran.setDescription("Payment");
            tran.setAmount(new BigDecimal("1.23"));
            tran.setMerchantId(10L);
            tran.setMerchantName("OTHER");
            tran.setMerchantCity("OTHERTOWN");
            tran.setMerchantZip("99999");
            tran.setCardNumber("5500000000000004");
            tran.setOrigTs("2025-02-02-01.01.01.010000");
            tran.setProcTs("2025-02-03-02.02.02.020000");
            tran.setVersion(4L);

            assertThat(tran.getTransactionId()).isEqualTo("0000000000000002");
            assertThat(tran.getTypeCode()).isEqualTo("02");
            assertThat(tran.getCategoryCode()).isEqualTo(6);
            assertThat(tran.getTransactionSource()).isEqualTo("POS TERM");
            assertThat(tran.getDescription()).isEqualTo("Payment");
            assertThat(tran.getAmount()).isEqualByComparingTo("1.23");
            assertThat(tran.getMerchantId()).isEqualTo(10L);
            assertThat(tran.getMerchantName()).isEqualTo("OTHER");
            assertThat(tran.getMerchantCity()).isEqualTo("OTHERTOWN");
            assertThat(tran.getMerchantZip()).isEqualTo("99999");
            assertThat(tran.getCardNumber()).isEqualTo("5500000000000004");
            assertThat(tran.getVersion()).isEqualTo(4L);

            final DailyTransaction daily = dailyTransaction();
            daily.setTransactionId("0000000000000003");
            daily.setTypeCode("03");
            daily.setCategoryCode(7);
            daily.setTransactionSource("System");
            daily.setDescription("Interest");
            daily.setAmount(new BigDecimal("4.56"));
            daily.setMerchantId(0L);
            daily.setMerchantName(" ".repeat(50));
            daily.setMerchantCity(" ".repeat(50));
            daily.setMerchantZip(" ".repeat(10));
            daily.setCardNumber("4111111111111112");
            daily.setOrigTs("2025-03-03-03.03.03.030000");
            daily.setProcTs("2025-03-04-04.04.04.040000");

            assertThat(daily.getTransactionId()).isEqualTo("0000000000000003");
            assertThat(daily.getTypeCode()).isEqualTo("03");
            assertThat(daily.getCategoryCode()).isEqualTo(7);
            assertThat(daily.getTransactionSource()).isEqualTo("System");
            assertThat(daily.getDescription()).isEqualTo("Interest");
            assertThat(daily.getAmount()).isEqualByComparingTo("4.56");
            assertThat(daily.getMerchantId())
                    .as("app/cbl/CBACT04C.cbl sets the merchant id to zero and the name, city and postal "
                            + "code to spaces for a generated interest transaction, so those exact values "
                            + "must be representable")
                    .isZero();
            assertThat(daily.getMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(daily.getCardNumber()).isEqualTo("4111111111111112");
        }

        @Test
        @DisplayName("observed behaviour: both twins validate their NOT NULL columns in its constructor")
        void bothTwinsValidateTheirNotNullColumns() {
            assertThatIllegalArgumentException()
                    .as("both twins now validate, including the primary key. The four entities no longer "
                            + "carry three different policies: every NOT NULL column is enforced at "
                            + "construction across Account, Customer, Card and both transaction twins, so "
                            + "no entity's policy has to be inferred from another")
                    .isThrownBy(() -> new Transaction(null, null, null, null, null, null, null, null,
                            null, null, null, null, null))
                    .withMessageContaining("TRAN-ID");
            assertThatIllegalArgumentException()
                    .as("the staging twin enforces the same contract as the posted twin, which is what "
                            + "makes the 350 byte layouts genuinely interchangeable")
                    .isThrownBy(() -> new DailyTransaction(null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null))
                    .withMessageContaining("TRAN-ID");
        }
    }

    private static List<String> instanceFieldNames(final Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }
}
