package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TrnxRecord}, the 350-byte {@code TRNX-RECORD} of {@code app/cpy/COSTM01.CPY}.
 *
 * <p>Plain JUnit 5 throughout: no Spring context and no {@code JobLauncher}, because every decision in
 * the class under test is reachable without either. That is deliberate - the parity work has to be able
 * to assert record geometry and arithmetic with nothing in the path that could perturb it.
 *
 * <h2>What these tests are the audit of</h2>
 * The fixed-width codecs in this module are hand-written precisely so that every byte offset stays
 * reviewable against its copybook. That only holds if something actually checks the offsets, and this
 * class is that check for {@code COSTM01}. The layout asserted below is transcribed
 * <strong>by hand from the copybook</strong>, field for field and offset for offset, so the arithmetic
 * verified here is the copybook's own arithmetic rather than a restatement of the implementation. Field
 * names are carried verbatim, hyphens and all.
 *
 * <p>Both charsets are named explicitly and never defaulted, and the geometry and codec behaviour are
 * driven under each: {@code IBM037} for EBCDIC data and {@code US-ASCII} for the text fixtures. This
 * matters because the pad bytes differ - a space is {@code 0x40} under {@code IBM037} and {@code 0x20}
 * under {@code US-ASCII} - so a hard-coded ASCII pad would corrupt an EBCDIC record.
 *
 * <h2>Gates enforced here</h2>
 * <ul>
 *   <li><strong>G19</strong> - the record is byte-identical in width to its copybook declaration: the
 *       spans sum to exactly 350, the key is 32 bytes at offset 0 and the remainder 318 at offset 32.</li>
 *   <li><strong>G21</strong> - the trailing {@code FILLER X(20)} is present and space-filled in every
 *       serialised record, with a negative case proving a layout that drops it is rejected rather than
 *       silently producing 330 bytes.</li>
 *   <li><strong>G23</strong> - {@code TRNX-AMT} always carries scale exactly 2.</li>
 *   <li><strong>G24</strong> - excess fractional digits truncate toward zero and never round, because
 *       {@code ROUNDED} appears zero times in all 28 COBOL programs.</li>
 *   <li><strong>G51</strong> - the logic is reachable directly, with no HTTP layer and no job launcher.</li>
 * </ul>
 *
 * <h2>Fixture provenance</h2>
 * The field values used below are taken from the first record of {@code app/data/ASCII/dailytran.txt},
 * which is a {@code CVTRA06Y} record and therefore byte-identical in shape to the {@code CVTRA05Y}
 * {@code TRAN-RECORD} that {@code app/jcl/CREASTMT.JCL} sorts. Its amount image is
 * {@code 0000005047G}, that is {@code +504.77}. All 300 rows of that fixture carry a blank
 * {@code TRAN-PROC-TS}, since a <em>daily</em> transaction has not been processed yet, so the
 * {@code SORT}-derived off-by-two test supplies a fully populated 26-character process timestamp of its
 * own in order to make the legacy defect observable at all.
 */
@DisplayName("TrnxRecord - COSTM01 TRNX-RECORD, 350 bytes, 32-byte composite key")
class TrnxRecordTest {

    /** The text fixtures' code page. Named, never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets. Named, never defaulted. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // ---------------------------------------------------------------------------------------------
    // Field values from app/data/ASCII/dailytran.txt record 1.
    // ---------------------------------------------------------------------------------------------

    private static final String FIXTURE_TRAN_ID = "0000000000683580";
    private static final String FIXTURE_TYPE_CD = "01";
    private static final String FIXTURE_CAT_CD = "0001";
    private static final String FIXTURE_SOURCE = "POS TERM";
    private static final String FIXTURE_DESC = "Purchase at Abshire-Lowe";
    private static final String FIXTURE_AMT_IMAGE = "0000005047G";
    private static final String FIXTURE_MERCHANT_ID = "800000000";
    private static final String FIXTURE_MERCHANT_NAME = "Abshire-Lowe";
    private static final String FIXTURE_MERCHANT_CITY = "North Enoshaven";
    private static final String FIXTURE_MERCHANT_ZIP = "72112";
    private static final String FIXTURE_CARD_NUM = "4859452612877065";
    private static final String FIXTURE_ORIG_TS = "2022-06-10 19:27:53.000000";

    /**
     * A fully populated 26-character process timestamp. The fixture's own is blank, so this is supplied
     * to expose the {@code CREASTMT.JCL:54} off-by-two, which is invisible on a blank field.
     */
    private static final String FULL_PROC_TS = "2022-07-18 04:11:09.123456";

    // ---------------------------------------------------------------------------------------------
    // Helpers. These reproduce the legacy layout and the legacy sort, not the implementation.
    // ---------------------------------------------------------------------------------------------

    /** COBOL {@code PIC X} placement: left justified, padded on the right, truncated on the right. */
    private static String picX(String value, int width) {
        String truncated = value.length() > width ? value.substring(0, width) : value;
        return truncated + " ".repeat(width - truncated.length());
    }

