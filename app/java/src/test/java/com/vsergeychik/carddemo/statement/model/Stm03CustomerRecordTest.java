package com.vsergeychik.carddemo.statement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link Stm03CustomerRecord}, the single Java type for {@code app/cpy/CUSTREC.cpy}
 * ({@code 01 CUSTOMER-RECORD}, 500 bytes, nine-byte key) as {@code CBSTM03A} and {@code CBSTM03B}
 * use it.
 *
 * <h2>Provenance of every expected value in this class</h2>
 * <strong>Every expectation here is statically derived</strong> - read out of
 * {@code app/cpy/CUSTREC.cpy}, {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL},
 * {@code app/jcl/CREASTMT.JCL} and {@code app/data/ASCII/custdata.txt} - and <strong>none</strong> of
 * it was captured by running the legacy program. Running it is impossible in this environment, and the
 * eight verified blockers behind that are the substance of open risk <em>R-A</em>. One of the eight is
 * this very copybook: {@code app/cpy/CUSTREC.cpy} carries <strong>literal TAB characters at the start
 * of lines 6 through 22 inclusive</strong> - two per line, on {@code CUST-FIRST-NAME} through
 * {@code CUST-FICO-CREDIT-SCORE}, while lines 5 and 23 use spaces - which pushes the item names out of
 * COBOL's area B and makes GnuCOBOL fail outright at {@code cpy/CUSTREC.cpy:6} with "unbalanced
 * parentheses" and "invalid PICTURE character '2'". Its twin {@code app/cpy/CVCUS01Y.cpy} is
 * space-indented throughout and parses, which is how the TABs were isolated as the cause.
 *
 * <p>Because the values are derived rather than captured, they are written out <strong>as literals</strong>
 * with each offset and length spelled in full, so a reviewer holding the copybook open beside this file
 * can confirm every one by eye. Deliberately absent is any loop that walks
 * {@link Stm03CustomerRecord#LAYOUT} and checks it against itself: such a loop passes even when an
 * offset is wrong, which is precisely the defect worth catching. Nothing here opens
 * {@code app/cpy}, {@code app/cbl} or {@code app/data} at runtime either - those paths appear only in
 * comments, as citations. The reference tree is the parity oracle and stays untouched.
 *
 * <h2>The 500-byte width, corroborated four independent ways</h2>
 * <ol>
 *   <li>the copybook's own {@code PICTURE} widths, summed: {@code 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2
 *       + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3 + 168 = 500};</li>
 *   <li>the copybook's own header comment at line 2, {@code Data-structure for Customer entity
 *       (RECLN 500)};</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL}'s {@code FD CUST-FILE} split - {@code FD-CUST-ID PIC X(09)} at
 *       line 72 plus {@code FD-CUST-DATA PIC X(491)} at line 73;</li>
 *   <li>{@code app/data/ASCII/custdata.txt} - fifty records, every one exactly 500 bytes.</li>
 * </ol>
 *
 * <h2>Why this type exists separately from the customer domain's record</h2>
 * {@code app/cpy/CUSTREC.cpy} and {@code app/cpy/CVCUS01Y.cpy} were diffed in full. They agree on the
 * {@code (RECLN 500)} header, the {@code 01 CUSTOMER-RECORD} group name, all nineteen {@code PICTURE}
 * clauses, all nineteen offsets, the 500-byte total, and even the
 * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68} footer. They differ in exactly two respects: the
 * whitespace noted above, and <strong>one field name</strong> - {@code CUST-DOB-YYYYMMDD} here against
 * {@code CUST-DOB-YYYY-MM-DD} there. That single name is the whole reason two Java types are modelled,
 * because field-for-field parity diffing keys on the name, and it is guarded in both directions by
 * {@link TheCustrecDivergence} below.
 *
 * <h2>What is deliberately not here</h2>
 * No {@code CobolDecimal}, no {@code BigDecimal} and no {@code RoundingMode}, and that absence is part
 * of the contract rather than an omission: {@code CUSTREC.cpy} declares <strong>no signed picture, no
 * V-scaled picture and no {@code COMP-3}</strong>, so the record holds nothing to scale and nothing to
 * round, and there is no rounding mode to assert. Its three numeric fields - {@code CUST-ID 9(09)},
 * {@code CUST-SSN 9(09)} and {@code CUST-FICO-CREDIT-SCORE 9(03)} - are scale-free unsigned zoned
 * {@code DISPLAY} integers and read back through {@code int} views. {@link TypeHygiene} asserts the
 * absence rather than leaving it to be noticed.
 *
 * <p>No Spring context, no {@code MockMvc} and no {@code JobLauncher} either: this is a value type and
 * it is exercised directly. Nothing is mocked, because there is no collaborator to mock.
 *
 * <h2>Governing constraints</h2>
 * {@code review_rules} reports <strong>no user rules for this project</strong>, so no project rule
 * governs this file. Its absence is not licence to lower the bar: the twelve enterprise best-practice
 * substitutes <em>B1</em> through <em>B12</em> govern instead, and the ones with teeth here are
 * <em>B3</em> (the reference tree is immutable), <em>B6</em> (this record is personal data and is
 * asserted exactly as the COBOL stores it, with nothing masked and nothing weakened - the values used
 * are the synthetic ones already in the repository's own fixture), <em>B8</em> (every code page named
 * explicitly, no wildcard imports), <em>B9</em> (no mutable static state) and <em>B11</em> (hand-written
 * expectations).
 */
@DisplayName("Stm03CustomerRecord - CUSTOMER-RECORD of CUSTREC.cpy, 500 bytes, 19 spans")
class Stm03CustomerRecordTest {

    // =================================================================================================
    // Code pages. Both are named; neither is the platform default.
    //
    // config.CobolCharsetConfig is the production owner of this choice - it binds US-ASCII for the
    // fixture-shaped app/data/ASCII/*.txt data and IBM037 for the EBCDIC datasets, and every profile
    // states its dataset code page explicitly so no reader ever inherits file.encoding. That class is
    // deliberately NOT imported here: it is a Spring @Configuration, and this test runs no context.
    //
    // IBM037 is not in StandardCharsets. It ships in the JDK's jdk.charsets module, which is present in
    // a full JDK, so Charset.forName is the way to name it.
    // =================================================================================================

    /** The code page of {@code app/data/ASCII}, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The US and Canada EBCDIC code page of {@code app/data/EBCDIC}, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // =================================================================================================
    // Row 1 of app/data/ASCII/custdata.txt, transcribed span by span.
    //
    // Held as immutable Strings rather than as a byte[]: an array is mutable however final the field
    // is, and one test could then perturb another (B9, gate G53). Byte arrays are built inside the test
    // that needs them, through a named code page.
    //
    // The padding is written out as an explicit repeat() rather than typed as a run of spaces, because
    // a run of spaces in a source file is invisible to review and trailing whitespace does not survive
    // every editor. Each constant's length is asserted against its declared span width before use.
    // =================================================================================================

    /** {@code CUST-ID PIC 9(09)} at offset 0: the KSDS key, zero-filled on the left. */
    private static final String ROW1_CUST_ID = "000000001";

    /** {@code CUST-FIRST-NAME PIC X(25)} at offset 9. */
    private static final String ROW1_FIRST_NAME = "Immanuel" + " ".repeat(17);

    /** {@code CUST-MIDDLE-NAME PIC X(25)} at offset 34. */
    private static final String ROW1_MIDDLE_NAME = "Madeline" + " ".repeat(17);

    /** {@code CUST-LAST-NAME PIC X(25)} at offset 59. */
    private static final String ROW1_LAST_NAME = "Kessler" + " ".repeat(18);

    /** {@code CUST-ADDR-LINE-1 PIC X(50)} at offset 84 - moved at full width by {@code CBSTM03A:470}. */
    private static final String ROW1_ADDR_LINE_1 = "618 Deshaun Route" + " ".repeat(33);

    /** {@code CUST-ADDR-LINE-2 PIC X(50)} at offset 134 - moved at full width by {@code CBSTM03A:471}. */
    private static final String ROW1_ADDR_LINE_2 = "Apt. 802" + " ".repeat(42);

    /** {@code CUST-ADDR-LINE-3 PIC X(50)} at offset 184. */
    private static final String ROW1_ADDR_LINE_3 = "Altenwerthshire" + " ".repeat(35);

    /** {@code CUST-ADDR-STATE-CD PIC X(02)} at offset 234 - already exactly its declared width. */
    private static final String ROW1_STATE_CD = "NC";

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at offset 236 - already exactly its declared width. */
    private static final String ROW1_COUNTRY_CD = "USA";

    /** {@code CUST-ADDR-ZIP PIC X(10)} at offset 239. */
    private static final String ROW1_ZIP = "12546" + " ".repeat(5);

    /** {@code CUST-PHONE-NUM-1 PIC X(15)} at offset 249 - a field {@code CBSTM03A} never reads. */
    private static final String ROW1_PHONE_1 = "(908)119-8310" + " ".repeat(2);

    /** {@code CUST-PHONE-NUM-2 PIC X(15)} at offset 264 - a field {@code CBSTM03A} never reads. */
    private static final String ROW1_PHONE_2 = "(373)693-8684" + " ".repeat(2);

