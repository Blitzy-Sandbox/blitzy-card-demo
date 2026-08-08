package com.vsergeychik.carddemo.card.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CardXrefRecord}, the one Java type for {@code app/cpy/CVACT03Y.cpy}'s
 * {@code 01 CARD-XREF-RECORD} - exactly 50 bytes, and the most widely shared cross-reference layout
 * in the system.
 *
 * <p>Plain JUnit 5. No Spring context, no {@code @SpringBootTest}, no {@code MockMvc}: the class
 * under test is an immutable value type whose only collaborators are the two fixed-width classes in
 * {@code common}, so every decision inside it is reachable directly and every assertion here is
 * deterministic.
 *
 * <h2>Why a regression in this one type matters module-wide</h2>
 * Twelve of the twenty-eight programs {@code COPY CVACT03Y} - {@code CBACT03C}, {@code CBACT04C},
 * {@code CBSTM03A}, {@code CBTRN01C}, {@code CBTRN02C}, {@code CBTRN03C}, {@code COACTUPC},
 * {@code COACTVWC}, {@code COBIL00C}, {@code COCRDSLC}, {@code COCRDUPC} and {@code COTRN02C} -
 * and eleven of those twelve consumers live outside the {@code card} package. A single moved offset
 * here therefore radiates across the whole module, which is why every span below is asserted by its
 * literal offset and length rather than by a derived total.
 *
 * <p>The full 50-byte image is directly observable legacy output, not an internal detail:
 * {@code app/cbl/CBACT03C.cbl:78} and {@code :96} both execute {@code DISPLAY CARD-XREF-RECORD},
 * sending the entire record area - trailing {@code FILLER} spaces included - to {@code SYSOUT}. The
 * emitted width and byte content are exactly what the {@code CBACT03C} parity cases compare.
 *
 * <h2>The expected values are the copybook's and the fixture's, never the implementation's</h2>
 * Every offset, length, total and field value asserted below was transcribed by hand from
 * {@code app/cpy/CVACT03Y.cpy}:
 * <pre>
 *   *****************************************************************
 *   *    Data-structure for card xref (RECLN 50)
 *   *****************************************************************
 *    01 CARD-XREF-RECORD.
 *        05  XREF-CARD-NUM                     PIC X(16).
 *        05  XREF-CUST-ID                      PIC 9(09).
 *        05  XREF-ACCT-ID                      PIC 9(11).
 *        05  FILLER                            PIC X(14).
 * </pre>
 * 16 + 9 + 11 + 14 = 50, the {@code RECLN 50} the copybook's own header declares, with the four
 * spans starting at 0, 16, 25 and 36. The reference sources - {@code app/cpy/CVACT03Y.cpy},
 * {@code app/data/ASCII/cardxref.txt}, {@code app/csd/CARDDEMO.CSD} and {@code app/cbl/CBACT03C.cbl}
 * - are cited here as the binding contract and are <strong>never opened at runtime</strong>: they
 * are the parity oracle and are read-only. Runtime seeding uses the derived classpath copy
 * {@code src/test/resources/fixtures/cardxref.txt} instead.
 *
 * <h2>A deliberate, documented tension: {@code RECORDFORMAT(V)} against a fixed 50</h2>
 * {@code app/csd/CARDDEMO.CSD} declares {@code RECORDFORMAT(V)} for both cross-reference access
 * paths - at L43 for the {@code CCXREF} base cluster and at L69 for the {@code CXACAIX} alternate
 * index - while the batch JCL declares those datasets {@code RECFM=F} or {@code FB}. The two
 * disagree in the legacy definitions themselves. Per the migration's data-access ruling the Java
 * layer treats record length as <strong>copybook-fixed regardless</strong>, so every assertion below
 * asserts a fixed 50 and nothing here ever treats the record as variable-length. The disagreement is
 * recorded rather than repaired: "fixing" a legacy definition would be scope creep, and the CSD is
 * read-only reference material.
 *
 * <h2>Acceptance gates enforced directly by this file</h2>
 * <ul>
 *   <li><strong>G8</strong> - one Java type per copybook: {@code CardXrefRecord} is the sole type
 *       for {@code CVACT03Y}, and its four-span shape is asserted to match the copybook exactly.</li>
 *   <li><strong>G16</strong> / risk <strong>R-F</strong> - the 36-byte fixture row is widened to 50
 *       by the shared normaliser before any comparison, and a short row is rejected by the model.</li>
 *   <li><strong>G19</strong> - a serialised record is exactly 50 bytes.</li>
 *   <li><strong>G21</strong> - the trailing {@code FILLER X(14)} is present and space-filled.</li>
 *   <li><strong>G22</strong> - no {@code double} or {@code float} anywhere, asserted structurally.</li>
 *   <li><strong>G44</strong> - no persistence artefact: no entity annotation, no version column.</li>
 *   <li><strong>G49</strong> - both sides of every branch in {@code CardXrefRecord} are driven.</li>
 *   <li><strong>G52</strong> - every type imported explicitly; no wildcard import.</li>
 *   <li><strong>G53</strong> - no mutable static state and no dependence on execution order.</li>
 *   <li><strong>G54</strong> - runs non-interactively, with no clock, locale, network or
 *       platform-default-charset dependence.</li>
 * </ul>
 * No user rules were provided for this project, so the governing standard here is enterprise best
 * practice as codified by the migration plan itself - directives B1 through B12 - and this file cites
 * those directives rather than reproducing them.
 */
@DisplayName("CardXrefRecord - CVACT03Y CARD-XREF-RECORD, 50 bytes, 12 consumers")
class CardXrefRecordTest {

    /** The code page of the nine text fixtures under {@code app/data/ASCII}, named explicitly (B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The canonical name of the EBCDIC code page of the datasets under {@code app/data/EBCDIC}. */
    private static final String EBCDIC_NAME = "IBM037";

    /**
     * The EBCDIC code page, resolved fail-fast rather than assumed. Immutable, so this constant is
     * not mutable static state (G53).
     */
    private static final Charset EBCDIC = requireEbcdicCharset();

    /** The derived fixture on the test classpath. The reference data itself is never opened (B3). */
    private static final String FIXTURE = "/fixtures/cardxref.txt";

    /** Every row of the fixture is this wide, which is also {@code FILLER}'s offset. Risk R-F. */
    private static final int FIXTURE_ROW_WIDTH = 36;

    /** The fixture holds exactly this many cross-reference records. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /**
     * Row 1 of {@code app/data/ASCII/cardxref.txt}, verbatim - 36 bytes, because the fixture omits
     * the trailing {@code FILLER X(14)} that the copybook declares.
     */
    private static final String FIXTURE_ROW_1 = "050002445376574000000005000000000050";

    /** Row 2 of the fixture, verbatim. */
    private static final String FIXTURE_ROW_2 = "068358619817151600000002700000000027";

    /** Row 3 of the fixture, verbatim. */
    private static final String FIXTURE_ROW_3 = "092387719324733000000000200000000002";

    /** The three field values row 1 encodes, read off the row by hand rather than from the model. */
    private static final String ROW_1_CARD_NUM = "0500024453765740";

    private static final int ROW_1_CUST_ID = 50;

    private static final long ROW_1_ACCT_ID = 50L;

    /** The trailing {@code FILLER}'s content: fourteen spaces, because the copybook declares no VALUE. */
    private static final String FOURTEEN_SPACES = "              ";

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    /**
     * Resolves {@code IBM037} and fails with an actionable message if it is absent.
     *
     * <p>{@code IBM037} ships in the JDK's {@code jdk.charsets} module and is present in the verified
     * toolchain, OpenJDK 21.0.11. If it is genuinely missing, the run is on a cut-down runtime and the
     * fix is to install a full JDK - so this fails loudly, mirroring the charset configuration's own
     * fail-fast posture, rather than being skipped through an assumption. A skipped charset test would
     * report green while leaving every EBCDIC pad byte unverified (B7).
     */
    private static Charset requireEbcdicCharset() {
        if (!Charset.isSupported(EBCDIC_NAME)) {
            throw new IllegalStateException("Charset " + EBCDIC_NAME + " is required to verify that "
                    + "the code page is honoured as a parameter rather than defaulted. It ships in "
                    + "the JDK's jdk.charsets module and is present in OpenJDK 21.0.11, the verified "
                    + "toolchain for this module, so its absence means the run is on a cut-down "
                    + "runtime: install a full JDK rather than skipping this verification");
        }
        return Charset.forName(EBCDIC_NAME);
    }