    /** COBOL {@code PIC 9} placement: right justified, zero-filled on the left. */
    private static String pic9(String digits, int width) {
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Builds a 350-byte {@code CVTRA05Y TRAN-RECORD} image by absolute offset, in copybook order:
     * {@code TRAN-ID X(16)}, {@code TYPE-CD X(02)}, {@code CAT-CD 9(04)}, {@code SOURCE X(10)},
     * {@code DESC X(100)}, {@code AMT S9(09)V99} (11), {@code MERCHANT-ID 9(09)},
     * {@code MERCHANT-NAME X(50)}, {@code MERCHANT-CITY X(50)}, {@code MERCHANT-ZIP X(10)},
     * {@code CARD-NUM X(16)}, {@code ORIG-TS X(26)}, {@code PROC-TS X(26)}, {@code FILLER X(20)}.
     */
    private static String tranRecordImage(String procTs) {
        String image = picX(FIXTURE_TRAN_ID, 16)
                + picX(FIXTURE_TYPE_CD, 2)
                + pic9(FIXTURE_CAT_CD, 4)
                + picX(FIXTURE_SOURCE, 10)
                + picX(FIXTURE_DESC, 100)
                + FIXTURE_AMT_IMAGE
                + pic9(FIXTURE_MERCHANT_ID, 9)
                + picX(FIXTURE_MERCHANT_NAME, 50)
                + picX(FIXTURE_MERCHANT_CITY, 50)
                + picX(FIXTURE_MERCHANT_ZIP, 10)
                + picX(FIXTURE_CARD_NUM, 16)
                + picX(FIXTURE_ORIG_TS, 26)
                + picX(procTs, 26)
                + " ".repeat(20);
        assertThat(image).as("CVTRA05Y TRAN-RECORD is documented as RECLN = 350").hasSize(350);
        return image;
    }

    /**
     * Reproduces {@code app/jcl/CREASTMT.JCL:54} exactly:
     * {@code OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)}. DFSORT positions are 1-based and the
     * output is blank-padded to {@code LRECL=350}.
     *
     * <p>The third clause is the defect: {@code TRAN-ORIG-TS} occupies 1-based bytes 279-304 and
     * {@code TRAN-PROC-TS} 305-330, so copying 50 bytes from position 279 carries the whole original
     * timestamp but only the first 24 characters of the process timestamp, and leaves output bytes
     * 329-350 - which include the entire {@code FILLER X(20)} - blank.
     */
    private static String applyCreastmtOutrec(String tranRecord) {
        char[] out = new char[TrnxRecord.RECORD_LENGTH];
        Arrays.fill(out, ' ');
        tranRecord.getChars(262, 278, out, 0);      // 1:263,16
        tranRecord.getChars(0, 262, out, 16);       // 17:1,262
        tranRecord.getChars(278, 328, out, 278);    // 279:279,50 - 50 of 52 bytes
        return new String(out);
    }

    /** A record image of the declared width, filled with a repeated character. */
    private static byte[] filled(char character, int length, Charset charset) {
        return String.valueOf(character).repeat(length).getBytes(charset);
    }

    /** A populated record, every field written through the typed accessors. */
    private static TrnxRecord populated(Charset charset) {
        TrnxRecord record = TrnxRecord.newRecord(charset);
        record.writeTrnxCardNum(FIXTURE_CARD_NUM);
        record.writeTrnxId(FIXTURE_TRAN_ID);
        record.writeTrnxTypeCd(FIXTURE_TYPE_CD);
        record.writeTrnxCatCd(1);
        record.writeTrnxSource(FIXTURE_SOURCE);
        record.writeTrnxDesc(FIXTURE_DESC);
        record.writeTrnxAmt(new BigDecimal("504.77"));
        record.writeTrnxMerchantId(800000000);
        record.writeTrnxMerchantName(FIXTURE_MERCHANT_NAME);
        record.writeTrnxMerchantCity(FIXTURE_MERCHANT_CITY);
        record.writeTrnxMerchantZip(FIXTURE_MERCHANT_ZIP);
        record.writeTrnxOrigTs(FIXTURE_ORIG_TS);
        record.writeTrnxProcTs(FULL_PROC_TS);
        return record;
    }

    @Nested
    @DisplayName("Geometry - the copybook's own arithmetic (gate G19)")
    class Geometry {

        @Test
        @DisplayName("the declared record length is 350, as RECORDSIZE(350 350) and the 16+16+318 FD split both confirm")
        void recordLengthIs350() {
            assertThat(TrnxRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TrnxRecord.layout().recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("the fourteen elementary spans sum to exactly 350, FILLER included")
        void storageSpansSumToRecordLength() {
            List<FieldSpan> storage = TrnxRecord.layout().storageSpans();

            assertThat(storage).hasSize(14);
            assertThat(storage.stream().mapToInt(FieldSpan::length).sum())
                    .as("dropping FILLER X(20) would give 330; a sign byte on TRNX-AMT would give 351")
                    .isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the spans are contiguous from offset 0 with no gap and no overlap")
        void storageSpansAreContiguousFromZero() {
            int cursor = 0;
            for (FieldSpan span : TrnxRecord.layout().storageSpans()) {
                assertThat(span.offset()).as("offset of %s", span.name()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("TRNX-KEY is 32 bytes at offset 0, matching KEYS(32 0) in the IDCAMS define")
        void keyGroupIs32BytesAtOffsetZero() {
            assertThat(TrnxRecord.TRNX_KEY_OFFSET).isZero();
            assertThat(TrnxRecord.TRNX_KEY_LENGTH).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_KEY.offset()).isZero();
            assertThat(TrnxRecord.TRNX_KEY.length()).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_KEY.name()).isEqualTo("TRNX-KEY");

            assertThat(TrnxRecord.TRNX_CARD_NUM_LENGTH + TrnxRecord.TRNX_ID_LENGTH)
                    .as("the key is TRNX-CARD-NUM X(16) followed by TRNX-ID X(16)")
                    .isEqualTo(TrnxRecord.TRNX_KEY_LENGTH);
        }

        @Test
        @DisplayName("TRNX-REST is 318 bytes at offset 32, matching FD-ACCT-DATA X(318) and WS-TRAN-REST X(318)")
        void restGroupIs318BytesAtOffset32() {
            assertThat(TrnxRecord.TRNX_REST_OFFSET).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_REST_LENGTH).isEqualTo(318);
            assertThat(TrnxRecord.TRNX_REST.offset()).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_REST.length()).isEqualTo(318);
            assertThat(TrnxRecord.TRNX_REST.name()).isEqualTo("TRNX-REST");

            assertThat(TrnxRecord.TRNX_KEY_LENGTH + TrnxRecord.TRNX_REST_LENGTH)
                    .isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the two group items are REDEFINES overlays and contribute nothing to the total")
        void groupItemsAreOverlays() {
            assertThat(TrnxRecord.layout().redefinitions())
                    .containsExactly(TrnxRecord.TRNX_KEY, TrnxRecord.TRNX_REST);
            assertThat(TrnxRecord.TRNX_KEY.redefinition()).isTrue();
            assertThat(TrnxRecord.TRNX_REST.redefinition()).isTrue();
            assertThat(TrnxRecord.layout().storageSpans())
                    .doesNotContain(TrnxRecord.TRNX_KEY, TrnxRecord.TRNX_REST);
        }

        @ParameterizedTest(name = "{0} at offset {1}, length {2}")
        @CsvSource({
                "TRNX-CARD-NUM,        0,  16",
                "TRNX-ID,             16,  16",
                "TRNX-TYPE-CD,        32,   2",
                "TRNX-CAT-CD,         34,   4",
                "TRNX-SOURCE,         38,  10",
                "TRNX-DESC,           48, 100",
                "TRNX-AMT,           148,  11",
                "TRNX-MERCHANT-ID,   159,   9",
                "TRNX-MERCHANT-NAME, 168,  50",
                "TRNX-MERCHANT-CITY, 218,  50",
                "TRNX-MERCHANT-ZIP,  268,  10",
                "TRNX-ORIG-TS,       278,  26",
                "TRNX-PROC-TS,       304,  26",
                "FILLER,             330,  20"
        })
        @DisplayName("every field sits at its copybook offset and width")
        void everyFieldSitsAtItsCopybookOffset(String name, int offset, int length) {
            List<FieldSpan> matching = TrnxRecord.layout().storageSpans().stream()
                    .filter(span -> span.name().equals(name) && span.offset() == offset)
                    .toList();

            assertThat(matching).as("span named %s at offset %d", name, offset).hasSize(1);
            assertThat(matching.get(0).length()).isEqualTo(length);
        }

        @Test
        @DisplayName("TRNX-AMT is 11 bytes because S9(09)V99 reserves no sign byte")
        void amountOccupiesNineIntegerPlusTwoFractionBytes() {
            assertThat(TrnxRecord.TRNX_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TrnxRecord.TRNX_AMT_SCALE).isEqualTo(2).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(TrnxRecord.TRNX_AMT_LENGTH).isEqualTo(11);
            assertThat(TrnxRecord.TRNX_AMT.length())
                    .as("reserving a sign byte would make the record 351 and fail the layout check")
                    .isEqualTo(11);
            assertThat(TrnxRecord.TRNX_AMT.offset()).isEqualTo(148);
        }

        @Test
        @DisplayName("FILLER X(20) is the last declared span and reaches exactly byte 350")
        void fillerIsTheLastSpanAndClosesTheRecord() {
            List<FieldSpan> storage = TrnxRecord.layout().storageSpans();
            FieldSpan last = storage.get(storage.size() - 1);

            assertThat(last.name()).isEqualTo("FILLER");
            assertThat(last.offset()).isEqualTo(TrnxRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(last.length()).isEqualTo(TrnxRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(last.endOffsetExclusive()).isEqualTo(TrnxRecord.RECORD_LENGTH);
            assertThat(last.kind().filler())
                    .as("FILLER is declared with the FILLER kind, so it space-fills and stays non-referable")
                    .isTrue();
            assertThat(last.hasInitialValue())
                    .as("this FILLER declares no VALUE, unlike the '/' separators of CSDAT01Y")
                    .isFalse();
        }

        @Test
        @DisplayName("a layout that drops the trailing FILLER is rejected, not silently 330 bytes (gate G21)")
        void aLayoutMissingTheTrailingFillerIsRejected() {
            List<FieldSpan> withoutFiller = new ArrayList<>(TrnxRecord.layout().storageSpans());
            withoutFiller.removeIf(span -> span.name().equals("FILLER"));

            assertThat(withoutFiller.stream().mapToInt(FieldSpan::length).sum())
                    .as("this is the 330 a dropped FILLER would silently produce")
                    .isEqualTo(330);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(TrnxRecord.RECORD_LENGTH, withoutFiller))
                    .withMessageContaining("330")
                    .withMessageContaining("FILLER");
        }

        @Test
        @DisplayName("the descriptor table is immutable and cannot be written through (gate G53)")
        void descriptorTableIsImmutable() {
            List<FieldSpan> spans = TrnxRecord.fieldSpans();

            assertThat(spans).hasSize(16).isSameAs(TrnxRecord.fieldSpans());
            assertThat(spans.subList(0, 14)).isEqualTo(TrnxRecord.layout().storageSpans());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> spans.add(TrnxRecord.TRNX_AMT));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TrnxRecord.layout().spans().clear());
        }
    }

    @Nested
    @DisplayName("Allocation and initialisation")
    class Allocation {

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("a new record is exactly 350 bytes with the declared charset")
        void newRecordIsDeclaredWidth(String charsetName) {
            Charset charset = Charset.forName(charsetName);

            TrnxRecord record = TrnxRecord.newRecord(charset);

            assertThat(record.recordLength()).isEqualTo(350);
            assertThat(record.charset()).isEqualTo(charset);
            assertThat(record.encode()).hasSize(350);
            assertThat(record.recordImage()).hasSize(350);
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("unsigned spans initialise to zeros, a signed span to a signed zero, text to spaces")
        void initialisationFollowsCobolConvention(String charsetName) {
            TrnxRecord record = TrnxRecord.newRecord(Charset.forName(charsetName));

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD)).isEqualTo("0000");
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("000000000");
            assertThat(record.readTrnxAmtImage())
                    .as("TRNX-AMT is S9(09)V99, so its zero carries a positive-zero overpunch in the "
                            + "trailing byte - the form every signed field in app/data/ASCII is "
                            + "stored in - and the image is still exactly 11 characters")
                    .isEqualTo("0000000000{");
            assertThat(record.readTrnxCardNum()).isEqualTo(" ".repeat(16));
            assertThat(record.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("an initialised record's numeric fields are readable without failing on blank digits")
        void initialisedNumericFieldsAreReadable() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThat(record.readTrnxCatCd()).isZero();
            assertThat(record.readTrnxMerchantId()).isZero();
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("0.00");
            assertThat(record.readTrnxAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a null charset is rejected rather than defaulted to the platform")
        void nullCharsetIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.newRecord(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.decode(new byte[350], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.wrap(new byte[350], null));
        }
    }

    @Nested
    @DisplayName("decode - the COBOL alphanumeric group MOVE into a 350-byte receiver")
    class Decoding {

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("an oversized 1000-byte span keeps only its leading 350 bytes, per MOVE WS-M03B-FLDT TO TRNX-RECORD")
        void oversizedSpanIsTruncatedOnTheRight(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] expected = populated(charset).encode();

            // CBSTM03B hands back a PIC X(1000) span, space padded, and never decodes.
            byte[] fldt = filled(' ', 1000, charset);
            System.arraycopy(expected, 0, fldt, 0, TrnxRecord.RECORD_LENGTH);

            TrnxRecord decoded = TrnxRecord.decode(fldt, charset);

            assertThat(decoded.encode()).hasSize(350).isEqualTo(expected);
            assertThat(decoded.readTrnxCardNum()).isEqualTo(picX(FIXTURE_CARD_NUM, 16));
            assertThat(decoded.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
            assertThat(decoded.readTrnxAmt()).isEqualByComparingTo("504.77");
        }

        @Test
        @DisplayName("bytes beyond 350 are discarded, not shifted in from the right")
        void bytesBeyondTheReceiverAreDiscarded() {
            byte[] source = filled('X', 400, ASCII);
            for (int i = 350; i < 400; i++) {
                source[i] = (byte) 'Z';
            }

            TrnxRecord decoded = TrnxRecord.decode(source, ASCII);

            assertThat(decoded.recordImage())
                    .as("a PIC X receiver fills from the left and drops the overflow")
                    .isEqualTo("X".repeat(350))
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("a span of exactly 350 bytes is taken as it stands")
        void exactWidthSpanIsTakenAsIs() {
            byte[] source = populated(ASCII).encode();

            assertThat(TrnxRecord.decode(source, ASCII).encode()).isEqualTo(source);
        }

        @Test
        @DisplayName("a short span lands at the left and is padded on the right with the charset's space byte")
        void shortSpanIsPaddedOnTheRight() {
            TrnxRecord decoded = TrnxRecord.decode("ABC".getBytes(ASCII), ASCII);

            assertThat(decoded.encode()).hasSize(350);
            assertThat(decoded.readTrnxCardNum()).isEqualTo("ABC" + " ".repeat(13));
            assertThat(decoded.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a short span under IBM037 is padded with 0x40, not a hard-coded ASCII 0x20")
        void shortSpanUsesTheCharsetsOwnPadByte() {
            TrnxRecord decoded = TrnxRecord.decode("A".getBytes(EBCDIC), EBCDIC);

            byte[] image = decoded.encode();

            assertThat(image[0]).isEqualTo("A".getBytes(EBCDIC)[0]);
            assertThat(image[349]).isEqualTo((byte) 0x40);
            assertThat(decoded.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("an empty span is rejected, since a sending field has at least one byte")
        void emptySpanIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TrnxRecord.decode(new byte[0], ASCII))
                    .withMessageContaining("empty")
                    .withMessageContaining("newRecord");
        }

        @Test
        @DisplayName("a null span is rejected")
        void nullSpanIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.decode(null, ASCII));
        }

        @Test
        @DisplayName("the source array is never modified")
        void sourceArrayIsNotModified() {
            byte[] source = filled('Q', 1000, ASCII);
            byte[] before = source.clone();

            TrnxRecord decoded = TrnxRecord.decode(source, ASCII);
            decoded.writeTrnxCardNum("MUTATED");

            assertThat(source).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("wrap - the strict, exact-width entry point")
    class Wrapping {

        @Test
        @DisplayName("exactly 350 bytes are accepted and copied defensively")
        void exactWidthIsAccepted() {
            byte[] image = populated(ASCII).encode();

            TrnxRecord wrapped = TrnxRecord.wrap(image, ASCII);
            image[0] = (byte) 'Z';

            assertThat(wrapped.readTrnxCardNum())
                    .as("the record holds a copy, so mutating the caller's array cannot reach it")
                    .isEqualTo(FIXTURE_CARD_NUM);
        }

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {1, 330, 349, 351, 1000})
        @DisplayName("any width other than 350 is rejected rather than silently adjusted")
        void anyOtherWidthIsRejected(int width) {
            byte[] wrongWidth = filled(' ', width, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TrnxRecord.wrap(wrongWidth, ASCII))
                    .withMessageContaining("350");
        }

        @Test
        @DisplayName("a null span is rejected")
        void nullSpanIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> TrnxRecord.wrap(null, ASCII));
        }
    }

    @Nested
    @DisplayName("Serialisation - round trip byte identity (gate G21)")
    class Serialisation {

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("decode then encode returns a byte-for-byte identical 350-byte image, FILLER included")
        void roundTripIsByteIdentical(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] original = populated(charset).encode();

            byte[] roundTripped = TrnxRecord.decode(original, charset).encode();

            assertThat(roundTripped)
                    .as("350 and not 330: the trailing FILLER X(20) is emitted")
                    .hasSize(TrnxRecord.RECORD_LENGTH)
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("encode returns a fresh array, so mutating it cannot reach the record")
        void encodeReturnsADefensiveCopy() {
            TrnxRecord record = populated(ASCII);

            byte[] first = record.encode();
            Arrays.fill(first, (byte) 'Z');

            assertThat(record.encode()).isNotEqualTo(first);
            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
        }

        @Test
        @DisplayName("encode(Charset) returns the same bytes when the code page matches")
        void encodeWithMatchingCharsetReturnsTheSameBytes() {
            TrnxRecord record = populated(EBCDIC);

            assertThat(record.encode(EBCDIC)).isEqualTo(record.encode());
        }

        @Test
        @DisplayName("encode(Charset) refuses a different code page instead of silently transcoding")
        void encodeWithMismatchedCharsetIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.encode(EBCDIC))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("IBM037");
        }

        @Test
        @DisplayName("encode(Charset) rejects a null code page")
        void encodeWithNullCharsetIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.encode(null));
        }

        @Test
        @DisplayName("recordImage is the untrimmed 350-character view of the same bytes")
        void recordImageMatchesTheEncodedBytes() {
            TrnxRecord record = populated(ASCII);

            assertThat(record.recordImage())
                    .hasSize(350)
                    .isEqualTo(new String(record.encode(), ASCII));
        }
    }

    @Nested
    @DisplayName("TRNX-AMT - zoned sign overpunch and truncation (gates G23, G24)")
    class Amount {

        // The trailing character carries BOTH the low-order digit and the sign:
        //   '{ABCDEFGHI' for digits 0-9 positive, '}JKLMNOPQR' for digits 0-9 negative.
        // So a positive amount ending in 0 shows '{' and one ending in 9 shows 'I' - the field never
        // grows a twelfth byte to hold a sign. Every image below is 11 characters.
        @ParameterizedTest(name = "{0} stores as the 11-byte image {1}")
        @CsvSource({
                "504.77,        0000005047G",
                "-919.00,       0000009190}",
                "0.00,          0000000000{",
                "-0.01,         0000000000J",
                "0.01,          0000000000A",
                "1.00,          0000000010{",
                "-1.00,         0000000010}",
                "999999999.99,  9999999999I",
                "-999999999.99, 9999999999R"
        })
        @DisplayName("the sign is overpunched into the trailing byte and reserves no byte of its own")
        void signIsOverpunchedIntoTheTrailingByte(String amount, String expectedImage) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(amount));

            assertThat(record.readTrnxAmtImage())
                    .as("%s must occupy exactly 11 bytes whatever its sign", amount)
                    .hasSize(TrnxRecord.TRNX_AMT_LENGTH)
                    .isEqualTo(expectedImage);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo(amount);
            assertThat(record.encode()).hasSize(350);
        }

