package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranCategoryRecord}, the 60-byte {@code TRANCATG} transaction-category record
 * declared by {@code app/cpy/CVTRA04Y.cpy} with a 6-byte composite key.
 *
 * <h2>Provenance - every expectation traces to a reference artefact</h2>
 * Nothing below is restated from the implementation; each expectation is derived from a read-only
 * source in this repository, and the source is named at the point it is used.
 * <ul>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} - the primary contract. {@code 01 TRAN-CAT-RECORD} is
 *       {@code 05 TRAN-CAT-KEY} (itself {@code 10 TRAN-TYPE-CD PIC X(02)} plus
 *       {@code 10 TRAN-CAT-CD PIC 9(04)}), then {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}, then
 *       {@code 05 FILLER PIC X(04)}. The header comment reads {@code RECLN = 60}, and the widths add
 *       up to it: {@code 2 + 4 + 50 + 4 = 60}, with the key at {@code 2 + 4 = 6}.</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} - the 17-byte namesake key. Collision 1 below.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - the 350-byte transaction record that reuses this record's two
 *       item names at different offsets. Collision 2 below.</li>
 *   <li>{@code app/cpy/CVTRA03Y.cpy} - the transaction-type lookup that spells its key without the
 *       {@code -CD} suffix. Collision 3 below.</li>
 *   <li>{@code app/cpy/CVTRA07Y.cpy} - the report layouts, whose
 *       {@code 05 TRAN-REPORT-CAT-DESC PIC X(29)} at {@code :26} is the receiver that makes this
 *       record's untrimmed 50-byte description load-bearing.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} - the one and only COBOL consumer. It reads the record at
 *       {@code :505} ({@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD}), renders the 6-byte key at
 *       {@code :507}, and performs the truncating move at {@code :368}.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} - binds the {@code TRANCATG} DD as a keyed input at
 *       {@code :71-72}, under {@code STEP10R EXEC PGM=CBTRN03C} at {@code :59}.</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} - the shipped fixture: 1,098 bytes, being 18 rows of
 *       exactly 60 bytes each plus their line terminators. All 18 rows are reproduced verbatim below
 *       as literals.</li>
 * </ul>
 * The expectations are <strong>statically derived</strong>. COBOL cannot be executed in this
 * environment, so nothing here was captured from a live run; the four-way cross-check above - copybook
 * layout, consumer program, job binding, real fixture - is what stands in for a captured baseline.
 *
 * <h2>Why the fixture is embedded rather than read</h2>
 * The 18 rows are {@code private static final String} literals, copied byte for byte from
 * {@code app/data/ASCII/trancatg.txt}. This test opens no file and loads no classpath resource, for
 * two reasons. A model test must fail on a defect in the model, never on a missing or relocated test
 * resource - a resource lookup that returns {@code null} would surface as an I/O error and take the
 * per-package branch-coverage gate down with it, masking rather than revealing. And an embedded
 * literal is reviewable: the bytes being asserted are visible in the same file as the assertion,
 * so a reviewer can compare them against the copybook offsets without leaving this page.
 *
 * <h2>Rules status</h2>
 * {@code review_rules} reports that no user rules were provided for this project, so no project rule
 * governs this file. Its absence is not licence to lower the bar: the binding constraints applied here
 * are the migration plan's own - fixed width asserted at absolute offsets, offsets justified by
 * addition, no {@code double} or {@code float}, no wildcard import, no mutable static state, no Spring
 * context, no persistence mapping, and every charset named explicitly rather than defaulted.
 *
 * <h2>Scope - what this file deliberately leaves to its siblings</h2>
 * This is a record-model test. It asserts byte layout, the composite key sub-span, decode and encode
 * fidelity and COBOL {@code MOVE} semantics, and nothing else. Dataset access, the
 * {@code INVALID KEY} ladder at {@code app/cbl/CBTRN03C.cbl:505-511}, the report's page and account
 * total state machine and the 133-byte report line all belong to the repository, job and report-layout
 * tests in the sibling packages, and are not duplicated here.
 */
@DisplayName("TranCategoryRecord - CVTRA04Y TRAN-CAT-RECORD, 60 bytes, 6-byte composite key")
class TranCategoryRecordTest {

    /** The text fixtures are ASCII. Named explicitly; a platform default is never relied on. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, named explicitly for the same reason. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** {@code CVTRA04Y}'s declared record width, {@code RECLN = 60}. */
    private static final int DECLARED_RECORD_LENGTH = 60;

    /** {@code TRAN-CAT-KEY}'s declared width: {@code X(02)} plus {@code 9(04)}. */
    private static final int DECLARED_KEY_LENGTH = 6;

    /** {@code TRAN-CAT-TYPE-DESC}'s declared width, {@code PIC X(50)}. */
    private static final int DECLARED_DESC_LENGTH = 50;

    /**
     * {@code CVTRA07Y}'s {@code TRAN-REPORT-CAT-DESC PIC X(29)} at {@code app/cpy/CVTRA07Y.cpy:26} -
     * the receiver of the truncating move at {@code app/cbl/CBTRN03C.cbl:368}.
     */
    private static final int REPORT_CAT_DESC_LENGTH = 29;

    /**
     * {@code CVTRA01Y}'s {@code TRANCAT-ACCT-ID PIC 9(11)} - the item this record has no notion of,
     * and the entire difference between the two identically-named 6-byte and 17-byte keys.
     */
    private static final int NAMESAKE_ACCT_ID_LENGTH = 11;

    /** {@code CVTRA01Y}'s {@code TRAN-CAT-KEY}: {@code 11 + 2 + 4 = 17}. The trap, never the target. */
    private static final int NAMESAKE_KEY_LENGTH = 17;

    /** {@code CVTRA05Y}'s {@code TRAN-TYPE-CD} offset - after {@code TRAN-ID PIC X(16)}, so 16. */
    private static final int CVTRA05Y_TYPE_CD_OFFSET = 16;

    /** {@code CVTRA05Y}'s {@code TRAN-CAT-CD} offset - {@code 16 + 2 = 18}. */
    private static final int CVTRA05Y_CAT_CD_OFFSET = 18;

    /** Every fixture row's trailing {@code FILLER X(04)}: four ASCII zeros, and not spaces. */
    private static final String FIXTURE_FILLER = "0000";

    /** A freshly built record's {@code FILLER X(04)}: four spaces, the initialised default. */
    private static final String BLANK_FILLER = "    ";

    // =================================================================================================
    // The shipped fixture, verbatim. app/data/ASCII/trancatg.txt, 18 rows, 1-based bytes 1-60 of each.
    // Each literal is exactly 60 characters: TRAN-TYPE-CD 1-2, TRAN-CAT-CD 3-6, TRAN-CAT-TYPE-DESC
    // 7-56 space-padded, FILLER 57-60. everyEmbeddedRowIsSixtyCharacters() proves the width, and
    // theEmbeddedRowsSegmentAtTheCopybookOffsets() proves each literal segments where the copybook says.
    // =================================================================================================

    /** Row 1: type {@code 01}, category {@code 0001}, {@code "Regular Sales Draft"} (19 chars). */
    private static final String ROW_01 = "010001Regular Sales Draft                               0000";

    /** Row 2: type {@code 01}, category {@code 0002}, {@code "Regular Cash Advance"} (20 chars). */
    private static final String ROW_02 = "010002Regular Cash Advance                              0000";

    /** Row 3: type {@code 01}, category {@code 0003}, {@code "Convenience Check Debit"} (23 chars). */
    private static final String ROW_03 = "010003Convenience Check Debit                           0000";

    /** Row 4: type {@code 01}, category {@code 0004}, {@code "ATM Cash Advance"} (16 chars). */
    private static final String ROW_04 = "010004ATM Cash Advance                                  0000";

    /** Row 5: type {@code 01}, category {@code 0005}, {@code "Interest Amount"} (15 chars). */
    private static final String ROW_05 = "010005Interest Amount                                   0000";

    /** Row 6: type {@code 02}, category {@code 0001}, {@code "Cash payment"} (12 chars). */
    private static final String ROW_06 = "020001Cash payment                                      0000";

    /** Row 7: type {@code 02}, category {@code 0002}, {@code "Electronic payment"} (18 chars). */
    private static final String ROW_07 = "020002Electronic payment                                0000";

    /** Row 8: type {@code 02}, category {@code 0003}, {@code "Check payment"} (13 chars). */
    private static final String ROW_08 = "020003Check payment                                     0000";

    /** Row 9: type {@code 03}, category {@code 0001}, {@code "Credit to Account"} (17 chars). */
    private static final String ROW_09 = "030001Credit to Account                                 0000";

    /** Row 10: type {@code 03}, category {@code 0002}, {@code "Credit to Purchase balance"} (26). */
    private static final String ROW_10 = "030002Credit to Purchase balance                        0000";

    /** Row 11: type {@code 03}, category {@code 0003}, {@code "Credit to Cash balance"} (22 chars). */
    private static final String ROW_11 = "030003Credit to Cash balance                            0000";

