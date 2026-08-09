package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link TranRecord} against {@code app/cpy/CVTRA05Y.cpy} and against the behaviour its nine
 * consuming COBOL programs depend on.
 *
 * <h2>Sources this suite is derived from</h2>
 * <p>Every expectation below traces to one of these, and each was re-read and re-derived rather than
 * taken on trust:
 * <ul>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - the record contract. Its header comment declares
 *       {@code RECLN = 350}, and its fourteen {@code PICTURE} clauses give every width:
 *       {@code 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350}. Offsets here are that addition,
 *       accumulated left to right; nothing is inferred from a parser.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} - builds a record in {@code 1300-B-WRITE-TX} using both write
 *       verbs ({@code STRING} at {@code :476-480} and {@code :485-489}, {@code MOVE} at
 *       {@code :482-484} and {@code :490-498}) and writes 350 bytes at {@code :500}.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} - {@code DISPLAY TRAN-RECORD} at {@code :180} needs the whole
 *       image; {@code DISPLAY 'TRAN-AMT ' TRAN-AMT} at {@code :198} needs the raw zoned span; and
 *       {@code TRAN-PROC-TS (1:10)} at {@code :173-174} is the reference modification that
 *       {@code tranProcDt()} reproduces.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} - the {@code POSTTRAN} poster: {@code MOVE DALYTRAN-AMT TO
 *       TRAN-AMT} at {@code :430}, {@code MOVE DALYTRAN-CARD-NUM TO TRAN-CARD-NUM} at {@code :435},
 *       {@code MOVE DB2-FORMAT-TS TO TRAN-PROC-TS} at {@code :438}, {@code WRITE} at {@code :564}.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} - {@code :100} and {@code :102} emit the SORT symbol lines
 *       quoted below as literal {@code PIC X(80)} JCL text.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} ({@code :41-42}, {@code LRECL=350}),
 *       {@code app/proc/TRANREPT.prc} ({@code :39-40}) and {@code app/jcl/INTCALC.jcl}
 *       ({@code PARM='2022071800'} at {@code :22}, {@code RECFM=F,LRECL=350} at {@code :39}).</li>
 *   <li>{@code app/data/ASCII/dailytran.txt} - 300 rows of exactly 350 bytes, the {@code CVTRA06Y}
 *       twin of this layout, and the only real data available for this record shape.</li>
 * </ul>
 *
 * <h2>Three independent confirmations of the two riskiest offsets</h2>
 * <p>{@code app/jcl/TRANREPT.jcl:41-42}, {@code app/proc/TRANREPT.prc:39-40} and
 * {@code app/cbl/CORPT00C.cbl:100,102} all carry, verbatim:
 * <pre>
 * TRAN-CARD-NUM,263,16,ZD
 * TRAN-PROC-DT,305,10,CH
 * </pre>
 * <p>Those are 1-based, so they pin {@code TRAN-CARD-NUM} to 0-based 262 and {@code TRAN-PROC-DT} -
 * the first ten bytes of {@code TRAN-PROC-TS} - to 0-based 304. Both are asserted below.
 *
 * <p>One deliberate discrepancy is recorded rather than resolved: the SORT symbol declares
 * {@code TRAN-CARD-NUM} as {@code ZD} (zoned decimal) while {@code CVTRA05Y} declares it
 * {@code PIC X(16)}. The copybook is the contract, so the field is a {@code String} and its leading
 * zeros are stored characters - {@code 0927987108636232} in fixture row 2 would lose its leading zero
 * as a number. The SORT declaration is only a collation instruction for a field whose characters happen
 * to be digits, and it is left exactly as it is.
 *
 * <h2>Why the fixture rows are embedded rather than read</h2>
 * <p>The three rows below are transcribed field by field from {@code app/data/ASCII/dailytran.txt} and
 * each was verified character-for-character against the real file. They are embedded, not loaded from
 * the classpath, so this suite is hermetic: it asserts the copybook contract, never the availability of
 * a fixture resource, and it performs no filesystem or classpath read at all. {@code app/data} is part
 * of the read-only parity oracle and nothing here writes to it.
 *
 * <h2>The overpunch census that makes the sign table empirical</h2>
 * <p>Reading the trailing byte of {@code TRAN-AMT} (0-based 142) across all 300 rows yields every one
 * of the twenty legal zoned characters, and the counts sum to exactly 300:
 * <pre>
 * positive final digit 0-9  {  A  B  C  D  E  F  G  H  I   counts 25 28 29 30 29 23 21 24 17 24 = 250
 * negative final digit 0-9  }  J  K  L  M  N  O  P  Q  R   counts  6  3  5  5  6  2  4  7  4  8 =  50
 * </pre>
 * <p>So the table asserted below is not a transcription of documentation - it is what this dataset
 * actually contains, and all twenty characters are exercised in both directions.
 *
 * <h2>Rules status</h2>
 * <p>{@code review_rules} reports "No user rules provided" for this project, which the Agent Action
 * Plan records too. The absence of project rules is not licence to assert less: this suite is held to
 * the plan's own transformation rules and gates - {@code R2}-{@code R5} (absolute offsets, truncation
 * not rounding, scale from the {@code PICTURE}, never {@code double}), {@code G19}/{@code G21} (width
 * and {@code FILLER}), {@code G22}-{@code G24} (numeric parity), {@code G34} (two accessors over one
 * span), {@code G44} (no persistence artefact), {@code G50}/{@code G53} (both outcomes of every branch,
 * no mutable static state) - each named at the assertion that discharges it.
 *
 * <p>Both code pages are always named explicitly. Nothing here relies on a platform default, because
 * the zoned sign overpunch bytes differ between code pages and a default-charset test would pass or
 * fail according to the machine it ran on.
 */
@DisplayName("TranRecord - CVTRA05Y, 350 bytes")
class TranRecordTest {

    /** The code page of {@code app/data/ASCII}, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The EBCDIC code page of {@code app/data/EBCDIC}. {@code StandardCharsets} has no constant for it,
     * so it is looked up by name; the JDK ships {@code IBM037} in its base module.
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * Row 1 of {@code app/data/ASCII/dailytran.txt}, assembled from its fourteen field images so each
     * one is visible and auditable. A positive amount, {@code 'G'} overpunch.
     */
    private static final String ROW_1 =
            "0000000000683580"                            // TRAN-ID            X(16)
            + "01"                                        // TRAN-TYPE-CD       X(02)
            + "0001"                                      // TRAN-CAT-CD        9(04)
            + "POS TERM  "                                // TRAN-SOURCE        X(10)
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // TRAN-DESC          X(100)
            + "0000005047G"                               // TRAN-AMT           S9(09)V99 = 504.77
            + "800000000"                                 // TRAN-MERCHANT-ID   9(09)
            + "Abshire-Lowe" + " ".repeat(38)             // TRAN-MERCHANT-NAME X(50)
            + "North Enoshaven" + " ".repeat(35)          // TRAN-MERCHANT-CITY X(50)
            + "72112     "                                // TRAN-MERCHANT-ZIP  X(10)
            + "4859452612877065"                          // TRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // TRAN-PROC-TS       X(26), unprocessed
            + " ".repeat(20);                             // FILLER             X(20)

    /**
     * Row 2 of the same fixture, and the load-bearing negative row: its amount image ends in
     * <code>'&#125;'</code>, the character that means "negative, final digit zero".
     *
     * <p>{@code 0000009190}<code>&#125;</code> is {@code -919.00}, not zero and not {@code +919.00}. A
     * codec that mapped a final digit of zero to the positive character regardless of sign would corrupt
     * this row silently - the magnitude would survive and only the sign would be lost - so this row is
     * round-tripped byte for byte below. Its {@code TRAN-CARD-NUM} {@code 0927987108636232} carries a
     * leading zero, which is the second reason the field must stay a {@code String}.
     */
    private static final String ROW_2 =
            "0000000001774260"                            // TRAN-ID            X(16)
            + "03"                                        // TRAN-TYPE-CD       X(02)
            + "0001"                                      // TRAN-CAT-CD        9(04)
            + "OPERATOR  "                                // TRAN-SOURCE        X(10)
            + "Return item at Nitzsche, Nicolas and Lowe" // TRAN-DESC          X(100), 41 chars
            + " ".repeat(59)                              //                    + 59 = 100
            + "0000009190}"                               // TRAN-AMT           S9(09)V99 = -919.00
            + "800000000"                                 // TRAN-MERCHANT-ID   9(09)
            + "Nitzsche, Nicolas and Lowe" + " ".repeat(24) // TRAN-MERCHANT-NAME X(50)
            + "Fidelshire" + " ".repeat(40)               // TRAN-MERCHANT-CITY X(50)
            + "53378     "                                // TRAN-MERCHANT-ZIP  X(10)
            + "0927987108636232"                          // TRAN-CARD-NUM      X(16), leading zero
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // TRAN-PROC-TS       X(26), unprocessed
            + " ".repeat(20);                             // FILLER             X(20)

