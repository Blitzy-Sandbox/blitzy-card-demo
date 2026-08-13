package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DatasetOutput;
import com.vsergeychik.carddemo.parity.FieldDiffer.Diff;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffKind;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.Fingerprint;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests the two properties of the parity judge that decide whether the diff-count gate means anything: that
 * a signed field is compared by its stored bytes, and that a width normalisation can only ever be applied
 * to the record it was derived from.
 */
@DisplayName("FieldDiffer - stored bytes decide, and a normalisation is bound to its own record")
class FieldDifferByteParityTest {
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final String TCATBALF = "TCATBALF";

    private static final String CCXREF = "CCXREF";

    private final FieldDiffer differ = FieldDiffer.forCharset(ASCII);

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

    private static RecordLayout tranCatBalLayout() {
        return RecordLayout.of(50,
                FieldSpan.unsignedNumeric("TRANCAT-ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("TRANCAT-TYPE-CD", 11, 2),
                FieldSpan.unsignedNumeric("TRANCAT-CD", 13, 4),
                FieldSpan.signedScaled("TRAN-CAT-BAL", 17, 9, 2),
                FieldSpan.filler(28, 22));
    }

    private static RecordLayout cardXrefLayout() {
        return RecordLayout.of(50,
                FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14));
    }

    private static String tranCatBalRow(String balanceImage) {
        return "00000000001" + "01" + "0001" + balanceImage + " ".repeat(22);
    }

    private static ParityCase caseFor(String dataset, String fieldName, String expectedValue) {
        return caseFor(dataset, fieldName, expectedValue, Map.of(), List.of());
    }

    private static ParityCase caseFor(String dataset,
                                      String fieldName,
                                      String expectedValue,
                                      Map<String, DatasetInput> inputs,
                                      List<DatasetNormalisation> normalisations) {
        return new ParityCase("CBTRN02C", "case01", "one field of one row", UnitKind.BATCH_JOB,
                inputs, Map.of(), null, null,
                List.of(new ExpectedRecord(dataset, 0, Map.of(fieldName, expectedValue), null)),
                List.of(), 0, List.of(), normalisations);
    }

    private Fingerprint fingerprintOf(String dataset, RecordLayout layout, String row) {
        return Fingerprint.of(
                List.of(DatasetOutput.ofImages(dataset, layout, List.of(row), ASCII)), List.of(),
                null, 0, List.of());
    }

    @Nested
    @DisplayName("A signed field is compared by its stored bytes")
    class SignedFieldsCompareByBytes {
        @Test
        @DisplayName("identical images are clean")
        void identicalImagesAreClean() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "0000123456C"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000123456C")));

            assertThat(outputIsClean(result)).isTrue();
            assertThat(outputCount(result)).isZero();
        }