    /** Row 12: type {@code 04}, category {@code 0001}, {@code "Zero dollar authorization"} (25). */
    private static final String ROW_12 = "040001Zero dollar authorization                         0000";

    /**
     * Row 13: type {@code 04}, category {@code 0002}, {@code "Online purchase authorization"} -
     * <strong>exactly 29 characters</strong>, one of the two widest descriptions in the fixture and
     * therefore a boundary case for the {@code PIC X(29)} receiver at
     * {@code app/cbl/CBTRN03C.cbl:368}. It fills that receiver exactly and loses nothing.
     */
    private static final String ROW_13 = "040002Online purchase authorization                     0000";

    /** Row 14: type {@code 04}, category {@code 0003}, {@code "Travel booking authorization"} (28). */
    private static final String ROW_14 = "040003Travel booking authorization                      0000";

    /** Row 15: type {@code 05}, category {@code 0001}, {@code "Refund credit"} (13 chars). */
    private static final String ROW_15 = "050001Refund credit                                     0000";

    /** Row 16: type {@code 06}, category {@code 0001}, {@code "Fraud reversal"} (14 chars). */
    private static final String ROW_16 = "060001Fraud reversal                                    0000";

    /** Row 17: type {@code 06}, category {@code 0002}, {@code "Non-fraud reversal"} (18 chars). */
    private static final String ROW_17 = "060002Non-fraud reversal                                0000";

    /**
     * Row 18: type {@code 07}, category {@code 0001}, {@code "Sales draft credit adjustment"} -
     * the other <strong>exactly 29</strong> character description, and the fixture's second and last
     * boundary case for the {@code PIC X(29)} report receiver.
     */
    private static final String ROW_18 = "070001Sales draft credit adjustment                     0000";

    /**
     * A description longer than the report receiver, which <strong>no fixture row supplies</strong>.
     *
     * <p>The widest description shipped in {@code app/data/ASCII/trancatg.txt} is exactly 29
     * characters, so every real row fills {@code TRAN-REPORT-CAT-DESC PIC X(29)} without losing a
     * byte. That leaves the discarding half of the {@code MOVE} at {@code app/cbl/CBTRN03C.cbl:368}
     * unexercised by real data, even though {@code TRAN-CAT-TYPE-DESC} is 50 bytes wide and can hold
     * any value up to that width. This synthetic value closes the gap: it is 45 characters, so a move
     * into the 29-byte receiver keeps {@link #SYNTHETIC_SURVIVING_29} and discards
     * {@link #SYNTHETIC_DISCARDED_TAIL}, and {@code 29 + 16 = 45} accounts for every character.
     */
    private static final String SYNTHETIC_LONG_DESC = "Provisional credit adjustment reversal notice";

    /** The leading 29 characters of {@link #SYNTHETIC_LONG_DESC} - what a {@code PIC X(29)} keeps. */
    private static final String SYNTHETIC_SURVIVING_29 = "Provisional credit adjustment";

    /** The trailing 16 characters of {@link #SYNTHETIC_LONG_DESC} - what that receiver discards. */
    private static final String SYNTHETIC_DISCARDED_TAIL = " reversal notice";

    /**
     * A description of exactly 30 characters: one past the receiver, so the move discards exactly one
     * character. The tightest possible proof that the boundary sits between 29 and 30.
     */
    private static final String SYNTHETIC_THIRTY_DESC = "Provisional credit adjustments";

    /**
     * The 18 embedded rows in dataset order, as an immutable list.
     *
     * <p>A method returning {@link List#of} rather than a static collection field, so this class holds
     * no mutable static state of any kind and no test can perturb what another test reads.
     *
     * @return the 18 rows, each exactly 60 characters, in the order the dataset stores them
     */
    private static List<String> fixtureRows() {
        return List.of(ROW_01, ROW_02, ROW_03, ROW_04, ROW_05, ROW_06, ROW_07, ROW_08, ROW_09,
                ROW_10, ROW_11, ROW_12, ROW_13, ROW_14, ROW_15, ROW_16, ROW_17, ROW_18);
    }

    /**
     * One embedded row by its 1-based dataset row number, matching how the fixture is cited.
     *
     * @param rowNumber the 1-based row number, 1 to 18
     * @return that row's 60 characters
     */
    private static String fixtureRow(int rowNumber) {
        return fixtureRows().get(rowNumber - 1);
    }

    /**
     * Decodes an embedded row under {@code US-ASCII}, the Java form of
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} at {@code app/cbl/CBTRN03C.cbl:505}.
     *
     * @param rowNumber the 1-based row number, 1 to 18
     * @return the decoded record
     */
    private static TranCategoryRecord decodedRow(int rowNumber) {
        return TranCategoryRecord.decode(fixtureRow(rowNumber).getBytes(ASCII), ASCII);
    }