    /** A representative record, used wherever the particular field values do not matter. */
    private static CardXrefRecord sample() {
        return new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
    }

    /**
     * Reads every row of the classpath fixture as text, exactly as stored and deliberately
     * <strong>un-widened</strong>, so that any test needing the copybook's declared width has to
     * normalise through the shared codec explicitly.
     *
     * <p>A fresh list is returned on every call, so no state is shared between test methods and no
     * test depends on another having run first (G53).
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = CardXrefRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream)
                    .as("the derived fixture must be on the test classpath at %s, copied from "
                            + "app/data/ASCII/cardxref.txt into "
                            + "app/java/src/test/resources/fixtures/cardxref.txt", FIXTURE)
                    .isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the CARDXREF fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * The {@code PIC X(16)} padding cases, supplied as arguments rather than as CSV because the
     * expected images are made entirely of significant trailing spaces, and a CSV column's whitespace
     * handling is exactly the kind of implicit behaviour that should not decide an assertion (B8).
     */
    private static List<Arguments> cardNumberPaddingCases() {
        return List.of(Arguments.of("", " ".repeat(16)),
                Arguments.of("0", "0" + " ".repeat(15)),
                Arguments.of("ABC", "ABC" + " ".repeat(13)),
                Arguments.of("012345678901234", "012345678901234 "),
                Arguments.of("0123456789012345", "0123456789012345"));
    }

    /**
     * Every stored fixture row, as {@code @ParameterizedTest} arguments, so that all
     * {@value #FIXTURE_ROW_COUNT} rows are driven individually and a failure names the offending row
     * rather than aborting the whole set at the first one.
     */
    private static List<Arguments> fixtureRowCases() {
        List<Arguments> cases = new ArrayList<>();
        List<String> rows = fixtureRows();
        for (int index = 0; index < rows.size(); index++) {
            cases.add(Arguments.of(index + 1, rows.get(index)));
        }
        return cases;
    }