        @Test
        @DisplayName("the unsigned zone-F form differs from the signed form, though both mean the same")
        void aZoneMismatchIsADifference() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "0000123456C"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("00001234563")));

            assertThat(outputCount(result)).isOne();
            Diff diff = outputDiffs(result).get(0);
            assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(diff.fieldName()).isEqualTo("TRAN-CAT-BAL");
            assertThat(diff.offset()).isEqualTo(17);
            assertThat(diff.length()).isEqualTo(11);
            assertThat(diff.explanation())
                    .as("the explanation must say the quantity agrees and the bytes do not, or the "
                            + "reader will take a true finding for a false positive")
                    .contains("differs in its stored bytes")
                    .contains("difference in stored FORM, not in quantity")
                    .contains("unsigned zone-F rendering")
                    .as("the eleven characters 0000123456C carry the digits 00001234563, so nine "
                            + "integer digits and two fraction digits make 12345.63")
                    .contains("12345.63");
        }

        @Test
        @DisplayName("a negative zero differs from a positive zero")
        void negativeZeroDiffersFromPositiveZero() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "0000000000{"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000000000}")));

            assertThat(outputCount(result)).isOne();
            assertThat(outputDiffs(result).get(0).kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("difference in stored FORM, not in quantity")
                    .contains("differ in SIGN")
                    .as("a BigDecimal comparison cannot see this at all: it has no negative zero")
                    .contains("negative zero")
                    .as("and the sign hint must name the overpunch that is actually present, which "
                            + "asking a decoded BigDecimal for its signum would get wrong")
                    .contains("is a NEGATIVE sign overpunch");
        }

        @Test
        @DisplayName("a decimal literal expectation is encoded to the canonical image and compared")
        void aLiteralExpectationComparesCanonically() {
            DiffResult clean = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "12345.63"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000123456C")));

            assertThat(outputIsClean(clean))
                    .as("a literal states a value and gets the one stored form a COBOL store produces")
                    .isTrue();

            DiffResult zoneF = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "12345.63"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("00001234563")));

            assertThat(outputCount(zoneF)).isOne();
            assertThat(outputDiffs(zoneF).get(0).explanation())
                    .contains("the canonical image of the literal")
                    .contains("0000123456C");
        }

        @Test
        @DisplayName("a negative-zero literal encodes to the negative-zero image, not the positive one")
        void aNegativeZeroLiteralEncodesToTheNegativeZeroImage() {
            assertThat(outputIsClean(differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "-0.00"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000000000}")))))
                    .as("this is the whole reason the literal reader honours a leading minus on an "
                            + "all-zero value")
                    .isTrue();

            assertThat(outputCount(differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "-0.00"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000000000{")))))
                    .isOne();
        }

        @Test
        @DisplayName("a genuine quantity difference is reported as one, with both quantities named")
        void aQuantityDifferenceNamesBothQuantities() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "12345.63"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000123457C")));

            assertThat(outputCount(result)).isOne();
            assertThat(outputDiffs(result).get(0).explanation())
                    .contains("different quantities")
                    .contains("12345.63")
                    .contains("12345.73");
        }

        @Test
        @DisplayName("observed bytes that are not a zoned image are reported as undecodable")
        void undecodableObservedBytesAreReportedAsSuch() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "12345.63"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("000012345$0")));

            assertThat(outputCount(result)).isPositive();
            assertThat(outputDiffs(result))
                    .anyMatch(diff -> diff.kind() == DiffKind.UNDECODABLE_FIELD
                            || diff.kind() == DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("an expectation that is neither an image nor a literal is reported as malformed")
        void aMalformedExpectationIsReportedAsSuch() {
            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "about twelve quid"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000123456C")));

            assertThat(outputCount(result)).isOne();
            assertThat(outputDiffs(result).get(0).kind()).isEqualTo(DiffKind.MALFORMED_EXPECTATION);
            assertThat(outputDiffs(result).get(0).explanation()).contains("00000001940{");
        }
    }

    @Nested
    @DisplayName("A normalisation is bound to the record it was derived from")
    class NormalisationsAreDatasetBound {
        @Test
        @DisplayName("the cross-reference pad applies to the cross-reference, at seed time")
        void theCrossReferencePadAppliesToItsOwnDataset() {
            String thirtySixByteRow = "0500024453765740" + "000000050" + "00000000005";
            DatasetNormalisation pad =
                    new DatasetNormalisation(CCXREF, Normalisation.CARDXREF_FILLER_PAD_36_TO_50);

            String seeded = pad.normaliseSeedRow(thirtySixByteRow);

            DiffResult result = differ.compare(
                    caseFor(CCXREF, "XREF-ACCT-ID", "00000000005",
                            Map.of(CCXREF, DatasetInput.ofRows(List.of(thirtySixByteRow))),
                            List.of(pad)),
                    fingerprintOf(CCXREF, cardXrefLayout(), seeded));

            assertThat(thirtySixByteRow).hasSize(36);
            assertThat(seeded).hasSize(50).startsWith(thirtySixByteRow);
            assertThat(outputIsClean(result)).isTrue();
            assertThat(pad.kind().name())
                    .as("a normalisation that fires must be visible to a reviewer: the case names it")
                    .isEqualTo("CARDXREF_FILLER_PAD_36_TO_50");
            assertThat(pad.kind().absentSpan())
                    .as("and names the span it supplies, so what was added is auditable")
                    .isEqualTo("FILLER PIC X(14)");
        }

        @Test
        @DisplayName("the same pad cannot repair a different 50-byte record that happens to be short")
        void theSamePadCannotRepairAnUnrelatedFiftyByteRecord() {
            String thirtySixBytes = "00000000001" + "01" + "0001" + "0000123456C" + " ".repeat(8);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetNormalisation(TCATBALF,
                            Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                    .withMessageContaining("does not describe")
                    .withMessageContaining("FILLER PIC X(14)");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes(TCATBALF)).isFalse();

            DiffResult result = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "0000123456C"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(), thirtySixBytes));

            assertThat(thirtySixBytes).hasSize(36);
            assertThat(outputCount(result)).isPositive();
            assertThat(outputDiffs(result))
                    .anyMatch(diff -> diff.kind() == DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("a case that declares no normalisation gets no pad")
        void anUndeclaredNormalisationDoesNotFire() {
            String thirtySixByteRow = "0500024453765740" + "000000050" + "00000000005";

            ParityCase undeclared = caseFor(CCXREF, "XREF-ACCT-ID", "00000000005");
            DiffResult result = differ.compare(undeclared,
                    fingerprintOf(CCXREF, cardXrefLayout(), thirtySixByteRow));

            assertThat(undeclared.normalisations()).isEmpty();
            assertThat(outputDiffs(result))
                    .anyMatch(diff -> diff.kind() == DiffKind.RECORD_WIDTH_MISMATCH);
        }

        @Test
        @DisplayName("the security-user pad is bound to USRSEC alone")
        void theSecurityUserPadIsBoundToUsrsecAlone() {
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.datasets())
                    .containsExactly("USRSEC");
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.describes("USRSEC")).isTrue();
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.describes("CCXREF")).isFalse();
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.padWidth()).isEqualTo(23);
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.copybook()).isEqualTo("CSUSR01Y");
        }

        @Test
        @DisplayName("the cross-reference pad covers every DD name and path the one cluster is bound to")
        void theCrossReferencePadCoversEveryAliasOfItsCluster() {
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.datasets())
                    .containsExactlyInAnyOrder("CCXREF", "CXACAIX", "XREFFILE", "XREFFIL1",
                            "CARDXREF");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes("XREFFIL1")).isTrue();
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes("TCATBALF")).isFalse();
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.describes("DISCGRP")).isFalse();
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.sourceWidth()).isEqualTo(36);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.targetWidth()).isEqualTo(50);
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.absentSpan())
                    .isEqualTo("FILLER PIC X(14)");
        }

        @Test
        @DisplayName("asking whether a normalisation applies to no dataset at all is refused")
        void aNullDatasetIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Normalisation.USRSEC_FILLER_PAD_57_TO_80.describes(null))
                    .withMessageContaining("dataset binding key is required");
        }
    }

    @Nested
    @DisplayName("The differ's own contract")
    class DifferContract {
        @Test
        @DisplayName("the code page is always named, never defaulted")
        void theCodePageIsAlwaysNamed() {
            assertThat(differ.codec().charset()).isEqualTo(ASCII);
            assertThatNullPointerException().isThrownBy(() -> FieldDiffer.forCharset(null))
                    .withMessageContaining("charset is required");
            assertThatNullPointerException().isThrownBy(() -> new FieldDiffer(null))
                    .withMessageContaining("FixedWidthCodec is required");
        }

        @Test
        @DisplayName("both sides of a comparison are mandatory")
        void bothSidesAreMandatory() {
            ParityCase parityCase = caseFor(TCATBALF, "TRAN-CAT-BAL", "0.00");
            Fingerprint fingerprint =
                    fingerprintOf(TCATBALF, tranCatBalLayout(), tranCatBalRow("0000000000{"));

            assertThatNullPointerException().isThrownBy(() -> differ.compare(null, fingerprint))
                    .withMessageContaining("ParityCase is required");
            assertThatNullPointerException().isThrownBy(() -> differ.compare(parityCase, null))
                    .withMessageContaining("Fingerprint is required");
        }

        @Test
        @DisplayName("a row of the wrong width is one width mismatch, not a cascade of field differences")
        void aWrongWidthRowIsOneDifference() {
            DiffResult tooWide = differ.compare(
                    caseFor(TCATBALF, "TRAN-CAT-BAL", "0.00"),
                    fingerprintOf(TCATBALF, tranCatBalLayout(),
                            tranCatBalRow("0000000000{") + "X"));

            assertThat(outputDiffs(tooWide))
                    .as("a record whose width disagrees with its layout has every subsequent offset "
                            + "shifted, so it is reported once against the record rather than as a "
                            + "difference per field")
                    .hasSize(1);
            assertThat(outputDiffs(tooWide).get(0).kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH);
            assertThatIllegalArgumentException()
                    .as("and no normalisation may rescue an over-wide row: padding is one-directional "
                            + "and truncating would hide the disagreement")
                    .isThrownBy(() -> Normalisation.CARDXREF_FILLER_PAD_36_TO_50
                            .normaliseSeedRow(tranCatBalRow("0000000000{") + "X", CCXREF))
                    .withMessageContaining("cannot be normalised");
        }
    }
}
