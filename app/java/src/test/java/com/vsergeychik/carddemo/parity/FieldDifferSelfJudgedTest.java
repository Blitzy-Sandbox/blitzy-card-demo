package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DatasetOutput;
import com.vsergeychik.carddemo.parity.FieldDiffer.Diff;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffKind;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.Fingerprint;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Self-tests for {@link FieldDiffer}, the deterministic judge of this migration.
 */
@DisplayName("FieldDiffer - the deterministic judge, itself judged")
class FieldDifferSelfJudgedTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String PROGRAM = "CBACT01C";

    private static final String CASE_ID = "case01";

    private static final String DESCRIPTION = "FieldDiffer self-test";

    private static final String ACCOUNT = "ACCTFILE";

    private static final String XREF = "CARDXREF";

    private static final String USRSEC = "USRSEC";

    private static final String RETURN_CODE_SCOPE = "<return-code>";

    private static final String MESSAGES_SCOPE = "<messages>";

    private static final String RECORD_SCOPE_FIELD = "<record>";

    private static final String ACCOUNT_FIXTURE = "fixtures/acctdata.txt";

    private static final String XREF_FIXTURE = "fixtures/cardxref.txt";

    private static final String USRSEC_ROW_57 =
            "ADMIN001MARGARET            GOLD                PASSWORDA";

    private static final int USRSEC_FILLER_WIDTH = 23;

    private static final int XREF_FILLER_WIDTH = 14;

    private static final String XREF_CARD_NUM = "XREF-CARD-NUM";

    private static final String XREF_CUST_ID = "XREF-CUST-ID";

    private static final String XREF_ACCT_ID = "XREF-ACCT-ID";

    private static final String FILLER = "FILLER";

    private static final String ACCT_ID = "ACCT-ID";

    private static final String ACCT_ACTIVE_STATUS = "ACCT-ACTIVE-STATUS";

    private static final String ACCT_CURR_BAL = "ACCT-CURR-BAL";

    private static final String ACCT_CREDIT_LIMIT = "ACCT-CREDIT-LIMIT";

    private static final String ACCT_OPEN_DATE = "ACCT-OPEN-DATE";

    private static final String ACCT_EXPIRAION_DATE = "ACCT-EXPIRAION-DATE";

    private static final String SEC_USR_ID = "SEC-USR-ID";

    private static final String SEC_USR_LNAME = "SEC-USR-LNAME";

    private static final String SEC_USR_FILLER = "SEC-USR-FILLER";

    private final FieldDiffer differ = FieldDiffer.forCharset(ASCII);

    private static ParityCase caseOf(ExpectedRecord... expected) {
        return caseOf(List.of(expected), 0, List.of(), List.of());
    }

    private static ParityCase caseOf(List<Normalisation> normalisations, ExpectedRecord... expected) {
        return caseOf(List.of(expected), 0, List.of(), normalisations);
    }

    private static ParityCase caseOf(List<ExpectedRecord> expected,
                                     int returnCode,
                                     List<String> messages,
                                     List<Normalisation> normalisations) {
        return new ParityCase(PROGRAM, CASE_ID, DESCRIPTION, UnitKind.BATCH_JOB,
                seedsFor(expected, normalisations), Map.of(), null, null,
                expected, List.of(), returnCode, displayLines(messages),
                boundNormalisations(expected, normalisations));
    }

    private static List<EmittedMessage> displayLines(List<String> messages) {
        List<EmittedMessage> lines = new ArrayList<>(messages.size());
        for (String text : messages) {
            lines.add(new EmittedMessage(MessageChannel.DISPLAY_LINE, text));
        }
        return lines;
    }

    private static List<DatasetNormalisation> boundNormalisations(List<ExpectedRecord> expected,
                                                                 List<Normalisation> declared) {
        List<DatasetNormalisation> bound = new ArrayList<>(declared.size());
        for (Normalisation kind : declared) {
            bound.add(new DatasetNormalisation(datasetFor(kind, expected), kind));
        }
        return bound;
    }

    private static String datasetFor(Normalisation kind, List<ExpectedRecord> expected) {
        for (ExpectedRecord expectation : expected) {
            if (kind.describes(expectation.dataset())) {
                return expectation.dataset();
            }
        }
        return kind.datasets().iterator().next();
    }

    private static Map<String, DatasetInput> seedsFor(List<ExpectedRecord> expected,
                                                     List<Normalisation> declared) {
        Map<String, DatasetInput> seeds = new LinkedHashMap<>();
        for (Normalisation kind : declared) {
            seeds.put(datasetFor(kind, expected),
                    DatasetInput.ofRows(List.of("x".repeat(kind.sourceWidth()))));
        }
        return seeds;
    }

    private static String seeded(String dataset, Normalisation kind, String row) {
        return new DatasetNormalisation(dataset, kind).normaliseSeedRow(row);
    }

    private static ExpectedRecord pinning(String dataset, int rowIndex, Map<String, String> fields) {
        return new ExpectedRecord(dataset, rowIndex, fields, null);
    }

    private static ExpectedRecord pinningImage(String dataset, int rowIndex, String image) {
        return new ExpectedRecord(dataset, rowIndex, Map.of(), image);
    }

    private static Map<String, String> fields(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("fields(...) takes name/value pairs, but was given "
                    + namesAndValues.length + " argument(s)");
        }
        Map<String, String> pinned = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            pinned.put(namesAndValues[index], namesAndValues[index + 1]);
        }
        return pinned;
    }

    private static DatasetOutput output(String dataset, RecordLayout layout, String... rows) {
        return DatasetOutput.ofImages(dataset, layout, List.of(rows), ASCII);
    }

    private static Fingerprint wrote(String dataset, RecordLayout layout, String... rows) {
        return Fingerprint.of(List.of(output(dataset, layout, rows)), List.of(), null, 0, List.of());
    }

    private static Fingerprint wrote(int returnCode, List<String> messages, DatasetOutput... outputs) {
        return Fingerprint.of(List.of(outputs), List.of(), null, returnCode, displayLines(messages));
    }

    private static String accountRow() {
        return fixtureRow(ACCOUNT_FIXTURE, AccountRecord.RECORD_LENGTH);
    }

    private static String xrefRow36() {
        return fixtureRow(XREF_FIXTURE, CardXrefRecord.FILLER_OFFSET);
    }

    private static String xrefRow50() {
        return xrefRow36() + " ".repeat(XREF_FILLER_WIDTH);
    }

    private static String usrsecRow80() {
        return USRSEC_ROW_57 + " ".repeat(USRSEC_FILLER_WIDTH);
    }

    private static String replacing(String row, FieldSpan span, String value) {
        assertThat(value)
                .as("a replacement for %s must be exactly its declared width, or the record's total "
                        + "width would change and a different assertion would fire", span.describe())
                .hasSize(span.length());
        return row.substring(0, span.offset()) + value
                + row.substring(span.offset() + span.length());
    }

    private static String fixtureRow(String resource, int expectedWidth) {
        List<String> rows = fixtureRows(resource);
        assertThat(rows).as("%s must carry at least one row", resource).isNotEmpty();
        assertThat(rows.get(0)).as("row 1 of %s", resource).hasSize(expectedWidth);
        return rows.get(0);
    }

    private static List<String> fixtureRows(String resource) {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     FieldDifferTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n")) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read " + resource, problem);
        }
        return rows;
    }

    private static List<Diff> outputDiffs(DiffResult result) {
        List<Diff> output = new ArrayList<>();
        for (Diff diff : result.entries()) {
            if (diff.kind() != DiffKind.INCOMPLETE_EXPECTATION) {
                output.add(diff);
            }
        }
        return output;
    }

    private static int outputCount(DiffResult result) {
        return outputDiffs(result).size();
    }

    private static boolean outputIsClean(DiffResult result) {
        return outputDiffs(result).isEmpty();
    }

    private static List<DiffKind> allKindsOf(DiffResult result) {
        List<DiffKind> kinds = new ArrayList<>();
        for (Diff diff : result.entries()) {
            kinds.add(diff.kind());
        }
        return kinds;
    }

    private static List<DiffKind> kindsOf(DiffResult result) {
        List<DiffKind> kinds = new ArrayList<>();
        for (Diff diff : outputDiffs(result)) {
            kinds.add(diff.kind());
        }
        return kinds;
    }

    private static Diff onlyDiff(DiffResult result) {
        assertThat(outputDiffs(result)).as("expected exactly one difference: %s", result.render())
                .hasSize(1);
        return outputDiffs(result).get(0);
    }

    @Nested
    @DisplayName("A clean comparison reports a diff count of zero")
    class CleanComparison {
        @Test
        @DisplayName("every field pinned to its stored value gives count 0 and isClean")
        void everyFieldPinnedCorrectlyIsClean() {
            ParityCase parityCase = caseOf(pinning(XREF, 0, fields(
                    XREF_CARD_NUM, "0500024453765740",
                    XREF_CUST_ID, "000000050",
                    XREF_ACCT_ID, "00000000050",
                    FILLER, " ".repeat(XREF_FILLER_WIDTH))));

            DiffResult result =
                    differ.compare(parityCase, wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isZero();
            assertThat(outputIsClean(result)).isTrue();
            assertThat(outputDiffs(result)).isEmpty();
            assertThat(parityCase.normalisations())
                    .as("a full-width row needs no pad, and none is declared")
                    .isEmpty();
            assertThat(result.render()).contains("0 differences", "CLEAN, the gate is satisfied");
        }

        @Test
        @DisplayName("a whole-record image pinned to the stored bytes is clean")
        void wholeRecordImagePinnedCorrectlyIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinningImage(XREF, 0, xrefRow50())),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputIsClean(result)).isTrue();
        }

        @Test
        @DisplayName("the real 300-byte account row is clean, misspelled field name and FILLER included")
        void realAccountRowIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_ID, "00000000001",
                            ACCT_ACTIVE_STATUS, "Y",
                            ACCT_CURR_BAL, "194.00",
                            ACCT_CREDIT_LIMIT, "2020.00",
                            ACCT_OPEN_DATE, "2014-11-20",
                            ACCT_EXPIRAION_DATE, "2025-05-20",
                            FILLER, " ".repeat(AccountRecord.FILLER_LENGTH)))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("a unit that wrote nothing and set return code 0 is clean")
        void aUnitThatWroteNothingIsClean() {
            DiffResult result = differ.compare(caseOf(), Fingerprint.ofReturnCode(0));

            assertThat(outputIsClean(result)).isTrue();
            assertThat(result.program()).isEqualTo(PROGRAM);
            assertThat(result.caseId()).isEqualTo(CASE_ID);
        }
    }

    @Nested
    @DisplayName("Comparison is field by field, never whole strings")
    class FieldGranularity {
        @Test
        @DisplayName("one wrong field yields exactly one diff, located by offset and length")
        void oneWrongFieldYieldsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000051"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(XREF);
            assertThat(diff.rowIndex()).isZero();
            assertThat(diff.fieldName()).isEqualTo(XREF_CUST_ID);
            assertThat(diff.offset()).isEqualTo(CardXrefRecord.XREF_CUST_ID_OFFSET);
            assertThat(diff.length()).isEqualTo(CardXrefRecord.XREF_CUST_ID_LENGTH);
            assertThat(diff.expected())
                    .doesNotContain("000000051")
                    .contains("<identifier>", "len=9", "sha256=");
            assertThat(diff.actual())
                    .doesNotContain("000000050")
                    .contains("<identifier>", "len=9", "sha256=");
            assertThat(diff.expected()).isNotEqualTo(diff.actual());
        }

        @Test
        @DisplayName("three wrong fields yield THREE diffs, one per field - not one for the record")
        void threeWrongFieldsYieldThreeDiffs() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(
                            XREF_CARD_NUM, "9999999999999999",
                            XREF_CUST_ID, "000000051",
                            XREF_ACCT_ID, "00000000051"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isEqualTo(3);
            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID);
            assertThat(outputDiffs(result)).extracting(Diff::kind)
                    .containsOnly(DiffKind.VALUE_MISMATCH);
        }

        @Test
        @DisplayName("diffs come out in COPYBOOK order, whatever order the fixture declares them")
        void diffOrderIsCopybookOrderNotFixtureOrder() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(
                            XREF_ACCT_ID, "00000000051",
                            XREF_CUST_ID, "000000051",
                            XREF_CARD_NUM, "9999999999999999"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID);
        }

        @Test
        @DisplayName("a whole-record expectation still localises each difference to its own span")
        void wholeRecordImageDiffLocalisesToSpans() {
            String wrong = replacing(
                    replacing(xrefRow50(), CardXrefRecord.XREF_CUST_ID, "000000051"),
                    CardXrefRecord.XREF_ACCT_ID, "00000000051");

            DiffResult result = differ.compare(
                    caseOf(pinningImage(XREF, 0, wrong)),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result)).isEqualTo(2);
            assertThat(outputDiffs(result))
                    .extracting(Diff::fieldName)
                    .containsExactly(XREF_CUST_ID, XREF_ACCT_ID);
            assertThat(outputDiffs(result)).extracting(Diff::offset).containsExactly(
                    CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("a field wrong in both fields and expectedBytes counts once, not twice")
        void aFieldPinnedTwiceIsCountedOnce() {
            String wrong = replacing(xrefRow50(), CardXrefRecord.XREF_CUST_ID, "000000051");

            DiffResult result = differ.compare(
                    caseOf(new ExpectedRecord(XREF, 0, fields(XREF_CUST_ID, "000000051"), wrong)),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputCount(result))
                    .as("the diff count must count distinct findings: %s", result.render())
                    .isEqualTo(1);
            assertThat(onlyDiff(result).fieldName()).isEqualTo(XREF_CUST_ID);
        }
    }

    @Nested
    @DisplayName("PIC X comparison is at full declared width and never trims")
    class AlphanumericPadding {
        @Test
        @DisplayName("an expectation short of the declared width IS a difference")
        void anExpectationShortOfTheDeclaredWidthIsADifference() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(SEC_USR_LNAME, "GOLD"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT,
                            seeded(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.actual())
                    .doesNotContain("GOLD")
                    .contains("<personal>", "len=20", "sha256=");
            assertThat(diff.expected()).contains("<personal>", "len=4");
            assertThat(diff.explanation())
                    .contains("differ ONLY in trailing spaces")
                    .contains("space-padded to its declared width");
        }

        @Test
        @DisplayName("the same value written out to the declared width is clean")
        void theSameValueAtFullWidthIsClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(
                                    SEC_USR_ID, "ADMIN001",
                                    SEC_USR_LNAME, "GOLD                "))),
                    wrote(USRSEC, SecUserRecord.LAYOUT,
                            seeded(USRSEC, Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("render makes the invisible visible: trailing spaces counted, length stated")
        void renderCountsTrailingSpacesAndStatesLength() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(AccountRecord.ACCT_GROUP_ID_NAME, "GOLD"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(onlyDiff(result).render())
                    .contains("'GOLD' (len=4)")
                    .contains("10 trailing space(s) (len=10)")
                    .contains("offset " + AccountRecord.ACCT_GROUP_ID_OFFSET)
                    .contains("length " + AccountRecord.ACCT_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("a FILLER span is a comparable field, so a non-space byte in it is reported")
        void aFillerSpanIsComparable() {
            String polluted = replacing(xrefRow50(), CardXrefRecord.FILLER,
                    "X" + " ".repeat(XREF_FILLER_WIDTH - 1));

            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, polluted));

            assertThat(onlyDiff(result).fieldName()).isEqualTo(FILLER);
        }
    }

    @Nested
    @DisplayName("PIC 9 comparison keeps leading zeros significant")
    class NumericZeroFill {
        @Test
        @DisplayName("an unpadded numeric expectation IS a difference, and is named as one")
        void anUnpaddedNumericExpectationIsADifference() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "50"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("unsigned zoned PIC 9")
                    .contains("differ ONLY in leading zeros")
                    .contains("zero-filled on the left for PIC 9");
        }

        @Test
        @DisplayName("the zero-filled image of the same number is clean")
        void theZeroFilledImageIsClean() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputIsClean(result)).isTrue();
        }
    }

    @Nested
    @DisplayName("Signed zoned fields - the stored byte form is authoritative, the decoded number "
            + "explains it")
    class SignedZonedFields {
        @Test
        @DisplayName("the stored image 00000001940{ denotes 194.00 - twelve digits, no sign byte")
        void theStoredOverpunchImageDenotesTheDocumentedValue() {
            assertThat(differ.codec().decodeSignedScaled("00000001940{",
                    CobolDecimal.MONETARY_SCALE))
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_CURR_BAL, "194.00",
                            ACCT_CREDIT_LIMIT, "2020.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("two images of the same value are still a difference: the bytes are the contract")
        void twoImagesOfTheSameValueAreStillADifference() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "000000019400"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("difference in stored FORM, not in quantity")
                    .contains("unsigned zone-F rendering")
                    .contains("194.00");
        }

        @ParameterizedTest(name = "{0} decodes to {1}, canonical={2}")
        @CsvSource({
            "00000001940{, 194.00, true",
            "00000001940A, 194.01, true",
            "000000019400, 194.00, false",
            "00000001940}, -194.00, true",
            "00000001940J, -194.01, true"
        })
        @DisplayName("every overpunch form the fixtures and the codec produce is compared correctly")
        void everyOverpunchFormComparesCorrectly(String image, String value, boolean canonical) {
            String row = replacing(accountRow(), AccountRecord.SPAN_ACCT_CURR_BAL, image);
            Fingerprint fingerprint = wrote(ACCOUNT, AccountRecord.LAYOUT, row);

            assertThat(differ.codec().decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE))
                    .as("%s denotes %s", image, value)
                    .isEqualByComparingTo(new BigDecimal(value));
            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, image))), fingerprint)))
                    .as("pinning the exact bytes %s must always be clean", image)
                    .isTrue();

            DiffResult againstTheLiteral = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, value))), fingerprint);
            if (canonical) {
                assertThat(outputIsClean(againstTheLiteral))
                        .as("%s is the canonical image of %s", image, value)
                        .isTrue();
            } else {
                assertThat(onlyDiff(againstTheLiteral).explanation())
                        .as("%s denotes %s but is not the image a COBOL store writes", image, value)
                        .contains("unsigned zone-F rendering");
            }

            BigDecimal offByAPenny = new BigDecimal(value).add(new BigDecimal("0.01"));
            assertThat(outputCount(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, offByAPenny.toPlainString()))),
                    fingerprint)))
                    .as("%s must NOT compare equal to %s", image, offByAPenny)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a value wrong by a factor of ten is reported, and the message names both sides")
        void aFactorOfTenIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "1940.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.explanation())
                    .contains("differs numerically: expected 1940.00, decoded 194.00")
                    .contains("at scale " + CobolDecimal.MONETARY_SCALE)
                    .contains("with rounding " + RoundingMode.DOWN)
                    .contains("truncation toward zero, because ROUNDED appears zero times");
        }

        @Test
        @DisplayName("the overpunch byte is named in the failure text, with its code point")
        void theOverpunchByteIsMadeVisible() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "1940.00"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            assertThat(onlyDiff(result).explanation())
                    .contains("The trailing byte is '{' (0x7B)")
                    .contains("POSITIVE sign overpunch");
        }

        @Test
        @DisplayName("an undecodable field is REPORTED, not thrown - one bad byte cannot hide the rest")
        void anUndecodableFieldIsReportedNotThrown() {
            String row = replacing(accountRow(), AccountRecord.SPAN_ACCT_CURR_BAL, "00000001940*");

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_CURR_BAL, "194.00",
                            ACCT_EXPIRAION_DATE, "1999-01-01"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, row));

            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.UNDECODABLE_FIELD, DiffKind.VALUE_MISMATCH);
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("not a valid signed zoned DISPLAY image")
                    .contains("neither a digit nor a recognised sign overpunch");
        }

        @Test
        @DisplayName("an expectation that is neither an image nor a literal is REPORTED, not thrown")
        void aMalformedExpectationIsReportedNotThrown() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_CURR_BAL, "no-such-value"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MALFORMED_EXPECTATION);
            assertThat(diff.explanation())
                    .contains("neither a zoned image")
                    .contains("nor a decimal literal")
                    .contains("denotes 194.00");
        }

        @Test
        @DisplayName("no monetary comparison uses a half-rounding or a directional mode")
        void theRoundingPolicyIsTruncation() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Total record width is checked before any field of the record is compared")
    class RecordWidthAndFiller {
        @Test
        @DisplayName("a record short by its trailing FILLER is reported as RECORD_WIDTH_MISMATCH")
        void anOmittedFillerIsAWidthMismatch() {
            String withoutFiller = accountRow().substring(0, AccountRecord.FILLER_OFFSET);

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_ID, "00000000001"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, withoutFiller));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(diff.fieldName()).isEqualTo(RECORD_SCOPE_FIELD);
            assertThat(diff.offset()).isZero();
            assertThat(diff.length()).isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(diff.expected()).isEqualTo(String.valueOf(AccountRecord.RECORD_LENGTH));
            assertThat(diff.actual()).isEqualTo(String.valueOf(AccountRecord.FILLER_OFFSET));
            assertThat(diff.explanation())
                    .contains("a FILLER was omitted, which shifts every offset after it")
                    .contains("The case declares no normalisation")
                    .contains("must not be padded away");
        }

        @Test
        @DisplayName("a width mismatch skips the field pass, so it is ONE finding and not a cascade")
        void aWidthMismatchSkipsTheFieldPass() {
            String withoutFiller = accountRow().substring(0, AccountRecord.FILLER_OFFSET);

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(
                            ACCT_ID, "99999999999",
                            ACCT_ACTIVE_STATUS, "N",
                            ACCT_OPEN_DATE, "1999-01-01"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, withoutFiller));

            assertThat(kindsOf(result)).containsExactly(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("Field comparison is skipped for this record");
        }

        @Test
        @DisplayName("an expected image of the wrong width is reported against the expectation")
        void anExpectedImageOfTheWrongWidthIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinningImage(ACCOUNT, 0,
                            accountRow().substring(0, AccountRecord.FILLER_OFFSET))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThat(diff.explanation())
                    .contains("the expected record image is 122 byte(s) wide")
                    .contains("The expectation itself is the wrong width here");
        }

        @Test
        @DisplayName("a record one byte too WIDE is reported too, never quietly truncated")
        void anOverWideRecordIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50() + " "));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @ParameterizedTest(name = "{0} declares {1} bytes")
        @CsvSource({
            "CardXref, 50",
            "SecUser, 80",
            "Account, 300"
        })
        @DisplayName("the declared widths the differ enforces are the copybooks' own")
        void declaredWidthsAreTheCopybooksOwn(String record, int width) {
            Map<String, RecordLayout> layouts = new LinkedHashMap<>();
            layouts.put("CardXref", CardXrefRecord.LAYOUT);
            layouts.put("SecUser", SecUserRecord.LAYOUT);
            layouts.put("Account", AccountRecord.LAYOUT);

            assertThat(layouts.get(record).recordLength()).isEqualTo(width);
        }
    }

    @Nested
    @DisplayName("Exactly two width normalisations exist, and this class owns both")
    class TheTwoNormalisations {
        @Test
        @DisplayName("cardxref 36 to 50: with the normalisation declared the compare is clean")
        void cardxrefPadMakesTheCompareClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50),
                            pinning(XREF, 0, fields(
                                    XREF_CARD_NUM, "0500024453765740",
                                    XREF_CUST_ID, "000000050",
                                    XREF_ACCT_ID, "00000000050",
                                    FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, seeded(XREF,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50, xrefRow36())));

            assertThat(outputCount(result)).as(result.render()).isZero();
        }

        @Test
        @DisplayName("cardxref 36 to 50: without it the very same row is a width mismatch")
        void cardxrefRowWithoutTheNormalisationIsAWidthMismatch() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow36()));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("USRSEC 57 to 80: with the normalisation declared the compare is clean")
        void usrsecPadMakesTheCompareClean() {
            DiffResult result = differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0, fields(
                                    SEC_USR_ID, "ADMIN001",
                                    SEC_USR_LNAME, "GOLD                ",
                                    SEC_USR_FILLER, " ".repeat(USRSEC_FILLER_WIDTH)))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, seeded(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)));

            assertThat(outputCount(result)).as(result.render()).isZero();
            assertThat(usrsecRow80()).hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("USRSEC 57 to 80: without it the very same row is a width mismatch")
        void usrsecRowWithoutTheNormalisationIsAWidthMismatch() {
            DiffResult result = differ.compare(
                    caseOf(pinning(USRSEC, 0, fields(SEC_USR_ID, "ADMIN001"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW_57));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("a normalisation fires ONLY for its own width pair, never as a general escape hatch")
        void aNormalisationFiresOnlyForItsOwnWidthPair() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetNormalisation(USRSEC,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                    .withMessageContaining("does not describe");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Normalisation.CARDXREF_FILLER_PAD_36_TO_50
                            .normaliseSeedRow(USRSEC_ROW_57, XREF))
                    .withMessageContaining("cannot be normalised")
                    .withMessageContaining("pads 36 to 50");

            DiffResult result = differ.compare(
                    caseOf(pinning(USRSEC, 0, fields(SEC_USR_ID, "ADMIN001"))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, USRSEC_ROW_57));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("a pad that fires announces itself, naming widths, copybook and absent span")
        void aPadThatFiresAnnouncesItself() {
            ParityCase parityCase = caseOf(List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50),
                    pinning(XREF, 0, fields(XREF_CUST_ID, "000000050")));

            DiffResult result = differ.compare(parityCase,
                    wrote(XREF, CardXrefRecord.LAYOUT, seeded(XREF,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50, xrefRow36())));

            assertThat(outputIsClean(result)).isTrue();

            assertThat(parityCase.normalisations()).singleElement().satisfies(declared -> {
                assertThat(declared.dataset()).isEqualTo(XREF);
                assertThat(declared.kind()).isEqualTo(Normalisation.CARDXREF_FILLER_PAD_36_TO_50);
                assertThat(declared.kind().sourceWidth()).isEqualTo(36);
                assertThat(declared.kind().targetWidth()).isEqualTo(50);
                assertThat(declared.kind().padWidth()).isEqualTo(14);
                assertThat(declared.kind().copybook()).isEqualTo("CVACT03Y");
                assertThat(declared.kind().absentSpan()).isEqualTo("FILLER PIC X(14)");
            });
        }

        @Test
        @DisplayName("fifty padded rows need ONE declaration, not fifty")
        void repeatedPaddingNeedsOneDeclaration() {
            List<ExpectedRecord> expectations = new ArrayList<>();
            for (int row = 0; row < 3; row++) {
                expectations.add(pinning(XREF, row, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH))));
            }
            ParityCase parityCase = caseOf(expectations, 0, List.of(),
                    List.of(Normalisation.CARDXREF_FILLER_PAD_36_TO_50));
            DatasetNormalisation declared = parityCase.normalisations().get(0);

            List<String> seededRows = declared.normaliseSeedRows(
                    List.of(xrefRow36(), xrefRow36(), xrefRow50()));

            DiffResult result = differ.compare(parityCase,
                    wrote(XREF, CardXrefRecord.LAYOUT, seededRows.get(0), seededRows.get(1),
                            seededRows.get(2)));

            assertThat(outputIsClean(result)).isTrue();
            assertThat(parityCase.normalisations()).hasSize(1);
            assertThat(seededRows).allSatisfy(row -> assertThat(row).hasSize(50));
        }

        @Test
        @DisplayName("only two normalisations exist, and each carries its own evidence")
        void onlyTwoNormalisationsExist() {
            assertThat(Normalisation.values()).hasSize(2);

            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth()).isEqualTo(36);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.targetWidth()).isEqualTo(50);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.padWidth())
                    .isEqualTo(XREF_FILLER_WIDTH);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.copybook()).isEqualTo("CVACT03Y");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.absentSpan())
                    .isEqualTo("FILLER PIC X(14)");

            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth()).isEqualTo(80);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.padWidth())
                    .isEqualTo(USRSEC_FILLER_WIDTH);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.copybook()).isEqualTo("CSUSR01Y");
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.absentSpan())
                    .isEqualTo("SEC-USR-FILLER PIC X(23)");
        }

        @ParameterizedTest(name = "{0} rows measure {1} bytes")
        @CsvSource({
            "fixtures/acctdata.txt, 300",
            "fixtures/carddata.txt, 150",
            "fixtures/cardxref.txt, 36",
            "fixtures/custdata.txt, 500",
            "fixtures/dailytran.txt, 350",
            "fixtures/discgrp.txt, 50",
            "fixtures/tcatbal.txt, 50",
            "fixtures/trancatg.txt, 60",
            "fixtures/trantype.txt, 60"
        })
        @DisplayName("cardxref is the ONLY fixture that deviates, so no third normalisation is needed")
        void cardxrefIsTheOnlyDeviatingFixture(String resource, int width) {
            assertThat(fixtureRows(resource))
                    .as("every row of %s", resource)
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row).hasSize(width));
        }
    }

    @Nested
    @DisplayName("The RETURN-CODE is compared, because it is the batch exit status")
    class ReturnCode {
        @Test
        @DisplayName("a differing return code is reported against its own pseudo-scope")
        void aDifferingReturnCodeIsReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 8, List.of(), List.of()), Fingerprint.ofReturnCode(0));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.RETURN_CODE_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(RETURN_CODE_SCOPE);
            assertThat(diff.fieldName()).isEqualTo("RETURN-CODE");
            assertThat(diff.rowIndex()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.offset()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.length()).isEqualTo(Diff.NOT_APPLICABLE);
            assertThat(diff.expected()).isEqualTo("8");
            assertThat(diff.actual()).isEqualTo("0");
            assertThat(diff.explanation())
                    .contains("the case expects RETURN-CODE 8 but the unit reported 0")
                    .contains(FileStatus.APPL_EOF + " (end of file)")
                    .contains("JCL COND gating");
        }

        @ParameterizedTest(name = "return code {0}")
        @ValueSource(ints = {0, 3, 4, 8, 12, 16})
        @DisplayName("every return code this migration produces compares equal to itself")
        void everyProducedReturnCodeComparesEqualToItself(int returnCode) {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(), returnCode, List.of(), List.of()),
                    Fingerprint.ofReturnCode(returnCode)))).isTrue();

            int different = returnCode == 0 ? 8 : 0;
            assertThat(outputCount(differ.compare(
                    caseOf(List.of(), returnCode, List.of(), List.of()),
                    Fingerprint.ofReturnCode(different)))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Emitted lines are compared positionally and byte-exactly")
    class EmittedMessages {
        @Test
        @DisplayName("a differing count is reported when the shared prefix matches")
        void aDifferingCountIsReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("FIRST")));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MESSAGE_COUNT_MISMATCH);
            assertThat(diff.dataset()).isEqualTo(MESSAGES_SCOPE);
            assertThat(diff.explanation())
                    .contains("expects 2 emitted line(s) but the unit emitted 1");
        }

        @Test
        @DisplayName("a differing line is reported at its own position")
        void aDifferingLineIsReportedAtItsPosition() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("FIRST", "OTHER")));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MESSAGE_MISMATCH);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.expected()).isEqualTo("SECOND");
            assertThat(diff.actual()).isEqualTo("OTHER");
        }

        @Test
        @DisplayName("count and position are both reported: a reviewer needs both")
        void countAndPositionAreBothReported() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("OTHER")));

            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.MESSAGE_COUNT_MISMATCH, DiffKind.MESSAGE_MISMATCH);
        }

        @Test
        @DisplayName("two file-status lines get a hint narrowing the difference to the status image")
        void fileStatusLinesGetATargetedHint() {
            assertThat(FileStatus.toDisplayLine(FileStatus.OK)).isEqualTo("FILE STATUS IS: NNNN0000");

            DiffResult result = differ.compare(
                    caseOf(List.of(), 0, List.of(FileStatus.toDisplayLine(FileStatus.NOT_FOUND)),
                            List.of()),
                    wrote(0, List.of(FileStatus.toDisplayLine(FileStatus.OK))));

            assertThat(onlyDiff(result).explanation())
                    .contains("Both lines are file-status DISPLAY lines")
                    .contains(FileStatus.DISPLAY_PREFIX)
                    .contains("IO-STATUS-04 image differs: expected '0023', observed '0000'")
                    .contains("Emit these lines through FileStatus.toDisplayLine");
        }

        @Test
        @DisplayName("an empty string is a legitimate expected line - COBOL DISPLAY emits one")
        void anEmptyLineIsALegitimateExpectation() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(), 0, List.of(""), List.of()),
                    wrote(0, List.of(""))))).isTrue();

            assertThat(outputCount(differ.compare(
                    caseOf(List.of(), 0, List.of(""), List.of()),
                    wrote(0, List.of(" "))))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("A record that is absent, and a record that should not be there, both count")
    class MissingAndExtraRecords {
        @Test
        @DisplayName("an expectation against a dataset the unit never wrote is one diff")
        void anUnwrittenDatasetIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_ID, "00000000001"))),
                    Fingerprint.ofReturnCode(0));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.MISSING_RECORD);
            assertThat(diff.fieldName()).isEqualTo(RECORD_SCOPE_FIELD);
            assertThat(diff.actual()).isNull();
            assertThat(diff.explanation())
                    .contains("wrote no record at all to dataset " + ACCOUNT)
                    .contains("no output dataset at all");
        }

        @Test
        @DisplayName("an expectation past the last written row is a missing record, and the row that "
                + "IS there was never expected")
        void aRowPastTheEndIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 1, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(kindsOf(result))
                    .containsExactly(DiffKind.MISSING_RECORD, DiffKind.EXTRA_RECORD);
            Diff diff = outputDiffs(result).get(0);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.explanation())
                    .contains("holds 1 row(s), so there is no row at 0-based index 1");
            assertThat(outputDiffs(result).get(1).rowIndex()).isZero();
        }

        @Test
        @DisplayName("an expectation against an opened-but-unwritten dataset is one diff")
        void anEmptyDatasetIsOneDiff() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(0, List.of(), DatasetOutput.empty(XREF, CardXrefRecord.LAYOUT)));

            assertThat(onlyDiff(result).kind()).isEqualTo(DiffKind.MISSING_RECORD);
        }

        @Test
        @DisplayName("a missing record is ONE diff, not one per field it would have carried")
        void aMissingRecordIsOneDiffNotOnePerField() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 1, fields(
                            XREF_CARD_NUM, "0500024453765740",
                            XREF_CUST_ID, "000000050",
                            XREF_ACCT_ID, "00000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(outputDiffs(result))
                    .filteredOn(diff -> diff.kind() == DiffKind.MISSING_RECORD)
                    .singleElement()
                    .satisfies(diff -> assertThat(diff.expected())
                            .as("the expected side names the fields the record would have carried")
                            .contains(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID));
        }

        @Test
        @DisplayName("one row too many is a parity failure in its own right")
        void oneRowTooManyIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50()));

            Diff diff = onlyDiff(result);
            assertThat(diff.kind()).isEqualTo(DiffKind.EXTRA_RECORD);
            assertThat(diff.rowIndex()).isEqualTo(1);
            assertThat(diff.expected()).isNull();
            assertThat(diff.explanation())
                    .contains("wrote 2 row(s) to dataset " + XREF + " but the case expects 1")
                    .contains("Extra output is a parity failure in its own right");
        }

        @Test
        @DisplayName("every extra row is reported, in ascending row order")
        void everyExtraRowIsReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50(), xrefRow50()));

            assertThat(outputDiffs(result)).extracting(Diff::rowIndex).containsExactly(1, 2);
            assertThat(outputDiffs(result)).extracting(Diff::kind)
                    .containsOnly(DiffKind.EXTRA_RECORD);
        }

        @Test
        @DisplayName("a dataset the case says nothing about is reported: silence is not permission")
        void rowsInAnUnaddressedDatasetAreReported() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields(XREF_CUST_ID, "000000050"))),
                    wrote(0, List.of(),
                            output(XREF, CardXrefRecord.LAYOUT, xrefRow50()),
                            output(ACCOUNT, AccountRecord.LAYOUT, accountRow(), accountRow())));

            assertThat(outputIsClean(result)).isFalse();
            assertThat(outputDiffs(result)).singleElement().satisfies(diff -> {
                assertThat(diff.kind()).isEqualTo(DiffKind.EXTRA_DATASET);
                assertThat(diff.dataset()).isEqualTo(ACCOUNT);
                assertThat(diff.actual()).isEqualTo("2 row(s)");
            });
        }
    }

    @Nested
    @DisplayName("Field names are the copybook's own, and an unknown one fails loudly")
    class FieldNaming {
        @Test
        @DisplayName("the misspelled ACCT-EXPIRAION-DATE is the contract; the corrected spelling is not")
        void theMisspelledNameIsTheContract() {
            assertThat(ACCT_EXPIRAION_DATE)
                    .as("app/cpy/CVACT01Y.cpy misspells it, and the model preserves the misspelling")
                    .isEqualTo(AccountRecord.ACCT_EXPIRAION_DATE_NAME);

            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields(ACCT_EXPIRAION_DATE, "2025-05-20"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow())))).isTrue();

            DiffResult corrected = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, fields("ACCT-EXPIRATION-DATE", "2025-05-20"))),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            Diff diff = onlyDiff(corrected);
            assertThat(diff.kind()).isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
            assertThat(diff.explanation())
                    .contains("declares no such span")
                    .contains("ACCT-EXPIRAION-DATE is spelled that way on purpose")
                    .contains("Addressable here:");
        }

        @Test
        @DisplayName("an unknown field name lists every addressable name, so the fix takes seconds")
        void anUnknownFieldNameListsTheAddressableNames() {
            DiffResult result = differ.compare(
                    caseOf(pinning(XREF, 0, fields("XREF-CARDNUM", "0500024453765740"))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(onlyDiff(result).explanation())
                    .contains(XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID, FILLER);
        }

        @Test
        @DisplayName("the first FILLER is addressed as FILLER, never as FILLER-1")
        void theFirstFillerIsAddressedWithoutAnOrdinal() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(pinning(XREF, 0, fields(FILLER, " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50())))).isTrue();

            DiffResult withOrdinal = differ.compare(
                    caseOf(pinning(XREF, 0, fields("FILLER-1", " ".repeat(XREF_FILLER_WIDTH)))),
                    wrote(XREF, CardXrefRecord.LAYOUT, xrefRow50()));

            assertThat(onlyDiff(withOrdinal).kind())
                    .as("one name, one spelling: an alias would be a silently tolerated ambiguity")
                    .isEqualTo(DiffKind.FIELD_ABSENT_IN_FINGERPRINT);
        }

        @Test
        @DisplayName("a copybook-named filler keeps its own name, as SEC-USR-FILLER does")
        void aNamedFillerKeepsItsName() {
            assertThat(outputIsClean(differ.compare(
                    caseOf(List.of(Normalisation.USRSEC_FILLER_PAD_57_TO_80),
                            pinning(USRSEC, 0,
                                    fields(SEC_USR_FILLER, " ".repeat(USRSEC_FILLER_WIDTH)))),
                    wrote(USRSEC, SecUserRecord.LAYOUT, seeded(USRSEC,
                            Normalisation.USRSEC_FILLER_PAD_57_TO_80, USRSEC_ROW_57)))))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("The report is deterministic, ordered and never truncated")
    class DeterminismAndCompleteness {
        @Test
        @DisplayName("the same comparison run twice produces identical ordered output")
        void theSameComparisonTwiceIsIdentical() {
            ParityCase parityCase = caseOf(List.of(
                            pinning(XREF, 0, fields(
                                    XREF_CARD_NUM, "9999999999999999",
                                    XREF_CUST_ID, "000000051",
                                    XREF_ACCT_ID, "00000000051"))),
                    8, List.of("FIRST", "SECOND"), List.of());
            Fingerprint fingerprint = wrote(0, List.of("OTHER"),
                    output(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50()));

            DiffResult first = differ.compare(parityCase, fingerprint);
            DiffResult second = differ.compare(parityCase, fingerprint);

            assertThat(outputCount(second)).isEqualTo(outputCount(first));
            assertThat(second.render()).isEqualTo(first.render());
            assertThat(kindsOf(second)).isEqualTo(kindsOf(first));
        }

        @Test
        @DisplayName("traversal is records, then extra rows, then the return code, then the messages")
        void traversalOrderIsFullyDetermined() {
            DiffResult result = differ.compare(
                    caseOf(List.of(pinning(XREF, 0, fields(XREF_CUST_ID, "000000051"))),
                            8, List.of("FIRST", "SECOND"), List.of()),
                    wrote(0, List.of("OTHER"),
                            output(XREF, CardXrefRecord.LAYOUT, xrefRow50(), xrefRow50())));

            assertThat(kindsOf(result)).containsExactly(
                    DiffKind.VALUE_MISMATCH,
                    DiffKind.EXTRA_RECORD,
                    DiffKind.RETURN_CODE_MISMATCH,
                    DiffKind.MESSAGE_COUNT_MISMATCH,
                    DiffKind.MESSAGE_MISMATCH);
        }

        @Test
        @DisplayName("every difference is rendered - the list is never capped or summarised")
        void everyDifferenceIsRendered() {
            Map<String, String> everySpanWrong = new LinkedHashMap<>();
            for (FieldSpan span : AccountRecord.LAYOUT.spans()) {
                everySpanWrong.put(span.name(),
                        span.kind() == PictureKind.SIGNED_SCALED ? "0.01" : "?");
            }

            DiffResult result = differ.compare(
                    caseOf(pinning(ACCOUNT, 0, everySpanWrong)),
                    wrote(ACCOUNT, AccountRecord.LAYOUT, accountRow()));

            int spans = AccountRecord.LAYOUT.spans().size();
            assertThat(outputCount(result)).isEqualTo(spans);
            assertThat(result.render())
                    .contains(spans + " differences")
                    .contains("[1/" + spans + "]")
                    .contains("[" + spans + "/" + spans + "]")
                    .contains("the gate requires a diff count of 0");
        }

        @Test
        @DisplayName("a result is immutable once produced, so two tests cannot interfere")
        void aResultIsImmutable() {
            DiffResult result = differ.compare(
                    caseOf(List.of(), 8, List.of(), List.of()), Fingerprint.ofReturnCode(0));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.entries().add(null));
        }

        @Test
        @DisplayName("a result names its program and case, so a failure is traceable from the header")
        void aResultNamesItsProgramAndCase() {
            DiffResult result = differ.compare(caseOf(), Fingerprint.ofReturnCode(0));

            assertThat(result.program()).isEqualTo(PROGRAM);
            assertThat(result.caseId()).isEqualTo(CASE_ID);
            assertThat(result.render()).startsWith("Parity comparison " + PROGRAM + "/" + CASE_ID);
            assertThat(result.toString()).isEqualTo(result.render());
        }

        @Test
        @DisplayName("no diff kind is advisory: every kind the differ can report counts")
        void everyDiffKindCounts() {
            assertThat(DiffKind.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                    "VALUE_MISMATCH",
                    "MISSING_RECORD",
                    "EXTRA_RECORD",
                    "EXTRA_DATASET",
                    "RECORD_WIDTH_MISMATCH",
                    "FIELD_ABSENT_IN_FINGERPRINT",
                    "UNDECODABLE_FIELD",
                    "MALFORMED_EXPECTATION",
                    "RESPONSE_MISMATCH",
                    "SEND_COUNT_MISMATCH",
                    "RETURN_CODE_MISMATCH",
                    "MESSAGE_MISMATCH",
                    "MESSAGE_CHANNEL_MISMATCH",
                    "MESSAGE_COUNT_MISMATCH",
                    "INCOMPLETE_EXPECTATION",
                    "MISSING_DATASET",
                    "DATASET_ROW_COUNT_MISMATCH",
                    "DATASET_WIDTH_MISMATCH");
        }
    }

    @Nested
    @DisplayName("The differ is constructed over an explicit codec and an explicit charset")
    class ConstructionContract {
        @Test
        @DisplayName("a null charset is rejected: an encoding is never derived from the platform")
        void aNullCharsetIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> FieldDiffer.forCharset(null));
        }

        @Test
        @DisplayName("a null codec is rejected")
        void aNullCodecIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> new FieldDiffer(null));
        }

        @Test
        @DisplayName("the codec supplied at construction is the one reported and used")
        void theSuppliedCodecIsTheOneReported() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThat(new FieldDiffer(codec).codec()).isSameAs(codec);
            assertThat(differ.codec().charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("neither argument to compare may be null - use ofReturnCode for a silent unit")
        void compareRejectsNullArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> differ.compare(null, Fingerprint.ofReturnCode(0)));
            assertThatNullPointerException()
                    .isThrownBy(() -> differ.compare(caseOf(), null));
        }
    }
}