    /**
     * {@code CUST-SSN PIC 9(09)} at offset 279, stored exactly as the copybook declares it: nine
     * digits, unformatted and unmasked, leading zero and all.
     */
    private static final String ROW1_SSN = "020973888";

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)} at offset 288 - already exactly its declared width. */
    private static final String ROW1_GOVT_ID = "00000000000049368437";

    /**
     * {@code CUST-DOB-YYYYMMDD PIC X(10)} at offset 308.
     *
     * <p>Note the mismatch between the name and the data, and note that both are preserved: the field
     * <em>name</em> is un-hyphenated, yet the ten bytes the fixture stores are the
     * <em>hyphenated</em> {@code 1961-06-08}. This span is {@code PIC X(10)} - character data, not a
     * date - so it is never parsed, never reformatted to {@code 19610608}, and never routed through a
     * {@code DateTimeFormatter} whose default locale or calendar could move it.
     */
    private static final String ROW1_DOB = "1961-06-08";

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at offset 318 - already exactly its declared width. */
    private static final String ROW1_EFT_ACCOUNT_ID = "0053581756";

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at offset 328. */
    private static final String ROW1_PRI_CARD_HOLDER_IND = "Y";

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at offset 329 - already exactly its declared width. */
    private static final String ROW1_FICO = "274";

    /**
     * The trailing {@code FILLER PIC X(168)} at offset 332: 168 spaces, which is what bytes 332 to 499
     * of the real fixture row actually hold.
     */
    private static final String ROW1_FILLER = " ".repeat(168);

    /**
     * Row 1 of {@code app/data/ASCII/custdata.txt} assembled in copybook order.
     *
     * <p>Concatenated from the spans above rather than pasted as one 500-character literal, so that
     * each field's contribution stays individually reviewable. {@link Geometry#theAssembledRowIsFiveHundredBytes()}
     * checks the assembly before any other test relies on it.
     */
    private static final String ROW1_IMAGE = ROW1_CUST_ID
            + ROW1_FIRST_NAME
            + ROW1_MIDDLE_NAME
            + ROW1_LAST_NAME
            + ROW1_ADDR_LINE_1
            + ROW1_ADDR_LINE_2
            + ROW1_ADDR_LINE_3
            + ROW1_STATE_CD
            + ROW1_COUNTRY_CD
            + ROW1_ZIP
            + ROW1_PHONE_1
            + ROW1_PHONE_2
            + ROW1_SSN
            + ROW1_GOVT_ID
            + ROW1_DOB
            + ROW1_EFT_ACCOUNT_ID
            + ROW1_PRI_CARD_HOLDER_IND
            + ROW1_FICO
            + ROW1_FILLER;

    /**
     * Row 1 of the fixture as a record, built through the canonical constructor.
     *
     * <p>A method rather than a field, so no two tests can ever share mutable state through it (B9).
     * The record is immutable, but a shared instance would still couple the tests that read it.
     *
     * @return the record whose eighteen components are the fixture's own span images
     */
    private static Stm03CustomerRecord row1() {
        return new Stm03CustomerRecord(ROW1_CUST_ID,
                ROW1_FIRST_NAME,
                ROW1_MIDDLE_NAME,
                ROW1_LAST_NAME,
                ROW1_ADDR_LINE_1,
                ROW1_ADDR_LINE_2,
                ROW1_ADDR_LINE_3,
                ROW1_STATE_CD,
                ROW1_COUNTRY_CD,
                ROW1_ZIP,
                ROW1_PHONE_1,
                ROW1_PHONE_2,
                ROW1_SSN,
                ROW1_GOVT_ID,
                ROW1_DOB,
                ROW1_EFT_ACCOUNT_ID,
                ROW1_PRI_CARD_HOLDER_IND,
                ROW1_FICO);
    }

    /**
     * The same eighteen field images written at their natural, unpadded widths - the shape a caller
     * types by hand, which is what exercises each receiver's own {@code MOVE} rule.
     *
     * @return a record equal to {@link #row1()}, reached by padding rather than by transcription
     */
    private static Stm03CustomerRecord row1Unpadded() {
        return new Stm03CustomerRecord("1",
                "Immanuel",
                "Madeline",
                "Kessler",
                "618 Deshaun Route",
                "Apt. 802",
                "Altenwerthshire",
                "NC",
                "USA",
                "12546",
                "(908)119-8310",
                "(373)693-8684",
                "20973888",
                "00000000000049368437",
                "1961-06-08",
                "0053581756",
                "Y",
                "274");
    }

    /**
     * Asserts one declared span against literals transcribed by hand from {@code CUSTREC.cpy}.
     *
     * <p>The four expectations arrive as <strong>arguments</strong>, written out at each call site, so
     * this is not the descriptor table checking itself: every value it is compared against was typed
     * from the copybook. A wrong offset in the type therefore fails here, which a loop over
     * {@link Stm03CustomerRecord#LAYOUT} could never do.
     *
     * @param span     the descriptor under test, one of the type's own constants
     * @param name     the COBOL item name, spelled exactly as the copybook spells it
     * @param offset   the zero-based offset the copybook's widths put the item at
     * @param length   the item's declared {@code PICTURE} width
     * @param kind     the item's {@code PICTURE} category
     */
    private static void assertSpan(FieldSpan span, String name, int offset, int length,
                                   PictureKind kind) {
        assertThat(span.name()).as("COBOL item name").isEqualTo(name);
        assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
        assertThat(span.length()).as("%s declared width", name).isEqualTo(length);
        assertThat(span.kind()).as("%s PICTURE category", name).isEqualTo(kind);
        assertThat(span.endOffsetExclusive())
                .as("%s ends where the next item begins", name)
                .isEqualTo(offset + length);
    }

    @Nested
    @DisplayName("1. Geometry - nineteen spans totalling 500 bytes, transcribed from CUSTREC.cpy")
    class Geometry {

        @Test
        @DisplayName("this test class's own transcription of fixture row 1 is 500 bytes")
        void theAssembledRowIsFiveHundredBytes() {
            // A self-check on the expectation before anything is asserted against it. If a span
            // constant above were mistyped by a character, every later comparison would be measuring
            // the wrong thing, and it would look like a defect in the type rather than in the test.
            assertThat(ROW1_CUST_ID).hasSize(9);
            assertThat(ROW1_FIRST_NAME).hasSize(25);
            assertThat(ROW1_MIDDLE_NAME).hasSize(25);
            assertThat(ROW1_LAST_NAME).hasSize(25);
            assertThat(ROW1_ADDR_LINE_1).hasSize(50);
            assertThat(ROW1_ADDR_LINE_2).hasSize(50);
            assertThat(ROW1_ADDR_LINE_3).hasSize(50);
            assertThat(ROW1_STATE_CD).hasSize(2);
            assertThat(ROW1_COUNTRY_CD).hasSize(3);
            assertThat(ROW1_ZIP).hasSize(10);
            assertThat(ROW1_PHONE_1).hasSize(15);
            assertThat(ROW1_PHONE_2).hasSize(15);
            assertThat(ROW1_SSN).hasSize(9);
            assertThat(ROW1_GOVT_ID).hasSize(20);
            assertThat(ROW1_DOB).hasSize(10);
            assertThat(ROW1_EFT_ACCOUNT_ID).hasSize(10);
            assertThat(ROW1_PRI_CARD_HOLDER_IND).hasSize(1);
            assertThat(ROW1_FICO).hasSize(3);
            assertThat(ROW1_FILLER).hasSize(168);
            assertThat(ROW1_IMAGE)
                    .as("app/data/ASCII/custdata.txt row 1, reassembled span by span")
                    .hasSize(500);
        }

        @Test
        @DisplayName("the declared record length is 500 and the layout holds all nineteen spans")
        void theDeclaredRecordLengthIsFiveHundred() {
            // Gate G19, against the literal rather than against the constant itself.
            assertThat(Stm03CustomerRecord.RECORD_LENGTH)
                    .as("CUSTREC.cpy line 2 states RECLN 500, and CBSTM03B's FD split is 9 + 491")
                    .isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.recordLength()).isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.spans())
                    .as("18 referable items plus the trailing FILLER")
                    .hasSize(19);
            // No REDEFINES anywhere in this copybook, so every span occupies storage of its own.
            assertThat(Stm03CustomerRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(Stm03CustomerRecord.LAYOUT.storageSpans()).hasSize(19);
        }

        @Test
        @DisplayName("the CUSTFILE KSDS key is the leading nine bytes, which is CUST-ID")
        void theKeyIsTheLeadingNineBytes() {
            assertThat(Stm03CustomerRecord.KEY_OFFSET).isEqualTo(0);
            assertThat(Stm03CustomerRecord.KEY_LENGTH).isEqualTo(9);
            // CBSTM03B.CBL:189 moves LK-M03B-KEY (1:LK-M03B-KEY-LN) into FD-CUST-ID before the keyed
            // read, and CBSTM03A.CBL:372-374 supplies XREF-CUST-ID with LENGTH OF as the key length.
            assertThat(Stm03CustomerRecord.KEY_OFFSET).isEqualTo(Stm03CustomerRecord.CUST_ID_OFFSET);
            assertThat(Stm03CustomerRecord.KEY_LENGTH).isEqualTo(Stm03CustomerRecord.CUST_ID_LENGTH);
        }

        @Test
        @DisplayName("spans 1 to 4: CUST-ID and the three name fields")
        void spansOneToFour() {
            assertThat(Stm03CustomerRecord.CUST_ID_OFFSET).isEqualTo(0);
            assertThat(Stm03CustomerRecord.CUST_ID_LENGTH).isEqualTo(9);
            assertSpan(Stm03CustomerRecord.CUST_ID, "CUST-ID", 0, 9, PictureKind.UNSIGNED_NUMERIC);

            assertThat(Stm03CustomerRecord.CUST_FIRST_NAME_OFFSET).isEqualTo(9);
            assertThat(Stm03CustomerRecord.CUST_FIRST_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_FIRST_NAME, "CUST-FIRST-NAME", 9, 25,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_MIDDLE_NAME_OFFSET).isEqualTo(34);
            assertThat(Stm03CustomerRecord.CUST_MIDDLE_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_MIDDLE_NAME, "CUST-MIDDLE-NAME", 34, 25,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_LAST_NAME_OFFSET).isEqualTo(59);
            assertThat(Stm03CustomerRecord.CUST_LAST_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_LAST_NAME, "CUST-LAST-NAME", 59, 25,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 5 to 10: the three address lines, state, country and zip")
        void spansFiveToTen() {
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_1_OFFSET).isEqualTo(84);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_1_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_1, "CUST-ADDR-LINE-1", 84, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_2_OFFSET).isEqualTo(134);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_2_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_2, "CUST-ADDR-LINE-2", 134, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_3_OFFSET).isEqualTo(184);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_3_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_3, "CUST-ADDR-LINE-3", 184, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_STATE_CD_OFFSET).isEqualTo(234);
            assertThat(Stm03CustomerRecord.CUST_ADDR_STATE_CD_LENGTH).isEqualTo(2);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_STATE_CD, "CUST-ADDR-STATE-CD", 234, 2,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD_OFFSET).isEqualTo(236);
            assertThat(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD_LENGTH).isEqualTo(3);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD, "CUST-ADDR-COUNTRY-CD", 236, 3,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_ZIP_OFFSET).isEqualTo(239);
            assertThat(Stm03CustomerRecord.CUST_ADDR_ZIP_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_ZIP, "CUST-ADDR-ZIP", 239, 10,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 11 to 14: the two telephone numbers, the SSN and the government identifier")
        void spansElevenToFourteen() {
            // CBSTM03A reads none of these four. They are still part of the byte contract - every
            // offset after them depends on their widths - so they are asserted like any other span.
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_1_OFFSET).isEqualTo(249);
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_1_LENGTH).isEqualTo(15);
            assertSpan(Stm03CustomerRecord.CUST_PHONE_NUM_1, "CUST-PHONE-NUM-1", 249, 15,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_2_OFFSET).isEqualTo(264);
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_2_LENGTH).isEqualTo(15);
            assertSpan(Stm03CustomerRecord.CUST_PHONE_NUM_2, "CUST-PHONE-NUM-2", 264, 15,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_SSN_OFFSET).isEqualTo(279);
            assertThat(Stm03CustomerRecord.CUST_SSN_LENGTH).isEqualTo(9);
            assertSpan(Stm03CustomerRecord.CUST_SSN, "CUST-SSN", 279, 9,
                    PictureKind.UNSIGNED_NUMERIC);

            assertThat(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID_OFFSET).isEqualTo(288);
            assertThat(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID_LENGTH).isEqualTo(20);
            assertSpan(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID, "CUST-GOVT-ISSUED-ID", 288, 20,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 15 to 18: date of birth, EFT account, card-holder indicator and FICO")
        void spansFifteenToEighteen() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_OFFSET).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, "CUST-DOB-YYYYMMDD", 308, 10,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID_OFFSET).isEqualTo(318);
            assertThat(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID, "CUST-EFT-ACCOUNT-ID", 318, 10,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND_OFFSET).isEqualTo(328);
            assertThat(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND_LENGTH).isEqualTo(1);
            assertSpan(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND, "CUST-PRI-CARD-HOLDER-IND", 328,
                    1, PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_OFFSET).isEqualTo(329);
            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_LENGTH).isEqualTo(3);
            assertSpan(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE, "CUST-FICO-CREDIT-SCORE", 329, 3,
                    PictureKind.UNSIGNED_NUMERIC);
        }

        @Test
        @DisplayName("span 19: FILLER PIC X(168) at offset 332, a declared span and not an implied gap")
        void spanNineteenIsFiller() {
            // Gate G21. FILLER is a third of this record, and if it were inferred as "whatever is left
            // between the last field and byte 500" then dropping or mis-sizing it would corrupt the
            // record width silently. It is declared, so it can be asserted.
            assertThat(Stm03CustomerRecord.FILLER_OFFSET).isEqualTo(332);
            assertThat(Stm03CustomerRecord.FILLER_LENGTH).isEqualTo(168);
            assertSpan(Stm03CustomerRecord.FILLER, "FILLER", 332, 168, PictureKind.FILLER);
            assertThat(Stm03CustomerRecord.FILLER.kind().filler()).isTrue();
            assertThat(Stm03CustomerRecord.LAYOUT.spans())
                    .as("FILLER is an entry in the layout, positioned and length-bearing")
                    .contains(Stm03CustomerRecord.FILLER);
            assertThat(Stm03CustomerRecord.LAYOUT.spans().get(18))
                    .as("and it is the last entry, closing the record at byte 500")
                    .isEqualTo(Stm03CustomerRecord.FILLER);
            // It carries no VALUE clause, so it holds spaces rather than a literal.
            assertThat(Stm03CustomerRecord.FILLER.hasInitialValue()).isFalse();
        }

        @Test
        @DisplayName("the nineteen declared widths sum to exactly 500")
        void theNineteenWidthsSumToFiveHundred() {
            // The copybook's own arithmetic, written out. This is corroboration 1 of the four.
            int sum = 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1
                    + 3 + 168;
            assertThat(sum).isEqualTo(500);

            int declared = 0;
            for (FieldSpan span : Stm03CustomerRecord.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared)
                    .as("the type's own spans must account for every one of the 500 bytes")
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("every span begins exactly where the previous one ended, from 0 through to 500")
        void everySpanBeginsWhereThePreviousOneEnded() {
            // Written as nineteen literal additions rather than derived from the table, so a shifted
            // offset cannot hide behind a consistently shifted neighbour.
            assertThat(0 + 9).isEqualTo(9);
            assertThat(9 + 25).isEqualTo(34);
            assertThat(34 + 25).isEqualTo(59);
            assertThat(59 + 25).isEqualTo(84);
            assertThat(84 + 50).isEqualTo(134);
            assertThat(134 + 50).isEqualTo(184);
            assertThat(184 + 50).isEqualTo(234);
            assertThat(234 + 2).isEqualTo(236);
            assertThat(236 + 3).isEqualTo(239);
            assertThat(239 + 10).isEqualTo(249);
            assertThat(249 + 15).isEqualTo(264);
            assertThat(264 + 15).isEqualTo(279);
            assertThat(279 + 9).isEqualTo(288);
            assertThat(288 + 20).isEqualTo(308);
            assertThat(308 + 10).isEqualTo(318);
            assertThat(318 + 10).isEqualTo(328);
            assertThat(328 + 1).isEqualTo(329);
            assertThat(329 + 3).isEqualTo(332);
            assertThat(332 + 168)
                    .as("the trailing FILLER closes the record exactly at its declared width")
                    .isEqualTo(500);

            // And the same chain read out of the type, so a divergence between the two is caught.
            List<FieldSpan> spans = Stm03CustomerRecord.LAYOUT.storageSpans();
            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("%s must begin at %d", span.name(), expectedOffset)
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            assertThat(expectedOffset).isEqualTo(500);
        }

        @Test
        @DisplayName("dropping FILLER from the layout fails immediately, naming the 168-byte shortfall")
        void droppingFillerFailsImmediately() {
            // The other half of gate G21, and the reason the width self-check earns its place: an
            // 18-span layout declares 332 bytes against a record length of 500, so it cannot be built
            // at all. Omitting FILLER is therefore a construction failure that names the shortfall,
            // rather than a wrong byte discovered hundreds of offsets downstream.
            FieldSpan[] withoutFiller = Stm03CustomerRecord.LAYOUT.storageSpans()
                    .subList(0, 18)
                    .toArray(new FieldSpan[0]);
            assertThat(withoutFiller).hasSize(18);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(500, withoutFiller))
                    .withMessageContainingAll("332", "500", "168", "FILLER");

            assertThat(500 - Stm03CustomerRecord.FILLER_OFFSET)
                    .as("the eighteen referable items reach byte 332, so FILLER supplies the rest")
                    .isEqualTo(168);
        }
    }

    @Nested
    @DisplayName("2. FILLER is emitted as 168 spaces, always")
    class Filler {

        @Test
        @DisplayName("an initialised record blanks FILLER and zero-fills only the numeric spans")
        void anInitialisedRecordBlanksFiller() {
            // blank(Charset) follows the COBOL INITIALIZE convention, which is applied per PICTURE
            // category rather than uniformly: alphanumeric spans and FILLER take spaces, the three
            // unsigned numeric spans take zeros. The all-spaces record is a different state entirely
            // and arrives by group MOVE - see GroupMoveTolerance below.
            Stm03CustomerRecord blank = Stm03CustomerRecord.blank(ASCII);

            assertThat(blank.groupImage(ASCII)).hasSize(500);
            assertThat(blank.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .as("FILLER carries no VALUE clause, so it initialises to spaces")
                    .isEqualTo(" ".repeat(168));
            assertThat(blank.custId()).isEqualTo("000000000");
            assertThat(blank.custSsn()).isEqualTo("000000000");
            assertThat(blank.custFicoCreditScore()).isEqualTo("000");
            assertThat(blank.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(blank.custDobYyyymmdd()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("FILLER is still 168 spaces after every one of the eighteen fields is populated")
        void fillerSurvivesEveryFieldBeingWritten() {
            // Gate G21 at run time. Every referable field carries a value here, so if any write were
            // mis-offset by even one byte it would land inside the trailing span and be seen.
            String filler = row1().spanImage(Stm03CustomerRecord.FILLER, ASCII);

            assertThat(filler).hasSize(168).isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("bytes 332 to 499 of the encoded image are spaces under both code pages")
        void theTrailingBytesOfTheImageAreSpaces() {
            // Read out of the encoded bytes rather than through the FILLER descriptor, so the two
            // routes to the same 168 bytes have to agree. Corroborated by the fixture itself: bytes
            // 332 to 499 of app/data/ASCII/custdata.txt row 1 are all spaces.
            assertThat(new String(row1().encode(ASCII), ASCII).substring(332))
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
            assertThat(new String(row1().encode(EBCDIC), EBCDIC).substring(332))
                    .as("the same 168 spaces, this time as EBCDIC 0x40 bytes")
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("FILLER is readable through its descriptor but is not a comparable field")
        void fillerIsReadableButNotComparable() {
            Stm03CustomerRecord record = row1();

            // Readable, because a width assertion has to be able to see it.
            assertThat(record.spanBytes(Stm03CustomerRecord.FILLER, ASCII)).hasSize(168);
            // Absent from the field map, because FILLER is not a referable COBOL name and so is not a
            // field that parity diffing can compare. Its bytes are in the image either way.
            assertThat(record.fieldImages(ASCII))
                    .as("eighteen referable items, FILLER excluded")
                    .hasSize(18)
                    .doesNotContainKey("FILLER");
        }
    }

    @Nested
    @DisplayName("3. Type hygiene - what this record deliberately is not")
    class TypeHygiene {

        @Test
        @DisplayName("no persistence annotation anywhere: this is a copybook record, not an entity")
        void noPersistenceAnnotationAnywhere() {
            // Gate G44. The migration reaches the existing datasets over JDBC with no schema change,
            // so there is no table to map to, no generated identifier and no version column. A
            // reflective check is the right instrument here precisely because it is checking for
            // ABSENCE - there is no positive fact a literal could assert instead.
            List<String> forbidden = List.of("Entity", "Table", "Id", "Column", "GeneratedValue",
                    "Version", "Embeddable", "MappedSuperclass");
            List<String> found = new ArrayList<>();

            for (Annotation annotation : Stm03CustomerRecord.class.getAnnotations()) {
                if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                    found.add("class: " + annotation.annotationType().getName());
                }
            }
            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                        found.add(field.getName() + ": " + annotation.annotationType().getName());
                    }
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                        found.add(method.getName() + ": " + annotation.annotationType().getName());
                    }
                }
            }

            assertThat(found)
                    .as("no DDL, no ORM mapping and no version column belong on a VSAM record")
                    .isEmpty();
        }

        @Test
        @DisplayName("no double and no float: every numeric here is an exact zoned DISPLAY integer")
        void noBinaryFloatingPointAnywhere() {
            // Gate G22. Binary floating point cannot represent a decimal fraction exactly, so it has
            // no place anywhere near a monetary or identifier field. This record has no fractional
            // field at all - see the class comment on why it also has no BigDecimal - but the ban is
            // asserted rather than assumed, because a later accessor could quietly introduce one.
            List<Class<?>> banned = List.of(double.class, float.class, Double.class, Float.class);
            List<String> offenders = new ArrayList<>();

            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (banned.contains(field.getType())) {
                    offenders.add("field " + field.getName() + " is " + field.getType().getName());
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                if (banned.contains(method.getReturnType())) {
                    offenders.add("method " + method.getName() + " returns "
                            + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (banned.contains(parameter)) {
                        offenders.add("method " + method.getName() + " takes "
                                + parameter.getName());
                    }
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("nothing scaled and nothing rounded: no BigDecimal, no RoundingMode, no decimal helper")
        void noScaledArithmeticAnywhere() {
            // CUSTREC.cpy declares no PIC S, no V and no COMP-3, so there is nothing in this record to
            // scale and no rounding mode to choose. That absence is part of the contract: a BigDecimal
            // appearing here would mean a picture had been misread. Asserted over every declared
            // member so the claim cannot rot.
            List<String> bannedTypeNames = List.of("java.math.BigDecimal", "java.math.RoundingMode",
                    "com.vsergeychik.carddemo.common.CobolDecimal");
            List<String> offenders = new ArrayList<>();

            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (bannedTypeNames.contains(field.getType().getName())) {
                    offenders.add("field " + field.getName());
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                if (bannedTypeNames.contains(method.getReturnType().getName())) {
                    offenders.add("method " + method.getName() + " returns "
                            + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (bannedTypeNames.contains(parameter.getName())) {
                        offenders.add("method " + method.getName() + " takes " + parameter.getName());
                    }
                }
            }

            assertThat(offenders)
                    .as("no signed and no V-scaled picture exists in CUSTREC.cpy, so none of these "
                            + "types has anything to do here")
                    .isEmpty();
        }

        @Test
        @DisplayName("every static field is final: a record area is per-instance state, never shared")
        void everyStaticFieldIsFinal() {
            // Gate G53. COBOL WORKING-STORAGE is per-run state, and turning it into a mutable static
            // would make two concurrent statement runs share one customer.
            List<String> mutable = new ArrayList<>();
            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }

            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the three numeric views are int, because nine digits fit an int")
        void theNumericViewsAreInt() throws NoSuchMethodException {
            // PIC 9(09) tops out at 999,999,999 and PIC 9(03) at 999. A long would advertise nineteen
            // digits of state that neither field can hold.
            assertThat(Stm03CustomerRecord.class.getMethod("custIdValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custSsnValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custFicoCreditScoreValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(999_999_999).isLessThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("the diagnostic rendering names all eighteen fields and discloses no identity")
        void theDiagnosticRenderingWithholdsTheIdentity() {
            // B6: the posture is neither weakened nor unrequestedly strengthened. fieldImages and
            // encode still return the real bytes, because a caller asks for those by name; what is
            // withheld is the incidental disclosure a record's generated toString would make.
            String rendered = row1().toString();

            assertThat(rendered).contains("CUST-DOB-YYYYMMDD", "CUST-SSN", "CUST-GOVT-ISSUED-ID");
            assertThat(rendered)
                    .as("the SSN, the government identifier and the EFT account are not rendered")
                    .doesNotContain(ROW1_SSN, ROW1_GOVT_ID, ROW1_EFT_ACCOUNT_ID);
            assertThat(rendered)
                    .as("nor is the customer's name or date of birth")
                    .doesNotContain("Immanuel", "Kessler", ROW1_DOB);
            assertThat(row1().fieldImages(ASCII))
                    .as("but the values themselves are still reachable by name")
                    .containsEntry("CUST-SSN", ROW1_SSN);
        }
    }

    @Nested
    @DisplayName("4. Fixture row 1 - all nineteen spans, decoded and re-encoded byte-identically")
    class FixtureRowOne {

        @Test
        @DisplayName("decoding row 1 recovers every one of the eighteen referable fields")
        void decodingRowOneRecoversEveryField() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFirstName()).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(record.custMiddleName()).isEqualTo("Madeline" + " ".repeat(17));
            assertThat(record.custLastName()).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(record.custAddrLine1()).isEqualTo("618 Deshaun Route" + " ".repeat(33));
            assertThat(record.custAddrLine2()).isEqualTo("Apt. 802" + " ".repeat(42));
            assertThat(record.custAddrLine3()).isEqualTo("Altenwerthshire" + " ".repeat(35));
            assertThat(record.custAddrStateCd()).isEqualTo("NC");
            assertThat(record.custAddrCountryCd()).isEqualTo("USA");
            assertThat(record.custAddrZip()).isEqualTo("12546" + " ".repeat(5));
            assertThat(record.custPhoneNum1()).isEqualTo("(908)119-8310" + " ".repeat(2));
            assertThat(record.custPhoneNum2()).isEqualTo("(373)693-8684" + " ".repeat(2));
            assertThat(record.custSsn()).isEqualTo("020973888");
            assertThat(record.custGovtIssuedId()).isEqualTo("00000000000049368437");
            assertThat(record.custDobYyyymmdd())
                    .as("the field name is un-hyphenated but the ten stored bytes are hyphenated, and "
                            + "both are preserved exactly as they are")
                    .isEqualTo("1961-06-08");
            assertThat(record.custEftAccountId()).isEqualTo("0053581756");
            assertThat(record.custPriCardHolderInd()).isEqualTo("Y");
            assertThat(record.custFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("span 19 decodes too: FILLER is 168 spaces in the real fixture row")
        void theFillerSpanDecodesAsSpaces() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("decode then encode is byte-identical to the fixture row")
        void decodeThenEncodeIsByteIdentical() {
            byte[] expected = ROW1_IMAGE.getBytes(ASCII);
            assertThat(expected).as("the transcription is 500 bytes under US-ASCII").hasSize(500);

            byte[] actual = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).encode(ASCII);

            assertThat(actual).hasSize(500).isEqualTo(expected);
        }

        @Test
        @DisplayName("the group image is the same 500 characters, read in one piece")
        void theGroupImageIsTheSameFiveHundredCharacters() {
            // The 01 CUSTOMER-RECORD group viewed whole, which is the shape parity fingerprinting
            // captures before decomposing a record into named fields.
            assertThat(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).groupImage(ASCII))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE);
            assertThat(row1().groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
        }

        @Test
        @DisplayName("the same row round-trips through IBM037, so no platform default can leak in")
        void theSameRowRoundTripsThroughEbcdic() {
            // Encoded to EBCDIC bytes and decoded straight back. Every field must come out identical,
            // which it cannot do if any step reached for file.encoding instead of the named code page.
            byte[] ebcdic = row1().encode(EBCDIC);
            assertThat(ebcdic).hasSize(500);

            Stm03CustomerRecord recovered = Stm03CustomerRecord.decode(ebcdic, EBCDIC);

            assertThat(recovered).isEqualTo(row1());
            assertThat(recovered.custDobYyyymmdd()).isEqualTo("1961-06-08");
            assertThat(recovered.custSsn()).isEqualTo("020973888");
            assertThat(recovered.spanImage(Stm03CustomerRecord.FILLER, EBCDIC))
                    .isEqualTo(" ".repeat(168));
            // And the two code pages really are different byte sequences for the same record - proof
            // that the charset argument is doing work rather than being decorative.
            assertThat(ebcdic).isNotEqualTo(row1().encode(ASCII));
            assertThat(ebcdic[0])
                    .as("EBCDIC digit zero is 0xF0, ASCII digit zero is 0x30")
                    .isEqualTo((byte) 0xF0);
            assertThat(row1().encode(ASCII)[0]).isEqualTo((byte) 0x30);
        }

        @Test
        @DisplayName("the field map is keyed by the copybook's own eighteen names")
        void theFieldMapIsKeyedByCopybookNames() {
            Map<String, String> images = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII)
                    .fieldImages(ASCII);

            assertThat(images).hasSize(18);
            assertThat(images.keySet()).containsExactly("CUST-ID",
                    "CUST-FIRST-NAME",
                    "CUST-MIDDLE-NAME",
                    "CUST-LAST-NAME",
                    "CUST-ADDR-LINE-1",
                    "CUST-ADDR-LINE-2",
                    "CUST-ADDR-LINE-3",
                    "CUST-ADDR-STATE-CD",
                    "CUST-ADDR-COUNTRY-CD",
                    "CUST-ADDR-ZIP",
                    "CUST-PHONE-NUM-1",
                    "CUST-PHONE-NUM-2",
                    "CUST-SSN",
                    "CUST-GOVT-ISSUED-ID",
                    "CUST-DOB-YYYYMMDD",
                    "CUST-EFT-ACCOUNT-ID",
                    "CUST-PRI-CARD-HOLDER-IND",
                    "CUST-FICO-CREDIT-SCORE");
            assertThat(images).containsEntry("CUST-FICO-CREDIT-SCORE", "274");
        }

        @Test
        @DisplayName("writing the fields at their natural widths reaches the identical 500 bytes")
        void naturalWidthsReachTheIdenticalBytes() {
            // The receiver applies its own MOVE rule at construction, so a caller who types "1" and a
            // caller who types "000000001" end up holding the same record. Before that rule moved to
            // construction, only encode() applied it and the two compared unequal while encoding
            // identically - an accessor, equals and encode could each describe a different record.
            assertThat(row1Unpadded()).isEqualTo(row1());
            assertThat(row1Unpadded()).hasSameHashCodeAs(row1());
            assertThat(row1Unpadded().encode(ASCII)).isEqualTo(ROW1_IMAGE.getBytes(ASCII));
            assertThat(row1Unpadded().groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
        }
    }

    @Nested
    @DisplayName("5. Spans are returned untrimmed - trimming belongs to the consumer")
    class UntrimmedSpans {

        @Test
        @DisplayName("every PIC X accessor returns its full declared width, padding included")
        void everyAlphanumericAccessorReturnsItsFullWidth() {
            // AAP 0.3.7: "not trimmed on read unless the COBOL trims". Here the COBOL does not trim on
            // read - it trims at the point of USE, and only for some fields. CBSTM03A.CBL:462, :464 and
            // :466 compose the statement name with STRING ... DELIMITED BY ' ', which stops at the
            // first space, and :472 to :478 do the same for ADDR-LINE-3, STATE-CD, COUNTRY-CD and ZIP.
            // But :470 and :471 are plain MOVEs of ADDR-LINE-1 and ADDR-LINE-2 at FULL WIDTH, trailing
            // spaces and all. A record type that trimmed on read would make those two moves impossible
            // to reproduce, so the padding is the caller's to remove.
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custFirstName()).hasSize(25).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(record.custMiddleName()).hasSize(25).isEqualTo("Madeline" + " ".repeat(17));
            assertThat(record.custLastName()).hasSize(25).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(record.custAddrLine1()).hasSize(50);
            assertThat(record.custAddrLine2()).hasSize(50);
            assertThat(record.custAddrLine3()).hasSize(50);
            assertThat(record.custAddrStateCd()).hasSize(2);
            assertThat(record.custAddrCountryCd()).hasSize(3);
            assertThat(record.custAddrZip()).hasSize(10).isEqualTo("12546" + " ".repeat(5));
            assertThat(record.custPhoneNum1()).hasSize(15);
            assertThat(record.custPhoneNum2()).hasSize(15);
            assertThat(record.custGovtIssuedId()).hasSize(20);
            assertThat(record.custDobYyyymmdd()).hasSize(10);
            assertThat(record.custEftAccountId()).hasSize(10);
            assertThat(record.custPriCardHolderInd()).hasSize(1);
        }

        @Test
        @DisplayName("the three PIC 9 accessors also return their full declared width")
        void everyNumericAccessorReturnsItsFullWidth() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custId()).hasSize(9);
            assertThat(record.custSsn()).hasSize(9);
            assertThat(record.custFicoCreditScore()).hasSize(3);
        }

        @Test
        @DisplayName("a trailing-space name is not silently equal to its trimmed form")
        void aPaddedNameIsNotItsTrimmedForm() {
            // The distinction the untrimmed contract protects. If the accessor trimmed, these two
            // assertions could not both hold, and a 25-byte span would be indistinguishable from an
            // 8-byte string when the record was written back.
            String first = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).custFirstName();

            assertThat(first).isNotEqualTo("Immanuel");
            assertThat(first.strip())
                    .as("the consumer trims, exactly as STRING ... DELIMITED BY ' ' does")
                    .isEqualTo("Immanuel");
        }
    }

    @Nested
    @DisplayName("6. The two MOVE directions - PIC X pads and truncates right, PIC 9 left")
    class MoveDirections {

        @Test
        @DisplayName("a short PIC X value is right-space-padded to its declared width")
        void aShortAlphanumericIsRightPadded() {
            assertThat(row1Unpadded().custFirstName()).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(row1Unpadded().custLastName()).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(row1Unpadded().custAddrZip()).isEqualTo("12546" + " ".repeat(5));
            // Already exactly its width, so the rule is a no-op and the value passes through.
            assertThat(row1Unpadded().custAddrStateCd()).isEqualTo("NC");
            assertThat(row1Unpadded().custAddrCountryCd()).isEqualTo("USA");
        }

        @Test
        @DisplayName("an over-wide PIC X value is truncated on the RIGHT")
        void anOverWideAlphanumericIsTruncatedRight() {
            // A PIC X receiver fills from its leftmost byte and discards the overflow, so the leading
            // characters are the survivors. The sending value is split at the receiver's own width so
            // the boundary is written out rather than computed: fifty characters land, ten are lost.
            String survivingFifty = "618 Deshaun Route, Building 12, Suite 400, Altenwe";
            String discardedTen = "rthshire, ";
            assertThat(survivingFifty).hasSize(50);
            assertThat(discardedTen).hasSize(10);
            String sixtyCharacters = survivingFifty + discardedTen;
            assertThat(sixtyCharacters).hasSize(60);

            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME,
                    sixtyCharacters,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3,
                    "North Carolina", "United States",
                    ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custAddrLine1())
                    .as("the leading fifty survive; the ten that follow are discarded on the right")
                    .hasSize(50)
                    .isEqualTo(survivingFifty)
                    .doesNotContain(discardedTen);
            assertThat(record.custAddrStateCd())
                    .as("PIC X(02) keeps the first two characters of 'North Carolina'")
                    .isEqualTo("No");
            assertThat(record.custAddrCountryCd())
                    .as("PIC X(03) keeps the first three characters of 'United States'")
                    .isEqualTo("Uni");
        }

        @ParameterizedTest(name = "CUST-PRI-CARD-HOLDER-IND receives [{0}] and holds [{1}]")
        @DisplayName("PIC X(01) round-trips one character, blanks an empty value and keeps the first")
        @CsvSource(nullValues = "NIL", value = {
            "Y, Y",
            "N, N",
            "'', ' '",
            "YN, Y",
            "'  ', ' '",
        })
        void theSingleCharacterIndicator(String supplied, String expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID,
                    supplied,
                    ROW1_FICO);

            assertThat(record.custPriCardHolderInd()).hasSize(1).isEqualTo(expected);
        }

        @ParameterizedTest(name = "CUST-ID receives [{0}] and holds [{1}]")
        @DisplayName("PIC 9(09) is zero-filled on the LEFT and truncated on the LEFT")
        @CsvSource({
            "1, 000000001",
            "42, 000000042",
            "20973888, 020973888",
            "999999999, 999999999",
            "1234567890, 234567890",
            "10000000001, 000000001",
        })
        void theNineDigitKey(String supplied, String expected) {
            // A numeric receiver aligns on its implied decimal point, so a short image gains leading
            // zeros and a long one loses its HIGH-order digits. That is the opposite direction from
            // PIC X, and confusing the two is the classic silent MOVE defect - which is why both
            // directions are asserted rather than assumed.
            Stm03CustomerRecord record = new Stm03CustomerRecord(supplied,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId()).hasSize(9).isEqualTo(expected);
        }

        @ParameterizedTest(name = "CUST-FICO-CREDIT-SCORE receives [{0}] and holds [{1}]")
        @DisplayName("PIC 9(03) follows the same left-hand rules in three bytes")
        @CsvSource({
            "274, 274",
            "7, 007",
            "0, 000",
            "1234, 234",
            "999, 999",
        })
        void theThreeDigitScore(String supplied, String expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND,
                    supplied);

            assertThat(record.custFicoCreditScore()).hasSize(3).isEqualTo(expected);
        }

        @Test
        @DisplayName("the SSN keeps the leading zero the fixture stores, and the int view drops it")
        void theSsnKeepsItsLeadingZero() {
            // Sliced from the stored image the number reads 020-97-3888. Sliced from the integer
            // 20973888 it would read 209-73-888, which is a different person. The nine-byte image is
            // therefore the field, and the int view is a convenience over it.
            Stm03CustomerRecord record = row1Unpadded();

            assertThat(record.custSsn()).isEqualTo("020973888");
            assertThat(record.custSsnValue(ASCII)).isEqualTo(20973888);
            assertThat(record.custIdValue(ASCII)).isEqualTo(1);
            assertThat(record.custFicoCreditScoreValue(ASCII)).isEqualTo(274);
        }

        @Test
        @DisplayName("a null field image is refused, naming the field and what to pass instead")
        void aNullFieldImageIsRefused() {
            // There is no null in a COBOL record area: a field always holds bytes. Blanking one is
            // therefore spaces or an empty string, and the message says so rather than leaving the
            // caller to guess.
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(null,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO))
                    .withMessageContainingAll("CUST-ID", "spaces or an empty string");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(ROW1_CUST_ID,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, null,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO))
                    .withMessageContaining("CUST-DOB-YYYYMMDD");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(ROW1_CUST_ID,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, null))
                    .withMessageContaining("CUST-FICO-CREDIT-SCORE");
        }
    }

    @Nested
    @DisplayName("7. One span, two views - the equivalence that stands in for REDEFINES here")
    class SpanAndFieldEquivalence {

        // CUSTREC.cpy contains NO REDEFINES clause at all, and - unlike COSTM01.CPY - no sub-groups
        // either: nineteen flat 05-level items and nothing overlaid on any of them.
        // Stm03CustomerRecord.LAYOUT.redefinitions() is empty, which Geometry asserts. The obligation
        // that gate G34 carries is therefore discharged on the mechanism this record actually has:
        // two typed views over one backing span, which must agree in both directions. A byte written
        // through the span must be visible through the field, and the other way round, or the record
        // area and the components have quietly become two different records.

        @Test
        @DisplayName("the nine-byte key span and the CUST-ID field are two views of the same bytes")
        void theKeySpanAndTheFieldAreOneStorage() {
            Stm03CustomerRecord record = row1();

            // Field view -> span view.
            assertThat(record.spanImage(Stm03CustomerRecord.CUST_ID, ASCII))
                    .isEqualTo(record.custId())
                    .isEqualTo("000000001");
            // The same nine bytes addressed as the KSDS key rather than as the field, by offset and
            // length rather than by name.
            assertThat(record.groupImage(ASCII).substring(Stm03CustomerRecord.KEY_OFFSET,
                            Stm03CustomerRecord.KEY_OFFSET + Stm03CustomerRecord.KEY_LENGTH))
                    .isEqualTo("000000001");
            // Span view -> field view: a record decoded from an image whose leading nine bytes were
            // set wholesale reports the new key through the field accessor.
            String reKeyed = "000000042" + ROW1_IMAGE.substring(9);
            assertThat(reKeyed).hasSize(500);

            Stm03CustomerRecord rekeyed = Stm03CustomerRecord.decode(reKeyed, ASCII);

            assertThat(rekeyed.custId()).isEqualTo("000000042");
            assertThat(rekeyed.custIdValue(ASCII)).isEqualTo(42);
            assertThat(rekeyed.spanImage(Stm03CustomerRecord.CUST_ID, ASCII))
                    .isEqualTo("000000042");
            assertThat(rekeyed.custLastName())
                    .as("and nothing outside the key moved")
                    .isEqualTo("Kessler" + " ".repeat(18));
        }

        @Test
        @DisplayName("a sub-span over offsets 9 to 83 round-trips and is visible through three fields")
        void aSubSpanOverTheThreeNamesRoundTrips() {
            // Offsets 9 through 83 inclusive are exactly CUST-FIRST-NAME, CUST-MIDDLE-NAME and
            // CUST-LAST-NAME - 25 + 25 + 25 = 75 bytes - so one arbitrary range and three named fields
            // describe the same storage and have to agree.
            assertThat(9 + 25 + 25 + 25).isEqualTo(84);

            String extracted = row1().groupImage(ASCII).substring(9, 84);

            assertThat(extracted).hasSize(75);
            assertThat(extracted)
                    .isEqualTo(ROW1_FIRST_NAME + ROW1_MIDDLE_NAME + ROW1_LAST_NAME);

            // Replace the whole 75-byte range wholesale and read it back through the three accessors.
            String replacement = "Grace" + " ".repeat(20)
                    + "Brewster" + " ".repeat(17)
                    + "Hopper" + " ".repeat(19);
            assertThat(replacement).hasSize(75);

            String replaced = ROW1_IMAGE.substring(0, 9) + replacement + ROW1_IMAGE.substring(84);
            assertThat(replaced).hasSize(500);

            Stm03CustomerRecord renamed = Stm03CustomerRecord.decode(replaced, ASCII);

            assertThat(renamed.custFirstName()).isEqualTo("Grace" + " ".repeat(20));
            assertThat(renamed.custMiddleName()).isEqualTo("Brewster" + " ".repeat(17));
            assertThat(renamed.custLastName()).isEqualTo("Hopper" + " ".repeat(19));
            // Byte-identical round trip of the replaced image, and nothing either side of the range
            // disturbed.
            assertThat(renamed.encode(ASCII)).isEqualTo(replaced.getBytes(ASCII));
            assertThat(renamed.custId()).isEqualTo("000000001");
            assertThat(renamed.custAddrLine1()).isEqualTo(ROW1_ADDR_LINE_1);
        }

        @Test
        @DisplayName("every one of the eighteen fields agrees with its own span, both ways")
        void everyFieldAgreesWithItsSpan() {
            Stm03CustomerRecord record = row1();
            Map<String, String> images = record.fieldImages(ASCII);

            // The field map, the span reader and the component accessors are three routes to the same
            // eighteen strings. Checking all three against each other catches a span whose descriptor
            // and whose accessor have drifted apart.
            assertThat(images).containsEntry("CUST-ID", record.custId());
            assertThat(images).containsEntry("CUST-FIRST-NAME", record.custFirstName());
            assertThat(images).containsEntry("CUST-MIDDLE-NAME", record.custMiddleName());
            assertThat(images).containsEntry("CUST-LAST-NAME", record.custLastName());
            assertThat(images).containsEntry("CUST-ADDR-LINE-1", record.custAddrLine1());
            assertThat(images).containsEntry("CUST-ADDR-LINE-2", record.custAddrLine2());
            assertThat(images).containsEntry("CUST-ADDR-LINE-3", record.custAddrLine3());
            assertThat(images).containsEntry("CUST-ADDR-STATE-CD", record.custAddrStateCd());
            assertThat(images).containsEntry("CUST-ADDR-COUNTRY-CD", record.custAddrCountryCd());
            assertThat(images).containsEntry("CUST-ADDR-ZIP", record.custAddrZip());
            assertThat(images).containsEntry("CUST-PHONE-NUM-1", record.custPhoneNum1());
            assertThat(images).containsEntry("CUST-PHONE-NUM-2", record.custPhoneNum2());
            assertThat(images).containsEntry("CUST-SSN", record.custSsn());
            assertThat(images).containsEntry("CUST-GOVT-ISSUED-ID", record.custGovtIssuedId());
            assertThat(images).containsEntry("CUST-DOB-YYYYMMDD", record.custDobYyyymmdd());
            assertThat(images).containsEntry("CUST-EFT-ACCOUNT-ID", record.custEftAccountId());
            assertThat(images)
                    .containsEntry("CUST-PRI-CARD-HOLDER-IND", record.custPriCardHolderInd());
            assertThat(images).containsEntry("CUST-FICO-CREDIT-SCORE", record.custFicoCreditScore());

            assertThat(record.spanImage(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, ASCII))
                    .isEqualTo(record.custDobYyyymmdd());
            assertThat(record.spanBytes(Stm03CustomerRecord.CUST_SSN, ASCII))
                    .isEqualTo(record.custSsn().getBytes(ASCII));
        }
    }

    @Nested
    @DisplayName("8. Boundaries - the first byte, the last byte, and the seam at 332")
    class Boundaries {

        // CUSTREC.cpy declares NO OCCURS table, so there is no one-based subscript here to convert to
        // a zero-based Java index. Gate G33's obligation is the same off-by-one hazard in its other
        // form - the byte index - and it is discharged on that: the first addressable byte, the last
        // addressable byte, the byte just past the end, and the seam between two adjacent spans.

        @Test
        @DisplayName("the first byte is offset 0 and is the leading digit of CUST-ID")
        void theFirstByteIsOffsetZero() {
            // COBOL reference modification is one-based - CUSTOMER-RECORD (1:1) is this byte - and Java
            // is zero-based. Offset 0 is that same byte, and it belongs to the key.
            String image = row1().groupImage(ASCII);

            assertThat(image.charAt(0)).isEqualTo('0');
            assertThat(image.substring(0, 1)).isEqualTo("0");
            assertThat(Stm03CustomerRecord.CUST_ID_OFFSET).isZero();
            assertThat(row1().spanImage(Stm03CustomerRecord.CUST_ID, ASCII).charAt(0))
                    .isEqualTo(image.charAt(0));

            // A record whose key starts with a non-zero digit proves the byte is really being read
            // rather than coinciding with a pad character.
            String nineHundred = "900000001" + ROW1_IMAGE.substring(9);
            assertThat(Stm03CustomerRecord.decode(nineHundred, ASCII).groupImage(ASCII).charAt(0))
                    .isEqualTo('9');
        }

        @Test
        @DisplayName("the last byte is offset 499 and is the final byte of FILLER")
        void theLastByteIsOffsetFourNineNine() {
            String image = row1().groupImage(ASCII);

            assertThat(image).hasSize(500);
            assertThat(image.charAt(499)).isEqualTo(' ');
            assertThat(image.substring(499)).isEqualTo(" ");
            // 499 is inside FILLER, which runs from 332 for 168 bytes: 332 + 168 - 1 = 499.
            assertThat(Stm03CustomerRecord.FILLER_OFFSET + Stm03CustomerRecord.FILLER_LENGTH - 1)
                    .isEqualTo(499);
            // And the byte is genuinely addressed rather than assumed: set it to a sentinel and read
            // it back through the FILLER descriptor.
            String sentinelTail = ROW1_IMAGE.substring(0, 499) + "#";
            assertThat(sentinelTail).hasSize(500);
            assertThat(Stm03CustomerRecord.decode(sentinelTail, ASCII).groupImage(ASCII).charAt(499))
                    .isEqualTo(' ');
            assertThat(Stm03CustomerRecord.decode(sentinelTail, ASCII)
                    .spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .as("FILLER is not a component, so it is re-emitted as the spaces the copybook "
                            + "declares it to hold rather than carrying the sentinel back out")
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("offset 500 is past the end and is refused")
        void offsetFiveHundredIsPastTheEnd() {
            String image = row1().groupImage(ASCII);

            assertThatExceptionOfType(StringIndexOutOfBoundsException.class)
                    .isThrownBy(() -> image.charAt(500));
            // And a descriptor that reaches past the declared width cannot even be placed in the
            // layout, so the record can never be asked to read it.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(500,
                            FieldSpan.alphanumeric("CUST-OVERRUN", 500, 1)));
        }

        @Test
        @DisplayName("the seam at 332: writing FICO leaves byte 332 alone and FILLER leaves 331 alone")
        void theSeamAtThreeThirtyTwo() {
            // CUST-FICO-CREDIT-SCORE occupies 329, 330 and 331 - it ENDS at 332 exclusive - and FILLER
            // begins at 332. An off-by-one in either direction shows up here and nowhere else, because
            // this is the only place in the record where a numeric span abuts the trailing filler.
            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_OFFSET
                    + Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_LENGTH).isEqualTo(332);
            assertThat(Stm03CustomerRecord.FILLER_OFFSET).isEqualTo(332);

            Stm03CustomerRecord withNines = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, "999");
            String image = withNines.groupImage(ASCII);

            assertThat(image.substring(329, 332))
                    .as("the score fills its own three bytes")
                    .isEqualTo("999");
            assertThat(image.charAt(331)).as("byte 331 is the last byte of the score").isEqualTo('9');
            assertThat(image.charAt(332))
                    .as("byte 332 is the first byte of FILLER and the score must not reach it")
                    .isEqualTo(' ');
            assertThat(image.charAt(328))
                    .as("byte 328 is CUST-PRI-CARD-HOLDER-IND and the score must not reach back into it")
                    .isEqualTo('Y');
            assertThat(withNines.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
            assertThat(withNines.custPriCardHolderInd()).isEqualTo("Y");
        }

        @Test
        @DisplayName("a span descriptor rejects a zero or negative width at declaration time")
        void aSpanRejectsAnImpossibleWidth() {
            // The other side of every width guard, so both branches of each are driven.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-EMPTY", 0, 0));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-NEGATIVE", -1, 9));
        }
    }

    @Nested
    @DisplayName("9. The CUSTREC divergence - one field name, and the whole reason this type exists")
    class TheCustrecDivergence {

        // app/cpy/CUSTREC.cpy and app/cpy/CVCUS01Y.cpy were diffed in full. They agree on absolutely
        // everything that a byte layout consists of: the (RECLN 500) header comment, the
        // 01 CUSTOMER-RECORD group name, all nineteen PICTURE clauses, all nineteen offsets, the
        // 500-byte total, and even the Ver: CardDemo_v1.0-15-g27d6c6f-68 footer. They differ in exactly
        // two respects - the TAB indentation recorded in this class's own comment, and ONE FIELD NAME:
        //
        //     CUSTREC.cpy   line 19:  05  CUST-DOB-YYYYMMDD     PIC X(10).
        //     CVCUS01Y.cpy  line 19:  05  CUST-DOB-YYYY-MM-DD   PIC X(10).
        //
        // That single name is why customer/model/CustomerRecord and this type both exist and are kept
        // PERMANENTLY DISTINCT. Parity verification compares records field by field, KEYED BY FIELD
        // NAME, so collapsing the two would silently drop a name the comparison depends on. The two are
        // never merged, never substituted for one another, never bridged by a converter, and neither
        // field is ever renamed to match the other.
        //
        // This test deliberately does NOT import customer.model.CustomerRecord. That type lives in a
        // different package which JaCoCo measures independently, so importing it would couple the two
        // packages and their coverage. The hyphenated spelling appears here only as a string literal, in
        // the negative assertion - which is the assertion that actually catches an accidental merge.

        @Test
        @DisplayName("the date-of-birth span is named CUST-DOB-YYYYMMDD, without hyphens")
        void theDobSpanCarriesTheCustrecName() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.name())
                    .as("CUSTREC.cpy line 19 spells it without hyphens")
                    .isEqualTo("CUST-DOB-YYYYMMDD");
            assertThat(row1().fieldImages(ASCII))
                    .as("and the field map, which is what parity diffing keys on, agrees")
                    .containsKey("CUST-DOB-YYYYMMDD");
        }

        @Test
        @DisplayName("and it is NOT named CUST-DOB-YYYY-MM-DD, which is CVCUS01Y's spelling")
        void theDobSpanIsNotTheCvcus01yName() {
            // The negative direction, and the one that matters: a merge of the two types, or a
            // well-meaning "correction" of the name, would leave the positive assertion above passing
            // if it were written loosely, but cannot survive this one.
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.name())
                    .isNotEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(row1().fieldImages(ASCII).keySet())
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
            assertThat(row1().toString())
                    .as("even the diagnostic rendering must not print the other copybook's spelling")
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
        }

        @Test
        @DisplayName("the span sits at offset 308 for 10 bytes, exactly as it does in the twin copybook")
        void theDobSpanGeometryIsIdenticalToTheTwin() {
            // The layouts are identical, which is the point: only the NAME distinguishes them. If the
            // offset or width differed, the two types would be different records rather than the same
            // record under two names, and the duplication would need no defending.
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.offset()).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.length()).isEqualTo(10);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.kind())
                    .as("PIC X(10) - character data, not a date type")
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_OFFSET).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the record component is named custDobYyyymmdd, so a rename cannot slip through")
        void theRecordComponentCarriesTheCustrecName() {
            // The Java-side half of the same guard. The descriptor's name string and the component's
            // identifier are two independent places the spelling has to hold, and a refactoring tool
            // would rename the identifier without touching the string.
            List<String> componentNames = new ArrayList<>();
            for (RecordComponent component : Stm03CustomerRecord.class.getRecordComponents()) {
                componentNames.add(component.getName());
            }

            assertThat(componentNames)
                    .hasSize(18)
                    .contains("custDobYyyymmdd")
                    .doesNotContain("custDobYyyyMmDd");
            assertThat(componentNames)
                    .as("the eighteen referable items in copybook order; FILLER is not a component")
                    .containsExactly("custId",
                            "custFirstName",
                            "custMiddleName",
                            "custLastName",
                            "custAddrLine1",
                            "custAddrLine2",
                            "custAddrLine3",
                            "custAddrStateCd",
                            "custAddrCountryCd",
                            "custAddrZip",
                            "custPhoneNum1",
                            "custPhoneNum2",
                            "custSsn",
                            "custGovtIssuedId",
                            "custDobYyyymmdd",
                            "custEftAccountId",
                            "custPriCardHolderInd",
                            "custFicoCreditScore");
        }

        @Test
        @DisplayName("the ten stored bytes stay hyphenated even though the field name is not")
        void theStoredValueStaysHyphenated() {
            // The oddity worth preserving rather than tidying: the NAME is un-hyphenated and the DATA
            // is hyphenated. Normalising the value to 19610608 to match the name would change what the
            // record stores, which is a behaviour change, and renaming the field to match the value
            // would erase the divergence this whole class defends.
            assertThat(row1().custDobYyyymmdd()).isEqualTo("1961-06-08").hasSize(10);
            assertThat(row1().custDobYyyymmdd()).contains("-").isNotEqualTo("19610608");
            assertThat(row1().spanImage(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, ASCII))
                    .isEqualTo("1961-06-08");
            assertThat(row1().groupImage(ASCII).substring(308, 318)).isEqualTo("1961-06-08");
        }

        @Test
        @DisplayName("the group name collides with the twin's, and that is expected, not a defect")
        void theGroupNameCollisionIsExpected() {
            // Both copybooks declare 01 CUSTOMER-RECORD, so the group names are identical. That
            // collision is precisely why the FIELD name is the only discriminator, and it is not
            // grounds for merging the types. This type exposes the group name in its documentation
            // rather than as a constant - there is no group-name constant to assert - so the identity
            // asserted here is the one it does expose: a 500-byte layout of nineteen spans whose
            // eighteen referable names are CUSTREC's, differing from CVCUS01Y's at exactly one entry.
            assertThat(Stm03CustomerRecord.LAYOUT.recordLength()).isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.spans()).hasSize(19);

            List<String> referableNames = new ArrayList<>();
            for (FieldSpan span : Stm03CustomerRecord.LAYOUT.spans()) {
                if (!span.kind().filler()) {
                    referableNames.add(span.name());
                }
            }

            assertThat(referableNames).hasSize(18);
            assertThat(referableNames).containsExactly("CUST-ID",
                    "CUST-FIRST-NAME",
                    "CUST-MIDDLE-NAME",
                    "CUST-LAST-NAME",
                    "CUST-ADDR-LINE-1",
                    "CUST-ADDR-LINE-2",
                    "CUST-ADDR-LINE-3",
                    "CUST-ADDR-STATE-CD",
                    "CUST-ADDR-COUNTRY-CD",
                    "CUST-ADDR-ZIP",
                    "CUST-PHONE-NUM-1",
                    "CUST-PHONE-NUM-2",
                    "CUST-SSN",
                    "CUST-GOVT-ISSUED-ID",
                    "CUST-DOB-YYYYMMDD",
                    "CUST-EFT-ACCOUNT-ID",
                    "CUST-PRI-CARD-HOLDER-IND",
                    "CUST-FICO-CREDIT-SCORE");
            assertThat(referableNames)
                    .as("seventeen of these eighteen names are also CVCUS01Y's; the eighteenth is the "
                            + "divergence")
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
        }
    }

    @Nested
    @DisplayName("10. The 1000-byte arrival - decode takes the leading 500 and nothing else")
    class TheThousandByteArrival {

        // This is how a CUSTOMER-RECORD actually reaches this type in the statement flow, and it is the
        // reason decode tolerates a wrong-length span instead of refusing one.
        //
        // CBSTM03B is the entire data-access layer of the statement job: CBSTM03A declares only its two
        // outputs (STMTFILE, HTMLFILE) and all four inputs live in CBSTM03B, which CBSTM03A calls
        // thirteen times. CBSTM03B.CBL:190 reads the 500-byte CUSTFILE record INTO LK-M03B-FLDT, which
        // is declared PIC X(1000) at CBSTM03B.CBL:112, so the caller gets back a 1000-byte span.
        // CBSTM03A.CBL:388 then performs
        //
        //     MOVE WS-M03B-FLDT TO CUSTOMER-RECORD
        //
        // and a COBOL alphanumeric MOVE fills its receiver from the left and discards the overflow, so
        // the surviving bytes are the leading 500. CBSTM03A.CBL:376 sets MOVE SPACES TO WS-M03B-FLDT
        // before each read (and :400 before the ACCTFILE read), which is why the trailing FILLER arrives
        // blank rather than holding whatever the previous call left there.
        //
        // A SOURCE ODDITY, RECORDED AND DELIBERATELY NOT ACTED ON: CBSTM03B.CBL:72 declares
        // FD-CUST-ID PIC X(09) - ALPHANUMERIC - while the copybook declares CUST-ID PIC 9(09) - NUMERIC.
        // CBSTM03B moves the key in as characters at :189, MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO
        // FD-CUST-ID, so the nine-byte key span is byte-identical either way and nothing observable
        // turns on the difference. The COPYBOOK picture is authoritative for the Java type, and the
        // discrepancy is recorded here rather than "fixed" in either direction.

        @Test
        @DisplayName("a 1000-byte span decodes to the leading 500, and the tail 500 vanish")
        void aThousandByteSpanKeepsOnlyTheLeadingFiveHundred() {
            // The tail is a non-space sentinel precisely so that a leak would be unmistakable: spaces
            // would be indistinguishable from the FILLER that is supposed to be there.
            String sentinelTail = "#".repeat(500);
            String thousandBytes = ROW1_IMAGE + sentinelTail;
            assertThat(thousandBytes).hasSize(1000);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(thousandBytes, ASCII);

            // Every field is exactly what the 500-byte image alone would have produced.
            assertThat(record).isEqualTo(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII));
            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFicoCreditScore()).isEqualTo("274");
            assertThat(record.custDobYyyymmdd()).isEqualTo("1961-06-08");
            // And no sentinel byte survives anywhere - not in the image, not in the bytes, not in the
            // trailing FILLER that sits closest to the truncation point.
            assertThat(record.groupImage(ASCII)).hasSize(500).doesNotContain("#");
            assertThat(new String(record.encode(ASCII), ASCII)).hasSize(500).doesNotContain("#");
            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("the same 1000-byte arrival works from bytes, under both code pages")
        void theSameArrivalWorksFromBytes() {
            String thousandBytes = ROW1_IMAGE + "#".repeat(500);

            byte[] ascii = thousandBytes.getBytes(ASCII);
            assertThat(ascii).hasSize(1000);
            assertThat(Stm03CustomerRecord.decode(ascii, ASCII).encode(ASCII))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE.getBytes(ASCII));

            byte[] ebcdic = thousandBytes.getBytes(EBCDIC);
            assertThat(ebcdic).hasSize(1000);
            assertThat(Stm03CustomerRecord.decode(ebcdic, EBCDIC).encode(EBCDIC))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE.getBytes(EBCDIC));
        }

        @Test
        @DisplayName("an exactly-500-byte span is the boundary case and passes through untouched")
        void anExactlyFiveHundredByteSpanPassesThrough() {
            // The equal-length branch of the move rule: no padding and no truncation applies.
            assertThat(ROW1_IMAGE).hasSize(500);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
            assertThat(record.encode(ASCII)).isEqualTo(ROW1_IMAGE.getBytes(ASCII));
        }

        @Test
        @DisplayName("a 501-byte span loses exactly its last byte")
        void aFiveHundredAndOneByteSpanLosesItsLastByte() {
            // One past the boundary, which is the smallest possible over-long span and therefore the
            // case an off-by-one in the truncation would break first.
            String oneTooMany = ROW1_IMAGE + "#";
            assertThat(oneTooMany).hasSize(501);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(oneTooMany, ASCII);

            assertThat(record.groupImage(ASCII)).hasSize(500).isEqualTo(ROW1_IMAGE);
            assertThat(record.groupImage(ASCII)).doesNotContain("#");
            assertThat(record).isEqualTo(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII));
        }

        @Test
        @DisplayName("a 499-byte span is right-space-padded to 500, which is the declared contract")
        void aFourHundredAndNinetyNineByteSpanIsPadded() {
            // The declared behaviour, read from decode's own contract rather than guessed at: the same
            // alphanumeric MOVE that truncates an over-long sending field PADS a short one on the
            // right. Refusing a short span would refuse the very MOVE that populates this record.
            //
            // No unchecked ArrayIndexOutOfBoundsException or StringIndexOutOfBoundsException escapes
            // here - the span is widened, deliberately and in one place.
            String oneTooFew = ROW1_IMAGE.substring(0, 499);
            assertThat(oneTooFew).hasSize(499);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(oneTooFew, ASCII);

            assertThat(record.groupImage(ASCII))
                    .hasSize(500)
                    .as("the missing byte is supplied as a space, restoring the full FILLER")
                    .isEqualTo(ROW1_IMAGE);
            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("a nine-byte span carrying only a key is padded out to a whole blank record")
        void aKeyOnlySpanIsPaddedToAWholeRecord() {
            // A much shorter span, so the padding branch is driven well away from the boundary too.
            Stm03CustomerRecord record = Stm03CustomerRecord.decode("000000001", ASCII);

            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(record.custDobYyyymmdd()).isEqualTo(" ".repeat(10));
            assertThat(record.encode(ASCII)).hasSize(500);
            assertThat(record.groupImage(ASCII))
                    .isEqualTo("000000001" + " ".repeat(491));
        }

        @Test
        @DisplayName("an empty span decodes to a wholly blank 500-byte record")
        void anEmptySpanDecodesToABlankRecord() {
            // The extreme of the same branch: nothing sent, so every byte is padding.
            Stm03CustomerRecord fromEmptyString = Stm03CustomerRecord.decode("", ASCII);
            Stm03CustomerRecord fromEmptyBytes = Stm03CustomerRecord.decode(new byte[0], ASCII);

            assertThat(fromEmptyString.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThat(fromEmptyBytes.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThat(fromEmptyString).isEqualTo(fromEmptyBytes);
            assertThat(fromEmptyString.encode(ASCII)).hasSize(500);
        }

        @Test
        @DisplayName("a null span is refused, and the message points at blank(Charset) instead")
        void aNullSpanIsRefused() {
            // The fail side of the null guard on each of the three parameters, so both sides of all
            // three are driven.
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((String) null, ASCII))
                    .withMessageContaining("blank(Charset)");
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((byte[]) null, ASCII))
                    .withMessageContaining("CUSTOMER-RECORD");
            assertThatNullPointerException()
                    .as("the code page is never derived from the platform, so it cannot be omitted")
                    .isThrownBy(() -> Stm03CustomerRecord.decode(new byte[500], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.blank(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> row1().spanImage(null, ASCII))
                    .withMessageContaining("CUST_DOB_YYYYMMDD");
            assertThatNullPointerException()
                    .isThrownBy(() -> row1().spanBytes(null, ASCII));
        }
    }

    @Nested
    @DisplayName("11. The group-move tolerance - bytes the elementary pictures never saw")
    class GroupMoveTolerance {

        // MOVE WS-M03B-FLDT TO CUSTOMER-RECORD is a GROUP alphanumeric move: it ignores the elementary
        // PICTURE clauses underneath and copies bytes. A blank CUSTFILE record therefore arrives as 500
        // spaces, numeric spans included. Refusing such an image at construction would refuse a record
        // the legacy program handles perfectly well, so a non-digit image in a numeric span is RECEIVED
        // as bytes - and the numeric VIEWS are where the complaint belongs, because that is where a
        // caller asks for a number rather than for storage.

        @Test
        @DisplayName("a 500-space image constructs, because a group MOVE can produce exactly that")
        void anAllSpacesImageConstructs() {
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThat(blankByGroupMove.custId()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custFicoCreditScore()).isEqualTo("   ");
            assertThat(blankByGroupMove.custFirstName()).isEqualTo(" ".repeat(25));
        }

        @Test
        @DisplayName("and it round-trips to the very same 500 bytes, so reading one is not a one-way door")
        void anAllSpacesImageRoundTrips() {
            byte[] spaces = " ".repeat(500).getBytes(ASCII);

            assertThat(Stm03CustomerRecord.decode(spaces, ASCII).encode(ASCII)).isEqualTo(spaces);
        }

        @Test
        @DisplayName("the three numeric views still refuse a blank span, naming the field")
        void theNumericViewsRefuseABlankSpan() {
            // The other side of the tolerance, and the branch that makes it safe: storage may hold
            // anything, but a request for a NUMBER over a blank span is an error and is reported as one
            // rather than being read as zero. A blank key silently becoming customer 0 is exactly the
            // kind of plausible-looking record that is hardest to trace back later.
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custIdValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custSsnValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custFicoCreditScoreValue(ASCII));
        }

        @Test
        @DisplayName("an initialised record's numeric views read zero, because its spans hold digits")
        void anInitialisedRecordsNumericViewsReadZero() {
            // The pass side of the very same guard, which is what makes it a branch rather than a
            // one-way check. blank(Charset) zero-fills the numeric spans, so all three views succeed.
            Stm03CustomerRecord blank = Stm03CustomerRecord.blank(ASCII);

            assertThat(blank.custIdValue(ASCII)).isZero();
            assertThat(blank.custSsnValue(ASCII)).isZero();
            assertThat(blank.custFicoCreditScoreValue(ASCII)).isZero();
        }

        @Test
        @DisplayName("an empty numeric image blanks rather than zero-filling, and the two stay distinct")
        void anEmptyNumericImageBlanksRatherThanZeroFilling() {
            // An empty string is not a run of digits, so the alphanumeric rule applies and the span
            // blanks. It must NOT zero-fill: "000000000" is a real customer whose id is zero, while
            // spaces are the absence of an id, and only a group MOVE produces either. Keeping them
            // distinct is what lets the numeric view refuse the blank while accepting the zero.
            Stm03CustomerRecord empty = new Stm03CustomerRecord("",
                    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");

            assertThat(empty.custId()).isEqualTo(" ".repeat(9));
            assertThat(empty.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(empty.custFicoCreditScore()).isEqualTo("   ");
            assertThat(empty.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThatIllegalArgumentException().isThrownBy(() -> empty.custIdValue(ASCII));
            // Whereas an explicitly zero-filled key is a value, and reads back as one.
            assertThat(Stm03CustomerRecord.decode("000000000" + " ".repeat(491), ASCII)
                    .custIdValue(ASCII)).isZero();
        }

        @Test
        @DisplayName("a partly numeric span is received as bytes, not silently renumbered")
        void aPartlyNumericSpanIsReceivedAsBytes() {
            // Not all digits, so the numeric MOVE does not apply and the bytes survive untouched.
            // Left-truncating this would rewrite storage the program can still read back out.
            Stm03CustomerRecord record = new Stm03CustomerRecord("12 45678X",
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId()).isEqualTo("12 45678X").hasSize(9);
            assertThat(record.groupImage(ASCII).substring(0, 9)).isEqualTo("12 45678X");
            assertThatIllegalArgumentException().isThrownBy(() -> record.custIdValue(ASCII));
            assertThat(record.custSsnValue(ASCII))
                    .as("and the neighbouring numeric field is unaffected by it")
                    .isEqualTo(20973888);
        }

        @Test
        @DisplayName("an over-wide non-digit numeric image truncates on the RIGHT, as PIC X would")
        void anOverWideNonDigitNumericImageTruncatesRight() {
            // The branch pairing worth pinning down: which move rule applies is decided by whether the
            // image is all digits, so the SAME field truncates on the left for "1234567890" and on the
            // right for a non-digit image of the same width. Both directions are asserted so neither
            // arm of that decision is left unexercised.
            Stm03CustomerRecord record = new Stm03CustomerRecord("12 45678XYZ",
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId())
                    .as("eleven characters, none of them a digit run, so the leading nine survive")
                    .isEqualTo("12 45678X")
                    .hasSize(9);
        }
    }
}