        @ParameterizedTest(name = "{0} truncates to {1}")
        @CsvSource({
                "1.239,    1.23",
                "-1.239,   -1.23",
                "1.999,    1.99",
                "-1.999,   -1.99",
                "0.005,    0.00",
                "-0.005,   0.00",
                "504.7777, 504.77"
        })
        @DisplayName("excess fractional digits truncate toward zero and never round, because ROUNDED appears nowhere")
        void excessFractionDigitsTruncateTowardZero(String given, String expected) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(given));

            assertThat(record.readTrnxAmt())
                    .as("RoundingMode.DOWN, never HALF_UP or HALF_EVEN")
                    .isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("truncation is genuinely DOWN, differing from HALF_UP and HALF_EVEN on the same input")
        void truncationDiffersFromRounding() {
            BigDecimal given = new BigDecimal("1.239");
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(given);

            assertThat(record.readTrnxAmt())
                    .isEqualByComparingTo(given.setScale(2, RoundingMode.DOWN))
                    .isNotEqualByComparingTo(given.setScale(2, RoundingMode.HALF_UP))
                    .isNotEqualByComparingTo(given.setScale(2, RoundingMode.HALF_EVEN));
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"504.77", "-919.00", "0.00", "0", "1", "-1", "504.7", "1.239"})
        @DisplayName("every decoded amount carries scale exactly 2, so zero is 0.00 and not BigDecimal.ZERO")
        void everyDecodedAmountCarriesScaleTwo(String amount) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(amount));
            BigDecimal read = record.readTrnxAmt();

            assertThat(read.scale()).isEqualTo(TrnxRecord.TRNX_AMT_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("zero renders as the scale-2 zero, unequal to BigDecimal.ZERO under equals but equal under compareTo")
        void zeroIsScaleTwoZero() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(BigDecimal.ZERO);
            BigDecimal read = record.readTrnxAmt();

            assertThat(read).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(read).isEqualTo(new BigDecimal("0.00"));
            assertThat(read.equals(BigDecimal.ZERO))
                    .as("equals is scale sensitive, which is exactly why compareTo is mandated")
                    .isFalse();
            assertThat(read).isEqualByComparingTo(CobolDecimal.monetaryZero());
        }

        @Test
        @DisplayName("an oversized value keeps its low-order digits and its sign, since ON SIZE ERROR appears nowhere")
        void oversizedValueKeepsLowOrderDigitsAndSign() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal("1234567890.99"));

            assertThat(record.readTrnxAmtImage()).hasSize(11);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("234567890.99");
        }

        @Test
        @DisplayName("a null amount is rejected")
        void nullAmountIsRejected() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxAmt(null));
        }

        @Test
        @DisplayName("the amount round trips through the raw span under both code pages")
        void amountRoundTripsUnderBothCodePages() {
            for (Charset charset : List.of(ASCII, EBCDIC)) {
                TrnxRecord record = TrnxRecord.newRecord(charset);
                record.writeTrnxAmt(new BigDecimal("-919.00"));

                TrnxRecord reread = TrnxRecord.wrap(record.encode(), charset);

                assertThat(reread.readTrnxAmtImage()).isEqualTo("0000009190}");
                assertThat(reread.readTrnxAmt()).isEqualByComparingTo("-919.00");
            }
        }
    }

    /**
     * The highest-value tests in this class. They encode the single design fact that a conventional
     * value object would violate silently: after a wholesale group write, every typed accessor over the
     * overwritten bytes must observe the <em>new</em> value, because
     * {@code app/cbl/CBSTM03A.CBL:426-429} reads three fields straight after moving 318 bytes into
     * {@code TRNX-REST}.
     */
    @Nested
    @DisplayName("Read-through - typed accessors decode the live bytes, never a cached copy")
    class ReadThrough {

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("TRNX-AMT read straight after a wholesale TRNX-REST write reflects the new amount")
        void amountReflectsAWholesaleRestWrite(String charsetName) {
            Charset charset = Charset.forName(charsetName);

            TrnxRecord source = TrnxRecord.newRecord(charset);
            source.writeTrnxAmt(new BigDecimal("42.42"));
            String restImage = source.readTrnxRest();

            TrnxRecord target = TrnxRecord.newRecord(charset);
            target.writeTrnxAmt(new BigDecimal("999.99"));
            assertThat(target.readTrnxAmt()).isEqualByComparingTo("999.99");

            // MOVE WS-TRAN-REST (CR-JMP, TR-JMP) TO TRNX-REST   -- CBSTM03A.CBL:426-427
            target.writeTrnxRest(restImage);

            // ADD TRNX-AMT TO WS-TOTAL-AMT                      -- CBSTM03A.CBL:429
            assertThat(target.readTrnxAmt())
                    .as("a cached decode would still report 999.99 and corrupt the statement total")
                    .isEqualByComparingTo("42.42");
            assertThat(target.readTrnxAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("TRNX-DESC read straight after a wholesale TRNX-REST write reflects the new description")
        void descriptionReflectsAWholesaleRestWrite() {
            TrnxRecord source = TrnxRecord.newRecord(ASCII);
            source.writeTrnxDesc("GROCERIES");
            String restImage = source.readTrnxRest();

            TrnxRecord target = TrnxRecord.newRecord(ASCII);
            target.writeTrnxDesc("SUPERSEDED");

            target.writeTrnxRest(restImage);

            // MOVE TRNX-DESC TO ST-TRANDT                       -- CBSTM03A.CBL:677
            assertThat(target.readTrnxDesc()).isEqualTo(picX("GROCERIES", 100));
        }

        @Test
        @DisplayName("every field of TRNX-REST is refreshed by one group write, not just the ones read back")
        void everyRestFieldIsRefreshedByOneGroupWrite() {
            TrnxRecord source = populated(ASCII);
            TrnxRecord target = TrnxRecord.newRecord(ASCII);

            target.writeTrnxRest(source.readTrnxRest());

            assertThat(target.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(target.readTrnxCatCd()).isEqualTo(1);
            assertThat(target.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
            assertThat(target.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
            assertThat(target.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(target.readTrnxMerchantId()).isEqualTo(800000000);
            assertThat(target.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
            assertThat(target.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
            assertThat(target.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
            assertThat(target.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
            assertThat(target.readTrnxProcTs()).isEqualTo(FULL_PROC_TS);
        }

        @Test
        @DisplayName("a wholesale TRNX-KEY write is visible through both key components, and the reverse")
        void keyComponentsAndGroupShareStorage() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxKey(FIXTURE_CARD_NUM + FIXTURE_TRAN_ID);

            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
            assertThat(record.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);

            // MOVE ... TO TRNX-CARD-NUM / TRNX-ID   -- CBSTM03A.CBL:421 and :424-425
            record.writeTrnxCardNum("0000000000000009");
            record.writeTrnxId("0000000000000007");

            assertThat(record.readTrnxKey()).isEqualTo("00000000000000090000000000000007");
        }

        @Test
        @DisplayName("a group write does not disturb storage outside the group")
        void groupWriteDoesNotDisturbNeighbouringStorage() {
            TrnxRecord record = populated(ASCII);
            String keyBefore = record.readTrnxKey();

            record.writeTrnxRest(" ".repeat(TrnxRecord.TRNX_REST_LENGTH));

            assertThat(record.readTrnxKey()).isEqualTo(keyBefore);

            String restBefore = record.readTrnxRest();
            record.writeTrnxKey(" ".repeat(TrnxRecord.TRNX_KEY_LENGTH));
            assertThat(record.readTrnxRest()).isEqualTo(restBefore);
        }
    }

    @Nested
    @DisplayName("Wholesale span symmetry - CBSTM03A.CBL:829 followed by :426-427")
    class WholesaleSpans {

        @Test
        @DisplayName("reading TRNX-REST out and writing it back into a fresh record reproduces bytes 32 to 349")
        void restSpanRoundTripsThroughTheTable() {
            TrnxRecord source = populated(ASCII);

            // MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT)   -- CBSTM03A.CBL:829
            String tableSlot = source.readTrnxRest();
            assertThat(tableSlot)
                    .as("WS-TRAN-REST is PIC X(318)")
                    .hasSize(TrnxRecord.TRNX_REST_LENGTH);

            // MOVE WS-TRAN-REST (CR-JMP, TR-JMP) TO TRNX-REST   -- CBSTM03A.CBL:426-427
            TrnxRecord target = TrnxRecord.newRecord(ASCII);
            target.writeTrnxRest(tableSlot);

            byte[] sourceImage = source.encode();
            byte[] targetImage = target.encode();

            assertThat(Arrays.copyOfRange(targetImage, 32, 350))
                    .as("bytes 32 to 349 must agree exactly")
                    .isEqualTo(Arrays.copyOfRange(sourceImage, 32, 350));
        }

        @Test
        @DisplayName("the byte paths for both groups require the exact span width")
        void byteLevelGroupWritesRequireExactWidth() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxKeyBytes(new byte[31]));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxRestBytes(new byte[319]));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxKeyBytes(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxRestBytes(null));
        }

        @Test
        @DisplayName("the byte paths round trip both groups exactly")
        void byteLevelGroupPathsRoundTrip() {
            TrnxRecord source = populated(ASCII);
            TrnxRecord target = TrnxRecord.newRecord(ASCII);

            byte[] keyBytes = source.readTrnxKeyBytes();
            byte[] restBytes = source.readTrnxRestBytes();
            assertThat(keyBytes).hasSize(32);
            assertThat(restBytes).hasSize(318);

            target.writeTrnxKeyBytes(keyBytes);
            target.writeTrnxRestBytes(restBytes);

            assertThat(target.encode()).isEqualTo(source.encode());
        }

        @Test
        @DisplayName("the group byte accessors return defensive copies")
        void groupByteAccessorsReturnCopies() {
            TrnxRecord record = populated(ASCII);

            byte[] rest = record.readTrnxRestBytes();
            Arrays.fill(rest, (byte) 'Z');

            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
        }

        @Test
        @DisplayName("a short group value is padded on the right and an over-long one truncated on the right")
        void groupStringWritesFollowThePicXRule() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxKey("ABC");
            assertThat(record.readTrnxKey()).isEqualTo("ABC" + " ".repeat(29));

            record.writeTrnxKey("A".repeat(40));
            assertThat(record.readTrnxKey())
                    .as("a PIC X receiver keeps the leading characters")
                    .isEqualTo("A".repeat(32));

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxKey(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxRest(null));
        }
    }

    /**
     * The provenance tests. These prove the class accepts, unaltered, exactly what
     * {@code app/jcl/CREASTMT.JCL} actually produces - including a process timestamp two characters
     * short, which is a real defect in the legacy job and is reproduced rather than repaired.
     */
    @Nested
    @DisplayName("SORT-derived input - CREASTMT.JCL:54 reproduced, not corrected (practice B4)")
    class SortDerivedInput {

        @Test
        @DisplayName("a record reshaped by the legacy OUTREC decodes field for field")
        void sortDerivedRecordDecodesFieldForField() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);

            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
            assertThat(record.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
            assertThat(record.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(record.readTrnxCatCd()).isEqualTo(1);
            assertThat(record.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
            assertThat(record.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
            assertThat(record.readTrnxAmtImage()).isEqualTo(FIXTURE_AMT_IMAGE);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(record.readTrnxMerchantId()).isEqualTo(800000000);
            assertThat(record.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
            assertThat(record.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
            assertThat(record.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
            assertThat(record.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
        }

        @Test
        @DisplayName("TRNX-PROC-TS legitimately carries 24 characters and two trailing spaces, and is left alone")
        void processTimestampLosesItsLastTwoCharacters() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);
            String procTs = record.readTrnxProcTs();

            assertThat(procTs)
                    .as("the field is still X(26); it is the sort that under-fills it")
                    .hasSize(TrnxRecord.TRNX_PROC_TS_LENGTH);
            assertThat(procTs)
                    .isEqualTo(FULL_PROC_TS.substring(0, TrnxRecord.TRNX_PROC_TS_SORT_DERIVED_LENGTH)
                            + "  ");
            assertThat(procTs.substring(24))
                    .as("OUTREC 279:279,50 copies 50 of the 52 trailing bytes")
                    .isEqualTo("  ");
            assertThat(TrnxRecord.TRNX_PROC_TS_SORT_DERIVED_LENGTH).isEqualTo(24);
            assertThat(TrnxRecord.TRNX_PROC_TS_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("FILLER X(20) arrives entirely blank because output bytes 329 to 350 receive nothing")
        void fillerArrivesBlank() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);

            assertThat(record.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a SORT-derived record re-encodes byte-identically and is neither normalised nor rejected")
        void sortDerivedRecordReEncodesByteIdentically() {
            byte[] derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS)).getBytes(ASCII);

            assertThat(TrnxRecord.wrap(derived, ASCII).encode()).isEqualTo(derived);
            assertThat(TrnxRecord.decode(derived, ASCII).encode()).isEqualTo(derived);
        }

        @Test
        @DisplayName("the fixture's own blank process timestamp is equally acceptable")
        void blankProcessTimestampIsAccepted() {
            byte[] derived = applyCreastmtOutrec(tranRecordImage("")).getBytes(ASCII);

            TrnxRecord record = TrnxRecord.wrap(derived, ASCII);

            assertThat(record.readTrnxProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(record.encode()).isEqualTo(derived);
        }
    }

    @Nested
    @DisplayName("Field semantics - padding direction, truncation direction and untrimmed reads")
    class FieldSemantics {

        @Test
        @DisplayName("a PIC X read is never trimmed, because the padding is part of the field's value")
        void picXReadsAreNotTrimmed() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxDesc("SHORT");
            record.writeTrnxMerchantName("ACME");

            assertThat(record.readTrnxDesc())
                    .hasSize(100)
                    .startsWith("SHORT")
                    .endsWith(" ")
                    .isEqualTo("SHORT" + " ".repeat(95));
            assertThat(record.readTrnxMerchantName()).hasSize(50).isEqualTo("ACME" + " ".repeat(46));
        }

        @ParameterizedTest(name = "{0} is padded on the right to {1} characters")
        @CsvSource({"16, TRNX-CARD-NUM", "16, TRNX-ID", "2, TRNX-TYPE-CD", "10, TRNX-SOURCE"})
        @DisplayName("PIC X fields pad on the right, not the left")
        void picXFieldsPadOnTheRight(int width, String fieldName) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);
            FieldSpan span = TrnxRecord.layout().span(fieldName);

            record.writeTrnxCardNum("A");
            record.writeTrnxId("A");
            record.writeTrnxTypeCd("A");
            record.writeTrnxSource("A");

            assertThat(record.readRawImage(span))
                    .hasSize(width)
                    .startsWith("A")
                    .isEqualTo("A" + " ".repeat(width - 1));
        }

        @Test
        @DisplayName("an over-long PIC X value loses its trailing characters, keeping the leading ones")
        void picXTruncatesOnTheRight() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxTypeCd("ABCD");
            record.writeTrnxMerchantZip("0123456789XYZ");

            assertThat(record.readTrnxTypeCd()).isEqualTo("AB");
            assertThat(record.readTrnxMerchantZip()).isEqualTo("0123456789");
        }

        @ParameterizedTest(name = "TRNX-CAT-CD {0} is stored as {1}")
        @CsvSource({"7, 0007", "0, 0000", "1, 0001", "42, 0042", "9999, 9999"})
        @DisplayName("PIC 9 fields zero-fill on the LEFT, the mirror image of the PIC X rule")
        void pic9FieldsZeroFillOnTheLeft(int value, String expectedImage) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCatCd(value);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD)).isEqualTo(expectedImage);
            assertThat(record.readTrnxCatCd()).isEqualTo(value);
        }

        @Test
        @DisplayName("an over-long PIC 9 value keeps its LOW-order digits, since a numeric receiver aligns on the decimal point")
        void pic9TruncatesOnTheLeft() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCatCd(123456);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD))
                    .as("keeping the leading digits instead is the classic defect")
                    .isEqualTo("3456");
            assertThat(record.readTrnxCatCd()).isEqualTo(3456);
        }

        @Test
        @DisplayName("TRNX-MERCHANT-ID uses the full nine-digit range as a long")
        void merchantIdUsesTheFullNineDigitRange() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxMerchantId(999999999);
            assertThat(record.readTrnxMerchantId()).isEqualTo(999999999);
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("999999999");

            record.writeTrnxMerchantId(7);
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("000000007");
        }

        @Test
        @DisplayName("a negative value is refused by an unsigned PIC 9 field rather than stored as its magnitude")
        void unsignedNumericFieldsRefuseNegativeValues() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxCatCd(-1))
                    .withMessageContaining("PIC 9");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxMerchantId(-1))
                    .withMessageContaining("PIC 9");
        }

        @Test
        @DisplayName("a blank numeric span is reported precisely rather than coerced to a plausible zero")
        void blankNumericSpanIsReportedNotCoerced() {
            TrnxRecord record = TrnxRecord.decode(" ".repeat(350).getBytes(ASCII), ASCII);

            assertThatIllegalArgumentException().isThrownBy(record::readTrnxCatCd);
            assertThatIllegalArgumentException().isThrownBy(record::readTrnxMerchantId);
            assertThatIllegalArgumentException().isThrownBy(record::readTrnxAmt);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD))
                    .as("the raw span stays inspectable even when it will not decode")
                    .isEqualTo("    ");
        }

        @ParameterizedTest(name = "card number {0} survives as a String")
        @ValueSource(strings = {
                "0000000000000001",
                "0859452612877065",
                "0000000000000000",
                "4859452612877065"
        })
        @DisplayName("a leading zero in TRNX-CARD-NUM survives decode and encode, because PIC X is never parsed as a number")
        void leadingZerosArePreserved(String cardNumber) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCardNum(cardNumber);
            TrnxRecord reread = TrnxRecord.wrap(record.encode(), ASCII);

            assertThat(reread.readTrnxCardNum()).isEqualTo(cardNumber);
            assertThat(reread.readTrnxKey()).startsWith(cardNumber);
        }

        @Test
        @DisplayName("a leading zero in TRNX-ID and TRNX-MERCHANT-ZIP survives too")
        void leadingZerosSurviveInOtherCharacterFields() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxId("0000000000683580");
            record.writeTrnxMerchantZip("07112");

            assertThat(record.readTrnxId()).isEqualTo("0000000000683580");
            assertThat(record.readTrnxMerchantZip()).isEqualTo("07112     ");
        }

        @Test
        @DisplayName("every character field rejects a null value rather than blanking the span")
        void characterFieldsRejectNull() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxCardNum(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxId(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxTypeCd(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxSource(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxDesc(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantName(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantCity(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantZip(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxOrigTs(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxProcTs(null));
        }

        @Test
        @DisplayName("all thirteen writable fields round trip together under both code pages")
        void everyWritableFieldRoundTrips() {
            for (Charset charset : List.of(ASCII, EBCDIC)) {
                TrnxRecord written = populated(charset);

                TrnxRecord reread = TrnxRecord.wrap(written.encode(), charset);

                assertThat(reread.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
                assertThat(reread.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
                assertThat(reread.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
                assertThat(reread.readTrnxCatCd()).isEqualTo(1);
                assertThat(reread.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
                assertThat(reread.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
                assertThat(reread.readTrnxAmt()).isEqualByComparingTo("504.77");
                assertThat(reread.readTrnxMerchantId()).isEqualTo(800000000);
                assertThat(reread.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
                assertThat(reread.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
                assertThat(reread.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
                assertThat(reread.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
                assertThat(reread.readTrnxProcTs()).isEqualTo(FULL_PROC_TS);
                assertThat(reread.readFiller()).isEqualTo(" ".repeat(20));
            }
        }
    }

    @Nested
    @DisplayName("Raw span access and diagnostics")
    class RawAccessAndDiagnostics {

        @Test
        @DisplayName("every declared span is readable as an untrimmed image and as bytes")
        void everyDeclaredSpanIsReadableRaw() {
            TrnxRecord record = populated(ASCII);

            for (FieldSpan span : TrnxRecord.fieldSpans()) {
                assertThat(record.readRawImage(span))
                        .as("raw image of %s", span.name())
                        .hasSize(span.length());
                assertThat(record.readRawBytes(span))
                        .as("raw bytes of %s", span.name())
                        .hasSize(span.length());
            }
        }

        @Test
        @DisplayName("the raw byte accessor returns a defensive copy")
        void rawByteAccessorReturnsACopy() {
            TrnxRecord record = populated(ASCII);

            byte[] amount = record.readRawBytes(TrnxRecord.TRNX_AMT);
            Arrays.fill(amount, (byte) '0');

            assertThat(record.readTrnxAmtImage()).isEqualTo(FIXTURE_AMT_IMAGE);
        }

        @Test
        @DisplayName("a descriptor from another copybook is rejected instead of reading the wrong offset")
        void foreignDescriptorIsRejected() {
            TrnxRecord record = populated(ASCII);
            FieldSpan foreign = FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 0, 10);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.readRawImage(foreign))
                    .withMessageContaining("COSTM01");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.readRawBytes(foreign))
                    .withMessageContaining("COSTM01");
        }

        @Test
        @DisplayName("a null descriptor is rejected")
        void nullDescriptorIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.readRawImage(null));
            assertThatNullPointerException().isThrownBy(() -> record.readRawBytes(null));
        }

        @Test
        @DisplayName("the raw amount image is the stored zoned form, with no decimal point and the sign still overpunched")
        void rawAmountImageIsTheStoredZonedForm() {
            TrnxRecord record = populated(ASCII);

            assertThat(record.readTrnxAmtImage())
                    .isEqualTo(FIXTURE_AMT_IMAGE)
                    .doesNotContain(".")
                    .isEqualTo(record.readRawImage(TrnxRecord.TRNX_AMT));
        }

        @Test
        @DisplayName("toString names the key, the raw amount and the code page")
        void toStringNamesTheKeyAmountAndCodePage() {
            TrnxRecord record = populated(ASCII);

            // The transaction identifier and the code page identify the record; the card number is
            // masked to its last four characters and the amount is withheld with its width.
            assertThat(record.toString())
                    .startsWith("TrnxRecord[")
                    .as("the statement files carry a PAN; it must not reach a log line (CWE-532)")
                    .doesNotContain(FIXTURE_CARD_NUM)
                    .contains("*".repeat(FIXTURE_CARD_NUM.length() - 4)
                            + FIXTURE_CARD_NUM.substring(FIXTURE_CARD_NUM.length() - 4))
                    .contains(FIXTURE_TRAN_ID)
                    .doesNotContain(FIXTURE_AMT_IMAGE)
                    .contains(DiagnosticText.OMITTED + ":" + FIXTURE_AMT_IMAGE.length())
                    .contains("US-ASCII")
                    .endsWith("]");
        }

        @Test
        @DisplayName("toString never throws, even on a record whose numeric spans will not decode")
        void toStringNeverThrowsOnUndecodableSpans() {
            TrnxRecord blank = TrnxRecord.decode(" ".repeat(350).getBytes(ASCII), ASCII);

            assertThat(blank.toString()).contains("TrnxRecord[").contains("US-ASCII");
        }

        @Test
        @DisplayName("instances are independent, so writing one cannot alter another")
        void instancesAreIndependent() {
            byte[] shared = populated(ASCII).encode();

            TrnxRecord first = TrnxRecord.wrap(shared, ASCII);
            TrnxRecord second = TrnxRecord.wrap(shared, ASCII);

            first.writeTrnxAmt(new BigDecimal("1.00"));

            assertThat(second.readTrnxAmt())
                    .as("no mutable static state and no shared array")
                    .isEqualByComparingTo("504.77");
            assertThat(first.readTrnxAmt()).isEqualByComparingTo("1.00");
        }
    }
}
