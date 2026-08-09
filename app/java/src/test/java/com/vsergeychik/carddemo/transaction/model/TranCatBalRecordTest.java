package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link TranCatBalRecord} - the single Java type for {@code app/cpy/CVTRA01Y.cpy}, whose own
 * header comment reads <em>"Data-structure for transaction category balance (RECLN = 50)"</em>. That is
 * the {@code TCATBALF} record: <strong>50 bytes carrying a 17-byte composite key</strong>.
 *
 * <h2>Where every expectation in this file comes from</h2>
 * Each expectation is derived from a reference source and never restated from the implementation, so a
 * defect in the type cannot make its own test agree with it. The sources, each re-read and re-measured
 * while writing this file:
 * <ul>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} - the four elementary items, the 17-byte {@code TRAN-CAT-KEY}
 *       group, the trailing {@code FILLER X(22)} and the {@code RECLN = 50} total. Re-derived by
 *       addition: {@code 11 + 2 + 4 + 11 + 22 = 50} for the record and {@code 11 + 2 + 4 = 17} for the
 *       key.</li>
 *   <li>{@code app/cpy/CVTRA02Y.cpy} - {@code 01 DIS-GROUP-RECORD}, which <strong>also totals exactly
 *       50 bytes</strong> and is therefore invisible to a total-width check. Its geometry is asserted
 *       here as a set of negatives, so the substitution cannot happen silently. Collision 1 below.</li>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} - {@code 01 TRAN-CAT-RECORD}, which declares a group with the
 *       <strong>same COBOL name {@code TRAN-CAT-KEY}</strong> at only 6 bytes. Collision 2 below.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:503-509} - paragraph {@code 2700-A-CREATE-TCATBAL-REC}, the sole
 *       source of {@link TranCatBalRecord#initialize()}'s contract. Quoted verbatim in
 *       {@link InitializeSemantics}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} - {@code :193} {@code DISPLAY TRAN-CAT-BAL-RECORD}, which is why a
 *       whole-record image accessor exists; and {@code :464-465}, the interest formula that consumes
 *       {@code TRAN-CAT-BAL} with <strong>no {@code ROUNDED}</strong> into a
 *       {@code WS-MONTHLY-INT PIC S9(09)V99} receiver declared at {@code :168}.</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl:41-42} and {@code app/jcl/INTCALC.jcl:27-28} - both bind the
 *       {@code TCATBALF} DD name that {@link TranCatBalRecord#DD_NAME} carries.</li>
 *   <li>{@code app/data/ASCII/tcatbal.txt} - measured at 50 records of exactly 50 bytes (2550 bytes
 *       including line terminators). The rows embedded below are transcribed from it field by field.</li>
 *   <li>{@code app/data/ASCII/discgrp.txt} - 51 records of exactly 50 bytes, which is what makes
 *       Collision 1 real in the shipped data and not merely in the declarations.</li>
 * </ul>
 *
 * <h2>Why the fixture rows are embedded rather than loaded</h2>
 * Every row below is a {@code private static final String} assembled from its field images, each with
 * the {@code PICTURE} clause it satisfies written beside it. Nothing is read from the filesystem or from
 * the classpath. That is deliberate on three counts: this test asserts the copybook contract rather than
 * the availability of a resource; a missing resource would fail the branch-coverage gate with an I/O
 * error instead of with a real defect; and an expectation spelled out field by field is auditable against
 * the copybook by eye, which a byte range pulled out of a file is not.
 *
 * <h2>Collision 1 - {@code CVTRA02Y} also totals 50, so width alone proves nothing</h2>
 * <table border="1">
 *   <caption>The two 50-byte layouts share no offset past byte 11</caption>
 *   <tr><th></th><th>{@code CVTRA01Y} (this record)</th><th>{@code CVTRA02Y}</th></tr>
 *   <tr><td>key group</td><td>{@code TRAN-CAT-KEY}, <strong>17</strong></td>
 *       <td>{@code DIS-GROUP-KEY}, <strong>16</strong></td></tr>
 *   <tr><td>first key item</td><td>{@code TRANCAT-ACCT-ID PIC 9(11)}, numeric</td>
 *       <td>{@code DIS-ACCT-GROUP-ID PIC X(10)}, alphanumeric</td></tr>
 *   <tr><td>signed amount</td><td>{@code TRAN-CAT-BAL S9(09)V99}, <strong>11 bytes at 17</strong></td>
 *       <td>{@code DIS-INT-RATE S9(04)V99}, <strong>6 bytes at 16</strong></td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(22)} at 28</td><td>{@code X(28)} at 22</td></tr>
 *   <tr><td>total</td><td>{@code 11+2+4+11+22 = 50}</td><td>{@code 10+2+4+6+28 = 50}</td></tr>
 * </table>
 * The confusion is live rather than hypothetical: {@code CBACT04C} reads a {@code TCATBALF} record and
 * then keys {@code DISCGRP} from its fields, so both layouts are handled inside one loop. A one-byte
 * shift would move every byte of the balance while leaving the record width right. {@link CollisionDefence}
 * asserts the difference as geometry, and neither copybook name is ever tidied or merged away.
 *
 * <h2>Collision 2 - the group name {@code TRAN-CAT-KEY} is reused for 6 bytes</h2>
 * {@code app/cpy/CVTRA04Y.cpy} names a group {@code TRAN-CAT-KEY} that is {@code TRAN-TYPE-CD X(02)}
 * plus {@code TRAN-CAT-CD 9(04)} - 6 bytes, with no account identifier at all. Both names stay verbatim,
 * because the parity differ compares field by field <em>by name</em> and renaming either would hide a
 * real difference. The two keys are never interchangeable, and that is asserted rather than assumed.
 *
 * <h2>Constraints this file holds itself to</h2>
 * {@code review_rules} reports <strong>"No user rules provided"</strong> - confirmed by reading the whole
 * document - so no project rule governs this file. Its absence is not licence to lower the bar; the
 * migration plan's own constraints bind instead, and these are the ones that shaped this file:
 * <ul>
 *   <li><b>R5, B11</b> - every assertion is at an absolute byte offset, and every offset is justified by
 *       addition from the {@code PICTURE} clauses in a comment beside it. Nothing is parsed, split,
 *       matched by regular expression or trimmed before comparison, and no third-party copybook parser is
 *       imported or consulted.</li>
 *   <li><b>R4, G22</b> - neither {@code double} nor {@code float} appears anywhere in this file, and
 *       {@link StructuralGuards} proves the same of the type under test.</li>
 *   <li><b>R3, G23</b> - {@link BigDecimal#scale()} is asserted to be exactly 2 wherever a balance is
 *       handed out, not merely {@link BigDecimal#compareTo(BigDecimal)}, which is scale-blind.</li>
 *   <li><b>R2, G24</b> - {@link RoundingMode#DOWN} is the only rounding mode named in code here. The
 *       keyword {@code ROUNDED} appears zero times across all 28 COBOL programs, so a half-up or
 *       half-even expectation would itself be the defect; both signs of an over-precise store are
 *       driven.</li>
 *   <li><b>G19, G21, B5</b> - the width is 50; a freshly allocated record's {@code FILLER} is
 *       space-filled; and {@code INITIALIZE} leaves an existing {@code FILLER} untouched, which is
 *       preserved legacy behaviour and never "cleaned up".</li>
 *   <li><b>G34</b> - the 17-byte key is asserted as a view over the record's own bytes, in both
 *       directions.</li>
 *   <li><b>B7, B8, G52, G53, G54</b> - plain JUnit 5 with no Spring annotation, no clock, locale,
 *       filesystem, network or ordering dependence; every import individually named with no wildcard;
 *       every code page passed explicitly; no mutable static state, including in this test class, which
 *       polices itself.</li>
 *   <li><b>G44</b> - no persistence mapping is permitted on the type, and that is checked reflectively
 *       rather than by inspection: {@code TCATBALF} is read and rewritten, which is exactly where
 *       optimistic-locking metadata would otherwise creep in.</li>
 * </ul>
 *
 * <h2>Deliberately out of scope</h2>
 * Repository access paths, the {@code '00'}/{@code '23'} create-versus-update discrimination,
 * {@code FileStatus} ladders, {@code AbendException}, job orchestration, the interest formula itself and
 * anything HTTP or Spring belong to sibling tests and are not duplicated here. This file asserts the
 * model type's own byte behaviour, its key sub-span and its {@code INITIALIZE} semantics - nothing else.
 */
@DisplayName("TranCatBalRecord - CVTRA01Y TRAN-CAT-BAL-RECORD, 50 bytes, 17-byte composite key")
class TranCatBalRecordTest {

    /**
     * The code page of the ASCII fixtures, named explicitly. A platform default is never relied on:
     * fixed-width mainframe data is bytes in a specific code page, and the pad and overpunch bytes
     * themselves are code-page dependent.
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The code page of the EBCDIC datasets under {@code app/data/EBCDIC}. Used only to prove that the
     * supplied charset is honoured rather than assumed; the ASCII fixtures remain authoritative.
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // =================================================================================================
    // Fixture field images, transcribed from app/data/ASCII/tcatbal.txt. The file was measured at 50
    // rows of exactly 50 bytes, and the three variable-free fields below were confirmed identical in
    // all 50 rows; only TRANCAT-ACCT-ID varies, running 00000000001 through 00000000050.
    // =================================================================================================

    /** {@code TRANCAT-TYPE-CD PIC X(02)}, 1-based bytes 12-13. Every row holds {@code 01}. */
    private static final String FIXTURE_TYPE_CD = "01";

    /** {@code TRANCAT-CD PIC 9(04)}, 1-based bytes 14-17. Every row holds {@code 0001}. */
    private static final String FIXTURE_CAT_CD_IMAGE = "0001";

    /** The numeric value of {@link #FIXTURE_CAT_CD_IMAGE}, stated separately so the decode is checked. */
    private static final int FIXTURE_CAT_CD = 1;

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99}, 1-based bytes 18-28: ten zeros then <code>&#123;</code>.
     *
     * <p>The trailing <code>&#123;</code> is the zone-{@code C} overpunch for digit {@code 0}, so this
     * image is <strong>+0.00</strong> and not an unset field. All fifty rows of the fixture end this way,
     * which is measured rather than assumed - so the fixture exercises only the positive half of the
     * overpunch alphabet, and {@link SignOverpunch} supplies synthetic spans for the negative half.
     */
    private static final String FIXTURE_BALANCE_IMAGE = "0000000000{";

    /**
     * {@code FILLER PIC X(22)}, 1-based bytes 29-50: twenty-two ASCII <strong>zeros</strong>, not spaces.
     *
     * <p>This is the single most consequential measurement in the fixture, because it reconciles two
     * obligations that only look contradictory. A record built from nothing space-fills its reserved
     * span, while a record read from the dataset must carry these zeros back out verbatim on rewrite.
     * Both are asserted, separately, in {@link FreshlyAllocatedRecord} and {@link ShippedFixtureRows}.
     */
    private static final String FIXTURE_FILLER_IMAGE = "0".repeat(22);

    /**
     * Row 1 of {@code app/data/ASCII/tcatbal.txt}, assembled from its five field images so that every
     * byte is visible and auditable against the copybook.
     */
    private static final String ROW_1 =
            "00000000001"             // TRANCAT-ACCT-ID 9(11)     1-based 1-11,  0-based 0
            + FIXTURE_TYPE_CD         // TRANCAT-TYPE-CD X(02)     1-based 12-13, 0-based 11
            + FIXTURE_CAT_CD_IMAGE    // TRANCAT-CD      9(04)     1-based 14-17, 0-based 13
            + FIXTURE_BALANCE_IMAGE   // TRAN-CAT-BAL    S9(09)V99 1-based 18-28, 0-based 17
            + FIXTURE_FILLER_IMAGE;   // FILLER          X(22)     1-based 29-50, 0-based 28

    /** Row 4 of the same fixture. Identical to row 1 but for the account identifier. */
    private static final String ROW_4 =
            "00000000004"
            + FIXTURE_TYPE_CD
            + FIXTURE_CAT_CD_IMAGE
            + FIXTURE_BALANCE_IMAGE
            + FIXTURE_FILLER_IMAGE;

    /** Row 50, the last of the fixture, whose account identifier is the highest it carries. */
    private static final String ROW_50 =
            "00000000050"
            + FIXTURE_TYPE_CD
            + FIXTURE_CAT_CD_IMAGE
            + FIXTURE_BALANCE_IMAGE
            + FIXTURE_FILLER_IMAGE;

    /**
     * A synthetic row shaped exactly like a fixture row but carrying a <strong>non-zero</strong> balance.
     *
     * <p>The shipped fixture cannot prove that {@link TranCatBalRecord#initialize()} zeroes the balance,
     * because every one of its rows already holds {@code +0.00}. This row supplies the stale value that
     * a create-on-miss must not inherit: {@code 0000001234E} is unscaled {@code 12345} with a zone-{@code C}
     * {@code 'E'} for a positive low-order digit {@code 5}, that is {@code 123.45}.
     */
    private static final String ROW_WITH_STALE_BALANCE =
            "00000000009"             // TRANCAT-ACCT-ID 9(11)
            + "AB"                    // TRANCAT-TYPE-CD X(02) - alphanumeric, so INITIALIZE blanks it
            + "0007"                  // TRANCAT-CD      9(04) - numeric, so INITIALIZE zeroes it
            + "0000001234E"           // TRAN-CAT-BAL    S9(09)V99 = 123.45, the value to be discarded
            + FIXTURE_FILLER_IMAGE;   // FILLER          X(22) - 22 zeros, which INITIALIZE must not touch

    /**
     * Decodes a fixture row under {@code US-ASCII}. A fresh record per call, so no test can observe
     * another's mutation and no shared mutable state exists (practice B9).
     *
     * @param row the 50-character row image
     * @return a record over exactly those bytes
     */
    private static TranCatBalRecord decoded(String row) {
        return TranCatBalRecord.decode(row, ASCII);
    }

    /**
     * Assembles a 50-character record image from its five field images, so that an expectation is spelled
     * out from the copybook rather than produced by the code under test (practice B11).
     *
     * @param acctId  eleven digits for {@code TRANCAT-ACCT-ID PIC 9(11)}
     * @param typeCd  two characters for {@code TRANCAT-TYPE-CD PIC X(02)}
     * @param catCd   four digits for {@code TRANCAT-CD PIC 9(04)}
     * @param balance eleven characters for {@code TRAN-CAT-BAL PIC S9(09)V99}, sign overpunch included
     * @param filler  twenty-two characters for {@code FILLER PIC X(22)}
     * @return the assembled row, proven to be 50 characters wide
     */
    private static String recordImage(String acctId, String typeCd, String catCd, String balance,
                                     String filler) {
        assertThat(acctId).as("TRANCAT-ACCT-ID PIC 9(11)").hasSize(11);
        assertThat(typeCd).as("TRANCAT-TYPE-CD PIC X(02)").hasSize(2);
        assertThat(catCd).as("TRANCAT-CD PIC 9(04)").hasSize(4);
        assertThat(balance).as("TRAN-CAT-BAL PIC S9(09)V99 is 9 + 2 = 11 bytes").hasSize(11);
        assertThat(filler).as("FILLER PIC X(22)").hasSize(22);
        // 11 + 2 + 4 + 11 + 22 = 50. Stated as the sum so that a dropped span shows up as arithmetic.
        return acctId + typeCd + catCd + balance + filler;
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (gates G19 and G21)")
    class DeclaredGeometry {

        @Test
        @DisplayName("RECLN = 50, and the five declared spans account for every one of those bytes")
        void theRecordIsFiftyBytesAndEveryByteIsAccountedFor() {
            // 11 + 2 + 4 + 11 + 22 = 50. Written as the addition rather than as the total, because a
            // dropped FILLER or a sign byte wrongly reserved for TRAN-CAT-BAL is visible in the sum and
            // invisible in the total.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH
                    + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH
                    + TranCatBalRecord.TRANCAT_CD_LENGTH
                    + TranCatBalRecord.TRAN_CAT_BAL_LENGTH
                    + TranCatBalRecord.FILLER_LENGTH)
                    .as("CVTRA01Y: 11 + 2 + 4 + 11 + 22")
                    .isEqualTo(50);
            assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(TranCatBalRecord.LAYOUT.recordLength()).isEqualTo(50);
            assertThat(TranCatBalRecord.LAYOUT.spans()).hasSize(5);
            // The static self-check runs at class initialisation; calling it here proves its success
            // path returns the width rather than merely not throwing.
            assertThat(TranCatBalRecord.verifyGeometry()).isEqualTo(50);
        }

        @Test
        @DisplayName("TRAN-CAT-KEY is 17 bytes over three items, and TRAN-CAT-BAL begins where it ends")
        void theKeyIsSeventeenBytesOverThreeItems() {
            // 11 + 2 + 4 = 17. The key is the leading sub-span, so its offset is 0 by construction.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH
                    + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH
                    + TranCatBalRecord.TRANCAT_CD_LENGTH)
                    .as("CVTRA01Y TRAN-CAT-KEY: 11 + 2 + 4")
                    .isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.recordLength()).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.spans()).hasSize(3);
            // The balance starts at the byte immediately after the key: 0 + 17 = 17.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_KEY_OFFSET
                            + TranCatBalRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("every offset is the running sum of the lengths that precede it")
        void everyOffsetIsTheRunningSumOfThePrecedingLengths() {
            // Offsets are 0-based; the copybook's 1-based positions are 1-11, 12-13, 14-17, 18-28, 29-50.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET).as("first item").isZero();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET).as("0 + 11").isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_CD_OFFSET).as("11 + 2").isEqualTo(13);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET).as("13 + 4").isEqualTo(17);
            assertThat(TranCatBalRecord.FILLER_OFFSET).as("17 + 11").isEqualTo(28);
            // 28 + 22 = 50, so the last declared span ends exactly at the record's width.
            assertThat(TranCatBalRecord.FILLER_SPAN.endOffsetExclusive())
                    .as("28 + 22")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);
            // Each descriptor addresses the same bytes as its constants, so the two can never drift.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.offset()).isZero();
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.length()).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.offset()).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.length()).isEqualTo(2);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.offset()).isEqualTo(13);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.length()).isEqualTo(4);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.offset()).isEqualTo(17);
            assertThat(TranCatBalRecord.FILLER_SPAN.offset()).isEqualTo(28);
            assertThat(TranCatBalRecord.FILLER_SPAN.length()).isEqualTo(22);
        }

        @Test
        @DisplayName("the five spans tile the record contiguously, with no gap and no overlap")
        void theFiveSpansTileTheRecordContiguously() {
            List<FieldSpan> spans = TranCatBalRecord.LAYOUT.spans();
            assertThat(spans).hasSize(5);
            // Declaration order is copybook order, which is exactly what makes each offset the running
            // sum of the lengths before it.
            assertThat(spans.stream().map(FieldSpan::name).toList()).containsExactly(
                    TranCatBalRecord.TRANCAT_ACCT_ID_NAME,
                    TranCatBalRecord.TRANCAT_TYPE_CD_NAME,
                    TranCatBalRecord.TRANCAT_CD_NAME,
                    TranCatBalRecord.TRAN_CAT_BAL_NAME,
                    TranCatBalRecord.FILLER_SPAN.name());

            // Walking the spans and accumulating their lengths proves there is no gap and no overlap:
            // each span must begin at exactly the byte the previous one ended on. A gap would leave
            // undefined bytes in the middle of a record; an overlap would make one store clobber another.
            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("span %s begins where the previous one ended", span.name())
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            // And the walk lands exactly on the declared width: 11 + 2 + 4 + 11 + 22 = 50.
            assertThat(expectedOffset)
                    .as("11 + 2 + 4 + 11 + 22")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("TRAN-CAT-BAL occupies 9 + 2 = 11 bytes, because nothing in app/cpy is packed")
        void theBalanceSpanIsElevenBytesBecauseNothingIsPacked() {
            // A signed zoned PIC S9(p)V(s) field occupies exactly p + s bytes: the sign is overpunched
            // into the trailing byte and consumes none of its own. 9 + 2 = 11.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SCALE).isEqualTo(2);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS
                    + TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .as("PIC S9(09)V99 is 9 + 2 bytes, with no byte reserved for the sign")
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_LENGTH);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH).isEqualTo(11);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.length()).isEqualTo(11);
            // Asserted so that a future assumption of COMP-3 breaks this test loudly. A grep of the whole
            // of app/cpy returns zero COMP-3 and zero PACKED-DECIMAL declarations: every persisted numeric
            // in this system is zoned DISPLAY, so no nibble unpacking is ever needed and a packed span -
            // which would be ceil((9 + 2 + 1) / 2) = 6 bytes - is simply wrong here.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH)
                    .as("zoned DISPLAY, not COMP-3")
                    .isNotEqualTo(6);
            // The declared scale is the system-wide monetary scale, which is where it comes from.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("the trailing FILLER X(22) is a declared span, not an inferred gap (G21)")
        void theFillerIsADeclaredSpanNotAnInferredGap() {
            assertThat(TranCatBalRecord.FILLER_SPAN.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(TranCatBalRecord.FILLER_SPAN.kind().filler()).isTrue();
            assertThat(TranCatBalRecord.LAYOUT.spans()).contains(TranCatBalRecord.FILLER_SPAN);
            // If FILLER were omitted from the layout the remaining spans would total 28, so the width
            // check below is what makes its absence impossible rather than merely discouraged.
            assertThat(TranCatBalRecord.RECORD_LENGTH - TranCatBalRecord.FILLER_LENGTH)
                    .as("50 - 22 leaves the four named items")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("each span's kind matches its PICTURE category, which fixes its pad and justification")
        void eachSpanKindMatchesItsPictureCategory() {
            // PIC 9 is numeric display: zero-padded and right-justified on the implied decimal point.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind().numericDisplay()).isTrue();
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.kind().numericDisplay()).isTrue();
            // PIC X is alphanumeric: space-padded and left-justified - so TRANCAT-TYPE-CD is a String
            // even though its shipped values look like the digits 01.
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind().leftJustified()).isTrue();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind().numericDisplay()).isFalse();
            // PIC S9(p)V(s) is signed zoned, which is what puts the sign in the trailing byte.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.kind())
                    .isEqualTo(PictureKind.SIGNED_SCALED);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.kind().numericDisplay()).isTrue();
            // No item in CVTRA01Y redefines another: the copybook declares no REDEFINES at all.
            assertThat(TranCatBalRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(TranCatBalRecord.LAYOUT.storageSpans()).hasSize(5);
        }

        @Test
        @DisplayName("the copybook, record and item names are carried verbatim for the parity differ")
        void theCopybookVocabularyIsVerbatim() {
            // The differ compares field by field BY NAME, so these strings are part of the contract.
            assertThat(TranCatBalRecord.COPYBOOK).isEqualTo("CVTRA01Y");
            assertThat(TranCatBalRecord.RECORD_NAME).isEqualTo("TRAN-CAT-BAL-RECORD");
            // Both app/jcl/POSTTRAN.jcl:41-42 and app/jcl/INTCALC.jcl:27-28 bind this DD name.
            assertThat(TranCatBalRecord.DD_NAME).isEqualTo("TCATBALF");
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME).isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_NAME).isEqualTo("TRANCAT-ACCT-ID");
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_NAME).isEqualTo("TRANCAT-TYPE-CD");
            assertThat(TranCatBalRecord.TRANCAT_CD_NAME).isEqualTo("TRANCAT-CD");
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_NAME).isEqualTo("TRAN-CAT-BAL");
            // The descriptors carry the same names, so a rename cannot reach only one of the two.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.name())
                    .isEqualTo(TranCatBalRecord.TRANCAT_ACCT_ID_NAME);
            assertThat(TranCatBalRecord.LAYOUT.hasSpan(TranCatBalRecord.TRAN_CAT_BAL_NAME)).isTrue();
            assertThat(TranCatBalRecord.LAYOUT.span(TranCatBalRecord.TRAN_CAT_BAL_NAME))
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_SPAN);
        }

        @Test
        @DisplayName("verifyRecordLength accepts agreement and rejects a shortfall and an excess alike")
        void verifyRecordLengthAcceptsAgreementAndRejectsDisagreement() {
            // The success branch returns the width it was given.
            assertThat(TranCatBalRecord.verifyRecordLength(50, 50)).isEqualTo(50);
            // 49: what a dropped FILLER byte, or any short span, would produce.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(50, 49))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("FILLER");
            // 51: what reserving a twelfth byte for TRAN-CAT-BAL's sign would produce. Both failure
            // directions are driven, so the guard is proven rather than merely present (G50).
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(50, 51))
                    .withMessageContaining("overpunched");
        }

        @Test
        @DisplayName("verifyKeyGeometry accepts 17 and rejects the 16 that CVTRA02Y would supply")
        void verifyKeyGeometryAcceptsSeventeenAndRejectsSixteen() {
            assertThat(TranCatBalRecord.verifyKeyGeometry(17, 17)).isEqualTo(17);
            // A 16-byte key against a balance at 17 is precisely the CVTRA02Y substitution, and it is
            // the one slip a total-width check can never see.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyKeyGeometry(16, 17))
                    .withMessageContaining("CVTRA02Y");
        }
    }

    @Nested
    @DisplayName("Collision defence - two layouts that are invisible to a width check (practice B4)")
    class CollisionDefence {

        @Test
        @DisplayName("Collision 1: CVTRA02Y also totals 50, so only the field geometry separates them")
        void theCvtra02yLayoutIsRejectedGeometryByGeometry() {
            // app/cpy/CVTRA02Y.cpy, 01 DIS-GROUP-RECORD, re-read and re-derived while writing this test:
            //   DIS-ACCT-GROUP-ID PIC X(10)     10 bytes at 0   - ALPHANUMERIC
            //   DIS-TRAN-TYPE-CD  PIC X(02)      2 bytes at 10
            //   DIS-TRAN-CAT-CD   PIC 9(04)      4 bytes at 12   -> DIS-GROUP-KEY is 10 + 2 + 4 = 16
            //   DIS-INT-RATE      PIC S9(04)V99  6 bytes at 16   - 4 + 2, not 9 + 2
            //   FILLER            PIC X(28)     28 bytes at 22
            // 10 + 2 + 4 + 6 + 28 = 50, the very same total as CVTRA01Y's 11 + 2 + 4 + 11 + 22.
            int cvtra02yTotal = 10 + 2 + 4 + 6 + 28;
            assertThat(cvtra02yTotal)
                    .as("both copybooks declare RECLN = 50, so the total discriminates nothing")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);

            // Hence every assertion below is a NEGATIVE against CVTRA02Y's number, one per field.
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .as("TRAN-CAT-KEY is 17, never DIS-GROUP-KEY's 16")
                    .isEqualTo(17)
                    .isNotEqualTo(16);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .as("the signed amount starts at 17, never at DIS-INT-RATE's 16")
                    .isEqualTo(17)
                    .isNotEqualTo(16);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH)
                    .as("S9(09)V99 is 11 bytes, never S9(04)V99's 6")
                    .isEqualTo(11)
                    .isNotEqualTo(6);
            assertThat(TranCatBalRecord.FILLER_OFFSET)
                    .as("FILLER starts at 28, never at CVTRA02Y's 22")
                    .isEqualTo(28)
                    .isNotEqualTo(22);
            assertThat(TranCatBalRecord.FILLER_LENGTH)
                    .as("FILLER is 22 bytes, never CVTRA02Y's 28 - the pair is transposed, which is "
                            + "exactly how a transcription slip survives review")
                    .isEqualTo(22)
                    .isNotEqualTo(28);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH)
                    .as("TRANCAT-ACCT-ID is 11 bytes, never DIS-ACCT-GROUP-ID's 10")
                    .isEqualTo(11)
                    .isNotEqualTo(10);
            // The first key item differs in category as well as in width: numeric here, alphanumeric
            // there. A record decoded at the wrong one would not even fail to parse.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind().numericDisplay())
                    .as("TRANCAT-ACCT-ID PIC 9(11) is numeric; DIS-ACCT-GROUP-ID PIC X(10) is not")
                    .isTrue();

            // And the difference is real in the shipped data, not only in the declarations:
            // app/data/ASCII/discgrp.txt is 51 rows of 50 bytes whose row 1 reads
            // "A000000000" + "01" + "0001" + "00150{" + 28 zeros - a SIX-character rate at 0-based 16
            // meaning 0015.00, where this record carries an ELEVEN-character balance at 0-based 17.
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.tranCatBalImage())
                    .as("this record's signed span is 11 characters wide")
                    .hasSize(11);
            assertThat(record.tranCatKeyImage())
                    .as("and its key is 17, so a 16-byte read would take a balance digit into the key")
                    .hasSize(17);
        }

        @Test
        @DisplayName("Collision 2: CVTRA04Y names a 6-byte group TRAN-CAT-KEY, and both names stay")
        void theCvtra04yGroupOfTheSameNameIsSixBytesNotSeventeen() {
            // app/cpy/CVTRA04Y.cpy, 01 TRAN-CAT-RECORD, re-read while writing this test:
            //   05 TRAN-CAT-KEY.
            //      10 TRAN-TYPE-CD PIC X(02)   2 bytes
            //      10 TRAN-CAT-CD  PIC 9(04)   4 bytes   -> that group is 2 + 4 = 6 bytes
            // The COBOL name is identical to this record's group; the width is not, and there is no
            // account identifier in it at all.
            int cvtra04yKeyLength = 2 + 4;
            assertThat(cvtra04yKeyLength).as("CVTRA04Y TRAN-CAT-KEY: 2 + 4").isEqualTo(6);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME)
                    .as("the name is carried verbatim and is never disambiguated, because the parity "
                            + "differ compares by name and a rename would hide a real difference")
                    .isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .as("this TRAN-CAT-KEY is 17 bytes, never CVTRA04Y's 6")
                    .isEqualTo(17)
                    .isNotEqualTo(cvtra04yKeyLength);
            // The difference is the account identifier, which CVTRA04Y's group does not carry:
            // 17 - 6 = 11 = TRANCAT-ACCT-ID PIC 9(11).
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH - cvtra04yKeyLength)
                    .as("17 - 6 is exactly TRANCAT-ACCT-ID PIC 9(11)")
                    .isEqualTo(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH);
            // A key value of this type is therefore never substitutable for a 6-byte one.
            assertThat(new TranCatKey(1L, "01", 1).image(ASCII)).hasSize(17);
        }
    }

    @Nested
    @DisplayName("TRAN-CAT-KEY as a view over the record's own first 17 bytes (gate G34)")
    class CompositeKey {

        @Test
        @DisplayName("the key image is its three items concatenated, each by its own PICTURE's rule")
        void theKeyImageIsTheConcatenationOfItsThreeItems() {
            // Built from the three parts rather than restated as a literal, so the expectation is
            // derived from the copybook and not from the code under test (practice B11).
            String expected = "00000000001"          // TRANCAT-ACCT-ID 9(11), zero-filled from the left
                    + FIXTURE_TYPE_CD                // TRANCAT-TYPE-CD X(02), as stored
                    + FIXTURE_CAT_CD_IMAGE;          // TRANCAT-CD      9(04), zero-filled from the left
            assertThat(expected).as("11 + 2 + 4 = 17").hasSize(17);

            assertThat(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).image(ASCII))
                    .isEqualTo(expected);
            assertThat(decoded(ROW_1).tranCatKeyImage()).isEqualTo(expected);
            // The key's byte form is the same 17 bytes, under the code page that was named.
            assertThat(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).toByteArray(ASCII))
                    .isEqualTo(expected.getBytes(ASCII));
        }

        @Test
        @DisplayName("the key inside a record is literally its bytes 0 through 16, not a copy of a copy")
        void theKeyIsTheRecordsFirstSeventeenBytes() {
            TranCatBalRecord record = decoded(ROW_1);
            byte[] whole = record.encode();
            byte[] keyBytes = record.tranCatKeyBytes();
            assertThat(keyBytes).hasSize(17);
            // Compared at absolute offsets 0..16 rather than by any form of parsing (R5).
            for (int offset = TranCatBalRecord.TRAN_CAT_KEY_OFFSET;
                    offset < TranCatBalRecord.TRAN_CAT_KEY_LENGTH; offset++) {
                assertThat(keyBytes[offset])
                        .as("byte at 0-based offset %d", offset)
                        .isEqualTo(whole[offset]);
            }
            // The standalone key value renders the same 17 bytes as the embedded one.
            assertThat(record.tranCatKey().image(ASCII)).isEqualTo(record.tranCatKeyImage());
            assertThat(record.tranCatKey())
                    .isEqualTo(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
        }

        @Test
        @DisplayName("the key stops short of TRAN-CAT-BAL: offset 17 is outside it")
        void theKeyStopsShortOfTheBalance() {
            // The key spans 0..16 inclusive; the balance begins at 17. 0 + 17 = 17, so the first byte
            // of the balance is the first byte the key does not cover.
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_OFFSET + TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_OFFSET);

            TranCatBalRecord record = decoded(ROW_1);
            String keyBefore = record.tranCatKeyImage();
            // Moving the balance must not disturb one byte of the key.
            record.tranCatBal(new BigDecimal("4321.99"));
            assertThat(record.tranCatKeyImage())
                    .as("a balance store lies wholly beyond the key")
                    .isEqualTo(keyBefore);
            assertThat(record.tranCatKey())
                    .isEqualTo(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
            // And symmetrically, the key image never carries any part of the balance image.
            assertThat(record.tranCatKeyImage()).doesNotContain(record.tranCatBalImage());
        }

        @Test
        @DisplayName("mutating an item moves the key image with it - the first direction of the view")
        void mutatingAnItemMovesTheKeyImage() {
            TranCatBalRecord record = decoded(ROW_1);
            record.trancatCd(2);
            assertThat(record.tranCatKeyImage())
                    .isEqualTo("00000000001" + FIXTURE_TYPE_CD + "0002");
            record.trancatAcctId(50L);
            assertThat(record.tranCatKeyImage())
                    .isEqualTo("00000000050" + FIXTURE_TYPE_CD + "0002");
            record.trancatTypeCd("AB");
            assertThat(record.tranCatKeyImage()).isEqualTo("00000000050" + "AB" + "0002");
            // The key value type follows the same bytes, because it is read from them.
            assertThat(record.tranCatKey()).isEqualTo(new TranCatKey(50L, "AB", 2));
            // Bytes 17..49 are untouched by any of that: the balance and FILLER still read as shipped.
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("writing a key image moves the three items with it - the second direction")
        void writingAKeyImageMovesTheThreeItems() {
            // Decode a 17-byte image into the value type, then write it into a record and read the three
            // items back out. Round-tripped in both directions, which is what gate G34 asks for.
            String keyImage = "00000000042" + "07" + "0009";
            TranCatKey key = TranCatKey.decode(keyImage, ASCII);
            assertThat(key.trancatAcctId()).isEqualTo(42L);
            assertThat(key.trancatTypeCd()).isEqualTo("07");
            assertThat(key.trancatCd()).isEqualTo(9);

            TranCatBalRecord record = decoded(ROW_1).tranCatKey(key);
            assertThat(record.trancatAcctId()).isEqualTo(42L);
            assertThat(record.trancatTypeCd()).isEqualTo("07");
            assertThat(record.trancatCd()).isEqualTo(9);
            assertThat(record.tranCatKeyImage()).isEqualTo(keyImage);
            // Round trip closed: the record's key renders the image it was written from.
            assertThat(TranCatKey.decode(record.tranCatKeyBytes(), ASCII)).isEqualTo(key);
        }

        // ignoreLeadingAndTrailingWhitespace is switched OFF deliberately: the trailing space of a
        // right-padded PIC X value is the very thing under test, and JUnit would otherwise trim it away
        // and let a trimming defect pass.
        @ParameterizedTest(name = "TRANCAT-TYPE-CD X(02): \"{0}\" stores as \"{1}\"")
        @DisplayName("PIC X pads and truncates on the RIGHT - the opposite end from PIC 9")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "01|01",      // exact width: stored unchanged
            "1|1 ",       // short: right-space-padded, and the trailing space is NOT trimmed away
            "0123|01",    // over-long: COBOL fills from the left and discards the overflow
            "AB|AB",
            "  |  "       // all spaces: a blanked field is still two bytes wide
        })
        void thePicXComponentPadsAndTruncatesOnTheRight(String supplied, String stored) {
            TranCatBalRecord record = decoded(ROW_1).trancatTypeCd(supplied);
            assertThat(record.trancatTypeCd()).isEqualTo(stored);
            assertThat(record.trancatTypeCd()).hasSize(2);
            // The span still starts at 11 and ends at 13, whatever was moved into it.
            assertThat(record.rawImage().substring(11, 13)).isEqualTo(stored);
            assertThat(record.rawImage()).hasSize(50);
        }

        @Test
        @DisplayName("PIC 9 zero-fills and truncates on the LEFT, keeping the low-order digits")
        void thePic9ComponentsZeroFillAndTruncateOnTheLeft() {
            // Short: a numeric receiver is aligned on its implied decimal point, so the value is
            // left-zero-filled to the declared width.
            TranCatBalRecord record = decoded(ROW_1).trancatAcctId(7L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000007");
            assertThat(record.trancatAcctId()).isEqualTo(7L);
            assertThat(record.rawImage().substring(0, 11)).isEqualTo("00000000007");

            // Over-long: the digits that survive are the LOW-order ones. Keeping the leading digits
            // instead is the classic defect, so it is asserted against explicitly.
            record.trancatAcctId(123456789012L);        // 12 digits into PIC 9(11)
            assertThat(record.trancatAcctIdImage())
                    .as("the low-order 11 digits survive")
                    .isEqualTo("23456789012")
                    .isNotEqualTo("12345678901");
            assertThat(record.trancatAcctId()).isEqualTo(23456789012L);

            record.trancatCd(12345);                     // 5 digits into PIC 9(04)
            assertThat(record.trancatCdImage())
                    .as("the low-order 4 digits survive")
                    .isEqualTo("2345")
                    .isNotEqualTo("1234");
            assertThat(record.trancatCd()).isEqualTo(2345);

            // Widths never change, whatever was moved in.
            assertThat(record.trancatAcctIdImage()).hasSize(11);
            assertThat(record.trancatCdImage()).hasSize(4);
            assertThat(record.rawImage()).hasSize(50);
        }

        @Test
        @DisplayName("TRANCAT-TYPE-CD decodes untrimmed, so a padded value keeps its trailing space")
        void theTypeCodeDecodesUntrimmed() {
            // The shipped value is exactly "01": two characters, nothing to trim.
            assertThat(decoded(ROW_1).trancatTypeCd()).isEqualTo("01");
            // A one-character value is stored right-space-padded and read back with that space intact.
            // Trimming here would make "1" and "1 " indistinguishable, and the parity differ compares
            // the stored bytes rather than a tidied value.
            TranCatBalRecord record = decoded(ROW_1).trancatTypeCd("1");
            assertThat(record.trancatTypeCd()).isEqualTo("1 ").isNotEqualTo("1");
            assertThat(record.trancatTypeCd().charAt(1)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the key round-trips under EBCDIC too, on different bytes but the same values")
        void theKeyRoundTripsUnderEbcdicToo() {
            TranCatKey key = new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            byte[] ascii = key.toByteArray(ASCII);
            byte[] ebcdic = key.toByteArray(EBCDIC);
            assertThat(ascii).hasSize(17);
            assertThat(ebcdic).hasSize(17);
            // Same width, different bytes: the code page is honoured rather than assumed, which is why
            // every entry point on this type demands one.
            assertThat(ebcdic).isNotEqualTo(ascii);
            assertThat(TranCatKey.decode(ebcdic, EBCDIC)).isEqualTo(key);
            assertThat(key.image(EBCDIC)).isEqualTo(key.image(ASCII));
        }

        @Test
        @DisplayName("null is rejected wherever a value is structurally required")
        void nullIsRejectedWhereAValueIsStructurallyRequired() {
            // PIC X(02) has no null: COBOL blanks a field by moving SPACES, and so must a caller.
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranCatKey(1L, null, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode("00000000001010001", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).tranCatKey(null))
                    .withMessageContaining("TRAN-CAT-KEY");
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).trancatTypeCd(null));
        }

        @Test
        @DisplayName("a key image of the wrong width is rejected rather than silently widened")
        void aKeyImageOfTheWrongWidthIsRejected() {
            // 16 characters - the CVTRA02Y width - must not be accepted as a 17-byte key.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatKey.decode("0000000000101000", ASCII))
                    .withMessageContaining("17");
            // And neither must 18.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatKey.decode("000000000010100010", ASCII));
        }

        @Test
        @DisplayName("a negative key component has no representation in PIC 9 and is refused")
        void aNegativeKeyComponentIsRefused() {
            // PIC 9 declares no sign position, so there is nowhere for a minus to go. A signed value
            // belongs in a PIC S9 field, which is what TRAN-CAT-BAL is and these three are not.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decoded(ROW_1).trancatAcctId(-1L));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decoded(ROW_1).trancatCd(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TranCatKey(-1L, FIXTURE_TYPE_CD, 1).image(ASCII));
        }
    }

    @Nested
    @DisplayName("The shipped TCATBALF rows, decoded at the copybook's absolute offsets")
    class ShippedFixtureRows {

        @Test
        @DisplayName("row 1 is 50 bytes and decodes field for field, with nothing trimmed")
        void rowOneIsFiftyBytesAndDecodesFieldForField() {
            assertThat(ROW_1).as("app/data/ASCII/tcatbal.txt row 1").hasSize(50);
            TranCatBalRecord record = decoded(ROW_1);

            assertThat(record.recordLength()).isEqualTo(50);
            // 1-based bytes 1-11 -> 0-based 0..10
            assertThat(record.trancatAcctId()).isEqualTo(1L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000001");
            // 1-based bytes 12-13 -> 0-based 11..12. Alphanumeric, so read back untrimmed.
            assertThat(record.trancatTypeCd()).isEqualTo("01");
            // 1-based bytes 14-17 -> 0-based 13..16
            assertThat(record.trancatCd()).isEqualTo(1);
            assertThat(record.trancatCdImage()).isEqualTo("0001");
            // 1-based bytes 18-28 -> 0-based 17..27
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            // 1-based bytes 29-50 -> 0-based 28..49
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.fillerImage()).hasSize(22);

            // Each item read again straight out of the raw image at its absolute offsets, so the
            // accessors are checked against the bytes and not merely against each other (R5).
            String raw = record.rawImage();
            assertThat(raw).isEqualTo(ROW_1).hasSize(50);
            assertThat(raw.substring(0, 11)).isEqualTo("00000000001");
            assertThat(raw.substring(11, 13)).isEqualTo("01");
            assertThat(raw.substring(13, 17)).isEqualTo("0001");
            assertThat(raw.substring(17, 28)).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(raw.substring(28, 50)).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("the shipped balance 0000000000{ is +0.00 at scale 2, not an unset field")
        void theShippedBalanceIsPositiveZeroAtScaleTwo() {
            TranCatBalRecord record = decoded(ROW_1);
            BigDecimal balance = record.tranCatBal();

            // Scale is asserted exactly, because compareTo alone is scale-blind and would accept a
            // value of scale 0 that no PIC S9(09)V99 field could ever hold (R3, G23).
            assertThat(balance.scale()).as("PIC S9(09)V99 has scale exactly 2").isEqualTo(2);
            assertThat(balance).isEqualTo(new BigDecimal("0.00"));
            assertThat(balance.signum()).isZero();
            assertThat(record.tranCatBalIsZero()).isTrue();

            // The trailing glyph is the positive-zero overpunch, so the sign is present and positive.
            char trailing = FIXTURE_BALANCE_IMAGE.charAt(FIXTURE_BALANCE_IMAGE.length() - 1);
            assertThat(trailing).isEqualTo(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.digitOf(trailing)).isZero();
            assertThat(ZonedSign.isNegative(trailing)).isFalse();
            // The ten bytes before it are the remaining digits, all zero.
            assertThat(FIXTURE_BALANCE_IMAGE.substring(0, 10)).isEqualTo("0000000000");
        }

        @Test
        @DisplayName("rows 1, 4 and 50 differ only in the account, so the account is the varying key part")
        void theRowsDifferOnlyInTheAccountIdentifier() {
            // The fixture was measured across all 50 rows: TRANCAT-TYPE-CD is 01 and TRANCAT-CD is 0001
            // in every one of them, and the account identifiers run 00000000001 to 00000000050.
            assertThat(decoded(ROW_1).trancatAcctId()).isEqualTo(1L);
            assertThat(decoded(ROW_4).trancatAcctId()).isEqualTo(4L);
            assertThat(decoded(ROW_50).trancatAcctId()).isEqualTo(50L);

            for (String row : List.of(ROW_1, ROW_4, ROW_50)) {
                TranCatBalRecord record = decoded(row);
                assertThat(row).hasSize(50);
                assertThat(record.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
                assertThat(record.trancatCd()).isEqualTo(FIXTURE_CAT_CD);
                assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
                assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            }
            // Distinct accounts, so distinct keys - which is what makes the 17-byte key selective.
            assertThat(decoded(ROW_1).tranCatKey()).isNotEqualTo(decoded(ROW_4).tranCatKey());
        }

        @Test
        @DisplayName("a decoded row re-serialises byte for byte, its zero-filled FILLER included")
        void aDecodedRowReserialisesByteForByte() {
            TranCatBalRecord record = decoded(ROW_1);
            // Lossless: what came in goes back out, all 50 bytes of it. If the codec emitted spaces over
            // the fixture's zero-filled FILLER that would be a 22-byte difference per record which no
            // field-level comparison could see and no width check could catch.
            assertThat(record.encode()).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record.encode()).hasSize(50);
            assertThat(record.rawImage()).isEqualTo(ROW_1);
            assertThat(record.fillerImage())
                    .as("still the shipped zeros, not spaces")
                    .isEqualTo(FIXTURE_FILLER_IMAGE)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("FILLER survives the read-modify-rewrite cycle that CBTRN02C:527-528 performs")
        void theFillerSurvivesARewrite() {
            // The update path reads a record, adds to the balance and REWRITEs it. The reserved bytes
            // that came in have to go back out verbatim, so they are asserted after the mutation.
            TranCatBalRecord record = decoded(ROW_1).addToTranCatBal(new BigDecimal("10.00"));

            // 10.00 is unscaled 1000, left-zero-filled to 11 as 00000001000, whose low-order digit 0
            // becomes the positive overpunch '{': 0000000100{.
            assertThat(record.tranCatBalImage()).isEqualTo("0000000100{");
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.tranCatKeyImage()).isEqualTo(decoded(ROW_1).tranCatKeyImage());
            // The whole rewritten image is the shipped row with only bytes 17..27 changed.
            assertThat(record.rawImage()).isEqualTo(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, "0000000100{", FIXTURE_FILLER_IMAGE));
        }

        @Test
        @DisplayName("fieldImages names the four items in copybook order and excludes FILLER")
        void fieldImagesNamesTheFourItemsInOrderAndExcludesFiller() {
            Map<String, String> images = decoded(ROW_1).fieldImages();
            // FILLER is not a referable COBOL name, so it is not a field the differ can compare by name;
            // it is compared as part of the record image instead.
            assertThat(images.keySet()).containsExactly(
                    TranCatBalRecord.TRANCAT_ACCT_ID_NAME,
                    TranCatBalRecord.TRANCAT_TYPE_CD_NAME,
                    TranCatBalRecord.TRANCAT_CD_NAME,
                    TranCatBalRecord.TRAN_CAT_BAL_NAME);
            assertThat(images).hasSize(4);
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_ACCT_ID_NAME, "00000000001");
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_TYPE_CD_NAME, FIXTURE_TYPE_CD);
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_CD_NAME, FIXTURE_CAT_CD_IMAGE);
            assertThat(images).containsEntry(TranCatBalRecord.TRAN_CAT_BAL_NAME,
                    FIXTURE_BALANCE_IMAGE);
        }

        @Test
        @DisplayName("the field image map is unmodifiable, so a caller cannot rewrite the record through it")
        void theFieldImageMapIsUnmodifiable() {
            Map<String, String> images = decoded(ROW_1).fieldImages();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put(TranCatBalRecord.TRAN_CAT_BAL_NAME, "0000000001A"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.remove(TranCatBalRecord.TRANCAT_CD_NAME));
        }

        @Test
        @DisplayName("every byte array handed out is a copy, so the 50-byte area cannot be reached")
        void everyByteArrayHandedOutIsACopy() {
            TranCatBalRecord record = decoded(ROW_1);

            byte[] whole = record.encode();
            whole[TranCatBalRecord.TRAN_CAT_BAL_OFFSET] = (byte) '9';
            byte[] key = record.tranCatKeyBytes();
            key[0] = (byte) '9';
            byte[] filler = record.fillerBytes();
            filler[0] = (byte) ' ';

            // The record is the single source of truth and is unchanged by any of that.
            assertThat(record.rawImage()).isEqualTo(ROW_1);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.fillerBytes()).hasSize(22);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aRowOfTheWrongWidthIsRejected() {
            // 49: one byte short, which is what a dropped FILLER byte looks like.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1.substring(0, 49), ASCII))
                    .withMessageContaining("50");
            // 51: one byte long, which is what a sign byte wrongly reserved for the balance looks like.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1 + "0", ASCII));
            // Both directions drive the same guard, so neither branch of it is left unproven (G50).
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(new byte[36], ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that needs a value")
        void nullIsRejectedAtEveryEntryPoint() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.newInstance(null));
        }
    }

    @Nested
    @DisplayName("The zoned sign overpunch - both halves of the alphabet (gate G50)")
    class SignOverpunch {

        // The fixture exercises only '{', so the negative half would otherwise never be reached. The
        // spans below are synthetic 11-byte TRAN-CAT-BAL images placed in an otherwise shipped row, so
        // both halves of the alphabet are driven at the record's own offset 17.
        //   positive digits 0-9 -> { A B C D E F G H I
        //   negative digits 0-9 -> } J K L M N O P Q R
        @ParameterizedTest(name = "{0} decodes to {1}")
        @DisplayName("a trailing glyph from either half decodes to its signed value at scale 2")
        @CsvSource({
            "0000000000{, 0.00",        // positive zero
            "0000000000A, 0.01",        // positive 1
            "0000000000G, 0.07",        // positive 7
            "0000000000I, 0.09",        // positive 9, the last of the positive half
            "0000000000}, 0.00",        // negative zero: still zero, and it must not fail to decode
            "0000000000J, -0.01",       // negative 1
            "0000000000R, -0.09",       // negative 9, the last of the negative half
            "0000123456C, 12345.63",    // a full-width positive value, low-order digit 3
            "0000123456L, -12345.63",   // the same magnitude negative, low-order digit 3
            "9999999999I, 999999999.99" // the largest value PIC S9(09)V99 can hold
        })
        void aTrailingGlyphFromEitherHalfDecodes(String balanceImage, String expected) {
            assertThat(balanceImage).as("PIC S9(09)V99 is 11 bytes").hasSize(11);
            TranCatBalRecord record = decoded(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, balanceImage, FIXTURE_FILLER_IMAGE));

            BigDecimal balance = record.tranCatBal();
            assertThat(balance.scale()).as("the declared scale is always reported").isEqualTo(2);
            assertThat(balance).isEqualByComparingTo(new BigDecimal(expected));
            // The bytes are untouched by a read, so the image still reads exactly as supplied.
            assertThat(record.tranCatBalImage()).isEqualTo(balanceImage);
            // And the key ahead of it and the FILLER behind it are unaffected at their own offsets.
            assertThat(record.tranCatKeyImage()).hasSize(17);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @ParameterizedTest(name = "{0} stores as {1}")
        @DisplayName("a stored value carries the overpunch for its sign into the trailing byte")
        @CsvSource({
            "0.00, 0000000000{",
            "0.01, 0000000000A",
            "0.07, 0000000000G",
            "0.09, 0000000000I",
            "-0.01, 0000000000J",
            "-0.09, 0000000000R",
            "-0.10, 0000000001}",        // negative with a low-order digit of 0 -> '}'
            "12345.63, 0000123456C",
            "-12345.63, 0000123456L",
            "999999999.99, 9999999999I",
            "-999999999.99, 9999999999R"
        })
        void aStoredValueCarriesTheOverpunchForItsSign(String value, String expectedImage) {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal(value));
            assertThat(record.tranCatBalImage()).isEqualTo(expectedImage).hasSize(11);
            // Round trip: the image reads back as the value that was stored, at scale 2.
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal(value));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            // A store touches bytes 17..27 only.
            assertThat(record.tranCatKeyImage()).isEqualTo(decoded(ROW_1).tranCatKeyImage());
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("+0.00 encodes as 0000000000{ and never as eleven plain digits")
        void positiveZeroEncodesAsTheBraceNeverAsAPlainDigit() {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("0.00"));
            // A codec that emitted a plain '0' for the positive-zero digit would corrupt the record:
            // the trailing byte carries the sign as well as the digit, so dropping the zone loses the
            // sign entirely. All 50 shipped rows carry '{' here, which is what makes this the norm
            // rather than an edge case.
            assertThat(record.tranCatBalImage())
                    .isEqualTo("0000000000{")
                    .isNotEqualTo("00000000000");
            assertThat(record.tranCatBalImage().charAt(10)).isEqualTo(ZonedSign.POSITIVE_ZERO);
            assertThat(record.encode()).isEqualTo(ROW_1.getBytes(ASCII));
        }

        @Test
        @DisplayName("a negative value whose low-order digit is zero keeps the negative-zero glyph")
        void aNegativeValueWithAZeroLowOrderDigitKeepsItsGlyph() {
            // -0.10 is unscaled -10, so the magnitude's digits are 00000000010 and the low-order digit
            // is 0: the negative half's zero glyph '}' is the only faithful encoding.
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("-0.10"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000001}");
            assertThat(record.tranCatBalImage().charAt(10)).isEqualTo(ZonedSign.NEGATIVE_ZERO);
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-0.10"));
            assertThat(record.tranCatBal().signum()).isNegative();
            assertThat(record.tranCatBalIsZero()).isFalse();
        }

        @Test
        @DisplayName("the two overpunch alphabets are the zoned tables, C-zone and D-zone")
        void theTwoOverpunchAlphabetsAreTheZonedTables() {
            // IBM037 decodes 0xC0-0xC9 to {ABCDEFGHI and 0xD0-0xD9 to }JKLMNOPQR, and the ASCII
            // fixtures spell those same characters. Both are 10 characters, one per decimal digit.
            assertThat(ZonedSign.POSITIVE_DIGITS).isEqualTo("{ABCDEFGHI").hasSize(10);
            assertThat(ZonedSign.NEGATIVE_DIGITS).isEqualTo("}JKLMNOPQR").hasSize(10);
            assertThat(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.NEGATIVE_ZERO).isEqualTo('}');
            // Every glyph this file drives, checked against the table it came from.
            assertThat(ZonedSign.overpunch(0, false)).isEqualTo('{');
            assertThat(ZonedSign.overpunch(1, false)).isEqualTo('A');
            assertThat(ZonedSign.overpunch(7, false)).isEqualTo('G');
            assertThat(ZonedSign.overpunch(9, false)).isEqualTo('I');
            assertThat(ZonedSign.overpunch(0, true)).isEqualTo('}');
            assertThat(ZonedSign.overpunch(1, true)).isEqualTo('J');
            assertThat(ZonedSign.overpunch(9, true)).isEqualTo('R');
            assertThat(ZonedSign.digitOf('G')).isEqualTo(7);
            assertThat(ZonedSign.digitOf('P')).isEqualTo(7);
            assertThat(ZonedSign.isNegative('P')).isTrue();
            assertThat(ZonedSign.isNegative('G')).isFalse();
        }

        @Test
        @DisplayName("a balance span that is neither digits nor an overpunch is refused, not guessed at")
        void anUnrecognisedBalanceSpanIsRefused() {
            // A trailing character outside both alphabets and outside 0-9 carries no digit and no sign.
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, "0000000000*", FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
            // A non-digit among the leading ten is equally unreadable.
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, "00000X0000{", FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
            // A blank span - which is what an uninitialised area would hold - is refused too, rather
            // than being read as zero.
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, " ".repeat(11), FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
        }
    }

    @Nested
    @DisplayName("Numeric parity - truncation, never rounding (gates G22, G23, G24, G25)")
    class NumericParity {

        @Test
        @DisplayName("the balance handed out is a scale-2 BigDecimal, fit to feed the interest formula")
        void theBalanceHandedOutIsAScaleTwoBigDecimal() {
            // Gate G25 corroboration, not the formula itself. This type's TRAN-CAT-BAL is the
            // MULTIPLICAND in app/cbl/CBACT04C.cbl:464-465:
            //     COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
            // There is no ROUNDED on that statement and the receiver WS-MONTHLY-INT is PIC S9(09)V99
            // (declared at :168), so the product is truncated to two decimal places on store. What this
            // type owes that computation is a value of the exact declared scale, carried as a BigDecimal
            // and never as a binary float, so that the truncation happens once and where it is written.
            // The formula's own assertions belong to the AccountInterestCalcJob test and are not
            // duplicated here.
            BigDecimal shipped = decoded(ROW_1).tranCatBal();
            assertThat(shipped).isInstanceOf(BigDecimal.class);
            assertThat(shipped.scale()).isEqualTo(2);
            assertThat(shipped.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);

            // The same holds after any store, whatever scale the caller supplied.
            for (String value : List.of("0", "0.0", "0.00", "1", "1.5", "-1.5", "123456789")) {
                BigDecimal stored = decoded(ROW_1).tranCatBal(new BigDecimal(value)).tranCatBal();
                assertThat(stored.scale())
                        .as("a %s-scaled sender still lands at scale 2", value)
                        .isEqualTo(2);
            }

            // The rounding policy the store obeys is truncation toward zero, named in exactly one place.
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("an over-precise store truncates toward zero for a positive and a negative value")
        void anOverPreciseStoreTruncatesTowardZero() {
            // The keyword ROUNDED appears zero times across all 28 COBOL programs, so COBOL discards the
            // excess fractional digits rather than rounding them. 1.239 therefore stores as 1.23; a
            // half-up policy would have produced 1.24 and would be the defect, not the fix.
            TranCatBalRecord positive = decoded(ROW_1).tranCatBal(new BigDecimal("1.239"));
            assertThat(positive.tranCatBal()).isEqualByComparingTo(new BigDecimal("1.23"));
            assertThat(positive.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("1.24"));
            assertThat(positive.tranCatBal().scale()).isEqualTo(2);
            // 1.23 is unscaled 123, left-zero-filled to 00000000123, low-order digit 3 positive -> 'C'.
            assertThat(positive.tranCatBalImage()).isEqualTo("0000000012C");

            // Truncation toward zero is sign-symmetric, which is why DOWN is right and a floor policy is
            // not: -1.239 stores as -1.23, whereas flooring would have produced -1.24.
            TranCatBalRecord negative = decoded(ROW_1).tranCatBal(new BigDecimal("-1.239"));
            assertThat(negative.tranCatBal()).isEqualByComparingTo(new BigDecimal("-1.23"));
            assertThat(negative.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("-1.24"));
            assertThat(negative.tranCatBal().scale()).isEqualTo(2);
            // Same digits, negative low-order 3 -> 'L'.
            assertThat(negative.tranCatBalImage()).isEqualTo("0000000012L");

            // A value that needs no truncation is stored unchanged, which is the other branch.
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("2.50")).tranCatBalImage())
                    .isEqualTo("0000000025{");
        }

        @Test
        @DisplayName("a sender of smaller scale is padded up, which is exact and never truncates")
        void aSenderOfSmallerScaleIsPaddedUp() {
            // Scaling up adds trailing zeros and loses nothing; it is what gives the fixed-width writer
            // the two fractional digits the PICTURE declares.
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("7"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            // 7.00 is unscaled 700 -> 00000000700, low-order digit 0 positive -> '{'.
            assertThat(record.tranCatBalImage()).isEqualTo("0000000070{");
        }

        @Test
        @DisplayName("integer digits beyond the ninth are discarded silently, as COBOL discards them")
        void integerDigitsBeyondTheNinthAreDiscardedSilently() {
            // ON SIZE ERROR appears zero times in the source, so a COBOL store into PIC S9(09)V99
            // truncates at the high end too and reports nothing. The receiver keeps its low-order nine
            // integer digits and the sign of the computed value.
            TranCatBalRecord positive = decoded(ROW_1).tranCatBal(new BigDecimal("12345678901.23"));
            assertThat(positive.tranCatBal()).isEqualByComparingTo(new BigDecimal("345678901.23"));
            assertThat(positive.tranCatBalImage()).isEqualTo("3456789012C");

            TranCatBalRecord negative = decoded(ROW_1).tranCatBal(new BigDecimal("-12345678901.23"));
            assertThat(negative.tranCatBal()).isEqualByComparingTo(new BigDecimal("-345678901.23"));
            assertThat(negative.tranCatBalImage()).isEqualTo("3456789012L");

            // A value that fits takes the other branch and is stored whole.
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("999999999.99")).tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));
        }

        @Test
        @DisplayName("ADD ... TO TRAN-CAT-BAL accumulates at scale 2 and subtracts for a negative addend")
        void addAccumulatesAtScaleTwo() {
            // The update path at app/cbl/CBTRN02C.cbl:527 adds the daily transaction amount to the
            // balance just read, then REWRITEs. The augend is therefore whatever was on the dataset.
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);

            record.addToTranCatBal(new BigDecimal("12.34"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000123D");

            record.addToTranCatBal(new BigDecimal("12.34"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("24.68"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000246H");

            // COBOL's ADD of a negative amount subtracts, and the stored sign follows the result.
            record.addToTranCatBal(new BigDecimal("-30.00"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-5.32"));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            assertThat(record.tranCatBal().signum()).isNegative();
            // -5.32 is magnitude 532 -> 00000000532, negative low-order 2 -> 'K'.
            assertThat(record.tranCatBalImage()).isEqualTo("0000000053K");

            // An addend of finer scale is truncated on store, not rounded.
            record.addToTranCatBal(new BigDecimal("0.005"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-5.31"));
        }

        @Test
        @DisplayName("ADD requires an addend, because COBOL has no null to add")
        void addRequiresAnAddend() {
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).addToTranCatBal(null))
                    .withMessageContaining("ADD");
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).tranCatBal(null));
        }

        @Test
        @DisplayName("tranCatBalIsZero answers by sign, so 0.00 and a truncated 0.001 are both zero")
        void zeroIsDecidedBySignNotByScale() {
            assertThat(decoded(ROW_1).tranCatBalIsZero()).isTrue();
            // A scale-0 zero is still zero: signum is scale-blind where equals is not, and BigDecimal
            // equality would judge 0.00 and 0 unequal.
            assertThat(decoded(ROW_1).tranCatBal(BigDecimal.ZERO).tranCatBalIsZero()).isTrue();
            // 0.001 truncates to 0.00, so a record built from it is zero as well.
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("0.001")).tranCatBalIsZero()).isTrue();
            // And the false branch.
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("0.01")).tranCatBalIsZero()).isFalse();
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("-0.01")).tranCatBalIsZero()).isFalse();
        }
    }

    @Nested
    @DisplayName("INITIALIZE - reproduced from CBTRN02C:503-509, FILLER included (practice B5)")
    class InitializeSemantics {

        // app/cbl/CBTRN02C.cbl:503-509, paragraph 2700-A-CREATE-TCATBAL-REC, reads verbatim:
        //
        //     INITIALIZE TRAN-CAT-BAL-RECORD
        //     MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
        //     MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
        //     MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
        //     ADD DALYTRAN-AMT TO TRAN-CAT-BAL
        //
        // followed by WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD. Two consequences are
        // load-bearing, and each has its own test below: the ADD immediately after the INITIALIZE proves
        // the balance must be exactly zero at that point, and COBOL's INITIALIZE without REPLACING does
        // not touch FILLER at all. The paragraph is cited here ONLY as the source of this contract; the
        // create-versus-update discrimination around it belongs to the job's own test.

        @Test
        @DisplayName("the balance is zeroed to scale 2, so the following ADD cannot accumulate onto stale data")
        void theBalanceIsZeroedSoTheFollowingAddStartsFromZero() {
            // Start from a record carrying a real balance - the shipped rows are all +0.00 and so could
            // not tell a zeroing INITIALIZE from a no-op one.
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.tranCatBal())
                    .as("the stale value the create path must discard")
                    .isEqualByComparingTo(new BigDecimal("123.45"));

            record.initialize();
            assertThat(record.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.tranCatBal().scale())
                    .as("zero at the declared scale, not a scale-0 zero")
                    .isEqualTo(2);
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.tranCatBalIsZero()).isTrue();

            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL. The augend is the initialised zero, so the result is
            // exactly the amount added. Had INITIALIZE left 123.45 in place, this would read 198.95.
            record.addToTranCatBal(new BigDecimal("75.50"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("75.50"));
            assertThat(record.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("198.95"));
            // 75.50 is unscaled 7550 -> 00000007550, low-order digit 0 positive -> '{'.
            assertThat(record.tranCatBalImage()).isEqualTo("0000000755{");
        }

        @Test
        @DisplayName("FILLER is left exactly as it was, which is preserved behaviour and not a defect")
        void theFillerIsLeftExactlyAsItWas() {
            // COBOL's INITIALIZE without REPLACING implies SPACE for alphanumeric items and ZERO for
            // numeric items, and it SKIPS FILLER entirely. On the create-on-miss path the record area
            // still holds whatever the immediately preceding READ left there, so a newly created record
            // inherits the previous row's reserved bytes. That is reproduced here rather than tidied:
            // "cleaning it up" to spaces would be a 22-byte difference per created record that the
            // parity differ reports and that no field-level comparison could see.
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);

            record.initialize();

            assertThat(record.fillerImage())
                    .as("still the twenty-two ASCII zeros the row was read with")
                    .isEqualTo(FIXTURE_FILLER_IMAGE)
                    .isNotEqualTo(" ".repeat(22));
            // Asserted at absolute offsets 28..49 as well, so the claim rests on bytes and not on an
            // accessor that might itself be reading the wrong span.
            byte[] image = record.encode();
            byte[] zero = "0".getBytes(ASCII);
            for (int offset = TranCatBalRecord.FILLER_OFFSET;
                    offset < TranCatBalRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at 0-based offset %d is still an ASCII zero", offset)
                        .isEqualTo(zero[0]);
            }
            assertThat(record.encode()).hasSize(50);
        }

        @Test
        @DisplayName("numeric items go to ZERO and the alphanumeric item goes to SPACES")
        void numericItemsGoToZeroAndTheAlphanumericItemToSpaces() {
            // ROW_WITH_STALE_BALANCE carries account 9, type "AB" and category 7, so both category
            // branches of INITIALIZE are driven by one call and neither can be mistaken for the other.
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.trancatAcctId()).isEqualTo(9L);
            assertThat(record.trancatTypeCd()).isEqualTo("AB");
            assertThat(record.trancatCd()).isEqualTo(7);

            record.initialize();

            // PIC 9(11) and PIC 9(04) are numeric, so INITIALIZE moves ZERO - which is zero DIGITS in
            // the stored image, not blanks.
            assertThat(record.trancatAcctId()).isZero();
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000000");
            assertThat(record.trancatCd()).isZero();
            assertThat(record.trancatCdImage()).isEqualTo("0000");
            // PIC X(02) is alphanumeric, so INITIALIZE moves SPACES - and the value is read untrimmed,
            // so it is two spaces and not an empty string.
            assertThat(record.trancatTypeCd()).isEqualTo("  ").hasSize(2);
            // The whole initialised image, spelled out: zeros, spaces, zeros, positive zero, and the
            // inherited FILLER.
            assertThat(record.rawImage()).isEqualTo(recordImage("00000000000", "  ", "0000",
                    FIXTURE_BALANCE_IMAGE, FIXTURE_FILLER_IMAGE));
        }

        @Test
        @DisplayName("initialize returns this and is idempotent, so a create path chains as COBOL sequences it")
        void initializeReturnsThisAndIsIdempotent() {
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.initialize()).isSameAs(record);
            String once = record.rawImage();
            assertThat(record.initialize().rawImage()).isEqualTo(once);
            // Every mutator returns this for the same reason, so the paragraph reads as one chain.
            assertThat(record.trancatAcctId(1L)).isSameAs(record);
            assertThat(record.trancatTypeCd("01")).isSameAs(record);
            assertThat(record.trancatCd(1)).isSameAs(record);
            assertThat(record.tranCatBal(BigDecimal.ZERO)).isSameAs(record);
            assertThat(record.addToTranCatBal(BigDecimal.ONE)).isSameAs(record);
            assertThat(record.tranCatKey(new TranCatKey(1L, "01", 1))).isSameAs(record);
        }

        @Test
        @DisplayName("the whole create-on-miss paragraph reproduces statement for statement")
        void theWholeCreateOnMissParagraphReproducesStatementForStatement() {
            // The five statements of 2700-A-CREATE-TCATBAL-REC, in source order, over a record area that
            // still holds the previous READ - which is exactly the situation the paragraph runs in.
            long xrefAcctId = 42L;                              // XREF-ACCT-ID
            String dalytranTypeCd = "05";                       // DALYTRAN-TYPE-CD
            int dalytranCatCd = 3;                              // DALYTRAN-CAT-CD
            BigDecimal dalytranAmt = new BigDecimal("250.75");  // DALYTRAN-AMT

            TranCatBalRecord created = decoded(ROW_WITH_STALE_BALANCE)
                    .initialize()
                    .trancatAcctId(xrefAcctId)
                    .trancatTypeCd(dalytranTypeCd)
                    .trancatCd(dalytranCatCd)
                    .addToTranCatBal(dalytranAmt);

            // 250.75 is unscaled 25075 -> 00000025075, low-order digit 5 positive -> 'E'.
            assertThat(created.rawImage()).isEqualTo(recordImage("00000000042", "05", "0003",
                    "0000002507E", FIXTURE_FILLER_IMAGE));
            assertThat(created.encode()).hasSize(50);
            assertThat(created.tranCatBal()).isEqualByComparingTo(dalytranAmt);
            assertThat(created.tranCatKey()).isEqualTo(new TranCatKey(42L, "05", 3));
            // And the record that would be WRITTEN carries the inherited FILLER, unaltered.
            assertThat(created.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }
    }

    @Nested
    @DisplayName("A freshly allocated record - the write path from nothing (gates G19, G21)")
    class FreshlyAllocatedRecord {

        @Test
        @DisplayName("FILLER X(22) at offset 28 is space-filled, which does NOT contradict the read path")
        void theFillerIsSpaceFilledOnAFreshRecord() {
            // Gate G21 is about a record built from nothing: there are no inherited bytes to carry
            // through, so the reserved span is space-filled, which is COBOL's alphanumeric default.
            // The read path asserts the opposite-looking thing - a decoded row keeps its twenty-two
            // ASCII zeros - and the two are not in conflict: allocation chooses the default, whereas a
            // decode has real bytes and must not overwrite them. That is precisely why initialize()
            // leaves FILLER alone: it cannot know which of the two situations it is in.
            TranCatBalRecord fresh = TranCatBalRecord.newInstance(ASCII);
            assertThat(fresh.fillerImage()).isEqualTo(" ".repeat(22)).hasSize(22);

            byte[] image = fresh.encode();
            byte[] space = " ".getBytes(ASCII);
            for (int offset = TranCatBalRecord.FILLER_OFFSET;
                    offset < TranCatBalRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at 0-based offset %d is a space", offset)
                        .isEqualTo(space[0]);
            }
            // A dropped FILLER would leave this record 28 bytes wide, so the width check catches it
            // immediately - which is why both the width and the span content are asserted.
            assertThat(image).hasSize(50);
        }

        @Test
        @DisplayName("the four named items are initialised and the record is exactly 50 bytes")
        void theFourNamedItemsAreInitialised() {
            TranCatBalRecord fresh = TranCatBalRecord.newInstance(ASCII);
            assertThat(fresh.recordLength()).isEqualTo(50);
            assertThat(fresh.trancatAcctId()).isZero();
            assertThat(fresh.trancatAcctIdImage()).isEqualTo("00000000000");
            assertThat(fresh.trancatTypeCd()).isEqualTo("  ");
            assertThat(fresh.trancatCd()).isZero();
            assertThat(fresh.trancatCdImage()).isEqualTo("0000");
            assertThat(fresh.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.tranCatBal().scale()).isEqualTo(2);
            assertThat(fresh.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            // The whole image, spelled out from the copybook: 11 zeros, 2 spaces, 4 zeros, the
            // positive-zero balance and 22 spaces.
            assertThat(fresh.rawImage()).isEqualTo(recordImage("00000000000", "  ", "0000",
                    "0000000000{", " ".repeat(22)));
        }

        @Test
        @DisplayName("the whole 50-byte image is available, which is what DISPLAY of the record needs")
        void theWholeFiftyByteImageIsAvailable() {
            // app/cbl/CBACT04C.cbl:193 does DISPLAY TRAN-CAT-BAL-RECORD - the whole group item, not a
            // field of it - so a whole-record accessor is part of the contract. encode() is the byte
            // form and rawImage() the character form; both are the full width.
            assertThat(TranCatBalRecord.newInstance(ASCII).encode()).hasSize(50);
            assertThat(TranCatBalRecord.newInstance(ASCII).rawImage()).hasSize(50);

            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.encode()).hasSize(50).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record.rawImage()).hasSize(50).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("the code page is whatever was supplied and is never a platform default")
        void theCodePageIsWhateverWasSupplied() {
            assertThat(TranCatBalRecord.newInstance(ASCII).charset()).isEqualTo(ASCII);
            assertThat(TranCatBalRecord.newInstance(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(decoded(ROW_1).charset()).isEqualTo(ASCII);

            // The same logical values encode to different bytes under the two code pages, which is the
            // whole reason the charset is a required argument everywhere rather than a default.
            byte[] asciiImage = TranCatBalRecord.newInstance(ASCII).encode();
            byte[] ebcdicImage = TranCatBalRecord.newInstance(EBCDIC).encode();
            assertThat(ebcdicImage).hasSize(50);
            assertThat(ebcdicImage).isNotEqualTo(asciiImage);
        }
    }

    @Nested
    @DisplayName("Value semantics - the comparison the dataset itself makes")
    class ValueSemantics {

        @Test
        @DisplayName("two records over the same 50 bytes and the same code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            TranCatBalRecord first = decoded(ROW_1);
            TranCatBalRecord second = decoded(ROW_1);
            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a record equals itself without comparing a byte")
        void aRecordEqualsItself() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCatBalRecord is ever equal to one")
        void anythingElseIsNeverEqual() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record).isNotEqualTo(null);
            assertThat(record).isNotEqualTo(ROW_1);
            assertThat(record).isNotEqualTo(record.tranCatKey());
        }

        @Test
        @DisplayName("different bytes are not equal, and a FILLER difference is a difference")
        void differentBytesAreNotEqual() {
            assertThat(decoded(ROW_1)).isNotEqualTo(decoded(ROW_4));

            // Same key and same balance, but the reserved span differs. Equality takes in all 50 bytes,
            // so this is correctly unequal - which is what stops a 22-byte rewrite difference from
            // being mistaken for a match.
            TranCatBalRecord spaceFilled = decoded(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, FIXTURE_BALANCE_IMAGE, " ".repeat(22)));
            assertThat(spaceFilled).isNotEqualTo(decoded(ROW_1));
            assertThat(spaceFilled.tranCatKey()).isEqualTo(decoded(ROW_1).tranCatKey());
            assertThat(spaceFilled.tranCatBalImage()).isEqualTo(decoded(ROW_1).tranCatBalImage());
        }

        @Test
        @DisplayName("the same bytes under a different code page are not the same record")
        void theSameBytesUnderADifferentCodePageAreNotEqual() {
            byte[] bytes = ROW_1.getBytes(ASCII);
            assertThat(TranCatBalRecord.decode(bytes, ASCII))
                    .isNotEqualTo(TranCatBalRecord.decode(bytes, EBCDIC));
        }

        @Test
        @DisplayName("the key value type compares by its three components")
        void theKeyComparesByItsThreeComponents() {
            TranCatKey key = new TranCatKey(1L, "01", 1);
            assertThat(key).isEqualTo(new TranCatKey(1L, "01", 1));
            assertThat(key).hasSameHashCodeAs(new TranCatKey(1L, "01", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(2L, "01", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(1L, "02", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(1L, "01", 2));
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering - what it withholds while staying diagnosable")
    class DiagnosticRendering {

        // These two tests assert the PROPERTY - that nothing sensitive is disclosed and that the record
        // stays identifiable - rather than the particular placeholder token used to withhold a value.
        // The token is the business of the diagnostic helper's own test, and this file imports only the
        // types it was given as dependencies.

        @Test
        @DisplayName("the account identifier is masked and the balance is withheld entirely")
        void theAccountIsMaskedAndTheBalanceWithheld() {
            String rendered = decoded(ROW_1).toString();
            assertThat(rendered).startsWith(TranCatBalRecord.RECORD_NAME + "[");
            assertThat(rendered).endsWith("]");
            // Every item is named, so a record is still identifiable in a log.
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_ACCT_ID_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_TYPE_CD_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_CD_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRAN_CAT_BAL_NAME);
            // A monetary figure is never rendered, so the stored image must not appear anywhere in it.
            assertThat(rendered).doesNotContain(FIXTURE_BALANCE_IMAGE);
            // The account identifier is masked rather than printed whole.
            assertThat(rendered).doesNotContain("00000000001");
            // The type and category codes are not sensitive and stay readable.
            assertThat(rendered).contains(FIXTURE_TYPE_CD);
            assertThat(rendered).contains(FIXTURE_CAT_CD_IMAGE);
        }

        @Test
        @DisplayName("the rendering is one line and is deterministic, so it cannot forge a log entry")
        void theRenderingIsOneLineAndDeterministic() {
            String rendered = decoded(ROW_1).toString();
            assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
            assertThat(rendered).isEqualTo(decoded(ROW_1).toString());
            // A balance that is not zero is withheld exactly as a zero one is: neither the value nor its
            // stored digits reach the rendering.
            String withBalance = decoded(ROW_1).tranCatBal(new BigDecimal("1234.56")).toString();
            assertThat(withBalance)
                    .doesNotContain("1234.56")
                    .doesNotContain("123456")
                    .doesNotContain("0000012345F");
            assertThat(withBalance).contains(TranCatBalRecord.TRAN_CAT_BAL_NAME);
        }
    }

    @Nested
    @DisplayName("Structural guards - properties of the type itself (gates G22, G44, G52, G53)")
    class StructuralGuards {

        /**
         * The fields a class actually declares, with the coverage agent's contribution removed.
         *
         * <p>JaCoCo adds a {@code private static transient synthetic boolean[] $jacocoData} probe array
         * to every instrumented class. It is not final and it is not the class's own state, so a bare
         * walk over {@code getDeclaredFields()} would fail the no-mutable-static-state guard under
         * {@code mvn verify} while passing under a plain {@code mvn test}. A guard that only holds when
         * coverage is switched off is worse than no guard at all.
         *
         * @param type the class to inspect
         * @return the declared fields, instrumentation artefacts excluded
         */
        private static List<Field> declaredFields(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields in %s to vouch for", type.getName())
                    .isNotEmpty();
            return fields;
        }

        @Test
        @DisplayName("neither double nor float appears in any field, parameter or return type (R4, G22)")
        void noBinaryFloatingPointAnywhere() {
            // Every PIC 9...V... value is a BigDecimal at the declared scale. Binary floating point
            // cannot represent a decimal fraction exactly, so one value of either type here would
            // silently change money.
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                for (Field field : declaredFields(type)) {
                    assertThat(field.getType())
                            .as("field %s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isSynthetic() || method.getName().startsWith("$")) {
                        continue;
                    }
                    assertThat(method.getReturnType())
                            .as("return type of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                    assertThat(method.getParameterTypes())
                            .as("parameters of %s.%s", type.getSimpleName(), method.getName())
                            .doesNotContain(double.class, float.class, Double.class, Float.class);
                }
            }
        }

        @Test
        @DisplayName("no persistence mapping of any kind is declared on the type (G44)")
        void noPersistenceMappingOfAnyKind() {
            // The TCATBALF KSDS is reached by plain JDBC over the record image: there is no entity, no
            // table, no identifier and no version column to map to, and no schema change is permitted.
            // This matters most here of all the model types, because TCATBALF is read and REWRITTEN,
            // which is exactly where optimistic-locking metadata would otherwise be introduced.
            assertThat(TranCatBalRecord.class.getAnnotations()).isEmpty();
            assertThat(TranCatKey.class.getAnnotations()).isEmpty();

            List<Annotation> found = new ArrayList<>();
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                found.addAll(List.of(type.getAnnotations()));
                for (Field field : declaredFields(type)) {
                    found.addAll(List.of(field.getAnnotations()));
                }
                for (Method method : type.getDeclaredMethods()) {
                    found.addAll(List.of(method.getAnnotations()));
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    found.addAll(List.of(constructor.getAnnotations()));
                }
            }
            for (Annotation annotation : found) {
                String name = annotation.annotationType().getName();
                assertThat(name)
                        .as("annotation %s must not be a persistence or framework mapping", name)
                        .doesNotStartWith("jakarta.persistence.")
                        .doesNotStartWith("javax.persistence.")
                        .doesNotStartWith("org.springframework.");
            }
        }

        @Test
        @DisplayName("no mutable static state, so instances are independent (G53)")
        void noMutableStaticState() {
            // COBOL WORKING-STORAGE must never become a static Java field: that would break request
            // isolation and make a test's outcome depend on which tests ran before it.
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                for (Field field : declaredFields(type)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static field %s.%s must be final", type.getSimpleName(),
                                        field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("the 50-byte area is private and final, and every constructor states its code page")
        void theRecordAreaIsPrivateAndFinal() {
            // The record area is the single source of truth and is only ever handed out as a copy, which
            // is what stops a field value and the record's bytes from ever disagreeing.
            for (Field field : declaredFields(TranCatBalRecord.class)) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            // Construction goes through newInstance or decode, each of which demands a charset, so the
            // code page can never be omitted by accident.
            for (Constructor<?> constructor : TranCatBalRecord.class.getDeclaredConstructors()) {
                assertThat(Modifier.isPrivate(constructor.getModifiers()))
                        .as("constructor %s must be private", constructor)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("this test class holds no mutable static state either (practice B9)")
        void thisTestClassHoldsNoMutableStaticStateEither() {
            // The guard polices the guard: every constant in this file is final and of an immutable type,
            // so no test can leave state behind for another and the suite is order-independent (G54).
            for (Field field : declaredFields(TranCatBalRecordTest.class)) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("field %s must be static, as a shared fixture constant", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("field %s must be of an immutable type", field.getName())
                        .isIn(String.class, int.class, Charset.class);
            }
        }
    }
}