    /**
     * Widens a stored fixture row to the copybook's declared width through the single shared
     * normaliser, {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}.
     *
     * <p>This is the whole of risk <strong>R-F</strong> and gate <strong>G16</strong>, and it is
     * expressed as a call rather than as arithmetic on purpose. The rule has exactly one home - the
     * codec, plus the parity harness that uses it - and re-implementing a right-pad in this file
     * would let a broken production normaliser sit behind green tests (B11).
     */
    private byte[] widenThroughTheSharedNormaliser(String storedRow) {
        return asciiCodec.padToDeclaredWidth(storedRow.getBytes(ASCII), CardXrefRecord.RECORD_LENGTH);
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (G8, G19)")
    class DeclaredGeometry {

        @Test
        @DisplayName("RECLN 50: the four declared spans sum to exactly the declared record length")
        void spansSumToTheDeclaredRecordLength() {
            int sum = 0;
            for (FieldSpan span : CardXrefRecord.LAYOUT.storageSpans()) {
                sum += span.length();
            }

            assertThat(CardXrefRecord.RECORD_LENGTH)
                    .as("CVACT03Y's header declares RECLN 50")
                    .isEqualTo(50);
            assertThat(CardXrefRecord.LAYOUT.recordLength())
                    .as("the layout's declared length must equal RECORD_LENGTH")
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            // Spelled out as the copybook spells it, so a reader can check it against L5-L8 by eye.
            assertThat(sum).isEqualTo(16 + 9 + 11 + 14).isEqualTo(50);
        }

        @Test
        @DisplayName("Four storage spans in copybook order, and no REDEFINES overlay")
        void declaresFourStorageSpansAndNoOverlay() {
            assertThat(CardXrefRecord.LAYOUT.storageSpans())
                    .containsExactly(CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER);
            // CVACT03Y declares no REDEFINES at all, so none may be invented here (B4).
            assertThat(CardXrefRecord.LAYOUT.redefinitions()).isEmpty();
        }

        @ParameterizedTest(name = "span {0}: {1} at offset {2}, length {3}, {4}")
        @CsvSource({
                "0, XREF-CARD-NUM,  0, 16, ALPHANUMERIC",
                "1, XREF-CUST-ID,  16,  9, UNSIGNED_NUMERIC",
                "2, XREF-ACCT-ID,  25, 11, UNSIGNED_NUMERIC",
                "3, FILLER,        36, 14, FILLER"
        })
        @DisplayName("Every span sits at its literal copybook offset with its literal copybook width")
        void spanGeometryIsLiteralAndPositional(int index,
                                                String cobolName,
                                                int offset,
                                                int length,
                                                PictureKind kind) {
            FieldSpan span = CardXrefRecord.LAYOUT.storageSpans().get(index);

            assertThat(span.name())
                    .as("span %d of CVACT03Y must be %s, verbatim", index, cobolName)
                    .isEqualTo(cobolName);
            assertThat(span.offset())
                    .as("%s must start at absolute 0-based offset %d", cobolName, offset)
                    .isEqualTo(offset);
            assertThat(span.length())
                    .as("%s at offset %d must be %d byte(s) wide", cobolName, offset, length)
                    .isEqualTo(length);
            assertThat(span.endOffsetExclusive())
                    .as("%s at offset %d must end at %d", cobolName, offset, offset + length)
                    .isEqualTo(offset + length);
            assertThat(span.kind())
                    .as("%s at offset %d carries the %s picture", cobolName, offset, kind)
                    .isEqualTo(kind);
            // No 05-item of CVACT03Y declares a VALUE, and none is a REDEFINES overlay.
            assertThat(span.hasInitialValue())
                    .as("%s declares no VALUE in the copybook", cobolName)
                    .isFalse();
            assertThat(span.redefinition())
                    .as("%s is storage, not a REDEFINES overlay", cobolName)
                    .isFalse();
        }

        @Test
        @DisplayName("The spans are contiguous from offset 0: no gap, no overlap, closing at 50")
        void spansAreContiguousWithNoGapAndNoOverlap() {
            List<FieldSpan> spans = CardXrefRecord.LAYOUT.storageSpans();

            assertThat(spans).as("CVACT03Y declares four 05-items at L5-L8").hasSize(4);
            assertThat(spans.get(0).offset())
                    .as("%s must open the record at offset 0", spans.get(0).name())
                    .isZero();
            for (int index = 0; index < spans.size() - 1; index++) {
                FieldSpan current = spans.get(index);
                FieldSpan next = spans.get(index + 1);
                int expectedNextOffset = current.offset() + current.length();

                assertThat(next.offset())
                        .as("%s occupies [%d, %d), so %s must start at %d - a gap would leave a byte "
                                        + "undeclared and an overlap would double-count one",
                                current.name(), current.offset(), expectedNextOffset,
                                next.name(), expectedNextOffset)
                        .isEqualTo(expectedNextOffset);
            }
            FieldSpan last = spans.get(spans.size() - 1);
            assertThat(last.offset() + last.length())
                    .as("the trailing %s at offset %d must close the record at %d",
                            last.name(), last.offset(), CardXrefRecord.RECORD_LENGTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The published offset and length constants agree with the layout descriptors")
        void publishedConstantsAgreeWithTheDescriptors() {
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_OFFSET).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_LENGTH).isEqualTo(9);
            // Twenty-five, not sixteen: CVACT02Y's CardRecord puts ITS account id at 16 because it
            // has no customer id in between. Reading this one at 16 would return a plausible number.
            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET).isEqualTo(25);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);

            assertThat(CardXrefRecord.XREF_CARD_NUM.offset())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_OFFSET);
            assertThat(CardXrefRecord.XREF_CARD_NUM.length())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(CardXrefRecord.XREF_CUST_ID.offset())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_CUST_ID.length())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_LENGTH);
            assertThat(CardXrefRecord.XREF_ACCT_ID.offset())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_ACCT_ID.length())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_LENGTH);
            assertThat(CardXrefRecord.FILLER.offset()).isEqualTo(CardXrefRecord.FILLER_OFFSET);
            assertThat(CardXrefRecord.FILLER.length()).isEqualTo(CardXrefRecord.FILLER_LENGTH);
        }

        @Test
        @DisplayName("Field names are carried verbatim from the copybook, hyphens and all")
        void fieldNamesAreVerbatim() {
            assertThat(CardXrefRecord.XREF_CARD_NUM_NAME).isEqualTo("XREF-CARD-NUM");
            assertThat(CardXrefRecord.XREF_CUST_ID_NAME).isEqualTo("XREF-CUST-ID");
            assertThat(CardXrefRecord.XREF_ACCT_ID_NAME).isEqualTo("XREF-ACCT-ID");
            assertThat(CardXrefRecord.XREF_CARD_NUM.name())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_NAME);
            assertThat(CardXrefRecord.XREF_CUST_ID.name())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_NAME);
            assertThat(CardXrefRecord.XREF_ACCT_ID.name())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);
            // Names are the parity differ's keys, so they are looked up case-sensitively and exactly.
            assertThat(CardXrefRecord.LAYOUT.hasSpan("XREF-CARD-NUM")).isTrue();
            assertThat(CardXrefRecord.LAYOUT.hasSpan("xref-card-num")).isFalse();
            assertThat(CardXrefRecord.LAYOUT.hasSpan("XREF_CARD_NUM")).isFalse();
        }

        @Test
        @DisplayName("The trailing FILLER is a first-class span at [36, 50), not an implicit gap (G21)")
        void fillerIsAFirstClassSpan() {
            assertThat(CardXrefRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(CardXrefRecord.FILLER.kind().filler()).isTrue();
            assertThat(CardXrefRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            // No VALUE in the copybook, so the FILLER's content is the code page's space byte.
            assertThat(CardXrefRecord.FILLER.hasInitialValue()).isFalse();
            // FILLER is not a referable COBOL name, so it is not resolvable by name.
            assertThat(CardXrefRecord.LAYOUT.hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.span("FILLER"))
                    .withMessageContaining("FILLER is not referable");
            // 36 is exactly the declared width less the trailing FILLER, which is why the fixture is
            // 36 bytes wide - risk R-F, addressed in full in the fixture group below.
            assertThat(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET)
                    .isEqualTo(FIXTURE_ROW_WIDTH);
        }

        @Test
        @DisplayName("The picture bounds are derived from the declared digit counts")
        void pictureBoundsFollowTheDeclaredDigitCounts() {
            assertThat(CardXrefRecord.XREF_CUST_ID_MAX_VALUE)
                    .as("PIC 9(09) holds nine nines")
                    .isEqualTo(999_999_999);
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE)
                    .as("PIC 9(11) holds eleven nines")
                    .isEqualTo(99_999_999_999L);
            // PIC 9(11) genuinely exceeds the int range, which is why the account id is a long while
            // the customer id is an int. Neither is ever a binary floating-point primitive (G22).
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE).isGreaterThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("The layout self-check passes for the copybook's own descriptor set")
        void layoutSelfCheckPassesForTheCopybooksDescriptors() {
            // Re-declaring the identical set proves the self-check accepts it, and that LAYOUT is not
            // merely a field that happened to initialise before any verification ran.
            RecordLayout reDeclared = RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                    CardXrefRecord.XREF_CARD_NUM,
                    CardXrefRecord.XREF_CUST_ID,
                    CardXrefRecord.XREF_ACCT_ID,
                    CardXrefRecord.FILLER);

            assertThat(reDeclared).isEqualTo(CardXrefRecord.LAYOUT);
            assertThat(reDeclared.recordLength()).isEqualTo(50);
            assertThat(reDeclared.storageSpans()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("The total-width self-check must also FAIL - a passing check proves nothing alone")
    class LayoutSelfCheckRejections {

        @Test
        @DisplayName("Dropping the trailing FILLER is rejected: 36 declared against a record length of 50")
        void droppingTheTrailingFillerIsRejected() {
            // The single most consequential transcription error this layout can suffer. Without the
            // FILLER the record would be 36 bytes, silently shifting every subsequent byte offset in
            // the entire dataset - which is precisely the shape of the fixture deviation, risk R-F.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("FILLER");
        }

        @Test
        @DisplayName("A gap between two spans is rejected: every byte must be declared")
        void aGapBetweenSpansIsRejected() {
            // Omitting XREF-CUST-ID leaves bytes [16, 25) undeclared.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("gap")
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @Test
        @DisplayName("An overlap between two spans is rejected unless declared as a REDEFINES overlay")
        void anOverlapBetweenSpansIsRejected() {
            // XREF-CUST-ID mistyped one byte early, at 15 instead of 16, overlapping the card number.
            FieldSpan overlapping = FieldSpan.unsignedNumeric(CardXrefRecord.XREF_CUST_ID_NAME,
                    15, CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            overlapping,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("overlap")
                    .withMessageContaining("XREF-CUST-ID");
        }

        @Test
        @DisplayName("A duplicated referable name is rejected; only FILLER may legitimately repeat")
        void aDuplicatedReferableNameIsRejected() {
            FieldSpan duplicate = FieldSpan.alphanumeric(CardXrefRecord.XREF_CARD_NUM_NAME,
                    CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            duplicate,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("XREF-CARD-NUM")
                    .withMessageContaining("more than once");
        }

        @Test
        @DisplayName("An over-wide FILLER is rejected: 51 declared against a record length of 50")
        void anOverWideFillerIsRejected() {
            FieldSpan tooWide = FieldSpan.filler(CardXrefRecord.FILLER_OFFSET,
                    CardXrefRecord.FILLER_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID,
                            tooWide))
                    .withMessageContaining("51")
                    .withMessageContaining("50");
        }
    }

    @Nested
    @DisplayName("Two keys, one 50-byte record - the base KSDS and the alternate index")
    class TwoKeysOneRecord {

        // app/csd/CARDDEMO.CSD is unambiguous about the two access paths, in its own words:
        //   L37  DEFINE FILE(CCXREF)
        //   L38  DESCRIPTION(CARD TO ACCOUNT XREF)
        //   L39  DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)          <- base cluster, card-number key
        //   L63  DEFINE FILE(CXACAIX)
        //   L64  DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)
        //   L65  DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH)      <- a PATH over that same base
        // Both address ONE record in ONE dataset through two access paths. The alternate index is
        // never a second table and never a second record type, so this model exposes two key
        // accessors over one 50-byte span rather than modelling two entities. (Whether a repository
        // honours that is gate G45, asserted at the repository level, not here.)

        @Test
        @DisplayName("The CCXREF base key is XREF-CARD-NUM: 16 bytes at offset 0")
        void baseKeyIsTheCardNumberAtOffsetZero() {
            CardXrefRecord record = sample();
            byte[] image = record.encode(ASCII);

            assertThat(record.cardNumberKey())
                    .as("the base CCXREF key is the raw PIC X(16) image")
                    .isEqualTo(ROW_1_CARD_NUM)
                    .isEqualTo(record.xrefCardNum())
                    .hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(new String(image, CardXrefRecord.XREF_CARD_NUM_OFFSET,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH, ASCII))
                    .as("the serialised base key occupies bytes [0, 16) of the record")
                    .isEqualTo(record.cardNumberKey());
            assertThat(record.cardNumberKey().getBytes(ASCII))
                    .as("the serialised key image is exactly 16 bytes")
                    .hasSize(16);
        }

        @Test
        @DisplayName("The CXACAIX alternate key is XREF-ACCT-ID: 11 bytes at offset 25, not 16")
        void alternateIndexKeyIsTheAccountIdAtOffsetTwentyFive() {
            // Three deliberately distinguishable field values, so a wrong offset cannot coincidentally
            // produce the right answer.
            CardXrefRecord record = new CardXrefRecord("1111222233334444", 555_555_555, 77_777_777_777L);
            byte[] image = record.encode(ASCII);

            assertThat(record.accountIdAlternateIndexKey())
                    .as("the CXACAIX key is XREF-ACCT-ID")
                    .isEqualTo(77_777_777_777L)
                    .isEqualTo(record.xrefAcctId());
            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("the serialised alternate key occupies bytes [25, 36)")
                    .isEqualTo("77777777777")
                    .hasSize(11);

            // Offset 16 is where CVACT02Y's CardRecord keeps ITS account id. Here it is the customer
            // id: reading the account id from 16 compiles, type-checks, and returns a wrong number.
            assertThat(new String(image, 16, 9, ASCII)).isEqualTo("555555555");
            assertThat(new String(image, 16, 11, ASCII)).isNotEqualTo("77777777777");
        }

        @Test
        @DisplayName("The alternate key image is 11 bytes left-zero-filled: account 50 is 00000000050")
        void alternateKeyImageIsLeftZeroFilledToElevenBytes() {
            byte[] image = sample().encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("PIC 9(11) is right justified and zero-filled on the left")
                    .isEqualTo("00000000050")
                    .hasSize(11);
            assertThat(sample().accountIdAlternateIndexKey()).isEqualTo(ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("XREF-CARD-NUM is a String, never a numeric type: the leading zero is data")
        void cardNumberIsAStringSoLeadingZerosSurvive() throws NoSuchMethodException {
            // Structural first: the accessor's STATIC type is not numeric. A long or a BigInteger here
            // would reduce 0500024453765740 to 500024453765740 - silent data loss that no compiler
            // catches and that breaks every keyed read on the base KSDS.
            Class<?> baseKeyType = CardXrefRecord.class.getMethod("cardNumberKey").getReturnType();
            Class<?> cardNumType = CardXrefRecord.class.getMethod("xrefCardNum").getReturnType();

            assertThat(baseKeyType).isEqualTo(String.class).isEqualTo(cardNumType);
            assertThat(baseKeyType.isPrimitive())
                    .as("a primitive card number could not carry a leading zero")
                    .isFalse();
            assertThat(Number.class.isAssignableFrom(baseKeyType))
                    .as("XREF-CARD-NUM is PIC X(16) - alphanumeric, not numeric")
                    .isFalse();

            // Then behaviourally: the leading zero survives a full round trip through the bytes.
            CardXrefRecord decoded = CardXrefRecord.decode(sample().encode(ASCII), ASCII);
            assertThat(decoded.xrefCardNum()).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.cardNumberKey()).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            // The loss a numeric type would have caused, made explicit.
            assertThat(Long.toString(Long.parseLong(ROW_1_CARD_NUM))).doesNotStartWith("0");
        }

        @Test
        @DisplayName("The two keys are different fields of different pictures, not interchangeable")
        void theTwoKeysAreDistinct() throws NoSuchMethodException {
            CardXrefRecord record = new CardXrefRecord("0000000000000050", 50, 50L);

            // Both happen to denote 50 here, yet one is a 16-character image and the other an
            // 11-digit number: the picture, not the value, is what makes them different kinds of key.
            assertThat(record.cardNumberKey()).isEqualTo("0000000000000050").hasSize(16);
            assertThat(record.accountIdAlternateIndexKey()).isEqualTo(50L);
            assertThat(CardXrefRecord.class.getMethod("accountIdAlternateIndexKey").getReturnType())
                    .as("XREF-ACCT-ID is PIC 9(11), which exceeds the int range")
                    .isEqualTo(long.class);
        }
    }

    @Nested
    @DisplayName("Encoding - always the complete 50-byte image, FILLER included (G19, G21)")
    class Encoding {

        @Test
        @DisplayName("A freshly serialised image is exactly 50 bytes, by every encode route")
        void serialisedWidthIsAlwaysFifty() {
            CardXrefRecord record = sample();

            assertThat(record.encode(ASCII)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(EBCDIC)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(asciiCodec)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(ebcdicCodec)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthRecord(asciiCodec).recordLength())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthRecord(ebcdicCodec).toByteArray())
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The complete image of fixture row 1, byte for byte: 36 data bytes then 14 spaces")
        void completeImageOfFixtureRowOne() {
            String image = new String(sample().encode(ASCII), ASCII);

            assertThat(image)
                    .as("what CBACT03C's DISPLAY CARD-XREF-RECORD puts on SYSOUT")
                    .isEqualTo(FIXTURE_ROW_1 + FOURTEEN_SPACES)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("G21: bytes 36..49 are fourteen spaces - the FILLER is emitted, never optimised away")
        void fillerIsEmittedAsFourteenSpaces() {
            byte[] image = sample().encode(ASCII);

            // The total width is the immediate tripwire: dropping the FILLER makes this 36.
            assertThat(image)
                    .as("a record missing its FILLER would be %d bytes, not %d",
                            FIXTURE_ROW_WIDTH, CardXrefRecord.RECORD_LENGTH)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            // Then the slice, so the diagnosis is unambiguous rather than "the width is wrong".
            assertThat(new String(image, CardXrefRecord.FILLER_OFFSET,
                    CardXrefRecord.FILLER_LENGTH, ASCII))
                    .isEqualTo(FOURTEEN_SPACES)
                    .hasSize(14);
            // And byte by byte, so a failure names the exact offset that diverged (B11).
            for (int offset = CardXrefRecord.FILLER_OFFSET;
                 offset < CardXrefRecord.RECORD_LENGTH;
                 offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at offset %d must be the US-ASCII space 0x20, never a NUL",
                                offset)
                        .isEqualTo((byte) 0x20);
            }
        }

        @Test
        @DisplayName("The FILLER uses the code page's own space byte: 0x20 in ASCII, 0x40 in IBM037")
        void fillerUsesTheCodePagesOwnSpaceByte() {
            byte[] ascii = sample().encode(ASCII);
            byte[] ebcdic = sample().encode(EBCDIC);

            for (int offset = CardXrefRecord.FILLER_OFFSET;
                 offset < CardXrefRecord.RECORD_LENGTH;
                 offset++) {
                assertThat(ascii[offset]).as("US-ASCII space at offset %d", offset)
                        .isEqualTo((byte) 0x20);
                // A hard-coded 0x20 here would corrupt every pad byte of an EBCDIC record, which is
                // exactly why the charset is a parameter and never a platform default (B8).
                assertThat(ebcdic[offset]).as("IBM037 space at offset %d", offset)
                        .isEqualTo((byte) 0x40);
            }
        }

        @ParameterizedTest(name = "XREF-CUST-ID {0} encodes as {1}")
        @CsvSource({
                "0,         000000000",
                "1,         000000001",
                "50,        000000050",
                "27,        000000027",
                "2,         000000002",
                "999999999, 999999999"
        })
        @DisplayName("PIC 9(09) is right justified and zero-filled on the left")
        void customerIdIsLeftZeroFilledToNineDigits(int custId, String expectedImage) {
            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_CUST_ID_LENGTH, ASCII))
                    .as("XREF-CUST-ID at offset 16")
                    .isEqualTo(expectedImage)
                    .hasSize(9);
        }

        @ParameterizedTest(name = "XREF-ACCT-ID {0} encodes as {1}")
        @CsvSource({
                "0,           00000000000",
                "50,          00000000050",
                "27,          00000000027",
                "2,           00000000002",
                "99999999999, 99999999999"
        })
        @DisplayName("PIC 9(11) is right justified and zero-filled on the left")
        void accountIdIsLeftZeroFilledToElevenDigits(long acctId, String expectedImage) {
            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("XREF-ACCT-ID at offset 25")
                    .isEqualTo(expectedImage)
                    .hasSize(11);
        }

        @ParameterizedTest(name = "card number \"{0}\" pads to \"{1}\"")
        @MethodSource("com.vsergeychik.carddemo.card.model.CardXrefRecordTest#cardNumberPaddingCases")
        @DisplayName("PIC X(16) is left justified and space-padded on the right")
        void cardNumberIsRightSpacePaddedToSixteen(String supplied, String expectedImage) {
            byte[] image = new CardXrefRecord(supplied, 0, 0L).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_CARD_NUM_OFFSET,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH, ASCII))
                    .as("XREF-CARD-NUM at offset 0")
                    .isEqualTo(expectedImage)
                    .hasSize(16);
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("An all-spaces card number with zero ids still occupies its full 50 bytes")
        void anEmptyRecordStillOccupiesFiftyBytes() {
            byte[] image = new CardXrefRecord("", 0, 0L).encode(ASCII);

            assertThat(new String(image, ASCII))
                    .isEqualTo(" ".repeat(16) + "000000000" + "00000000000" + FOURTEEN_SPACES)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("toFixedWidthRecord hands back a span addressable by the layout's own descriptors")
        void toFixedWidthRecordIsAddressableBySpan() {
            FixedWidthRecord record = sample().toFixedWidthRecord(asciiCodec);

            assertThat(record.charset())
                    .as("the span carries the code page it was built with, never a default")
                    .isEqualTo(ASCII);
            assertThat(record.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.readSpan(CardXrefRecord.XREF_CARD_NUM)).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.readSpan(CardXrefRecord.XREF_CUST_ID)).isEqualTo("000000050");
            assertThat(record.readSpan(CardXrefRecord.XREF_ACCT_ID)).isEqualTo("00000000050");
            assertThat(record.readSpan(CardXrefRecord.FILLER)).isEqualTo(FOURTEEN_SPACES);
            assertThat(record.readSpanBytes(CardXrefRecord.FILLER)).hasSize(14);
        }
    }

    @Nested
    @DisplayName("COBOL MOVE truncation direction - opposite for PIC X and PIC 9")
    class MoveSemantics {

        // The model's constructor deliberately REJECTS an over-wide value rather than reshaping it,
        // because an out-of-range argument is a defect in the caller. Where a real COBOL MOVE is being
        // translated, truncation IS the faithful behaviour, and it is applied at the call site through
        // the codec so the direction is a visible, deliberate choice - never a plain Java assignment,
        // which would neither pad nor truncate and would leave the defect invisible.

        @Test
        @DisplayName("PIC X(16) truncates on the RIGHT: 20 characters keep the FIRST 16")
        void alphanumericMoveTruncatesOnTheRight() {
            String sending = "01234567890123456789";
            assertThat(sending).hasSize(20);

            String moved = asciiCodec.movePicX(sending, CardXrefRecord.XREF_CARD_NUM_LENGTH);

            assertThat(moved)
                    .as("a PIC X receiver is filled from its leftmost position and discards the rest")
                    .isEqualTo("0123456789012345")
                    .hasSize(16);
            // The truncated value is then storable, and lands at offset 0 exactly as moved.
            byte[] image = new CardXrefRecord(moved, 0, 0L).encode(ASCII);
            assertThat(new String(image, 0, 16, ASCII)).isEqualTo("0123456789012345");
            // Whereas handing the untruncated value straight to the model is rejected, not reshaped.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(sending, 0, 0L))
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("PIC 9(11) truncates on the LEFT: 13 digits keep the RIGHTMOST 11")
        void accountIdMoveTruncatesOnTheLeft() {
            String moved = asciiCodec.movePic9("1234567890123", CardXrefRecord.XREF_ACCT_ID_LENGTH);

            assertThat(moved)
                    .as("a numeric receiver is aligned on its implied decimal point, so the high-order "
                            + "digits are the ones lost")
                    .isEqualTo("34567890123")
                    .hasSize(11);

            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, 0, Long.parseLong(moved)).encode(ASCII);
            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII)).isEqualTo("34567890123");
        }

        @Test
        @DisplayName("PIC 9(09) truncates on the LEFT: 11 digits keep the RIGHTMOST 9")
        void customerIdMoveTruncatesOnTheLeft() {
            String moved = asciiCodec.movePic9("12345678901", CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThat(moved).isEqualTo("345678901").hasSize(9);

            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, Integer.parseInt(moved), 0L)
                    .encode(ASCII);
            assertThat(new String(image, CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_CUST_ID_LENGTH, ASCII)).isEqualTo("345678901");
        }

        @Test
        @DisplayName("The two move rules genuinely disagree, which is why the caller must choose one")
        void theTwoMoveRulesDisagree() {
            String sending = "1234567890123";

            // Same sending value, same receiver width, opposite surviving digits.
            assertThat(asciiCodec.movePicX(sending, 11)).isEqualTo("12345678901");
            assertThat(asciiCodec.movePic9(sending, 11)).isEqualTo("34567890123");
            assertThat(asciiCodec.movePicX(sending, 11)).isNotEqualTo(asciiCodec.movePic9(sending, 11));
            // A plain Java assignment would do neither: it keeps all 13 characters, which cannot be
            // stored in an 11-byte span at all.
            assertThat(sending).hasSize(13);
            assertThat(sending.length()).isNotEqualTo(11);
        }

        @Test
        @DisplayName("A short numeric move zero-fills on the left, as MOVE '05' TO a PIC 9 field does")
        void aShortNumericMoveZeroFillsOnTheLeft() {
            assertThat(asciiCodec.movePic9("50", CardXrefRecord.XREF_CUST_ID_LENGTH))
                    .isEqualTo("000000050");
            assertThat(asciiCodec.movePic9(ROW_1_ACCT_ID, CardXrefRecord.XREF_ACCT_ID_LENGTH))
                    .isEqualTo("00000000050");
            assertThat(asciiCodec.movePicX("ABC", CardXrefRecord.XREF_CARD_NUM_LENGTH))
                    .isEqualTo("ABC             ");
        }
    }

    @Nested
    @DisplayName("Decoding - the declared width only, PIC X untrimmed, PIC 9 digits only")
    class Decoding {

        @Test
        @DisplayName("A 50-byte record decodes to its three named fields at their copybook offsets")
        void decodesAllThreeFields() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(decoded.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("PIC X decode does not trim: the trailing padding is part of the field's value")
        void alphanumericDecodeDoesNotTrim() {
            byte[] stored = ("ABC" + " ".repeat(13) + "000000001" + "00000000002" + FOURTEEN_SPACES)
                    .getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            // The parity differ compares this field byte for byte, so trimming here would discard the
            // very bytes it is meant to compare.
            assertThat(decoded.xrefCardNum())
                    .isEqualTo("ABC             ")
                    .hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(decoded.cardNumberKey()).hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(decoded.xrefCustId()).isEqualTo(1);
            assertThat(decoded.xrefAcctId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("All three decode entry points agree, so no route can drift from the others")
        void allThreeDecodeEntryPointsAgree() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            CardXrefRecord viaCharset = CardXrefRecord.decode(stored, ASCII);
            CardXrefRecord viaCodec = CardXrefRecord.decode(stored, asciiCodec);
            CardXrefRecord viaSpan = CardXrefRecord.decodeSpan(wrapped, asciiCodec);

            assertThat(viaCodec).isEqualTo(viaCharset);
            assertThat(viaSpan).isEqualTo(viaCharset);
            assertThat(viaSpan).isEqualTo(sample());
        }

        @ParameterizedTest(name = "a row of {0} byte(s) is rejected")
        @ValueSource(ints = {1, 35, 36, 49, 51, 100})
        @DisplayName("Any width other than 50 is rejected - the 36-byte fixture row included (R-F)")
        void rejectsEveryWidthOtherThanFifty(int width) {
            byte[] wrongWidth = "0".repeat(width).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .as("a %d-byte row is not a CVACT03Y record", width)
                    .isThrownBy(() -> CardXrefRecord.decode(wrongWidth, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(wrongWidth, asciiCodec));
        }

        @Test
        @DisplayName("A 36-byte span is rejected by the model and its message names the shared normaliser")
        void aShortSpanIsRejectedAndPointsAtTheNormaliser() {
            // The failing side of the model's own width self-check, driven by the exact width the
            // fixture actually has. The model must NOT self-heal a short row: widening belongs to
            // FixedWidthCodec and to the parity harness, so that the correction has one home. This is
            // risk R-F, and gate G16 requires the right-pad to happen before any comparison.
            FixedWidthRecord tooNarrow = new FixedWidthRecord(FIXTURE_ROW_WIDTH, ASCII);

            assertThat(tooNarrow.recordLength()).isEqualTo(FIXTURE_ROW_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(tooNarrow, asciiCodec))
                    .withMessageContaining("padToDeclaredWidth")
                    .withMessageContaining("50")
                    .withMessageContaining("36");
        }

        @Test
        @DisplayName("A span of exactly 50 bytes passes the width self-check - the other side of it")
        void aFiftyByteSpanPassesTheWidthSelfCheck() {
            FixedWidthRecord exact = asciiCodec.newRecord(CardXrefRecord.LAYOUT);

            assertThat(exact.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);
            CardXrefRecord decoded = CardXrefRecord.decodeSpan(exact, asciiCodec);
            // An INITIALIZEd area: PIC X spans are spaces, PIC 9 spans are zeros.
            assertThat(decoded.xrefCardNum()).isEqualTo(" ".repeat(16));
            assertThat(decoded.xrefCustId()).isZero();
            assertThat(decoded.xrefAcctId()).isZero();
        }

        @Test
        @DisplayName("A non-digit in either PIC 9 span fails loudly rather than decoding as zero")
        void rejectsNonDigitsInEitherNumericSpan() {
            byte[] custIdCorrupt =
                    (ROW_1_CARD_NUM + "0000X0050" + "00000000050" + FOURTEEN_SPACES).getBytes(ASCII);
            byte[] acctIdCorrupt =
                    (ROW_1_CARD_NUM + "000000050" + "000000000Z0" + FOURTEEN_SPACES).getBytes(ASCII);
            byte[] spacesInNumeric =
                    (ROW_1_CARD_NUM + "         " + "00000000050" + FOURTEEN_SPACES).getBytes(ASCII);

            // Silently yielding zero would hide a misaligned span behind a plausible value, which is
            // the hardest class of parity defect to trace.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(custIdCorrupt, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(acctIdCorrupt, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(spacesInNumeric, ASCII));
        }

        @Test
        @DisplayName("Nothing may be decoded from, or encoded into, an unstated code page")
        void nullArgumentsAreRejectedEverywhere() {
            byte[] stored = sample().encode(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            // The casts choose between the Charset and the codec overload for a literal null; the
            // first argument of decode needs none, because decodeSpan is named rather than overloaded.
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(stored, (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(stored, (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(wrapped, null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((FixedWidthCodec) null));
            assertThatNullPointerException().isThrownBy(() -> sample().toFixedWidthRecord(null));
        }
    }

    @Nested
    @DisplayName("Round trip - serialise, decode, re-serialise must be byte-identical")
    class RoundTrip {

        @Test
        @DisplayName("A 50-byte image survives decode then re-encode unchanged, byte for byte")
        void imageSurvivesDecodeThenReEncode() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);

            byte[] reEncoded = CardXrefRecord.decode(stored, ASCII).encode(ASCII);

            // Compared as bytes, not as trimmed strings, and never by reflective deep equality (B11).
            assertThat(reEncoded).isEqualTo(stored).hasSize(CardXrefRecord.RECORD_LENGTH);
            for (int offset = 0; offset < CardXrefRecord.RECORD_LENGTH; offset++) {
                assertThat(reEncoded[offset])
                        .as("byte at offset %d must survive the round trip unchanged", offset)
                        .isEqualTo(stored[offset]);
            }
        }

        @Test
        @DisplayName("The three fields survive field by field, compared individually not structurally")
        void fieldsSurviveFieldByField() {
            CardXrefRecord original = new CardXrefRecord("0683586198171516", 27, 27L);

            CardXrefRecord reDecoded = CardXrefRecord.decode(original.encode(ASCII), ASCII);

            // Field by field on purpose: a recursive or reflective comparison would pass even if the
            // fields had been silently swapped into differently named members.
            assertThat(reDecoded.xrefCardNum()).isEqualTo(original.xrefCardNum());
            assertThat(reDecoded.xrefCustId()).isEqualTo(original.xrefCustId());
            assertThat(reDecoded.xrefAcctId()).isEqualTo(original.xrefAcctId());
            assertThat(reDecoded.cardNumberKey()).isEqualTo(original.cardNumberKey());
            assertThat(reDecoded.accountIdAlternateIndexKey())
                    .isEqualTo(original.accountIdAlternateIndexKey());
        }

        @Test
        @DisplayName("The widest storable record round trips, proving both pictures are wide enough")
        void theWidestStorableRecordRoundTrips() {
            CardXrefRecord widest = new CardXrefRecord("9999999999999999",
                    CardXrefRecord.XREF_CUST_ID_MAX_VALUE,
                    CardXrefRecord.XREF_ACCT_ID_MAX_VALUE);

            byte[] image = widest.encode(ASCII);

            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(image, ASCII))
                    .isEqualTo("9999999999999999" + "999999999" + "99999999999" + FOURTEEN_SPACES);
            assertThat(CardXrefRecord.decode(image, ASCII)).isEqualTo(widest);
        }
    }

    @Nested
    @DisplayName("The 36-byte fixture and the shared 36-to-50 right pad (G16, risk R-F)")
    class ShortFixtureRows {

        @Test
        @DisplayName("The fixture really is 50 rows of 36 bytes, so the deviation is measured not assumed")
        void theFixtureIsFiftyRowsOfThirtySixBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows)
                    .as("the CARDXREF fixture holds one row per cross-reference record")
                    .hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d of %s", index + 1, FIXTURE)
                        .hasSize(FIXTURE_ROW_WIDTH);
            }
            // Risk R-F, asserted rather than described: app/data/ASCII/cardxref.txt carries 36 bytes
            // per record where app/cpy/CVACT03Y.cpy declares 50, because the fixture omits the
            // trailing FILLER X(14). This test also polices the derivation of the classpath copy - a
            // fixture that had been "helpfully" pre-padded to 50 would fail right here, and the
            // reference data must never be rewritten because it is the parity oracle.
            assertThat(FIXTURE_ROW_WIDTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET);
            assertThat(rows.get(0)).isEqualTo(FIXTURE_ROW_1);
            assertThat(rows.get(1)).isEqualTo(FIXTURE_ROW_2);
            assertThat(rows.get(2)).isEqualTo(FIXTURE_ROW_3);
        }

        @Test
        @DisplayName("The shared normaliser only APPENDS: bytes 0..35 are untouched, 36..49 are spaces")
        void theNormaliserOnlyAppends() {
            byte[] raw = FIXTURE_ROW_1.getBytes(ASCII);
            assertThat(raw).hasSize(FIXTURE_ROW_WIDTH);

            // Gate G16 / risk R-F: the 36-to-50 right pad is owned by FixedWidthCodec and by the parity
            // harness - never by this test and never by CardXrefRecord - and it must be applied before
            // any field-for-field comparison. Re-implementing the pad here would let this test pass
            // while the production normaliser was wrong, which is the precise failure mode directive
            // B11 exists to prevent, so this file contains no padding code of its own at all.
            byte[] widened = asciiCodec.padToDeclaredWidth(raw, CardXrefRecord.RECORD_LENGTH);

            assertThat(widened)
                    .as("the widened row is exactly the copybook's declared width")
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            for (int offset = 0; offset < FIXTURE_ROW_WIDTH; offset++) {
                assertThat(widened[offset])
                        .as("the correction must be non-destructive: byte at offset %d must be "
                                + "byte-identical to the original 36-byte fixture row", offset)
                        .isEqualTo(raw[offset]);
            }
            for (int offset = FIXTURE_ROW_WIDTH; offset < CardXrefRecord.RECORD_LENGTH; offset++) {
                assertThat(widened[offset])
                        .as("the appended FILLER byte at offset %d must be a space, because a FILLER "
                                + "carrying no VALUE holds spaces", offset)
                        .isEqualTo((byte) 0x20);
            }
            assertThat(new String(widened, ASCII)).isEqualTo(FIXTURE_ROW_1 + FOURTEEN_SPACES);
        }

        @Test
        @DisplayName("A widened row decodes, and the model re-emits exactly the widened bytes")
        void aWidenedRowDecodesAndReEmitsTheSameBytes() {
            byte[] widened = widenThroughTheSharedNormaliser(FIXTURE_ROW_1);

            CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

            assertThat(decoded.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(decoded.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
            assertThat(asciiCodec.readPicX(decoded.toFixedWidthRecord(asciiCodec),
                    CardXrefRecord.FILLER))
                    .as("the FILLER the normaliser appended reads back as fourteen spaces")
                    .isEqualTo(FOURTEEN_SPACES);
            // A round trip through the model must not reintroduce the 36-byte deviation.
            assertThat(decoded.encode(asciiCodec))
                    .isEqualTo(widened)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("An unwidened 36-byte row is rejected - the model never self-heals a short row")
        void anUnwidenedRowIsRejected() {
            byte[] raw = FIXTURE_ROW_1.getBytes(ASCII);

            // The binding ruling: widening is the caller's deliberate, visible act at the boundary.
            // If the model quietly tolerated 36 bytes, every field after the missing span would drift
            // in any dataset that genuinely was 50 bytes wide.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(raw, ASCII))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("The normaliser widens only: an over-long row is rejected, never truncated")
        void theNormaliserRefusesToTruncate() {
            byte[] tooLong = (FIXTURE_ROW_1 + FOURTEEN_SPACES + " ").getBytes(ASCII);
            assertThat(tooLong).hasSize(CardXrefRecord.RECORD_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> asciiCodec.padToDeclaredWidth(tooLong,
                            CardXrefRecord.RECORD_LENGTH))
                    .withMessageContaining("never truncates");
        }

        @ParameterizedTest(name = "row {0}: {1}")
        @MethodSource("com.vsergeychik.carddemo.card.model.CardXrefRecordTest#fixtureRowCases")
        @DisplayName("Every one of the 50 fixture rows widens, decodes and re-encodes byte-identically")
        void everyFixtureRowWidensDecodesAndReEncodes(int rowNumber, String storedRow) {
            assertThat(storedRow).as("row %d", rowNumber).hasSize(FIXTURE_ROW_WIDTH);

            byte[] widened = widenThroughTheSharedNormaliser(storedRow);
            CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

            // The expected values come from the row itself, sliced at the copybook's own offsets, so
            // this is an independent check on the transcription rather than a restatement of the model.
            assertThat(decoded.xrefCardNum())
                    .as("XREF-CARD-NUM of row %d, bytes [0, 16)", rowNumber)
                    .isEqualTo(storedRow.substring(0, 16));
            assertThat(decoded.xrefCustId())
                    .as("XREF-CUST-ID of row %d, bytes [16, 25)", rowNumber)
                    .isEqualTo(Integer.parseInt(storedRow.substring(16, 25)));
            assertThat(decoded.xrefAcctId())
                    .as("XREF-ACCT-ID of row %d, bytes [25, 36)", rowNumber)
                    .isEqualTo(Long.parseLong(storedRow.substring(25, 36)));
            assertThat(decoded.cardNumberKey())
                    .as("CCXREF base key of row %d", rowNumber)
                    .isEqualTo(storedRow.substring(0, 16));
            assertThat(decoded.accountIdAlternateIndexKey())
                    .as("CXACAIX alternate key of row %d", rowNumber)
                    .isEqualTo(Long.parseLong(storedRow.substring(25, 36)));
            assertThat(decoded.encode(asciiCodec))
                    .as("row %d must re-encode to exactly the widened bytes", rowNumber)
                    .isEqualTo(widened);
        }

        @ParameterizedTest(name = "row {0} decodes to card {1}, customer {2}, account {3}")
        @CsvSource({
                "1, 0500024453765740, 50, 50",
                "2, 0683586198171516, 27, 27",
                "3, 0923877193247330,  2,  2"
        })
        @DisplayName("The first three fixture rows decode to their hand-read field values")
        void theFirstThreeRowsDecodeToTheirHandReadValues(int rowNumber,
                                                          String cardNum,
                                                          int custId,
                                                          long acctId) {
            String storedRow = fixtureRows().get(rowNumber - 1);

            CardXrefRecord decoded =
                    CardXrefRecord.decode(widenThroughTheSharedNormaliser(storedRow), asciiCodec);

            assertThat(decoded.xrefCardNum()).isEqualTo(cardNum).startsWith("0");
            assertThat(decoded.xrefCustId()).isEqualTo(custId);
            assertThat(decoded.xrefAcctId()).isEqualTo(acctId);
            assertThat(decoded).isEqualTo(new CardXrefRecord(cardNum, custId, acctId));
        }
    }

    @Nested
    @DisplayName("The code page is always a parameter, never the platform default")
    class CharsetIsAlwaysAParameter {

        @Test
        @DisplayName("IBM037 is present in the verified toolchain, asserted rather than assumed away")
        void ibm037IsPresent() {
            // Asserted with an actionable message instead of Assumptions.assumeTrue, so the run stays
            // deterministic: a skipped charset test reports green while leaving every EBCDIC pad byte
            // unverified (B7). IBM037 ships in the JDK's jdk.charsets module and OpenJDK 21.0.11 is the
            // verified toolchain for this module.
            assertThat(Charset.isSupported(EBCDIC_NAME))
                    .as("%s must be available; it ships in the JDK's jdk.charsets module, so if this "
                            + "fails the runtime is a cut-down JDK and the fix is to install a full "
                            + "one - never to skip this verification", EBCDIC_NAME)
                    .isTrue();
            assertThat(EBCDIC.name()).isEqualTo(EBCDIC_NAME);
            assertThat(ASCII).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("The same record encodes to DIFFERENT bytes under US-ASCII and under IBM037")
        void theTwoCodePagesProduceDifferentImages() {
            CardXrefRecord record = sample();

            byte[] ascii = record.encode(ASCII);
            byte[] ebcdic = record.encode(EBCDIC);

            // If the charset parameter were ignored, or a platform default were consulted, these two
            // images would be identical and this assertion would collapse.
            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii).hasSameSizeAs(ebcdic).hasSize(CardXrefRecord.RECORD_LENGTH);
            // The measured pad and digit bytes of the two code pages.
            assertThat(ascii[0]).as("US-ASCII '0'").isEqualTo((byte) 0x30);
            assertThat(ebcdic[0]).as("IBM037 '0'").isEqualTo((byte) 0xF0);
            assertThat(ascii[CardXrefRecord.FILLER_OFFSET]).as("US-ASCII space").isEqualTo((byte) 0x20);
            assertThat(ebcdic[CardXrefRecord.FILLER_OFFSET]).as("IBM037 space").isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("Each image is self-consistent: decoding under its own code page returns the record")
        void eachImageIsSelfConsistentUnderItsOwnCodePage() {
            CardXrefRecord record = new CardXrefRecord("0923877193247330", 2, 2L);

            assertThat(CardXrefRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(asciiCodec), asciiCodec)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(ebcdicCodec), ebcdicCodec)).isEqualTo(record);
            // And the codecs really do carry the code page they were constructed with.
            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("No byte-level entry point offers a no-arg overload that could default the code page")
        void everyByteLevelEntryPointDemandsACodePage() {
            List<String> byteLevelOperations =
                    List.of("encode", "decode", "decodeSpan", "toFixedWidthRecord");

            int inspected = 0;
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                if (!byteLevelOperations.contains(method.getName()) || method.isSynthetic()) {
                    continue;
                }
                inspected++;
                boolean statesTheCodePage = false;
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (parameterType.equals(Charset.class)
                            || parameterType.equals(FixedWidthCodec.class)) {
                        statesTheCodePage = true;
                    }
                }
                assertThat(statesTheCodePage)
                        .as("%s must take a Charset or a FixedWidthCodec: a no-arg overload would be "
                                        + "an invitation to fall back on the platform default",
                                method.getName())
                        .isTrue();
            }
            assertThat(inspected)
                    .as("encode x2, decode x2, decodeSpan and toFixedWidthRecord")
                    .isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("Construction - every PICTURE bound enforced at the boundary, both sides driven")
    class Construction {

        @Test
        @DisplayName("The three fields are held exactly as supplied, with no normalisation")
        void holdsTheSuppliedFieldsVerbatim() {
            CardXrefRecord record = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(record.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(record.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
            // No trimming, no upper-casing, no re-formatting: the value is the bytes.
            assertThat(new CardXrefRecord("  ABC  ", 0, 0L).xrefCardNum()).isEqualTo("  ABC  ");
        }

        @ParameterizedTest(name = "a card number of {0} character(s) is accepted")
        @ValueSource(ints = {0, 1, 15, 16})
        @DisplayName("A card number up to and including PIC X(16) is accepted")
        void acceptsACardNumberUpToItsPicture(int length) {
            String supplied = "9".repeat(length);

            CardXrefRecord record = new CardXrefRecord(supplied, 0, 0L);

            assertThat(record.xrefCardNum()).isEqualTo(supplied).hasSize(length);
            assertThat(record.encode(ASCII)).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "a card number of {0} character(s) is rejected")
        @ValueSource(ints = {17, 20, 50})
        @DisplayName("A card number wider than PIC X(16) is rejected, and the message names the remedy")
        void rejectsAnOverWideCardNumber(int length) {
            String supplied = "9".repeat(length);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(supplied, 0, 0L))
                    .withMessageContaining("XREF-CARD-NUM")
                    .withMessageContaining("PIC X(16)")
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("A null card number is rejected: PIC X(16) has no null, only SPACES")
        void rejectsANullCardNumber() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardXrefRecord(null, 0, 0L))
                    .withMessageContaining("XREF-CARD-NUM");
            // The sanctioned way to say "blank": an empty string, which encodes as sixteen spaces.
            assertThat(new CardXrefRecord("", 0, 0L).xrefCardNum()).isEmpty();
        }

        @ParameterizedTest(name = "customer id {0} is rejected")
        @ValueSource(ints = {-1, -50, Integer.MIN_VALUE, 1_000_000_000, Integer.MAX_VALUE})
        @DisplayName("A customer id outside PIC 9(09) is rejected - negatives have nowhere to put a sign")
        void rejectsAnUnstorableCustomerId(int custId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L))
                    .withMessageContaining("XREF-CUST-ID");
        }

        @ParameterizedTest(name = "account id {0} is rejected")
        @ValueSource(longs = {-1L, -50L, Long.MIN_VALUE, 100_000_000_000L, Long.MAX_VALUE})
        @DisplayName("An account id outside PIC 9(11) is rejected - negatives included")
        void rejectsAnUnstorableAccountId(long acctId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId))
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @ParameterizedTest(name = "customer id {0} is accepted")
        @ValueSource(ints = {0, 1, 50, 999_999_998, 999_999_999})
        @DisplayName("Every value a PIC 9(09) can hold is accepted, boundaries included")
        void acceptsEveryStorableCustomerId(int custId) {
            assertThat(new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L).xrefCustId()).isEqualTo(custId);
        }

        @ParameterizedTest(name = "account id {0} is accepted")
        @ValueSource(longs = {0L, 1L, 50L, 99_999_999_998L, 99_999_999_999L})
        @DisplayName("Every value a PIC 9(11) can hold is accepted, boundaries included")
        void acceptsEveryStorableAccountId(long acctId) {
            assertThat(new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId).xrefAcctId()).isEqualTo(acctId);
        }
    }

    @Nested
    @DisplayName("Value semantics - what the parity differ and 9300-CHECK-CHANGE-IN-REC both rely on")
    class ValueSemantics {

        @Test
        @DisplayName("An instance equals itself, and equals a separately built instance of equal fields")
        void equalFieldsMeanEqualRecords() {
            CardXrefRecord one = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            CardXrefRecord other = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(one.equals(one)).as("reflexive: the identity short circuit").isTrue();
            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(other).as("symmetric").isEqualTo(one);
            // Repeated hashing of equal instances is stable, which is what caching them relies on.
            assertThat(one.hashCode()).isEqualTo(one.hashCode()).isEqualTo(other.hashCode());
        }

        @Test
        @DisplayName("A difference in XREF-CARD-NUM alone breaks equality")
        void aCardNumberDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base)
                    .isNotEqualTo(new CardXrefRecord("0500024453765741", ROW_1_CUST_ID, ROW_1_ACCT_ID));
        }

        @Test
        @DisplayName("A difference in XREF-CUST-ID alone breaks equality")
        void aCustomerIdDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, 51, ROW_1_ACCT_ID));
        }

        @Test
        @DisplayName("A difference in XREF-ACCT-ID alone breaks equality")
        void anAccountIdDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, 51L));
        }

        @Test
        @DisplayName("A record never equals null, a String, or an unrelated type")
        void neverEqualsNullOrAnUnrelatedType() {
            CardXrefRecord record = sample();

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals(ROW_1_CARD_NUM)).isFalse();
            assertThat(record.equals(Integer.valueOf(ROW_1_CUST_ID))).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("Padding participates in equality, because padding is data in a PIC X field")
        void paddingParticipatesInEquality() {
            // The COBOL 9300-CHECK-CHANGE-IN-REC comparison is byte-for-byte, so a trimmed value and a
            // padded value are genuinely different records and must not compare equal.
            assertThat(new CardXrefRecord("ABC", 1, 1L))
                    .isNotEqualTo(new CardXrefRecord("ABC             ", 1, 1L));
            assertThat(new CardXrefRecord("", 1, 1L))
                    .isNotEqualTo(new CardXrefRecord(" ".repeat(16), 1, 1L));
        }

        @Test
        @DisplayName("toString names every field by its copybook name and is deterministic")
        void toStringSpeaksTheCopybooksVocabulary() {
            String rendered = new CardXrefRecord("ABC", 50, 50L).toString();

            assertThat(rendered)
                    .isNotNull()
                    .startsWith("CARD-XREF-RECORD[")
                    // All three fields are masked: this record exists to link a card number to a
                    // customer and an account, so rendering it in full published that association. A
                    // value at or below the revealed length is masked entirely rather than disclosed.
                    .contains(CardXrefRecord.XREF_CARD_NUM_NAME + "='***'")
                    .contains(CardXrefRecord.XREF_CUST_ID_NAME + "=*****0050")
                    .contains(CardXrefRecord.XREF_ACCT_ID_NAME + "=*******0050")
                    .endsWith("]");
            // 'ABC' is masked entirely rather than shown, because showing four of three characters
            // would disclose the whole of a short identifier while looking as though it had been
            // masked - the most misleading of the available outcomes.
            assertThat(rendered).doesNotContain("ABC");
            // No clock, no identity hash, no locale-dependent formatting: the same record always
            // renders identically, which is what makes a failure message reproducible (B7).
            assertThat(rendered).isEqualTo(new CardXrefRecord("ABC", 50, 50L).toString());
            assertThat(sample().toString()).isNotNull().doesNotContain("@");
        }

        @Test
        @DisplayName("a control character in the retained suffix cannot forge a second log line")
        void theRetainedSuffixCannotForgeALogLine() {
            // XREF-CARD-NUM is PIC X(16), so this record holds whatever the CCXREF dataset holds and
            // nothing here validates it as digits. The mask covers the first twelve characters; the four
            // it reveals are exactly where a stored CR or LF survives, and appending them raw to a
            // string documented as safe to log lets the dataset append a log entry of its own (CWE-117).
            String rendered = new CardXrefRecord("411111111111\r\nOK", 50, 50L).toString();

            assertThat(rendered)
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .contains(CardXrefRecord.XREF_CARD_NUM_NAME + "='" + "*".repeat(12)
                            + "X'0D'X'0A'OK'");
            assertThat(rendered.lines())
                    .as("this rendering is documented as safe to log, which means one line")
                    .hasSize(1);
            // Lossless and rendering-only: the stored image is byte-identical to what was supplied,
            // because the parity harness reads it by name and must see the real bytes.
            assertThat(new CardXrefRecord("411111111111\r\nOK", 50, 50L).xrefCardNum())
                    .isEqualTo("411111111111\r\nOK");
        }
    }

    @Nested
    @DisplayName("Structural guards - gates G8, G22, G44 and G53 asserted about the type itself")
    class StructuralGuards {

        @Test
        @DisplayName("G22: no double, float, Double or Float in any field, accessor or constructor")
        void noBinaryFloatingPointAnywhere() {
            // CVACT03Y declares no COMP-3, no signed picture and no V scale, so there is nothing here
            // to round and the module's decimal helper is deliberately absent from this type's
            // dependency graph. A single double would put a representation error into a key field.
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("declared field %s", field.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .as("parameter types of %s", method.getName())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
            for (Constructor<?> constructor : CardXrefRecord.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("constructor parameter types")
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("The two numeric fields are an int and a long, sized by their declared digit counts")
        void theNumericFieldsAreIntegralAndCorrectlySized() throws NoSuchMethodException {
            assertThat(CardXrefRecord.class.getMethod("xrefCustId").getReturnType())
                    .as("PIC 9(09) fits an int")
                    .isEqualTo(int.class);
            assertThat(CardXrefRecord.class.getMethod("xrefAcctId").getReturnType())
                    .as("PIC 9(11) exceeds the int range, so it must be a long")
                    .isEqualTo(long.class);
        }

        @Test
        @DisplayName("G44: no persistence artefact - no entity annotation and no version field")
        void noPersistenceArtefacts() {
            assertThat(CardXrefRecord.class.getAnnotations())
                    .as("a plain value type carries no annotation at all: no entity mapping, no table, "
                            + "no index, and no Spring stereotype")
                    .isEmpty();
            assertNoPersistenceAnnotation(CardXrefRecord.class.getAnnotations());
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                assertNoPersistenceAnnotation(field.getAnnotations());
                String lowerCased = field.getName().toLowerCase(Locale.ROOT);
                assertThat(lowerCased)
                        .as("field %s must not be an optimistic-lock discriminator: the concurrency "
                                        + "check is the COBOL's own field-by-field re-read, and adding a "
                                        + "version column would be a schema change", field.getName())
                        .doesNotContain("version")
                        .doesNotContain("optimistic")
                        .doesNotContain("optlock");
            }
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                assertNoPersistenceAnnotation(method.getAnnotations());
            }
            for (Constructor<?> constructor : CardXrefRecord.class.getDeclaredConstructors()) {
                assertNoPersistenceAnnotation(constructor.getAnnotations());
            }
        }

        @Test
        @DisplayName("G8/G53: the only static members are the immutable layout constants")
        void onlyImmutableStaticConstantsAreDeclared() {
            List<String> mutableStatics = new ArrayList<>();
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("COBOL WORKING-STORAGE must never become a mutable static field: it would break "
                            + "request isolation and make every test order-dependent")
                    .isEmpty();
            // The three instance fields are final, so a decoded record is safe to share without copying.
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            // One type per copybook, and it is not extensible into a variant with different geometry.
            assertThat(Modifier.isFinal(CardXrefRecord.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("The layout is published as an immutable descriptor list the parity differ can read")
        void theLayoutIsPublishedImmutably() {
            List<FieldSpan> spans = CardXrefRecord.LAYOUT.spans();

            assertThat(spans).hasSize(4);
            // Publishing the layout cannot leak mutable state, so a repository or the parity differ may
            // read the field geometry without this type growing a parallel reflection surface.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> spans.add(CardXrefRecord.FILLER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.storageSpans().clear());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.span("NOT-A-CVACT03Y-FIELD"))
                    .withMessageContaining("declares no field named");
        }

        private void assertNoPersistenceAnnotation(Annotation[] annotations) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getName())
                        .as("annotation %s", annotation.annotationType().getName())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence");
            }
        }
    }
}