    /**
     * Pads on the right with spaces, which is how COBOL fills an alphanumeric receiver.
     *
     * <p>Written out here rather than borrowed from {@link FixedWidthCodec}, so an expected image is
     * never produced by the same code that produces the actual one.
     *
     * @param value the sending value; must not be longer than {@code width}
     * @param width the receiver's declared width
     * @return the value followed by enough spaces to reach {@code width}
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Left-zero-fills a value to a width, which is how COBOL stores into an unsigned numeric receiver.
     *
     * <p>Built from {@link Integer#toString(int)} and a literal {@code '0'} rather than from
     * {@code String.format}, deliberately: a format string takes its digit glyphs from the default
     * locale, so {@code "%04d"} renders Arabic-Indic digits under an Arabic locale and this test would
     * pass or fail according to the JVM it ran on. {@link Integer#toString(int)} always emits ASCII
     * digits, which is what a zoned {@code DISPLAY} field holds.
     *
     * @param value the value; must not be negative and must fit in {@code width} digits
     * @param width the receiver's declared digit count
     * @return the value's digits preceded by enough zeros to reach {@code width}
     */
    private static String zoned(int value, int width) {
        String digits = Integer.toString(value);
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Performs {@code app/cbl/CBTRN03C.cbl:368} - {@code MOVE TRAN-CAT-TYPE-DESC TO
     * TRAN-REPORT-CAT-DESC} - at the point of use, exactly as the migrated report writer must.
     *
     * <p>The truncation belongs here and never in the model: {@link TranCategoryRecord} hands out all
     * 50 declared bytes, and the receiver's width is applied by whoever owns the receiver.
     *
     * @param storedDescription the 50-character stored description, untrimmed
     * @return the {@link #REPORT_CAT_DESC_LENGTH} characters a {@code PIC X(29)} receiver would hold
     */
    private static String movedIntoReportField(String storedDescription) {
        return new FixedWidthCodec(ASCII).movePicX(storedDescription, REPORT_CAT_DESC_LENGTH);
    }

    @Nested
    @DisplayName("Declared geometry - CVTRA04Y's own arithmetic, 2+4+50+4 = 60")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 60 bytes, and the declared spans add up to it")
        void theRecordIsSixtyBytes() {
            // RECLN = 60 is the copybook header's own figure. Asserted three ways so a drift in any one
            // of them is caught: the constant, the layout's declared length, and the summed spans.
            assertThat(TranCategoryRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(TranCategoryRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(TranCategoryRecord.sumOfDeclaredSpanWidths()).isEqualTo(DECLARED_RECORD_LENGTH);

            // The addition itself, written out from the PICTURE clauses rather than asserted as a
            // single literal: TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) + TRAN-CAT-TYPE-DESC X(50)
            // + FILLER X(04) = 2 + 4 + 50 + 4 = 60.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH
                    + TranCategoryRecord.TRAN_CAT_CD_LENGTH
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                    + TranCategoryRecord.FILLER_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);

            // And the same 60 grouped as the copybook groups it: key 6 + description 50 + FILLER 4.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                    + TranCategoryRecord.FILLER_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("every span sits at the copybook's offset: 0, 2, 6 and 56")
        void everySpanSitsAtItsCopybookOffset() {
            // 0-based offsets, with the copybook's own 1-based columns in the comment. Each offset is
            // the running total of the widths ahead of it, so the arithmetic is visible:
            //   TRAN-TYPE-CD        cols 1-2    offset 0            (nothing ahead of it)
            //   TRAN-CAT-CD         cols 3-6    offset 0 + 2  =  2
            //   TRAN-CAT-TYPE-DESC  cols 7-56   offset 2 + 4  =  6
            //   FILLER              cols 57-60  offset 6 + 50 = 56
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_CAT_CD_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_TYPE_CD_OFFSET
                            + TranCategoryRecord.TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_CD_OFFSET
                            + TranCategoryRecord.TRAN_CAT_CD_LENGTH)
                    .isEqualTo(6);
            assertThat(TranCategoryRecord.FILLER_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET
                            + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER_OFFSET + TranCategoryRecord.FILLER_LENGTH)
                    .as("the FILLER ends on the record's last byte, leaving nothing over")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the four spans are declared in copybook order, contiguously, with no overlay")
        void theFourSpansAreDeclaredInCopybookOrder() {
            List<FieldSpan> spans = TranCategoryRecord.LAYOUT.storageSpans();

            assertThat(spans).containsExactly(
                    TranCategoryRecord.TRAN_TYPE_CD,
                    TranCategoryRecord.TRAN_CAT_CD,
                    TranCategoryRecord.TRAN_CAT_TYPE_DESC,
                    TranCategoryRecord.FILLER);
            // CVTRA04Y declares no REDEFINES, so the layout must declare no overlay either - inventing
            // one would double-count bytes the record does not have.
            assertThat(TranCategoryRecord.LAYOUT.redefinitions()).isEmpty();

            // Contiguity, walked rather than assumed: each span begins exactly where the last ended.
            int cursor = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset()).as("span %s must begin at byte %d", span.name(), cursor)
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("dropping the trailing FILLER X(04) fails immediately, it does not shorten quietly")
        void droppingTheFillerFailsImmediately() {
            // The whole point of declaring FILLER as a first-class span. Omit it and the layout is 56
            // bytes against a declared 60, which is refused at construction - long before a 56-byte
            // write could shift every subsequent byte of the dataset.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH,
                            TranCategoryRecord.TRAN_TYPE_CD,
                            TranCategoryRecord.TRAN_CAT_CD,
                            TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .withMessageContaining("declares 56 byte(s)")
                    .withMessageContaining("4 byte(s) short")
                    .withMessageContaining("dropped trailing FILLER");
        }

        @Test
        @DisplayName("the FILLER is a declared span carrying no VALUE, positioned at byte 56")
        void theFillerIsADeclaredSpan() {
            assertThat(TranCategoryRecord.FILLER.offset()).isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER.length()).isEqualTo(4);
            assertThat(TranCategoryRecord.FILLER.hasInitialValue())
                    .as("CVTRA04Y's FILLER X(04) declares no VALUE clause")
                    .isFalse();
            assertThat(TranCategoryRecord.FILLER.redefinition()).isFalse();
        }

        @Test
        @DisplayName("verifyDeclaredGeometry passes on the real constants, and is safe to repeat")
        void verifyDeclaredGeometryPasses() {
            // The static initialiser has already run this, so calling it here records only that it
            // reads immutable constants and has no side effect - the passing side of the self-check.
            TranCategoryRecord.verifyDeclaredGeometry();
            TranCategoryRecord.verifyDeclaredGeometry();
            assertThat(TranCategoryRecord.sumOfDeclaredSpanWidths()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a key width that contradicts its two items is reported, with both widths")
        void aKeyWidthThatContradictsItsItemsIsReported() {
            // The failing side of the self-check, reachable only because verifyGeometry takes its
            // operands as parameters. Through verifyDeclaredGeometry these four branches are
            // unreachable, the constants they guard being correct.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, 7, 7,
                            DECLARED_RECORD_LENGTH))
                    .withMessageContaining("declared as 7 byte(s)")
                    .withMessageContaining("occupy 6")
                    .withMessageContaining("sub-span over exactly those two items");
        }

        @Test
        @DisplayName("a 17-byte key width is reported as the CVTRA01Y conflation it almost certainly is")
        void aSeventeenByteKeyIsReportedAsTheConflation() {
            // 13 + 4 = 17 makes the items agree with the group, so only the named trap distinguishes a
            // transcription from CVTRA01Y from an ordinary typo.
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(13, 4, NAMESAKE_KEY_LENGTH,
                            NAMESAKE_KEY_LENGTH, DECLARED_RECORD_LENGTH))
                    .withMessageContaining("BEWARE THE NAMESAKE")
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("TRANCAT-ACCT-ID 9(11)")
                    .withMessageContaining("the two records have been conflated");
        }

        @Test
        @DisplayName("a description offset that is not the key width is reported, naming 17")
        void aDescriptionOffsetThatIsNotTheKeyWidthIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, DECLARED_KEY_LENGTH,
                            NAMESAKE_KEY_LENGTH, DECLARED_RECORD_LENGTH))
                    .withMessageContaining("must begin at offset 6")
                    .withMessageContaining("declared at offset 17")
                    .withMessageContaining("An offset of 17 here");
        }

        @Test
        @DisplayName("a total of 56 rather than 60 is reported, and names the dropped FILLER")
        void aTotalThatIsNotSixtyIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, DECLARED_KEY_LENGTH,
                            DECLARED_KEY_LENGTH, DECLARED_RECORD_LENGTH - 4))
                    .withMessageContaining("RECLN = 60")
                    .withMessageContaining("sum to 56")
                    .withMessageContaining("FILLER 4");
        }
    }

    /**
     * Three of this copybook's names are reused elsewhere in the same domain with different meanings,
     * and in one case a different byte width. Each collision is real, each is invisible to a
     * total-width check, and none may be renamed, merged or aliased away: the parity differ compares
     * fields <em>by name</em>, so tidying a name would make a genuine difference invisible.
     *
     * <p>Every assertion here is on <strong>this</strong> record's own side of the collision. The
     * namesake types are described from their copybooks and cited by path, never imported or exercised
     * - each has its own test alongside this one, and asserting on it from here would duplicate that
     * test and couple the two files for no gain.
     */
    @Nested
    @DisplayName("Name collision defence - three names shared with three other copybooks")
    class NameCollisionDefence {

        @Test
        @DisplayName("Collision 1: this TRAN-CAT-KEY is SIX bytes, not CVTRA01Y's seventeen")
        void collisionOneTheKeyIsSixBytesNotSeventeen() {
            // app/cpy/CVTRA01Y.cpy declares a group with the IDENTICAL COBOL name TRAN-CAT-KEY:
            //     10 TRANCAT-ACCT-ID PIC 9(11)   <- an item this record has no notion of
            //     10 TRANCAT-TYPE-CD PIC X(02)
            //     10 TRANCAT-CD      PIC 9(04)
            // which is 11 + 2 + 4 = 17 bytes over the TCATBALF dataset. This copybook's key is
            // 2 + 4 = 6 bytes over TRANCATG. Same name; different width, members and dataset.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_NAME)
                    .as("the colliding name is carried verbatim, never disambiguated")
                    .isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .isEqualTo(DECLARED_KEY_LENGTH)
                    .isNotEqualTo(NAMESAKE_KEY_LENGTH);
            // The difference between the two keys is exactly the account id: 6 + 11 = 17.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH + NAMESAKE_ACCT_ID_LENGTH)
                    .isEqualTo(NAMESAKE_KEY_LENGTH);
        }

        @Test
        @DisplayName("Collision 1: the key is TWO items, and neither of them is an account id")
        void collisionOneTheKeyHasTwoItemsAndNoAccountId() {
            // CVTRA01Y's key has three items; this one has two. Counted from the layout rather than
            // asserted as a number: every storage span that lies wholly inside the key's 6 bytes.
            List<FieldSpan> keyItems = new ArrayList<>();
            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                if (span.endOffsetExclusive() <= TranCategoryRecord.TRAN_CAT_KEY_OFFSET
                        + TranCategoryRecord.TRAN_CAT_KEY_LENGTH) {
                    keyItems.add(span);
                }
            }
            assertThat(keyItems)
                    .as("CVTRA04Y's TRAN-CAT-KEY is TRAN-TYPE-CD plus TRAN-CAT-CD and nothing else")
                    .containsExactly(TranCategoryRecord.TRAN_TYPE_CD, TranCategoryRecord.TRAN_CAT_CD)
                    .hasSize(2);

            // And no span of this record - inside the key or outside it - is an account identifier or
            // carries CVTRA01Y's TRANCAT- member prefix. A record that grew either has been conflated.
            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                assertThat(span.name())
                        .as("no span of CVTRA04Y names an account identifier")
                        .doesNotContain("ACCT")
                        .doesNotStartWith("TRANCAT-");
            }
        }

        @Test
        @DisplayName("Collision 2: TRAN-TYPE-CD is at 0 and TRAN-CAT-CD at 2, not CVTRA05Y's 16 and 18")
        void collisionTwoTheItemNamesAreSharedWithCvtra05y() {
            // app/cpy/CVTRA05Y.cpy - the 350-byte TRAN-RECORD - declares items with these EXACT names:
            //     05 TRAN-ID      PIC X(16)   offset  0
            //     05 TRAN-TYPE-CD PIC X(02)   offset 16   <- same name, different offset
            //     05 TRAN-CAT-CD  PIC 9(04)   offset 18   <- same name, different offset
            // The clash is not hypothetical. app/cbl/CBTRN03C.cbl copies both copybooks, so the bare
            // names are genuinely ambiguous there and COBOL forces "OF TRAN-RECORD" at five sites:
            //   :189 MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE
            //   :191 MOVE TRAN-TYPE-CD OF TRAN-RECORD  (into FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY, :192)
            //   :193 MOVE TRAN-CAT-CD  OF TRAN-RECORD  (into FD-TRAN-CAT-CD  OF FD-TRAN-CAT-KEY, :194)
            //   :365 MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD
            //   :367 MOVE TRAN-CAT-CD  OF TRAN-RECORD TO TRAN-REPORT-CAT-CD
            // Those five qualifications are the evidence the collision is genuine; in Java the two
            // records are separate types, and they must STAY separate types.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name()).isEqualTo("TRAN-TYPE-CD");
            assertThat(TranCategoryRecord.TRAN_CAT_CD.name()).isEqualTo("TRAN-CAT-CD");

            assertThat(TranCategoryRecord.TRAN_TYPE_CD.offset())
                    .as("in CVTRA04Y this item opens the record; in CVTRA05Y it sits after TRAN-ID")
                    .isZero()
                    .isNotEqualTo(CVTRA05Y_TYPE_CD_OFFSET);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.offset())
                    .isEqualTo(2)
                    .isNotEqualTo(CVTRA05Y_CAT_CD_OFFSET);

            // The widths DO agree between the two copybooks - X(02) and 9(04) in both - which is
            // precisely why only the offsets can tell a transcription error apart from a correct one.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.length()).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.length()).isEqualTo(4);
        }

        @Test
        @DisplayName("Collision 3: this item is TRAN-TYPE-CD, whereas CVTRA03Y's is TRAN-TYPE")
        void collisionThreeCvtra03yDropsTheCdSuffix() {
            // app/cpy/CVTRA03Y.cpy - the transaction-type lookup - names its analogous 2-byte key item
            //     05 TRAN-TYPE PIC X(02)      offset 0
            // WITHOUT the -CD suffix, and its description TRAN-TYPE-DESC rather than
            // TRAN-CAT-TYPE-DESC. Both records are 60 bytes, both are lookups, both are read by
            // CBTRN03C in the same paragraph sequence, and both are keyed from the same 2-character
            // value. One character of the name is all that separates them, and it is never reconciled.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name())
                    .isEqualTo("TRAN-TYPE-CD")
                    .endsWith("-CD")
                    .isNotEqualTo("TRAN-TYPE");
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC.name())
                    .isEqualTo("TRAN-CAT-TYPE-DESC")
                    .isNotEqualTo("TRAN-TYPE-DESC");

            // CVTRA03Y's own layout is TRAN-TYPE X(02) + TRAN-TYPE-DESC X(50) + FILLER X(08) = 60: the
            // same total as this record but a different FILLER width, so even the two 60-byte records
            // disagree on where their trailing reserved span begins - 52 there, 56 here.
            assertThat(TranCategoryRecord.FILLER_OFFSET).isEqualTo(56).isNotEqualTo(52);
            assertThat(TranCategoryRecord.FILLER_LENGTH).isEqualTo(4).isNotEqualTo(8);
        }
    }

    /**
     * The 6-byte {@code TRAN-CAT-KEY} group is a <em>sub-span</em> over the record's first two
     * elementary items, not a third field. So the key image and the two items are two views of the
     * same six bytes, and the tests below round-trip that relationship in both directions: change an
     * item and the key follows, change the key and both items follow.
     */
    @Nested
    @DisplayName("The TRAN-CAT-KEY sub-span - one set of six bytes, two ways of reading it")
    class CompositeKey {

        @Test
        @DisplayName("the key occupies bytes 0 to 5 and stops before the description at byte 6")
        void theKeyOccupiesTheFirstSixBytes() {
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(DECLARED_KEY_LENGTH);
            // The first byte NOT in the key is byte 6, which is where TRAN-CAT-TYPE-DESC begins. The
            // description is therefore outside the key, and a keyed read never reads any of it.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_OFFSET + TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .as("byte 6 is the first byte beyond the key")
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET);
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC.offset())
                    .isGreaterThanOrEqualTo(TranCategoryRecord.TRAN_CAT_KEY_OFFSET
                            + TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
            assertThat(TranCategoryRecord.FILLER.offset())
                    .isGreaterThan(TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key image is the two item images concatenated, not a literal of its own")
        void theKeyImageIsTheTwoItemsConcatenated() {
            TranCategoryRecord record = decodedRow(1);

            // Built by concatenation from the two spans the copybook declares, so the expectation is
            // derived rather than hardcoded. It happens to read "010001" for row 1, and that is a
            // consequence of the two items, not an independent claim about the bytes.
            String composed = record.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)
                    + record.fieldImage(TranCategoryRecord.TRAN_CAT_CD);
            assertThat(composed).hasSize(DECLARED_KEY_LENGTH);
            assertThat(record.tranCatKeyImage()).isEqualTo(composed);
            assertThat(record.tranCatKeyBytes()).isEqualTo(composed.getBytes(ASCII));

            // The static key builder must agree with the record's own image, or a repository would look
            // up a key that no stored record can match.
            assertThat(TranCategoryRecord.tranCatKeyImage(record.tranTypeCd(), record.tranCatCd(),
                    ASCII)).isEqualTo(composed);
            assertThat(TranCategoryRecord.tranCatKeyBytes(record.tranTypeCd(), record.tranCatCd(),
                    ASCII)).isEqualTo(composed.getBytes(ASCII));
        }

        @Test
        @DisplayName("changing TRAN-CAT-CD changes the key image, because they are the same bytes")
        void changingAnItemChangesTheKeyImage() {
            // Direction one of the round trip: item -> key. The category code is rewritten in place, in
            // the record area, and the key image reflects it because the key is a view over those bytes
            // rather than a separately stored copy.
            TranCategoryRecord original = decodedRow(1);
            assertThat(original.tranCatKeyImage()).isEqualTo("010001");

            FixedWidthRecord area = original.toRecordArea();
            area.writeSpan(TranCategoryRecord.TRAN_CAT_CD, "0009");
            TranCategoryRecord mutated = TranCategoryRecord.decode(area.toByteArray(), ASCII);

            assertThat(mutated.tranCatCd()).isEqualTo(9);
            assertThat(mutated.tranCatKeyImage()).isEqualTo("010009")
                    .isNotEqualTo(original.tranCatKeyImage());
            // The type code half of the key is untouched, and so is everything past byte 6.
            assertThat(mutated.tranTypeCd()).isEqualTo(original.tranTypeCd());
            assertThat(mutated.tranCatTypeDesc()).isEqualTo(original.tranCatTypeDesc());
            assertThat(mutated.filler()).isEqualTo(original.filler());
            // The original is immutable: toRecordArea() handed back a copy, not its own bytes.
            assertThat(original.tranCatKeyImage()).isEqualTo("010001");
            assertThat(original.tranCatCd()).isEqualTo(1);
        }

        @Test
        @DisplayName("writing the six key bytes rewrites both items, and nothing beyond byte 6")
        void writingTheKeyRewritesBothItems() {
            // Direction two of the round trip: key -> items. Six bytes are written at the key's own
            // offset, addressed absolutely, and both elementary items follow from them.
            TranCategoryRecord original = decodedRow(1);

            FixedWidthRecord area = original.toRecordArea();
            area.writeString(TranCategoryRecord.TRAN_CAT_KEY_OFFSET,
                    TranCategoryRecord.TRAN_CAT_KEY_LENGTH, "070001");
            TranCategoryRecord rekeyed = TranCategoryRecord.decode(area.toByteArray(), ASCII);

            assertThat(rekeyed.tranTypeCd()).isEqualTo("07");
            assertThat(rekeyed.tranCatCd()).isEqualTo(1);
            assertThat(rekeyed.tranCatKeyImage()).isEqualTo("070001");
            assertThat(rekeyed.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo("07");
            assertThat(rekeyed.fieldImage(TranCategoryRecord.TRAN_CAT_CD)).isEqualTo("0001");
            // Proof the key really does end at byte 6: the description and the FILLER are untouched.
            assertThat(rekeyed.tranCatTypeDesc()).isEqualTo(original.tranCatTypeDesc());
            assertThat(rekeyed.filler()).isEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("every fixture key is six printable bytes, as CBTRN03C:507 DISPLAYs it")
        void everyKeyIsSixPrintableBytes() {
            // app/cbl/CBTRN03C.cbl:507 renders the key on the not-found path:
            //   DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY
            // so the six bytes appear verbatim in the SYSOUT a parity fingerprint compares. A
            // non-printable byte there would corrupt that line, so every one is checked.
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                byte[] key = decodedRow(rowNumber).tranCatKeyBytes();
                assertThat(key).hasSize(DECLARED_KEY_LENGTH);
                for (byte value : key) {
                    assertThat(value)
                            .as("key byte of row %d must be printable ASCII", rowNumber)
                            .isBetween((byte) 0x20, (byte) 0x7E);
                }
            }
        }

        @Test
        @DisplayName("every key in the fixture is distinct, which a 6-byte KSDS key requires")
        void everyKeyIsDistinct() {
            List<String> keys = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                keys.add(decodedRow(rowNumber).tranCatKeyImage());
            }
            assertThat(keys).hasSize(18).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("The shipped TRANCATG fixture - all 18 rows, decoded at the copybook's offsets")
    class ShippedFixture {

        @Test
        @DisplayName("all 18 embedded rows are exactly 60 characters, so none needs widening")
        void everyEmbeddedRowIsSixtyCharacters() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(18);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(DECLARED_RECORD_LENGTH));
            // Unlike cardxref.txt, which is 36 bytes against CVACT03Y's declared 50, this fixture
            // matches its copybook exactly - so no row is padded before decoding and none may be.
            assertThat(rows).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("each embedded row segments where CVTRA04Y says it does, at 0, 2, 6 and 56")
        void theEmbeddedRowsSegmentAtTheCopybookOffsets() {
            // Asserted on the raw literals by absolute offset, before any decode is involved, so this is
            // a statement about the dataset's bytes rather than about the model. No regex and no split:
            // a fixed-width field is located by its offset and its width, never by a pattern.
            for (String row : fixtureRows()) {
                String typeCd = row.substring(0, 2);
                String catCd = row.substring(2, 6);
                String description = row.substring(6, 56);
                String filler = row.substring(56, 60);

                // The whole 6-byte key is zoned DISPLAY in this dataset - TRAN-TYPE-CD is PIC X(02) and
                // so need not be numeric, but every value shipped is - checked character by character at
                // its own position rather than by a pattern over the group.
                for (int column = 0; column < DECLARED_KEY_LENGTH; column++) {
                    assertThat(row.charAt(column))
                            .as("byte %d of row's key must be an ASCII digit", column)
                            .isBetween('0', '9');
                }
                assertThat(typeCd).hasSize(TranCategoryRecord.TRAN_TYPE_CD_LENGTH);
                assertThat(catCd).hasSize(TranCategoryRecord.TRAN_CAT_CD_LENGTH);
                assertThat(description).hasSize(DECLARED_DESC_LENGTH);
                assertThat(filler).isEqualTo(FIXTURE_FILLER);
                // The description is stored left-justified and space-padded, per PIC X.
                assertThat(description).doesNotStartWith(" ");
                assertThat(description.charAt(DECLARED_DESC_LENGTH - 1))
                        .as("byte 56 of every row is padding, no description reaches the full 50")
                        .isEqualTo(' ');
                assertThat(typeCd + catCd + description + filler).isEqualTo(row);
            }
        }

        @ParameterizedTest(name = "row {0}: TRAN-TYPE-CD {1}, TRAN-CAT-CD {2}, {3}")
        @DisplayName("every one of the 18 rows decodes field by field, description padded to 50")
        @CsvSource(delimiter = '|', value = {
            " 1 | 01 | 1 | Regular Sales Draft",
            " 2 | 01 | 2 | Regular Cash Advance",
            " 3 | 01 | 3 | Convenience Check Debit",
            " 4 | 01 | 4 | ATM Cash Advance",
            " 5 | 01 | 5 | Interest Amount",
            " 6 | 02 | 1 | Cash payment",
            " 7 | 02 | 2 | Electronic payment",
            " 8 | 02 | 3 | Check payment",
            " 9 | 03 | 1 | Credit to Account",
            "10 | 03 | 2 | Credit to Purchase balance",
            "11 | 03 | 3 | Credit to Cash balance",
            "12 | 04 | 1 | Zero dollar authorization",
            "13 | 04 | 2 | Online purchase authorization",
            "14 | 04 | 3 | Travel booking authorization",
            "15 | 05 | 1 | Refund credit",
            "16 | 06 | 1 | Fraud reversal",
            "17 | 06 | 2 | Non-fraud reversal",
            "18 | 07 | 1 | Sales draft credit adjustment",
        })
        void everyRowDecodesFieldByField(int rowNumber, String typeCd, int catCd, String description) {
            TranCategoryRecord record = decodedRow(rowNumber);

            // TRAN-TYPE-CD is PIC X(02), so it is a String and "01" never collapses to 1: the leading
            // zero is a stored character and part of the key.
            assertThat(record.tranTypeCd()).isEqualTo(typeCd).hasSize(2);
            // TRAN-CAT-CD is PIC 9(04) - scale-free - so it is an int, and 0001 reads back as 1.
            assertThat(record.tranCatCd()).isEqualTo(catCd);
            // TRAN-CAT-TYPE-DESC is PIC X(50) and is NEVER trimmed: the padding is stored data.
            assertThat(record.tranCatTypeDesc())
                    .isEqualTo(padded(description, DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH)
                    .startsWith(description);
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);

            // The stored images, read by descriptor at their absolute offsets.
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo(typeCd);
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_CD))
                    .isEqualTo(zoned(catCd, TranCategoryRecord.TRAN_CAT_CD_LENGTH));
            assertThat(record.tranCatKeyImage())
                    .isEqualTo(typeCd + zoned(catCd, TranCategoryRecord.TRAN_CAT_CD_LENGTH))
                    .hasSize(DECLARED_KEY_LENGTH);
            // And the whole 60 bytes still round-trip to the row they came from.
            assertThat(record.toImage()).isEqualTo(fixtureRow(rowNumber));
            assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("row 1's description keeps all 31 of its trailing spaces on read")
        void rowOneDescriptionIsUntrimmed() {
            // Spelled out for one row so the untrimmed contract is unmistakable: 19 characters of text
            // and 31 of padding, which is 50. Trimming here would silently discard 31 bytes that the
            // parity differ compares field by field.
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.tranCatTypeDesc())
                    .isEqualTo("Regular Sales Draft" + " ".repeat(31))
                    .hasSize(DECLARED_DESC_LENGTH);
            assertThat("Regular Sales Draft".length() + 31).isEqualTo(DECLARED_DESC_LENGTH);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("named spans read back at their offsets, and every array handed out is a copy")
        void namedSpansReadBackAndArraysAreCopies() {
            TranCategoryRecord record = decodedRow(1);

            assertThat(record.fieldBytes(TranCategoryRecord.TRAN_TYPE_CD))
                    .isEqualTo("01".getBytes(ASCII));
            assertThat(record.fieldBytes(TranCategoryRecord.FILLER))
                    .isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .isEqualTo(padded("Regular Sales Draft", DECLARED_DESC_LENGTH));

            // Mutating anything handed back must not reach the record: it is immutable and shareable.
            byte[] whole = record.toByteArray();
            whole[0] = (byte) '9';
            assertThat(record.tranTypeCd()).isEqualTo("01");
            byte[] filler = record.fillerBytes();
            filler[0] = (byte) '9';
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            byte[] keyBytes = record.tranCatKeyBytes();
            keyBytes[0] = (byte) '9';
            assertThat(record.tranCatKeyImage()).isEqualTo("010001");
            FixedWidthRecord area = record.toRecordArea();
            area.writeSpan(TranCategoryRecord.TRAN_TYPE_CD, "99");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(area.readSpan(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo("99");
            assertThat(area.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the two widest descriptions in the fixture are exactly 29 characters")
        void theTwoWidestDescriptionsAreExactlyTwentyNine() {
            // This is the measured fact that makes the synthetic over-length case below mandatory: the
            // fixture reaches the report receiver's width and never exceeds it, so real data alone
            // cannot prove that the truncating MOVE discards anything.
            int widest = 0;
            List<String> widestDescriptions = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                String stored = decodedRow(rowNumber).tranCatTypeDesc();
                int textLength = DECLARED_DESC_LENGTH;
                while (textLength > 0 && stored.charAt(textLength - 1) == ' ') {
                    textLength--;
                }
                assertThat(textLength).isLessThanOrEqualTo(REPORT_CAT_DESC_LENGTH);
                if (textLength > widest) {
                    widest = textLength;
                    widestDescriptions.clear();
                }
                if (textLength == widest) {
                    widestDescriptions.add(stored.substring(0, textLength));
                }
            }
            assertThat(widest).isEqualTo(REPORT_CAT_DESC_LENGTH);
            assertThat(widestDescriptions).containsExactly(
                    "Online purchase authorization",
                    "Sales draft credit adjustment");
        }
    }

    /**
     * COBOL {@code MOVE} adjusts a width in a direction that depends on the receiver's category, and
     * getting that direction wrong is the classic silent defect. An alphanumeric receiver is filled
     * from the left, so a short value is space-padded on the right and an over-long one loses its
     * <strong>trailing</strong> characters. A numeric receiver is aligned on its implied decimal point,
     * so a short value is zero-filled on the left and an over-long one loses its <strong>leading</strong>
     * digits. Both directions of both rules are driven here.
     */
    @Nested
    @DisplayName("COBOL MOVE semantics - PIC X truncates right, PIC 9 truncates left")
    class MoveSemantics {

        @Test
        @DisplayName("X(50) into X(29): an exactly-29 description survives the report move intact")
        void anExactlyTwentyNineDescriptionSurvivesIntact() {
            // app/cbl/CBTRN03C.cbl:368, inside 1120-WRITE-DETAIL:
            //   MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC
            // with the receiver at app/cpy/CVTRA07Y.cpy:26, PIC X(29). Rows 13 and 18 are the fixture's
            // two boundary cases: their descriptions are exactly 29 characters, so they fill the
            // receiver completely and lose nothing at all.
            assertThat(movedIntoReportField(decodedRow(13).tranCatTypeDesc()))
                    .isEqualTo("Online purchase authorization")
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .doesNotEndWith(" ");
            assertThat(movedIntoReportField(decodedRow(18).tranCatTypeDesc()))
                    .isEqualTo("Sales draft credit adjustment")
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("X(50) into X(29): a short description arrives padded, losing no character")
        void aShortDescriptionArrivesPadded() {
            // Row 1's description is 19 characters, so the move keeps all 19 and the receiver's
            // remaining 10 bytes are the padding the 50-byte field already carried.
            assertThat(movedIntoReportField(decodedRow(1).tranCatTypeDesc()))
                    .isEqualTo(padded("Regular Sales Draft", REPORT_CAT_DESC_LENGTH))
                    .hasSize(REPORT_CAT_DESC_LENGTH);
            assertThat(movedIntoReportField(decodedRow(6).tranCatTypeDesc()))
                    .isEqualTo(padded("Cash payment", REPORT_CAT_DESC_LENGTH))
                    .hasSize(REPORT_CAT_DESC_LENGTH);
        }

        @Test
        @DisplayName("X(50) into X(29): a 45-character description loses its trailing 16 characters")
        void anOverLongDescriptionLosesItsTrailingCharacters() {
            // The case no fixture row can supply, and the reason this test exists. TRAN-CAT-TYPE-DESC is
            // 50 bytes wide, so any value from 30 to 50 characters is representable in it and would be
            // truncated by the report move - but the widest description actually shipped is exactly 29.
            // Without a synthetic value the discarding half of the MOVE is never exercised.
            assertThat(SYNTHETIC_LONG_DESC).hasSize(45);
            assertThat(SYNTHETIC_SURVIVING_29).hasSize(REPORT_CAT_DESC_LENGTH);
            assertThat(SYNTHETIC_DISCARDED_TAIL).hasSize(16);
            // 29 + 16 = 45: every character is accounted for, either kept or discarded.
            assertThat(SYNTHETIC_SURVIVING_29 + SYNTHETIC_DISCARDED_TAIL).isEqualTo(SYNTHETIC_LONG_DESC);

            TranCategoryRecord built = TranCategoryRecord.of("09", 42, SYNTHETIC_LONG_DESC, ASCII);
            // The model itself keeps all 50 declared bytes - it never pre-truncates.
            assertThat(built.tranCatTypeDesc())
                    .isEqualTo(padded(SYNTHETIC_LONG_DESC, DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH);

            // The receiver's width is applied at the point of use, and keeps the LEADING 29.
            String moved = movedIntoReportField(built.tranCatTypeDesc());
            assertThat(moved)
                    .isEqualTo(SYNTHETIC_SURVIVING_29)
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .isNotEqualTo(SYNTHETIC_LONG_DESC);
            // The tail was really there and really went: present in the stored 50 bytes, absent from the
            // 29 that survive. Both halves asserted, so this is a discard rather than an absence.
            assertThat(built.tranCatTypeDesc()).contains(SYNTHETIC_DISCARDED_TAIL);
            assertThat(moved).doesNotContain(SYNTHETIC_DISCARDED_TAIL);
            // Truncation on the right, never on the left: the value's opening characters survive.
            assertThat(SYNTHETIC_LONG_DESC).startsWith(moved);
        }

        @Test
        @DisplayName("X(50) into X(29): 30 characters lose exactly one, which fixes the boundary at 29")
        void thirtyCharactersLoseExactlyOne() {
            // One character past the receiver. The tightest available proof that the cut falls between
            // the 29th and the 30th character and not a byte either side of it.
            assertThat(SYNTHETIC_THIRTY_DESC).hasSize(REPORT_CAT_DESC_LENGTH + 1);

            TranCategoryRecord built = TranCategoryRecord.of("09", 43, SYNTHETIC_THIRTY_DESC, ASCII);
            String moved = movedIntoReportField(built.tranCatTypeDesc());

            assertThat(moved).hasSize(REPORT_CAT_DESC_LENGTH)
                    .isEqualTo(SYNTHETIC_THIRTY_DESC.substring(0, REPORT_CAT_DESC_LENGTH))
                    .isEqualTo(SYNTHETIC_SURVIVING_29);
            assertThat(SYNTHETIC_THIRTY_DESC).isEqualTo(moved + "s");
        }

        @Test
        @DisplayName("writing X(50): a short description is space-padded on the right to all 50 bytes")
        void aShortDescriptionIsPaddedToFifty() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Refund credit", ASCII);

            assertThat(built.tranCatTypeDesc())
                    .isEqualTo("Refund credit" + " ".repeat(37))
                    .hasSize(DECLARED_DESC_LENGTH);
            // Read back at its absolute offset, so this is a claim about bytes 6 to 55 and not about a
            // decoded convenience value.
            assertThat(built.fieldImage(TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .isEqualTo(padded("Refund credit", DECLARED_DESC_LENGTH));
            assertThat(built.toImage().substring(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET,
                    TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(padded("Refund credit", DECLARED_DESC_LENGTH));
            // An empty description blanks the whole span rather than shortening the record.
            assertThat(TranCategoryRecord.of("01", 1, "", ASCII).tranCatTypeDesc())
                    .isEqualTo(" ".repeat(DECLARED_DESC_LENGTH));
        }

        @Test
        @DisplayName("writing X(50): a 51-character description is truncated on the right to 50")
        void anOverWideDescriptionIsTruncatedToFifty() {
            String tooLong = "D".repeat(DECLARED_DESC_LENGTH) + "X";
            assertThat(tooLong).hasSize(51);

            TranCategoryRecord built = TranCategoryRecord.of("01", 1, tooLong, ASCII);
            assertThat(built.tranCatTypeDesc())
                    .isEqualTo("D".repeat(DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH)
                    .doesNotContain("X");
            // Over-wide does not throw. ON SIZE ERROR appears nowhere in the 28 programs, so COBOL
            // discards the excess silently, and parity requires the same silence.
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("writing X(02): a one-character type code is space-padded on the right to two")
        void aShortTypeCodeIsPaddedToTwo() {
            assertThat(TranCategoryRecord.of("1", 1, "x", ASCII).tranTypeCd()).isEqualTo("1 ");
            assertThat(TranCategoryRecord.tranCatKeyImage("1", 1, ASCII)).isEqualTo("1 0001");
            // An empty type code blanks both bytes; it never shortens the key below six.
            assertThat(TranCategoryRecord.tranCatKeyImage("", 0, ASCII))
                    .isEqualTo("  0000")
                    .hasSize(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("writing X(02): a three-character type code is truncated on the right to two")
        void anOverWideTypeCodeIsTruncatedToTwo() {
            // PIC X fills from the left, so "012" keeps "01" and drops the '2'. Keeping the trailing two
            // characters instead - "12" - would be the mirror-image defect, and would key on a category
            // that exists nowhere in the dataset.
            assertThat(TranCategoryRecord.of("012", 1, "x", ASCII).tranTypeCd())
                    .isEqualTo("01")
                    .isNotEqualTo("12");
            assertThat(TranCategoryRecord.tranCatKeyImage("012", 1, ASCII)).isEqualTo("010001");
            assertThat(TranCategoryRecord.tranCatKeyBytes("012", 1, ASCII))
                    .isEqualTo("010001".getBytes(ASCII));
        }

        @Test
        @DisplayName("writing 9(04): a one-digit category code is zero-filled on the LEFT to four")
        void aShortCategoryCodeIsZeroFilledOnTheLeft() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "x", ASCII);
            assertThat(built.fieldImage(TranCategoryRecord.TRAN_CAT_CD)).isEqualTo("0001");
            assertThat(built.tranCatKeyImage()).isEqualTo("010001");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 0, ASCII)).isEqualTo("010000");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 42, ASCII)).isEqualTo("010042");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 9999, ASCII)).isEqualTo("019999");
        }

        @Test
        @DisplayName("writing 9(04): 12345 keeps its LOW-order four digits, so 2345 and never 1234")
        void anOverWideCategoryCodeKeepsItsLowOrderDigits() {
            // A numeric receiver aligns on its implied decimal point, so the surviving digits are the
            // low-order ones. Keeping 1234 instead is the classic defect this asserts against.
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 12345, ASCII))
                    .isEqualTo("012345")
                    .isNotEqualTo("011234");
            assertThat(TranCategoryRecord.of("01", 12345, "x", ASCII))
                    .extracting(TranCategoryRecord::tranCatCd)
                    .isEqualTo(2345);
            assertThat(TranCategoryRecord.of("01", 10000, "x", ASCII).tranCatCd())
                    .as("10000 loses its leading 1 and reads back as 0")
                    .isZero();
        }

        @Test
        @DisplayName("a negative category code has no PIC 9(04) representation and is rejected")
        void aNegativeCategoryCodeIsRejected() {
            // Storing the magnitude would invent a value the COBOL could never hold, so every entry
            // point that accepts a category code refuses it rather than normalising it.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", -1, "x", ASCII))
                    .withMessageContaining("PIC 9(04)")
                    .withMessageContaining("unsigned picture with no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", -1, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyBytes("01", -1, ASCII));
        }
    }

    /**
     * The {@code FILLER} span carries different content on the two paths, and both are correct.
     *
     * <p>A record <em>built</em> from field values space-fills its {@code FILLER}, because that is the
     * category default for an alphanumeric reserved span with no {@code VALUE} clause - which is what
     * {@code CVTRA04Y} declares. A record <em>decoded</em> from {@code app/data/ASCII/trancatg.txt}
     * finds four ASCII zeros there instead, because that is what the shipped dataset actually stores,
     * and it must re-emit them verbatim so a row round-trips byte for byte.
     *
     * <p>The two obligations are complementary rather than contradictory: the first is about what an
     * uninitialised reserved span contains, the second about not overwriting what was read. They can
     * only be confused by a codec that normalises {@code FILLER} on re-encode, which is precisely the
     * defect these two tests separate.
     */
    @Nested
    @DisplayName("The FILLER read and write asymmetry - spaces when built, zeros when read")
    class FillerAsymmetry {

        @Test
        @DisplayName("a freshly built record space-fills FILLER X(04) at byte 56")
        void aFreshlyBuiltRecordSpaceFillsTheFiller() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThat(built.filler()).isEqualTo(BLANK_FILLER).hasSize(4);
            assertThat(built.fillerBytes()).isEqualTo(BLANK_FILLER.getBytes(ASCII));
            assertThat(built.fieldImage(TranCategoryRecord.FILLER)).isEqualTo(BLANK_FILLER);
            // Read straight out of the 60-character image at the copybook's own offset, bytes 57-60.
            assertThat(built.toImage().substring(TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(BLANK_FILLER);
            // Present and 4 bytes wide, so the built record is a full 60 bytes and not 56.
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(built.toImage())
                    .isEqualTo("01" + "0001" + padded("Regular Sales Draft", DECLARED_DESC_LENGTH)
                            + BLANK_FILLER)
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a decoded row keeps its four ASCII zeros and re-encodes byte for byte")
        void aDecodedRowKeepsItsZeroFiller() {
            // Every one of the 18 rows, not a sample: the codec must not normalise FILLER on any of
            // them, and a byte-identical round trip is the only way to prove it did not.
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                String row = fixtureRow(rowNumber);
                TranCategoryRecord record = decodedRow(rowNumber);

                assertThat(record.filler())
                        .as("row %d stores ASCII zeros in its FILLER, not spaces", rowNumber)
                        .isEqualTo(FIXTURE_FILLER)
                        .isNotEqualTo(BLANK_FILLER);
                assertThat(record.toByteArray()).isEqualTo(row.getBytes(ASCII));
                assertThat(record.toImage()).isEqualTo(row);
                assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the two paths differ only in the FILLER, which is exactly the point")
        void theTwoPathsDifferOnlyInTheFiller() {
            // Same key, same description, different FILLER. Everything a caller reads through a named
            // accessor agrees; only bytes 57-60 disagree, and the record images therefore differ.
            TranCategoryRecord fromDataset = decodedRow(1);
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThat(built.tranCatKeyImage()).isEqualTo(fromDataset.tranCatKeyImage());
            assertThat(built.tranTypeCd()).isEqualTo(fromDataset.tranTypeCd());
            assertThat(built.tranCatCd()).isEqualTo(fromDataset.tranCatCd());
            assertThat(built.tranCatTypeDesc()).isEqualTo(fromDataset.tranCatTypeDesc());
            assertThat(built.filler()).isNotEqualTo(fromDataset.filler());
            assertThat(built.toImage()).isNotEqualTo(fromDataset.toImage());
            // The difference is confined to the FILLER span: the first 56 bytes are identical.
            assertThat(built.toImage().substring(0, TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(fromDataset.toImage().substring(0, TranCategoryRecord.FILLER_OFFSET));
        }
    }

    @Nested
    @DisplayName("Decoding guards - a malformed row is reported, never quietly accepted")
    class DecodingGuards {

        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aWrongWidthRowIsRejected() {
            // Tolerating a short row would let every field offset drift by the shortfall. A row that is
            // genuinely short because its source omits a trailing span must be widened deliberately,
            // before it reaches the decoder - which is what cardxref.txt needs and this fixture does not.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[59], ASCII))
                    .withMessageContaining("59 byte(s)")
                    .withMessageContaining("exactly 60");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[61], ASCII))
                    .withMessageContaining("61 byte(s)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[0], ASCII));
        }

        @Test
        @DisplayName("a TRAN-CAT-CD that does not hold four digits is rejected, not silently zeroed")
        void aNonNumericCategoryCodeIsRejected() {
            // PIC 9(04) is zoned DISPLAY, one digit per byte. Letters there are a corrupt row, and
            // reading them as zero would fabricate a key that matches a real record.
            String corrupt = "01" + "ABCD"
                    + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + FIXTURE_FILLER;
            assertThat(corrupt).hasSize(DECLARED_RECORD_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(corrupt.getBytes(ASCII), ASCII));

            String blankKey = "  " + "    "
                    + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + FIXTURE_FILLER;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(blankKey.getBytes(ASCII), ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that requires a value")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(null, ASCII))
                    .withMessageContaining("record bytes are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[DECLARED_RECORD_LENGTH], null))
                    .withMessageContaining("never a platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of(null, 1, "x", ASCII))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, null, ASCII))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, "x", null))
                    .withMessageContaining("never derived from the platform");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage(null, 1, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", 1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> decodedRow(1).fieldImage(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> decodedRow(1).fieldBytes(null))
                    .withMessageContaining("field descriptor is required");
        }

        @Test
        @DisplayName("the code page is honoured: the same characters, different bytes, same values")
        void theCodePageIsHonoured() {
            // Both pages are named. Neither is a platform default, and no test here depends on the
            // JVM's file.encoding.
            TranCategoryRecord ascii = decodedRow(1);
            TranCategoryRecord ebcdic = TranCategoryRecord.decode(fixtureRow(1).getBytes(EBCDIC),
                    EBCDIC);

            assertThat(ebcdic.charset()).isEqualTo(EBCDIC);
            assertThat(ebcdic.toByteArray()).isNotEqualTo(ascii.toByteArray());
            assertThat(ebcdic.tranTypeCd()).isEqualTo(ascii.tranTypeCd());
            assertThat(ebcdic.tranCatCd()).isEqualTo(ascii.tranCatCd());
            assertThat(ebcdic.tranCatTypeDesc()).isEqualTo(ascii.tranCatTypeDesc());
            assertThat(ebcdic.tranCatKeyImage()).isEqualTo(ascii.tranCatKeyImage());
            assertThat(ebcdic.toImage()).isEqualTo(ascii.toImage());
        }

        @Test
        @DisplayName("ASCII bytes read as EBCDIC are not digits, and the row is refused")
        void aRowThatWillNotDecodeIsRejected() {
            // The counterpart of the case above. This type decodes eagerly, so reading the wrong page
            // fails at construction rather than producing a record whose fields are quietly wrong.
            byte[] asciiRow = fixtureRow(1).getBytes(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(asciiRow, EBCDIC))
                    .withMessageContaining("is not a digit");
        }
    }

    @Nested
    @DisplayName("Value semantics and diagnostic rendering")
    class ValueSemanticsAndRendering {

        @Test
        @DisplayName("two records over the same bytes and the same code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            byte[] row = fixtureRow(1).getBytes(ASCII);
            assertThat(TranCategoryRecord.decode(row, ASCII))
                    .isEqualTo(TranCategoryRecord.decode(row, ASCII))
                    .hasSameHashCodeAs(TranCategoryRecord.decode(row, ASCII));
        }

        @Test
        @DisplayName("a record equals itself without comparing a single byte")
        void aRecordEqualsItself() {
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCategoryRecord is ever equal to one")
        void anotherTypeIsNeverEqual() {
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.equals(null)).isFalse();
            // A bare key image is not a record, however identical the characters look.
            assertThat(record.equals("010001")).isFalse();
            assertThat(record.equals(record.toImage())).isFalse();
            assertThat(record.equals(Integer.valueOf(1))).isFalse();
        }

        @Test
        @DisplayName("two rows with different keys are unequal, and hash differently")
        void differentRowsAreUnequal() {
            TranCategoryRecord first = decodedRow(1);
            TranCategoryRecord second = decodedRow(2);
            assertThat(first).isNotEqualTo(second);
            assertThat(first.hashCode()).isNotEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("identical bytes under a different code page are NOT the same record")
        void aDifferentCodePageIsNotEqual() {
            // This isolates the charset half of the comparison: the two records hold byte-for-byte
            // identical images, so only the declared code page can distinguish them - and it must,
            // because a record's meaning is its bytes plus the page they are read under.
            //
            // ISO-8859-1 for the second reading rather than IBM037, deliberately. This type decodes
            // eagerly, so the same bytes have to stay valid under both pages for the comparison to be
            // about the charset alone; ISO-8859-1 agrees with US-ASCII on every digit and letter in the
            // fixture, whereas reading ASCII bytes as EBCDIC fails the PIC 9 digit check first and would
            // prove nothing about equality. Both pages are named; neither is a platform default.
            byte[] row = fixtureRow(1).getBytes(ASCII);
            TranCategoryRecord underAscii = TranCategoryRecord.decode(row, ASCII);
            TranCategoryRecord underLatin1 = TranCategoryRecord.decode(row,
                    StandardCharsets.ISO_8859_1);

            assertThat(underAscii.toByteArray()).isEqualTo(underLatin1.toByteArray());
            assertThat(underAscii.tranCatKeyImage()).isEqualTo(underLatin1.tranCatKeyImage());
            assertThat(underAscii).isNotEqualTo(underLatin1);
            assertThat(underLatin1).isNotEqualTo(underAscii);
        }

        @Test
        @DisplayName("a difference confined to the FILLER is never mistaken for equality")
        void aFillerDifferenceIsNotEqual() {
            // Byte comparison rather than field comparison is what makes this work: two records that
            // agree on every decoded value but differ in bytes 57-60 are correctly unequal, which is
            // what a byte-for-byte parity comparison requires.
            byte[] fromDataset = fixtureRow(1).getBytes(ASCII);
            byte[] blanked = fixtureRow(1).getBytes(ASCII);
            for (int index = TranCategoryRecord.FILLER_OFFSET;
                    index < TranCategoryRecord.RECORD_LENGTH; index++) {
                blanked[index] = (byte) ' ';
            }
            TranCategoryRecord stored = TranCategoryRecord.decode(fromDataset, ASCII);
            TranCategoryRecord reblanked = TranCategoryRecord.decode(blanked, ASCII);

            assertThat(stored.tranCatKeyImage()).isEqualTo(reblanked.tranCatKeyImage());
            assertThat(stored.tranCatTypeDesc()).isEqualTo(reblanked.tranCatTypeDesc());
            assertThat(stored.tranCatCd()).isEqualTo(reblanked.tranCatCd());
            assertThat(stored).isNotEqualTo(reblanked);
        }

        @Test
        @DisplayName("the rendering names every COBOL item and keeps the padding visible")
        void theRenderingNamesEveryItem() {
            // TRANCATG carries no personal data - a transaction type and a category description
            // identify a kind of activity, not a person - so this rendering withholds nothing, and the
            // padding stays visible because a wrong width is a real defect worth seeing.
            String rendered = decodedRow(1).toString();
            assertThat(rendered)
                    .startsWith("TranCategoryRecord[")
                    .contains(TranCategoryRecord.TRAN_CAT_KEY_NAME + "='010001'")
                    .contains("TRAN-TYPE-CD='01'")
                    .contains("TRAN-CAT-CD=1")
                    .contains("TRAN-CAT-TYPE-DESC='"
                            + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + "'")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("charset=US-ASCII")
                    .endsWith("]");
            // Deterministic: no clock, no identity hash, no locale-dependent formatting.
            assertThat(rendered).isEqualTo(decodedRow(1).toString()).doesNotContain("@");
        }

        @Test
        @DisplayName("both whole-record accessors report exactly 60, in bytes and in characters")
        void bothWholeRecordAccessorsReportSixty() {
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                TranCategoryRecord record = decodedRow(rowNumber);
                assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
                assertThat(record.toImage()).hasSize(DECLARED_RECORD_LENGTH);
                assertThat(record.toRecordArea().recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            }
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "x", ASCII);
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(built.toImage()).hasSize(DECLARED_RECORD_LENGTH);
        }
    }

    /**
     * Properties of the type itself rather than of any one instance, asserted reflectively because they
     * are claims about what the class may and may not declare.
     */
    @Nested
    @DisplayName("Structural guards - what this type may never declare")
    class StructuralGuards {

        /** The arbitrary-precision decimal type, named as a string so this file need not import it. */
        private static final String BIG_DECIMAL = "java.math.BigDecimal";

        /**
         * The fields this class declares, with the coverage agent's contribution removed.
         *
         * <p>JaCoCo adds a non-final {@code private static transient synthetic boolean[] $jacocoData}
         * probe array to every instrumented class, so a bare walk over {@code getDeclaredFields()}
         * would fail the no-mutable-static-state guard under {@code verify} while passing under a plain
         * {@code test} run.
         *
         * @return the declared fields, instrumentation artefacts excluded
         */
        private static List<Field> declaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : TranCategoryRecord.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields to vouch for").isNotEmpty();
            return fields;
        }

        /**
         * The methods this class declares, with the coverage agent's contribution removed.
         *
         * @return the declared methods, instrumentation artefacts excluded
         */
        private static List<Method> declaredMethods() {
            List<Method> methods = new ArrayList<>();
            for (Method method : TranCategoryRecord.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.getName().startsWith("$")) {
                    methods.add(method);
                }
            }
            assertThat(methods).as("the guard must find real methods to vouch for").isNotEmpty();
            return methods;
        }

        @Test
        @DisplayName("no field, parameter or return type is double or float")
        void noBinaryFloatingPointAnywhere() {
            // A binary floating-point primitive cannot represent a decimal fraction exactly, so it is
            // barred module-wide for anything derived from a PICTURE clause. This record has no decimal
            // field at all, which makes the guard trivially satisfiable and worth stating anyway: a
            // later edit that introduced one would be caught here.
            for (Field field : declaredFields()) {
                assertThat(field.getType())
                        .as("field %s must not be a binary floating-point type", field.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getReturnType())
                        .as("method %s must not return a binary floating-point type", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("no accessor returns an arbitrary-precision decimal, because CVTRA04Y has none")
        void noArbitraryPrecisionDecimalAccessor() {
            // CVTRA04Y declares no signed decimal field: its items are X(02), 9(04), X(50) and a FILLER.
            // So the fixed-point decimal helper is deliberately absent from the type under test, and an
            // arbitrary-precision decimal accessor here would not be a feature but a defect - a sign
            // that CVTRA01Y's TRAN-CAT-BAL PIC S9(09)V99 had been transcribed into this record.
            // Compared by name so that this file need not import the type it is forbidding.
            for (Field field : declaredFields()) {
                assertThat(field.getType().getName())
                        .as("field %s must not be an arbitrary-precision decimal", field.getName())
                        .isNotEqualTo(BIG_DECIMAL);
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("accessor %s must not return an arbitrary-precision decimal",
                                method.getName())
                        .isNotEqualTo(BIG_DECIMAL);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName()).isNotEqualTo(BIG_DECIMAL);
                }
            }
            // And the layout declares no signed span either, which is the same claim at the byte level.
            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                assertThat(span.kind())
                        .as("span %s must not be a signed scaled span", span.name())
                        .isNotEqualTo(PictureKind.SIGNED_SCALED)
                        .isIn(PictureKind.ALPHANUMERIC, PictureKind.UNSIGNED_NUMERIC,
                                PictureKind.FILLER);
            }
        }

        @Test
        @DisplayName("no persistence or framework mapping is declared anywhere on the type")
        void noPersistenceMapping() {
            // TRANCATG is a VSAM KSDS reached through JDBC with no schema, no migration and no
            // generated table behind it. An entity, table, identity or version annotation would imply
            // one. Every annotation the type declares - on the class, on a field or on a method - is
            // gathered first, so the two claims below are made about one collected set rather than by a
            // loop whose body may never run: the set is empty, and it contains nothing from a
            // persistence or framework package.
            List<String> annotationTypes = new ArrayList<>();
            for (Annotation annotation : TranCategoryRecord.class.getAnnotations()) {
                annotationTypes.add(annotation.annotationType().getName());
            }
            for (Field field : declaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    annotationTypes.add(annotation.annotationType().getName());
                }
            }
            for (Method method : declaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (!Override.class.equals(annotation.annotationType())) {
                        annotationTypes.add(annotation.annotationType().getName());
                    }
                }
            }

            assertThat(annotationTypes)
                    .as("a record model carries no framework annotation of any kind")
                    .isEmpty();
            assertThat(annotationTypes).noneMatch(name -> name.startsWith("jakarta.persistence.")
                    || name.startsWith("javax.persistence.")
                    || name.startsWith("org.springframework."));
        }

        @Test
        @DisplayName("every field is final, and every instance field is private")
        void everyFieldIsFinalAndInstanceFieldsArePrivate() {
            // No mutable static state, and no mutable instance state either: the record is immutable
            // and therefore safe to share and to cache across threads.
            for (Field field : declaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the type is final, so no subclass can weaken its byte contract")
        void theTypeIsFinal() {
            assertThat(Modifier.isFinal(TranCategoryRecord.class.getModifiers())).isTrue();
            assertThat(TranCategoryRecord.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.transaction.model");
        }
    }
}
