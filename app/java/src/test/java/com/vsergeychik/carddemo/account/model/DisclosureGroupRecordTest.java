package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DisclosureGroupRecord}, the Java form of {@code app/cpy/CVTRA02Y.cpy}
 * &mdash; the disclosure group record, exactly <strong>50 bytes</strong>, consumed by exactly one
 * class: the interest calculator translated from {@code app/cbl/CBACT04C.cbl}.
 *
 * <h2>Governing rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line, <em>"No user rules provided."</em> &mdash; that
 * is the entire document, so there is nothing further to page through. Its absence is stated here
 * explicitly and is treated as no licence whatsoever to lower the bar: the enterprise best-practice
 * substitutes of the plan bind in its place, and the ones that shape this file are named where they
 * apply. In summary they are: the closed, exactly-versioned dependency set (only JUnit&nbsp;5 Jupiter
 * and AssertJ, both already supplied by {@code spring-boot-starter-test}, and nothing added to
 * {@code app/java/pom.xml}); reference inputs held immutable (the fixture is read from its
 * <em>classpath copy</em>, never from {@code app/data/ASCII} in place); no silent scope creep (the
 * {@code FILLER} discrepancy below is documented and asserted from both sides rather than quietly
 * resolved); defects and dead code preserved rather than tidied; explicit over implicit (the charset,
 * the scale and the rounding mode are named at every boundary and there is not one wildcard import);
 * no static mutable state; hand-written, reviewable slicing rather than any third-party copybook
 * parser; and environmental limits documented rather than absorbed.
 *
 * <h2>Provenance: every expectation here is statically derived</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong>, so no expected value
 * below was ever captured from a live run. Each one is instead traceable to a specific artefact in
 * this checkout, and the test that asserts it names the artefact:
 * <ul>
 *   <li>a {@code PICTURE} clause or item name in {@code app/cpy/CVTRA02Y.cpy};</li>
 *   <li>a statement in {@code app/cbl/CBACT04C.cbl}, cited by line;</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy}, for the 17-versus-16 byte key contrast;</li>
 *   <li>or a byte measured directly out of {@code app/data/ASCII/discgrp.txt}, whose byte-identical
 *       copy ships on the test classpath as {@value #FIXTURE}.</li>
 * </ul>
 * That is a weaker oracle than a captured baseline in exactly one respect: a static derivation can
 * encode a misreading. Three things narrow that risk here. Widths and offsets are taken from the
 * copybook's own arithmetic rather than from prose; every round-trip case is seeded from real fixture
 * bytes rather than invented data; and the two places where a plausible-looking implementation would
 * diverge silently &mdash; the key width and the zero predicate &mdash; are each asserted from more
 * than one direction.
 *
 * <h2>The layout under test, hand-summed</h2>
 *
 * <p>{@code app/cpy/CVTRA02Y.cpy} is 13 lines long. Line 2 declares
 * {@code Data-structure for disclosure group (RECLN = 50)} and {@code 01 DIS-GROUP-RECORD.} is at
 * line 4:
 *
 * <table>
 *   <caption>{@code CVTRA02Y}, with 0-based Java offsets</caption>
 *   <tr><th>Copybook line</th><th>Item</th><th>PICTURE</th><th>Bytes</th><th>Offset</th></tr>
 *   <tr><td>L5</td><td>DIS-GROUP-KEY (group)</td><td>&mdash;</td><td>16</td><td>0&ndash;15</td></tr>
 *   <tr><td>L6</td><td>DIS-ACCT-GROUP-ID</td><td>X(10)</td><td>10</td><td>0</td></tr>
 *   <tr><td>L7</td><td>DIS-TRAN-TYPE-CD</td><td>X(02)</td><td>2</td><td>10</td></tr>
 *   <tr><td>L8</td><td>DIS-TRAN-CAT-CD</td><td>9(04)</td><td>4</td><td>12</td></tr>
 *   <tr><td>L9</td><td>DIS-INT-RATE</td><td>S9(04)V99</td><td>6</td><td><strong>16</strong></td></tr>
 *   <tr><td>L10</td><td>FILLER</td><td>X(28)</td><td>28</td><td>22</td></tr>
 * </table>
 *
 * <p>{@code 10 + 2 + 4 + 6 + 28 = 50}. {@code S9(04)V99} is {@code 4 + 2 = 6} bytes and reserves
 * <strong>no</strong> byte for its sign, which is overpunched into the trailing byte.
 *
 * <h2>What this file owns, and what it deliberately does not</h2>
 *
 * <p>A sibling test round-trips the same fixture rows through {@code AccountRepository} and asserts
 * that {@code DIS-INT-RATE} decodes from offset 16 &mdash; but it does so <em>through the
 * repository</em>. Nothing here re-tests repository behaviour and nothing here mocks one; no
 * collaborator is stubbed at all, because the class under test is a value type over a byte array.
 * What this file owns is the record type's own branch surface: the declared geometry and the layout
 * self-check including its failure path, the field typing and its rejection paths, the key sub-span,
 * the raw span accessors, the {@code REDEFINES} round trip, {@code equals}/{@code hashCode}, and
 * above all the {@link BigDecimal} zero predicate.
 *
 * <p>It lives in {@code com.vsergeychik.carddemo.account.model} rather than the parent test package
 * because the coverage gate in {@code app/java/pom.xml} declares its {@code BRANCH} rule at
 * {@code PACKAGE} granularity as well as {@code BUNDLE}, so this package is measured independently
 * and cannot be covered from anywhere else.
 *
 * <h2>Conventions</h2>
 *
 * <p>Plain JUnit&nbsp;5 with AssertJ and no Spring context, because nothing under test needs one. The
 * class under test is a value type over a byte array, so nothing is mocked and Mockito is not used at
 * all. Both code pages are named explicitly and neither is ever defaulted: {@code US-ASCII} for the
 * text fixtures, {@code IBM037} for the EBCDIC datasets. Expected images are built by literal
 * concatenation and plain index arithmetic so a reviewer can check each one against the copybook line
 * by line. There is no wildcard import, not even a static one; no {@code double} and no
 * {@code float}; no rounding mode other than {@link RoundingMode#DOWN}; no mutable static state; and
 * no disabled or skipped test.
 *
 * <p>Two of the obligations above are compile-time properties of this file rather than things a test
 * can assert at run time, and are called out so nothing is claimed that is not actually enforced. The
 * absence of wildcard imports leaves no trace in the compiled class - imports are resolved and
 * discarded by the compiler - so it is discharged by the import block above and confirmed by
 * inspection, not by a test method. The same applies to the absence of any rounding mode other than
 * {@link RoundingMode#DOWN} in this file's own text; what a test <em>can</em> and does assert is that
 * the module's single rounding constant is {@code DOWN}, and that values where truncation and rounding
 * disagree come out truncated. Everything else below is asserted by an executing test.
 */
@DisplayName("DisclosureGroupRecord - CVTRA02Y DIS-GROUP-RECORD, 50 bytes, 16-byte key, rate at 16")
class DisclosureGroupRecordTest {

    /** The text fixtures are ASCII. Named explicitly at every byte boundary, never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The EBCDIC code page of the binary datasets under {@code app/data/EBCDIC}, used here only to
     * prove that the charset is an explicit parameter and that it is not part of record identity.
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The shipped fixture on the test classpath. Verified byte-identical to the read-only
     * {@code app/data/ASCII/discgrp.txt}, which is never opened in place at runtime.
     */
    private static final String FIXTURE = "/fixtures/discgrp.txt";

    /** Measured: {@code awk 'END{print NR}' app/data/ASCII/discgrp.txt} prints 51. */
    private static final int FIXTURE_ROW_COUNT = 51;

    /**
     * Measured: {@code awk '{print length($0)}' ... | sort -u} prints the single value 50, so the
     * fixture matches the copybook exactly and needs no padding or normalisation. The only two
     * right-pad normalisations in this project - {@code cardxref} 36 to 50 and {@code USRSEC} 57 to
     * 80 - belong to the parity differ, and neither applies to this record.
     */
    private static final int FIXTURE_ROW_WIDTH = 50;

    /** {@code DIS-ACCT-GROUP-ID} of fixture rows 1-17, measured. Ten characters, no padding needed. */
    private static final String GROUP_ID_A = "A000000000";

    /**
     * {@code DIS-ACCT-GROUP-ID} of fixture rows 18-34, measured: the seven-character literal of
     * {@code app/cbl/CBACT04C.cbl:L437} right-space-padded by COBOL into its {@code PIC X(10)}
     * receiver.
     */
    private static final String GROUP_ID_DEFAULT = "DEFAULT   ";

    /** {@code DIS-ACCT-GROUP-ID} of fixture rows 35-51, measured. Right-space-padded to 10. */
    private static final String GROUP_ID_ZEROAPR = "ZEROAPR   ";

    /** Measured: each of the three group ids occupies exactly 17 of the 51 rows. */
    private static final int ROWS_PER_GROUP_ID = 17;

    /**
     * The measured content of the trailing {@code FILLER X(28)} on every one of the 51 fixture rows:
     * 28 ASCII <strong>zeros</strong>. See {@link DefaultLiteralAndFiller} for why this is asserted
     * alongside, and not instead of, the space-filled fresh-construction default.
     */
    private static final String FIXTURE_FILLER = "0".repeat(DisclosureGroupRecord.FILLER_LENGTH);

    /**
     * Fixture row 1, transcribed field by field from
     * {@code awk 'NR==1{...}' app/data/ASCII/discgrp.txt}, which prints
     * {@code [A000000000][01][0001][00150{][0000000000000000000000000000]}. Written as a
     * concatenation rather than one 50-character literal so each field's width is visible.
     */
    private static final String ROW_1 = GROUP_ID_A + "01" + "0001" + "00150{" + FIXTURE_FILLER;

    /**
     * The stored zoned image of {@code DIS-INT-RATE} on fixture row 1. The trailing {@code &#123;} is
     * the zone-C overpunch for "positive, low-order digit 0", so the digits are {@code 001500} and
     * the value is {@code +15.00} at scale 2.
     */
    private static final String ROW_1_RATE_IMAGE = "00150{";

    /**
     * The six bytes a <strong>17</strong>-byte key would read as the rate, measured with
     * {@code awk 'NR==1{print substr($0,18,6)}'}. Not merely a different value: not a decodable
     * zoned image at all. See {@link SixteenByteKeyNotSeventeen}.
     */
    private static final String ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY = "0150{0";

    /** The three distinct rate values in the fixture, measured across all 51 rows. */
    private static final String RATE_ZERO_IMAGE = "00000{";

    /** Measured: 30 of the 51 rows carry this image, decoding to {@code 0.00}. */
    private static final int ROWS_WITH_A_ZERO_RATE = 30;

    /** Measured: 15 of the 51 rows carry {@code 00150&#123;}, decoding to {@code 15.00}. */
    private static final int ROWS_WITH_A_15_PERCENT_RATE = 15;

    /** Measured: 6 of the 51 rows carry {@code 00250&#123;}, decoding to {@code 25.00}. */
    private static final int ROWS_WITH_A_25_PERCENT_RATE = 6;

    /**
     * Reads the shipped fixture as 50-character rows, exactly as stored, with nothing trimmed and
     * nothing normalised.
     *
     * <p>The charset is named rather than defaulted, which is the whole point: fixed-width mainframe
     * data is bytes in a specific code page and a platform default is the classic silent corrupter of
     * it. The rows are returned from a local list, so no state is shared between tests.
     *
     * @return the 51 rows in file order
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = DisclosureGroupRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the DISCGRP fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * A record decoded from fixture row 1, the canonical sample: group {@code A000000000}, type
     * {@code 01}, category {@code 0001}, rate {@code 15.00}.
     *
     * @return a freshly decoded record; never shared between tests
     */
    private static DisclosureGroupRecord row1() {
        return DisclosureGroupRecord.decode(ROW_1, ASCII);
    }

    /**
     * Builds a 50-character row image from its five field images, so every expected row in this file
     * is assembled the same auditable way.
     *
     * @param groupId  the {@code X(10)} image
     * @param typeCd   the {@code X(02)} image
     * @param catCd    the {@code 9(04)} image
     * @param rate     the {@code S9(04)V99} image, 6 characters including the sign overpunch
     * @param filler   the {@code X(28)} image
     * @return the concatenated 50-character row
     */
    private static String row(String groupId, String typeCd, String catCd, String rate,
            String filler) {
        String image = groupId + typeCd + catCd + rate + filler;
        assertThat(image).as("a hand-built row must be exactly the declared record width")
                .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        return image;
    }

    /**
     * A record whose four data items come from fixture row 1 but whose {@code FILLER} is whatever the
     * caller supplies, for isolating the filler from the rest of the record.
     *
     * @param rateImage the 6-character zoned rate image to store in bytes 16 to 21
     * @return the decoded record
     */
    private static DisclosureGroupRecord withRateImage(String rateImage) {
        return DisclosureGroupRecord.decode(
                row(GROUP_ID_A, "01", "0001", rateImage, FIXTURE_FILLER), ASCII);
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic, gates G19 and G21")
    class DeclaredGeometry {

        @Test
        @DisplayName("The record is 50 bytes, and the five declared items sum to exactly that")
        void theRecordIsFiftyBytes() {
            // app/cpy/CVTRA02Y.cpy:L2 - "Data-structure for disclosure group (RECLN = 50)".
            assertThat(DisclosureGroupRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(DisclosureGroupRecord.layout().recordLength()).isEqualTo(50);
            assertThat(new DisclosureGroupRecord(ASCII).recordLength()).isEqualTo(50);

            // The hand sum, written out so it can be checked against L6-L10 without running anything:
            // 10 + 2 + 4 + 6 + 28. The signed field contributes 6, not 7 - see signIsOverpunched().
            int handSum = DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH
                    + DisclosureGroupRecord.DIS_INT_RATE_LENGTH
                    + DisclosureGroupRecord.FILLER_LENGTH;
            assertThat(handSum).isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);

            assertThat(new DisclosureGroupRecord(ASCII).encode())
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            assertThat(new DisclosureGroupRecord(ASCII).encodeToString())
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("DIS-GROUP-KEY spans bytes 0 to 15 and is exactly 16 bytes")
        void theKeyIsSixteenBytesStartingAtZero() {
            // app/cpy/CVTRA02Y.cpy:L5-L8 - the group item is X(10) + X(02) + 9(04).
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_OFFSET).isZero();
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH).isEqualTo(16);
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(16);

            FieldSpan key = DisclosureGroupRecord.layout().span("DIS-GROUP-KEY");
            assertThat(key.offset()).isZero();
            assertThat(key.length()).isEqualTo(16);
            assertThat(key.endOffsetExclusive()).isEqualTo(16);

            // And the decoded key really is 16 characters of the row, not 17.
            assertThat(row1().disGroupKey()).hasSize(16).isEqualTo("A000000000" + "01" + "0001");
            assertThat(row1().disGroupKeyBytes()).hasSize(16);
        }

        @Test
        @DisplayName("DIS-INT-RATE starts at byte 16 - the single offset a 17-byte key gets wrong")
        void theRateStartsAtByteSixteen() {
            // app/cpy/CVTRA02Y.cpy:L9. Stated as its own test because it is the one assertion that
            // distinguishes this record's layout from CVTRA01Y's, and a total-width check cannot:
            // both records sum to 50. See SixteenByteKeyNotSeventeen for the corruption it prevents.
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET).isEqualTo(16);
            assertThat(DisclosureGroupRecord.layout().span("DIS-INT-RATE").offset()).isEqualTo(16);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET)
                    .as("the rate begins immediately after the 16-byte key")
                    .isEqualTo(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);

            // Read straight out of the fixture row at that absolute offset, by plain index arithmetic.
            assertThat(ROW_1.substring(16, 22)).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(row1().disIntRateImage()).isEqualTo(ROW_1_RATE_IMAGE);
        }

        @Test
        @DisplayName("Every item sits at its copybook offset, contiguously from byte 0")
        void everyItemSitsAtItsCopybookOffset() {
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET).isZero();          // L6
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET).isEqualTo(10);      // L7
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET).isEqualTo(12);       // L8
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET).isEqualTo(16);          // L9
            assertThat(DisclosureGroupRecord.FILLER_OFFSET).isEqualTo(22);                // L10

            // Each offset is the previous offset plus the previous width: no gap and no overlap.
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET
                            + DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH);
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET
                            + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET
                            + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_OFFSET
                            + DisclosureGroupRecord.DIS_INT_RATE_LENGTH);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET
                    + DisclosureGroupRecord.FILLER_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("FILLER X(28) occupies bytes 22 to 49 and is a first-class span - gate G21")
        void fillerIsADeclaredSpanNotAnImplicitGap() {
            // app/cpy/CVTRA02Y.cpy:L10. Omitting FILLER would leave the layout 28 bytes short, which
            // is exactly what layoutRejectsADroppedTrailingFiller() proves.
            assertThat(DisclosureGroupRecord.FILLER_OFFSET).isEqualTo(22);
            assertThat(DisclosureGroupRecord.FILLER_LENGTH).isEqualTo(28);
            assertThat(DisclosureGroupRecord.FILLER_NAME).isEqualTo("FILLER");

            assertThat(DisclosureGroupRecord.FILLER_SPAN.offset()).isEqualTo(22);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.length()).isEqualTo(28);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.kind().filler()).isTrue();
            assertThat(DisclosureGroupRecord.FILLER_SPAN.redefinition()).isFalse();

            assertThat(DisclosureGroupRecord.layout().storageSpans())
                    .as("FILLER occupies storage and is therefore one of the storage spans")
                    .contains(DisclosureGroupRecord.FILLER_SPAN);
            assertThat(row1().filler()).hasSize(28);
            assertThat(row1().fillerBytes()).hasSize(28);
        }

        @Test
        @DisplayName("FILLER is not referable, exactly as in COBOL")
        void fillerIsNotReferableByName() {
            assertThat(DisclosureGroupRecord.layout().hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.layout().span("FILLER"))
                    .withMessageContaining("FILLER is not referable");
        }

        @Test
        @DisplayName("The sign of S9(04)V99 is overpunched: 6 bytes, never 7")
        void signIsOverpunched() {
            // app/cpy/CVTRA02Y.cpy:L9. p = 4, s = 2, and the sign occupies no byte of its own. A
            // seventh byte would make the record 51 and be rejected by the layout self-check - see
            // layoutRejectsAPhantomSignByte().
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS).isEqualTo(4);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SCALE).isEqualTo(2);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_LENGTH).isEqualTo(6);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS
                            + DisclosureGroupRecord.DIS_INT_RATE_SCALE);

            // A negative rate still occupies exactly 6 bytes, which is the whole point.
            DisclosureGroupRecord negative = new DisclosureGroupRecord(ASCII);
            negative.disIntRate(new BigDecimal("-15.00"));
            assertThat(negative.disIntRateImage()).hasSize(6).isEqualTo("00150}");
            assertThat(negative.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The layout declares 5 storage spans and 1 overlay, in copybook order")
        void layoutDeclaresFiveStorageSpansAndOneOverlay() {
            RecordLayout layout = DisclosureGroupRecord.layout();

            assertThat(layout.spans()).hasSize(6);
            assertThat(layout.storageSpans())
                    .containsExactly(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                            DisclosureGroupRecord.FILLER_SPAN);
            assertThat(layout.redefinitions())
                    .as("DIS-GROUP-KEY is a group overlay over its own three members")
                    .containsExactly(DisclosureGroupRecord.DIS_GROUP_KEY_SPAN);

            // The storage spans - and only they - sum to the record length. The overlay does not.
            int storageWidth = 0;
            for (FieldSpan span : layout.storageSpans()) {
                storageWidth += span.length();
            }
            assertThat(storageWidth).isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @ParameterizedTest
        @CsvSource({
            "DIS-ACCT-GROUP-ID,  0, 10, ALPHANUMERIC",
            "DIS-TRAN-TYPE-CD,  10,  2, ALPHANUMERIC",
            "DIS-TRAN-CAT-CD,   12,  4, UNSIGNED_NUMERIC",
            "DIS-GROUP-KEY,      0, 16, ALPHANUMERIC",
            "DIS-INT-RATE,      16,  6, SIGNED_SCALED"
        })
        @DisplayName("Every referable span is addressable by its verbatim copybook name")
        void everyReferableSpanIsAddressableByName(String name, int offset, int length,
                PictureKind kind) {
            assertThat(DisclosureGroupRecord.layout().hasSpan(name)).isTrue();

            FieldSpan span = DisclosureGroupRecord.layout().span(name);
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.kind()).isEqualTo(kind);
            assertThat(span.hasInitialValue())
                    .as("CVTRA02Y declares no VALUE clause on any item")
                    .isFalse();
        }

        @Test
        @DisplayName("Names are case-sensitive and no foreign item name resolves")
        void namesAreCaseSensitiveAndForeignNamesDoNotResolve() {
            assertThat(DisclosureGroupRecord.layout().hasSpan("dis-int-rate")).isFalse();
            // TRAN-CAT-BAL belongs to CVTRA01Y, the other 50-byte record. It must not resolve here.
            assertThat(DisclosureGroupRecord.layout().hasSpan("TRAN-CAT-BAL")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.layout().span("TRAN-CAT-BAL"))
                    .withMessageContaining("declares no field named 'TRAN-CAT-BAL'");
        }

        @Test
        @DisplayName("The width self-check accepts the real layout and rejects a dropped FILLER")
        void layoutRejectsADroppedTrailingFiller() {
            // The success path: re-declaring the real layout from its published spans succeeds.
            assertThat(RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                    DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                    DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                    DisclosureGroupRecord.DIS_GROUP_KEY_SPAN,
                    DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                    DisclosureGroupRecord.FILLER_SPAN).recordLength()).isEqualTo(50);

            // The failure path: without FILLER X(28) the declared storage is 22 bytes, not 50.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN))
                    .withMessageContaining("28 byte(s) short");
        }

        @Test
        @DisplayName("The width self-check rejects a phantom sign byte on DIS-INT-RATE")
        void layoutRejectsAPhantomSignByte() {
            // Reserving a 7th byte for the overpunched sign shifts FILLER to 23 and makes the record
            // 51 bytes. Asserted because it is the other way a transcription can go wrong at L9.
            FieldSpan rateWithSignByte = FieldSpan.signedScaled("DIS-INT-RATE",
                    DisclosureGroupRecord.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS + 1,
                    DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            assertThat(rateWithSignByte.length()).isEqualTo(7);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            rateWithSignByte,
                            FieldSpan.filler(23, DisclosureGroupRecord.FILLER_LENGTH)))
                    .withMessageContaining("1 byte(s) too long");
        }

        @Test
        @DisplayName("A row that is not exactly 50 bytes is rejected, never padded or truncated")
        void aRowOfTheWrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1.substring(0, 49), ASCII))
                    .withMessageContaining("must match its declared width exactly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1 + " ", ASCII))
                    .withMessageContaining("must match its declared width exactly");
        }

        @Test
        @DisplayName("The charset is an explicit parameter and is never defaulted")
        void theCharsetIsAlwaysAnExplicitParameter() {
            assertThat(new DisclosureGroupRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new DisclosureGroupRecord(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(DisclosureGroupRecord.decode(ROW_1, ASCII).charset()).isEqualTo(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> new DisclosureGroupRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode((byte[]) null, ASCII));

            // A multi-byte code page cannot hold a zoned DISPLAY field of n digits in n bytes.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DisclosureGroupRecord(StandardCharsets.UTF_16))
                    .withMessageContaining("must encode to exactly one byte");
        }
    }

    /**
     * The 16-versus-17 byte key, which is the most expensive mistake available in this record and the
     * reason gate G25 needs an <em>offset</em> assertion rather than a width assertion.
     *
     * <p>The 17-byte key belongs to a different copybook. {@code app/cpy/CVTRA01Y.cpy:L5-L8} declares
     * {@code TRAN-CAT-KEY} as {@code TRANCAT-ACCT-ID 9(11)} + {@code TRANCAT-TYPE-CD X(02)} +
     * {@code TRANCAT-CD 9(04)} = <strong>17</strong>, and that record's remaining items are
     * {@code TRAN-CAT-BAL S9(09)V99} = 11 and {@code FILLER X(22)} = 22. Its total is
     * {@code 17 + 11 + 22 = 50} - the <em>same</em> 50 this record declares. So a 17-byte disclosure
     * key passes any total-width check while shifting {@code DIS-INT-RATE} one byte to the right and
     * corrupting every rate in the system. Only the offset catches it, which is why
     * {@link DeclaredGeometry#theRateStartsAtByteSixteen()} is stated on its own and why this group
     * exists.
     */
    @Nested
    @DisplayName("The key is 16 bytes, not 17 - CVTRA02Y against CVTRA01Y")
    class SixteenByteKeyNotSeventeen {

        @Test
        @DisplayName("A CVTRA01Y-shaped record also totals 50, so a width check is blind to the slip")
        void aSeventeenByteKeyedRecordAlsoTotalsFifty() {
            // Transcribed from app/cpy/CVTRA01Y.cpy:L5-L10 purely to prove the width check cannot
            // tell the two layouts apart. This layout is correct FOR THAT COPYBOOK; it is simply not
            // this record's, and nothing here is imported into DisclosureGroupRecord.
            RecordLayout tranCatBalShaped = RecordLayout.of(50,
                    FieldSpan.unsignedNumeric("TRANCAT-ACCT-ID", 0, 11),      // CVTRA01Y:L6  9(11)
                    FieldSpan.alphanumeric("TRANCAT-TYPE-CD", 11, 2),         // CVTRA01Y:L7  X(02)
                    FieldSpan.unsignedNumeric("TRANCAT-CD", 13, 4),           // CVTRA01Y:L8  9(04)
                    FieldSpan.redefining("TRAN-CAT-KEY", 0, 17, PictureKind.ALPHANUMERIC), // L5
                    FieldSpan.signedScaled("TRAN-CAT-BAL", 17, 9, 2),         // CVTRA01Y:L9  11 bytes
                    FieldSpan.filler(28, 22));                                // CVTRA01Y:L10 X(22)

            assertThat(tranCatBalShaped.recordLength())
                    .as("both copybooks declare RECLN = 50")
                    .isEqualTo(DisclosureGroupRecord.layout().recordLength());
            assertThat(tranCatBalShaped.span("TRAN-CAT-KEY").length())
                    .as("CVTRA01Y's key genuinely is 17 bytes")
                    .isEqualTo(17);
            assertThat(tranCatBalShaped.span("TRAN-CAT-BAL").offset())
                    .as("and its balance therefore starts at 17, not 16")
                    .isEqualTo(17);

            // The two layouts agree on the total and disagree on where the scaled field begins.
            // That is precisely the defect a width assertion cannot detect.
            assertThat(DisclosureGroupRecord.layout().span("DIS-INT-RATE").offset()).isEqualTo(16);
        }

        @Test
        @DisplayName("A 17-byte DIS-GROUP-KEY overlay is rejected at layout construction")
        void aSeventeenByteKeyOverlayIsRejected() {
            // The mechanical guard: the group overlay is declared after its three elementary members,
            // at which point exactly 16 bytes of storage exist. Claiming 17 reaches past them.
            FieldSpan seventeenByteKey = FieldSpan.redefining("DIS-GROUP-KEY",
                    DisclosureGroupRecord.DIS_GROUP_KEY_OFFSET, 17, PictureKind.ALPHANUMERIC);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            seventeenByteKey,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                            DisclosureGroupRecord.FILLER_SPAN))
                    .withMessageContaining("reaches byte 17 but only 16 byte(s) of storage");
        }

        @Test
        @DisplayName("Fixture row 1 read with a 17-byte key yields the corrupted span 0150{0")
        void aSeventeenByteKeyCorruptsTheRateSpanOfRealData() {
            // Measured with awk on app/data/ASCII/discgrp.txt:
            //   NR==1 substr($0,1,17)  -> A0000000000100010   <- what a 17-byte key would read as key
            //   NR==1 substr($0,18,6)  -> 0150{0              <- and as the rate. Not the real rate.
            assertThat(ROW_1.substring(0, 17)).isEqualTo("A0000000000100010");
            assertThat(ROW_1.substring(17, 23)).isEqualTo(ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY);

            // The correct 16-byte key leaves the rate span intact at 16 to 21.
            assertThat(ROW_1.substring(0, 16)).isEqualTo("A000000000010001");
            assertThat(ROW_1.substring(16, 22)).isEqualTo(ROW_1_RATE_IMAGE);

            // The record reads the correct span, and demonstrably not the corrupted one.
            DisclosureGroupRecord record = row1();
            assertThat(record.disIntRateImage())
                    .isEqualTo(ROW_1_RATE_IMAGE)
                    .isNotEqualTo(ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY);
            assertThat(record.disGroupKey())
                    .hasSize(16)
                    .isNotEqualTo(ROW_1.substring(0, 17));
        }

        @Test
        @DisplayName("The corrupted span is not merely a different value - it will not decode at all")
        void theCorruptedSpanIsNotEvenADecodableZonedImage() {
            // 0150{0 puts the sign overpunch '{' in a leading digit position, where only 0-9 are
            // legal, and leaves a plain '0' in the trailing position. So the failure is loud rather
            // than silent - but only for row 1's particular bytes, which is why the offset assertion
            // above, not this one, is the real guard.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThat(codec.decodeSignedScaled(ROW_1_RATE_IMAGE,
                    DisclosureGroupRecord.DIS_INT_RATE_SCALE))
                    .isEqualByComparingTo(new BigDecimal("15.00"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled(
                            ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY,
                            DisclosureGroupRecord.DIS_INT_RATE_SCALE))
                    .withMessageContaining("holds only the digits 0 to 9");
        }

        @Test
        @DisplayName("The rate decodes to a clean APR from offset 16 on every fixture row")
        void everyFixtureRowYieldsACleanAprAtOffsetSixteen() {
            // The corroborating evidence that 16 is right: read from 16 the fixture holds exactly
            // three well-formed rates - 0.00, 15.00 and 25.00. Read from 17 it holds garbage.
            Set<BigDecimal> ratesAtSixteen = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                ratesAtSixteen.add(DisclosureGroupRecord.decode(row, ASCII).disIntRate());
            }

            assertThat(ratesAtSixteen).containsExactlyInAnyOrder(
                    new BigDecimal("0.00"), new BigDecimal("15.00"), new BigDecimal("25.00"));
        }
    }

    @Nested
    @DisplayName("Field typing - what must and must not be modelled as a number")
    class FieldTyping {

        @Test
        @DisplayName("DIS-ACCT-GROUP-ID X(10) pads on the right and truncates on the right")
        void groupIdIsAlphanumericPaddedAndTruncatedOnTheRight() {
            // COBOL fills a PIC X receiver from its leftmost position, so a short sender is padded on
            // the right and an over-long one loses its RIGHTMOST characters - never its leftmost.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disAcctGroupId("AB");
            assertThat(record.disAcctGroupId()).isEqualTo("AB        ").hasSize(10);

            record.disAcctGroupId("ABCDEFGHIJKL");
            assertThat(record.disAcctGroupId())
                    .as("right truncation: the leading 10 survive, so never 'CDEFGHIJKL'")
                    .isEqualTo("ABCDEFGHIJ");

            record.disAcctGroupId("");
            assertThat(record.disAcctGroupId()).isEqualTo(" ".repeat(10));

            record.disAcctGroupId(GROUP_ID_A);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);

            assertThatNullPointerException().isThrownBy(() -> record.disAcctGroupId(null));
        }

        @Test
        @DisplayName("Reads are never trimmed - the padding of DEFAULT and ZEROAPR is data")
        void readsAreNeverTrimmed() {
            // The fixture stores 'DEFAULT   ' and 'ZEROAPR   ', padded to 10. A field-by-field differ
            // compares those bytes, so trimming on read would make a real difference invisible.
            assertThat(GROUP_ID_DEFAULT).hasSize(10).isNotEqualTo("DEFAULT");
            assertThat(GROUP_ID_ZEROAPR).hasSize(10).isNotEqualTo("ZEROAPR");

            DisclosureGroupRecord record = DisclosureGroupRecord.decode(
                    row(GROUP_ID_DEFAULT, "01", "0001", RATE_ZERO_IMAGE, FIXTURE_FILLER), ASCII);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT).endsWith("   ");
        }

        @Test
        @DisplayName("DIS-TRAN-TYPE-CD is a String, not a number, despite reading 01 to 07")
        void tranTypeCodeIsAlphanumericNotNumeric() throws NoSuchMethodException {
            // app/cpy/CVTRA02Y.cpy:L7 declares PIC X(02). The fixture's measured distinct values are
            // 01, 02, 03, 04, 05, 06 and 07, which look numeric; modelling the item as an int would
            // strip the leading zero and corrupt the key bytes on the way back out. Asserted
            // reflectively so the contract is on the accessor's type, not merely on one value.
            Method accessor = DisclosureGroupRecord.class.getMethod("disTranTypeCd");
            assertThat(accessor.getReturnType()).isEqualTo(String.class);

            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind().numericDisplay()).isFalse();
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind().leftJustified()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07"})
        @DisplayName("Every measured type code keeps its leading zero across decode and re-encode")
        void everyTypeCodeSurvivesTheRoundTripWithItsLeadingZero(String typeCd) {
            String image = row(GROUP_ID_A, typeCd, "0001", RATE_ZERO_IMAGE, FIXTURE_FILLER);
            DisclosureGroupRecord record = DisclosureGroupRecord.decode(image, ASCII);

            assertThat(record.disTranTypeCd()).isEqualTo(typeCd).hasSize(2).startsWith("0");
            assertThat(record.encodeToString()).isEqualTo(image);
            assertThat(record.encodeToString().substring(10, 12)).isEqualTo(typeCd);
        }

        @Test
        @DisplayName("An over-long type code truncates on the right, to two characters")
        void anOverLongTypeCodeTruncatesOnTheRight() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranTypeCd("XYZ");
            assertThat(record.disTranTypeCd()).isEqualTo("XY");

            record.disTranTypeCd("1");
            assertThat(record.disTranTypeCd()).isEqualTo("1 ");

            assertThatNullPointerException().isThrownBy(() -> record.disTranTypeCd(null));
        }

        @ParameterizedTest
        @CsvSource({"1, 0001", "2, 0002", "3, 0003", "4, 0004", "0, 0000", "9999, 9999"})
        @DisplayName("DIS-TRAN-CAT-CD 9(04) zero-fills on the LEFT")
        void categoryCodeZeroFillsOnTheLeft(int value, String expectedImage) {
            // app/cpy/CVTRA02Y.cpy:L8 declares PIC 9(04). A numeric receiver is aligned on its implied
            // decimal point, so a short sender is filled with zeros on the LEFT. The fixture's
            // measured distinct values are 0001, 0002, 0003 and 0004.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disTranCatCd(value);

            assertThat(record.disTranCatCdImage()).isEqualTo(expectedImage).hasSize(4);
            assertThat(record.disTranCatCd()).isEqualTo(value);
            assertThat(record.encodeToString().substring(12, 16)).isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("An oversized category code truncates on the LEFT and does not throw")
        void anOversizedCategoryCodeTruncatesOnTheLeft() {
            // COBOL keeps the receiver's LOW-order digits: 12345 into PIC 9(04) stores 2345, never
            // 1234. The loss is reported only under ON SIZE ERROR, which appears nowhere in any of the
            // 28 programs, so nothing is thrown here either.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranCatCd(12_345);
            assertThat(record.disTranCatCdImage()).isEqualTo("2345").isNotEqualTo("1234");
            assertThat(record.disTranCatCd()).isEqualTo(2345);

            record.disTranCatCd("987654");
            assertThat(record.disTranCatCdImage()).isEqualTo("7654");
            assertThat(record.disTranCatCd()).isEqualTo(7654);
        }

        @Test
        @DisplayName("The digit-string overload is the MOVE '05' shape, and rejects non-digits")
        void theDigitStringOverloadIsTheMoveLiteralShape() {
            // MOVE '05' TO a numeric receiver moves an alphanumeric literal into a numeric field, so
            // the String overload exists. It still applies the PIC 9 left-fill rule.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranCatCd("5");
            assertThat(record.disTranCatCdImage()).isEqualTo("0005");

            record.disTranCatCd("0001");
            assertThat(record.disTranCatCd()).isEqualTo(1);

            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd("00A1"))
                    .withMessageContaining("holds only the digits 0 to 9");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(""))
                    .withMessageContaining("at least one digit");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd("00 1"));
            assertThatNullPointerException().isThrownBy(() -> record.disTranCatCd((String) null));
        }

        @Test
        @DisplayName("PIC 9 has no sign position, so a negative category code is rejected")
        void aNegativeCategoryCodeIsRejected() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(-1))
                    .withMessageContaining("PIC 9 has no sign position");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(Integer.MIN_VALUE));
        }

        @Test
        @DisplayName("A misaligned category span fails loudly rather than yielding a plausible number")
        void aMisalignedCategorySpanFailsLoudly() {
            // If the record were ever built over the wrong offsets, bytes 12 to 15 would hold
            // non-digits. Silently yielding zero would hide the misalignment behind a believable
            // value, which is the hardest class of parity defect to find.
            DisclosureGroupRecord broken = DisclosureGroupRecord.decode(
                    row(GROUP_ID_A, "01", "00-1", RATE_ZERO_IMAGE, FIXTURE_FILLER), ASCII);

            assertThat(broken.disTranCatCdImage()).isEqualTo("00-1");
            assertThatIllegalArgumentException().isThrownBy(broken::disTranCatCd)
                    .withMessageContaining("holds only the digits 0 to 9");
        }

        @Test
        @DisplayName("DIS-INT-RATE is a BigDecimal of scale exactly 2, with 4 integer digits")
        void theRateIsABigDecimalOfScaleTwo() throws NoSuchMethodException {
            Method accessor = DisclosureGroupRecord.class.getMethod("disIntRate");
            assertThat(accessor.getReturnType()).isEqualTo(BigDecimal.class);

            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind())
                    .isEqualTo(PictureKind.SIGNED_SCALED);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind().numericDisplay()).isTrue();
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind().leftJustified()).isFalse();

            assertThat(row1().disIntRate())
                    .isEqualTo(new BigDecimal("15.00"))
                    .hasScaleOf(DisclosureGroupRecord.DIS_INT_RATE_SCALE);

            // Four integer digit positions: 9999.99 is the largest storable magnitude.
            DisclosureGroupRecord widest = new DisclosureGroupRecord(ASCII);
            widest.disIntRate(new BigDecimal("9999.99"));
            assertThat(widest.disIntRate()).isEqualTo(new BigDecimal("9999.99"));
            assertThat(widest.disIntRateImage()).hasSize(6);
        }
    }

    /**
     * The zero predicate, which is the load-bearing branch of this record.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L214-L217} reads:
     * <pre>
     * L214  IF DIS-INT-RATE NOT = 0
     * L215      PERFORM 1300-COMPUTE-INTEREST
     * L216      PERFORM 1400-COMPUTE-FEES
     * L217  END-IF
     * </pre>
     * Two consequences follow, and both are asserted here.
     *
     * <p>First, it is a <strong>value</strong> comparison against the literal {@code 0}. A rate
     * decoded from {@code 00000&#123;} is {@code BigDecimal("0.00")} at scale 2, which
     * {@code compareTo}-equals {@link BigDecimal#ZERO} but is <em>not</em> {@code equals} to it,
     * because {@code BigDecimal.equals} compares scale as well as value. An {@code equals}-based test
     * would classify every zero rate as non-zero and perform interest work the COBOL skips - on 30 of
     * the 51 fixture rows, so on the majority of an interest run.
     *
     * <p>Second, {@code 1400-COMPUTE-FEES} is reached <em>only</em> when the rate is non-zero. It sits
     * inside the guard at L216, not outside it, which makes this predicate genuinely load-bearing
     * rather than cosmetic. That paragraph is itself a documented no-op - {@code L518-L520} is
     * {@code 1400-COMPUTE-FEES.} / {@code * To be implemented} / {@code EXIT.} - and must stay one;
     * nothing here implements it and nothing here tidies it away.
     */
    @Nested
    @DisplayName("The zero predicate - CBACT04C:L214 IF DIS-INT-RATE NOT = 0")
    class ZeroPredicate {

        @Test
        @DisplayName("A rate of 0.00 is zero by value, and that is why compareTo is required")
        void zeroIsDetectedByValueNotByEquals() {
            DisclosureGroupRecord record = withRateImage(RATE_ZERO_IMAGE);
            BigDecimal rate = record.disIntRate();

            // The field always reads at scale 2, so this is the shape the predicate must cope with.
            assertThat(rate).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));

            // Value comparison: zero.
            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rate.compareTo(BigDecimal.ZERO)).isZero();

            // Scale-sensitive comparison: NOT equal. This is the trap the predicate must avoid, and
            // asserting it here is what makes the test demonstrate WHY compareTo is required rather
            // than merely happening to use it.
            assertThat(rate.equals(BigDecimal.ZERO))
                    .as("BigDecimal.equals compares scale as well as value, so 0.00 != ZERO")
                    .isFalse();

            assertThat(record.disIntRateIsZero()).isTrue();
            assertThat(record.disIntRateIsNotZero()).isFalse();
        }

        @Test
        @DisplayName("A freshly initialised record reads 0.00 at scale 2 and is zero")
        void aFreshRecordIsZero() {
            // A fresh record's rate image is the unsigned zoned form 000000, which is a different byte
            // sequence from the stored 00000{ but the same value. Both must satisfy the predicate.
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.disIntRateImage()).isEqualTo("000000");
            assertThat(fresh.disIntRate()).hasScaleOf(2).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.disIntRateIsZero()).isTrue();
            assertThat(fresh.disIntRateIsNotZero()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.01", "-0.01", "15.00", "25.00", "-15.00", "9999.99", "-9999.99"})
        @DisplayName("Any non-zero rate, positive or negative, takes the interest branch")
        void anyNonZeroRateIsNotZero(String value) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRateIsZero()).isFalse();
            assertThat(record.disIntRateIsNotZero()).isTrue();
            assertThat(record.disIntRate()).hasScaleOf(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.0", "0.00", "-0.00", "0.000"})
        @DisplayName("Zero at any sending scale stores as 0.00 and is still zero by value")
        void zeroAtAnySendingScaleIsStillZero(String value) {
            // COBOL has no notion of a scale-0 zero distinct from 0.00: the receiver's PICTURE decides.
            // So every one of these senders must land on the same stored value and the same branch.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(record.disIntRateImage()).isEqualTo(RATE_ZERO_IMAGE);
            assertThat(record.disIntRateIsZero()).isTrue();
            assertThat(record.disIntRateIsNotZero()).isFalse();
        }

        @Test
        @DisplayName("The predicate is exactly the negation of its counterpart, both ways")
        void theTwoPredicatesAreExactComplements() {
            DisclosureGroupRecord zero = withRateImage(RATE_ZERO_IMAGE);
            DisclosureGroupRecord nonZero = withRateImage(ROW_1_RATE_IMAGE);

            assertThat(zero.disIntRateIsZero()).isNotEqualTo(zero.disIntRateIsNotZero());
            assertThat(nonZero.disIntRateIsZero()).isNotEqualTo(nonZero.disIntRateIsNotZero());

            assertThat(zero.disIntRateIsZero()).isTrue();
            assertThat(nonZero.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("The fixture exercises both branches from real data: 30 zero, 21 non-zero rows")
        void bothBranchesAreReachableFromRealFixtureData() {
            // Measured: awk substr($0,17,6) | sort | uniq -c gives 30 x 00000{, 15 x 00150{,
            // 6 x 00250{. More than half the fixture takes the zero branch, which is why getting the
            // predicate wrong would invert the dominant path of an interest run rather than an edge.
            int zeroRows = 0;
            int nonZeroRows = 0;
            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);
                if (record.disIntRateIsZero()) {
                    zeroRows++;
                    assertThat(record.disIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
                } else {
                    nonZeroRows++;
                    assertThat(record.disIntRate()).isGreaterThan(BigDecimal.ZERO);
                }
            }

            assertThat(zeroRows).isEqualTo(ROWS_WITH_A_ZERO_RATE);
            assertThat(nonZeroRows)
                    .isEqualTo(ROWS_WITH_A_15_PERCENT_RATE + ROWS_WITH_A_25_PERCENT_RATE);
            assertThat(zeroRows + nonZeroRows).isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(zeroRows).as("the zero branch is the majority path").isGreaterThan(nonZeroRows);
        }
    }

    @Nested
    @DisplayName("Fixture round trip - all 51 rows of discgrp.txt, gates G19 and G21")
    class FixtureRoundTrip {

        @Test
        @DisplayName("The fixture is 51 rows of exactly 50 bytes, needing no normalisation")
        void theFixtureMatchesTheCopybookExactly() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d must be exactly the declared record width", index + 1)
                        .hasSize(FIXTURE_ROW_WIDTH);
                assertThat(rows.get(index).getBytes(ASCII))
                        .as("row %d must encode to %d single-byte characters", index + 1,
                                FIXTURE_ROW_WIDTH)
                        .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("Every row decodes at the copybook offsets and re-encodes byte-identically")
        void everyRowRoundTripsByteForByte() {
            List<String> rows = fixtureRows();

            for (int index = 0; index < rows.size(); index++) {
                String stored = rows.get(index);
                int rowNumber = index + 1;
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(stored, ASCII);

                // Each field is compared against the row sliced by plain index arithmetic at the
                // copybook's absolute offsets - 0, 10, 12, 16, 22 - so the assertion is independent of
                // how the record computes them.
                assertThat(record.disAcctGroupId())
                        .as("row %d DIS-ACCT-GROUP-ID at 0..9", rowNumber)
                        .isEqualTo(stored.substring(0, 10));
                assertThat(record.disTranTypeCd())
                        .as("row %d DIS-TRAN-TYPE-CD at 10..11", rowNumber)
                        .isEqualTo(stored.substring(10, 12));
                assertThat(record.disTranCatCdImage())
                        .as("row %d DIS-TRAN-CAT-CD at 12..15", rowNumber)
                        .isEqualTo(stored.substring(12, 16));
                assertThat(record.disIntRateImage())
                        .as("row %d DIS-INT-RATE at 16..21", rowNumber)
                        .isEqualTo(stored.substring(16, 22));
                assertThat(record.filler())
                        .as("row %d FILLER at 22..49", rowNumber)
                        .isEqualTo(stored.substring(22, 50));
                assertThat(record.disGroupKey())
                        .as("row %d DIS-GROUP-KEY at 0..15", rowNumber)
                        .isEqualTo(stored.substring(0, 16));

                // Byte identity, which is what proves nothing was normalised, trimmed or re-padded on
                // the way through - the 28 filler zeros included.
                assertThat(record.encodeToString())
                        .as("row %d must re-encode byte-identically", rowNumber)
                        .isEqualTo(stored);
                assertThat(record.encode())
                        .as("row %d bytes must be identical", rowNumber)
                        .isEqualTo(stored.getBytes(ASCII));

                // And the decoded rate always reports the declared scale.
                assertThat(record.disIntRate())
                        .as("row %d DIS-INT-RATE scale", rowNumber)
                        .hasScaleOf(DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            }
        }

        @Test
        @DisplayName("Row 1 is [A000000000][01][0001][00150{][28 zeros] with a rate of 15.00")
        void rowOneDecodesFieldForField() {
            List<String> rows = fixtureRows();
            String stored = rows.get(0);

            assertThat(stored).as("the shipped fixture's first row").isEqualTo(ROW_1);

            DisclosureGroupRecord record = DisclosureGroupRecord.decode(stored, ASCII);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(record.disTranTypeCd()).isEqualTo("01");
            assertThat(record.disTranCatCdImage()).isEqualTo("0001");
            assertThat(record.disTranCatCd()).isEqualTo(1);
            assertThat(record.disIntRateImage()).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(record.disIntRate())
                    .isEqualTo(new BigDecimal("15.00"))
                    .hasScaleOf(2);
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");
            assertThat(record.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("The fixture holds exactly three group ids, 17 rows each, DEFAULT on lines 18-34")
        void theThreeGroupIdsOccupySeventeenRowsEach() {
            List<String> rows = fixtureRows();
            int aRows = 0;
            int defaultRows = 0;
            int zeroAprRows = 0;

            for (int index = 0; index < rows.size(); index++) {
                String groupId = DisclosureGroupRecord.decode(rows.get(index), ASCII)
                        .disAcctGroupId();
                int lineNumber = index + 1;

                if (GROUP_ID_A.equals(groupId)) {
                    aRows++;
                    assertThat(lineNumber).as("A000000000 rows are lines 1-17").isBetween(1, 17);
                } else if (GROUP_ID_DEFAULT.equals(groupId)) {
                    defaultRows++;
                    assertThat(lineNumber).as("DEFAULT rows are lines 18-34").isBetween(18, 34);
                } else {
                    zeroAprRows++;
                    assertThat(groupId).isEqualTo(GROUP_ID_ZEROAPR);
                    assertThat(lineNumber).as("ZEROAPR rows are lines 35-51").isBetween(35, 51);
                }
            }

            assertThat(aRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(defaultRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(zeroAprRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(aRows + defaultRows + zeroAprRows).isEqualTo(FIXTURE_ROW_COUNT);
        }

        @Test
        @DisplayName("The fixture's rate images are 30 zero, 15 at 15.00 and 6 at 25.00")
        void theRateDistributionIsAsMeasured() {
            List<String> rows = fixtureRows();
            int zero = 0;
            int fifteen = 0;
            int twentyFive = 0;

            for (String row : rows) {
                String image = DisclosureGroupRecord.decode(row, ASCII).disIntRateImage();
                if (RATE_ZERO_IMAGE.equals(image)) {
                    zero++;
                } else if ("00150{".equals(image)) {
                    fifteen++;
                } else {
                    assertThat(image).isEqualTo("00250{");
                    twentyFive++;
                }
            }

            assertThat(zero).isEqualTo(ROWS_WITH_A_ZERO_RATE);
            assertThat(fifteen).isEqualTo(ROWS_WITH_A_15_PERCENT_RATE);
            assertThat(twentyFive).isEqualTo(ROWS_WITH_A_25_PERCENT_RATE);

            // All 51 overpunch bytes are '{', the zone-C form for "positive, low-order digit 0".
            for (String row : rows) {
                assertThat(row.charAt(21))
                        .as("every fixture rate ends in the +0 overpunch")
                        .isEqualTo('{');
            }
        }

        @Test
        @DisplayName("The category codes in the fixture are 0001 to 0004 and the type codes 01 to 07")
        void theKeySubFieldsMatchTheMeasuredFixtureValues() {
            Set<String> typeCodes = new LinkedHashSet<>();
            Set<String> categoryCodes = new LinkedHashSet<>();

            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);
                typeCodes.add(record.disTranTypeCd());
                categoryCodes.add(record.disTranCatCdImage());
            }

            assertThat(typeCodes)
                    .containsExactlyInAnyOrder("01", "02", "03", "04", "05", "06", "07");
            assertThat(categoryCodes)
                    .containsExactlyInAnyOrder("0001", "0002", "0003", "0004");
        }

        @Test
        @DisplayName("The same stored image decoded under either code page yields the same items")
        void theCodePageIsHonouredAndIsNotPartOfIdentity() {
            // The ASCII fixture and the EBCDIC dataset hold the same record in different code pages.
            // Decoding each under its own charset must produce identical items - and different bytes.
            DisclosureGroupRecord ascii = DisclosureGroupRecord.decode(ROW_1, ASCII);
            DisclosureGroupRecord ebcdic = DisclosureGroupRecord.decode(ROW_1.getBytes(EBCDIC),
                    EBCDIC);

            assertThat(ebcdic.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(ebcdic.disTranTypeCd()).isEqualTo("01");
            assertThat(ebcdic.disTranCatCd()).isEqualTo(1);
            assertThat(ebcdic.disIntRate()).isEqualTo(new BigDecimal("15.00"));
            assertThat(ebcdic.filler()).isEqualTo(FIXTURE_FILLER);

            assertThat(ascii).isEqualTo(ebcdic).hasSameHashCodeAs(ebcdic);
            assertThat(ascii.encode())
                    .as("the items are equal but the stored bytes are not")
                    .isNotEqualTo(ebcdic.encode());
            assertThat(ebcdic.encodeToString()).isEqualTo(ROW_1);
        }
    }

    /**
     * The {@code 'DEFAULT'} literal and the {@code FILLER} span.
     *
     * <h2>A measured discrepancy, resolved by asserting both truths</h2>
     *
     * <p>The written specification says {@code FILLER X(28)} is space-filled. Measurement of the
     * fixture contradicts it: {@code awk '{print substr($0,23)}' app/data/ASCII/discgrp.txt | sort -u}
     * yields a single distinct value, 28 ASCII <strong>zeros</strong>. (For contrast,
     * {@code acctdata.txt}'s {@code FILLER X(178)} genuinely is all spaces on every row, so this is a
     * property of this fixture rather than of the project's fixtures generally.)
     *
     * <p>Rather than quietly pick a side, both behaviours are asserted below and each is labelled:
     * <ul>
     *   <li><strong>Decode preserves the stored bytes verbatim</strong>, so the fixture's zeros survive
     *       a round trip. A codec that space-normalised {@code FILLER} on decode would fail
     *       {@link #decodePreservesTheFixturesFillerBytesVerbatim()} - which is the point of having it.</li>
     *   <li><strong>Fresh construction emits spaces</strong>, which is the COBOL convention for a
     *       character {@code FILLER} declaring no {@code VALUE}, and what gate G21 requires. Nothing in
     *       the COBOL ever <em>writes</em> {@code DIS-GROUP-RECORD} - {@code app/cbl/CBACT04C.cbl:L416}
     *       and {@code L444} only do {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD} - so there is no
     *       COBOL write path to contradict the space default.</li>
     * </ul>
     * Both are correct because they answer different questions: what a <em>stored</em> record contains,
     * and what an <em>initialised</em> one contains. What matters to both gates is that the span is
     * present at its full declared width, which is asserted either way.
     */
    @Nested
    @DisplayName("The 'DEFAULT' literal and FILLER - gate G21, and a documented discrepancy")
    class DefaultLiteralAndFiller {

        @Test
        @DisplayName("MOVE 'DEFAULT' into X(10) stores DEFAULT plus exactly three spaces")
        void theSevenCharacterDefaultLiteralIsRightPaddedToTen() {
            // app/cbl/CBACT04C.cbl:L437 - MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID. Seven characters into
            // a PIC X(10) receiver, which COBOL right-space-pads.
            assertThat(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID).isEqualTo("DEFAULT").hasSize(7);

            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            assertThat(record.disAcctGroupId())
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID + "   ");

            // The same result through the codec's MOVE primitive, so the padding rule is confirmed
            // independently of the record's accessor.
            assertThat(new FixedWidthCodec(ASCII).movePicX(
                    DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH))
                    .isEqualTo("DEFAULT   ");
        }

        @Test
        @DisplayName("The padded literal matches the bytes the fixture actually stores on lines 18-34")
        void thePaddedLiteralMatchesTheFixtureBytes() {
            // This is why every account in CBACT04C finds its DEFAULT row: acctdata.txt leaves
            // ACCT-GROUP-ID blank on all 50 rows, the keyed read misses with file status '23'
            // (CBACT04C:L436), and L437 substitutes the padded literal.
            DisclosureGroupRecord constructed = new DisclosureGroupRecord(ASCII);
            constructed.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            String storedDefaultRow = fixtureRows().get(17);      // line 18, 0-based index 17
            DisclosureGroupRecord stored = DisclosureGroupRecord.decode(storedDefaultRow, ASCII);

            assertThat(stored.disAcctGroupId())
                    .isEqualTo(constructed.disAcctGroupId())
                    .isEqualTo(GROUP_ID_DEFAULT);
            assertThat(storedDefaultRow.substring(0, 10)).isEqualTo("DEFAULT   ");
        }

        @Test
        @DisplayName("Decode preserves the fixture's 28 FILLER zeros verbatim, and they round-trip")
        void decodePreservesTheFixturesFillerBytesVerbatim() {
            // MEASURED, and contrary to the written "space-filled" description: every one of the 51
            // rows carries 28 ASCII zeros in bytes 22..49. A codec that normalised them to spaces on
            // decode would break byte identity here.
            assertThat(FIXTURE_FILLER).isEqualTo("0000000000000000000000000000").hasSize(28);

            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);

                assertThat(record.filler())
                        .isEqualTo(FIXTURE_FILLER)
                        .doesNotContain(" ");
                assertThat(record.fillerBytes()).isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
                assertThat(record.encodeToString().substring(22)).isEqualTo(FIXTURE_FILLER);
                assertThat(record.encodeToString()).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("Fresh construction emits 28 FILLER spaces - the COBOL default, gate G21")
        void freshConstructionEmitsTwentyEightFillerSpaces() {
            // The other half of the resolution. FieldSpan.filler declares no VALUE, so INITIALIZE
            // fills the span with the charset's space byte. The span is present at full width either
            // way, which is what gate G21 turns on.
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.filler()).isEqualTo(" ".repeat(28)).hasSize(28);
            assertThat(fresh.fillerBytes())
                    .hasSize(28)
                    .containsOnly(" ".getBytes(ASCII)[0]);
            assertThat(fresh.encodeToString().substring(22)).isEqualTo(" ".repeat(28));

            // Documented plainly: the fixture carries zeros, a freshly built record carries spaces,
            // and both are correct because they answer different questions.
            assertThat(fresh.filler())
                    .as("a fresh record's FILLER differs from a stored row's, by design")
                    .isNotEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("An all-default record is 50 bytes: 12 spaces, 0000, 000000, then 28 spaces")
        void anAllDefaultRecordIsFullyInitialised() {
            // The complete initialised image, so every span's default is visible in one assertion.
            // Character spans get the space byte; numeric DISPLAY spans get the zero byte.
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.encodeToString())
                    .isEqualTo(" ".repeat(10) + " ".repeat(2) + "0000" + "000000" + " ".repeat(28))
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            assertThat(fresh.disAcctGroupId()).isEqualTo(" ".repeat(10));
            assertThat(fresh.disTranTypeCd()).isEqualTo("  ");
            assertThat(fresh.disTranCatCd()).isZero();
            assertThat(fresh.disIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.filler()).isEqualTo(" ".repeat(28));
        }

        @Test
        @DisplayName("Fully populating a fresh record reproduces a fixture row apart from FILLER")
        void aPopulatedFreshRecordDiffersFromAFixtureRowOnlyInFiller() {
            DisclosureGroupRecord built = new DisclosureGroupRecord(ASCII);
            built.disAcctGroupId(GROUP_ID_A);
            built.disTranTypeCd("01");
            built.disTranCatCd(1);
            built.disIntRate(new BigDecimal("15.00"));

            assertThat(built.encodeToString())
                    .isEqualTo(ROW_1.substring(0, 22) + " ".repeat(28));
            assertThat(built.encodeToString().substring(0, 22))
                    .as("the four data items are byte-identical to the fixture row")
                    .isEqualTo(ROW_1.substring(0, 22));
            assertThat(built).isNotEqualTo(row1());
        }
    }

    @Nested
    @DisplayName("Numeric parity - gates G22, G23, G24 and G25")
    class NumericParity {

        @Test
        @DisplayName("G22 - no field, return type or parameter is double, float, Double or Float")
        void noBinaryFloatingPointTypeAppearsAnywhereOnTheType() {
            // Every PIC 9...V... and COMP-3 field becomes a BigDecimal at its declared scale. A binary
            // floating-point type cannot represent a decimal fraction exactly, so its presence anywhere
            // on this type would be a parity defect by construction. Asserted reflectively rather than
            // by reading the source, so it holds for whatever the class actually compiles to.
            Set<Class<?>> forbidden = Set.of(double.class, float.class, Double.class, Float.class);

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                assertThat(forbidden)
                        .as("field %s must not be a binary floating-point type", field.getName())
                        .doesNotContain(field.getType());
            }
            for (Method method : DisclosureGroupRecord.class.getDeclaredMethods()) {
                assertThat(forbidden)
                        .as("method %s must not return a binary floating-point type", method.getName())
                        .doesNotContain(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(forbidden)
                            .as("method %s must not accept a binary floating-point type",
                                    method.getName())
                            .doesNotContain(parameter);
                }
            }

            assertThat(DisclosureGroupRecord.class.getDeclaredFields()).isNotEmpty();
            assertThat(DisclosureGroupRecord.class.getDeclaredMethods()).isNotEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "15.00", "25.00", "-15.00", "0.01", "9999.99", "-9999.99"})
        @DisplayName("G23 - the rate always reports scale exactly 2, the zero rate included")
        void theRateAlwaysReportsScaleExactlyTwo(String value) {
            // app/cpy/CVTRA02Y.cpy:L9 declares V99, so the scale is 2 and never 0. A careless
            // implementation yields scale 0 for zero, which produces the wrong fixed-width image.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRate()).hasScaleOf(2);
            assertThat(record.disIntRate().scale()).isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            assertThat(record.disIntRateImage()).hasSize(DisclosureGroupRecord.DIS_INT_RATE_LENGTH);

            // Re-reading a stored value never changes its scale, so decode and encode agree.
            DisclosureGroupRecord reread = DisclosureGroupRecord.decode(record.encode(), ASCII);
            assertThat(reread.disIntRate()).hasScaleOf(2).isEqualTo(record.disIntRate());
        }

        @Test
        @DisplayName("G23 - a zero rate reports scale 2, not scale 0")
        void aZeroRateReportsScaleTwoNotZero() {
            DisclosureGroupRecord stored = withRateImage(RATE_ZERO_IMAGE);
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(stored.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(fresh.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(stored.disIntRate().scale()).isNotZero();
            assertThat(BigDecimal.ZERO.scale())
                    .as("which is exactly why BigDecimal.ZERO is not an acceptable stand-in")
                    .isZero();
        }

        @Test
        @DisplayName("G24 - the one rounding mode in the system is DOWN, because ROUNDED is never used")
        void theOnlyRoundingModeIsDown() {
            // The keyword ROUNDED appears zero times across all 28 COBOL programs, so COBOL truncates
            // excess fractional digits on store. DOWN is therefore the only faithful mode: HALF_UP and
            // HALF_EVEN are both wrong. The mode is named in exactly one place in the module.
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_SCALE)
                    .isEqualTo(2);
        }

        @ParameterizedTest
        @CsvSource({
            // sent,      stored (DOWN),  what a rounding implementation would have stored
            "15.999,      15.99,          16.00",
            "0.125,       0.12,           0.13",
            "0.129,       0.12,           0.13",
            "25.005,      25.00,          25.01",
            "-15.999,    -15.99,         -16.00",
            "-0.125,      -0.12,          -0.13"
        })
        @DisplayName("G24 - excess fraction digits are truncated toward zero, never rounded")
        void excessFractionDigitsAreTruncatedTowardZero(String sent, String storedDown,
                String ifItRounded) {
            // Each case is chosen so that truncation and rounding DISAGREE, which is what makes a
            // rounding regression fail loudly here instead of drifting by a cent somewhere downstream.
            // The rounded answer is asserted as a literal rather than computed, so no rounding mode
            // other than DOWN is named anywhere in this file.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(sent));

            assertThat(record.disIntRate())
                    .as("%s must truncate to %s", sent, storedDown)
                    .isEqualTo(new BigDecimal(storedDown))
                    .hasScaleOf(2);
            assertThat(record.disIntRate())
                    .as("%s must NOT round to %s", sent, ifItRounded)
                    .isNotEqualTo(new BigDecimal(ifItRounded));
            assertThat(new BigDecimal(storedDown))
                    .as("the case is only meaningful if the two answers differ")
                    .isNotEqualByComparingTo(new BigDecimal(ifItRounded));
        }

        @ParameterizedTest
        @CsvSource({
            "123456.78,   3456.78,   34567H",
            "-123456.78, -3456.78,   34567Q",
            "10000.00,    0.00,      00000{",
            "-10000.00,   0.00,      00000{",
            "99999.99,    9999.99,   99999I"
        })
        @DisplayName("G24 - integer digits beyond four are discarded on the LEFT, and nothing throws")
        void excessIntegerDigitsAreDiscardedOnTheLeft(String sent, String stored, String image) {
            // A numeric receiver is aligned on its implied decimal point, so an oversized value keeps
            // its LOW-order digits: 123456.78 into S9(04)V99 stores 3456.78, never 1234.56. COBOL
            // reports that loss only under ON SIZE ERROR, which appears nowhere in this codebase, so
            // no exception is thrown - the value is quietly wrapped, exactly as the mainframe would.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(sent));

            assertThat(record.disIntRate()).isEqualByComparingTo(new BigDecimal(stored)).hasScaleOf(2);
            assertThat(record.disIntRateImage()).isEqualTo(image).hasSize(6);
            assertThat(record.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("G24 - the receiver's own truncation matches CobolDecimal.storeAtPicture exactly")
        void theReceiverTruncatesExactlyAsStoreAtPictureDoes() {
            // The record must not implement its own scaling: it delegates to the single seam that owns
            // the policy. Asserted by comparing the stored value against that seam's own answer.
            for (String sent : List.of("15.999", "123456.789", "-123456.789", "0.005", "9999.994")) {
                DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
                record.disIntRate(new BigDecimal(sent));

                assertThat(record.disIntRate())
                        .as("stored %s", sent)
                        .isEqualByComparingTo(CobolDecimal.storeAtPicture(new BigDecimal(sent),
                                DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS,
                                DisclosureGroupRecord.DIS_INT_RATE_SCALE));
            }
        }

        @Test
        @DisplayName("G25 - the rate this record supplies is read from offset 16 at scale 2")
        void theRateFeedsTheInterestFormulaAtTheRightOffsetAndScale() {
            // app/cbl/CBACT04C.cbl:L464-L465 computes
            //     COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
            // with no ROUNDED phrase, storing into WS-MONTHLY-INT PIC S9(09)V99 declared at L168. The
            // formula itself belongs to the interest calculator's own test; what is proved HERE is only
            // that the rate this record yields is the right value at the right scale, read from the
            // right offset - because that is the input the formula cannot recover from if it is wrong.
            DisclosureGroupRecord fixtureRow = row1();
            BigDecimal rate = fixtureRow.disIntRate();

            assertThat(fixtureRow.disIntRateImage())
                    .as("read from bytes 16..21 of a real fixture row")
                    .isEqualTo(ROW_1.substring(16, 22));
            assertThat(rate).isEqualTo(new BigDecimal("15.00")).hasScaleOf(2);

            // The worked case: a balance of 99.99 against the fixture's real 15.00 APR. The exact
            // quotient is 1.249875, so truncation gives 1.24 and rounding would have given 1.25. If the
            // rate were read one byte off, or at the wrong scale, this number could not come out right.
            BigDecimal balance = new BigDecimal("99.99");
            assertThat(CobolDecimal.monthlyInterest(balance, rate))
                    .isEqualTo(new BigDecimal("1.24"))
                    .isNotEqualTo(new BigDecimal("1.25"))
                    .hasScaleOf(2);

            // A zero rate is the other half of the guard: the COBOL never reaches the formula at all.
            DisclosureGroupRecord zeroRateRow = withRateImage(RATE_ZERO_IMAGE);
            assertThat(zeroRateRow.disIntRateIsZero()).isTrue();
            assertThat(CobolDecimal.monthlyInterest(balance, zeroRateRow.disIntRate()))
                    .as("which is why L214 guards the computation rather than relying on the result")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("G25 - DIS-INT-RATE is an annual percentage, so 15.00 means 15% APR")
        void theRateIsAnAnnualPercentage() {
            // The divisor 1200 = 12 months x 100 percent, which is what makes the stored 15.00 an APR
            // rather than a fraction. Recorded here so the units of this field are unambiguous.
            assertThat(CobolDecimal.MONTHLY_INTEREST_DIVISOR).isEqualTo(1200L);

            // One month of 15% APR on 1000.00 is 12.50 exactly - a clean case with no truncation, so
            // the units are demonstrated without the rounding policy clouding it.
            DisclosureGroupRecord record = withRateImage(ROW_1_RATE_IMAGE);
            assertThat(CobolDecimal.monthlyInterest(new BigDecimal("1000.00"), record.disIntRate()))
                    .isEqualByComparingTo(new BigDecimal("12.50"));
        }

        @Test
        @DisplayName("The rate accessor rejects null rather than storing a default")
        void theRateAccessorRejectsNull() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            assertThatNullPointerException().isThrownBy(() -> record.disIntRate(null));
        }
    }

    /**
     * The zoned sign overpunch, whose branches the fixture cannot reach.
     *
     * <p>All 51 fixture rows end their rate span in <code>&#123;</code>, the zone-C overpunch for
     * "positive, low-order digit 0". So the positive-nonzero zone (<code>A</code> to <code>I</code>)
     * and the whole negative zone (<code>&#125;</code>, then <code>J</code> to <code>R</code>) are
     * unreachable from real data and must be driven from synthesised 6-byte spans. Without them those
     * paths would simply never execute.
     */
    @Nested
    @DisplayName("Sign overpunch - the branches no fixture row reaches")
    class SignOverpunch {

        @ParameterizedTest
        @CsvSource({
            // image,   decoded value,  which zone and low-order digit
            "00150{,     15.00,         zone C positive digit 0",
            "00150A,     15.01,         zone C positive digit 1",
            "00150E,     15.05,         zone C positive digit 5",
            "00150I,     15.09,         zone C positive digit 9",
            "00150},    -15.00,         zone D negative digit 0",
            "00150J,    -15.01,         zone D negative digit 1",
            "00150N,    -15.05,         zone D negative digit 5",
            "00150R,    -15.09,         zone D negative digit 9",
            "001500,     15.00,         zone F unsigned, taken as positive"
        })
        @DisplayName("Every overpunch zone decodes to the right signed value at scale 2")
        void everyOverpunchZoneDecodesCorrectly(String image, String expected, String zone) {
            DisclosureGroupRecord record = withRateImage(image);

            assertThat(record.disIntRate())
                    .as("%s is %s", image, zone)
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
            assertThat(record.disIntRateImage()).isEqualTo(image).hasSize(6);

            // The stored bytes survive untouched, so a synthesised span round-trips like a fixture row.
            assertThat(record.encodeToString())
                    .isEqualTo(row(GROUP_ID_A, "01", "0001", image, FIXTURE_FILLER));
        }

        @ParameterizedTest
        @CsvSource({
            "15.00,   00150{",
            "15.01,   00150A",
            "15.09,   00150I",
            "-15.00,  00150}",
            "-15.01,  00150J",
            "-15.09,  00150R",
            "0.01,    00000A",
            "-0.01,   00000J",
            "0.00,    00000{"
        })
        @DisplayName("Every signed value encodes to its overpunched image in exactly 6 bytes")
        void everySignedValueEncodesToItsOverpunchedImage(String value, String expectedImage) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRateImage())
                    .isEqualTo(expectedImage)
                    .as("the sign never occupies a byte of its own")
                    .hasSize(DisclosureGroupRecord.DIS_INT_RATE_LENGTH);
            assertThat(record.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"15.00", "-15.00", "15.01", "-15.01", "0.01", "-0.01", "0.00",
            "9999.99", "-9999.99"})
        @DisplayName("Store then read then store again is stable for both signs")
        void storeAndReadAreStableForBothSigns(String value) {
            DisclosureGroupRecord first = new DisclosureGroupRecord(ASCII);
            first.disIntRate(new BigDecimal(value));

            DisclosureGroupRecord second = DisclosureGroupRecord.decode(first.encode(), ASCII);
            assertThat(second.disIntRate()).isEqualTo(first.disIntRate()).hasScaleOf(2);
            assertThat(second.disIntRateImage()).isEqualTo(first.disIntRateImage());

            DisclosureGroupRecord third = new DisclosureGroupRecord(ASCII);
            third.disIntRate(second.disIntRate());
            assertThat(third.disIntRateImage()).isEqualTo(first.disIntRateImage());
            assertThat(Arrays.equals(third.encode(), first.encode()))
                    .as("a re-store of a decoded value reproduces the same bytes")
                    .isTrue();
        }

        @Test
        @DisplayName("A negative rate is non-zero and truncates toward zero, not toward minus infinity")
        void aNegativeRateTruncatesTowardZero() {
            // Truncation toward zero means -15.999 becomes -15.99, not -16.00. The distinction only
            // shows up on negative values, and this record can hold them because L9 declares S9.
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal("-15.999"));

            assertThat(record.disIntRate())
                    .isEqualTo(new BigDecimal("-15.99"))
                    .isNotEqualTo(new BigDecimal("-16.00"));
            assertThat(record.disIntRate()).isLessThan(BigDecimal.ZERO);
            assertThat(record.disIntRateIsZero()).isFalse();
            assertThat(record.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("An unrecognised trailing character fails loudly rather than decoding to a number")
        void anUnrecognisedTrailingCharacterIsRejected() {
            DisclosureGroupRecord broken = withRateImage("00150*");

            assertThat(broken.disIntRateImage()).isEqualTo("00150*");
            assertThatIllegalArgumentException().isThrownBy(broken::disIntRate)
                    .withMessageContaining("neither a digit nor a sign overpunch character");
            assertThatIllegalArgumentException().isThrownBy(broken::disIntRateIsZero);
        }

        @Test
        @DisplayName("A non-digit in a leading position is rejected too")
        void aNonDigitInALeadingPositionIsRejected() {
            DisclosureGroupRecord broken = withRateImage("0 150{");

            assertThatIllegalArgumentException().isThrownBy(broken::disIntRate)
                    .withMessageContaining("holds only the digits 0 to 9");
        }
    }

    /**
     * The {@code REDEFINES} overlay, gate G34: two typed accessors over one backing span.
     *
     * <p>{@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L5} is a COBOL group item over the three
     * elementary items at L6 to L8. In a flattened layout that is an overlay: it views storage the
     * members already account for, so it neither advances the layout cursor nor contributes to the
     * record total. Reading the whole key and reading its three parts therefore address the same bytes
     * and can never disagree - which is what the mutation tests below actually prove.
     *
     * <p>This mirrors the program: {@code app/cbl/CBACT04C.cbl:L210-L212} builds the key by moving into
     * the three sub-items and then reads the file by the group, so a caller sets the parts and reads
     * the whole. There is deliberately no setter for the group, because no COBOL statement in this
     * codebase moves into it.
     */
    @Nested
    @DisplayName("The DIS-GROUP-KEY overlay - gate G34, two accessors over one span")
    class RedefinesOverlay {

        @Test
        @DisplayName("The key overlay and its three members address exactly the same bytes")
        void theOverlayAndItsMembersShareOneBackingSpan() {
            FieldSpan key = DisclosureGroupRecord.DIS_GROUP_KEY_SPAN;

            assertThat(key.redefinition()).as("a group item is an overlay, not extra storage").isTrue();
            assertThat(key.offset()).isEqualTo(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN.offset());
            assertThat(key.endOffsetExclusive())
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN.endOffsetExclusive());

            DisclosureGroupRecord record = row1();
            assertThat(record.disGroupKey())
                    .isEqualTo(record.disAcctGroupId() + record.disTranTypeCd()
                            + record.disTranCatCdImage());
        }

        @Test
        @DisplayName("Writing through a member is observed through the overlay, and the row bytes")
        void aWriteThroughAMemberIsSeenThroughTheOverlay() {
            DisclosureGroupRecord record = row1();
            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");

            record.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);
            assertThat(record.disGroupKey())
                    .as("the overlay sees the padded literal immediately")
                    .isEqualTo("DEFAULT   " + "01" + "0001");

            record.disTranTypeCd("07");
            assertThat(record.disGroupKey()).isEqualTo("DEFAULT   " + "07" + "0001");

            record.disTranCatCd(4);
            assertThat(record.disGroupKey()).isEqualTo("DEFAULT   " + "07" + "0004");

            // And the whole-record image agrees with the overlay, byte for byte.
            assertThat(record.encodeToString().substring(0, 16)).isEqualTo(record.disGroupKey());
            assertThat(new String(record.disGroupKeyBytes(), ASCII)).isEqualTo(record.disGroupKey());
        }

        @Test
        @DisplayName("Reading the overlay never disturbs its members, and the round trip is exact")
        void readingTheOverlayIsNonDestructive() {
            DisclosureGroupRecord record = row1();

            String beforeKey = record.disGroupKey();
            byte[] beforeBytes = record.disGroupKeyBytes();

            // Read the overlay and every member repeatedly; nothing may change.
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(record.disGroupKey()).isEqualTo(beforeKey);
                assertThat(record.disGroupKeyBytes()).isEqualTo(beforeBytes);
                assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);
                assertThat(record.disTranTypeCd()).isEqualTo("01");
                assertThat(record.disTranCatCd()).isEqualTo(1);
            }
            assertThat(record.encodeToString()).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("Every exposed byte array is a copy, so no caller can mutate the record")
        void everyExposedByteArrayIsACopy() {
            DisclosureGroupRecord record = row1();

            byte[] keyBytes = record.disGroupKeyBytes();
            byte[] fillerBytes = record.fillerBytes();
            byte[] whole = record.encode();
            assertThat(keyBytes).isNotSameAs(record.disGroupKeyBytes());
            assertThat(fillerBytes).isNotSameAs(record.fillerBytes());
            assertThat(whole).isNotSameAs(record.encode());

            // Corrupting every returned array must leave the record untouched.
            Arrays.fill(keyBytes, (byte) 'Z');
            Arrays.fill(fillerBytes, (byte) 'Z');
            Arrays.fill(whole, (byte) 'Z');

            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.encodeToString()).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("Decode copies its input, so mutating the caller's array cannot alter the record")
        void decodeCopiesItsInput() {
            byte[] source = ROW_1.getBytes(ASCII);
            DisclosureGroupRecord record = DisclosureGroupRecord.decode(source, ASCII);

            Arrays.fill(source, (byte) 'Z');

            assertThat(record.encodeToString()).isEqualTo(ROW_1);
            assertThat(record.disIntRate()).isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("A full key round trip: build from parts, read the whole, decode it back")
        void aFullKeyRoundTripThroughBothViews() {
            // The CBACT04C:L210-L212 shape: three MOVEs into the sub-items, then a keyed read by group.
            DisclosureGroupRecord built = new DisclosureGroupRecord(ASCII);
            built.disAcctGroupId(GROUP_ID_A);
            built.disTranTypeCd("01");
            built.disTranCatCd(1);
            built.disIntRate(new BigDecimal("15.00"));

            String key = built.disGroupKey();
            assertThat(key).hasSize(16).isEqualTo(ROW_1.substring(0, 16));

            // Decoding the encoded record recovers the same key through the same overlay.
            DisclosureGroupRecord reread = DisclosureGroupRecord.decode(built.encode(), ASCII);
            assertThat(reread.disGroupKey()).isEqualTo(key);
            assertThat(reread.disGroupKeyBytes()).isEqualTo(built.disGroupKeyBytes());
            assertThat(reread.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(reread.disTranTypeCd()).isEqualTo("01");
            assertThat(reread.disTranCatCd()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Value semantics - equals, hashCode and toString")
    class ValueSemantics {

        @Test
        @DisplayName("Equal items mean equal records, and equal hash codes")
        void equalItemsMeanEqualRecords() {
            DisclosureGroupRecord one = row1();
            DisclosureGroupRecord other = row1();

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(other).isEqualTo(one);
            assertThat(one).isEqualTo(one);
            assertThat(one.hashCode()).isEqualTo(one.hashCode());
        }

        @Test
        @DisplayName("A difference in the X(10) item alone breaks equality")
        void aDifferenceInTheAlphanumericItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            assertThat(changed).isNotEqualTo(base);
            assertThat(changed.disTranTypeCd()).isEqualTo(base.disTranTypeCd());
            assertThat(changed.disIntRate()).isEqualTo(base.disIntRate());
        }

        @Test
        @DisplayName("A difference in the X(02) item alone breaks equality, leading zero included")
        void aDifferenceInTheTypeCodeBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disTranTypeCd("02");

            assertThat(changed).isNotEqualTo(base);

            // And the leading zero is part of the value: '1 ' is not '01'.
            DisclosureGroupRecord unpadded = row1();
            unpadded.disTranTypeCd("1");
            assertThat(unpadded.disTranTypeCd()).isEqualTo("1 ");
            assertThat(unpadded).isNotEqualTo(base);
        }

        @Test
        @DisplayName("A difference in the 9(04) item alone breaks equality")
        void aDifferenceInTheNumericItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disTranCatCd(2);

            assertThat(changed).isNotEqualTo(base);
            assertThat(changed.disTranCatCdImage()).isEqualTo("0002");
        }

        @Test
        @DisplayName("A difference in the S9(04)V99 item alone breaks equality")
        void aDifferenceInTheScaledItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disIntRate(new BigDecimal("25.00"));

            assertThat(changed).isNotEqualTo(base);

            // Sign is part of the value too.
            DisclosureGroupRecord negated = row1();
            negated.disIntRate(new BigDecimal("-15.00"));
            assertThat(negated).isNotEqualTo(base);
        }

        @Test
        @DisplayName("A difference in FILLER alone breaks equality - no declared byte is excluded")
        void aDifferenceInFillerBreaksEquality() {
            DisclosureGroupRecord stored = row1();
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);
            fresh.disAcctGroupId(GROUP_ID_A);
            fresh.disTranTypeCd("01");
            fresh.disTranCatCd(1);
            fresh.disIntRate(new BigDecimal("15.00"));

            assertThat(fresh.filler()).isEqualTo(" ".repeat(28));
            assertThat(stored.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(fresh)
                    .as("the four data items match; only FILLER differs, and that is enough")
                    .isNotEqualTo(stored);
        }

        @Test
        @DisplayName("The BigDecimal scale trap: 15.0 and 15.00 are not equal, yet both store the same")
        void differentSendingScalesNormaliseToOneStoredValue() {
            // BigDecimal.equals is scale-sensitive, so this pair is NOT equal as raw values...
            assertThat(new BigDecimal("15.0").equals(new BigDecimal("15.00"))).isFalse();
            assertThat(new BigDecimal("15.0")).isEqualByComparingTo(new BigDecimal("15.00"));

            // ...but the field normalises every store to its declared scale of 2, so records built from
            // either sender are equal, hash equally and hold identical bytes. That normalisation is why
            // equals over the decoded items is consistent, and why testing the field AGAINST ZERO still
            // needs compareTo - see ZeroPredicate.
            DisclosureGroupRecord oneDecimal = row1();
            DisclosureGroupRecord twoDecimals = row1();
            oneDecimal.disIntRate(new BigDecimal("15.0"));
            twoDecimals.disIntRate(new BigDecimal("15.00"));

            assertThat(oneDecimal.disIntRate()).isEqualTo(twoDecimals.disIntRate()).hasScaleOf(2);
            assertThat(oneDecimal).isEqualTo(twoDecimals).hasSameHashCodeAs(twoDecimals);
            assertThat(oneDecimal.disIntRateImage()).isEqualTo(twoDecimals.disIntRateImage());
            assertThat(oneDecimal.encode()).isEqualTo(twoDecimals.encode());
        }

        @Test
        @DisplayName("Item equality is not byte equality, and the unsigned zoned form proves it")
        void itemEqualityIsNotByteEquality() {
            // 001500 (unsigned zone F) and 00150{ (overpunched +0) both decode to 15.00, so the records
            // are item-equal while their stored bytes differ. Where byte identity is what matters - a
            // parity round trip - encode() is the thing to compare, not equals().
            DisclosureGroupRecord unsigned = withRateImage("001500");
            DisclosureGroupRecord overpunched = withRateImage(ROW_1_RATE_IMAGE);

            assertThat(unsigned).isEqualTo(overpunched).hasSameHashCodeAs(overpunched);
            assertThat(unsigned.encode())
                    .as("equal items, different bytes")
                    .isNotEqualTo(overpunched.encode());
            assertThat(unsigned.disIntRateImage()).isNotEqualTo(overpunched.disIntRateImage());
        }

        @Test
        @DisplayName("A record never equals null, a String, or an unrelated type")
        void aRecordNeverEqualsNullOrAnotherType() {
            DisclosureGroupRecord record = row1();

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals(ROW_1)).isFalse();
            assertThat(record.equals(record.disIntRate())).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("toString names every item as the copybook spells it and hides no padding")
        void toStringSpeaksTheCopybooksVocabulary() {
            String rendered = row1().toString();

            assertThat(rendered)
                    .startsWith("DisclosureGroupRecord[")
                    .contains("DIS-ACCT-GROUP-ID='A000000000'")
                    .contains("DIS-TRAN-TYPE-CD='01'")
                    .contains("DIS-TRAN-CAT-CD=0001 (1)")
                    .contains("DIS-INT-RATE=15.00 (image '00150{')")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("charset=US-ASCII")
                    .endsWith("]");

            // Deterministic: no clock, no identity hash, no locale-dependent formatting.
            assertThat(rendered).isEqualTo(row1().toString());

            // The padding of DEFAULT is visible, which is the whole reason the items are quoted.
            DisclosureGroupRecord padded = row1();
            padded.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);
            assertThat(padded.toString()).contains("DIS-ACCT-GROUP-ID='DEFAULT   '");
        }
    }

    /**
     * Schema and state integrity: gate G44 (no schema artefacts of any kind) and gate G53 (no static
     * mutable state).
     *
     * <p>The migration reaches the existing datasets through plain JDBC with no DDL, no ORM, no
     * migration tooling and no schema change, so this record must carry no persistence mapping and no
     * row-version concept. The annotation checks below match by annotation <em>simple name</em>, which
     * is deliberate: no JPA artefact is declared in {@code app/java/pom.xml} and none may be added, so
     * the annotation classes are not on the classpath and cannot be referenced directly. Matching by
     * name means these assertions keep working - and keep failing if a mapping is ever introduced -
     * without pulling a forbidden dependency in to express them.
     */
    @Nested
    @DisplayName("Schema and state integrity - gates G44 and G53")
    class SchemaIntegrity {

        /**
         * The persistence-mapping annotation names that must never appear. Held as an immutable
         * {@code Set.of(...)} local to this class, matched by simple name so no JPA dependency is
         * needed to express the check.
         */
        private static final Set<String> FORBIDDEN_ANNOTATIONS =
                Set.of("Entity", "Table", "Id", "Column", "Version", "GeneratedValue", "Embeddable",
                        "MappedSuperclass", "JoinColumn", "SequenceGenerator");

        /** Substrings that would betray an embedded DDL statement in a constant. */
        private static final Set<String> DDL_KEYWORDS =
                Set.of("CREATE TABLE", "ALTER TABLE", "DROP TABLE", "CREATE INDEX", "PRIMARY KEY",
                        "FOREIGN KEY", "INSERT INTO", "SELECT ", "UPDATE ", "DELETE FROM");

        @Test
        @DisplayName("G44 - the type carries no persistence-mapping annotation at any level")
        void theTypeCarriesNoPersistenceMapping() {
            assertThat(DisclosureGroupRecord.class.getAnnotations())
                    .as("a copybook model is not a mapped entity")
                    .isEmpty();

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    assertThat(FORBIDDEN_ANNOTATIONS)
                            .as("field %s carries @%s", field.getName(),
                                    annotation.annotationType().getSimpleName())
                            .doesNotContain(annotation.annotationType().getSimpleName());
                }
            }
            for (Method method : DisclosureGroupRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    assertThat(FORBIDDEN_ANNOTATIONS)
                            .as("method %s carries @%s", method.getName(),
                                    annotation.annotationType().getSimpleName())
                            .doesNotContain(annotation.annotationType().getSimpleName());
                }
            }
        }

        @Test
        @DisplayName("G44 - no field implies a row version, and no constant holds a DDL statement")
        void noRowVersionAndNoEmbeddedDdl() throws IllegalAccessException {
            // Optimistic concurrency in this system is CBACT04C's and COACTUPC's own re-read-and-compare
            // (9300-CHECK-CHANGE-IN-REC), not a version column. Introducing one would be a schema
            // change, which is forbidden outright.
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("no field may imply a row version or a schema concept")
                        .doesNotContain("version")
                        .doesNotContain("sequence")
                        .doesNotContain("tablename");
            }

            // Every String constant is inspected for DDL. The declared constants are copybook item
            // names, the DEFAULT literal, and nothing else.
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    assertThat(value).as("constant %s must not hold SQL", field.getName()).isNotNull();
                    String upper = value.toUpperCase(Locale.ROOT);
                    for (String keyword : DDL_KEYWORDS) {
                        assertThat(upper)
                                .as("constant %s must not hold DDL or DML", field.getName())
                                .doesNotContain(keyword);
                    }
                }
            }
        }

        @Test
        @DisplayName("G44 - no dataset name is hard-coded into the model")
        void noDatasetNameIsHardCodedIntoTheModel() throws IllegalAccessException {
            // Dataset names live in application.yml, keyed by the CSD DSNAME values, so no
            // AWS.M2.CARDDEMO.* literal may appear in Java source.
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    assertThat((String) field.get(null))
                            .as("constant %s must not name a dataset", field.getName())
                            .doesNotContain("AWS.M2.CARDDEMO")
                            .doesNotContain("VSAM");
                }
            }
        }

        @Test
        @DisplayName("G53 - every static field is final and of a deeply immutable type")
        void everyStaticFieldIsFinalAndImmutable() {
            // COBOL WORKING-STORAGE must never become static Java state: that would break request
            // isolation and test determinism. The permitted types are the primitives, String, and the
            // two deeply immutable descriptor records - FieldSpan and RecordLayout, the latter copying
            // its span list defensively.
            Set<Class<?>> immutableTypes = Set.of(int.class, long.class, boolean.class, char.class,
                    short.class, byte.class, String.class, FieldSpan.class, RecordLayout.class,
                    BigDecimal.class, RoundingMode.class, PictureKind.class, Charset.class);
            int staticFields = 0;

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                staticFields++;
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s must not be an array - arrays are always mutable",
                                field.getName())
                        .isFalse();
                assertThat(immutableTypes)
                        .as("static field %s is of mutable type %s", field.getName(),
                                field.getType().getName())
                        .contains(field.getType());
            }

            assertThat(staticFields).as("the geometry constants and the layout").isPositive();
        }

        @Test
        @DisplayName("G53 - the instance fields are final too, and the byte area never escapes")
        void theInstanceFieldsAreFinalAndTheAreaNeverEscapes() {
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("instance field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isPublic(field.getModifiers()))
                        .as("instance field %s must not be public", field.getName())
                        .isFalse();
                assertThat(field.getType().isArray())
                        .as("instance field %s must not expose a raw array", field.getName())
                        .isFalse();
            }

            // No accessor returns the internal area itself: two records built from the same bytes hold
            // independent storage.
            DisclosureGroupRecord one = row1();
            DisclosureGroupRecord other = row1();
            one.disIntRate(new BigDecimal("25.00"));
            assertThat(other.disIntRate()).isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("G53 - this test class itself holds no mutable static state")
        void theTestClassItselfHoldsNoMutableStaticState() {
            // The same bar the class under test is held to. Every constant here is a primitive, a
            // String, a Charset, or an immutable Set/List; nothing is populated in a @BeforeAll.
            Set<Class<?>> immutableTypes = Set.of(int.class, String.class, Charset.class);

            for (Field field : DisclosureGroupRecordTest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static test field %s must be final", field.getName())
                        .isTrue();
                assertThat(immutableTypes)
                        .as("static test field %s is of mutable type %s", field.getName(),
                                field.getType().getName())
                        .contains(field.getType());
            }

            // The fixture is re-read into a fresh local list on every call, so no test can observe
            // another test's mutation of it.
            assertThat(fixtureRows()).isNotSameAs(fixtureRows()).isEqualTo(fixtureRows());
        }

        @Test
        @DisplayName("G53 - no nested test class holds mutable static state either")
        void noNestedTestClassHoldsMutableStaticState() {
            // The two lookup tables in this very class are static, so the bar is applied to the nested
            // classes as well rather than only to the enclosing one. Both are immutable Set.of views.
            int inspected = 0;

            for (Class<?> nested : DisclosureGroupRecordTest.class.getDeclaredClasses()) {
                for (Field field : nested.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    inspected++;
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s.%s must be final", nested.getSimpleName(),
                                    field.getName())
                            .isTrue();
                    assertThat(field.getType().isArray())
                            .as("static field %s.%s must not be an array", nested.getSimpleName(),
                                    field.getName())
                            .isFalse();
                }
            }

            assertThat(inspected)
                    .as("FORBIDDEN_ANNOTATIONS and DDL_KEYWORDS are the ones being checked")
                    .isGreaterThanOrEqualTo(2);
            assertThat(FORBIDDEN_ANNOTATIONS).isUnmodifiable();
            assertThat(DDL_KEYWORDS).isUnmodifiable();
        }

        @Test
        @DisplayName("G8 - exactly one Java type models CVTRA02Y, and it is final")
        void exactlyOneFinalTypeModelsTheCopybook() {
            assertThat(Modifier.isFinal(DisclosureGroupRecord.class.getModifiers()))
                    .as("a copybook model has no subtype: the layout is the layout")
                    .isTrue();
            assertThat(DisclosureGroupRecord.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(DisclosureGroupRecord.class.getInterfaces()).isEmpty();
            assertThat(DisclosureGroupRecord.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.account.model");
        }
    }
}