    /**
     * Row 7 of the same fixture. A negative amount carrying a {@code 'P'} overpunch, which is how the
     * fixture stores a returned item.
     */
    private static final String ROW_7 =
            "0000000016259484"                            // TRAN-ID            X(16)
            + "03"                                        // TRAN-TYPE-CD       X(02)
            + "0001"                                      // TRAN-CAT-CD        9(04)
            + "OPERATOR  "                                // TRAN-SOURCE        X(10)
            + "Return item at Sipes Inc" + " ".repeat(76) // TRAN-DESC          X(100)
            + "0000000567P"                               // TRAN-AMT           S9(09)V99 = -56.77
            + "800000000"                                 // TRAN-MERCHANT-ID   9(09)
            + "Sipes Inc" + " ".repeat(41)                // TRAN-MERCHANT-NAME X(50)
            + "Emilioside" + " ".repeat(40)               // TRAN-MERCHANT-CITY X(50)
            + "93329     "                                // TRAN-MERCHANT-ZIP  X(10)
            + "4011500891777367"                          // TRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // TRAN-PROC-TS       X(26)
            + " ".repeat(20);                             // FILLER             X(20)

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is the 350 bytes the copybook's RECLN comment declares")
        void recordLengthIs350() {
            assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TranRecord.LAYOUT.recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("the fourteen declared spans sum to exactly 350 with no gap and no overlap")
        void declaredSpansAccountForEveryByte() {
            assertThat(TranRecord.sumOfDeclaredSpanLengths()).isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.LAYOUT.storageSpans()).hasSize(14);
            assertThat(TranRecord.LAYOUT.redefinitions()).isEmpty();

            int cursor = 0;
            for (FieldSpan span : TranRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("contiguity at %s", span.name()).isEqualTo(cursor);
                cursor += span.length();
            }
            assertThat(cursor).isEqualTo(TranRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the width is the copybook's own addition, term by term, not a magic number")
        void widthIsTheCopybookAdditionTermByTerm() {
            // Every term below is one PICTURE clause of app/cpy/CVTRA05Y.cpy, in declaration order:
            //   TRAN-ID X(16) + TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) + TRAN-SOURCE X(10)
            // + TRAN-DESC X(100) + TRAN-AMT S9(09)V99 (9+2) + TRAN-MERCHANT-ID 9(09)
            // + TRAN-MERCHANT-NAME X(50) + TRAN-MERCHANT-CITY X(50) + TRAN-MERCHANT-ZIP X(10)
            // + TRAN-CARD-NUM X(16) + TRAN-ORIG-TS X(26) + TRAN-PROC-TS X(26) + FILLER X(20)
            // Written out so a reviewer can check the arithmetic against the copybook by eye rather
            // than trusting a constant: 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350.
            int fromPictureClauses =
                    16 + 2 + 4 + 10 + 100 + (9 + 2) + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20;

            assertThat(fromPictureClauses).isEqualTo(350);
            assertThat(fromPictureClauses).isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(fromPictureClauses).isEqualTo(TranRecord.sumOfDeclaredSpanLengths());

            // And term by term against the declared spans, so a compensating pair of errors cannot hide
            // inside a correct total.
            assertThat(TranRecord.LAYOUT.storageSpans())
                    .extracting(FieldSpan::length)
                    .containsExactly(16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20);
        }

        @ParameterizedTest(name = "{0} at 0-based {1}, {2} bytes")
        @CsvSource({
                "TRAN-ID,              0,  16",
                "TRAN-TYPE-CD,        16,   2",
                "TRAN-CAT-CD,         18,   4",
                "TRAN-SOURCE,         22,  10",
                "TRAN-DESC,           32, 100",
                "TRAN-AMT,           132,  11",
                "TRAN-MERCHANT-ID,   143,   9",
                "TRAN-MERCHANT-NAME, 152,  50",
                "TRAN-MERCHANT-CITY, 202,  50",
                "TRAN-MERCHANT-ZIP,  252,  10",
                "TRAN-CARD-NUM,      262,  16",
                "TRAN-ORIG-TS,       278,  26",
                "TRAN-PROC-TS,       304,  26",
        })
        @DisplayName("every referable span sits where CVTRA05Y puts it")
        void everySpanMatchesTheCopybook(String name, int offset, int length) {
            FieldSpan span = TranRecord.LAYOUT.span(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
        }

        @Test
        @DisplayName("the named offset and length constants agree with the span descriptors")
        void constantsAgreeWithDescriptors() {
            assertThat(TranRecord.TRAN_ID.offset()).isEqualTo(TranRecord.TRAN_ID_OFFSET);
            assertThat(TranRecord.TRAN_ID.length()).isEqualTo(TranRecord.TRAN_ID_LENGTH);
            assertThat(TranRecord.TRAN_CARD_NUM.offset()).isEqualTo(TranRecord.TRAN_CARD_NUM_OFFSET);
            assertThat(TranRecord.TRAN_CARD_NUM.length()).isEqualTo(TranRecord.TRAN_CARD_NUM_LENGTH);
            assertThat(TranRecord.FILLER.offset()).isEqualTo(TranRecord.FILLER_OFFSET);
            assertThat(TranRecord.FILLER.length()).isEqualTo(TranRecord.FILLER_LENGTH);
            assertThat(TranRecord.FILLER.kind().filler()).isTrue();
            assertThat(TranRecord.FILLER.name()).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("TRAN-AMT occupies p+s = 11 bytes, the sign being overpunched not stored")
        void signedSpanIsElevenBytes() {
            // PIC S9(09)V99 is nine integer digits plus two fraction digits and nothing else. The V is
            // an implied decimal point that occupies no byte, and the S is an operational sign
            // overpunched into the trailing byte rather than given a position of its own. Reading it as
            // 9, 10 or 12 shifts every field after offset 132.
            assertThat(TranRecord.TRAN_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranRecord.TRAN_AMT_SCALE).isEqualTo(2);
            assertThat(TranRecord.TRAN_AMT_LENGTH)
                    .isEqualTo(TranRecord.TRAN_AMT_INTEGER_DIGITS + TranRecord.TRAN_AMT_SCALE)
                    .isEqualTo(11);
            assertThat(TranRecord.TRAN_AMT.length()).isEqualTo(11);

            // And it is zoned DISPLAY, not packed. All 28 copybooks in app/cpy were searched: there is
            // not one COMP-3, PACKED-DECIMAL, USAGE COMP or SIGN clause among them, so no persisted
            // numeric span in this migration ever needs nibble unpacking. Asserting the width here is
            // what makes a later packed-decimal assumption - which would halve the span to six bytes -
            // fail loudly instead of quietly.
            assertThat(TranRecord.TRAN_AMT.length())
                    .as("a packed reading of S9(09)V99 would be 6 bytes; this field is zoned DISPLAY")
                    .isNotEqualTo(6);
            assertThat(TranRecord.decode(ROW_1, ASCII).tranAmtImage())
                    .as("the stored form is eleven printable characters, which is what zoned means")
                    .hasSize(11)
                    .isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("the monetary scale comes from CobolDecimal, so the policy lives in one place")
        void monetaryScaleIsCentralised() {
            assertThat(TranRecord.TRAN_AMT_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("TRAN-PROC-DT is the first ten bytes of TRAN-PROC-TS, corroborated three ways")
        void procDateSharesProcTimestampOffset() {
            // "TRAN-PROC-DT,305,10,CH" appears verbatim in three independent places:
            //   app/jcl/TRANREPT.jcl:42, app/proc/TRANREPT.prc:40 and app/cbl/CORPT00C.cbl:102
            // (the last as a PIC X(80) literal the online program writes into a submitted job). The
            // COBOL itself agrees a fourth time: CBTRN03C.cbl:173-174 filters on TRAN-PROC-TS (1:10),
            // the reference modification tranProcDt() reproduces.
            // 1-based 305 -> 0-based 304, which is exactly where TRAN-PROC-TS starts.
            assertThat(TranRecord.TRAN_PROC_DT_OFFSET).isEqualTo(TranRecord.TRAN_PROC_TS_OFFSET);
            assertThat(TranRecord.TRAN_PROC_DT_LENGTH).isEqualTo(10);
            assertThat(TranRecord.TRAN_PROC_DT_OFFSET).isEqualTo(305 - 1);

            // The date is a prefix of the timestamp, not a field of its own: it must not reach past it.
            assertThat(TranRecord.TRAN_PROC_DT_LENGTH)
                    .isLessThan(TranRecord.TRAN_PROC_TS_LENGTH);
            assertThat(TranRecord.LAYOUT.hasSpan("TRAN-PROC-DT"))
                    .as("TRAN-PROC-DT is an accessor over TRAN-PROC-TS, never a fifteenth span - "
                            + "declaring it would overlap and the layout self-check would reject it")
                    .isFalse();
        }

        @Test
        @DisplayName("the TRANSACT key is TRAN-ID in full, as CBTRN02C's FD split confirms")
        void keyLengthIsSixteen() {
            assertThat(TranRecord.TRAN_ID_KEY_LENGTH)
                    .isEqualTo(TranRecord.TRAN_ID_LENGTH)
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("TRAN-CARD-NUM sits at 1-based 263, corroborated three ways")
        void cardNumberOffsetMatchesTheSortSymbol() {
            // "TRAN-CARD-NUM,263,16,ZD" appears verbatim in app/jcl/TRANREPT.jcl:41,
            // app/proc/TRANREPT.prc:39 and app/cbl/CORPT00C.cbl:100, each against a LRECL=350 dataset.
            // 1-based 263 -> 0-based 262.
            assertThat(TranRecord.TRAN_CARD_NUM_OFFSET).isEqualTo(263 - 1);
            assertThat(TranRecord.TRAN_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(TranRecord.TRAN_CARD_NUM.offset()).isEqualTo(262);

            // The SORT calls it ZD while CVTRA05Y declares PIC X(16). Per the copybook-is-the-contract
            // rule the field stays alphanumeric; the discrepancy is recorded, not resolved. The proof
            // that it matters is fixture row 2, whose card number begins with a zero that a numeric
            // reading would discard.
            assertThat(TranRecord.TRAN_CARD_NUM.kind().numericDisplay())
                    .as("PIC X(16) in the copybook outranks the SORT symbol's ZD collation hint")
                    .isFalse();
            assertThat(TranRecord.decode(ROW_2, ASCII).tranCardNum()).startsWith("0");
        }

        @Test
        @DisplayName("the width self-check rejects a wrong descriptor set - the failure branch (G50)")
        void theWidthSelfCheckRejectsAWrongDescriptorSet() {
            // TranRecord.LAYOUT is built at class initialisation, so the passing side of this check has
            // already run by the time any test executes. The failing side has to be provoked
            // deliberately, and these are the two ways a transcription of CVTRA05Y actually goes wrong.

            // 1. A dropped trailing FILLER. The thirteen named spans sum to 330, and a codec built on
            //    them would produce plausible-looking 330-byte records that every consumer rejects.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(
                            TranRecord.RECORD_LENGTH,
                            TranRecord.TRAN_ID,
                            TranRecord.TRAN_TYPE_CD,
                            TranRecord.TRAN_CAT_CD,
                            TranRecord.TRAN_SOURCE,
                            TranRecord.TRAN_DESC,
                            TranRecord.TRAN_AMT,
                            TranRecord.TRAN_MERCHANT_ID,
                            TranRecord.TRAN_MERCHANT_NAME,
                            TranRecord.TRAN_MERCHANT_CITY,
                            TranRecord.TRAN_MERCHANT_ZIP,
                            TranRecord.TRAN_CARD_NUM,
                            TranRecord.TRAN_ORIG_TS,
                            TranRecord.TRAN_PROC_TS))
                    .withMessageContaining("330")
                    .withMessageContaining("FILLER");

            // 2. A sign byte reserved for TRAN-AMT. Reading PIC S9(09)V99 as ten integer digits plus a
            //    separate sign position makes the span 12 bytes rather than 11, because in zoned
            //    DISPLAY the sign is overpunched into the trailing byte and occupies no byte of its own.
            FieldSpan amountWithASignByte = FieldSpan.signedScaled(
                    "TRAN-AMT", TranRecord.TRAN_AMT_OFFSET, 10, TranRecord.TRAN_AMT_SCALE);
            assertThat(amountWithASignByte.length()).isEqualTo(12);

            //    Left in place, the wider span runs one byte into TRAN-MERCHANT-ID and the geometry
            //    check catches the collision straight away.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(
                            TranRecord.RECORD_LENGTH,
                            TranRecord.TRAN_ID,
                            TranRecord.TRAN_TYPE_CD,
                            TranRecord.TRAN_CAT_CD,
                            TranRecord.TRAN_SOURCE,
                            TranRecord.TRAN_DESC,
                            amountWithASignByte,
                            TranRecord.TRAN_MERCHANT_ID,
                            TranRecord.TRAN_MERCHANT_NAME,
                            TranRecord.TRAN_MERCHANT_CITY,
                            TranRecord.TRAN_MERCHANT_ZIP,
                            TranRecord.TRAN_CARD_NUM,
                            TranRecord.TRAN_ORIG_TS,
                            TranRecord.TRAN_PROC_TS,
                            TranRecord.FILLER))
                    .withMessageContaining("overlap")
                    .withMessageContaining("TRAN-MERCHANT-ID");

            //    Shift everything after it along by one - which is what a transcriber who believed in a
            //    sign byte would actually do - and the collision disappears while the record silently
            //    becomes 351 bytes. That is the case the total-width check exists for, and its message
            //    names the cause.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(
                            TranRecord.RECORD_LENGTH,
                            TranRecord.TRAN_ID,
                            TranRecord.TRAN_TYPE_CD,
                            TranRecord.TRAN_CAT_CD,
                            TranRecord.TRAN_SOURCE,
                            TranRecord.TRAN_DESC,
                            amountWithASignByte,
                            FieldSpan.unsignedNumeric("TRAN-MERCHANT-ID", 144, 9),
                            FieldSpan.alphanumeric("TRAN-MERCHANT-NAME", 153, 50),
                            FieldSpan.alphanumeric("TRAN-MERCHANT-CITY", 203, 50),
                            FieldSpan.alphanumeric("TRAN-MERCHANT-ZIP", 253, 10),
                            FieldSpan.alphanumeric("TRAN-CARD-NUM", 263, 16),
                            FieldSpan.alphanumeric("TRAN-ORIG-TS", 279, 26),
                            FieldSpan.alphanumeric("TRAN-PROC-TS", 305, 26),
                            FieldSpan.filler(331, 20)))
                    .withMessageContaining("351")
                    .withMessageContaining("sign");

            // The real layout, by contrast, is accepted - so the check discriminates rather than always
            // objecting.
            assertThat(RecordLayout.of(TranRecord.RECORD_LENGTH,
                    TranRecord.LAYOUT.spans().toArray(new FieldSpan[0])))
                    .isEqualTo(TranRecord.LAYOUT);
        }

        @Test
        @DisplayName("each field's Java form follows its PICTURE, not what its contents look like")
        void javaTypesFollowThePictureClauses() {
            // TRAN-TYPE-CD holds '01' and '03' in the fixture, so an int is the tempting choice - and
            // wrong: CVTRA05Y declares PIC X(02), which makes the leading zero a stored character.
            // TRAN-CAT-CD next door is PIC 9(04) and genuinely numeric. Asserting the declared return
            // types keeps a later "tidy-up" from swapping either one.
            assertThat(returnTypeOf("tranTypeCd")).isEqualTo(String.class);
            assertThat(returnTypeOf("tranCatCd")).isEqualTo(int.class);
            assertThat(returnTypeOf("tranCardNum")).isEqualTo(String.class);
            assertThat(returnTypeOf("tranId")).isEqualTo(String.class);
            assertThat(returnTypeOf("tranProcTs")).isEqualTo(String.class);
            assertThat(returnTypeOf("tranProcDt")).isEqualTo(String.class);
            assertThat(returnTypeOf("filler")).isEqualTo(String.class);

            // PIC S9(09)V99 is money: BigDecimal, never a binary floating point type (R4, G22).
            assertThat(returnTypeOf("tranAmt")).isEqualTo(BigDecimal.class);
            assertThat(returnTypeOf("tranAmtImage")).isEqualTo(String.class);
        }

        private Class<?> returnTypeOf(String methodName) {
            for (Method method : TranRecord.class.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
                    return method.getReturnType();
                }
            }
            throw new AssertionError("TranRecord declares no no-argument accessor named " + methodName);
        }
    }

    @Nested
    @DisplayName("an initialised area")
    class InitialisedArea {

        @Test
        @DisplayName("is 350 bytes: text blank, unsigned numerics zero-filled, TRAN-AMT a signed zero")
        void newRecordIsInitialisedPerPicture() {
            TranRecord record = new TranRecord(ASCII);

            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.tranId()).isEqualTo(" ".repeat(16));
            assertThat(record.tranDesc()).isEqualTo(" ".repeat(100));
            assertThat(record.tranCatCdImage()).isEqualTo("0000");
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.tranAmtImage())
                    .as("TRAN-AMT is S9(09)V99: ten zoned zeros and a positive-zero overpunch, which "
                            + "is how a zero-valued signed field is stored in app/data/ASCII")
                    .isEqualTo("0".repeat(10) + "{");
            assertThat(record.tranAmt()).isEqualByComparingTo("0.00");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("initialises identically under EBCDIC, using that code page's own pad bytes")
        void newRecordHonoursTheNamedCodePage() {
            TranRecord record = new TranRecord(EBCDIC);

            assertThat(record.charset()).isEqualTo(EBCDIC);
            assertThat(record.tranCatCdImage()).isEqualTo("0000");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            // EBCDIC space is 0x40, not 0x20 - proof the pad byte came from the named charset.
            assertThat(record.rawImage()[TranRecord.FILLER_OFFSET]).isEqualTo((byte) 0x40);
        }
    }

