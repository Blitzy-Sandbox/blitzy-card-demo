package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The deterministic judge of this migration: it compares one {@link ParityCase}'s expectations against one
 * observed {@link Fingerprint} field by field and reports how many differences it found.
 */
public final class FieldDiffer {
    private static final String FILLER_NAME = "FILLER";

    private static final String FILLER_ORDINAL_SEPARATOR = "-";

    private static final String RETURN_CODE_SCOPE = "<return-code>";

    private static final String MESSAGES_SCOPE = "<messages>";

    private static final String RESPONSE_SCOPE = "<response>";

    private static final String RECORD_SCOPE_FIELD = "<record>";

    private static final String DATASET_SCOPE_FIELD = "<dataset>";

    private static final String RETURN_CODE_FIELD = "RETURN-CODE";

    private static final String MESSAGE_FIELD = "DISPLAY";

    private static final String SEND_COUNT_FIELD = "SEND-MAP-COUNT";

    private static final String WRITES_CHANNEL = "writes";

    private static final String FINAL_STATE_CHANNEL = "final state";

    private final FixedWidthCodec codec;

    public FieldDiffer(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: the differ decodes "
            + "every record through it so that field offsets stay reviewable against the copybook, "
            + "and it is also where the code page comes from");
    }

    /**
     * Builds a differ over a named charset - the convenient form for a test, which knows its fixtures are
     * {@code US-ASCII} and says so rather than relying on a platform default.
     *
     * @param charset the code page of the data being compared; never {@code null}
     * @return a differ whose codec uses {@code charset}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static FieldDiffer forCharset(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required and is never defaulted: the ASCII "
            + "fixtures are US-ASCII and the EBCDIC datasets are IBM037, and which one applies is "
            + "the caller's knowledge, not this class's");
        return new FieldDiffer(new FixedWidthCodec(charset));
    }

    public FixedWidthCodec codec() {
        return codec;
    }

    public DiffResult compare(ParityCase parityCase, Fingerprint fingerprint) {
        Objects.requireNonNull(parityCase, "A ParityCase is required: it is the authoritative "
            + "expectation side of the comparison");
        Objects.requireNonNull(fingerprint, "A Fingerprint is required: it is what the unit under "
            + "test actually produced. A unit that writes nothing still reports its return code, so "
            + "use Fingerprint.ofReturnCode(int) rather than passing null");

        List<Diff> diffs = new ArrayList<>();

        compareChannel(parityCase.expectedWrites(), fingerprint.writes(), WRITES_CHANNEL,
            parityCase.normalisations(), datasetExpectationsOn(parityCase, DatasetChannel.WRITES),
            diffs);
        compareChannel(parityCase.expectedFinalState(), fingerprint.finalState(),
            FINAL_STATE_CHANNEL, parityCase.normalisations(),
            datasetExpectationsOn(parityCase, DatasetChannel.FINAL_STATE), diffs);
        compareResponse(parityCase.expectedResponse(), fingerprint.response(), diffs);
        compareReturnCode(parityCase, fingerprint, diffs);
        compareMessages(parityCase, fingerprint, diffs);

        return new DiffResult(parityCase.program(), parityCase.caseId(), diffs);
    }

    private void compareChannel(List<ExpectedRecord> expectations,
                                Map<String, DatasetOutput> observed,
                                String channel,
                                List<ParityCase.DatasetNormalisation> normalisations,
                                List<ExpectedDataset> datasetExpectations,
                                List<Diff> diffs) {
        Map<String, Set<Integer>> expectedRows = new LinkedHashMap<>();

        for (ExpectedDataset expectation : datasetExpectations) {
            expectedRows.computeIfAbsent(expectation.dataset(), key -> new LinkedHashSet<>());
        }

        for (ExpectedRecord expectation : expectations) {
            expectedRows.computeIfAbsent(expectation.dataset(), key -> new LinkedHashSet<>())
                .add(expectation.rowIndex());
            compareExpectedRecord(observed, expectation, channel, normalisations, diffs);
        }

        compareDatasetExpectations(datasetExpectations, observed, channel, diffs);
        reportUnexpectedOutput(observed, expectedRows, channel, diffs);
    }

    private static void compareDatasetExpectations(List<ExpectedDataset> datasetExpectations,
                                                   Map<String, DatasetOutput> observed,
                                                   String channel,
                                                   List<Diff> diffs) {
        for (ExpectedDataset expectation : datasetExpectations) {
            String dataset = expectation.dataset();
            DatasetOutput output = observed.get(dataset);
            if (output == null) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                    expectation.rowCount() + " row(s)", null, DiffKind.MISSING_DATASET,
                    "the case expects dataset " + dataset + " on the " + channel + " channel with "
                        + expectation.rowCount() + " row(s), and the fingerprint carries "
                        + describeDatasetKeys(observed) + ". A dataset-level expectation asserts that "
                        + "the dataset itself was opened or created, which is the one statement no row "
                        + "expectation can make: a dataset holding no row has no row to address. Report "
                        + "the dataset through the recorder - openedWithoutWriting for an output that "
                        + "was created and never written to - rather than leaving it out of the "
                        + "fingerprint."));
                continue;
            }
            if (output.rowCount() != expectation.rowCount()) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                    expectation.rowCount() + " row(s)",
                    output.rowCount() + " row(s)", DiffKind.DATASET_ROW_COUNT_MISMATCH,
                    "dataset " + dataset + " holds " + output.rowCount() + " row(s) on the " + channel
                        + " channel where the case expects exactly " + expectation.rowCount()
                        + ". Counted once for the dataset: this is a different finding from a row whose "
                        + "bytes are wrong, and an expected count of zero is the assertion that the "
                        + "dataset exists and produced nothing."));
            }
            Integer expectedWidth = expectation.recordLength();
            if (expectedWidth != null && output.layout().recordLength() != expectedWidth) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, expectedWidth, Integer.toString(expectedWidth),
                    Integer.toString(output.layout().recordLength()),
                    DiffKind.DATASET_WIDTH_MISMATCH,
                    "dataset " + dataset + " was reported on the " + channel + " channel with a "
                        + output.layout().recordLength() + "-byte layout where the case expects "
                        + expectedWidth + ". The dataset's declared width is the only width an empty "
                        + "dataset has, so pinning it is how a case pins the identity of an output it "
                        + "expects to be empty - a 430-byte reject file and a 133-byte report are not "
                        + "interchangeable just because both are empty."));
            }
        }
    }

    private static List<ExpectedDataset> datasetExpectationsOn(ParityCase parityCase,
                                                               DatasetChannel channel) {
        List<ExpectedDataset> selected = new ArrayList<>();
        for (ExpectedDataset expectation : parityCase.expectedDatasets()) {
            if (expectation.channel() == channel) {
                selected.add(expectation);
            }
        }
        return selected;
    }

    private void compareExpectedRecord(Map<String, DatasetOutput> observed,
                                       ExpectedRecord expectation,
                                       String channel,
                                       List<ParityCase.DatasetNormalisation> normalisations,
                                       List<Diff> diffs) {
        String dataset = expectation.dataset();
        int rowIndex = expectation.rowIndex();

        DatasetOutput output = observed.get(dataset);
        if (output == null) {
            diffs.add(missingRecord(dataset, rowIndex, expectation, null, channel,
                "the unit wrote no record at all to dataset " + dataset + " on the " + channel
                    + " channel: the fingerprint carries " + describeDatasetKeys(observed)));
            return;
        }

        if (!output.hasRow(rowIndex)) {
            diffs.add(missingRecord(dataset, rowIndex, expectation, output.layout(), channel,
                "dataset " + dataset + " holds " + output.rowCount() + " row(s), so there is no row "
                    + "at 0-based index " + rowIndex));
            return;
        }

        RecordLayout layout = output.layout();
        byte[] observedRow = output.row(rowIndex);
        if (observedRow.length != layout.recordLength()) {
            diffs.add(widthMismatch(dataset, rowIndex, layout, observedRow.length, channel,
                "the observed record is " + observedRow.length + " byte(s) wide but "
                    + describeLayout(layout) + " declares " + layout.recordLength()
                    + ". A record short by exactly one span almost always means a FILLER was "
                    + "omitted, which shifts every offset after it - and a FILLER the copybook "
                    + "declares must not be padded away, because its bytes are part of the record "
                    + "the COBOL writes. Note that nothing is padded at comparison time: "
                    + describeNormalisations(normalisations, dataset)
                    + ", and the two recorded fixture-to-copybook deviations are repaired once at "
                    + "SEED time by ParityCase.Normalisation, so a width disagreement here is a "
                    + "genuine difference and not a missing declaration."));
            return;
        }

        FixedWidthRecord record = codec.wrap(observedRow, layout);
        Map<String, FieldSpan> addressable = addressableSpans(layout);

        Set<String> comparedFields =
            compareNamedFields(expectation, layout, addressable, record, channel, diffs);
        compareRecordImage(expectation, layout, addressable, record, comparedFields, channel, diffs);
        requireCompleteCoverage(expectation, layout, addressable, comparedFields, channel, diffs);
    }

    private static void requireCompleteCoverage(ExpectedRecord expectation,
                                                RecordLayout layout,
                                                Map<String, FieldSpan> addressable,
                                                Set<String> comparedFields,
                                                String channel,
                                                List<Diff> diffs) {
        if (expectation.expectedBytes() != null) {
            return;
        }
        boolean[] covered = new boolean[layout.recordLength()];
        for (String fieldName : comparedFields) {
            FieldSpan span = addressable.get(fieldName);
            if (span == null) {
                continue;
            }
            int end = Math.min(span.offset() + span.length(), covered.length);
            for (int index = span.offset(); index < end; index++) {
                covered[index] = true;
            }
        }

        List<String> uncovered = new ArrayList<>();
        int uncoveredBytes = 0;
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            FieldSpan span = entry.getValue();
            int missing = 0;
            int end = Math.min(span.offset() + span.length(), covered.length);
            for (int index = span.offset(); index < end; index++) {
                if (!covered[index]) {
                    missing++;
                }
            }
            if (missing > 0) {
                uncovered.add(entry.getKey() + " (offset " + span.offset() + ", length "
                    + span.length() + ')');
                uncoveredBytes += missing;
            }
        }
        if (uncovered.isEmpty()) {
            return;
        }

        diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), RECORD_SCOPE_FIELD, 0,
            layout.recordLength(), layout.recordLength() + " byte(s) accounted for",
            (layout.recordLength() - uncoveredBytes) + " byte(s) accounted for",
            DiffKind.INCOMPLETE_EXPECTATION,
            "the " + channel + " expectation accounts for only "
                + (layout.recordLength() - uncoveredBytes) + " of " + describeLayout(layout)
                + "'s " + layout.recordLength() + " byte(s), leaving " + uncoveredBytes
                + " unasserted in: " + String.join(", ", uncovered)
                + ". A byte no expectation covers is a byte that can be wrong while the diff count is "
                + "zero, and the spans that go unpinned are the ones hardest to notice - a FILLER "
                + "written as zeroes instead of spaces, or a monetary span out by a factor of ten. "
                + "Complete the expectation either way: pin \"expectedBytes\" with the whole record "
                + "image, which also proves the total width and therefore that every FILLER was "
                + "emitted, or name the remaining fields in \"fields\". A REDEFINES overlay covers the "
                + "same bytes as the span it redefines, so pinning either one of a pair is enough."));
    }

    private void reportUnexpectedOutput(Map<String, DatasetOutput> observed,
                                        Map<String, Set<Integer>> expectedRows,
                                        String channel,
                                        List<Diff> diffs) {
        for (Map.Entry<String, DatasetOutput> entry : observed.entrySet()) {
            String dataset = entry.getKey();
            DatasetOutput output = entry.getValue();
            Set<Integer> expected = expectedRows.get(dataset);
            if (expected == null) {
                continue;
            }

            for (int rowIndex = 0; rowIndex < output.rowCount(); rowIndex++) {
                if (expected.contains(rowIndex)) {
                    continue;
                }
                byte[] extra = output.row(rowIndex);
                diffs.add(new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, Diff.NOT_APPLICABLE,
                    extra.length, null,
                    maskImageOf(dataset, output.layout(), imageOf(extra)),
                    DiffKind.EXTRA_RECORD,
                    "the unit wrote " + output.rowCount() + " row(s) to dataset " + dataset
                        + " but the case expects " + expected.size() + ": the " + channel + " row at "
                        + "0-based index " + rowIndex + " is one no expectation addresses. The case "
                        + "addresses row(s) " + new TreeSet<>(expected) + " of that dataset. Extra "
                        + "output is a parity failure in its own right, and it is reported wherever "
                        + "the row sits, not only beyond the highest expected index, because an extra "
                        + "row inserted among the expected ones is exactly as wrong and considerably "
                        + "easier to miss."));
            }
        }

        for (Map.Entry<String, DatasetOutput> entry : observed.entrySet()) {
            String dataset = entry.getKey();
            DatasetOutput output = entry.getValue();
            if (expectedRows.containsKey(dataset)) {
                continue;
            }
            diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, null,
                output.rowCount() + " row(s)", DiffKind.EXTRA_DATASET,
                "the unit produced " + output.rowCount() + " row(s) on the " + channel
                    + " channel for dataset " + dataset + ", which the case expects nothing from. "
                    + "Touching a dataset the case does not mention is a parity failure in its own "
                    + "right: the expected datasets on this channel are "
                    + describeExpectedDatasets(expectedRows)
                    + ". An empty expectation is a positive assertion that nothing was produced, "
                    + "not an absence of interest."));
        }
    }

    private Set<String> compareNamedFields(ExpectedRecord expectation,
                                           RecordLayout layout,
                                           Map<String, FieldSpan> addressable,
                                           FixedWidthRecord record,
                                           String channel,
                                           List<Diff> diffs) {
        Map<String, String> expectedFields = expectation.fields();
        Set<String> compared = new LinkedHashSet<>();
        if (expectedFields.isEmpty()) {
            return compared;
        }

        for (Map.Entry<String, FieldSpan> span : addressable.entrySet()) {
            String fieldName = span.getKey();
            if (!expectedFields.containsKey(fieldName)) {
                continue;
            }
            compared.add(fieldName);
            compareField(expectation, fieldName, span.getValue(), expectedFields.get(fieldName),
                record, channel, diffs);
        }

        for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
            String fieldName = entry.getKey();
            if (compared.contains(fieldName)) {
                continue;
            }
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                Redaction.maskFieldValue(fieldName, entry.getValue()), null,
                DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                "the " + channel + " expectation pins '" + fieldName + "' but "
                    + describeLayout(layout) + " declares no such span, so nothing was decoded for "
                    + "it. Field names are the copybook's own, verbatim and case-sensitive - the "
                    + "misspelled ACCT-EXPIRAION-DATE is spelled that way on purpose - and a "
                    + "record's several FILLER spans are addressed as FILLER, FILLER"
                    + FILLER_ORDINAL_SEPARATOR + "2 and so on in declaration order. Addressable "
                    + "here: " + String.join(", ", addressable.keySet())));
        }
        return compared;
    }

    private void compareRecordImage(ExpectedRecord expectation,
                                    RecordLayout layout,
                                    Map<String, FieldSpan> addressable,
                                    FixedWidthRecord record,
                                    Set<String> comparedFields,
                                    String channel,
                                    List<Diff> diffs) {
        String expectedImage = expectation.expectedBytes();
        if (expectedImage == null) {
            return;
        }

        String dataset = expectation.dataset();
        int rowIndex = expectation.rowIndex();
        byte[] expectedBytes = expectedImage.getBytes(codec.charset());
        if (expectedBytes.length != layout.recordLength()) {
            diffs.add(widthMismatch(dataset, rowIndex, layout, expectedBytes.length, channel,
                "the expected record image is " + expectedBytes.length + " byte(s) wide but "
                    + describeLayout(layout) + " declares " + layout.recordLength()
                    + ". The expectation itself is the wrong width here, so it cannot be compared "
                    + "against a correctly built record. Write it out to the full declared width: "
                    + "the two recorded fixture-to-copybook deviations are repaired at SEED time by "
                    + "ParityCase.Normalisation and never at comparison time, so an expectation is "
                    + "always stated at the copybook's width."));
            return;
        }

        FixedWidthRecord expected = codec.wrap(expectedBytes, layout);
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            String fieldName = entry.getKey();
            if (comparedFields.contains(fieldName)) {
                continue;
            }
            FieldSpan span = entry.getValue();
            if (span.redefinition()) {
                continue;
            }
            compareField(expectation, fieldName, span, expected.readSpan(span), record, channel,
                diffs);
        }
    }

    private void compareField(ExpectedRecord expectation,
                              String fieldName,
                              FieldSpan span,
                              String expectedValue,
                              FixedWidthRecord record,
                              String channel,
                              List<Diff> diffs) {
        String actual = record.readSpan(span);
        if (span.kind() == PictureKind.SIGNED_SCALED) {
            compareSignedScaled(expectation, fieldName, span, expectedValue, actual, channel, diffs);
            return;
        }
        if (expectedValue.equals(actual)) {
            return;
        }
        diffs.add(valueMismatch(expectation, fieldName, span, expectedValue, actual,
            textMismatchExplanation(span, expectedValue, actual, channel)));
    }

    private void compareSignedScaled(ExpectedRecord expectation,
                                     String fieldName,
                                     FieldSpan span,
                                     String expectedValue,
                                     String actual,
                                     String channel,
                                     List<Diff> diffs) {
        if (expectedValue.equals(actual)) {
            return;
        }

        int scale = CobolDecimal.MONETARY_SCALE;
        FixedWidthCodec.SignedZoned actualValue;
        try {
            actualValue = codec.decodeSignedZoned(actual, scale);
        } catch (IllegalArgumentException undecodable) {
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
                span.length(), Redaction.maskFieldValue(fieldName, expectedValue),
                Redaction.maskFieldValue(fieldName, actual), DiffKind.UNDECODABLE_FIELD,
                "the observed bytes are not a valid signed zoned DISPLAY image for " + span.describe()
                    + " on the " + channel + " channel, so no value can be read from them at all. "
                    + signHint(actual) + " The codec reports: " + undecodable.getMessage()));
            return;
        }

        String expectedImage;
        try {
            expectedImage = canonicalSignedImage(expectedValue, span, scale);
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
                span.length(), Redaction.maskFieldValue(fieldName, expectedValue),
                Redaction.maskFieldValue(fieldName, actual), DiffKind.MALFORMED_EXPECTATION,
                "the " + channel + " expectation for " + span.describe() + " is neither a zoned "
                    + "image of the span's " + span.length() + " declared character(s) nor a "
                    + "decimal literal that can be encoded into one, so it cannot be compared. "
                    + "Write either the raw image - for example \"00000001940{\", whose trailing "
                    + "'{' is a positive-zero overpunch and which denotes 194.00 in a "
                    + "twelve-character S9(10)V99 span - or a decimal literal such as "
                    + "\"194.00\". Rejected because: " + malformed.getMessage()));
            return;
        }

        if (expectedImage.equals(actual)) {
            return;
        }
        diffs.add(valueMismatch(expectation, fieldName, span, expectedImage, actual,
            signedMismatchExplanation(span, expectedValue, expectedImage, actual, actualValue, scale,
                channel)));
    }

    private String canonicalSignedImage(String value, FieldSpan span, int scale) {
        if (value.length() == span.length()) {
            try {
                codec.decodeSignedZoned(value, scale);
                return value;
            } catch (IllegalArgumentException notAZonedImage) {
                return canonicalImageOfLiteral(value, span, scale);
            }
        }
        return canonicalImageOfLiteral(value, span, scale);
    }

    private String canonicalImageOfLiteral(String value, FieldSpan span, int scale) {
        return codec.encodeSignedZoned(FixedWidthCodec.SignedZoned.ofLiteral(value),
            span.length() - scale, scale);
    }

    private String signedMismatchExplanation(FieldSpan span,
                                             String expectedValue,
                                             String expectedImage,
                                             String actual,
                                             FixedWidthCodec.SignedZoned actualValue,
                                             int scale,
                                             String channel) {
        StringBuilder text = new StringBuilder("signed zoned ")
            .append(span.describe())
            .append(" differs in its stored bytes on the ")
            .append(channel)
            .append(" channel: expected ")
            .append(quoted(expectedImage));
        if (!expectedImage.equals(expectedValue)) {
            text.append(" (the canonical image of the literal ").append(quoted(expectedValue))
                .append(')');
        }
        text.append(", observed ").append(quoted(actual)).append('.');

        FixedWidthCodec.SignedZoned expectedNumber = null;
        try {
            expectedNumber = codec.decodeSignedZoned(expectedImage, scale);
        } catch (IllegalArgumentException undecodable) {
            text.append(" The expected image cannot itself be decoded, which means the expectation is "
                + "malformed: ").append(undecodable.getMessage());
        }
        if (expectedNumber != null) {
            boolean sameQuantity =
                expectedNumber.signedValue().compareTo(actualValue.signedValue()) == 0;
            if (sameQuantity) {
                text.append(" Both images denote ")
                    .append(expectedNumber.signedValue().toPlainString())
                    .append(", so this is a difference in stored FORM, not in quantity: the VALUE "
                        + "agrees but the REPRESENTATION does not - the record holds the right value "
                        + "but the wrong bytes, which is still a parity failure, because the gate is "
                        + "byte-for-byte.");
                if (expectedNumber.negative() != actualValue.negative()) {
                    text.append(" Specifically the two differ in SIGN while agreeing on every digit: "
                        + "they carry the opposite overpunch of a zero - one a negative zero, the "
                        + "other a positive zero - a distinction a BigDecimal cannot hold at all.");
                } else {
                    text.append(" The usual cause is an unsigned zone-F rendering - a plain digit in "
                        + "the trailing byte - where the signed overpunch form is expected.");
                }
                text.append(" A signed zoned field carries its sign as an overpunch in the trailing "
                    + "character, so one value has several encodings, and the parity contract is the "
                    + "record's bytes rather than the number they happen to denote. Emit the encoding "
                    + "the COBOL emits.");
            } else {
                text.append(" They also denote different quantities, so this differs numerically: "
                        + "expected ")
                    .append(expectedNumber.signedValue().toPlainString())
                    .append(", decoded ")
                    .append(actualValue.signedValue().toPlainString())
                    .append(" - the values differ as well as the bytes: expected image ")
                    .append(quoted(expectedImage))
                    .append(" denoting ")
                    .append(signedRendering(expectedNumber))
                    .append(", observed ")
                    .append(quoted(actual))
                    .append(" denoting ")
                    .append(signedRendering(actualValue))
                    .append(", at scale ").append(scale).append(" with rounding ")
                    .append(CobolDecimal.COBOL_ROUNDING)
                    .append(" (truncation toward zero, because ROUNDED appears zero times in all 28 "
                        + "programs).");
            }
        }
        return text.append(' ').append(signHint(actual)).toString();
    }

    private static String signedRendering(FixedWidthCodec.SignedZoned value) {
        return value.negativeZero()
            ? "a negative zero"
            : value.signedValue().toPlainString();
    }

    private static String quoted(String image) {
        return "\"" + image + "\"";
    }

    private static Diff valueMismatch(ExpectedRecord expectation,
                                      String fieldName,
                                      FieldSpan span,
                                      String expected,
                                      String actual,
                                      String explanation) {
        return new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
            span.length(), Redaction.maskFieldValue(fieldName, expected),
            Redaction.maskFieldValue(fieldName, actual), DiffKind.VALUE_MISMATCH, explanation);
    }

    private static String textMismatchExplanation(FieldSpan span,
                                                  String expected,
                                                  String actual,
                                                  String channel) {
        StringBuilder text = new StringBuilder(describeKind(span.kind()))
            .append(' ')
            .append(span.describe())
            .append(" differs on the ")
            .append(channel)
            .append(" channel.");
        if (expected.length() != span.length()) {
            text.append(" Note the expectation is ")
                .append(expected.length())
                .append(" character(s) where the span is ")
                .append(span.length())
                .append(": comparison is at full declared width and never trims, so write the "
                    + "expectation out to the span's width - space-padded on the right for PIC X, "
                    + "zero-filled on the left for PIC 9.");
        }
        if (differOnlyInTrailingSpaces(expected, actual)) {
            text.append(" The two differ ONLY in trailing spaces, which are significant: a COBOL "
                + "PIC X field is space-padded to its declared width and that padding is part of "
                + "its value.");
        }
        if (span.kind() == PictureKind.UNSIGNED_NUMERIC && differOnlyInLeadingZeros(expected, actual)) {
            text.append(" The two differ ONLY in leading zeros, which are significant: a PIC 9 image "
                + "is zero-filled to its declared width.");
        }
        return text.toString();
    }

    private static boolean differOnlyInTrailingSpaces(String expected, String actual) {
        return !expected.equals(actual)
            && stripTrailingSpaces(expected).equals(stripTrailingSpaces(actual));
    }

    private static boolean differOnlyInLeadingZeros(String expected, String actual) {
        return !expected.equals(actual)
            && stripLeadingZeros(expected).equals(stripLeadingZeros(actual));
    }

    private String signHint(String image) {
        if (image.isEmpty()) {
            return "The image is empty, so it carries no trailing sign position.";
        }
        char trailing = image.charAt(image.length() - 1);
        String rendered = "The trailing byte is " + describeCharacter(trailing) + ", which";
        if (trailing >= '0' && trailing <= '9') {
            return rendered + " is a plain digit: the unsigned zoned form, read as positive.";
        }
        try {
            FixedWidthCodec.SignedZoned decoded =
                codec.decodeSignedZoned(image, CobolDecimal.MONETARY_SCALE);
            return rendered + (decoded.negative()
                ? " is a NEGATIVE sign overpunch."
                : " is a POSITIVE sign overpunch.");
        } catch (IllegalArgumentException notAnOverpunch) {
            return rendered + " is neither a digit nor a recognised sign overpunch, so the field "
                + "cannot be read as a number at all.";
        }
    }

    private void compareResponse(ExpectedResponse expected,
                                 ObservedResponse observed,
                                 List<Diff> diffs) {
        if (expected == null && observed == null) {
            return;
        }
        if (expected == null) {
            diffs.add(responseDiff("<whole>", null, observed.toString(),
                DiffKind.RESPONSE_MISMATCH,
                "the unit returned an online response but the case expects none. A batch case has no "
                    + "screen, so a response here means the harness invoked something other than the "
                    + "unit the case names."));
            return;
        }
        if (observed == null) {
            diffs.add(responseDiff("<whole>", expected.toString(), null,
                DiffKind.RESPONSE_MISMATCH,
                "the case expects an online response but the unit returned none. Every path through "
                    + "an online program produces one - even the no-commarea guard, which produces a "
                    + "response carrying nothing but the next program and the navigation context."));
            return;
        }

        compareResponseScalar("nextProgram", expected.nextProgram(), observed.nextProgram(),
            "the target of EXEC CICS XCTL PROGRAM(...), which becomes a response field in a "
                + "stateless translation because the client resolves the navigation", diffs);
        compareResponseScalar("nextMapset", expected.nextMapset(), observed.nextMapset(),
            "the mapset the response names, carried in CDEMO-LAST-MAPSET", diffs);
        compareResponseScalar("nextMap", expected.nextMap(), observed.nextMap(),
            "the map the response names, carried in CDEMO-LAST-MAP", diffs);
        compareResponseScalar("cursorField", expected.cursorField(), observed.cursorField(),
            "the symbolic-map length item that received MOVE -1, which is how COBOL positions the "
                + "cursor - so a different field here means the cursor landed somewhere else", diffs);
        compareResponseScalar("termination", nameOf(expected.termination()),
            nameOf(observed.termination()),
            "how the transaction ended. XCTL transfers control and never returns, so the EXEC CICS "
                + "RETURN that follows it in the source is not reached, and a bare RETURN names no "
                + "transaction to carry the commarea back to; the three are not interchangeable",
            diffs);

        compareNavigation(expected.navigation(), observed.navigation(), diffs);
        compareSends(expected.sends(), observed.sends(), diffs);
    }

    private static void compareResponseScalar(String fieldName,
                                              String expected,
                                              String actual,
                                              String meaning,
                                              List<Diff> diffs) {
        if (Objects.equals(expected, actual)) {
            return;
        }
        diffs.add(responseDiff(fieldName, Redaction.maskFieldValue(fieldName, expected),
            Redaction.maskFieldValue(fieldName, actual), DiffKind.RESPONSE_MISMATCH,
            "response field '" + fieldName + "' differs. It carries " + meaning + '.'));
    }

    private static void compareNavigation(Map<String, String> expected,
                                          Map<String, String> actual,
                                          List<Diff> diffs) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String field = entry.getKey();
            String actualValue = actual.get(field);
            if (Objects.equals(entry.getValue(), actualValue)) {
                continue;
            }
            diffs.add(responseDiff("navigation." + field,
                Redaction.maskFieldValue(field, entry.getValue()),
                Redaction.maskFieldValue(field, actualValue),
                actual.containsKey(field)
                    ? DiffKind.RESPONSE_MISMATCH
                    : DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                "the CARDDEMO-COMMAREA field " + field + " differs in the returned navigation "
                    + "context. Conversation state travels in the payload rather than in a "
                    + "server-side session, so every one of these fields is part of the observable "
                    + "response. Present in the response: " + actual.keySet()));
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String field = entry.getKey();
            if (expected.containsKey(field)) {
                continue;
            }
            diffs.add(responseDiff("navigation." + field, null,
                Redaction.maskFieldValue(field, entry.getValue()),
                DiffKind.RESPONSE_MISMATCH,
                "the response carries the CARDDEMO-COMMAREA field " + field + ", which the case does "
                    + "not pin. A commarea field the case says nothing about is a field nobody has "
                    + "checked, and the commarea is carried forward into the next transaction."));
        }
    }

    private static void compareSends(List<ScreenSend> expected,
                                     List<ObservedSend> actual,
                                     List<Diff> diffs) {
        if (expected.size() != actual.size()) {
            diffs.add(new Diff(RESPONSE_SCOPE, Diff.NOT_APPLICABLE, SEND_COUNT_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected.size()),
                Integer.toString(actual.size()), DiffKind.SEND_COUNT_MISMATCH,
                "the case expects " + expected.size() + " screen send(s) but the unit performed "
                    + actual.size() + ". The send count is behaviour: several paths through these "
                    + "programs send the same map more than once in one invocation, and a "
                    + "translation that collapsed them would have changed what the terminal saw."));
        }

        int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            compareOneSend(index, expected.get(index), actual.get(index), diffs);
        }
    }

    private static void compareOneSend(int index,
                                       ScreenSend expected,
                                       ObservedSend actual,
                                       List<Diff> diffs) {
        String prefix = "sends[" + index + "].";
        compareSendMap(prefix + "fields.", expected.fields(), actual.fields(),
            "a symbolic-map output field of this send", diffs);
        compareSendMap(prefix + "attributes.", expected.attributes(), actual.attributes(),
            "an attribute item of this send - the colour, protection or highlight byte the program "
                + "moved, named by its DFHBMSCA or DFHATTR mnemonic. Colour is not decoration: it is "
                + "how the program distinguishes an error from a prompt from a confirmation", diffs);
    }

    private static void compareSendMap(String prefix,
                                       Map<String, String> expected,
                                       Map<String, String> actual,
                                       String meaning,
                                       List<Diff> diffs) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String field = entry.getKey();
            String actualValue = actual.get(field);
            if (Objects.equals(entry.getValue(), actualValue)) {
                continue;
            }
            diffs.add(responseDiff(prefix + field,
                Redaction.maskFieldValue(field, entry.getValue()),
                Redaction.maskFieldValue(field, actualValue),
                actual.containsKey(field)
                    ? DiffKind.RESPONSE_MISMATCH
                    : DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                field + " is " + meaning + ", and it differs. Present in this send: "
                    + actual.keySet()));
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String field = entry.getKey();
            if (expected.containsKey(field)) {
                continue;
            }
            diffs.add(responseDiff(prefix + field, null,
                Redaction.maskFieldValue(field, entry.getValue()), DiffKind.RESPONSE_MISMATCH,
                "the send carries " + field + ", which the case does not pin. " + meaning
                    + ", so an unpinned one is a field nobody has checked."));
        }
    }

    private static Diff responseDiff(String fieldName,
                                     String expected,
                                     String actual,
                                     DiffKind kind,
                                     String explanation) {
        return new Diff(RESPONSE_SCOPE, Diff.NOT_APPLICABLE, fieldName, Diff.NOT_APPLICABLE,
            Diff.NOT_APPLICABLE, expected, actual, kind, explanation);
    }

    private static String nameOf(Enum<?> constant) {
        return constant == null ? null : constant.name();
    }

    private void compareReturnCode(ParityCase parityCase, Fingerprint fingerprint, List<Diff> diffs) {
        int expected = parityCase.expectedReturnCode();
        int actual = fingerprint.returnCode();
        if (expected == actual) {
            return;
        }
        diffs.add(new Diff(RETURN_CODE_SCOPE, Diff.NOT_APPLICABLE, RETURN_CODE_FIELD,
            Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected),
            Integer.toString(actual), DiffKind.RETURN_CODE_MISMATCH,
            "the case expects RETURN-CODE " + expected + " but the unit reported " + actual
                + ". The codes this migration produces are 0 (normal), 3 (a named CEEDAYS feedback "
                + "token), 4, 8 and 12 (the MOVE 8 / MOVE 12 TO APPL-RESULT then abend convention) "
                + "and " + FileStatus.APPL_EOF + " (end of file); a batch exit status must carry the "
                + "same value so JCL COND gating continues to behave as it does today."));
    }

    private void compareMessages(ParityCase parityCase, Fingerprint fingerprint, List<Diff> diffs) {
        List<EmittedMessage> expected = parityCase.expectedMessages();
        List<EmittedMessage> actual = fingerprint.messages();

        if (expected.size() != actual.size()) {
            diffs.add(new Diff(MESSAGES_SCOPE, Diff.NOT_APPLICABLE, MESSAGE_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected.size()),
                Integer.toString(actual.size()), DiffKind.MESSAGE_COUNT_MISMATCH,
                "the case expects " + expected.size() + " emitted line(s) but the unit emitted "
                    + actual.size() + ". Emission order and count are both part of the expectation; "
                    + "an empty string on the DISPLAY_LINE channel is a legitimate expectation, "
                    + "because a COBOL DISPLAY of a blank line emits one."));
        }

        int shared = Math.min(expected.size(), actual.size());
        for (int position = 0; position < shared; position++) {
            EmittedMessage expectedLine = expected.get(position);
            EmittedMessage actualLine = actual.get(position);

            if (expectedLine.channel() != actualLine.channel()) {
                diffs.add(new Diff(MESSAGES_SCOPE, position, MESSAGE_FIELD, Diff.NOT_APPLICABLE,
                    Diff.NOT_APPLICABLE, expectedLine.channel().name(), actualLine.channel().name(),
                    DiffKind.MESSAGE_CHANNEL_MISMATCH,
                    "emitted line " + position + " arrived on the " + actualLine.channel()
                        + " channel where the case expects " + expectedLine.channel()
                        + ". The channels have different widths - " + expectedLine.channel()
                        + " because " + expectedLine.channel().declaration() + " - so comparing "
                        + "across them would let the 80-to-78 truncation of MOVE WS-MESSAGE TO "
                        + "ERRMSGO pass unnoticed."));
                continue;
            }
            if (expectedLine.text().equals(actualLine.text())) {
                continue;
            }
            diffs.add(new Diff(MESSAGES_SCOPE, position, MESSAGE_FIELD, Diff.NOT_APPLICABLE,
                expectedLine.text().length(), Redaction.maskIfSensitiveText(expectedLine.text()),
                Redaction.maskIfSensitiveText(actualLine.text()), DiffKind.MESSAGE_MISMATCH,
                "emitted line " + position + " differs byte-exactly on the "
                    + expectedLine.channel() + " channel."
                    + fileStatusHint(expectedLine.text(), actualLine.text())));
        }
    }

    private static String fileStatusHint(String expected, String actual) {
        int prefixLength = FileStatus.DISPLAY_PREFIX.length();
        int fullLength = prefixLength + FileStatus.STATUS_IMAGE_LENGTH;
        if (expected.length() == fullLength && actual.length() == fullLength
            && expected.startsWith(FileStatus.DISPLAY_PREFIX)
            && actual.startsWith(FileStatus.DISPLAY_PREFIX)) {
            return " Both lines are file-status DISPLAY lines carrying the literal '"
                + FileStatus.DISPLAY_PREFIX + "', so only the four-character IO-STATUS-04 image "
                + "differs: expected '" + expected.substring(prefixLength) + "', observed '"
                + actual.substring(prefixLength) + "'. Emit these lines through "
                + "FileStatus.toDisplayLine so all sixteen legacy emission sites stay byte-identical.";
        }
        return "";
    }

    private static Map<String, FieldSpan> addressableSpans(RecordLayout layout) {
        Map<String, FieldSpan> addressable = new LinkedHashMap<>();
        int fillerOrdinal = 0;
        for (FieldSpan span : layout.spans()) {
            if (FILLER_NAME.equals(span.name())) {
                fillerOrdinal++;
                addressable.put(
                    fillerOrdinal == 1
                        ? FILLER_NAME
                        : FILLER_NAME + FILLER_ORDINAL_SEPARATOR + fillerOrdinal,
                    span);
                continue;
            }
            addressable.put(span.name(), span);
        }
        return Collections.unmodifiableMap(addressable);
    }

    private static Diff missingRecord(String dataset,
                                      int rowIndex,
                                      ExpectedRecord expectation,
                                      RecordLayout layout,
                                      String channel,
                                      String reason) {
        return new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, Diff.NOT_APPLICABLE,
            Diff.NOT_APPLICABLE, summariseExpectation(expectation, layout), null,
            DiffKind.MISSING_RECORD,
            "no " + channel + " record was found to compare against: " + reason
                + ". This counts as one difference for the record rather than one per field, because "
                + "the fields of a record that was never produced are not independently wrong - they "
                + "are collectively absent.");
    }

    private static Diff widthMismatch(String dataset,
                                      int rowIndex,
                                      RecordLayout layout,
                                      int measuredWidth,
                                      String channel,
                                      String reason) {
        return new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, 0, layout.recordLength(),
            Integer.toString(layout.recordLength()), Integer.toString(measuredWidth),
            DiffKind.RECORD_WIDTH_MISMATCH,
            "total " + channel + " record width disagrees with the layout: " + reason
                + " Field comparison is skipped for this record, because once the total width is "
                + "wrong every offset past the missing span addresses the wrong bytes and the field "
                + "differences that follow would be noise hiding this one real finding.");
    }

    private static String summariseExpectation(ExpectedRecord expectation, RecordLayout layout) {
        if (expectation.expectedBytes() != null) {
            return maskImageOf(expectation.dataset(), layout, expectation.expectedBytes());
        }
        return "{" + String.join(", ", expectation.fields().keySet()) + "}";
    }

    private static String maskImageOf(String dataset, RecordLayout layout, String image) {
        if (layout == null) {
            return Redaction.maskUnlocatedImage(image);
        }
        return Redaction.maskRecordImage(dataset, Redaction.maskImage(image, redactionSpans(layout)));
    }

    private static List<Redaction.Span> redactionSpans(RecordLayout layout) {
        Map<String, FieldSpan> addressable = addressableSpans(layout);
        List<Redaction.Span> spans = new ArrayList<>(addressable.size());
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            FieldSpan span = entry.getValue();
            spans.add(new Redaction.Span(entry.getKey(), span.offset(), span.length()));
        }
        return spans;
    }

    private static String describeLayout(RecordLayout layout) {
        return "the layout (" + layout.storageSpans().size() + " storage span(s) and "
            + layout.redefinitions().size() + " REDEFINES overlay(s), declared length "
            + layout.recordLength() + ")";
    }

    private static String describeDatasetKeys(Map<String, DatasetOutput> observed) {
        return observed.isEmpty()
            ? "no dataset at all on this channel, so there is no output dataset at all to look in"
            : "dataset(s) " + String.join(", ", observed.keySet());
    }

    private static String describeNormalisations(
            List<ParityCase.DatasetNormalisation> normalisations, String dataset) {
        List<String> bound = new ArrayList<>();
        for (ParityCase.DatasetNormalisation normalisation : normalisations) {
            if (normalisation.dataset().equals(dataset)) {
                bound.add(normalisation.kind().name());
            }
        }
        if (bound.isEmpty()) {
            return "The case declares no normalisation for dataset " + dataset;
        }
        return "The case declares " + String.join(", ", bound) + " for dataset " + dataset
            + ", which is applied when the dataset is seeded and not here";
    }

    private static String describeExpectedDatasets(Map<String, Set<Integer>> expectedRows) {
        return expectedRows.isEmpty() ? "none at all"
            : String.join(", ", expectedRows.keySet());
    }

    private static String describeKind(PictureKind kind) {
        return switch (kind) {
            case ALPHANUMERIC -> "alphanumeric PIC X";
            case UNSIGNED_NUMERIC -> "unsigned zoned PIC 9";
            case SIGNED_SCALED -> "signed zoned PIC S9";
            case FILLER -> "reserved FILLER";
        };
    }

    private String imageOf(byte[] row) {
        if (row.length == 0) {
            return "";
        }
        return FixedWidthRecord.copyOf(row, row.length, codec.charset()).readString(0, row.length);
    }

    private static String describeValue(String value) {
        if (value == null) {
            return "<absent>";
        }
        int trailing = trailingSpaceCount(value);
        StringBuilder text = new StringBuilder("'")
            .append(escapeNonPrinting(value.substring(0, value.length() - trailing)))
            .append('\'');
        if (trailing > 0) {
            text.append(" + ").append(trailing).append(" trailing space(s)");
        }
        return text.append(" (len=").append(value.length()).append(')').toString();
    }

    private static String describeCharacter(char character) {
        return "'" + escapeNonPrinting(String.valueOf(character)) + "' (0x"
            + String.format(Locale.ROOT, "%02X", (int) character) + ")";
    }

    private static String escapeNonPrinting(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 0x20 && character <= 0x7E) {
                out.append(character);
            } else if (character <= 0xFF) {
                out.append("\\x").append(String.format(Locale.ROOT, "%02X", (int) character));
            } else {
                out.append("\\u").append(String.format(Locale.ROOT, "%04X", (int) character));
            }
        }
        return out.toString();
    }

    private static int trailingSpaceCount(String value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == ' '; index--) {
            count++;
        }
        return count;
    }

    private static String stripTrailingSpaces(String value) {
        return value.substring(0, value.length() - trailingSpaceCount(value));
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    public enum DiffKind {
        VALUE_MISMATCH,

        MISSING_RECORD,

        EXTRA_RECORD,

        EXTRA_DATASET,

        RECORD_WIDTH_MISMATCH,

        FIELD_ABSENT_IN_FINGERPRINT,

        UNDECODABLE_FIELD,

        MALFORMED_EXPECTATION,

        RESPONSE_MISMATCH,

        SEND_COUNT_MISMATCH,

        RETURN_CODE_MISMATCH,

        MESSAGE_MISMATCH,

        MESSAGE_CHANNEL_MISMATCH,

        INCOMPLETE_EXPECTATION,

        MISSING_DATASET,

        DATASET_ROW_COUNT_MISMATCH,

        DATASET_WIDTH_MISMATCH,

        MESSAGE_COUNT_MISMATCH
    }

    public record Diff(String dataset,
                       int rowIndex,
                       String fieldName,
                       int offset,
                       int length,
                       String expected,
                       String actual,
                       DiffKind kind,
                       String explanation) {
        public static final int NOT_APPLICABLE = -1;

        public Diff {
            Objects.requireNonNull(dataset, "Diff.dataset is required: a difference must say which "
                + "dataset or pseudo-scope it belongs to");
            Objects.requireNonNull(fieldName, "Diff.fieldName is required: a difference must name the "
                + "field it localises to, which is the entire point of a field-by-field differ");
            Objects.requireNonNull(kind, "Diff.kind is required");
            Objects.requireNonNull(explanation, "Diff.explanation is required: an unexplained "
                + "difference costs the reader a debugging session that a sentence would have saved");
            if (dataset.isBlank()) {
                throw new IllegalArgumentException("Diff.dataset must not be blank");
            }
            if (fieldName.isBlank()) {
                throw new IllegalArgumentException("Diff.fieldName must not be blank");
            }
            if (explanation.isBlank()) {
                throw new IllegalArgumentException("Diff.explanation must not be blank");
            }
            requireIndexOrNotApplicable(rowIndex, "rowIndex");
            requireIndexOrNotApplicable(offset, "offset");
            requireIndexOrNotApplicable(length, "length");
        }

        public String render() {
            StringBuilder text = new StringBuilder()
                .append(kind)
                .append(" at ")
                .append(dataset);
            if (rowIndex != NOT_APPLICABLE) {
                text.append(" row ").append(rowIndex);
            }
            text.append(" field ").append(fieldName);
            if (offset != NOT_APPLICABLE && length != NOT_APPLICABLE) {
                text.append(" (offset ").append(offset).append(", length ").append(length).append(')');
            } else if (length != NOT_APPLICABLE) {
                text.append(" (length ").append(length).append(')');
            }
            return text.append(System.lineSeparator())
                .append("    expected: ").append(describeValue(expected))
                .append(System.lineSeparator())
                .append("    actual:   ").append(describeValue(actual))
                .append(System.lineSeparator())
                .append("    because:  ").append(explanation)
                .toString();
        }

        @Override
        public String toString() {
            return render();
        }

        private static void requireIndexOrNotApplicable(int value, String member) {
            if (value < 0 && value != NOT_APPLICABLE) {
                throw new IllegalArgumentException("Diff." + member + " is " + value
                    + "; it must be zero or positive, or exactly " + NOT_APPLICABLE
                    + " to state that the concept does not apply");
            }
        }
    }

    public static final class DatasetOutput {
        private final String dataset;

        private final RecordLayout layout;

        private final List<byte[]> rows;

        private DatasetOutput(String dataset, RecordLayout layout, List<byte[]> rows) {
            this.dataset = dataset;
            this.layout = layout;
            List<byte[]> copies = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                byte[] row = rows.get(index);
                if (row == null) {
                    throw new IllegalArgumentException("DatasetOutput row " + index + " of dataset "
                        + dataset + " is null; a row the unit did not produce must be absent from the "
                        + "list rather than present as a hole in the order");
                }
                copies.add(row.clone());
            }
            this.rows = Collections.unmodifiableList(copies);
        }

        public static DatasetOutput of(String dataset, RecordLayout layout, List<byte[]> rows) {
            return new DatasetOutput(requireDatasetKey(dataset), requireLayout(layout, dataset),
                Objects.requireNonNull(rows, "DatasetOutput rows are required; pass an empty list for "
                    + "a dataset the unit produced nothing for"));
        }

        public static DatasetOutput ofImages(String dataset,
                                            RecordLayout layout,
                                            List<String> images,
                                            Charset charset) {
            Objects.requireNonNull(images, "DatasetOutput row images are required; pass an empty list "
                + "for a dataset the unit produced nothing for");
            Objects.requireNonNull(charset, "A charset is required to encode row images and is never "
                + "defaulted: a space is 0x40 under IBM037 and 0x20 under US-ASCII, so the code page "
                + "changes the bytes");
            List<byte[]> encoded = new ArrayList<>(images.size());
            for (int index = 0; index < images.size(); index++) {
                String image = images.get(index);
                if (image == null) {
                    throw new IllegalArgumentException("DatasetOutput row image " + index
                        + " of dataset " + dataset + " is null; a row the unit did not produce must be "
                        + "absent from the list rather than present as a hole in the order");
                }
                encoded.add(image.getBytes(charset));
            }
            return of(dataset, layout, encoded);
        }

        public static DatasetOutput empty(String dataset, RecordLayout layout) {
            return of(dataset, layout, List.of());
        }

        public String dataset() {
            return dataset;
        }

        public RecordLayout layout() {
            return layout;
        }

        public int rowCount() {
            return rows.size();
        }

        public boolean hasRow(int rowIndex) {
            return rowIndex >= 0 && rowIndex < rows.size();
        }

        public byte[] row(int rowIndex) {
            if (!hasRow(rowIndex)) {
                throw new IndexOutOfBoundsException("Dataset " + dataset + " holds " + rows.size()
                    + " row(s); there is no row at 0-based index " + rowIndex);
            }
            return rows.get(rowIndex).clone();
        }

        @Override
        public String toString() {
            return "DatasetOutput[" + dataset + ", " + rows.size() + " row(s) of declared length "
                + layout.recordLength() + "]";
        }

        private static String requireDatasetKey(String dataset) {
            Objects.requireNonNull(dataset, "DatasetOutput dataset is required and must be the "
                + "binding key declared in application.yml, never a literal dataset name");
            if (dataset.isBlank()) {
                throw new IllegalArgumentException("DatasetOutput dataset must not be blank");
            }
            return dataset;
        }

        private static RecordLayout requireLayout(RecordLayout layout, String dataset) {
            return Objects.requireNonNull(layout, "A RecordLayout is required for dataset " + dataset
                + ": the differ decodes each row through it, and its own self-check is what proves "
                + "every FILLER is declared and the spans total the declared record length");
        }
    }

    public record ObservedSend(Map<String, String> fields, Map<String, String> attributes) {
        public ObservedSend {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(fields, "ObservedSend fields are required; pass an empty map "
                    + "for a send that carried none")));
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(attributes, "ObservedSend attributes are required; pass an "
                    + "empty map for a send that set none")));
        }

        public static ObservedSend ofFields(Map<String, String> fields) {
            return new ObservedSend(fields, Map.of());
        }

        @Override
        public String toString() {
            return "ObservedSend[fields=" + fields.keySet() + ", attributes=" + attributes + ']';
        }
    }

    public record ObservedResponse(String nextProgram,
                                   String nextMapset,
                                   String nextMap,
                                   Map<String, String> navigation,
                                   List<ObservedSend> sends,
                                   String cursorField,
                                   Termination termination) {
        public ObservedResponse {
            navigation = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(navigation, "ObservedResponse navigation is required; pass an "
                    + "empty map for a response carrying no commarea field")));
            sends = List.copyOf(Objects.requireNonNull(sends, "ObservedResponse sends are required; "
                + "pass an empty list for a path that sends no map"));
        }

        @Override
        public String toString() {
            return "ObservedResponse[nextProgram=" + nextProgram + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap + ", navigation=" + navigation.keySet()
                + ", sends=" + sends.size() + ", cursorField=" + cursorField
                + ", termination=" + termination + ']';
        }
    }

    /**
     * Everything one run of a unit under test observably produced: the records it wrote in order, the state
     * each dataset was left in, the online response it returned, the {@code RETURN-CODE} it set and the
     * lines it emitted.
     */
    public static final class Fingerprint {
        private final Map<String, DatasetOutput> writes;

        private final Map<String, DatasetOutput> finalState;

        private final ObservedResponse response;

        private final int returnCode;

        private final List<EmittedMessage> messages;

        private Fingerprint(List<DatasetOutput> writes,
                            List<DatasetOutput> finalState,
                            ObservedResponse response,
                            int returnCode,
                            List<EmittedMessage> messages) {
            this.writes = freezeOutputs(writes, "writes");
            this.finalState = freezeOutputs(finalState, "finalState");
            this.response = response;
            this.returnCode = returnCode;
            List<EmittedMessage> emitted = new ArrayList<>(messages.size());
            for (int index = 0; index < messages.size(); index++) {
                EmittedMessage message = messages.get(index);
                if (message == null) {
                    throw new IllegalArgumentException("Fingerprint message " + index + " is null; "
                        + "capture an EmittedMessage with an empty text on the DISPLAY_LINE channel "
                        + "for a blank emitted line, which is what a COBOL DISPLAY of a blank line "
                        + "produces");
                }
                emitted.add(message);
            }
            this.messages = Collections.unmodifiableList(emitted);
        }

        public static Fingerprint of(List<DatasetOutput> writes,
                                     List<DatasetOutput> finalState,
                                     ObservedResponse response,
                                     int returnCode,
                                     List<EmittedMessage> messages) {
            Objects.requireNonNull(writes, "Fingerprint writes are required; pass an empty list for a "
                + "unit that writes nothing");
            Objects.requireNonNull(finalState, "Fingerprint finalState is required; pass an empty "
                + "list only when the unit touches no dataset at all - a unit that READ a dataset and "
                + "wrote nothing must still report that dataset's unchanged rows, because that is the "
                + "assertion such a case makes");
            Objects.requireNonNull(messages, "Fingerprint messages are required; pass an empty list "
                + "for a unit that emits nothing");
            if (returnCode < 0) {
                throw new IllegalArgumentException("Fingerprint returnCode is " + returnCode
                    + "; a z/OS step return code is never negative, so a negative value is a sign "
                    + "error in the capture rather than an observation");
            }
            return new Fingerprint(writes, finalState, response, returnCode, messages);
        }

        public static Fingerprint ofReturnCode(int returnCode) {
            return of(List.of(), List.of(), null, returnCode, List.of());
        }

        public Map<String, DatasetOutput> writes() {
            return writes;
        }

        public Map<String, DatasetOutput> finalState() {
            return finalState;
        }

        public ObservedResponse response() {
            return response;
        }

        public Optional<DatasetOutput> findWrites(String dataset) {
            Objects.requireNonNull(dataset, "A dataset binding key is required to look up writes");
            return Optional.ofNullable(writes.get(dataset));
        }

        public Optional<DatasetOutput> findFinalState(String dataset) {
            Objects.requireNonNull(dataset,
                "A dataset binding key is required to look up a final state");
            return Optional.ofNullable(finalState.get(dataset));
        }

        /**
         * The COBOL {@code RETURN-CODE} the run ended with.
         *
         * @return the return code, never negative
         */
        public int returnCode() {
            return returnCode;
        }

        public List<EmittedMessage> messages() {
            return messages;
        }

        @Override
        public String toString() {
            return "Fingerprint[writes=" + writes.keySet() + ", finalState=" + finalState.keySet()
                + ", response=" + (response == null ? "absent" : "present")
                + ", returnCode=" + returnCode + ", messages=" + messages.size() + ']';
        }

        private static Map<String, DatasetOutput> freezeOutputs(List<DatasetOutput> outputs,
                                                               String channel) {
            Map<String, DatasetOutput> byKey = new LinkedHashMap<>();
            for (int index = 0; index < outputs.size(); index++) {
                DatasetOutput output = outputs.get(index);
                if (output == null) {
                    throw new IllegalArgumentException("Fingerprint " + channel + " entry " + index
                        + " is null; remove the entry rather than leaving a hole among the datasets");
                }
                DatasetOutput previous = byKey.put(output.dataset(), output);
                if (previous != null) {
                    throw new IllegalArgumentException("Fingerprint " + channel + " declares dataset "
                        + output.dataset() + " more than once; merge the rows into one DatasetOutput "
                        + "in order, because a second entry would silently shadow the first and the "
                        + "rows it carries would never be compared");
                }
            }
            return Collections.unmodifiableMap(byKey);
        }
    }

    public static final class DiffResult {
        private final String program;

        private final String caseId;

        private final List<Diff> entries;

        private DiffResult(String program, String caseId, List<Diff> entries) {
            this.program = program;
            this.caseId = caseId;
            this.entries = List.copyOf(entries);
        }

        public int count() {
            return entries.size();
        }

        public boolean isClean() {
            return entries.isEmpty();
        }

        public List<Diff> entries() {
            return entries;
        }

        public String program() {
            return program;
        }

        public String caseId() {
            return caseId;
        }

        public String render() {
            String newLine = System.lineSeparator();
            StringBuilder text = new StringBuilder()
                .append("Parity comparison ")
                .append(program)
                .append('/')
                .append(caseId)
                .append(": ")
                .append(count())
                .append(count() == 1 ? " difference" : " differences")
                .append(isClean() ? " - CLEAN, the gate is satisfied for this case."
                    : " - the gate requires a diff count of 0.");
            for (int index = 0; index < entries.size(); index++) {
                text.append(newLine)
                    .append("  [")
                    .append(index + 1)
                    .append('/')
                    .append(entries.size())
                    .append("] ")
                    .append(entries.get(index).render());
            }
            return text.toString();
        }

        @Override
        public String toString() {
            return render();
        }
    }

}
