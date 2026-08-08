package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DatasetOutput;
import com.vsergeychik.carddemo.parity.FieldDiffer.Diff;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffKind;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.Fingerprint;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The contract test for {@link FieldDiffer} - the deterministic judge whose output <em>is</em> the
 * parity gate.
 *
 * <h2>Why every one of these tests is a false-pass test</h2>
 * <p>The gate is stated as "diff count = 0", so the only way this class can be wrong in a way that
 * matters is by returning zero when it should not. A differ that misses a difference does not merely
 * fail to report it: it converts a broken translation into a green build, and it does so silently and
 * repeatably. Each group below therefore drives a specific way that could happen and asserts it does
 * not:
 * <ul>
 *   <li><strong>Bytes, not values, for signed zoned fields.</strong> Two different images can denote
 *       the same number - an unsigned form and an overpunched form, or the two zeros - and COBOL
 *       writes exactly one of them. A comparison that short-circuited on numeric equality would
 *       accept a record the legacy program would never have produced.</li>
 *   <li><strong>Both directions of every set.</strong> Walking only the expectations cannot see an
 *       unexpected dataset, an unexpected row - including one <em>below</em> the highest expected
 *       index - or a unit that wrote a great deal while the expectations were empty.</li>
 *   <li><strong>No implicit padding.</strong> The two recorded fixture deviations are repaired once at
 *       seed time, so any width disagreement met here is a genuine difference and is reported as
 *       one.</li>
 *   <li><strong>The online response is compared at all.</strong> Several paths through these programs
 *       write no dataset, so for them the response is the entire observable behaviour.</li>
 *   <li><strong>Nothing is truncated and nothing leaks.</strong> Every difference is rendered, and no
 *       rendering carries the legacy credential span.</li>
 * </ul>
 *
 * <p>Every layout used here is declared in this file from explicit spans, apart from the deliberate
 * use of {@link SecUserRecord#LAYOUT} where the credential offset matters. Nothing reads the wall
 * clock, opens a network connection or writes a file, and there is no static mutable state: each test
 * builds its own differ, case and fingerprint.
 */
@DisplayName("FieldDiffer - the deterministic parity judge")
class FieldDifferTest {

    /** The code page of the nine ASCII fixtures, named explicitly and never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The dataset binding key used by the money-bearing scenarios. */
    private static final String ACCTDAT = "ACCTDAT";

    /** The dataset binding key whose rows carry the credential span. */
    private static final String USRSEC = "USRSEC";

    /**
     * A 24-byte layout carrying one alphanumeric field, one {@code PIC S9(10)V99} field and one
     * anonymous {@code FILLER} - the three categories a record comparison has to handle, in the
     * smallest record that can hold all three.
     */
    private static final RecordLayout MONEY_LAYOUT = RecordLayout.of(24,
        FieldSpan.alphanumeric("ACCT-ID", 0, 11),
        FieldSpan.signedScaled("ACCT-CURR-BAL", 11, 10, 2),
        FieldSpan.filler(23, 1));

    /** A layout with two anonymous {@code FILLER} spans, for the ordinal naming convention. */
    private static final RecordLayout TWO_FILLER_LAYOUT = RecordLayout.of(10,
        FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 4),
        FieldSpan.filler(4, 3),
        FieldSpan.filler(7, 3));

    /** A layout with a {@code REDEFINES} overlay, as {@code CVCRD01Y} declares one. */
    private static final RecordLayout REDEFINES_LAYOUT = RecordLayout.of(11,
        FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11),
        FieldSpan.redefining("CC-ACCT-ID-N", 0, 11, PictureKind.UNSIGNED_NUMERIC));

    /** A correct 24-byte row: account {@code 00000000011}, balance 194.00 positive-overpunched. */
    private static final String MONEY_ROW = "00000000011" + "00000001940{" + " ";

    /** An 80-byte USRSEC row carrying the legacy plaintext password at offset 48. */
    private static final String USRSEC_ROW =
        "ADMIN001John                Doe                 PASSWORDA" + " ".repeat(23);

    /** A differ over the fixtures' code page. */
    private static FieldDiffer differ() {
        return FieldDiffer.forCharset(ASCII);
    }

    /** Captures rows as a dataset output, encoding through the same code page the differ uses. */
    private static DatasetOutput output(String dataset, RecordLayout layout, String... images) {
        return DatasetOutput.ofImages(dataset, layout, List.of(images), ASCII);
    }

    /** A fingerprint carrying only final-state rows, which is the commonest shape. */
    private static Fingerprint finalState(DatasetOutput... outputs) {
        return Fingerprint.of(List.of(), List.of(outputs), null, 0, List.of());
    }

    /** A fingerprint carrying only write-channel rows. */
    private static Fingerprint writes(DatasetOutput... outputs) {
        return Fingerprint.of(List.of(outputs), List.of(), null, 0, List.of());
    }

    /** A batch case pinning the supplied final-state expectations. */
    private static ParityCase finalStateCase(ExpectedRecord... expectations) {
        return new ParityCase("CBACT04C", "case01", "a final-state expectation", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null, List.of(), List.of(expectations), 0, List.of(),
            List.of());
    }

    /** A batch case pinning the supplied write expectations. */
    private static ParityCase writeCase(ExpectedRecord... expectations) {
        return new ParityCase("CBACT04C", "case01", "a write expectation", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null, List.of(expectations), List.of(), 0, List.of(),
            List.of());
    }

    /** A record expectation pinning one field of one row. */
    private static ExpectedRecord pin(String dataset, int rowIndex, String field, String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, value);
        return new ExpectedRecord(dataset, rowIndex, fields, null);
    }

    /** Every kind the result carries, in traversal order. */
    private static List<DiffKind> kindsOf(DiffResult result) {
        List<DiffKind> kinds = new ArrayList<>();
        for (Diff diff : result.entries()) {
            kinds.add(diff.kind());
        }
        return kinds;
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Construction: the code page is a decision, never a default")
    class Construction {

        @Test
        @DisplayName("forCharset builds a differ whose codec carries that exact code page")
        void forCharsetCarriesTheCodePage() {
            Assertions.assertThat(FieldDiffer.forCharset(ASCII).codec().charset()).isEqualTo(ASCII);
            Assertions.assertThat(FieldDiffer.forCharset(Charset.forName("IBM037")).codec().charset())
                .isEqualTo(Charset.forName("IBM037"));
        }

        @Test
        @DisplayName("an injected codec is the one used, so a caller can encode the same way")
        void anInjectedCodecIsTheOneUsed() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            Assertions.assertThat(new FieldDiffer(codec).codec()).isSameAs(codec);
        }

        @Test
        @DisplayName("a null charset or codec is refused rather than defaulted")
        void aNullCharsetOrCodecIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> FieldDiffer.forCharset(null))
                .withMessageContainingAll("charset is required", "IBM037");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new FieldDiffer(null))
                .withMessageContaining("FixedWidthCodec is required");
        }

        @Test
        @DisplayName("a null case or fingerprint is refused, and the message says what to pass")
        void aNullArgumentIsRefused() {
            FieldDiffer differ = differ();

            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> differ.compare(null, Fingerprint.ofReturnCode(0)))
                .withMessageContaining("ParityCase is required");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> differ.compare(finalStateCase(), null))
                .withMessageContaining("ofReturnCode");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("A clean comparison: what a satisfied gate looks like")
    class Clean {

        @Test
        @DisplayName("matching fields, return code and messages produce a diff count of zero")
        void aMatchingComparisonIsClean() {
            ParityCase parityCase = new ParityCase("CBACT04C", "case01", "a clean case",
                UnitKind.BATCH_JOB, Map.of(), Map.of(), null, null,
                List.of(new ExpectedRecord(ACCTDAT, 0,
                    Map.of("ACCT-ID", "00000000011", "ACCT-CURR-BAL", "00000001940{"), MONEY_ROW)),
                List.of(), 4,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "RESP:000000000")),
                List.of());
            Fingerprint fingerprint = Fingerprint.of(
                List.of(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)), List.of(), null, 4,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "RESP:000000000")));

            DiffResult result = differ().compare(parityCase, fingerprint);

            Assertions.assertThat(result.isClean()).isTrue();
            Assertions.assertThat(result.count()).isZero();
            Assertions.assertThat(result.entries()).isEmpty();
            Assertions.assertThat(result.render())
                .contains("CBACT04C/case01", "0 differences", "CLEAN");
            Assertions.assertThat(result.program()).isEqualTo("CBACT04C");
            Assertions.assertThat(result.caseId()).isEqualTo("case01");
        }

        @Test
        @DisplayName("a case expecting nothing against a unit producing nothing is clean")
        void expectingNothingAndProducingNothingIsClean() {
            DiffResult result = differ().compare(finalStateCase(), Fingerprint.ofReturnCode(0));

            Assertions.assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("the result list is frozen, so a comparison cannot be edited after the fact")
        void theResultIsFrozen() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "99999999999")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.entries().clear());
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("VALUE_MISMATCH: one difference per field, at full declared width")
    class Values {

        @Test
        @DisplayName("a wrong alphanumeric field is one difference carrying its offset and width")
        void aWrongAlphanumericFieldIsOneDifference() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            Assertions.assertThat(diff.dataset()).isEqualTo(ACCTDAT);
            Assertions.assertThat(diff.rowIndex()).isZero();
            Assertions.assertThat(diff.fieldName()).isEqualTo("ACCT-ID");
            Assertions.assertThat(diff.offset()).isZero();
            Assertions.assertThat(diff.length()).isEqualTo(11);
            Assertions.assertThat(diff.expected()).isEqualTo("00000000099");
            Assertions.assertThat(diff.actual()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("three wrong fields are three differences, not one summary")
        void threeWrongFieldsAreThreeDifferences() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACCT-ID", "00000000099");
            fields.put("ACCT-CURR-BAL", "00000009990{");
            fields.put("FILLER", "X");
            ParityCase parityCase = finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null));

            DiffResult result = differ().compare(parityCase,
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.count()).isEqualTo(3);
            Assertions.assertThat(kindsOf(result))
                .containsOnly(DiffKind.VALUE_MISMATCH);
        }

        @Test
        @DisplayName("comparison is untrimmed: a value short of its declared width is a difference")
        void comparisonIsUntrimmed() {
            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_FNAME, "John")),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(result.count())
                .as("SEC-USR-FNAME is PIC X(20) and its padding is part of its value")
                .isOne();
            Assertions.assertThat(result.entries().get(0).actual()).hasSize(20);
        }

        @Test
        @DisplayName("trailing spaces are counted in the rendering rather than left invisible")
        void trailingSpacesAreCountedInTheRendering() {
            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_FNAME,
                    "Jane" + " ".repeat(16))),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(result.render())
                .contains("16 trailing space(s)", "len=20", "'Jane'", "'John'");
        }

        @Test
        @DisplayName("a FILLER span is comparable, which is how a case proves it was emitted")
        void aFillerSpanIsComparable() {
            DiffResult clean = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "FILLER", " ")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));
            DiffResult dirty = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "FILLER", "0")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(clean.isClean()).isTrue();
            Assertions.assertThat(dirty.count()).isOne();
            Assertions.assertThat(dirty.entries().get(0).kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
        }

        @Test
        @DisplayName("a second FILLER is addressed as FILLER-2, and FILLER-1 is not an alias")
        void theSecondFillerIsAddressedByOrdinal() {
            DatasetOutput observed = output("CCXREF", TWO_FILLER_LAYOUT, "1234" + "abc" + "def");

            DiffResult byOrdinal = differ().compare(
                finalStateCase(pin("CCXREF", 0, "FILLER-2", "def")), finalState(observed));
            DiffResult wrongOrdinal = differ().compare(
                finalStateCase(pin("CCXREF", 0, "FILLER-1", "abc")), finalState(observed));

            Assertions.assertThat(byOrdinal.isClean()).isTrue();
            Assertions.assertThat(wrongOrdinal.entries())
                .singleElement()
                .satisfies(diff -> Assertions.assertThat(diff.kind())
                    .isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT));
        }

        @Test
        @DisplayName("a REDEFINES overlay is addressable under its own name")
        void aRedefinesOverlayIsAddressable() {
            DatasetOutput observed = output("CARDDAT", REDEFINES_LAYOUT, "00000000011");

            DiffResult clean = differ().compare(
                finalStateCase(pin("CARDDAT", 0, "CC-ACCT-ID-N", "00000000011")),
                finalState(observed));
            DiffResult dirty = differ().compare(
                finalStateCase(pin("CARDDAT", 0, "CC-ACCT-ID-N", "00000000099")),
                finalState(observed));

            Assertions.assertThat(clean.isClean()).isTrue();
            Assertions.assertThat(dirty.count()).isOne();
        }

        @Test
        @DisplayName("the explanation names the PICTURE category, so a diff is self-describing")
        void theExplanationNamesThePictureCategory() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("alphanumeric PIC X");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Signed zoned fields: the bytes are authoritative, the value only explains")
    class SignedZoned {

        @Test
        @DisplayName("an identical zoned image is clean")
        void anIdenticalImageIsClean() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000001940{")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("a decimal literal is encoded to the canonical overpunched image and matches")
        void aDecimalLiteralIsEncodedToTheCanonicalImage() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "194.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.isClean())
                .as("encodeSignedScaled produces the overpunch form COBOL itself stores")
                .isTrue();
        }

        @Test
        @DisplayName("a negative literal encodes to the negative overpunch and matches")
        void aNegativeLiteralEncodesToTheNegativeOverpunch() {
            String negative = "00000000011" + "00000009190}" + " ";

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "-919.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, negative)));

            Assertions.assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("THE false pass: an unsigned image and an overpunched one are not the same bytes")
        void anUnsignedImageIsNotTheOverpunchedOne() {
            String unsigned = "00000000011" + "000000019400" + " ";
            FixedWidthCodec codec = differ().codec();

            Assertions.assertThat(codec.decodeSignedScaled("000000019400", 2))
                .as("both images decode to the same number, which is precisely the trap")
                .isEqualByComparingTo(codec.decodeSignedScaled("00000001940{", 2));

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000001940{")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, unsigned)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            Assertions.assertThat(diff.explanation())
                .contains("VALUE agrees", "REPRESENTATION does not", "overpunch");
        }

        @Test
        @DisplayName("negative zero and positive zero decode alike and are still a difference")
        void negativeZeroAndPositiveZeroAreADifference() {
            String negativeZero = "00000000011" + "00000000000}" + " ";
            FixedWidthCodec codec = differ().codec();

            Assertions.assertThat(codec.decodeSignedScaled("00000000000}", 2))
                .isEqualByComparingTo(codec.decodeSignedScaled("00000000000{", 2));

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000000000{")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, negativeZero)));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("VALUE agrees", "opposite overpunch of a zero");
        }

        @ParameterizedTest(name = "positive overpunch {0} matches digit {1}")
        @DisplayName("every positive overpunch form is compared as itself")
        @CsvSource({"'{',0", "A,1", "B,2", "C,3", "D,4", "E,5", "F,6", "G,7", "H,8", "I,9"})
        void everyPositiveOverpunchIsComparedAsItself(String overpunch, String digit) {
            String observed = "00000000011" + "00000000000".substring(0, 11) + overpunch + " ";
            String unsigned = "00000000011" + "00000000000".substring(0, 11) + digit + " ";

            Assertions.assertThat(differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000000000" + overpunch)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, observed))).isClean()).isTrue();
            Assertions.assertThat(differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000000000" + overpunch)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, unsigned))).count()).isOne();
        }

        @ParameterizedTest(name = "negative overpunch {0} is not its unsigned twin")
        @DisplayName("every negative overpunch form is compared as itself")
        @ValueSource(strings = {"}", "J", "K", "L", "M", "N", "O", "P", "Q", "R"})
        void everyNegativeOverpunchIsComparedAsItself(String overpunch) {
            String observed = "00000000011" + "00000000000" + overpunch + " ";

            Assertions.assertThat(differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000000000" + overpunch)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, observed))).isClean()).isTrue();
            Assertions.assertThat(differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "00000000000{")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, observed))).count()).isOne();
        }

        @Test
        @DisplayName("a genuinely different value says so, and names the truncating rounding mode")
        void aDifferentValueSaysSo() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "195.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("values differ as well as the bytes", "195.00", "194.00",
                    CobolDecimal.COBOL_ROUNDING.toString());
        }

        @Test
        @DisplayName("UNDECODABLE_FIELD: observed bytes that are not a zoned image at all")
        void observedBytesThatAreNotAZonedImage() {
            String corrupt = "00000000011" + "ABCDEFGHIJKL" + " ";

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "194.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, corrupt)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.UNDECODABLE_FIELD);
            Assertions.assertThat(diff.explanation())
                .contains("not a valid signed zoned DISPLAY image");
        }

        @Test
        @DisplayName("MALFORMED_EXPECTATION: an expectation that is neither an image nor a literal")
        void anExpectationThatIsNeitherImageNorLiteral() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "about two hundred")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.MALFORMED_EXPECTATION);
            Assertions.assertThat(diff.explanation())
                .as("an unevaluable case has not passed, so it still counts toward the diff count")
                .contains("neither a zoned image", "decimal literal");
        }

        @Test
        @DisplayName("a malformed expectation is reported rather than thrown, so later fields survive")
        void aMalformedExpectationDoesNotAbandonTheComparison() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACCT-CURR-BAL", "not numeric");
            fields.put("ACCT-ID", "00000000099");

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result))
                .as("an exception here would hide every difference after it")
                .containsExactlyInAnyOrder(DiffKind.MALFORMED_EXPECTATION, DiffKind.VALUE_MISMATCH);
        }

        @Test
        @DisplayName("the interest formula's own result is comparable at scale 2, truncated")
        void theInterestFormulaResultIsComparable() {
            BigDecimal monthly = CobolDecimal.monthlyInterest(new BigDecimal("100.00"),
                new BigDecimal("12.99"));
            String image = differ().codec().encodeSignedScaled(monthly, 10, 2);
            String row = "00000000011" + image + " ";

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", monthly.toPlainString())),
                finalState(output(ACCTDAT, MONEY_LAYOUT, row)));

            Assertions.assertThat(monthly).isEqualByComparingTo(new BigDecimal("1.08"));
            Assertions.assertThat(result.isClean()).isTrue();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Missing output: an expectation with nothing to compare against")
    class Missing {

        @Test
        @DisplayName("MISSING_RECORD when the dataset carries no rows at all")
        void missingWhenTheDatasetIsAbsent() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                Fingerprint.ofReturnCode(0));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.MISSING_RECORD);
            Assertions.assertThat(diff.actual()).isNull();
            Assertions.assertThat(diff.explanation()).contains("no dataset at all on this channel");
        }

        @Test
        @DisplayName("MISSING_RECORD when the dataset is shorter than the index addressed")
        void missingWhenTheRowIndexIsBeyondTheRows() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 3, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result))
                .as("row 3 is absent AND the row that is there was never expected; both are found")
                .containsExactly(DiffKind.MISSING_RECORD, DiffKind.EXTRA_RECORD);
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("holds 1", "index 3");
            Assertions.assertThat(result.entries().get(1).rowIndex()).isZero();
        }

        @Test
        @DisplayName("a missing record is one difference, not one per pinned field")
        void aMissingRecordIsOneDifference() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACCT-ID", "00000000011");
            fields.put("ACCT-CURR-BAL", "194.00");
            fields.put("FILLER", " ");

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null)),
                Fingerprint.ofReturnCode(0));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).expected())
                .contains("ACCT-ID", "ACCT-CURR-BAL", "FILLER");
        }

        @Test
        @DisplayName("a write expectation is not satisfied by a final-state row, or vice versa")
        void theTwoChannelsAreNotInterchangeable() {
            DiffResult writeExpected = differ().compare(
                writeCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(writeExpected))
                .as("\"did it write?\" and \"is the state right?\" are different questions")
                .containsExactly(DiffKind.MISSING_RECORD, DiffKind.EXTRA_DATASET);
            Assertions.assertThat(writeExpected.entries().get(0).explanation()).contains("writes");
            Assertions.assertThat(writeExpected.entries().get(1).explanation())
                .contains("final state");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Unexpected output: the half of the comparison a lookup-based judge omits")
    class Unexpected {

        @Test
        @DisplayName("EXTRA_RECORD beyond the highest expected index")
        void anExtraRowBeyondTheHighestExpectedIndex() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_RECORD);
            Assertions.assertThat(result.entries().get(0).rowIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("THE false pass: an extra row BELOW the highest expected index is reported")
        void anExtraRowBelowTheHighestExpectedIndex() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011"),
                    pin(ACCTDAT, 2, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW, MONEY_ROW, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result))
                .as("a 'beyond the last expected row' check would sail straight past index 1")
                .containsExactly(DiffKind.EXTRA_RECORD);
            Assertions.assertThat(result.entries().get(0).rowIndex()).isEqualTo(1);
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("not only beyond the highest expected index");
        }

        @Test
        @DisplayName("THE false pass: empty expectations do not excuse a unit that wrote rows")
        void emptyExpectationsDoNotExcuseWrittenRows() {
            DiffResult result = differ().compare(finalStateCase(),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW, MONEY_ROW, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_DATASET);
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("positive assertion that nothing was produced");
            Assertions.assertThat(result.entries().get(0).actual()).contains("3 row(s)");
        }

        @Test
        @DisplayName("EXTRA_DATASET: a dataset the case never mentions is a finding of its own")
        void aDatasetTheCaseNeverMentions() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW),
                    output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_DATASET);
            Assertions.assertThat(result.entries().get(0).dataset()).isEqualTo(USRSEC);
            Assertions.assertThat(result.entries().get(0).explanation()).contains(ACCTDAT);
        }

        @Test
        @DisplayName("an empty observed dataset the case does not mention is still reported")
        void anEmptyUnexpectedDatasetIsStillReported() {
            DiffResult result = differ().compare(finalStateCase(),
                finalState(DatasetOutput.empty(ACCTDAT, MONEY_LAYOUT)));

            Assertions.assertThat(kindsOf(result))
                .as("touching a dataset the case does not mention is itself the finding")
                .containsExactly(DiffKind.EXTRA_DATASET);
            Assertions.assertThat(result.entries().get(0).actual()).isEqualTo("0 row(s)");
        }

        @Test
        @DisplayName("every unexpected row is reported, not just the first")
        void everyUnexpectedRowIsReported() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 1, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW, MONEY_ROW, MONEY_ROW,
                    MONEY_ROW)));

            Assertions.assertThat(kindsOf(result))
                .containsExactly(DiffKind.EXTRA_RECORD, DiffKind.EXTRA_RECORD,
                    DiffKind.EXTRA_RECORD);
            Assertions.assertThat(result.entries())
                .extracting(Diff::rowIndex)
                .containsExactly(0, 2, 3);
        }

        @Test
        @DisplayName("unexpected rows are reported on the writes channel too")
        void unexpectedRowsAreReportedOnTheWritesChannel() {
            DiffResult result = differ().compare(writeCase(),
                writes(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_DATASET);
            Assertions.assertThat(result.entries().get(0).explanation()).contains("writes");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("RECORD_WIDTH_MISMATCH: nothing is padded at comparison time")
    class Widths {

        @Test
        @DisplayName("an observed row short by one span is one width difference, fields skipped")
        void anObservedRowShortByOneSpanIsOneDifference() {
            String short23 = MONEY_ROW.substring(0, 23);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACCT-ID", "99999999999");
            fields.put("ACCT-CURR-BAL", "999.00");

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, short23)));

            Assertions.assertThat(kindsOf(result))
                .as("field differences after a width error are noise hiding the real finding")
                .containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.expected()).isEqualTo("24");
            Assertions.assertThat(diff.actual()).isEqualTo("23");
            Assertions.assertThat(diff.explanation()).contains("FILLER was omitted");
        }

        @Test
        @DisplayName("an expected image of the wrong width is refused as an authoring error")
        void anExpectedImageOfTheWrongWidthIsRefused() {
            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, Map.of(),
                    MONEY_ROW.substring(0, 20))),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("expectation itself is the wrong width");
        }

        @Test
        @DisplayName("a declared normalisation does not pad at comparison time")
        void aDeclaredNormalisationDoesNotPadAtComparisonTime() {
            String short57 = USRSEC_ROW.substring(0, 57);
            ParityCase parityCase = new ParityCase("COUSR02C", "case01",
                "a seed-time pad must not become a comparison-time pad", UnitKind.BATCH_JOB,
                Map.of(USRSEC, DatasetInput.ofRows(List.of(short57))), Map.of(), null, null,
                List.of(), List.of(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "ADMIN001")), 0,
                List.of(),
                List.of(new DatasetNormalisation(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80)));

            DiffResult result = differ().compare(parityCase,
                finalState(output(USRSEC, SecUserRecord.LAYOUT, short57)));

            Assertions.assertThat(kindsOf(result))
                .as("the pad is applied once at seed time, so a short record here is a real defect")
                .containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("repaired", "SEED time");
        }

        @Test
        @DisplayName("the same row seeded through the normaliser first compares clean")
        void theSameRowNormalisedFirstComparesClean() {
            String seeded = new DatasetNormalisation(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80)
                .normaliseSeedRow(USRSEC_ROW.substring(0, 57));

            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "ADMIN001")),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, seeded)));

            Assertions.assertThat(seeded).hasSize(80);
            Assertions.assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("a row one byte too long is reported just as loudly as one too short")
        void aRowTooLongIsAlsoReported() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW + "X")));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            Assertions.assertThat(result.entries().get(0).actual()).isEqualTo("25");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Whole-record images: pinned at record level, reported at field level")
    class RecordImages {

        @Test
        @DisplayName("an image difference is localised to the span that carries it")
        void anImageDifferenceIsLocalisedToItsSpan() {
            String wrongBalance = "00000000011" + "00000009990{" + " ";

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, Map.of(), MONEY_ROW)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, wrongBalance)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.fieldName()).isEqualTo("ACCT-CURR-BAL");
            Assertions.assertThat(diff.offset()).isEqualTo(11);
            Assertions.assertThat(diff.length()).isEqualTo(12);
        }

        @Test
        @DisplayName("a field pinned by both fields and expectedBytes counts once")
        void aFieldPinnedTwiceCountsOnce() {
            String wrongBalance = "00000000011" + "00000009990{" + " ";

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0,
                    Map.of("ACCT-CURR-BAL", "00000001940{"), MONEY_ROW)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, wrongBalance)));

            Assertions.assertThat(result.count())
                .as("the diff count must stay a count of distinct findings")
                .isOne();
        }

        @Test
        @DisplayName("an image pins every span, FILLER included, which is how a case proves the width")
        void anImagePinsEverySpanIncludingFiller() {
            String wrongFiller = "00000000011" + "00000001940{" + "0";

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, Map.of(), MONEY_ROW)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, wrongFiller)));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).fieldName()).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("a REDEFINES overlay is not reported twice from a whole-record image")
        void anOverlayIsNotReportedTwiceFromAnImage() {
            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord("CARDDAT", 0, Map.of(), "00000000011")),
                finalState(output("CARDDAT", REDEFINES_LAYOUT, "00000000099")));

            Assertions.assertThat(result.count())
                .as("an overlay shares bytes a storage span already covers")
                .isOne();
            Assertions.assertThat(result.entries().get(0).fieldName()).isEqualTo("CC-ACCT-ID");
        }

        @Test
        @DisplayName("a matching image is clean, and proves the FILLER was emitted as spaces")
        void aMatchingImageIsClean() {
            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, Map.of(), MONEY_ROW)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.isClean()).isTrue();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("FIELD_ABSENT_IN_FINGERPRINT: an expectation naming no span at all")
    class AbsentFields {

        @Test
        @DisplayName("a misspelled field name is reported with every addressable name listed")
        void aMisspelledFieldNameIsReported() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BALANCE", "194.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
            Assertions.assertThat(diff.actual()).isNull();
            Assertions.assertThat(diff.explanation())
                .contains("ACCT-ID", "ACCT-CURR-BAL", "FILLER", "ACCT-EXPIRAION-DATE");
        }

        @Test
        @DisplayName("a correctly spelled field and a misspelled one are reported independently")
        void aCorrectAndAMisspelledFieldAreIndependent() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACCT-ID", "00000000099");
            fields.put("ACCT-BALANCE", "194.00");

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(kindsOf(result))
                .containsExactly(DiffKind.VALUE_MISMATCH, DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("The online response: for several paths it is the entire observable behaviour")
    class Responses {

        /** A controller case pinning the supplied response. */
        private ParityCase controllerCase(ExpectedResponse response) {
            return new ParityCase("COUSR02C", "case01", "an online expectation",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(0, "DFHENTER", null, "US-ASCII", Map.of(), Map.of(), Map.of()),
                response, List.of(), List.of(), 0, List.of(), List.of());
        }

        /** The expectation every response test starts from. */
        private ExpectedResponse expected() {
            return new ExpectedResponse("COSGN00C", "COUSR02", "COUSR2A",
                Map.of("CDEMO-TO-PROGRAM", "COSGN00C"),
                List.of(new ScreenSend(Map.of("ERRMSGO", "text"), Map.of("ERRMSGC", "DFHRED"))),
                "USRIDINL", Termination.XCTL);
        }

        /** The observation that satisfies {@link #expected()} exactly. */
        private ObservedResponse observed() {
            return new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                Map.of("CDEMO-TO-PROGRAM", "COSGN00C"),
                List.of(new ObservedSend(Map.of("ERRMSGO", "text"), Map.of("ERRMSGC", "DFHRED"))),
                "USRIDINL", Termination.XCTL);
        }

        /** Compares one response observation against {@link #expected()}. */
        private DiffResult compare(ObservedResponse response) {
            return differ().compare(controllerCase(expected()),
                Fingerprint.of(List.of(), List.of(), response, 0, List.of()));
        }

        @Test
        @DisplayName("a matching response is clean")
        void aMatchingResponseIsClean() {
            Assertions.assertThat(compare(observed()).isClean()).isTrue();
        }

        @ParameterizedTest(name = "a wrong {0} is a RESPONSE_MISMATCH")
        @DisplayName("every scalar response field is compared")
        @CsvSource({"nextProgram", "nextMapset", "nextMap", "cursorField", "termination"})
        void everyScalarResponseFieldIsCompared(String field) {
            ObservedResponse wrong = switch (field) {
                case "nextProgram" -> new ObservedResponse("COADM01C", "COUSR02", "COUSR2A",
                    observed().navigation(), observed().sends(), "USRIDINL", Termination.XCTL);
                case "nextMapset" -> new ObservedResponse("COSGN00C", "COSGN00", "COUSR2A",
                    observed().navigation(), observed().sends(), "USRIDINL", Termination.XCTL);
                case "nextMap" -> new ObservedResponse("COSGN00C", "COUSR02", "COSGN0A",
                    observed().navigation(), observed().sends(), "USRIDINL", Termination.XCTL);
                case "cursorField" -> new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                    observed().navigation(), observed().sends(), "FNAMEL", Termination.XCTL);
                default -> new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                    observed().navigation(), observed().sends(), "USRIDINL",
                    Termination.RETURN_TRANSID);
            };

            DiffResult result = compare(wrong);

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).kind())
                .isEqualTo(DiffKind.RESPONSE_MISMATCH);
            Assertions.assertThat(result.entries().get(0).fieldName()).isEqualTo(field);
        }

        @Test
        @DisplayName("an absent scalar and a present one are distinct, not both \"empty\"")
        void anAbsentScalarIsDistinctFromAPresentOne() {
            DiffResult result = compare(new ObservedResponse(null, "COUSR02", "COUSR2A",
                observed().navigation(), observed().sends(), "USRIDINL", Termination.XCTL));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).actual()).isNull();
        }

        @Test
        @DisplayName("a missing commarea field is reported as absent, not merely different")
        void aMissingCommareaFieldIsReportedAsAbsent() {
            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                Map.of(), observed().sends(), "USRIDINL", Termination.XCTL));

            Assertions.assertThat(kindsOf(result))
                .containsExactly(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
            Assertions.assertThat(result.entries().get(0).fieldName())
                .isEqualTo("navigation.CDEMO-TO-PROGRAM");
        }

        @Test
        @DisplayName("an unpinned commarea field is reported: it is a field nobody has checked")
        void anUnpinnedCommareaFieldIsReported() {
            Map<String, String> navigation = new LinkedHashMap<>();
            navigation.put("CDEMO-TO-PROGRAM", "COSGN00C");
            navigation.put("CDEMO-USER-TYPE", "A");

            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                navigation, observed().sends(), "USRIDINL", Termination.XCTL));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.RESPONSE_MISMATCH);
            Assertions.assertThat(result.entries().get(0).fieldName())
                .isEqualTo("navigation.CDEMO-USER-TYPE");
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("carried forward into the next transaction");
        }

        @Test
        @DisplayName("SEND_COUNT_MISMATCH: the send count is behaviour in its own right")
        void theSendCountIsBehaviour() {
            List<ObservedSend> twice = List.of(observed().sends().get(0), observed().sends().get(0));

            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                observed().navigation(), twice, "USRIDINL", Termination.XCTL));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.SEND_COUNT_MISMATCH);
            Assertions.assertThat(result.entries().get(0).expected()).isEqualTo("1");
            Assertions.assertThat(result.entries().get(0).actual()).isEqualTo("2");
        }

        @Test
        @DisplayName("the common prefix of the sends is still compared when the counts differ")
        void theCommonPrefixIsStillComparedWhenCountsDiffer() {
            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                observed().navigation(),
                List.of(ObservedSend.ofFields(Map.of("ERRMSGO", "different"))),
                "USRIDINL", Termination.XCTL));

            Assertions.assertThat(kindsOf(result))
                .contains(DiffKind.RESPONSE_MISMATCH);
            Assertions.assertThat(result.render()).contains("sends[0].fields.ERRMSGO");
        }

        @Test
        @DisplayName("a send's BMS payload field is compared, and located by send index")
        void aSendPayloadFieldIsCompared() {
            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                observed().navigation(),
                List.of(new ObservedSend(Map.of("ERRMSGO", "wrong"), Map.of("ERRMSGC", "DFHRED"))),
                "USRIDINL", Termination.XCTL));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).fieldName())
                .isEqualTo("sends[0].fields.ERRMSGO");
        }

        @Test
        @DisplayName("a send's attribute metadata is compared: colour is behaviour, not decoration")
        void aSendAttributeIsCompared() {
            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                observed().navigation(),
                List.of(new ObservedSend(Map.of("ERRMSGO", "text"), Map.of("ERRMSGC", "DFHGREEN"))),
                "USRIDINL", Termination.XCTL));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).fieldName())
                .isEqualTo("sends[0].attributes.ERRMSGC");
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("Colour is not decoration");
        }

        @Test
        @DisplayName("an unpinned send field is reported as unchecked")
        void anUnpinnedSendFieldIsReported() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ERRMSGO", "text");
            fields.put("TITLE01O", "AWS Mainframe Modernization");

            DiffResult result = compare(new ObservedResponse("COSGN00C", "COUSR02", "COUSR2A",
                observed().navigation(),
                List.of(new ObservedSend(fields, Map.of("ERRMSGC", "DFHRED"))),
                "USRIDINL", Termination.XCTL));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.RESPONSE_MISMATCH);
            Assertions.assertThat(result.entries().get(0).fieldName())
                .isEqualTo("sends[0].fields.TITLE01O");
        }

        @Test
        @DisplayName("a case expecting a response against a unit returning none is a difference")
        void expectingAResponseAndGettingNoneIsADifference() {
            DiffResult result = differ().compare(controllerCase(expected()),
                Fingerprint.ofReturnCode(0));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.RESPONSE_MISMATCH);
            Assertions.assertThat(diff.fieldName()).isEqualTo("<whole>");
            Assertions.assertThat(diff.explanation()).contains("no-commarea guard");
        }

        @Test
        @DisplayName("a batch case against a unit that returned a response is a difference")
        void aBatchCaseAgainstAResponseIsADifference() {
            DiffResult result = differ().compare(finalStateCase(),
                Fingerprint.of(List.of(), List.of(), observed(), 0, List.of()));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.entries().get(0).explanation())
                .contains("A batch case has no screen");
        }

        @Test
        @DisplayName("no response expected and none returned is clean")
        void noResponseEitherSideIsClean() {
            Assertions.assertThat(differ().compare(finalStateCase(), Fingerprint.ofReturnCode(0))
                .isClean()).isTrue();
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Return code and emitted messages")
    class ReturnCodeAndMessages {

        /** A batch case expecting the given return code and messages. */
        private ParityCase caseWith(int returnCode, EmittedMessage... messages) {
            return new ParityCase("CBACT01C", "case01", "a return code and its lines",
                UnitKind.BATCH_JOB, Map.of(), Map.of(), null, null, List.of(), List.of(),
                returnCode, List.of(messages), List.of());
        }

        @ParameterizedTest(name = "expected {0} against observed {1}")
        @DisplayName("RETURN_CODE_MISMATCH for every pairing this migration can produce")
        @CsvSource({"0,4", "0,8", "0,12", "0,16", "4,0", "8,12", "12,8", "16,0", "3,0"})
        void everyReturnCodePairingIsCompared(int expected, int actual) {
            DiffResult result = differ().compare(caseWith(expected),
                Fingerprint.ofReturnCode(actual));

            Assertions.assertThat(result.count()).isOne();
            Diff diff = result.entries().get(0);
            Assertions.assertThat(diff.kind()).isEqualTo(DiffKind.RETURN_CODE_MISMATCH);
            Assertions.assertThat(diff.expected()).isEqualTo(Integer.toString(expected));
            Assertions.assertThat(diff.actual()).isEqualTo(Integer.toString(actual));
            Assertions.assertThat(diff.explanation()).contains("JCL COND gating");
        }

        @ParameterizedTest(name = "return code {0} matches itself")
        @DisplayName("a matching return code is clean")
        @ValueSource(ints = {0, 3, 4, 8, 12, 16})
        void aMatchingReturnCodeIsClean(int returnCode) {
            Assertions.assertThat(differ().compare(caseWith(returnCode),
                Fingerprint.ofReturnCode(returnCode)).isClean()).isTrue();
        }

        @Test
        @DisplayName("MESSAGE_MISMATCH is byte-exact, including a single trailing space")
        void messageComparisonIsByteExact() {
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.DISPLAY_LINE, "ACCOUNT FILE OPENED")),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                        "ACCOUNT FILE OPENED "))));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.MESSAGE_MISMATCH);
            Assertions.assertThat(result.render()).contains("1 trailing space(s)");
        }

        @Test
        @DisplayName("MESSAGE_CHANNEL_MISMATCH: 80 bytes and 78 bytes are not the same expectation")
        void aChannelDifferenceIsItsOwnKind() {
            String text = "Invalid key pressed. Please see below...";
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.WS_MESSAGE_80,
                    text + " ".repeat(80 - text.length()))),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78,
                        text + " ".repeat(78 - text.length())))));

            Assertions.assertThat(kindsOf(result))
                .as("MOVE WS-MESSAGE TO ERRMSGO loses the last two bytes; that is the defect")
                .containsExactly(DiffKind.MESSAGE_CHANNEL_MISMATCH);
            Assertions.assertThat(result.entries().get(0).expected()).isEqualTo("WS_MESSAGE_80");
            Assertions.assertThat(result.entries().get(0).actual()).isEqualTo("SCREEN_ERRMSG_78");
        }

        @Test
        @DisplayName("a channel difference is reported instead of a text difference at that position")
        void aChannelDifferenceReplacesTheTextDifference() {
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.WS_MESSAGE_80, " ".repeat(80))),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "something else"))));

            Assertions.assertThat(result.count())
                .as("the widths differ, so the text cannot mean anything until the channel matches")
                .isOne();
        }

        @Test
        @DisplayName("MESSAGE_COUNT_MISMATCH, with the common prefix still compared")
        void aCountDifferenceStillComparesTheCommonPrefix() {
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.DISPLAY_LINE, "first"),
                    new EmittedMessage(MessageChannel.DISPLAY_LINE, "second")),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "wrong"))));

            Assertions.assertThat(kindsOf(result))
                .containsExactly(DiffKind.MESSAGE_COUNT_MISMATCH, DiffKind.MESSAGE_MISMATCH);
            Assertions.assertThat(result.entries().get(1).rowIndex()).isZero();
        }

        @Test
        @DisplayName("an unexpected emitted line is reported through the count")
        void anUnexpectedLineIsReported() {
            DiffResult result = differ().compare(caseWith(0),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "unexpected"))));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.MESSAGE_COUNT_MISMATCH);
            Assertions.assertThat(result.entries().get(0).expected()).isEqualTo("0");
        }

        @Test
        @DisplayName("an empty DISPLAY line is a legitimate expectation and compares cleanly")
        void anEmptyDisplayLineIsLegitimate() {
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.DISPLAY_LINE, "")),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, ""))));

            Assertions.assertThat(result.isClean()).isTrue();
        }

        @Test
        @DisplayName("each positional difference is reported, not only the first")
        void everyPositionalDifferenceIsReported() {
            DiffResult result = differ().compare(
                caseWith(0, new EmittedMessage(MessageChannel.DISPLAY_LINE, "a"),
                    new EmittedMessage(MessageChannel.DISPLAY_LINE, "b"),
                    new EmittedMessage(MessageChannel.DISPLAY_LINE, "c")),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "x"),
                        new EmittedMessage(MessageChannel.DISPLAY_LINE, "b"),
                        new EmittedMessage(MessageChannel.DISPLAY_LINE, "z"))));

            Assertions.assertThat(kindsOf(result))
                .containsExactly(DiffKind.MESSAGE_MISMATCH, DiffKind.MESSAGE_MISMATCH);
            Assertions.assertThat(result.entries())
                .extracting(Diff::rowIndex)
                .containsExactly(0, 2);
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Determinism and traversal order")
    class Determinism {

        @Test
        @DisplayName("two comparisons of the same inputs render identically")
        void twoComparisonsRenderIdentically() {
            ParityCase parityCase = finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099"),
                pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "USER0001"));
            Fingerprint fingerprint = finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW),
                output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW));

            String first = differ().compare(parityCase, fingerprint).render();
            String second = differ().compare(parityCase, fingerprint).render();

            Assertions.assertThat(second)
                .as("no traversal here may consult a hash-ordered collection")
                .isEqualTo(first);
        }

        @Test
        @DisplayName("the channels are traversed writes, final state, response, return code, messages")
        void theChannelsAreTraversedInAFixedOrder() {
            ParityCase parityCase = new ParityCase("COUSR02C", "case01", "one difference per channel",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(0, null, null, null, Map.of(), Map.of(), Map.of()),
                new ExpectedResponse("COSGN00C", null, null, Map.of(), List.of(), null,
                    Termination.XCTL),
                List.of(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                List.of(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "USER0001")),
                4, List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "expected line")),
                List.of());
            Fingerprint fingerprint = Fingerprint.of(
                List.of(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)),
                List.of(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)),
                new ObservedResponse("COADM01C", null, null, Map.of(), List.of(), null,
                    Termination.XCTL),
                0, List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "observed line")));

            DiffResult result = differ().compare(parityCase, fingerprint);

            Assertions.assertThat(result.entries())
                .extracting(Diff::dataset)
                .containsExactly(ACCTDAT, USRSEC, "<response>", "<return-code>", "<messages>");
        }

        @Test
        @DisplayName("field differences within a record are reported in copybook declaration order")
        void fieldDifferencesFollowCopybookOrder() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("FILLER", "X");
            fields.put("ACCT-CURR-BAL", "999.00");
            fields.put("ACCT-ID", "00000000099");

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(ACCTDAT, 0, fields, null)),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.entries())
                .as("a rendered failure should read down the record the way the copybook does")
                .extracting(Diff::fieldName)
                .containsExactly("ACCT-ID", "ACCT-CURR-BAL", "FILLER");
        }

        @Test
        @DisplayName("the report renders every difference and is never truncated or summarised")
        void theReportIsNeverTruncated() {
            List<ExpectedRecord> expectations = new ArrayList<>();
            for (int row = 0; row < 25; row++) {
                expectations.add(pin(ACCTDAT, row, "ACCT-ID", "00000000099"));
            }
            String[] rows = new String[25];
            Arrays.fill(rows, MONEY_ROW);

            DiffResult result = differ().compare(
                finalStateCase(expectations.toArray(new ExpectedRecord[0])),
                finalState(output(ACCTDAT, MONEY_LAYOUT, rows)));

            Assertions.assertThat(result.count()).isEqualTo(25);
            Assertions.assertThat(result.render())
                .contains("[1/25]", "[20/25]", "[25/25]", "25 differences")
                .doesNotContain("...");
        }

        @Test
        @DisplayName("a fingerprint cannot be perturbed after capture, so two compares agree")
        void aFingerprintCannotBePerturbedAfterCapture() {
            byte[] row = MONEY_ROW.getBytes(ASCII);
            List<byte[]> rows = new ArrayList<>();
            rows.add(row);
            DatasetOutput captured = DatasetOutput.of(ACCTDAT, MONEY_LAYOUT, rows);

            row[0] = 'X';
            rows.clear();

            Assertions.assertThat(captured.rowCount()).isOne();
            Assertions.assertThat(new String(captured.row(0), ASCII)).isEqualTo(MONEY_ROW);
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Redaction in the rendered report: complete without being a disclosure")
    class Redactions {

        @Test
        @DisplayName("a wrong password is reported as a difference with neither value shown")
        void aWrongPasswordIsReportedWithoutShowingIt() {
            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_PWD, "SECRET99")),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(result.count())
                .as("comparison is unaffected by masking; a wrong password is still a difference")
                .isOne();
            Assertions.assertThat(result.render())
                .contains(SecUserRecord.FIELD_SEC_USR_PWD, Redaction.MASK)
                .doesNotContain("PASSWORD", "SECRET99");
        }

        @Test
        @DisplayName("the credential span inside a whole-record image is masked in the report")
        void theCredentialSpanInAnImageIsMasked() {
            String wrong = USRSEC_ROW.substring(0, 56) + "U" + USRSEC_ROW.substring(57);

            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(USRSEC, 0, Map.of(), USRSEC_ROW)),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, wrong)));

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.render())
                .as("the type byte is the difference; the password must not travel with it")
                .contains(SecUserRecord.FIELD_SEC_USR_TYPE)
                .doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("a missing USRSEC record masks the credential span of its expected image")
        void aMissingRecordMasksTheImageItExpected() {
            DiffResult result = differ().compare(
                finalStateCase(new ExpectedRecord(USRSEC, 0, Map.of(), USRSEC_ROW)),
                Fingerprint.ofReturnCode(0));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.MISSING_RECORD);
            Assertions.assertThat(result.render())
                .contains("********")
                .doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("an extra USRSEC row does not print the password it carries")
        void anExtraRowDoesNotPrintThePassword() {
            DiffResult result = differ().compare(finalStateCase(),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_DATASET);
            Assertions.assertThat(result.render()).doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("an unexpected USRSEC row masks the credential span of the image it prints")
        void anExtraRowMasksItsCredentialSpan() {
            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "ADMIN001")),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW, USRSEC_ROW)));

            Assertions.assertThat(kindsOf(result)).containsExactly(DiffKind.EXTRA_RECORD);
            Assertions.assertThat(result.render())
                .as("an extra row is printed as a whole image, which is where the password sits")
                .contains("ADMIN001", "********")
                .doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("a send carrying PASSWDO does not print its value")
        void aSendCarryingPasswdoDoesNotPrintIt() {
            ParityCase parityCase = new ParityCase("COUSR02C", "case01", "a password on the screen",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(0, null, null, null, Map.of(), Map.of(), Map.of()),
                new ExpectedResponse(null, null, null, Map.of(),
                    List.of(new ScreenSend(Map.of("PASSWDO", "SECRET99"), Map.of())), null,
                    Termination.RETURN_TRANSID),
                List.of(), List.of(), 0, List.of(), List.of());
            Fingerprint fingerprint = Fingerprint.of(List.of(), List.of(),
                new ObservedResponse(null, null, null, Map.of(),
                    List.of(ObservedSend.ofFields(Map.of("PASSWDO", "PASSWORD"))), null,
                    Termination.RETURN_TRANSID),
                0, List.of());

            DiffResult result = differ().compare(parityCase, fingerprint);

            Assertions.assertThat(result.count()).isOne();
            Assertions.assertThat(result.render())
                .contains("PASSWDO", Redaction.MASK)
                .doesNotContain("PASSWORD", "SECRET99");
        }

        @Test
        @DisplayName("an ordinary field is shown in full, or a difference could not be diagnosed")
        void anOrdinaryFieldIsShownInFull() {
            DiffResult result = differ().compare(
                finalStateCase(pin(USRSEC, 0, SecUserRecord.FIELD_SEC_USR_ID, "USER0001")),
                finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW)));

            Assertions.assertThat(result.render()).contains("USER0001", "ADMIN001");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Rendering: a byte a reviewer cannot see is a byte they cannot diagnose")
    class Rendering {

        @Test
        @DisplayName("a non-printing byte is escaped rather than swallowed")
        void aNonPrintingByteIsEscaped() {
            String withControl = "0000000001\u0001" + "00000001940{" + " ";

            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, withControl)));

            Assertions.assertThat(result.render()).contains("\\x01");
        }

        @Test
        @DisplayName("an absent value renders as absent rather than as an empty string")
        void anAbsentValueRendersAsAbsent() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                Fingerprint.ofReturnCode(0));

            Assertions.assertThat(result.render()).contains("<absent>");
        }

        @Test
        @DisplayName("the offset and width are printed, so nobody counts bytes in a copybook")
        void theOffsetAndWidthArePrinted() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "999.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.render()).contains("offset 11", "length 12");
        }

        @Test
        @DisplayName("a difference renders the same way alone as it does inside a report")
        void aDifferenceRendersConsistently() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));
            Diff diff = result.entries().get(0);

            Assertions.assertThat(diff.toString()).isEqualTo(diff.render());
            Assertions.assertThat(result.toString()).isEqualTo(result.render());
            Assertions.assertThat(result.render()).contains(diff.render());
        }

        @Test
        @DisplayName("a singular difference is described in the singular")
        void aSingularDifferenceReadsAsOne() {
            DiffResult result = differ().compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)));

            Assertions.assertThat(result.render()).contains("1 difference -")
                .doesNotContain("1 differences");
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("Fingerprint, DatasetOutput and Diff: the capture contract")
    class CaptureContract {

        @Test
        @DisplayName("a negative return code is refused as a sign error in the capture")
        void aNegativeReturnCodeIsRefused() {
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> Fingerprint.ofReturnCode(-1))
                .withMessageContaining("never negative");
        }

        @Test
        @DisplayName("a null channel list is refused, and the message says to pass an empty one")
        void aNullChannelListIsRefused() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> Fingerprint.of(null, List.of(), null, 0, List.of()))
                .withMessageContaining("writes are required");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> Fingerprint.of(List.of(), null, null, 0, List.of()))
                .withMessageContaining("finalState is required");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> Fingerprint.of(List.of(), List.of(), null, 0, null))
                .withMessageContaining("messages are required");
        }

        @Test
        @DisplayName("the same dataset twice in one channel is refused rather than shadowed")
        void aDuplicateDatasetInOneChannelIsRefused() {
            DatasetOutput output = output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW);

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> Fingerprint.of(List.of(output, output), List.of(), null, 0,
                    List.of()))
                .withMessageContainingAll("more than once", "silently shadow");
        }

        @Test
        @DisplayName("the same dataset on both channels is legitimate: they answer different questions")
        void theSameDatasetOnBothChannelsIsLegitimate() {
            DatasetOutput output = output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW);
            Fingerprint fingerprint = Fingerprint.of(List.of(output), List.of(output), null, 0,
                List.of());

            Assertions.assertThat(fingerprint.findWrites(ACCTDAT)).isPresent();
            Assertions.assertThat(fingerprint.findFinalState(ACCTDAT)).isPresent();
            Assertions.assertThat(fingerprint.findWrites("NOSUCH")).isEmpty();
            Assertions.assertThat(fingerprint.findFinalState("NOSUCH")).isEmpty();
        }

        @Test
        @DisplayName("a null message or dataset entry is refused")
        void aNullEntryIsRefused() {
            List<EmittedMessage> messages = new ArrayList<>();
            messages.add(null);
            List<DatasetOutput> outputs = new ArrayList<>();
            outputs.add(null);

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> Fingerprint.of(List.of(), List.of(), null, 0, messages))
                .withMessageContaining("message 0 is null");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> Fingerprint.of(outputs, List.of(), null, 0, List.of()))
                .withMessageContaining("writes entry 0 is null");
        }

        @Test
        @DisplayName("ofReturnCode captures a pure computation: no dataset, no response, no line")
        void ofReturnCodeCapturesAPureComputation() {
            Fingerprint fingerprint = Fingerprint.ofReturnCode(3);

            Assertions.assertThat(fingerprint.writes()).isEmpty();
            Assertions.assertThat(fingerprint.finalState()).isEmpty();
            Assertions.assertThat(fingerprint.response()).isNull();
            Assertions.assertThat(fingerprint.messages()).isEmpty();
            Assertions.assertThat(fingerprint.returnCode()).isEqualTo(3);
        }

        @Test
        @DisplayName("the fingerprint's own rendering names shapes and counts, never a value")
        void theFingerprintRenderingNamesShapesOnly() {
            Fingerprint fingerprint = finalState(output(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW));

            Assertions.assertThat(fingerprint.toString())
                .contains(USRSEC)
                .doesNotContain("PASSWORD", "ADMIN001");
        }

        @Test
        @DisplayName("DatasetOutput rejects a blank key, a null layout and a null row")
        void datasetOutputRejectsMalformedInput() {
            List<byte[]> withHole = new ArrayList<>();
            withHole.add(null);

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> DatasetOutput.of("  ", MONEY_LAYOUT, List.of()))
                .withMessageContaining("must not be blank");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> DatasetOutput.of(ACCTDAT, null, List.of()))
                .withMessageContaining("RecordLayout is required");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> DatasetOutput.of(ACCTDAT, MONEY_LAYOUT, withHole))
                .withMessageContaining("row 0 of dataset");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> DatasetOutput.ofImages(ACCTDAT, MONEY_LAYOUT, List.of(), null))
                .withMessageContaining("charset is required");
        }

        @Test
        @DisplayName("DatasetOutput hands out copies, and row() states the bound it violated")
        void datasetOutputHandsOutCopies() {
            DatasetOutput captured = output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW);

            byte[] handedOut = captured.row(0);
            handedOut[0] = 'Z';

            Assertions.assertThat(new String(captured.row(0), ASCII)).isEqualTo(MONEY_ROW);
            Assertions.assertThat(captured.hasRow(0)).isTrue();
            Assertions.assertThat(captured.hasRow(1)).isFalse();
            Assertions.assertThat(captured.hasRow(-1)).isFalse();
            Assertions.assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(() -> captured.row(1));
        }

        @Test
        @DisplayName("an empty DatasetOutput states that a dataset was opened and left alone")
        void anEmptyOutputStatesAnUntouchedDataset() {
            DatasetOutput empty = DatasetOutput.empty(ACCTDAT, MONEY_LAYOUT);

            Assertions.assertThat(empty.rowCount()).isZero();
            Assertions.assertThat(empty.dataset()).isEqualTo(ACCTDAT);
            Assertions.assertThat(empty.layout()).isSameAs(MONEY_LAYOUT);
            Assertions.assertThat(empty.toString()).contains(ACCTDAT, "0 row(s)");
        }

        @Test
        @DisplayName("a Diff refuses a blank member and a negative index that is not the sentinel")
        void aDiffRefusesMalformedMembers() {
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new Diff(null, 0, "F", 0, 1, "a", "b", DiffKind.VALUE_MISMATCH,
                    "why"))
                .withMessageContaining("Diff.dataset is required");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Diff(" ", 0, "F", 0, 1, "a", "b", DiffKind.VALUE_MISMATCH,
                    "why"))
                .withMessageContaining("must not be blank");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Diff(ACCTDAT, 0, " ", 0, 1, "a", "b",
                    DiffKind.VALUE_MISMATCH, "why"))
                .withMessageContaining("fieldName must not be blank");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Diff(ACCTDAT, 0, "F", 0, 1, "a", "b",
                    DiffKind.VALUE_MISMATCH, " "))
                .withMessageContaining("explanation must not be blank");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new Diff(ACCTDAT, -2, "F", 0, 1, "a", "b",
                    DiffKind.VALUE_MISMATCH, "why"))
                .withMessageContaining("rowIndex");
            Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new Diff(ACCTDAT, 0, "F", 0, 1, "a", "b", null, "why"))
                .withMessageContaining("Diff.kind is required");
        }

        @Test
        @DisplayName("the not-applicable sentinel is negative, so no real index can collide with it")
        void theSentinelCannotCollideWithARealIndex() {
            Assertions.assertThat(Diff.NOT_APPLICABLE).isNegative();

            Diff diff = new Diff(ACCTDAT, Diff.NOT_APPLICABLE, "<record>", Diff.NOT_APPLICABLE,
                Diff.NOT_APPLICABLE, null, null, DiffKind.EXTRA_DATASET, "why");

            Assertions.assertThat(diff.render())
                .doesNotContain("row -1", "offset -1", "length -1");
        }

        @ParameterizedTest
        @DisplayName("every DiffKind renders its own name, so no kind is anonymous in a report")
        @EnumSource(DiffKind.class)
        void everyKindRendersItsName(DiffKind kind) {
            Diff diff = new Diff(ACCTDAT, 0, "ACCT-ID", 0, 11, "a", "b", kind, "why");

            Assertions.assertThat(diff.render()).startsWith(kind.name());
        }
    }

    // ===============================================================================================
    @Nested
    @DisplayName("No decorative kind: every DiffKind is produced by a real comparison")
    class Coverage {

        @Test
        @DisplayName("all fourteen kinds are reachable through compare()")
        void allKindsAreReachableThroughCompare() {
            Set<DiffKind> produced = EnumSet.noneOf(DiffKind.class);
            FieldDiffer differ = differ();

            // VALUE_MISMATCH, MISSING_RECORD, EXTRA_RECORD, EXTRA_DATASET
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000099")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)))));
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                Fingerprint.ofReturnCode(0))));
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW, MONEY_ROW)))));
            produced.addAll(kindsOf(differ.compare(finalStateCase(),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)))));

            // RECORD_WIDTH_MISMATCH, FIELD_ABSENT_IN_FINGERPRINT
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-ID", "00000000011")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW.substring(0, 23))))));
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "NO-SUCH-FIELD", "x")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)))));

            // UNDECODABLE_FIELD, MALFORMED_EXPECTATION
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "194.00")),
                finalState(output(ACCTDAT, MONEY_LAYOUT,
                    "00000000011" + "ABCDEFGHIJKL" + " ")))));
            produced.addAll(kindsOf(differ.compare(
                finalStateCase(pin(ACCTDAT, 0, "ACCT-CURR-BAL", "not numeric")),
                finalState(output(ACCTDAT, MONEY_LAYOUT, MONEY_ROW)))));

            // RESPONSE_MISMATCH, SEND_COUNT_MISMATCH
            produced.addAll(kindsOf(differ.compare(finalStateCase(),
                Fingerprint.of(List.of(), List.of(),
                    new ObservedResponse("COADM01C", null, null, Map.of(), List.of(), null,
                        Termination.XCTL), 0, List.of()))));
            produced.addAll(kindsOf(differ.compare(
                new ParityCase("COUSR02C", "case01", "one send expected", UnitKind.CONTROLLER_POJO,
                    Map.of(), Map.of(),
                    new ScreenRequest(0, null, null, null, Map.of(), Map.of(), Map.of()),
                    new ExpectedResponse(null, null, null, Map.of(),
                        List.of(new ScreenSend(Map.of("ERRMSGO", "t"), Map.of())), null,
                        Termination.RETURN_TRANSID),
                    List.of(), List.of(), 0, List.of(), List.of()),
                Fingerprint.of(List.of(), List.of(),
                    new ObservedResponse(null, null, null, Map.of(), List.of(), null,
                        Termination.RETURN_TRANSID), 0, List.of()))));

            // RETURN_CODE_MISMATCH, MESSAGE_COUNT_MISMATCH, MESSAGE_MISMATCH,
            // MESSAGE_CHANNEL_MISMATCH
            produced.addAll(kindsOf(differ.compare(finalStateCase(),
                Fingerprint.ofReturnCode(8))));
            produced.addAll(kindsOf(differ.compare(finalStateCase(),
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "extra"))))));
            ParityCase messageCase = new ParityCase("CBACT01C", "case01", "two channels",
                UnitKind.BATCH_JOB, Map.of(), Map.of(), null, null, List.of(), List.of(), 0,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "a"),
                    new EmittedMessage(MessageChannel.WS_MESSAGE_80, " ".repeat(80))),
                List.of());
            produced.addAll(kindsOf(differ.compare(messageCase,
                Fingerprint.of(List.of(), List.of(), null, 0,
                    List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "b"),
                        new EmittedMessage(MessageChannel.SCREEN_ERRMSG_78, " ".repeat(78)))))));

            Assertions.assertThat(produced)
                .as("a kind no comparison can produce is a claim the differ does not honour")
                .containsExactlyInAnyOrder(DiffKind.values());
        }

        @Test
        @DisplayName("there are fourteen kinds and every one counts toward the diff count")
        void everyKindCountsTowardTheDiffCount() {
            Assertions.assertThat(DiffKind.values())
                .as("an advisory kind in a parity gate is a finding that gets ignored")
                .hasSize(14);
        }
    }
}
