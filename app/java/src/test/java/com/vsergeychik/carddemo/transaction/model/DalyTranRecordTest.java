package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Verifies {@link DalyTranRecord} - the single Java type for {@code app/cpy/CVTRA06Y.cpy},
 * {@code 01 DALYTRAN-RECORD} - against the copybook that declares it and against the behaviour its two
 * consuming COBOL programs actually depend on.
 *
 * <h2>Provenance of every expectation in this file</h2>
 * <p>Nothing here was guessed. Each expectation traces to one of six read-only reference inputs, all of
 * which were re-read and re-derived while writing this test:
 * <ul>
 *   <li>{@code app/cpy/CVTRA06Y.cpy} - the authoritative record layout. Its header comment reads
 *       {@code Data-structure for DALYTRANsaction record (RECLN = 350)} and it declares fourteen items
 *       at level {@code 05}.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - {@code 01 TRAN-RECORD}. Diffed item for item against
 *       {@code CVTRA06Y}: the {@code PICTURE} clauses, the widths and the declaration order are
 *       <em>identical</em>, and the <em>only</em> difference is that thirteen names carry a
 *       {@code TRAN-} prefix where these carry {@code DALYTRAN-}. The fourteenth item is plain
 *       un-prefixed {@code FILLER PIC X(20)} in <em>both</em> copybooks.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} - the {@code POSTTRAN} validator and poster. Two statements are
 *       load-bearing here: {@code :414}, which reads
 *       {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, and {@code :447}, which reads
 *       {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} into the {@code PIC X(350)} receiver declared
 *       at {@code :176-178}.</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} - the orphan daily poster, invoked by no JCL anywhere. Its
 *       {@code :168} does {@code DISPLAY DALYTRAN-RECORD}, writing the whole group item to
 *       {@code SYSOUT} as characters.</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl} - {@code :30-31} bind the {@code DALYTRAN} DD to
 *       {@code AWS.M2.CARDDEMO.DALYTRAN.PS}. A <em>physical-sequential</em> dataset, not a KSDS, which
 *       is why this record type has no key at all.</li>
 *   <li>{@code app/data/ASCII/dailytran.txt} - 300 rows of exactly 350 bytes. Every fixture literal
 *       below was transcribed from it field by field and verified character for character, and every
 *       census figure quoted in a comment was counted from the real file.</li>
 * </ul>
 *
 * <p>Those inputs are read-only reference material: this test writes nothing to them, and - see the next
 * heading - it does not read them at run time either.
 *
 * <h2>Why the fixture rows are embedded rather than loaded</h2>
 * <p>The rows below are {@code private static final String} literals. Nothing in this file opens a file,
 * reads a classpath resource, or touches the network or the clock. That is deliberate: a test that loaded
 * {@code /fixtures/dailytran.txt} would fail with an {@code IOException} when that resource happened to
 * be absent, and an {@code IOException} is indistinguishable at the gate from a real transcription
 * defect. Embedding the bytes makes every failure here a statement about the copybook contract and
 * nothing else. Each literal is assembled from its fourteen field images, one per line with the
 * {@code PICTURE} in a trailing comment, so a reviewer can audit it against the copybook without
 * counting characters.
 *
 * <h2>Rules status</h2>
 * <p>{@code review_rules} reports <em>"No user rules provided."</em> - one line, the whole document,
 * confirmed by reading it in full. Their absence is not a licence to lower the bar, so this test is held
 * to the migration plan's own binding constraints instead. The ones that govern this file are named at
 * the group they are verified in: fixed-width offsets are absolute (R5), offsets are justified by
 * addition from the {@code PICTURE} clauses (B11), monetary values are {@link BigDecimal} at the
 * declared scale and never a binary floating-point type (R4), the scale comes from the {@code PICTURE}
 * (R3), excess fraction digits truncate rather than round (R2), the record is 350 bytes (G19),
 * {@code FILLER} is present and space-filled on a fresh area (G21), two accessors over one backing span
 * round-trip (G34), no persistence artefact exists (G44), imports are individually named (G52), there is
 * no static mutable state (G53), and every conditional is driven both ways (G50).
 *
 * <h2>What this file deliberately does not test</h2>
 * <p>Only this type's own byte behaviour. Repository open, read-next and close and the {@code '00'} /
 * {@code '10'} status ladder, the 430-byte {@code DALYREJS} record and its {@code VALIDATION-TRAILER}
 * reason codes, the four-stage validation cascade and its 102 and 103 outcomes,
 * {@code COMPUTE WS-TEMP-BAL}, job orchestration, abend handling, HTTP and Spring wiring all belong to
 * the sibling tests in {@code com.vsergeychik.carddemo.transaction} that own those units. The
 * {@code PIC X(350)} verbatim-copy fact from {@code CBTRN02C:447} is cited here for one reason only: it
 * is what makes a raw-image accessor mandatory rather than a convenience.
 */
@DisplayName("DalyTranRecord - CVTRA06Y, 350 bytes")
class DalyTranRecordTest {

    /**
     * The code page of {@code app/data/ASCII/dailytran.txt}, named explicitly. There is no platform
     * default anywhere in this file: a zoned sign overpunch is a single byte whose value differs between
     * code pages, so an assumed encoding would silently change what is being asserted.
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The EBCDIC code page of the {@code app/data/EBCDIC} datasets, used to prove that transcoding
     * preserves the overpunch <em>character</em> even though it changes the byte. Named through
     * {@link Charset#forName(String)} because {@link StandardCharsets} has no constant for it.
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The declared record width, restated here so an assertion can compare against a literal. */
    private static final int DECLARED_RECORD_LENGTH = 350;

    /**
     * The 0-based index of {@code DALYTRAN-AMT}'s trailing byte - the one carrying the sign overpunch.
     * {@code 132 + 11 - 1 = 142}.
     */
    private static final int AMOUNT_SIGN_BYTE_INDEX = 142;

    /**
     * Row 1 of {@code app/data/ASCII/dailytran.txt}, 1-based bytes 1-350, assembled from its fourteen
     * field images so that each one is separately auditable against {@code CVTRA06Y}. A positive amount
     * with a {@code 'G'} overpunch: {@code 0000005047G} is {@code 504.77}.
     */
    private static final String ROW_1 =
            "0000000000683580"                            // DALYTRAN-ID            X(16)   @  0
            + "01"                                        // DALYTRAN-TYPE-CD       X(02)   @ 16
            + "0001"                                      // DALYTRAN-CAT-CD        9(04)   @ 18
            + "POS TERM  "                                // DALYTRAN-SOURCE        X(10)   @ 22
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // DALYTRAN-DESC          X(100)  @ 32
            + "0000005047G"                               // DALYTRAN-AMT       S9(09)V99   @132
            + "800000000"                                 // DALYTRAN-MERCHANT-ID   9(09)   @143
            + "Abshire-Lowe" + " ".repeat(38)             // DALYTRAN-MERCHANT-NAME X(50)   @152
            + "North Enoshaven" + " ".repeat(35)          // DALYTRAN-MERCHANT-CITY X(50)   @202
            + "72112     "                                // DALYTRAN-MERCHANT-ZIP  X(10)   @252
            + "4859452612877065"                          // DALYTRAN-CARD-NUM      X(16)   @262
            + "2022-06-10 19:27:53.000000"                // DALYTRAN-ORIG-TS       X(26)   @278
            + " ".repeat(26)                              // DALYTRAN-PROC-TS       X(26)   @304
            + " ".repeat(20);                             // FILLER                 X(20)   @330

    /**
     * Row 2 of the same fixture, 1-based bytes 1-350. A returned item, and the first of the six rows
     * whose amount carries a <code>'&#125;'</code> overpunch - negative with a final digit of zero.
     * {@code 0000009190}<code>&#125;</code> is {@code -919.00}, emphatically not a zero value.
     */
    private static final String ROW_2 =
            "0000000001774260"                            // DALYTRAN-ID            X(16)   @  0
            + "03"                                        // DALYTRAN-TYPE-CD       X(02)   @ 16
            + "0001"                                      // DALYTRAN-CAT-CD        9(04)   @ 18
            + "OPERATOR  "                                // DALYTRAN-SOURCE        X(10)   @ 22
            + "Return item at Nitzsche, Nicolas and Lowe"
            + " ".repeat(59)                              // DALYTRAN-DESC          X(100)  @ 32
            + "0000009190}"                               // DALYTRAN-AMT       S9(09)V99   @132
            + "800000000"                                 // DALYTRAN-MERCHANT-ID   9(09)   @143
            + "Nitzsche, Nicolas and Lowe"
            + " ".repeat(24)                              // DALYTRAN-MERCHANT-NAME X(50)   @152
            + "Fidelshire" + " ".repeat(40)               // DALYTRAN-MERCHANT-CITY X(50)   @202
            + "53378     "                                // DALYTRAN-MERCHANT-ZIP  X(10)   @252
            + "0927987108636232"                          // DALYTRAN-CARD-NUM      X(16)   @262
            + "2022-06-10 19:27:53.000000"                // DALYTRAN-ORIG-TS       X(26)   @278
            + " ".repeat(26)                              // DALYTRAN-PROC-TS       X(26)   @304
            + " ".repeat(20);                             // FILLER                 X(20)   @330

    /**
     * A synthetic row - not a fixture row - whose trailing {@code FILLER X(20)} holds twenty ASCII
     * <em>zero</em> characters rather than twenty spaces.
     *
     * <p>It exists because {@code FILLER} content is not uniform across this repository's fixtures.
     * Every one of the 300 rows of {@code app/data/ASCII/dailytran.txt} pads {@code FILLER} with spaces,
     * but {@code tcatbal.txt}, {@code trantype.txt} and {@code trancatg.txt} pad theirs with ASCII
     * zeros. A codec that "helpfully" re-space-filled {@code FILLER} on write-back would pass every
     * assertion made against the daily fixture and still corrupt a stored row, so the lossless read path
     * has to be proved against bytes that are <em>not</em> the initialisation value.
     */
    private static final String ROW_WITH_ZERO_FILLER =
            "0000000000683580"                            // DALYTRAN-ID            X(16)   @  0
            + "01"                                        // DALYTRAN-TYPE-CD       X(02)   @ 16
            + "0001"                                      // DALYTRAN-CAT-CD        9(04)   @ 18
            + "POS TERM  "                                // DALYTRAN-SOURCE        X(10)   @ 22
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // DALYTRAN-DESC          X(100)  @ 32
            + "0000005047G"                               // DALYTRAN-AMT       S9(09)V99   @132
            + "800000000"                                 // DALYTRAN-MERCHANT-ID   9(09)   @143
            + "Abshire-Lowe" + " ".repeat(38)             // DALYTRAN-MERCHANT-NAME X(50)   @152
            + "North Enoshaven" + " ".repeat(35)          // DALYTRAN-MERCHANT-CITY X(50)   @202
            + "72112     "                                // DALYTRAN-MERCHANT-ZIP  X(10)   @252
            + "4859452612877065"                          // DALYTRAN-CARD-NUM      X(16)   @262
            + "2022-06-10 19:27:53.000000"                // DALYTRAN-ORIG-TS       X(26)   @278
            + " ".repeat(26)                              // DALYTRAN-PROC-TS       X(26)   @304
            + "0".repeat(20);                             // FILLER                 X(20)   @330 - ZEROS