    @Nested
    @DisplayName("decoding a fixture row")
    class FixtureDecode {

        @Test
        @DisplayName("all three embedded rows are exactly 350 characters")
        void embeddedRowsAreWellFormed() {
            // Guards the literals themselves. Each is assembled from its fourteen field images, so a
            // mistyped pad count would otherwise surface as a confusing decode failure elsewhere.
            assertThat(ROW_1).hasSize(350);
            assertThat(ROW_2).hasSize(350);
            assertThat(ROW_7).hasSize(350);
        }

        @Test
        @DisplayName("row 1 decodes field for field through the copybook offsets")
        void rowOneDecodesFieldForField() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.tranId()).isEqualTo("0000000000683580");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.tranCatCdImage()).isEqualTo("0001");
            assertThat(record.tranSource()).isEqualTo("POS TERM  ");
            // The full hundred characters, not a prefix: the pad is as much part of the record as the
            // text, and asserting the exact value is what proves nothing trimmed it on the way in.
            assertThat(record.tranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76))
                    .hasSize(100);
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.tranAmt()).isEqualByComparingTo("504.77");
            assertThat(record.tranMerchantId()).isEqualTo(800000000);
            assertThat(record.tranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.tranMerchantName()).startsWith("Abshire-Lowe").hasSize(50);
            assertThat(record.tranMerchantCity()).startsWith("North Enoshaven").hasSize(50);
            assertThat(record.tranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.tranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("row 2 decodes a negative amount whose final digit is zero - the '}' case")
        void rowTwoDecodesTheNegativeZeroDigitOverpunch() {
            TranRecord record = TranRecord.decode(ROW_2, ASCII);

            assertThat(record.tranId()).isEqualTo("0000000001774260");
            assertThat(record.tranTypeCd()).isEqualTo("03");
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.tranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.tranDesc())
                    .isEqualTo("Return item at Nitzsche, Nicolas and Lowe" + " ".repeat(59));

            // '}' means "negative, final digit 0". The magnitude is 919.00, so the value is -919.00 -
            // emphatically not zero, and not +919.00 either.
            assertThat(record.tranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.tranAmt()).isEqualByComparingTo("-919.00");
            assertThat(record.tranAmt().scale()).isEqualTo(2);
            assertThat(record.tranAmt().signum()).isNegative();
            assertThat(record.hasZeroTranAmt()).isFalse();

            assertThat(record.tranMerchantId()).isEqualTo(800000000);
            assertThat(record.tranMerchantName())
                    .isEqualTo("Nitzsche, Nicolas and Lowe" + " ".repeat(24));
            assertThat(record.tranMerchantCity()).isEqualTo("Fidelshire" + " ".repeat(40));
            assertThat(record.tranMerchantZip()).isEqualTo("53378     ");
            // A leading zero that only survives because the field is PIC X(16), not the SORT's ZD.
            assertThat(record.tranCardNum()).isEqualTo("0927987108636232");
            assertThat(record.tranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.tranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("row 7 decodes a negative amount from its J-to-R overpunch")
        void rowSevenDecodesANegativeAmount() {
            TranRecord record = TranRecord.decode(ROW_7, ASCII);

            assertThat(record.tranAmtImage()).isEqualTo("0000000567P");
            assertThat(record.tranAmt()).isEqualByComparingTo("-56.77");
            assertThat(record.tranAmt().signum()).isNegative();
            assertThat(record.tranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.tranCardNum()).isEqualTo("4011500891777367");
        }

        // Columns are name|offset|length|exact bytes, and whitespace is significant: TRAN-SOURCE and
        // TRAN-MERCHANT-ZIP carry trailing pad that is part of the stored value, so the CSV must not be
        // trimmed and the rows are therefore written without decorative alignment.
        @ParameterizedTest(name = "bytes {1}..{2} of the image are {0}")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
                // Every row states the same thing in the only form that actually proves an offset: the
                // 350-byte image, at absolute offset O for length L, holds exactly these bytes. The
                // accessor is then checked against the same slice, so an accessor reading the wrong
                // offset cannot agree with it. Offsets accumulate the copybook's PICTURE widths:
                // 0+16=16, +2=18, +4=22, +10=32, +100=132, +11=143, +9=152, +50=202, +50=252, +10=262,
                // +16=278, +26=304, +26=330, +20=350.
                "TRAN-ID|0|16|0000000000683580",
                "TRAN-TYPE-CD|16|2|01",
                "TRAN-CAT-CD|18|4|0001",
                "TRAN-SOURCE|22|10|POS TERM  ",
                "TRAN-AMT|132|11|0000005047G",
                "TRAN-MERCHANT-ID|143|9|800000000",
                "TRAN-MERCHANT-ZIP|252|10|72112     ",
                "TRAN-CARD-NUM|262|16|4859452612877065",
                "TRAN-ORIG-TS|278|26|2022-06-10 19:27:53.000000",
        })
        @DisplayName("every field is at its absolute offset in the 350-byte image (R5)")
        void everyFieldSitsAtItsAbsoluteOffset(String name, int offset, int length, String expected) {
            byte[] image = TranRecord.decode(ROW_1, ASCII).rawImage();

            assertThat(image).hasSize(350);
            assertThat(expected).as("the expected value of %s is %d bytes wide", name, length)
                    .hasSize(length);

            byte[] slice = new byte[length];
            System.arraycopy(image, offset, slice, 0, length);
            assertThat(new String(slice, ASCII)).as("%s at 0-based %d", name, offset)
                    .isEqualTo(expected);

            // The same bytes reached through the declared descriptor, which is what every consumer uses.
            FieldSpan span = TranRecord.LAYOUT.span(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(TranRecord.decode(ROW_1, ASCII).rawSpan(span)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the wide spans and the FILLER sit at their absolute offsets too (R5)")
        void theWideSpansSitAtTheirAbsoluteOffsets() {
            // Kept out of the table above only because a 100-byte and two 50-byte literals do not fit a
            // CSV row legibly. The assertion form is identical.
            byte[] image = TranRecord.decode(ROW_1, ASCII).rawImage();

            assertThat(sliceOf(image, TranRecord.TRAN_DESC_OFFSET, TranRecord.TRAN_DESC_LENGTH))
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76));
            assertThat(sliceOf(image, TranRecord.TRAN_MERCHANT_NAME_OFFSET,
                    TranRecord.TRAN_MERCHANT_NAME_LENGTH))
                    .isEqualTo("Abshire-Lowe" + " ".repeat(38));
            assertThat(sliceOf(image, TranRecord.TRAN_MERCHANT_CITY_OFFSET,
                    TranRecord.TRAN_MERCHANT_CITY_LENGTH))
                    .isEqualTo("North Enoshaven" + " ".repeat(35));
            assertThat(sliceOf(image, TranRecord.TRAN_PROC_TS_OFFSET,
                    TranRecord.TRAN_PROC_TS_LENGTH))
                    .isEqualTo(" ".repeat(26));
            assertThat(sliceOf(image, TranRecord.TRAN_PROC_DT_OFFSET,
                    TranRecord.TRAN_PROC_DT_LENGTH))
                    .isEqualTo(" ".repeat(10));
            assertThat(sliceOf(image, TranRecord.FILLER_OFFSET, TranRecord.FILLER_LENGTH))
                    .isEqualTo(" ".repeat(20));

            // The last declared byte is the record's last byte: 330 + 20 = 350, nothing beyond.
            assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH).isEqualTo(image.length);
        }

        private String sliceOf(byte[] image, int offset, int length) {
            byte[] slice = new byte[length];
            System.arraycopy(image, offset, slice, 0, length);
            return new String(slice, ASCII);
        }

        @Test
        @DisplayName("TRAN-AMT is decoded at scale exactly 2, never as a binary float")
        void amountCarriesTheDeclaredScale() {
            BigDecimal amount = TranRecord.decode(ROW_1, ASCII).tranAmt();

            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount).isEqualTo(new BigDecimal("504.77"));
        }

        @Test
        @DisplayName("character fields keep their significant trailing spaces")
        void characterFieldsAreNotTrimmed() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            // TRAN-SOURCE really is "POS TERM" followed by two spaces in the fixture; trimming it
            // would change both the record width on write-back and the report column alignment.
            assertThat(record.tranSource()).isEqualTo("POS TERM  ").endsWith("  ");
            assertThat(record.tranMerchantZip()).endsWith("     ");
            assertThat(record.tranDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("the FILLER span is present, twenty bytes wide and space-filled")
        void fillerIsPresentAndFilled() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.filler()).isEqualTo(" ".repeat(20)).hasSize(20);
            assertThat(record.rawSpanBytes(TranRecord.FILLER)).hasSize(20);
            // If FILLER were omitted the record would be 330 bytes and LAYOUT could not have been built.
            assertThat(record.rawImage()).hasSize(350);
        }

        @Test
        @DisplayName("TRAN-PROC-DT over an unprocessed row is ten spaces, not an empty string")
        void procDateOverAnUnprocessedRowIsBlank() {
            // TRAN-PROC-TS is blank in every one of the 300 fixture rows: the daily file holds
            // transactions that have not been posted yet, and CBTRN02C:438 is what fills the field in.
            // CBTRN03C:173-174 nonetheless compares TRAN-PROC-TS (1:10) against the report date range,
            // so a blank slice is a real, reachable input and must read back as ten spaces rather than
            // as "" or null - a trimming accessor would silently make the range test pass.
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.tranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.tranProcDt())
                    .isEqualTo(" ".repeat(10))
                    .isEqualTo(record.tranProcTs().substring(0, 10))
                    .hasSize(10);
            assertThat(record.tranProcDt().compareTo("2022-01-01"))
                    .as("a blank date sorts below any real one, so the range filter excludes the row")
                    .isNegative();
        }

        @Test
        @DisplayName("TRAN-PROC-DT is the (1:10) reference-modified slice of TRAN-PROC-TS")
        void procDateIsTheFirstTenCharacters() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);
            record.moveTranProcTs("2022-07-18 00:00:00.000000");

            // Two accessors, one span (G34): the date is a view over the timestamp's first ten bytes,
            // never a copy, so writing the timestamp is what changes the date.
            assertThat(record.tranProcDt())
                    .isEqualTo("2022-07-18")
                    .isEqualTo(record.tranProcTs().substring(0, 10))
                    .hasSize(10);
            assertThat(record.rawSpan(TranRecord.TRAN_PROC_TS))
                    .startsWith(record.tranProcDt());

            // And the same span read the other way round: overwriting only the date's ten bytes leaves
            // the rest of the timestamp exactly as it was.
            record.stringIntoTranProcTs("2022-07-19");
            assertThat(record.tranProcDt()).isEqualTo("2022-07-19");
            assertThat(record.tranProcTs()).isEqualTo("2022-07-19 00:00:00.000000");
        }

        @Test
        @DisplayName("displayImage reproduces DISPLAY TRAN-RECORD exactly")
        void displayImageIsTheWholeRecordAsText() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.displayImage()).hasSize(350).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("a short or over-long row is rejected rather than silently reshaping the record")
        void wrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1.substring(0, 349), ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1 + " ", ASCII));
        }
    }

    @Nested
    @DisplayName("the FILLER span, on the write path and on the read path")
    class FillerHandling {

        /**
         * Row 1 with its twenty {@code FILLER} bytes replaced by ASCII zeros. Not invented: the other
         * fixtures in {@code app/data/ASCII} really do fill their trailing {@code FILLER} that way -
         * {@code tcatbal.txt} fills {@code CVTRA01Y}'s {@code FILLER X(22)} with zeros in all 50 rows,
         * {@code trantype.txt} fills {@code CVTRA03Y}'s {@code X(08)} with zeros in all 7, and
         * {@code trancatg.txt} fills {@code CVTRA04Y}'s {@code X(04)} with zeros in all 18. Only
         * {@code dailytran.txt} uses spaces. Reserved bytes are whatever the producer left there.
         */
        private static final String ROW_1_WITH_ZERO_FILLER =
                ROW_1.substring(0, TranRecord.FILLER_OFFSET) + "0".repeat(20);

        @Test
        @DisplayName("a freshly allocated record space-fills FILLER, as INITIALIZE would (G21)")
        void aFreshRecordSpaceFillsFiller() {
            // The write-path obligation. Nothing has been read, so there is nothing to preserve and the
            // only defensible initial value is the one COBOL gives a PIC X item: spaces.
            TranRecord record = new TranRecord(ASCII);

            assertThat(record.filler()).isEqualTo(" ".repeat(20)).hasSize(20);
            assertThat(record.rawSpan(TranRecord.FILLER)).isEqualTo(" ".repeat(20));
            for (int offset = TranRecord.FILLER_OFFSET; offset < TranRecord.RECORD_LENGTH; offset++) {
                assertThat(record.rawImage()[offset])
                        .as("byte %d of a fresh record", offset)
                        .isEqualTo((byte) ' ');
            }
            // And the width is what fails first if FILLER is ever dropped from the layout.
            assertThat(record.rawImage()).hasSize(350);
        }

        @Test
        @DisplayName("a decoded record hands back the FILLER bytes it was given, whatever they were")
        void aDecodedRecordPreservesForeignFillerBytes() {
            // The read-path obligation, and it does not contradict the one above: initialising a new
            // area and reproducing a stored one are different jobs. Blanking a stored FILLER on the way
            // through would rewrite twenty bytes the record does not own, and a byte-level comparison
            // against the source dataset would then fail for a record whose every named field is right.
            TranRecord record = TranRecord.decode(ROW_1_WITH_ZERO_FILLER, ASCII);

            assertThat(record.filler()).isEqualTo("0".repeat(20)).isNotEqualTo(" ".repeat(20));
            assertThat(record.rawSpanBytes(TranRecord.FILLER))
                    .isEqualTo("0".repeat(20).getBytes(ASCII));
            // Every named field still decodes exactly as it does from the space-filled row.
            assertThat(record.tranId()).isEqualTo("0000000000683580");
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("re-encoding a decoded record leaves its FILLER bytes untouched")
        void reEncodingPreservesTheOriginalFillerBytes() {
            TranRecord record = TranRecord.decode(ROW_1_WITH_ZERO_FILLER, ASCII);

            assertThat(record.encode(ASCII)).isEqualTo(ROW_1_WITH_ZERO_FILLER.getBytes(ASCII));
            assertThat(record.rawImage()).isEqualTo(ROW_1_WITH_ZERO_FILLER.getBytes(ASCII));
            assertThat(record.displayImage()).isEqualTo(ROW_1_WITH_ZERO_FILLER);
            assertThat(record.copy().displayImage()).isEqualTo(ROW_1_WITH_ZERO_FILLER);

            // Writing to named fields does not disturb the reserved span either: CBACT04C touches
            // thirteen fields in 1300-B-WRITE-TX and never the FILLER.
            record.moveTranTypeCd("01");
            record.moveTranAmt(new BigDecimal("504.77"));
            record.stringIntoTranDesc("Int. for a/c ", "00000000011");
            assertThat(record.filler()).isEqualTo("0".repeat(20));
            assertThat(record.rawImage()).hasSize(350);
        }

        @Test
        @DisplayName("FILLER is a declared span, so it is emitted rather than merely tolerated")
        void fillerIsADeclaredSpanNotAnAfterthought() {
            // 330 + 20 = 350. Without the declaration the record is 330 bytes, every consumer's
            // subsequent read is off by twenty, and TranRecord.LAYOUT could not have been built at all.
            assertThat(TranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(TranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH)
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.LAYOUT.storageSpans())
                    .last()
                    .isEqualTo(TranRecord.FILLER);
            assertThat(TranRecord.FILLER.kind().filler()).isTrue();
        }
    }

    @Nested
    @DisplayName("zoned sign overpunch")
    class SignOverpunch {

        @ParameterizedTest(name = "{0} decodes to {1}")
        @CsvSource({
                // Zone C - positive, final digit 0 to 9. All ten characters occur in
                // app/data/ASCII/dailytran.txt: '{' 25 times, then A-I 28, 29, 30, 29, 23, 21, 24, 17
                // and 24 times, 250 rows in total.
                "0000000000{, 0.00",
                "0000000000A, 0.01",
                "0000000000B, 0.02",
                "0000000000C, 0.03",
                "0000000000D, 0.04",
                "0000000000E, 0.05",
                "0000000000F, 0.06",
                "0000000000G, 0.07",
                "0000000000H, 0.08",
                "0000000000I, 0.09",
                // Zone D - negative, final digit 0 to 9. Also all ten: '}' 6 times, then J-R 3, 5, 5,
                // 6, 2, 4, 7, 4 and 8 times, 50 rows in total. 250 + 50 = 300, the whole fixture.
                "0000000000}, 0.00",
                "0000000000J, -0.01",
                "0000000000K, -0.02",
                "0000000000L, -0.03",
                "0000000000M, -0.04",
                "0000000000N, -0.05",
                "0000000000O, -0.06",
                "0000000000P, -0.07",
                "0000000000Q, -0.08",
                "0000000000R, -0.09",
                // Real images lifted from the fixture rows embedded above.
                "0000005047G, 504.77",
                "0000000567P, -56.77",
                "0000009190}, -919.00",
                // Zone F - a plain trailing digit, which is what a field written by an unsigned sender
                // looks like. COBOL reads it as positive.
                "00000000005, 0.05",
                "00000504770, 5047.70",
        })
        @DisplayName("every legal overpunch character decodes to the value COBOL stores")
        void overpunchDecodesCorrectly(String image, String expected) {
            // The expected pairs are written out as literals rather than computed from the codec's own
            // sign table, so this asserts the contract instead of restating the implementation.
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(image);

            assertThat(record.tranAmtImage()).isEqualTo(image);
            assertThat(record.tranAmt()).isEqualByComparingTo(expected);
            assertThat(record.tranAmt().scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "{0} encodes to {1}")
        @CsvSource({
                // The same twenty characters in the other direction. Both halves matter: the trailing
                // byte carries the sign as well as the digit, so an encoder that chose the character
                // from the digit alone would pass the positive half and corrupt the negative one.
                "0.00,     0000000000{",
                "0.01,     0000000000A",
                "0.02,     0000000000B",
                "0.03,     0000000000C",
                "0.04,     0000000000D",
                "0.05,     0000000000E",
                "0.06,     0000000000F",
                "0.07,     0000000000G",
                "0.08,     0000000000H",
                "0.09,     0000000000I",
                "-0.10,    0000000001}",
                "-0.01,    0000000000J",
                "-0.02,    0000000000K",
                "-0.03,    0000000000L",
                "-0.04,    0000000000M",
                "-0.05,    0000000000N",
                "-0.06,    0000000000O",
                "-0.07,    0000000000P",
                "-0.08,    0000000000Q",
                "-0.09,    0000000000R",
                // The two load-bearing cases, stated as whole amounts rather than fractions of a cent.
                // A positive value whose last digit is zero must produce '{', never the character '0';
                // the matching negative must produce '}', never '{'.
                "919.00,   0000009190{",
                "-919.00,  0000009190}",
                "504.77,   0000005047G",
                "-56.77,   0000000567P",
        })
        @DisplayName("every value encodes to the character COBOL would have stored")
        void overpunchEncodesCorrectly(String value, String expectedImage) {
            TranRecord record = new TranRecord(ASCII);

            record.moveTranAmt(new BigDecimal(value));

            assertThat(record.tranAmtImage()).isEqualTo(expectedImage).hasSize(11);
            // And straight back again, which is the round trip that proves the pair of tables agree.
            assertThat(record.tranAmt()).isEqualByComparingTo(value);
        }

        @Test
        @DisplayName("a final digit of zero picks its character from the SIGN, not from the digit")
        void finalZeroDigitTakesItsCharacterFromTheSign() {
            // Written out separately from the table above because it is the single most damaging way to
            // get zoned decimal wrong: map "digit 0" to '{' unconditionally and every negative amount
            // ending in a whole ten silently becomes positive. The fixture contains six such rows.
            TranRecord positive = new TranRecord(ASCII);
            positive.moveTranAmt(new BigDecimal("919.00"));
            assertThat(positive.tranAmtImage())
                    .isEqualTo("0000009190{")
                    .doesNotEndWith("0")
                    .endsWith("{");

            TranRecord negative = new TranRecord(ASCII);
            negative.moveTranAmt(new BigDecimal("-919.00"));
            assertThat(negative.tranAmtImage())
                    .isEqualTo("0000009190}")
                    .endsWith("}");

            // Same ten leading characters, one differing byte, opposite signs.
            assertThat(positive.tranAmtImage().substring(0, 10))
                    .isEqualTo(negative.tranAmtImage().substring(0, 10));
            assertThat(positive.tranAmt().negate()).isEqualByComparingTo(negative.tranAmt());
        }

        @ParameterizedTest(name = "{0} round-trips unchanged")
        @ValueSource(strings = {
                // The six '}' rows of dailytran.txt. Each is an ordinary negative amount whose final
                // digit happens to be zero, so a numeric round-trip is faithful for all of them.
                "0000009190}", "0000002430}", "0000007630}",
                "0000009070}", "0000003720}", "0000004350}",
        })
        @DisplayName("a '}' carrying a non-zero magnitude survives a numeric round-trip")
        void negativeFinalZeroDigitRoundTrips(String image) {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(image);

            BigDecimal decoded = record.tranAmt();
            assertThat(decoded.signum()).isNegative();

            record.moveTranAmt(decoded);
            assertThat(record.tranAmtImage()).isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("the negative-zero re-encode hazard")
    class NegativeZeroHazard {

        private static final String NEGATIVE_ZERO = "0000000000}";
        private static final String POSITIVE_ZERO = "0000000000{";

        @Test
        @DisplayName("a whole-value negative zero decodes to plain zero, BigDecimal having no signed zero")
        void negativeZeroDecodesToZero() {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(record.tranAmt()).isEqualByComparingTo("0.00");
            assertThat(record.tranAmt().signum()).isZero();
            assertThat(record.hasZeroTranAmt()).isTrue();
        }

        @Test
        @DisplayName("a numeric round-trip normalises it to a positive zero - the one lossy path")
        void numericRoundTripLosesTheSign() {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(NEGATIVE_ZERO);

            record.moveTranAmt(record.tranAmt());

            assertThat(record.tranAmtImage())
                    .isEqualTo(POSITIVE_ZERO)
                    .isNotEqualTo(NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("the raw-image path copies it byte for byte, which is why it must be used")
        void rawCopyPreservesIt() {
            TranRecord original = TranRecord.decode(ROW_1, ASCII);
            original.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(original.copy().rawImage()).isEqualTo(original.rawImage());
            assertThat(original.encode(ASCII)).isEqualTo(original.rawImage());
            assertThat(original.copy().tranAmtImage()).isEqualTo(NEGATIVE_ZERO);
            assertThat(TranRecord.decode(original.rawImage(), ASCII).tranAmtImage())
                    .isEqualTo(NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("copying the raw amount span preserves it where a numeric copy would not")
        void spanCopyPreservesItAcrossRecords() {
            TranRecord source = new TranRecord(ASCII);
            source.writeTranAmtImage(NEGATIVE_ZERO);

            TranRecord viaImage = new TranRecord(ASCII);
            viaImage.writeTranAmtImage(source.tranAmtImage());
            assertThat(viaImage.tranAmtImage()).isEqualTo(NEGATIVE_ZERO);

            TranRecord viaValue = new TranRecord(ASCII);
            viaValue.moveTranAmt(source.tranAmt());
            assertThat(viaValue.tranAmtImage()).isEqualTo(POSITIVE_ZERO);
        }

        @Test
        @DisplayName("a positive zero and a negative zero are equal in value but different records")
        void equalValueDifferentRecord() {
            TranRecord positive = new TranRecord(ASCII);
            positive.writeTranAmtImage(POSITIVE_ZERO);
            TranRecord negative = new TranRecord(ASCII);
            negative.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(positive.tranAmt()).isEqualByComparingTo(negative.tranAmt());
            assertThat(positive.hasZeroTranAmt()).isTrue();
            assertThat(negative.hasZeroTranAmt()).isTrue();
            assertThat(positive).isNotEqualTo(negative);
        }

        @Test
        @DisplayName("hasZeroTranAmt is false for a non-zero amount and uses compareTo not equals")
        void zeroPredicateDistinguishesValueFromScale() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.hasZeroTranAmt()).isFalse();
            // 0.00 and 0 differ under BigDecimal.equals but not under compareTo; the predicate must
            // agree with COBOL's numeric comparison, so it uses compareTo.
            assertThat(CobolDecimal.monetaryZero()).isNotEqualTo(BigDecimal.ZERO);
            assertThat(CobolDecimal.monetaryZero()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("encode and copy")
    class EncodeAndCopy {

        @Test
        @DisplayName("decode then encode is byte-identical for all three fixture rows")
        void roundTripIsByteIdentical() {
            // Row 2 is the one that earns its place here: its amount image ends in '}', so a codec that
            // reconstructed the trailing byte from the digit alone would return 0000009190{ and turn
            // -919.00 into +919.00 while every other byte of the record matched.
            assertThat(TranRecord.decode(ROW_1, ASCII).encode(ASCII))
                    .isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(TranRecord.decode(ROW_2, ASCII).encode(ASCII))
                    .isEqualTo(ROW_2.getBytes(ASCII));
            assertThat(TranRecord.decode(ROW_7, ASCII).encode(ASCII))
                    .isEqualTo(ROW_7.getBytes(ASCII));

            // Also as text, which is the form DISPLAY TRAN-RECORD (CBTRN03C.cbl:180) writes.
            assertThat(TranRecord.decode(ROW_2, ASCII).displayImage()).isEqualTo(ROW_2);
        }

        @Test
        @DisplayName("encoding under the record's own charset returns the backing bytes verbatim")
        void sameCharsetReturnsBackingBytes() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }

        @Test
        @DisplayName("encoding under another charset transcodes yet preserves every field and the sign")
        void differentCharsetTranscodes() {
            TranRecord ascii = TranRecord.decode(ROW_1, ASCII);
            ascii.writeTranAmtImage("0000000000}");

            byte[] ebcdic = ascii.encode(EBCDIC);

            assertThat(ebcdic).hasSize(350).isNotEqualTo(ascii.rawImage());

            TranRecord reread = TranRecord.decode(ebcdic, EBCDIC);
            assertThat(reread.tranId()).isEqualTo(ascii.tranId());
            assertThat(reread.tranCardNum()).isEqualTo(ascii.tranCardNum());
            assertThat(reread.tranDesc()).isEqualTo(ascii.tranDesc());
            assertThat(reread.tranCatCdImage()).isEqualTo(ascii.tranCatCdImage());
            assertThat(reread.filler()).isEqualTo(ascii.filler());
            // The character-level transcode keeps even a negative zero, which a numeric one would not.
            assertThat(reread.tranAmtImage()).isEqualTo("0000000000}");
        }

        @Test
        @DisplayName("rawImage hands out a copy, so a caller cannot reach into the record")
        void rawImageIsDefensivelyCopied() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            byte[] image = record.rawImage();
            image[0] = (byte) 'Z';

            assertThat(record.tranId()).isEqualTo("0000000000683580");
            assertThat(record.rawImage()).isNotEqualTo(image);
        }

        @Test
        @DisplayName("a copy is independent of its original")
        void copyIsIndependent() {
            TranRecord original = TranRecord.decode(ROW_1, ASCII);
            TranRecord duplicate = original.copy();

            assertThat(duplicate).isEqualTo(original);
            assertThat(duplicate.charset()).isEqualTo(original.charset());

            duplicate.moveTranCardNum("9999999999999999");

            assertThat(original.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(duplicate.tranCardNum()).isEqualTo("9999999999999999");
            assertThat(duplicate).isNotEqualTo(original);
        }

        @Test
        @DisplayName("decoding from bytes and from text give the same record")
        void byteAndTextEntryPointsAgree() {
            assertThat(TranRecord.decode(ROW_1.getBytes(ASCII), ASCII))
                    .isEqualTo(TranRecord.decode(ROW_1, ASCII));
        }
    }

    @Nested
    @DisplayName("MOVE semantics")
    class MoveSemantics {

        @Test
        @DisplayName("a PIC X receiver is filled from the left and padded on the right")
        void picXPadsOnTheRight() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranSource("System");

            assertThat(record.tranSource()).isEqualTo("System    ").hasSize(10);
        }

        @Test
        @DisplayName("a PIC X receiver truncates on the RIGHT, discarding the tail not the head")
        void picXTruncatesOnTheRight() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranTypeCd("ABCD");

            assertThat(record.tranTypeCd()).isEqualTo("AB").isNotEqualTo("CD");
        }

        @Test
        @DisplayName("a MOVE blanks the whole receiver first, unlike STRING ... INTO")
        void moveBlanksTheReceiver() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranDesc("X".repeat(100));

            record.moveTranDesc("Int. for a/c 00000000011");

            assertThat(record.tranDesc())
                    .isEqualTo("Int. for a/c 00000000011" + " ".repeat(76))
                    .doesNotContain("X");
        }

        @Test
        @DisplayName("MOVE SPACES blanks the merchant text fields, as CBACT04C:492-494 does")
        void moveSpacesBlanksTextFields() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            record.moveTranMerchantName("");
            record.moveTranMerchantCity("");
            record.moveTranMerchantZip("");

            assertThat(record.tranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.tranMerchantCity()).isEqualTo(" ".repeat(50));
            assertThat(record.tranMerchantZip()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("a PIC 9 receiver zero-fills on the left, as MOVE 0 TO TRAN-MERCHANT-ID does")
        void pic9ZeroFillsOnTheLeft() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranMerchantId(0L);
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");

            record.moveTranMerchantId(800000000L);
            assertThat(record.tranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.tranMerchantId()).isEqualTo(800000000);

            record.moveTranCatCd(5);
            assertThat(record.tranCatCdImage()).isEqualTo("0005");
            assertThat(record.tranCatCd()).isEqualTo(5);
        }

        @Test
        @DisplayName("a PIC 9 receiver truncates on the LEFT, keeping the low-order digits")
        void pic9TruncatesOnTheLeft() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranCatCd(123456);

            assertThat(record.tranCatCdImage()).isEqualTo("3456").isNotEqualTo("1234");
        }

        @Test
        @DisplayName("MOVE '05' TO TRAN-CAT-CD stores 0005, not 0500 - the IBM alphanumeric-sender rule")
        void alphanumericLiteralIntoNumericReceiverIsDecimalAligned() {
            TranRecord record = new TranRecord(ASCII);

            // CBACT04C.cbl:483. The receiver is PIC 9(04), so IBM Enterprise COBOL treats the
            // alphanumeric sender as an unsigned integer and performs a numeric move: aligned on the
            // implied decimal point and zero-filled on the left.
            record.moveTranCatCd("05");

            assertThat(record.tranCatCdImage()).isEqualTo("0005").isNotEqualTo("0500");
            assertThat(record.tranCatCd()).isEqualTo(5);
        }

        @Test
        @DisplayName("an alphanumeric sender into a PIC 9 receiver must be all digits")
        void alphanumericLiteralMustBeNumeric() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranCatCd("A5"));
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranMerchantId("00 00"));
        }

        @Test
        @DisplayName("an unsigned PIC 9 receiver rejects a negative sender, having no room for a sign")
        void unsignedReceiverRejectsNegative() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranCatCd(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranMerchantId(-1L));
        }

        @Test
        @DisplayName("an alphanumeric merchant id is zero-filled on the left too")
        void alphanumericMerchantIdIsZeroFilled() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranMerchantId("42");

            assertThat(record.tranMerchantIdImage()).isEqualTo("000000042");
            assertThat(record.tranMerchantId()).isEqualTo(42);
        }

        @Test
        @DisplayName("TRAN-AMT truncates excess fraction digits toward zero, never rounding (R2, G24)")
        void amountTruncatesRatherThanRounds() {
            TranRecord record = new TranRecord(ASCII);

            // The ROUNDED phrase appears zero times across all 28 COBOL programs, so a store into a
            // scale-2 receiver discards the excess digits rather than rounding them. The policy has one
            // home - CobolDecimal - and TRAN-AMT routes through it, which is what this binds.
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(TranRecord.TRAN_AMT_SCALE);

            // A rounding-half policy of either flavour would give 1.24 and -1.24 here, so these two
            // assertions are what tell the two policies apart. Truncation is toward zero, which means
            // the negative case truncates UP in magnitude terms - it must not become -1.24.
            // 1.23 stores as nine integer digits then two fraction digits - 000000001 then 23 - with
            // the final 3 overpunched to 'C' for positive and 'L' for negative.
            record.moveTranAmt(new BigDecimal("1.239"));
            assertThat(record.tranAmt()).isEqualByComparingTo("1.23");
            assertThat(record.tranAmtImage()).isEqualTo("0000000012C");

            record.moveTranAmt(new BigDecimal("-1.239"));
            assertThat(record.tranAmt()).isEqualByComparingTo("-1.23");
            assertThat(record.tranAmtImage()).isEqualTo("0000000012L");

            // The boundary the two policies disagree on most visibly: a trailing 9 and a trailing 5.
            record.moveTranAmt(new BigDecimal("1.999"));
            assertThat(record.tranAmt()).isEqualByComparingTo("1.99");
            record.moveTranAmt(new BigDecimal("-1.999"));
            assertThat(record.tranAmt()).isEqualByComparingTo("-1.99");
            record.moveTranAmt(new BigDecimal("2.005"));
            assertThat(record.tranAmt()).isEqualByComparingTo("2.00");
            record.moveTranAmt(new BigDecimal("-2.005"));
            assertThat(record.tranAmt()).isEqualByComparingTo("-2.00");

            // Truncation stops at the declared scale; it never touches the significant digits.
            record.moveTranAmt(new BigDecimal("504.7799999"));
            assertThat(record.tranAmt()).isEqualByComparingTo("504.77");
            assertThat(record.tranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("TRAN-AMT is stored at scale 2 with the sign overpunched into the trailing byte")
        void amountIsStoredAsAZonedImage() {
            TranRecord record = new TranRecord(ASCII);

            record.moveTranAmt(new BigDecimal("504.77"));
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G").hasSize(11);

            record.moveTranAmt(new BigDecimal("-56.77"));
            assertThat(record.tranAmtImage()).isEqualTo("0000000567P");

            record.moveTranAmt(new BigDecimal("-919.00"));
            assertThat(record.tranAmtImage()).isEqualTo("0000009190}");

            assertThat(record.tranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an equal-scale MOVE, as CBACT04C:490 performs, changes nothing")
        void equalScaleMoveIsLossless() {
            TranRecord record = new TranRecord(ASCII);
            BigDecimal monthlyInterest = new BigDecimal("504.77"); // WS-MONTHLY-INT PIC S9(09)V99

            record.moveTranAmt(monthlyInterest);

            assertThat(record.tranAmt()).isEqualTo(monthlyInterest);
        }

        @Test
        @DisplayName("the card number is moved whole and stored unmasked")
        void cardNumberIsStoredWhole() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranCardNum("4859452612877065");

            assertThat(record.tranCardNum()).isEqualTo("4859452612877065").hasSize(16);
            assertThat(record.rawSpan(TranRecord.TRAN_CARD_NUM)).isEqualTo("4859452612877065");
        }

        @Test
        @DisplayName("timestamps of exactly 26 characters fill their fields")
        void timestampsFillTheirFields() {
            TranRecord record = new TranRecord(ASCII);
            String db2FormatTs = "2022-07-18 00:00:00.000000"; // DB2-FORMAT-TS PIC X(26)

            record.moveTranOrigTs(db2FormatTs);
            record.moveTranProcTs(db2FormatTs);

            assertThat(record.tranOrigTs()).isEqualTo(db2FormatTs).hasSize(26);
            assertThat(record.tranProcTs()).isEqualTo(db2FormatTs).hasSize(26);
        }

        @Test
        @DisplayName("every MOVE leaves the record exactly 350 bytes")
        void widthIsInvariantUnderEveryMove() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranId("X".repeat(40));
            record.moveTranDesc("Y".repeat(200));
            record.moveTranMerchantName("Z");
            record.moveTranAmt(new BigDecimal("-1.01"));
            record.moveTranCatCd(9999);

            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.displayImage()).hasSize(350);
        }
    }

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE INTO semantics")
    class StringIntoSemantics {

        @Test
        @DisplayName("STRING leaves the tail of the receiver exactly as it was")
        void stringIntoLeavesTheTailUntouched() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranDesc("X".repeat(100));

            // CBACT04C.cbl:485-489 strings 'Int. for a/c ' (13 characters) followed by ACCT-ID PIC 9(11)
            // into TRAN-DESC PIC X(100). 13 + 11 = 24, and COBOL writes exactly those 24 bytes.
            record.stringIntoTranDesc("Int. for a/c ", "00000000011");

            assertThat(record.tranDesc())
                    .isEqualTo("Int. for a/c 00000000011" + "X".repeat(76))
                    .hasSize(100);

            // Stated at absolute offsets, which is where it is either true or false: TRAN-DESC begins at
            // 32, so the 24 written bytes are 32..55 and the untouched remainder is 56..131. Bytes 56
            // onwards still hold the sentinel, and byte 132 - the first byte of TRAN-AMT - is unaffected.
            byte[] image = record.rawImage();
            assertThat(image).hasSize(350);
            assertThat(new String(image, 32, 24, ASCII)).isEqualTo("Int. for a/c 00000000011");
            assertThat(new String(image, 56, 76, ASCII)).isEqualTo("X".repeat(76));
            assertThat(new String(image, 132, 11, ASCII))
                    .as("the write stopped inside TRAN-DESC and never reached TRAN-AMT")
                    .isEqualTo("0".repeat(10) + "{");
        }

        @Test
        @DisplayName("the same content through MOVE blanks the tail - the two verbs really do differ")
        void moveAndStringIntoDifferObservably() {
            TranRecord viaString = new TranRecord(ASCII);
            viaString.moveTranDesc("X".repeat(100));
            viaString.stringIntoTranDesc("Int. for a/c ", "00000000011");

            TranRecord viaMove = new TranRecord(ASCII);
            viaMove.moveTranDesc("X".repeat(100));
            viaMove.moveTranDesc("Int. for a/c 00000000011");

            assertThat(viaString.tranDesc()).isNotEqualTo(viaMove.tranDesc());
            assertThat(viaString.tranDesc()).endsWith("X");
            assertThat(viaMove.tranDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("operands are concatenated at their full declared size, filling TRAN-ID exactly")
        void operandsAreConcatenatedDelimitedBySize() {
            TranRecord record = new TranRecord(ASCII);

            // CBACT04C.cbl:476-480 - PARM-DATE X(10) followed by WS-TRANID-SUFFIX 9(06).
            record.stringIntoTranId("2022071800", "000001");

            assertThat(record.tranId()).isEqualTo("2022071800000001").hasSize(16);
        }

        @Test
        @DisplayName("content beyond the receiver's width is discarded, there being no ON OVERFLOW")
        void overflowIsDiscarded() {
            TranRecord record = new TranRecord(ASCII);
            record.stringIntoTranTypeCd("ABCDEF");

            assertThat(record.tranTypeCd()).isEqualTo("AB");
        }

        @Test
        @DisplayName("STRING is offered for every character field")
        void everyCharacterFieldHasAStringPath() {
            TranRecord record = new TranRecord(ASCII);

            record.stringIntoTranSource("POS");
            record.stringIntoTranMerchantName("Abshire");
            record.stringIntoTranMerchantCity("North");
            record.stringIntoTranMerchantZip("72112");
            record.stringIntoTranCardNum("4859452612877065");
            record.stringIntoTranOrigTs("2022-06-10");
            record.stringIntoTranProcTs("2022-07-18");

            assertThat(record.tranSource()).isEqualTo("POS       ");
            assertThat(record.tranMerchantName()).startsWith("Abshire").hasSize(50);
            assertThat(record.tranMerchantCity()).startsWith("North").hasSize(50);
            assertThat(record.tranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranOrigTs()).startsWith("2022-06-10").hasSize(26);
            assertThat(record.tranProcDt()).isEqualTo("2022-07-18");
        }

        @Test
        @DisplayName("a STRING with no sending item is rejected")
        void noOperandsIsRejected() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(record::stringIntoTranDesc);
        }

        @Test
        @DisplayName("CBACT04C's 1300-B-WRITE-TX is reproducible statement for statement")
        void reproducesTheInterestTransactionBuild() {
            TranRecord record = new TranRecord(ASCII);

            record.stringIntoTranId("2022071800", "000001");            // :476-480
            record.moveTranTypeCd("01");                                // :482
            record.moveTranCatCd("05");                                 // :483
            record.moveTranSource("System");                            // :484
            record.stringIntoTranDesc("Int. for a/c ", "00000000011");  // :485-489
            record.moveTranAmt(new BigDecimal("504.77"));               // :490
            record.moveTranMerchantId(0L);                              // :491
            record.moveTranMerchantName("");                            // :492
            record.moveTranMerchantCity("");                            // :493
            record.moveTranMerchantZip("");                             // :494
            record.moveTranCardNum("4859452612877065");                 // :495
            record.moveTranOrigTs("2022-07-18 00:00:00.000000");        // :497
            record.moveTranProcTs("2022-07-18 00:00:00.000000");        // :498

            assertThat(record.tranId()).isEqualTo("2022071800000001");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCdImage()).isEqualTo("0005");
            assertThat(record.tranSource()).isEqualTo("System    ");
            assertThat(record.tranDesc()).isEqualTo("Int. for a/c 00000000011" + " ".repeat(76));
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.tranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranProcDt()).isEqualTo("2022-07-18");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));

            // WRITE FD-TRANFILE-REC FROM TRAN-RECORD at :500 writes exactly 350 bytes.
            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }
    }

    @Nested
    @DisplayName("raw span access")
    class RawSpanAccess {

        @Test
        @DisplayName("returns the stored image of any declared span, untrimmed")
        void readsAnyDeclaredSpan() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.rawSpan(TranRecord.TRAN_AMT)).isEqualTo("0000005047G");
            assertThat(record.rawSpan(TranRecord.TRAN_SOURCE)).isEqualTo("POS TERM  ");
            assertThat(record.rawSpan(TranRecord.FILLER)).isEqualTo(" ".repeat(20));
            assertThat(record.rawSpanBytes(TranRecord.TRAN_CARD_NUM))
                    .isEqualTo("4859452612877065".getBytes(ASCII));
        }

        @Test
        @DisplayName("the raw amount span is what DISPLAY 'TRAN-AMT ' TRAN-AMT writes to SYSOUT")
        void rawAmountSpanReproducesTheSysoutLine() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            // CBTRN03C.cbl:198 - a DISPLAY of a zoned item prints its stored bytes, not a number.
            assertThat("TRAN-AMT " + record.tranAmtImage()).isEqualTo("TRAN-AMT 0000005047G");
            assertThat(record.tranAmtImage()).isEqualTo(record.rawSpan(TranRecord.TRAN_AMT));
        }

        @Test
        @DisplayName("the raw view reads the stored span; it is not a re-encode of the decoded value")
        void theRawViewIsNotAReEncode() {
            // Two accessors over one span (G34), and the difference between reading and rebuilding shows
            // up on exactly one input: a whole-value negative zero. Reading returns the stored '}';
            // rebuilding from the BigDecimal - which has no signed zero - would return '{'. So if the
            // raw accessor were implemented as an encode of tranAmt(), this assertion would fail.
            TranRecord record = TranRecord.decode(ROW_1, ASCII);
            record.writeTranAmtImage("0000000000}");

            assertThat(record.tranAmt()).isEqualByComparingTo("0.00");
            assertThat(record.rawSpan(TranRecord.TRAN_AMT)).isEqualTo("0000000000}");
            assertThat(record.tranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.rawSpanBytes(TranRecord.TRAN_AMT))
                    .isEqualTo("0000000000}".getBytes(ASCII));

            // The same span, read straight out of the 350-byte image at its absolute offset.
            byte[] image = record.rawImage();
            byte[] span = new byte[TranRecord.TRAN_AMT_LENGTH];
            System.arraycopy(image, TranRecord.TRAN_AMT_OFFSET, span, 0, span.length);
            assertThat(new String(span, ASCII)).isEqualTo("0000000000}");
        }

        @Test
        @DisplayName("hands out a copy of the span bytes")
        void spanBytesAreCopied() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            byte[] bytes = record.rawSpanBytes(TranRecord.TRAN_ID);
            bytes[0] = (byte) 'Z';

            assertThat(record.tranId()).startsWith("0");
        }

        @Test
        @DisplayName("a descriptor from another copybook is rejected, not silently honoured")
        void foreignDescriptorIsRejected() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);
            FieldSpan foreign = FieldSpan.alphanumeric("DALYTRAN-ID", 0, 16);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.rawSpan(foreign))
                    .withMessageContaining("CVTRA05Y");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.rawSpanBytes(foreign));
        }

        @Test
        @DisplayName("a null descriptor is rejected")
        void nullDescriptorIsRejected() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.rawSpan(null));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpanBytes(null));
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        @DisplayName("a charset is always required - there is no platform default")
        void charsetIsMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranRecord(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1.getBytes(ASCII), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1, ASCII).encode(null));
        }

        @Test
        @DisplayName("a null image is rejected on both decode entry points")
        void imageIsMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode((String) null, ASCII));
        }

        @ParameterizedTest(name = "an amount image of {0} characters is rejected")
        @ValueSource(strings = {"0000005047", "0000005047GG", "", "0"})
        @DisplayName("an amount image must be exactly eleven characters, never padded or truncated")
        void amountImageWidthIsExact(String image) {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTranAmtImage(image))
                    .withMessageContaining("TRAN-AMT");
        }

        @Test
        @DisplayName("an eleven-character amount image is accepted")
        void amountImageOfElevenIsAccepted() {
            TranRecord record = new TranRecord(ASCII);

            record.writeTranAmtImage("0000005047G");

            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("null values are rejected by the amount writers")
        void amountWritersRejectNull() {
            TranRecord record = new TranRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.moveTranAmt(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTranAmtImage(null));
        }

        @Test
        @DisplayName("null values are rejected by the character writers")
        void characterWritersRejectNull() {
            TranRecord record = new TranRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.moveTranId(null));
            assertThatNullPointerException().isThrownBy(() -> record.moveTranCardNum(null));
            assertThatNullPointerException().isThrownBy(() -> record.moveTranCatCd(null));
        }
    }

    @Nested
    @DisplayName("identity and diagnostics")
    class IdentityAndDiagnostics {

        @Test
        @DisplayName("a record equals itself")
        void isReflexive() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("records with the same bytes under the same charset are equal")
        void sameBytesSameCharsetAreEqual() {
            TranRecord first = TranRecord.decode(ROW_1, ASCII);
            TranRecord second = TranRecord.decode(ROW_1, ASCII);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("records differing in one byte are not equal")
        void differentBytesAreNotEqual() {
            TranRecord first = TranRecord.decode(ROW_1, ASCII);
            TranRecord second = TranRecord.decode(ROW_1, ASCII);
            second.moveTranCatCd(2);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("the same characters under different code pages are not the same record")
        void differentCharsetsAreNotEqual() {
            TranRecord ascii = TranRecord.decode(ROW_1, ASCII);
            TranRecord ebcdic = TranRecord.decode(ROW_1, EBCDIC);

            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii.rawImage()).isNotEqualTo(ebcdic.rawImage());
        }

        @Test
        @DisplayName("null and a foreign type are not equal to a record")
        void foreignValuesAreNotEqual() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("0000000000683580")).isFalse();
        }

        @Test
        @DisplayName("toString names every field but withholds the card number, which is masked")
        void toStringIsCompleteButMasksThePan() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            String rendered = record.toString();

            // The structural codes identify the transaction and are disclosed in full, because a parity
            // failure is diagnosed from them.
            assertThat(rendered)
                    .contains("TRAN-ID='0000000000683580'")
                    .contains("TRAN-TYPE-CD='01'")
                    .contains("TRAN-CAT-CD='0001'")
                    .contains("TRAN-SOURCE='POS TERM  '")
                    .contains("TRAN-MERCHANT-ID='800000000'")
                    .contains("TRAN-ORIG-TS='2022-06-10 19:27:53.000000'")
                    .contains("FILLER.length=")
                    .contains("charset=US-ASCII");

            // The amount appears as image AND value, because that pair is what tells a numeric parity
            // failure apart from an encoding one.
            assertThat(rendered).contains("TRAN-AMT='0000005047G'=504.77");

            // The one field held back is the PAN: a diagnostic string may be logged, and a full card
            // number in a log is a disclosure this migration must not introduce. Masking here costs
            // nothing in parity terms, because the record's bytes are still reachable in full through
            // the accessors that a comparison actually uses.
            assertThat(rendered)
                    .doesNotContain("4859452612877065")
                    .contains("TRAN-CARD-NUM='************7065'");
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.rawSpan(TranRecord.TRAN_CARD_NUM)).isEqualTo("4859452612877065");
            assertThat(record.displayImage()).contains("4859452612877065");
        }
    }

    @Nested
    @DisplayName("type and schema hygiene")
    class TypeAndSchemaHygiene {

        @Test
        @DisplayName("no field or accessor uses double or float anywhere (R4, G22)")
        void noBinaryFloatingPointAnywhere() {
            // TRAN-AMT is PIC S9(09)V99. A double cannot represent 504.77 exactly, so one here would
            // silently change money - which is why the type is banned rather than merely discouraged.
            for (Field field : declaredFields()) {
                assertThat(field.getType())
                        .as("field %s", field.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .as("parameters of %s", method.getName())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("no persistence mapping of any kind is declared on the type (G44)")
        void noPersistenceMapping() {
            // Reaching the dataset is JDBC over the record image. There is no entity, no table, no
            // generated key and no version column to map to, and an annotation here - jakarta or javax,
            // class level or field level - would be the first step towards inventing a schema the
            // mainframe does not have.
            assertThat(TranRecord.class.getAnnotations()).isEmpty();
            for (Field field : declaredFields()) {
                assertThat(field.getAnnotations())
                        .as("field %s must carry no mapping annotation", field.getName())
                        .isEmpty();
                assertThat(field.getName())
                        .as("no optimistic-locking version column may appear; COACTUPC and COCRDUPC "
                                + "do their concurrency check by re-reading and comparing the record")
                        .isNotEqualToIgnoringCase("version");
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getAnnotations())
                        .as("method %s must carry no mapping annotation", method.getName())
                        .isEmpty();
            }
            for (java.lang.annotation.Annotation annotation : TranRecord.class.getAnnotations()) {
                assertThat(annotation.annotationType().getPackageName())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence")
                        .doesNotStartWith("org.springframework");
            }
        }

        @Test
        @DisplayName("no mutable static state, so nothing leaks between records or tests (G53)")
        void noMutableStaticState() {
            // COBOL WORKING-STORAGE is per-run state; turned into a static Java field it would be
            // shared across every request and every test, and the suite's outcome would depend on
            // execution order.
            for (Field field : declaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the 350-byte area is private and final, so a field and the bytes cannot disagree")
        void theRecordAreaIsEncapsulated() {
            for (Field field : declaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            // The type is final too: a subclass could override an accessor and break the one guarantee
            // every consumer relies on, that a field value and the stored bytes are the same thing.
            assertThat(Modifier.isFinal(TranRecord.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the type is a plain data carrier, not a Spring bean")
        void theTypeIsNotASpringComponent() {
            // Records are constructed, decoded and copied - never injected. A stereotype annotation
            // here would make a 350-byte mutable buffer a singleton, which is exactly the shared
            // mutable state the migration forbids.
            for (java.lang.annotation.Annotation annotation : TranRecord.class.getAnnotations()) {
                assertThat(annotation.annotationType().getSimpleName())
                        .isNotIn("Component", "Service", "Repository", "Configuration", "Entity",
                                "Table", "Id");
            }
            assertThat(TranRecord.class.getAnnotations()).isEmpty();
        }

        private List<Field> declaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : TranRecord.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields to vouch for").isNotEmpty();
            return fields;
        }

        private List<Method> declaredMethods() {
            List<Method> methods = new ArrayList<>();
            for (Method method : TranRecord.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.getName().startsWith("$")) {
                    methods.add(method);
                }
            }
            assertThat(methods).as("the guard must find real methods to vouch for").isNotEmpty();
            return methods;
        }
    }
}