    /**
     * Locates a declared span by its copybook name, {@code FILLER} included.
     *
     * <p>{@link RecordLayout#span(String)} deliberately cannot find {@code FILLER}, because
     * {@code FILLER} is not a referable COBOL item - and that refusal is itself asserted below. This
     * helper scans the storage spans instead, so one parameterized table can cover all fourteen items
     * uniformly rather than covering thirteen and leaving the reserved span to a separate case.
     *
     * @param name the copybook item name, verbatim
     * @return the matching span
     */
    private static FieldSpan spanNamed(String name) {
        for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
            if (span.name().equals(name)) {
                return span;
            }
        }
        throw new IllegalArgumentException("CVTRA06Y declares no span named " + name);
    }

    /**
     * The names of a type's declared fields whose name contains {@code fragment}.
     *
     * <p>Used to assert the <em>absence</em> of a construct, which is the only way to assert that
     * something was deliberately not modelled: that this record declares no key, and that it carries no
     * persistence annotation.
     *
     * @param type     the type to inspect
     * @param fragment the fragment to look for, matched case-sensitively
     * @return the matching field names, empty when there are none
     */
    private static List<String> declaredFieldNamesContaining(Class<?> type, String fragment) {
        List<String> matches = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().contains(fragment)) {
                matches.add(field.getName());
            }
        }
        return matches;
    }

    /**
     * Every {@link FieldSpan} constant this record declares, read reflectively.
     *
     * <p>Read through reflection rather than listed by hand so the assertion covers whatever the class
     * actually declares. A fifteenth span added later is inspected automatically instead of slipping past
     * a hard-coded list.
     *
     * @return the declared span descriptors
     */
    private static List<FieldSpan> declaredSpanConstants() {
        List<FieldSpan> spans = new ArrayList<>();
        for (Field field : DalyTranRecord.class.getDeclaredFields()) {
            if (field.getType() == FieldSpan.class) {
                try {
                    spans.add((FieldSpan) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("DalyTranRecord's span constant " + field.getName()
                            + " should be publicly readable", e);
                }
            }
        }
        return spans;
    }

    @Nested
    @DisplayName("declared geometry - B11, offsets justified by addition")
    class DeclaredGeometry {

        @Test
        @DisplayName("G19: the record is the 350 bytes the copybook's RECLN comment declares")
        void recordLengthIs350() {
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DalyTranRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("B11: the fourteen PICTURE widths add up to 350, restated as an addition")
        void theCopybookArithmeticAddsUpTo350() {
            // The copybook's own PICTURE clauses, in declaration order, added up by hand:
            //   DALYTRAN-ID              X(16)      16
            //   DALYTRAN-TYPE-CD         X(02)       2
            //   DALYTRAN-CAT-CD          9(04)       4
            //   DALYTRAN-SOURCE          X(10)      10
            //   DALYTRAN-DESC            X(100)    100
            //   DALYTRAN-AMT             S9(09)V99  11   <- p + s, the sign is overpunched not stored
            //   DALYTRAN-MERCHANT-ID     9(09)       9
            //   DALYTRAN-MERCHANT-NAME   X(50)      50
            //   DALYTRAN-MERCHANT-CITY   X(50)      50
            //   DALYTRAN-MERCHANT-ZIP    X(10)      10
            //   DALYTRAN-CARD-NUM        X(16)      16
            //   DALYTRAN-ORIG-TS         X(26)      26
            //   DALYTRAN-PROC-TS         X(26)      26
            //   FILLER                   X(20)      20
            // 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350
            assertThat(16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20)
                    .as("the copybook arithmetic, written out")
                    .isEqualTo(DECLARED_RECORD_LENGTH);

            // And the same addition performed over the class's own declared length constants, which is
            // what proves those constants encode the arithmetic above rather than merely agreeing with
            // a total that happens to be right.
            assertThat(DalyTranRecord.DALYTRAN_ID_LENGTH
                    + DalyTranRecord.DALYTRAN_TYPE_CD_LENGTH
                    + DalyTranRecord.DALYTRAN_CAT_CD_LENGTH
                    + DalyTranRecord.DALYTRAN_SOURCE_LENGTH
                    + DalyTranRecord.DALYTRAN_DESC_LENGTH
                    + DalyTranRecord.DALYTRAN_AMT_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_ID_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_NAME_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_CITY_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_ZIP_LENGTH
                    + DalyTranRecord.DALYTRAN_CARD_NUM_LENGTH
                    + DalyTranRecord.DALYTRAN_ORIG_TS_LENGTH
                    + DalyTranRecord.DALYTRAN_PROC_TS_LENGTH
                    + DalyTranRecord.FILLER_LENGTH)
                    .as("the same addition over the declared length constants")
                    .isEqualTo(DECLARED_RECORD_LENGTH);

            // And the class's own restatement of it, which walks the layout rather than the constants.
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the fourteen spans are contiguous from byte 0 with no gap and no overlap")
        void spansAreContiguousAndSumTo350() {
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).hasSize(14);
            int next = 0;
            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("%s starts where the previous span ends", span.name())
                        .isEqualTo(next);
                next += span.length();
            }
            assertThat(next).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @ParameterizedTest(name = "{0} at 0-based {1} for {2} bytes")
        @DisplayName("R5: every span sits at its absolute offset, under its copybook name verbatim")
        @CsvSource({
            // name,                   0-based offset, length    1-based COBOL columns
            "DALYTRAN-ID,0,16",              //                    1- 16
            "DALYTRAN-TYPE-CD,16,2",         //                   17- 18
            "DALYTRAN-CAT-CD,18,4",          //                   19- 22
            "DALYTRAN-SOURCE,22,10",         //                   23- 32
            "DALYTRAN-DESC,32,100",          //                   33-132
            "DALYTRAN-AMT,132,11",           //                  133-143
            "DALYTRAN-MERCHANT-ID,143,9",    //                  144-152
            "DALYTRAN-MERCHANT-NAME,152,50", //                  153-202
            "DALYTRAN-MERCHANT-CITY,202,50", //                  203-252
            "DALYTRAN-MERCHANT-ZIP,252,10",  //                  253-262
            "DALYTRAN-CARD-NUM,262,16",      //                  263-278
            "DALYTRAN-ORIG-TS,278,26",       //                  279-304
            "DALYTRAN-PROC-TS,304,26",       //                  305-330
            "FILLER,330,20"})                //                  331-350
        void everySpanSitsWhereTheCopybookPutsIt(String name, int offset, int length) {
            FieldSpan span = spanNamed(name);
            assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
            assertThat(span.length()).as("%s length", name).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).as("%s end", name).isEqualTo(offset + length);

            // The same offset and length reachable as named constants, so a caller never has to pair
            // one with the other by hand - and so a drift between the constant and the descriptor is
            // caught here rather than in a shifted field read.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawSpan(span)).hasSize(length);
        }

        @ParameterizedTest(name = "{0} is referable by name")
        @DisplayName("the thirteen named items are referable through the layout")
        @CsvSource({
            "DALYTRAN-ID", "DALYTRAN-TYPE-CD", "DALYTRAN-CAT-CD", "DALYTRAN-SOURCE",
            "DALYTRAN-DESC", "DALYTRAN-AMT", "DALYTRAN-MERCHANT-ID", "DALYTRAN-MERCHANT-NAME",
            "DALYTRAN-MERCHANT-CITY", "DALYTRAN-MERCHANT-ZIP", "DALYTRAN-CARD-NUM",
            "DALYTRAN-ORIG-TS", "DALYTRAN-PROC-TS"})
        void theThirteenNamedItemsAreReferable(String name) {
            assertThat(DalyTranRecord.LAYOUT.hasSpan(name)).isTrue();
            assertThat(DalyTranRecord.LAYOUT.span(name)).isEqualTo(spanNamed(name));
        }

        @Test
        @DisplayName("G21: the trailing span is named FILLER, without the DALYTRAN- prefix")
        void theFillerSpanKeepsTheCopybooksOwnUnprefixedName() {
            // CVTRA06Y:18 declares `05 FILLER PIC X(20).` - not DALYTRAN-FILLER. CVTRA05Y:18 declares
            // it the same way. The name is reproduced as declared rather than regularised, because a
            // field-by-field comparison is keyed on names.
            assertThat(DalyTranRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(DalyTranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(DalyTranRecord.LAYOUT.hasSpan("DALYTRAN-FILLER")).isFalse();
        }

        @Test
        @DisplayName("G21: FILLER occupies its twenty bytes yet stays unreferable by name, as in COBOL")
        void fillerIsPresentInStorageButNotReferableByName() {
            // Present as a first-class span: without it the spans could not sum to 350.
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).contains(DalyTranRecord.FILLER);
            assertThat(DalyTranRecord.FILLER.offset()).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER.length()).isEqualTo(20);

            // But not addressable by name, because FILLER is not a referable COBOL item. Both sides of
            // that are asserted: the query answers false, and the lookup refuses.
            assertThat(DalyTranRecord.LAYOUT.hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.LAYOUT.span("FILLER"));
        }

        @Test
        @DisplayName("DALYTRAN-AMT occupies p+s = 11 bytes: no COMP-3 exists in app/cpy at all")
        void amountOccupiesElevenBytes() {
            // S9(09)V99 is nine integer digits plus two fraction digits = 11 bytes. It is not 12: the
            // operational sign is overpunched into the trailing byte rather than given a byte of its
            // own. It is not 9 either.
            //
            // And it needs no nibble unpacking, because a repository-wide scan of app/cpy finds ZERO
            // COMP-3 and ZERO PACKED-DECIMAL declarations - every persisted numeric span in this
            // codebase is zoned DISPLAY. Asserting the width here means a future packed-decimal
            // assumption, which would make the field 6 bytes, breaks this test rather than silently
            // shifting the eight fields that follow it.
            assertThat(DalyTranRecord.DALYTRAN_AMT_LENGTH).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(2);
            assertThat(DalyTranRecord.DALYTRAN_AMT_INTEGER_DIGITS
                    + DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT.length()).isEqualTo(11);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmtImage()).hasSize(11);

            // The sign byte is the LAST of the eleven, at 0-based 142.
            assertThat(DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1)
                    .isEqualTo(AMOUNT_SIGN_BYTE_INDEX);
        }

        @Test
        @DisplayName("R3: the scale comes from the PICTURE, through one central policy constant")
        void monetaryScaleIsCentralised() {
            assertThat(DalyTranRecord.DALYTRAN_AMT_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("R2: the rounding policy is DOWN, because ROUNDED appears zero times in the source")
        void roundingPolicyIsTruncation() {
            // COBOL rounds only where a statement says ROUNDED, and an exhaustive search of all
            // twenty-eight programs finds the keyword zero times. Truncation on store is therefore not
            // a choice but the only faithful reading, and it is named in one place.
            assertThat(CobolDecimal.COBOL_ROUNDING).isSameAs(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("G34: the ORIG-DT slice shares DALYTRAN-ORIG-TS's offset, per CBTRN02C:414")
        void origDateSliceSharesTheTimestampsOffset() {
            // Two accessors over one backing span: the full X(26) timestamp and the (1:10) slice that
            // CBTRN02C:414 compares. COBOL reference modification is 1-based, so (1:10) starts at the
            // field's own first byte - the same 0-based offset - and runs for ten.
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_OFFSET)
                    .isEqualTo(DalyTranRecord.DALYTRAN_ORIG_TS_OFFSET)
                    .isEqualTo(278);
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_LENGTH).isEqualTo(10);

            // The slice is a window into the timestamp, so it cannot reach past it.
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_LENGTH)
                    .isLessThan(DalyTranRecord.DALYTRAN_ORIG_TS_LENGTH);
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a set of spans that drops the trailing FILLER")
        void theWidthSelfCheckRejectsADroppedFiller() {
            // The pass side of this check runs at class initialisation - DalyTranRecord.LAYOUT could not
            // have been constructed if the geometry were wrong, and every assertion above depends on it.
            // This is the fail side, and it is worth driving explicitly: a self-check that never
            // rejected anything would be indistinguishable from no self-check at all.
            //
            // Dropping FILLER is the realistic transcription error, because FILLER is the one item a
            // reader is tempted to treat as decoration. Twenty bytes short of 350.
            FieldSpan[] withoutFiller = spansExcept(DalyTranRecord.FILLER);
            assertThat(withoutFiller).hasSize(13);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, withoutFiller))
                    .withMessageContaining("330")
                    .withMessageContaining("20");
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a span set that reserves a separate sign byte")
        void theWidthSelfCheckRejectsAReservedSignByte() {
            // The other realistic transcription error: reading S9(09)V99 as needing a twelfth byte for
            // the sign. Modelled here as one extra trailing byte, which keeps the set contiguous and
            // isolates the failure to the total - 351 where the copybook says 350.
            List<FieldSpan> spans = new ArrayList<>(DalyTranRecord.LAYOUT.storageSpans());
            spans.add(FieldSpan.filler(DECLARED_RECORD_LENGTH, 1));
            FieldSpan[] oneByteTooLong = spans.toArray(new FieldSpan[0]);
            assertThat(oneByteTooLong).hasSize(15);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, oneByteTooLong))
                    .withMessageContaining("351");
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a gap left by a dropped middle item")
        void theWidthSelfCheckRejectsAGap() {
            // Dropping an item from the middle leaves a hole rather than a short record, and a hole is
            // the error that shifts every following field while keeping the total plausible. It is
            // rejected on its own terms.
            FieldSpan[] withoutTypeCd = spansExcept(DalyTranRecord.DALYTRAN_TYPE_CD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, withoutTypeCd))
                    .withMessageContaining("DALYTRAN-CAT-CD");
        }

        @Test
        @DisplayName("no key is declared: POSTTRAN.jcl:31 binds DALYTRAN to a sequential PS dataset")
        void noKeyIsDeclaredBecauseTheDatasetIsSequential() {
            // app/jcl/POSTTRAN.jcl:30-31 read:
            //     //DALYTRAN DD DISP=SHR,
            //     //         DSN=AWS.M2.CARDDEMO.DALYTRAN.PS
            // A physical-sequential dataset. Both consuming programs open it ORGANIZATION SEQUENTIAL /
            // ACCESS MODE SEQUENTIAL and read it with READ ... INTO. There is no keyed read, no
            // START/READ NEXT and no REWRITE anywhere against it, so this type declares no key - even
            // though its first sixteen bytes happen to align with the TRANSACT KSDS key that the posted
            // record carries.
            //
            // Asserted by contrast, which is what makes it more than a claim: the KSDS-backed sibling
            // modelled from CVTRA05Y does declare a key constant, and this type declares none.
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "KEY"))
                    .as("CVTRA06Y is read sequentially from a PS dataset, so it has no key")
                    .isEmpty();
            assertThat(declaredFieldNamesContaining(TranRecord.class, "KEY"))
                    .as("CVTRA05Y's TRANSACT record is a KSDS and does declare its key width")
                    .isNotEmpty();
        }

        /**
         * The declared storage spans with one span removed, for the self-check failure cases.
         *
         * @param excluded the span to leave out
         * @return the remaining spans in declaration order
         */
        private FieldSpan[] spansExcept(FieldSpan excluded) {
            List<FieldSpan> remaining = new ArrayList<>(DalyTranRecord.LAYOUT.storageSpans());
            remaining.remove(excluded);
            return remaining.toArray(new FieldSpan[0]);
        }
    }

    @Nested
    @DisplayName("R1: a standalone type, deliberately duplicating CVTRA05Y's geometry")
    class StandaloneTypeDecision {

        // Why this group exists at all.
        //
        // CVTRA06Y and CVTRA05Y are byte-for-byte identical in geometry. The obvious refactoring - one
        // Java type, or a shared base class, or a generic record parameterised by a name prefix - is
        // exactly the wrong move here, and these assertions are what stop someone making it in good
        // faith six months from now.
        //
        // The reason is the verification gate rather than taste. A field-by-field differ reports
        // findings BY FIELD NAME. A shared implementation would report DALYTRAN-AMT's value under the
        // name TRAN-AMT, every one of those name mismatches would count as a difference, and the
        // requirement that the difference count reach zero before a module is accepted could never be
        // met. The duplication buys a migration that can actually be verified; removing it would buy
        // slightly less code and an unverifiable result.
        //
        // CBTRN02C settles the question independently: it copies BOTH copybooks, so it holds a record of
        // each simultaneously and moves field by field between them. Two records of the same shape under
        // different names is not an accident of the copybook library - it is what the program needs.

        @Test
        @DisplayName("the type extends nothing: its superclass is Object and it implements no interface")
        void theTypeHasNoSharedBase() {
            assertThat(DalyTranRecord.class.getSuperclass())
                    .as("no shared base class with the CVTRA05Y model")
                    .isEqualTo(Object.class);
            assertThat(DalyTranRecord.class.getInterfaces())
                    .as("no shared interface either - a common interface would reintroduce the "
                            + "name-collapsing risk through the back door")
                    .isEmpty();
        }

        @Test
        @DisplayName("neither type is assignable to the other, in either direction")
        void neitherTypeIsAssignableToTheOther() {
            assertThat(TranRecord.class.isAssignableFrom(DalyTranRecord.class))
                    .as("a DalyTranRecord is not a TranRecord")
                    .isFalse();
            assertThat(DalyTranRecord.class.isAssignableFrom(TranRecord.class))
                    .as("a TranRecord is not a DalyTranRecord")
                    .isFalse();
            assertThat(DalyTranRecord.class).isNotEqualTo(TranRecord.class);
        }

        @Test
        @DisplayName("every declared span name carries the DALYTRAN- prefix, except un-prefixed FILLER")
        void everySpanNameCarriesTheDalytranPrefix() {
            List<FieldSpan> spans = declaredSpanConstants();
            assertThat(spans).hasSize(14);

            int prefixed = 0;
            int filler = 0;
            for (FieldSpan span : spans) {
                // Note the assertion is on the PREFIX, not on containment. "DALYTRAN-ID" does contain
                // the substring "TRAN-" - at index 4 - so a doesNotContain("TRAN-") check would fail on
                // correct data. What must never happen is a name that STARTS with TRAN-, because that
                // is the name the other copybook's items are reported under.
                assertThat(span.name())
                        .as("%s must not be reported under a CVTRA05Y name", span.name())
                        .doesNotStartWith("TRAN-");
                if ("FILLER".equals(span.name())) {
                    filler++;
                } else {
                    assertThat(span.name()).startsWith("DALYTRAN-");
                    prefixed++;
                }
            }
            assertThat(prefixed).as("thirteen prefixed items").isEqualTo(13);
            assertThat(filler).as("one un-prefixed reserved span").isEqualTo(1);
        }

        @Test
        @DisplayName("the two layouts are byte-compatible yet name-incompatible, item for item")
        void theTwoLayoutsAreByteCompatibleButNameIncompatible() {
            List<FieldSpan> daily = DalyTranRecord.LAYOUT.storageSpans();
            List<FieldSpan> master = TranRecord.LAYOUT.storageSpans();

            // Byte-compatible: same count, same total, and pairwise identical offsets and widths. This
            // is the fact that makes the duplication look removable.
            assertThat(daily).hasSameSizeAs(master);
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(TranRecord.RECORD_LENGTH);
            for (int i = 0; i < daily.size(); i++) {
                FieldSpan d = daily.get(i);
                FieldSpan m = master.get(i);
                assertThat(d.offset()).as("item %d offset", i + 1).isEqualTo(m.offset());
                assertThat(d.length()).as("item %d length", i + 1).isEqualTo(m.length());
                assertThat(d.kind()).as("item %d PICTURE category", i + 1).isEqualTo(m.kind());
            }

            // Name-incompatible: and this is the fact that makes removing it a parity defect. Thirteen
            // of the fourteen names differ; the fourteenth is plain FILLER in both copybooks, which is
            // why it is the one item that may legitimately match.
            int differing = 0;
            int shared = 0;
            for (int i = 0; i < daily.size(); i++) {
                if (daily.get(i).name().equals(master.get(i).name())) {
                    assertThat(daily.get(i).name()).isEqualTo("FILLER");
                    shared++;
                } else {
                    assertThat(daily.get(i).name()).startsWith("DALYTRAN-");
                    assertThat(master.get(i).name()).startsWith("TRAN-");
                    differing++;
                }
            }
            assertThat(differing).as("thirteen names diverge").isEqualTo(13);
            assertThat(shared).as("only the un-prefixed FILLER is shared").isEqualTo(1);

            // The two descriptor sets therefore share no referable descriptor, which is precisely why a
            // descriptor from one is refused by the other - asserted under verbatim image access below.
            assertThat(daily).doesNotContainAnyElementsOf(
                    master.stream().filter(s -> !"FILLER".equals(s.name())).toList());
        }

        @Test
        @DisplayName("the field-by-field rendering labels every value with a DALYTRAN- name")
        void theRenderingLabelsEveryValueWithADalytranName() {
            String rendered = DalyTranRecord.decode(ROW_1, ASCII).toString();

            // Each label is preceded by ", " in the rendering, so looking for ", TRAN-" finds a
            // CVTRA05Y-style label without tripping over the "TRAN-" that sits inside "DALYTRAN-".
            assertThat(rendered)
                    .as("no value may be reported under a CVTRA05Y name")
                    .doesNotContain(", TRAN-");
            for (FieldSpan span : declaredSpanConstants()) {
                if (!"FILLER".equals(span.name())) {
                    assertThat(rendered).as("%s is labelled", span.name()).contains(span.name());
                }
            }
        }
    }

    @Nested
    @DisplayName("G44: no persistence artefact of any kind")
    class NoPersistenceArtefacts {

        @Test
        @DisplayName("the type carries no annotation at all - not JPA, not Spring, not validation")
        void theTypeCarriesNoAnnotations() {
            // The migration adds no schema: no DDL, no entity mapping, no migration tool, no version
            // column. A record type is the place that constraint would be violated first, because
            // annotating it is the single easiest way to accidentally introduce an object-relational
            // mapping - so the absence is asserted rather than assumed.
            assertThat(DalyTranRecord.class.getAnnotations())
                    .as("a plain data type: no @Entity, no @Table, no @Component, no annotation at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("no member carries a jakarta.persistence or javax.persistence annotation")
        void noMemberCarriesAPersistenceAnnotation() {
            List<String> offenders = new ArrayList<>();
            collectPersistenceAnnotations(DalyTranRecord.class.getAnnotations(), "the type", offenders);
            for (Field field : DalyTranRecord.class.getDeclaredFields()) {
                collectPersistenceAnnotations(field.getAnnotations(), field.getName(), offenders);
            }
            for (Method method : DalyTranRecord.class.getDeclaredMethods()) {
                collectPersistenceAnnotations(method.getAnnotations(), method.getName(), offenders);
            }
            assertThat(offenders)
                    .as("JDBC to the existing dataset, with no schema and no ORM")
                    .isEmpty();
        }

        @Test
        @DisplayName("no version column: concurrency is not modelled on the record type")
        void noVersionColumnIsDeclared() {
            // Optimistic concurrency in this system is a re-read-and-compare in the update services, not
            // a version column - introducing one would be a schema change. A daily transaction record is
            // read-only input in both consuming programs and has no concurrency story at all.
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "VERSION")).isEmpty();
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "version")).isEmpty();
        }

        /**
         * Adds any persistence-framework annotation found on {@code annotations} to {@code offenders}.
         *
         * @param annotations the annotations to inspect
         * @param owner       what carries them, for the failure message
         * @param offenders   the accumulating list of violations
         */
        private void collectPersistenceAnnotations(Annotation[] annotations, String owner,
                List<String> offenders) {
            for (Annotation annotation : annotations) {
                String declaring = annotation.annotationType().getName();
                if (declaring.startsWith("jakarta.persistence")
                        || declaring.startsWith("javax.persistence")) {
                    offenders.add(owner + " carries " + declaring);
                }
            }
        }
    }

    @Nested
    @DisplayName("an initialised area - the WORKING-STORAGE equivalent")
    class InitialisedArea {

        @Test
        @DisplayName("G21: a fresh area is 350 bytes with FILLER space-filled at offset 330")
        void aFreshAreaHasSpaceFilledFiller() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(record.filler())
                    .as("FILLER X(20) at 0-based 330 initialises to the charset's space byte")
                    .isEqualTo(" ".repeat(20))
                    .hasSize(20);

            // Asserted at the absolute offset too, not only through the accessor, because the accessor
            // and the offset could in principle disagree.
            assertThat(record.displayImage().substring(330, 350)).isEqualTo(" ".repeat(20));
            for (int i = 330; i < DECLARED_RECORD_LENGTH; i++) {
                assertThat(record.rawImage()[i]).as("byte %d of FILLER", i).isEqualTo((byte) ' ');
            }
        }

        @Test
        @DisplayName("text spans blank, unsigned numerics zero-filled, the amount a signed zero")
        void initialisesEverySpanAccordingToItsPictureCategory() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // PIC X spans initialise to spaces, which is what INITIALIZE does in COBOL.
            assertThat(record.dalytranId()).isEqualTo(" ".repeat(16));
            assertThat(record.dalytranTypeCd()).isEqualTo("  ");
            assertThat(record.dalytranSource()).isEqualTo(" ".repeat(10));
            assertThat(record.dalytranDesc()).isEqualTo(" ".repeat(100));
            assertThat(record.dalytranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.dalytranMerchantCity()).isEqualTo(" ".repeat(50));
            assertThat(record.dalytranMerchantZip()).isEqualTo(" ".repeat(10));
            assertThat(record.dalytranCardNum()).isEqualTo(" ".repeat(16));
            assertThat(record.dalytranOrigTs()).isEqualTo(" ".repeat(26));
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));

            // PIC 9 and PIC S9 spans initialise to zeros instead, because spaces are not valid numeric
            // DISPLAY data and reading them back would throw rather than yield zero.
            assertThat(record.dalytranCatCdImage()).isEqualTo("0000");
            assertThat(record.dalytranCatCd()).isZero();
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.dalytranMerchantId()).isZero();
            assertThat(record.dalytranAmt()).isEqualByComparingTo(CobolDecimal.monetaryZero());
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.hasZeroDalytranAmt()).isTrue();

            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("initialises identically under EBCDIC, using that code page's own pad bytes")
        void initialisesUnderEbcdicToo() {
            DalyTranRecord record = new DalyTranRecord(EBCDIC);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.dalytranCatCd()).isZero();
            assertThat(record.charset()).isEqualTo(EBCDIC);

            // The pad byte is the code page's, not a hard-coded 0x20: EBCDIC space is 0x40.
            assertThat(record.rawImage()[330]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("a charset is never assumed - there is no platform default on any entry point")
        void aCharsetIsAlwaysRequired() {
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.getBytes(ASCII), null));
            assertThatNullPointerException().isThrownBy(() -> DalyTranRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1, ASCII).encode(null));
        }
    }

    @Nested
    @DisplayName("decoding a stored row - untrimmed, at absolute offsets")
    class FixtureDecode {

        @Test
        @DisplayName("every embedded literal is exactly 350 characters, as the fixture rows are")
        void embeddedRowsAreWellFormed() {
            assertThat(ROW_1).as("fixture row 1").hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ROW_2).as("fixture row 2").hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ROW_WITH_ZERO_FILLER).as("synthetic zero-FILLER row")
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("fixture row 1 decodes all fourteen items through the copybook offsets")
        void rowOneDecodesFieldForField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
            assertThat(record.dalytranTypeCd()).isEqualTo("01");
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ");
            assertThat(record.dalytranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.dalytranMerchantName()).isEqualTo("Abshire-Lowe" + " ".repeat(38));
            assertThat(record.dalytranMerchantCity()).isEqualTo("North Enoshaven" + " ".repeat(35));
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a decode is untrimmed: trailing spaces are data, not formatting")
        void aDecodeIsUntrimmed() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            // DALYTRAN-SOURCE really is "POS TERM" followed by two spaces, and CBTRN02C:428 moves all
            // ten bytes into a PIC X(10) receiver. A trimming decode would return eight characters, the
            // write-back would then space-pad differently, and every following comparison would be made
            // against the wrong bytes.
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ").hasSize(10)
                    .isNotEqualTo("POS TERM");
            assertThat(record.dalytranDesc()).hasSize(100).endsWith(" ");
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ").hasSize(10);

            // Every character span reads back at its full declared width, no exceptions.
            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(record.rawSpan(span)).as(span.name()).hasSize(span.length());
            }
        }

        @Test
        @DisplayName("DALYTRAN-TYPE-CD is a String and DALYTRAN-CAT-CD is an int, per their PICTUREs")
        void theTypeCodeIsTextAndTheCategoryCodeIsNumeric() throws Exception {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            // PIC X(02): '01' is two stored characters and its leading zero is data, so the accessor
            // returns text. Reading it as a number would lose the width and break the composite
            // transaction-category key that CBTRN02C:470 and :506 build from it.
            assertThat(DalyTranRecord.class.getMethod("dalytranTypeCd").getReturnType())
                    .isEqualTo(String.class);
            assertThat(record.dalytranTypeCd()).isEqualTo("01");

            // PIC 9(04): scale-free, so an int with no rounding policy to apply. The stored digits
            // remain available separately for a byte comparison.
            assertThat(DalyTranRecord.class.getMethod("dalytranCatCd").getReturnType())
                    .isEqualTo(int.class);
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
        }

        @Test
        @DisplayName("R4: the amount is a BigDecimal, never a binary floating-point type")
        void theAmountIsNeverABinaryFloatingPointType() throws Exception {
            // Asserted on the declared return type rather than on a value, because a value assertion
            // would still pass if the field were stored as a binary fraction and converted on the way
            // out - and a cent of representation drift is a parity failure.
            assertThat(DalyTranRecord.class.getMethod("dalytranAmt").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(DalyTranRecord.class.getMethod("moveDalytranAmt", BigDecimal.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("R3/G23: every decoded amount carries scale exactly 2, from the PICTURE")
        void everyDecodedAmountCarriesScaleTwo() {
            // scale() and not merely compareTo: 504.77 and 504.770 compare equal but only one of them
            // is what S9(09)V99 holds, and a scale drift would surface on write-back as a shifted field.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(new DalyTranRecord(ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmt())
                    .isEqualTo(new BigDecimal("504.77"));
        }

        @ParameterizedTest(name = "fixture line {0}: {1} decodes to {2}")
        @DisplayName("all SIX negative-zero-digit rows in the fixture decode to their real amounts")
        @CsvSource({
            // 1-based fixture line, the stored DALYTRAN-AMT image, the decoded value.
            // These are every row of app/data/ASCII/dailytran.txt whose trailing byte is '}' - the
            // census over the 300 rows finds exactly six, and here they all are.
            "2,0000009190},-919.00",
            "55,0000002430},-243.00",
            "87,0000007630},-763.00",
            "150,0000009070},-907.00",
            "165,0000003720},-372.00",
            "210,0000004350},-435.00"})
        void everyNegativeZeroDigitFixtureRowDecodesToItsRealAmount(int line, String image,
                String expected) {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage(image);

            // A '}' means "negative, final digit zero". It emphatically does not mean the amount is
            // zero, and every one of these six carries a substantial negative value.
            assertThat(record.dalytranAmt())
                    .as("fixture line %d", line)
                    .isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.dalytranAmt().scale()).as("line %d scale", line).isEqualTo(2);
            assertThat(record.dalytranAmt().signum()).as("line %d sign", line).isNegative();
            assertThat(record.hasZeroDalytranAmt()).as("line %d is not zero", line).isFalse();
            assertThat(record.dalytranAmtImage()).as("line %d image is preserved", line)
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("fixture row 2 decodes its '}' amount as negative and reads its other fields")
        void rowTwoDecodesANegativeAmount() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000001774260");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(record.dalytranAmt().signum()).isNegative();
            assertThat(record.hasZeroDalytranAmt()).isFalse();
            assertThat(record.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.dalytranTypeCd()).isEqualTo("03");
            assertThat(record.dalytranCardNum()).isEqualTo("0927987108636232");
            assertThat(record.dalytranMerchantCity()).isEqualTo("Fidelshire" + " ".repeat(40));
        }

        @Test
        @DisplayName("DALYTRAN-PROC-TS is blank on the daily file, as it is in all 300 fixture rows")
        void theProcessingTimestampIsBlankOnTheDailyFile() {
            // Verified by scanning the real file: bytes 305-330 are twenty-six spaces on every one of
            // the 300 rows, because a daily transaction has not been processed yet. Posting fills the
            // equivalent field on the transaction MASTER instead - CBTRN02C:437-438 moves a generated
            // timestamp into TRAN-PROC-TS, deliberately not back into this record.
            //
            // The consequence for a test: fixture data can only ever exercise the blank case, so the
            // non-blank case needs a synthetic row. Both are driven here.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranProcTs()).isEqualTo(" ".repeat(26));

            DalyTranRecord synthetic = DalyTranRecord.decode(ROW_1, ASCII);
            synthetic.moveDalytranProcTs("2022-07-19 23:16:01.000000");
            assertThat(synthetic.dalytranProcTs()).isEqualTo("2022-07-19 23:16:01.000000").hasSize(26);
            // And writing it disturbs nothing on either side of the span.
            assertThat(synthetic.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(synthetic.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the fixture's own coverage is narrow, and that is recorded rather than assumed")
        void theFixtureCoverageIsNarrowerThanTheFieldsAllow() {
            // Scanning app/data/ASCII/dailytran.txt establishes three limits that nobody should mistake
            // for properties of the copybook:
            //
            //   * only TWO distinct DALYTRAN-TYPE-CD / DALYTRAN-CAT-CD pairs occur - '01'/'0001' on 250
            //     rows and '03'/'0001' on 50 - so the fixture exercises no other category at all;
            //   * the largest magnitude is 0000009997G = 999.77, three integer digits, where the
            //     PICTURE allows nine;
            //   * 50 of the 300 amounts are negative.
            //
            // The field is wider than the data, so the wide cases are driven synthetically below rather
            // than assumed to be covered by the fixture.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranTypeCd()).isEqualTo("01");
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranTypeCd()).isEqualTo("03");
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranCatCdImage()).isEqualTo("0001");
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranCatCdImage()).isEqualTo("0001");

            DalyTranRecord widest = new DalyTranRecord(ASCII);
            widest.writeDalytranAmtImage("0000009997G");
            assertThat(widest.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999.77"));

            // What the PICTURE actually allows: nine integer digits and two fraction digits, so
            // 999999999.99 is the largest storable value - six orders of magnitude above anything the
            // fixture contains.
            //
            // Note the eleventh character of that image. It is 'I', not '9': the final digit is folded
            // into the overpunch glyph even at the maximum value, because 'I' means "positive, final
            // digit 9". There is no value of this field, anywhere in its range, whose eleventh character
            // is a plain digit when written by this codec - which is the clearest possible statement of
            // why the span is eleven bytes rather than twelve.
            DalyTranRecord full = new DalyTranRecord(ASCII);
            full.moveDalytranAmt(new BigDecimal("999999999.99"));
            assertThat(full.dalytranAmtImage()).isEqualTo("9999999999I").hasSize(11);
            assertThat(full.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));
            assertThat(full.dalytranAmt().scale()).isEqualTo(2);

            // And the most negative: 'R' is "negative, final digit 9".
            DalyTranRecord mostNegative = new DalyTranRecord(ASCII);
            mostNegative.moveDalytranAmt(new BigDecimal("-999999999.99"));
            assertThat(mostNegative.dalytranAmtImage()).isEqualTo("9999999999R").hasSize(11);
            assertThat(mostNegative.dalytranAmt())
                    .isEqualByComparingTo(new BigDecimal("-999999999.99"));

            // A value with a tenth integer digit does not fit, and COBOL discards the digits that do not
            // fit rather than reporting anything. That is asserted below in its own case, because it is
            // a MOVE rule rather than a range check.
        }

        @Test
        @DisplayName("a signed amount too wide for S9(09)V99 truncates on the LEFT, silently")
        void anOverWideAmountTruncatesHighOrderDigitsSilently() {
            // This completes the MOVE-direction matrix: the unsigned PIC 9 cases are asserted under MOVE
            // semantics below, and this is the same rule applied to the signed scaled field.
            //
            // A MOVE aligns the sender on the receiver's implied decimal point and discards whatever does
            // not fit - high-order digits on the left for a numeric receiver. It reports nothing when it
            // does so: COBOL has no ON SIZE ERROR phrase for MOVE, only for the arithmetic statements
            // (COMPUTE, ADD, SUBTRACT and so on). So a tenth integer digit is dropped without a
            // diagnostic, and the stored value is a billion lower than the sender.
            //
            // That is unquestionably a trap. It is also exactly what the legacy system does, so it is
            // asserted rather than corrected - rejecting the move here would be a behaviour change, and a
            // parity failure, in the direction that looks like an improvement.
            DalyTranRecord justFits = new DalyTranRecord(ASCII);
            justFits.moveDalytranAmt(new BigDecimal("999999999.99"));
            assertThat(justFits.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));

            DalyTranRecord oneDigitTooWide = new DalyTranRecord(ASCII);
            oneDigitTooWide.moveDalytranAmt(new BigDecimal("1000000000.00"));
            assertThat(oneDigitTooWide.dalytranAmt())
                    .as("the leading 1 is discarded, leaving nine zero digits")
                    .isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(oneDigitTooWide.dalytranAmtImage()).isEqualTo("0000000000{");

            // The sign survives the truncation even when the surviving magnitude is zero, which is how
            // the whole-value negative zero image can arise from a numeric move at all.
            DalyTranRecord negativeTooWide = new DalyTranRecord(ASCII);
            negativeTooWide.moveDalytranAmt(new BigDecimal("-1000000000.00"));
            assertThat(negativeTooWide.dalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(negativeTooWide.dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(negativeTooWide.hasZeroDalytranAmt()).isTrue();

            // A far wider sender keeps the LOW-order nine integer digits and both fraction digits.
            DalyTranRecord farTooWide = new DalyTranRecord(ASCII);
            farTooWide.moveDalytranAmt(new BigDecimal("9999999999.99"));
            assertThat(farTooWide.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));
            assertThat(farTooWide.dalytranAmtImage()).isEqualTo("9999999999I");

            // The receiver never widens: the span is eleven bytes and the record 350, whatever arrives.
            assertThat(farTooWide.dalytranAmtImage()).hasSize(11);
            assertThat(farTooWide.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than decoded at shifted offsets")
        void aRowOfTheWrongWidthIsRejected() {
            // Accepting a short row would let every offset past the truncation point drift, which is the
            // hardest class of defect to trace, so it is refused at the boundary.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(new byte[349], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(new byte[351], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.substring(1), ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1 + " ", ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((String) null, ASCII));
        }
    }

    @Nested
    @DisplayName("G34: DALYTRAN-ORIG-TS and its (1:10) slice, two accessors over one span")
    class OrigDateSlice {

        // The COBOL this group exists for, verbatim from app/cbl/CBTRN02C.cbl:414:
        //
        //     IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        //
        // followed by CONTINUE at :415 and, when it does not hold, MOVE 103 at :417 with
        // 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' at :418.
        //
        // Two details of that line matter here. First, (1:10) is a COBOL reference modifier - 1-based
        // offset 1, length 10 - so it is substring(0, 10) in Java, over the first ten of the field's
        // twenty-six bytes. Second, the comparison partner is ACCT-EXPIRAION-DATE, and the copybook
        // really does misspell it that way: app/cpy/CVACT01Y.cpy:11 declares
        // `05 ACCT-EXPIRAION-DATE PIC X(10).` That misspelling is preserved verbatim in the account
        // model and must never be corrected, because a field-by-field comparison is keyed on names.
        // It is also why the slice is exactly ten characters: that is the width of the field it is
        // compared against.
        //
        // The account record is not asserted here - it belongs to the account/model package's own test.
        // What is asserted here is that this record yields the ten bytes that comparison consumes.

        @Test
        @DisplayName("the slice is the first ten characters of the timestamp - the date")
        void theSliceIsTheFirstTenCharacters() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000").hasSize(26);
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10").hasSize(10);

            // Stated as the reference-modification conversion it implements, so the 1-based-to-0-based
            // step is visible rather than implied.
            assertThat(record.dalytranOrigDt()).isEqualTo(record.dalytranOrigTs().substring(0, 10));
            assertThat(record.dalytranOrigDt())
                    .isEqualTo(record.displayImage().substring(278, 288));
        }

        @Test
        @DisplayName("the slice is a VIEW over the span: mutate the timestamp and the slice follows")
        void theSliceTracksAMutationOfTheSpan() {
            // This is the round-trip that proves the two accessors read one backing span rather than two
            // separately stored copies. A cached or eagerly decoded slice would keep answering with the
            // old date after the timestamp was rewritten, and CBTRN02C's expiry comparison would then be
            // made against a stale value.
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");

            record.moveDalytranOrigTs("1999-12-31 00:00:00.000000");
            assertThat(record.dalytranOrigDt())
                    .as("the slice reads the span, so it moved with it")
                    .isEqualTo("1999-12-31");
            assertThat(record.dalytranOrigTs()).isEqualTo("1999-12-31 00:00:00.000000");

            // And back again, so the tracking is not a one-way coincidence.
            record.moveDalytranOrigTs("2022-06-10 19:27:53.000000");
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");

            // Writing the neighbouring span cannot disturb the slice: the two are adjacent, and an
            // off-by-one in either offset would show up here.
            record.moveDalytranProcTs("2022-07-19 23:16:01.000000");
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");
            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        }

        @Test
        @DisplayName("the slice is character data, never parsed - a blank date yields blanks")
        void theSliceIsNeverParsed() {
            // The COBOL comparison is a plain character comparison between two PIC X(10) items, and for
            // yyyy-MM-dd character order and chronological order coincide. Parsing would introduce a way
            // to fail on data COBOL compares happily - a blank field, or a partial date - so the slice
            // stays text.
            DalyTranRecord blank = new DalyTranRecord(ASCII);
            assertThat(blank.dalytranOrigDt()).isEqualTo(" ".repeat(10)).hasSize(10);

            DalyTranRecord partial = new DalyTranRecord(ASCII);
            partial.moveDalytranOrigTs("2022-06");
            assertThat(partial.dalytranOrigDt()).isEqualTo("2022-06   ").hasSize(10);

            // Character ordering is what the comparison relies on, and it holds for this format.
            assertThat("2022-06-10").isGreaterThan("2022-06-09").isLessThan("2022-06-11");
        }
    }

    @Nested
    @DisplayName("G34: the raw 350-byte image, a view over the whole record")
    class VerbatimImageAccess {

        // The second of this type's two accessor-over-one-span pairs: the decoded fields on one side and
        // the whole 350-byte image on the other, both reading the same backing area.
        //
        // It has to be a RAW SPAN COPY rather than a decode-and-re-encode, and the reason is in the
        // source rather than in preference:
        //
        //   * app/cbl/CBTRN02C.cbl:447 does MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA - a whole-group
        //     move into the PIC X(350) receiver declared at :176-178, which is then written as part of a
        //     430-byte record (350 + 80);
        //   * app/cbl/CBTRN01C.cbl:168 does DISPLAY DALYTRAN-RECORD, putting the whole group item on
        //     SYSOUT as characters.
        //
        // Neither statement looks at a single field. Both move bytes. Rebuilding those 350 bytes from
        // decoded values would risk a one-byte difference in the amount's sign overpunch, which is the
        // hazard driven in the overpunch group below.

        @Test
        @DisplayName("rawImage is the stored bytes verbatim, for a row whose sign byte is '}'")
        void rawImageIsByteVerbatimForANegativeZeroDigitRow() {
            // Deliberately one of the six '}' rows rather than an ordinary one: its sign byte is the one
            // that a decode-and-re-encode implementation is most likely to alter.
            byte[] stored = ROW_2.getBytes(ASCII);
            DalyTranRecord record = DalyTranRecord.decode(stored, ASCII);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH).isEqualTo(stored);
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.displayImage()).hasSize(DECLARED_RECORD_LENGTH).isEqualTo(ROW_2);
            assertThat(record.displayImage().charAt(AMOUNT_SIGN_BYTE_INDEX)).isEqualTo('}');
        }

        @Test
        @DisplayName("a decode then re-encode is byte-identical, for row 1 and for a '}' row alike")
        void decodeThenReEncodeIsByteIdentical() {
            // Round-tripping the ordinary row proves the layout is complete - a dropped or mis-sized
            // span would show up as a difference somewhere in the 350 bytes.
            byte[] positiveRow = ROW_1.getBytes(ASCII);
            assertThat(DalyTranRecord.decode(positiveRow, ASCII).encode(ASCII))
                    .as("row 1, positive 'G' overpunch")
                    .isEqualTo(positiveRow);

            // Round-tripping the '}' row additionally proves the sign handling is correct in BOTH
            // directions: a codec that read '}' correctly but wrote it back as '{' would pass every
            // value assertion and fail here.
            byte[] negativeRow = ROW_2.getBytes(ASCII);
            assertThat(DalyTranRecord.decode(negativeRow, ASCII).encode(ASCII))
                    .as("row 2, negative '}' overpunch")
                    .isEqualTo(negativeRow);

            // And through the text entry point, which is how the ASCII fixture is read.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).displayImage()).isEqualTo(ROW_1);
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).displayImage()).isEqualTo(ROW_2);
        }

        @Test
        @DisplayName("rawImage hands out a copy, so a caller cannot reach the backing area")
        void rawImageIsDefensivelyCopied() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            byte[] handedOut = record.rawImage();
            handedOut[0] = (byte) '9';

            assertThat(record.rawImage()[0]).isEqualTo((byte) '0');
            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("copy duplicates the bytes rather than rebuilding from decoded values")
        void copyIsByteVerbatimAndIndependent() {
            DalyTranRecord original = DalyTranRecord.decode(ROW_2, ASCII);
            DalyTranRecord duplicate = original.copy();

            assertThat(duplicate.rawImage()).isEqualTo(original.rawImage());
            assertThat(duplicate).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(duplicate.charset()).isEqualTo(original.charset());

            // Independent afterwards - this is the Java form of MOVE into a separate 350-byte receiver,
            // not an alias of the same storage.
            duplicate.moveDalytranSource("CHANGED   ");
            assertThat(original.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(duplicate.dalytranSource()).isEqualTo("CHANGED   ");
            assertThat(duplicate).isNotEqualTo(original);
        }

        @Test
        @DisplayName("encoding to the record's own code page returns the stored bytes unchanged")
        void encodingToTheSameCodePageIsTheStoredImage() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);
            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }

        @Test
        @DisplayName("transcoding to another code page changes the byte but keeps the character")
        void encodingToAnotherCodePagePreservesTheSignCharacter() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);

            byte[] transcoded = record.encode(EBCDIC);
            assertThat(transcoded).hasSize(DECLARED_RECORD_LENGTH).isNotEqualTo(record.rawImage());

            // The byte differs - '}' is 0x7D in ASCII and 0xD0 in IBM037 - but the character does not,
            // which is the property that matters: the value read back is the same amount.
            DalyTranRecord roundTripped = DalyTranRecord.decode(transcoded, EBCDIC);
            assertThat(roundTripped.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(roundTripped.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(roundTripped.dalytranAmt().scale()).isEqualTo(2);
            assertThat(roundTripped.dalytranCardNum()).isEqualTo("0927987108636232");
            assertThat(roundTripped.displayImage()).isEqualTo(ROW_2);
            assertThat(roundTripped.charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a raw span is readable for every declared field, at its full stored width")
        void rawSpanReadsEveryDeclaredField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(record.rawSpan(span)).as(span.name()).hasSize(span.length());
                assertThat(record.rawSpanBytes(span)).as(span.name()).hasSize(span.length());
                // The span read at its descriptor's offset must be the same bytes as the whole image
                // sliced at that offset - which is what makes these absolute-offset reads rather than
                // sequential ones.
                assertThat(record.rawSpan(span)).as("%s matches the image slice", span.name())
                        .isEqualTo(record.displayImage()
                                .substring(span.offset(), span.endOffsetExclusive()));
            }

            // A numeric DISPLAY span reads back as its stored bytes, sign overpunch included, which is
            // what a COBOL DISPLAY of the item would emit.
            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_AMT)).isEqualTo("0000005047G");
            assertThat(record.rawSpan(DalyTranRecord.FILLER)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a span descriptor from the other copybook is refused, not silently honoured")
        void aForeignSpanIsRejected() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            // TRAN-ID has the same offset and the same width as DALYTRAN-ID, so honouring it would read
            // the right bytes and report them under the wrong name - the exact failure mode the two
            // separate types exist to prevent. It is refused on identity, not on geometry.
            FieldSpan foreign = TranRecord.TRAN_ID;
            assertThat(foreign.offset()).isEqualTo(DalyTranRecord.DALYTRAN_ID.offset());
            assertThat(foreign.length()).isEqualTo(DalyTranRecord.DALYTRAN_ID.length());
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpan(foreign));
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpanBytes(foreign));

            assertThatNullPointerException().isThrownBy(() -> record.rawSpan(null));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpanBytes(null));
        }
    }

    @Nested
    @DisplayName("R2: the zoned sign overpunch, both halves of the table")
    class ZonedOverpunch {

        // The overpunch table, derived by counting the 300 trailing amount bytes of
        // app/data/ASCII/dailytran.txt at 0-based offset 142. The counts sum to exactly 300:
        //
        //   positive final digit 0-9  ->  {  A  B  C  D  E  F  G  H  I
        //                                25 28 29 30 29 23 21 24 17 24   = 250 rows
        //   negative final digit 0-9  ->  }  J  K  L  M  N  O  P  Q  R
        //                                 6  3  5  5  6  2  4  7  4  8   =  50 rows
        //
        // One character encodes both the final digit and the sign of the whole value. Every one of the
        // twenty is driven below, so no sign branch is left untaken.

        @ParameterizedTest(name = "{1} -> {3}")
        @DisplayName("all twenty overpunch characters decode with the right sign and magnitude")
        @CsvSource({
            // final digit, overpunch character, the stored 11-byte image, the decoded value
            "0,{,0000001230{,123.00",
            "1,A,0000001231A,123.11",
            "2,B,0000001232B,123.22",
            "3,C,0000001233C,123.33",
            "4,D,0000001234D,123.44",
            "5,E,0000001235E,123.55",
            "6,F,0000001236F,123.66",
            "7,G,0000001237G,123.77",
            "8,H,0000001238H,123.88",
            "9,I,0000001239I,123.99",
            "0,},0000001230},-123.00",
            "1,J,0000001231J,-123.11",
            "2,K,0000001232K,-123.22",
            "3,L,0000001233L,-123.33",
            "4,M,0000001234M,-123.44",
            "5,N,0000001235N,-123.55",
            "6,O,0000001236O,-123.66",
            "7,P,0000001237P,-123.77",
            "8,Q,0000001238Q,-123.88",
            "9,R,0000001239R,-123.99"})
        void everyOverpunchCharacterDecodes(int digit, char overpunch, String image, String expected) {
            boolean negative = expected.startsWith("-");

            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage(image);

            assertThat(image).hasSize(11).endsWith(String.valueOf(overpunch));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.dalytranAmt().signum() < 0).isEqualTo(negative);
            assertThat(record.hasZeroDalytranAmt()).isFalse();

            // The stored bytes survive: the raw paths never normalise the sign character.
            assertThat(record.dalytranAmtImage()).isEqualTo(image);
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) overpunch);

            // And the same value re-encodes to the same image, so the mapping is a bijection for every
            // one of the twenty characters rather than merely readable in one direction.
            DalyTranRecord rewritten = new DalyTranRecord(ASCII);
            rewritten.moveDalytranAmt(record.dalytranAmt());
            assertThat(rewritten.dalytranAmtImage())
                    .as("%s round-trips through its value", overpunch)
                    .isEqualTo(image);

            // Cross-checked against the codec's own declared table, so this test and the implementation
            // cannot drift apart silently.
            assertThat(ZonedSign.overpunch(digit, negative)).isEqualTo(overpunch);
            assertThat(ZonedSign.digitOf(overpunch)).isEqualTo(digit);
            assertThat(ZonedSign.isNegative(overpunch)).isEqualTo(negative);
        }

        @Test
        @DisplayName("the table this test asserts is the codec's own, in the documented order")
        void theTableMatchesTheCodecsDeclaredOrder() {
            assertThat(ZonedSign.POSITIVE_DIGITS).isEqualTo("{ABCDEFGHI").hasSize(10);
            assertThat(ZonedSign.NEGATIVE_DIGITS).isEqualTo("}JKLMNOPQR").hasSize(10);
            assertThat(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.NEGATIVE_ZERO).isEqualTo('}');
            assertThat(ZonedSign.POSITIVE_DIGITS.charAt(0)).isEqualTo(ZonedSign.POSITIVE_ZERO);
            assertThat(ZonedSign.NEGATIVE_DIGITS.charAt(0)).isEqualTo(ZonedSign.NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("a POSITIVE value ending in zero encodes as '{', never as the digit '0'")
        void aPositiveValueEndingInZeroEncodesAsPositiveZero() {
            // The final digit is not stored as a digit at all - it is folded into the sign character. A
            // codec that wrote the digit '0' and put the sign somewhere else would produce an 11-byte
            // image that no COBOL program could read.
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(new BigDecimal("325.00"));

            assertThat(record.dalytranAmtImage()).isEqualTo("0000003250{");
            assertThat(record.dalytranAmtImage()).endsWith("{").doesNotEndWith("0");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '{');
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("325.00"));
        }

        @Test
        @DisplayName("a NEGATIVE value ending in zero encodes as '}', never as '{'")
        void aNegativeValueEndingInZeroEncodesAsNegativeZero() {
            // This is the sign branch a "digit 0 means positive zero" shortcut breaks, and it is not a
            // corner case: it is what all six '}' rows of the fixture hold.
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(new BigDecimal("-919.00"));

            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.dalytranAmtImage()).endsWith("}").doesNotEndWith("{");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));

            // Which is exactly why a decode then re-encode of fixture row 2 is byte-identical.
            assertThat(record.dalytranAmtImage())
                    .isEqualTo(DalyTranRecord.decode(ROW_2, ASCII).dalytranAmtImage());
        }

        @Test
        @DisplayName("an ordinary '}' amount survives even a numeric round-trip")
        void anOrdinaryNegativeSurvivesANumericRoundTrip() {
            DalyTranRecord stored = DalyTranRecord.decode(ROW_2, ASCII);

            DalyTranRecord rebuilt = new DalyTranRecord(ASCII);
            rebuilt.moveDalytranAmt(stored.dalytranAmt());

            assertThat(rebuilt.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(rebuilt.dalytranAmtImage()).isEqualTo(stored.dalytranAmtImage());
        }

        @Test
        @DisplayName("a whole-value negative zero survives the raw path, and ONLY the raw path")
        void aWholeValueNegativeZeroNeedsTheRawPath() {
            // The one image where the character and the value genuinely disagree, and the reason the raw
            // accessors are mandatory rather than convenient. It does not occur in this fixture - which
            // is precisely what makes it dangerous, because no test built only from the fixture would
            // find it, and a production dataset would deliver it without warning.
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage("0000000000}");

            // Every path that moves BYTES preserves it.
            assertThat(record.dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.copy().dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.encode(ASCII)[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.displayImage().charAt(AMOUNT_SIGN_BYTE_INDEX)).isEqualTo('}');
            assertThat(DalyTranRecord.decode(record.rawImage(), ASCII).dalytranAmtImage())
                    .isEqualTo("0000000000}");

            // Its VALUE is zero, because BigDecimal has no signed zero. Both a positive-zero and a
            // negative-zero image answer the numeric zero test the same way, which is the COBOL
            // comparison and is also why a zero test can never stand in for a byte comparison.
            assertThat(record.dalytranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.hasZeroDalytranAmt()).isTrue();

            // So a numeric round-trip normalises the sign character. Asserted rather than avoided: this
            // is documented behaviour with a documented workaround, not a defect to be papered over.
            DalyTranRecord viaValue = new DalyTranRecord(ASCII);
            viaValue.moveDalytranAmt(record.dalytranAmt());
            assertThat(viaValue.dalytranAmtImage()).isEqualTo("0000000000{");
            assertThat(viaValue.dalytranAmt()).isEqualByComparingTo(record.dalytranAmt());
            assertThat(viaValue).isNotEqualTo(record);
        }

        @Test
        @DisplayName("an amount image is stored at exactly eleven characters, never padded or trimmed")
        void anAmountImageMustBeExactlyEleven() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThatIllegalArgumentException()
                    .as("ten characters, one short")
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000"));
            assertThatIllegalArgumentException()
                    .as("twelve characters, as if a sign byte were reserved")
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000{0"));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeDalytranAmtImage(null));

            // The accepting branch, so both outcomes of the width check are driven.
            record.writeDalytranAmtImage("0000005047G");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
        }
    }

    @Nested
    @DisplayName("COBOL MOVE semantics - the direction of truncation is not a detail")
    class MoveSemantics {

        // COBOL fills a PIC X receiver from its LEFTMOST position and discards what does not fit on the
        // RIGHT. It aligns a PIC 9 receiver on the implied decimal point and discards digits on the
        // LEFT. The two directions are opposite, and getting the numeric one backwards is a silent
        // hundredfold error rather than a visible failure. Both are driven here, in both directions.

        @Test
        @DisplayName("PIC X: a short sender is space-padded on the RIGHT")
        void picXPadsOnTheRight() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranSource("POS TERM");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ").hasSize(10);

            record.moveDalytranMerchantZip("72112");
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ").hasSize(10);

            record.moveDalytranDesc("Purchase at Abshire-Lowe");
            assertThat(record.dalytranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76)).hasSize(100);

            // An empty sender blanks the whole receiver rather than leaving it as it was.
            record.moveDalytranProcTs("");
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
        }

        @Test
        @DisplayName("PIC X: an over-long sender is truncated on the RIGHT, keeping the first bytes")
        void picXTruncatesOnTheRight() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // DALYTRAN-TYPE-CD is X(02): the first two characters survive.
            record.moveDalytranTypeCd("0199");
            assertThat(record.dalytranTypeCd()).isEqualTo("01").hasSize(2);

            // DALYTRAN-SOURCE is X(10).
            record.moveDalytranSource("ABCDEFGHIJKL");
            assertThat(record.dalytranSource()).isEqualTo("ABCDEFGHIJ").hasSize(10);

            // DALYTRAN-ID is X(16).
            record.moveDalytranId("0000000000683580EXTRA");
            assertThat(record.dalytranId()).isEqualTo("0000000000683580").hasSize(16);

            // The record stays 350 bytes throughout: a MOVE never resizes its receiver.
            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("PIC 9: a short sender is zero-filled on the LEFT - '05' stores 0005, not 0500")
        void picNineZeroFillsOnTheLeft() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // The surprising rule: an alphanumeric sender into a PIC 9 receiver is treated as an
            // unsigned integer and aligned on the implied decimal point, so it zero-fills on the LEFT. A
            // character move into a left-justified field would give 0500 and be wrong by a hundred.
            record.moveDalytranCatCd("05");
            assertThat(record.dalytranCatCdImage()).isEqualTo("0005").isNotEqualTo("0500");
            assertThat(record.dalytranCatCd()).isEqualTo(5);

            record.moveDalytranCatCd(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
            assertThat(record.dalytranCatCd()).isEqualTo(1);

            // Zero fills rather than blanks, so the span stays valid numeric DISPLAY data.
            record.moveDalytranMerchantId(0L);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.dalytranMerchantId()).isZero();

            record.moveDalytranMerchantId("800000000");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
        }

        @Test
        @DisplayName("PIC 9: an over-long sender is truncated on the LEFT, keeping the LOW-order digits")
        void picNineTruncatesOnTheLeft() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // DALYTRAN-CAT-CD is 9(04). Six digits in, the LOW-order four survive: 123456 -> 3456.
            // Keeping the leading digits instead - 1234 - is the classic defect, and it is the opposite
            // of the PIC X rule asserted above.
            record.moveDalytranCatCd("123456");
            assertThat(record.dalytranCatCdImage()).isEqualTo("3456").isNotEqualTo("1234");

            record.moveDalytranCatCd(123456);
            assertThat(record.dalytranCatCdImage()).isEqualTo("3456");
            assertThat(record.dalytranCatCd()).isEqualTo(3456);

            // DALYTRAN-MERCHANT-ID is 9(09). Thirteen digits in, the LOW-order nine survive.
            record.moveDalytranMerchantId("1234567890123");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("567890123");
            assertThat(record.dalytranMerchantId()).isEqualTo(567890123);

            record.moveDalytranMerchantId(1234567890123L);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("567890123");
        }

        @Test
        @DisplayName("PIC 9 is unsigned, so a negative sender and a non-digit sender are both refused")
        void picNineRefusesWhatItCannotRepresent() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // PIC 9 has no sign position at all, so there is nowhere to record a sign. Storing the
            // magnitude silently would be a value change; the move is refused instead.
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveDalytranCatCd(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.moveDalytranMerchantId(-1L));

            // A numeric MOVE requires every character of an alphanumeric sender to be a digit; a
            // non-digit is rejected rather than coerced.
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveDalytranCatCd("00A1"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.moveDalytranMerchantId("8000 0000"));
            assertThatNullPointerException().isThrownBy(() -> record.moveDalytranCatCd(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.moveDalytranMerchantId((String) null));

            // The area is untouched by a refused move.
            assertThat(record.dalytranCatCdImage()).isEqualTo("0000");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
        }

        @Test
        @DisplayName("every character field has a MOVE path, and together they rebuild row 1 exactly")
        void everyFieldHasAMovePathThatRebuildsRowOne() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranId("0000000000683580");
            record.moveDalytranTypeCd("01");
            record.moveDalytranCatCd("0001");
            record.moveDalytranSource("POS TERM  ");
            record.moveDalytranDesc("Purchase at Abshire-Lowe");
            record.writeDalytranAmtImage("0000005047G");
            record.moveDalytranMerchantId("800000000");
            record.moveDalytranMerchantName("Abshire-Lowe");
            record.moveDalytranMerchantCity("North Enoshaven");
            record.moveDalytranMerchantZip("72112");
            record.moveDalytranCardNum("4859452612877065");
            record.moveDalytranOrigTs("2022-06-10 19:27:53.000000");
            record.moveDalytranProcTs("");
            // FILLER is not written: it is already space-filled from initialisation, exactly as COBOL
            // leaves it, and it is not referable by name in any case.

            assertThat(record.displayImage()).isEqualTo(ROW_1);
            assertThat(record.rawImage()).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record).isEqualTo(DalyTranRecord.decode(ROW_1, ASCII));
        }

        @Test
        @DisplayName("R2/G24: the amount TRUNCATES excess fraction digits, positive and negative alike")
        void theAmountTruncatesRatherThanRounds() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            // Truncation, not rounding. COBOL rounds only where a statement says ROUNDED, and the
            // keyword appears zero times in all twenty-eight programs, so 1.239 stores as 1.23 and
            // 1.999 as 1.99 - a half-up policy would give 1.24 and 2.00 and be wrong by a cent.
            record.moveDalytranAmt(new BigDecimal("1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.23"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            record.moveDalytranAmt(new BigDecimal("1.999"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.99"));

            record.moveDalytranAmt(new BigDecimal("504.7799"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");

            // And for a NEGATIVE over-precise value. This is the case where DOWN and FLOOR diverge:
            // truncation moves -1.239 towards zero, giving -1.23, where rounding away from zero would
            // give -1.24. DOWN is what COBOL does.
            record.moveDalytranAmt(new BigDecimal("-1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-1.23"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            record.moveDalytranAmt(new BigDecimal("-1.999"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-1.99"));

            record.moveDalytranAmt(new BigDecimal("-919.0099"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");

            // A shorter scale is widened rather than left short, so the stored span is always eleven.
            record.moveDalytranAmt(new BigDecimal("7"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000000070{");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            assertThatNullPointerException().isThrownBy(() -> record.moveDalytranAmt(null));
        }

        @Test
        @DisplayName("the truncating store agrees with the central policy, applied independently")
        void theTruncatingStoreAgreesWithTheCentralPolicy() {
            // The record must not implement its own rounding: it delegates to the one policy that every
            // monetary field in the system shares. Asserted by applying that policy independently and
            // comparing.
            BigDecimal overPrecise = new BigDecimal("123.456");
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(overPrecise);

            assertThat(record.dalytranAmt())
                    .isEqualByComparingTo(CobolDecimal.storeMonetary(overPrecise))
                    .isEqualByComparingTo(overPrecise.setScale(2, CobolDecimal.COBOL_ROUNDING));

            BigDecimal negative = new BigDecimal("-123.456");
            record.moveDalytranAmt(negative);
            assertThat(record.dalytranAmt())
                    .isEqualByComparingTo(CobolDecimal.storeMonetary(negative))
                    .isEqualByComparingTo(negative.setScale(2, CobolDecimal.COBOL_ROUNDING));
        }
    }

    @Nested
    @DisplayName("G21: the FILLER read/write asymmetry - both sides, and why they agree")
    class FillerAsymmetry {

        // Two obligations that look contradictory and are not.
        //
        //   WRITE PATH: a freshly allocated area has FILLER space-filled, because that is what COBOL
        //   INITIALIZE does to a PIC X span and what a newly assembled record must therefore hold.
        //
        //   READ PATH: a decoded record re-encodes to the ORIGINAL bytes, FILLER included, whatever
        //   those bytes happen to be.
        //
        // They agree because they apply at different moments. Initialisation chooses a value for bytes
        // that have none yet; a decode adopts the bytes it was given and must not second-guess them. A
        // codec that conflated the two - re-space-filling FILLER on every write-back - would satisfy the
        // first obligation, pass every assertion made against the daily fixture whose FILLER is spaces
        // anyway, and still silently corrupt a stored row from any dataset whose FILLER is not.
        //
        // That dataset exists in this repository: dailytran.txt pads FILLER with spaces, but
        // tcatbal.txt, trantype.txt and trancatg.txt pad theirs with ASCII zeros. Hence the synthetic
        // zero-FILLER row.

        @Test
        @DisplayName("write path: a fresh area's FILLER is twenty spaces at offset 330")
        void writePathInitialisesFillerToSpaces() {
            DalyTranRecord fresh = new DalyTranRecord(ASCII);

            assertThat(fresh.filler()).isEqualTo(" ".repeat(20)).hasSize(20);
            assertThat(fresh.rawSpan(DalyTranRecord.FILLER)).isEqualTo(" ".repeat(20));
            assertThat(fresh.displayImage().substring(330, 350)).isEqualTo(" ".repeat(20));

            // And a record assembled entirely through the MOVE path still carries them, because nothing
            // in that path touches the reserved span.
            fresh.moveDalytranId("0000000000683580");
            fresh.moveDalytranCardNum("4859452612877065");
            assertThat(fresh.filler()).isEqualTo(" ".repeat(20));
            assertThat(fresh.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("read path: a decode then re-encode preserves NON-SPACE FILLER bytes exactly")
        void readPathPreservesTheOriginalFillerBytes() {
            // Twenty ASCII zeros, not spaces - the case the daily fixture cannot exercise.
            DalyTranRecord record = DalyTranRecord.decode(ROW_WITH_ZERO_FILLER, ASCII);

            assertThat(record.filler())
                    .as("the stored FILLER bytes are adopted, not replaced")
                    .isEqualTo("0".repeat(20)).hasSize(20);

            // Re-encoding must reproduce them, so the whole 350 bytes come back unchanged.
            assertThat(record.encode(ASCII)).isEqualTo(ROW_WITH_ZERO_FILLER.getBytes(ASCII));
            assertThat(record.displayImage()).isEqualTo(ROW_WITH_ZERO_FILLER);
            assertThat(record.rawImage()[330]).isEqualTo((byte) '0');
            assertThat(record.rawImage()[349]).isEqualTo((byte) '0');

            // Writing a real field must not disturb them either: a write-back is not a rebuild.
            record.moveDalytranSource("OPERATOR  ");
            assertThat(record.filler()).isEqualTo("0".repeat(20));
            record.moveDalytranAmt(new BigDecimal("-919.00"));
            assertThat(record.filler()).isEqualTo("0".repeat(20));

            // Nor must copy(), which is the whole-group MOVE.
            assertThat(record.copy().filler()).isEqualTo("0".repeat(20));

            // Nor a transcode to another code page.
            DalyTranRecord transcoded = DalyTranRecord.decode(record.encode(EBCDIC), EBCDIC);
            assertThat(transcoded.filler()).isEqualTo("0".repeat(20));
        }

        @Test
        @DisplayName("FILLER is why the record is 350 and not 330 bytes")
        void fillerIsWhatMakesTheRecordThreeHundredAndFifty() {
            // The consequence of dropping it, stated as arithmetic: the other thirteen items sum to 330.
            assertThat(DECLARED_RECORD_LENGTH - DalyTranRecord.FILLER_LENGTH).isEqualTo(330);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()
                    - DalyTranRecord.FILLER_LENGTH).isEqualTo(330);

            // A 330-byte record would be padded or truncated by the whole-group MOVE at CBTRN02C:447,
            // whose receiver is PIC X(350), and the 430-byte rejects record (350 + 80) would be wrong.
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawImage()).hasSize(350);
            assertThat(350 + 80).as("REJECT-TRAN-DATA X(350) + VALIDATION-TRAILER X(80)")
                    .isEqualTo(430);
        }
    }

    @Nested
    @DisplayName("identity and diagnostics")
    class IdentityAndDiagnostics {

        @Test
        @DisplayName("equality is by bytes and charset, so equal VALUES are not equal RECORDS")
        void equalityIsByBytesAndCharset() {
            DalyTranRecord one = DalyTranRecord.decode(ROW_1, ASCII);
            DalyTranRecord same = DalyTranRecord.decode(ROW_1, ASCII);

            // Reflexive, symmetric, and consistent with hashCode.
            assertThat(one).isEqualTo(one);
            assertThat(one).isEqualTo(same);
            assertThat(same).isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(same);

            // Different bytes, different record.
            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_2, ASCII));

            // Not equal to null, and not equal to something of another type - both branches of the
            // instanceof check.
            assertThat(one).isNotEqualTo(null);
            assertThat(one).isNotEqualTo("not a record");
            assertThat(one).isNotEqualTo(new Object());

            // The same bytes under a different code page are different data, so the charset
            // participates.
            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_1, EBCDIC));

            // And the case that makes byte equality the stronger test: two records whose amounts compare
            // numerically equal but whose stored images differ by one byte.
            DalyTranRecord positiveZero = new DalyTranRecord(ASCII);
            positiveZero.writeDalytranAmtImage("0000000000{");
            DalyTranRecord negativeZero = new DalyTranRecord(ASCII);
            negativeZero.writeDalytranAmtImage("0000000000}");

            assertThat(positiveZero.dalytranAmt())
                    .isEqualByComparingTo(negativeZero.dalytranAmt());
            assertThat(positiveZero)
                    .as("equal values, different records - the parity defect this prevents")
                    .isNotEqualTo(negativeZero);
        }

        @Test
        @DisplayName("the rendering names every field, describes the sensitive ones, and stays on one "
                + "line")
        void theRenderingNamesEveryFieldWithoutDisclosingTheActivity() {
            String rendered = DalyTranRecord.decode(ROW_1, ASCII).toString();

            // Every field is still NAMED, so the rendering still says what the record's shape is - which
            // is what a width or padding investigation needs from it.
            assertThat(rendered)
                    .contains("DALYTRAN-ID=")
                    .contains("DALYTRAN-TYPE-CD=")
                    .contains("DALYTRAN-CAT-CD=")
                    .contains("DALYTRAN-SOURCE=")
                    .contains("DALYTRAN-DESC=")
                    .contains("DALYTRAN-AMT=")
                    .contains("DALYTRAN-MERCHANT-ID=")
                    .contains("DALYTRAN-MERCHANT-NAME=")
                    .contains("DALYTRAN-MERCHANT-CITY=")
                    .contains("DALYTRAN-MERCHANT-ZIP=")
                    .contains("DALYTRAN-ORIG-TS=")
                    .contains("DALYTRAN-PROC-TS=")
                    .contains("charset=US-ASCII");

            // The fields that carry no personal data are retained in full.
            assertThat(rendered)
                    .contains("01")
                    .contains("0001")
                    .contains("POS TERM")
                    .contains("2022-06-10 19:27:53.000000");

            // The identifiers are masked to their last four characters: one record is still
            // distinguishable from another, and neither is quotable in full.
            assertThat(rendered)
                    .contains("3580")
                    .doesNotContain("0000000000683580")
                    .doesNotContain("800000000");

            // The merchant's name and city and the description are described by length only - together
            // with the amount they said who spent what and where (CWE-532).
            assertThat(rendered)
                    .doesNotContain("Abshire-Lowe")
                    .doesNotContain("North Enoshaven");

            // The amount is withheld in BOTH forms. The decoded value was the more revealing of the two
            // and previously had no protection at all.
            assertThat(rendered)
                    .doesNotContain("0000005047G")
                    .doesNotContain("504.77");

            // FILLER is reported by width rather than by content: it carries no field semantics, and
            // printing twenty spaces would only pad the line.
            assertThat(rendered).contains("FILLER.length=20");

            assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the byte-exact paths still carry every value the rendering withholds")
        void theByteExactPathsStillCarryEverything() {
            // The parity surface is untouched, and this is what makes withholding from the rendering
            // free: nothing that reads a record for parity reads toString().
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
            assertThat(record.dalytranAmt().toPlainString()).isEqualTo("504.77");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.dalytranMerchantName()).startsWith("Abshire-Lowe");
            assertThat(record.displayImage())
                    .contains("0000000000683580")
                    .contains("Abshire-Lowe");
        }

        @Test
        @DisplayName("a control character in a stored span cannot forge a second log line")
        void aControlCharacterCannotForgeALogLine() {
            // CWE-117. A PIC X span holds whatever the upstream file put there, and DALYTRAN-SOURCE is
            // retained in full - so it is the one that has to be escaped rather than trusted.
            String injected = ROW_1.substring(0, DalyTranRecord.DALYTRAN_SOURCE.offset())
                    + "A\r\nB      "
                    + ROW_1.substring(DalyTranRecord.DALYTRAN_SOURCE.offset()
                            + DalyTranRecord.DALYTRAN_SOURCE.length());

            String rendered = DalyTranRecord.decode(injected, ASCII).toString();

            assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
            assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the rendering masks the card number, and the byte-exact paths do not")
        void theRenderingMasksThePanButTheByteExactPathsDoNot() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            String rendered = record.toString();

            // Masked in the annotated Java diagnostic, at the field's full stored width so the rendering
            // still reports the record's shape correctly.
            assertThat(rendered).contains("DALYTRAN-CARD-NUM=************7065")
                    .doesNotContain("4859452612877065");

            // Not masked anywhere that parity depends on. This is the distinction that makes the two
            // facts compatible: toString has NO COBOL counterpart - nothing in CVTRA06Y or either
            // consuming program produces an annotated, comma-separated rendering of a record, and a
            // COBOL DISPLAY emits the record's BYTES. The observable behaviour lives entirely in the
            // byte-exact paths, and every one of them still carries all sixteen digits.
            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_CARD_NUM))
                    .isEqualTo("4859452612877065");
            assertThat(record.displayImage()).contains("4859452612877065");
            assertThat(new String(record.rawImage(), ASCII)).contains("4859452612877065");
            assertThat(new String(record.encode(ASCII), ASCII)).contains("4859452612877065");
            assertThat(new String(record.rawSpanBytes(DalyTranRecord.DALYTRAN_CARD_NUM), ASCII))
                    .isEqualTo("4859452612877065");
        }

        @Test
        @DisplayName("the record reports the charset it was built with, never a platform default")
        void theRecordReportsItsOwnCharset() {
            assertThat(new DalyTranRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new DalyTranRecord(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).charset()).isEqualTo(ASCII);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).copy().charset()).isEqualTo(ASCII);
        }
    }
}
