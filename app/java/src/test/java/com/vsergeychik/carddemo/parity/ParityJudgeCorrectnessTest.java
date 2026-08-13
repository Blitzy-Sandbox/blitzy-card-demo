package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the four ways the parity judge could report a clean comparison over output that is not clean.
 */
@DisplayName("Parity judge correctness - the four ways it could have passed dirty output")
class ParityJudgeCorrectnessTest {
    private static final String TRANSACT = "TRANSACT";

    private static final FieldSpan AMOUNT =
        FieldSpan.signedScaled("TRAN-AMT", 0, 10, 2);

    private static final RecordLayout AMOUNT_LAYOUT = new RecordLayout(12, List.of(AMOUNT));

    private static final FieldDiffer DIFFER = FieldDiffer.forCharset(StandardCharsets.US_ASCII);

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

    private static ParityCase amountCase(final String expectedImage) {
        return new ParityCase("CBACT04C", "case01", "one signed amount", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null,
            List.of(new ExpectedRecord(TRANSACT, 0, Map.of("TRAN-AMT", expectedImage), null)),
            List.of(), 0, List.of(), List.of());
    }

    private static Fingerprint amountFingerprint(final String observedImage) {
        return Fingerprint.of(
            List.of(DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT, List.of(observedImage),
                StandardCharsets.US_ASCII)),
            List.of(), null, 0, List.of());
    }

    @Nested
    @DisplayName("A signed zoned image is compared as bytes, not as a number - F19")
    class SignedZonedImages {
        @ParameterizedTest(name = "expected {0} against observed {1} is reported")
        @ValueSource(strings = {
            "00000000000{|00000000000}",
            "00000000000}|00000000000{",
            "000000000000|00000000000{",
            "00000000000{|000000000000",
            "000000000001|00000000000A",
            "00000000000A|000000000001",
            "00000000000J|00000000000}",
        })
        @DisplayName("Two encodings of one value are a difference, because the bytes are the contract")
        void twoEncodingsOfOneValueAreADifference(final String pair) {
            String expected = pair.substring(0, pair.indexOf('|'));
            String observed = pair.substring(pair.indexOf('|') + 1);

            DiffResult result = DIFFER.compare(amountCase(expected), amountFingerprint(observed));

            assertThat(outputIsClean(result))
                .as("expected %s against observed %s must not pass", expected, observed)
                .isFalse();
            assertThat(outputCount(result)).isEqualTo(1);
            assertThat(outputDiffs(result)).singleElement().satisfies(diff -> {
                assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
                assertThat(diff.expected()).isEqualTo(expected);
                assertThat(diff.actual()).isEqualTo(observed);
            });
        }

        @Test
        @DisplayName("Positive zero and negative zero are the case that used to pass silently")
        void positiveZeroAndNegativeZeroAreReported() {
            DiffResult result = DIFFER.compare(amountCase("00000000000{"),
                amountFingerprint("00000000000}"));

            assertThat(outputCount(result)).isEqualTo(1);
            assertThat(outputDiffs(result).get(0).explanation())
                .contains("right value but the wrong bytes")
                .contains("overpunch")
                .contains("0.00");
        }

        @Test
        @DisplayName("A genuinely different value is still reported with both numbers named")
        void aGenuinelyDifferentValueIsStillReported() {
            DiffResult result = DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("00000019500{"));

            assertThat(outputCount(result)).isEqualTo(1);
            assertThat(outputDiffs(result).get(0).explanation())
                .contains("denoting")
                .contains("1940.00")
                .contains("1950.00")
                .doesNotContain("right value but the wrong bytes");
        }

        @Test
        @DisplayName("Identical images still pass: the fix adds no false failure")
        void identicalImagesStillPass() {
            assertThat(outputIsClean(DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("00000019400{")))).isTrue();
            assertThat(outputIsClean(DIFFER.compare(amountCase("00000019400}"),
                amountFingerprint("00000019400}")))).isTrue();
            assertThat(outputIsClean(DIFFER.compare(amountCase("000000194000"),
                amountFingerprint("000000194000")))).isTrue();
        }

        @Test
        @DisplayName("A decimal literal is encoded to the one image a COBOL store produces, then compared")
        void aDecimalLiteralIsComparedAsTheImageItEncodesTo() {
            assertThat(outputIsClean(DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("00000019400{"))))
                .as("the canonical image of 1940.00 is the positive-overpunch form")
                .isTrue();
            assertThat(outputIsClean(DIFFER.compare(amountCase("-1940.00"),
                amountFingerprint("00000019400}"))))
                .as("and a negative literal encodes to the negative overpunch")
                .isTrue();

            DiffResult zoneF = DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("000000194000"));
            assertThat(outputIsClean(zoneF))
                .as("the unsigned zone-F rendering is not the image a COBOL store produces")
                .isFalse();
            assertThat(outputDiffs(zoneF)).singleElement().satisfies(diff -> {
                assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
                assertThat(diff.explanation())
                    .contains("the canonical image of the literal")
                    .contains("right value but the wrong bytes");
            });

            assertThat(outputIsClean(DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("00000019500{"))))
                .as("and a genuinely different quantity is reported as before")
                .isFalse();
        }

        @Test
        @DisplayName("A negative-zero literal is not satisfied by a positive zero, which decodes alike")
        void aNegativeZeroLiteralIsNotSatisfiedByAPositiveZero() {
            assertThat(outputIsClean(DIFFER.compare(amountCase("-0.00"),
                amountFingerprint("00000000000}"))))
                .as("the literal reader honours a leading minus on an all-zero value")
                .isTrue();

            DiffResult flipped = DIFFER.compare(amountCase("-0.00"),
                amountFingerprint("00000000000{"));
            assertThat(outputCount(flipped)).isEqualTo(1);
            assertThat(outputDiffs(flipped).get(0).explanation())
                .contains("differ in SIGN")
                .contains("opposite overpunch of a zero");
        }

        @Test
        @DisplayName("An undecodable observation is still reported as undecodable, not as a mismatch")
        void anUndecodableObservationIsStillReportedAsSuch() {
            DiffResult result = DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("0000001940**"));

            assertThat(outputCount(result)).isEqualTo(1);
            assertThat(outputDiffs(result).get(0).kind()).isEqualTo(DiffKind.UNDECODABLE_FIELD);
        }
    }

    @Nested
    @DisplayName("Output the case never mentions is reported - F20")
    class UnexpectedOutput {
        private ParityCase caseExpectingOnlyTransact() {
            return amountCase("00000019400{");
        }

        @Test
        @DisplayName("A dataset with no expectations is reported, once, with the rows it holds")
        void aDatasetWithNoExpectationsIsReported() {
            Fingerprint wroteAnExtraDataset = Fingerprint.of(List.of(
                DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT, List.of("00000019400{"),
                    StandardCharsets.US_ASCII),
                DatasetOutput.ofImages("DALYREJS", AMOUNT_LAYOUT,
                    List.of("00000000001{", "00000000002{"), StandardCharsets.US_ASCII)),
                List.of(), null, 0, List.of());

            DiffResult result = DIFFER.compare(caseExpectingOnlyTransact(), wroteAnExtraDataset);

            assertThat(outputIsClean(result)).isFalse();
            assertThat(outputDiffs(result)).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_DATASET);
            assertThat(outputDiffs(result).get(0).dataset()).isEqualTo("DALYREJS");
            assertThat(outputDiffs(result).get(0).actual()).isEqualTo("2 row(s)");
            assertThat(outputDiffs(result).get(0).explanation())
                .contains("which the case expects nothing from")
                .contains("2 row(s)")
                .contains(TRANSACT);
        }

        @Test
        @DisplayName("An unexpected dataset holding ZERO rows is still reported")
        void anEmptyUnexpectedDatasetIsStillReported() {
            Fingerprint openedButWroteNothing = Fingerprint.of(List.of(
                DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT, List.of("00000019400{"),
                    StandardCharsets.US_ASCII),
                DatasetOutput.empty("DALYREJS", AMOUNT_LAYOUT)),
                List.of(), null, 0, List.of());

            DiffResult result = DIFFER.compare(caseExpectingOnlyTransact(), openedButWroteNothing);

            assertThat(outputCount(result)).isEqualTo(1);
            assertThat(outputDiffs(result).get(0).kind()).isEqualTo(DiffKind.EXTRA_DATASET);
            assertThat(outputDiffs(result).get(0).explanation()).contains("0 row(s)");
        }

        @Test
        @DisplayName("Expected datasets come first, then unexpected ones, so the order is stable")
        void theOrderIsDeterministic() {
            Fingerprint twoExtras = Fingerprint.of(List.of(
                DatasetOutput.ofImages("DALYREJS", AMOUNT_LAYOUT, List.of("00000000001{"),
                    StandardCharsets.US_ASCII),
                DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT,
                    List.of("00000019400{", "00000000009{"), StandardCharsets.US_ASCII),
                DatasetOutput.empty("TCATBALF", AMOUNT_LAYOUT)),
                List.of(), null, 0, List.of());

            DiffResult first = DIFFER.compare(caseExpectingOnlyTransact(), twoExtras);
            DiffResult second = DIFFER.compare(caseExpectingOnlyTransact(), twoExtras);

            assertThat(outputDiffs(first)).extracting(Diff::dataset)
                .containsExactly(TRANSACT, "DALYREJS", "TCATBALF");
            assertThat(outputDiffs(first)).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_RECORD, DiffKind.EXTRA_DATASET,
                    DiffKind.EXTRA_DATASET);
            assertThat(first.render()).isEqualTo(second.render());
        }

        @Test
        @DisplayName("A case whose datasets all match still passes: no false failure is introduced")
        void aMatchingCaseStillPasses() {
            assertThat(outputIsClean(DIFFER.compare(caseExpectingOnlyTransact(),
                amountFingerprint("00000019400{")))).isTrue();
        }

        @Test
        @DisplayName("Extra rows in an EXPECTED dataset are still reported, as before")
        void extraRowsInAnExpectedDatasetAreStillReported() {
            Fingerprint oneRowTooMany = Fingerprint.of(List.of(
                DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT,
                    List.of("00000019400{", "00000000009{"), StandardCharsets.US_ASCII)),
                List.of(), null, 0, List.of());

            DiffResult result = DIFFER.compare(caseExpectingOnlyTransact(), oneRowTooMany);

            assertThat(outputDiffs(result)).singleElement()
                .satisfies(diff -> assertThat(diff.kind()).isEqualTo(DiffKind.EXTRA_RECORD));
        }

        @Test
        @DisplayName("A case expecting no records at all still reports what a unit wrote")
        void aCaseExpectingNothingStillReportsOutput() {
            List<EmittedMessage> report =
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE, "REPORT"));
            ParityCase expectsNothing = new ParityCase("CBACT01C", "case01", "read and print only",
                UnitKind.BATCH_JOB, Map.of(), Map.of(), null, null, List.of(), List.of(), 0, report,
                List.of());

            DiffResult result = DIFFER.compare(expectsNothing,
                Fingerprint.of(List.of(DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT,
                    List.of("00000019400{"), StandardCharsets.US_ASCII)), List.of(), null, 0,
                    report));

            assertThat(outputDiffs(result)).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_DATASET);
            assertThat(outputDiffs(result).get(0).actual()).isEqualTo("1 row(s)");
            assertThat(outputDiffs(result).get(0).explanation())
                .as("a case that pins nothing still says so, and the verdict names the datasets it "
                    + "does expect - which here is the empty set")
                .contains("which the case expects nothing from")
                .contains("An empty expectation is a positive assertion that nothing was produced");
        }
    }

    @Nested
    @DisplayName("A normalisation applies only to the datasets it is bound to - F21")
    class NormalisationBinding {
        private static final RecordLayout XREF_LAYOUT = new RecordLayout(50,
            List.of(FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14)));

        private static final String THIRTY_SIX = "4111111111111111" + "000000009" + "00000000011";

        private ParityCase caseOver(final String dataset) {
            return new ParityCase("CBACT03C", "case01", "one cross-reference row", UnitKind.BATCH_JOB,
                Map.of(dataset, DatasetInput.ofRows(List.of(THIRTY_SIX))), Map.of(), null, null,
                List.of(), List.of(new ExpectedRecord(dataset, 0,
                    Map.of("XREF-ACCT-ID", "00000000011"), null)),
                0, List.of(),
                List.of(new DatasetNormalisation(dataset,
                    Normalisation.CARDXREF_FILLER_PAD_36_TO_50)));
        }

        private Fingerprint fingerprintOver(final String dataset) {
            String seeded = new DatasetNormalisation(dataset,
                Normalisation.CARDXREF_FILLER_PAD_36_TO_50).normaliseSeedRow(THIRTY_SIX);
            return Fingerprint.of(List.of(), List.of(DatasetOutput.ofImages(dataset, XREF_LAYOUT,
                List.of(seeded), StandardCharsets.US_ASCII)), null, 0, List.of());
        }

        private ParityCase unpaddedCaseOver(final String dataset) {
            return new ParityCase("CBACT03C", "case01", "one cross-reference row", UnitKind.BATCH_JOB,
                Map.of(dataset, DatasetInput.ofRows(List.of(THIRTY_SIX))), Map.of(), null, null,
                List.of(), List.of(new ExpectedRecord(dataset, 0,
                    Map.of("XREF-ACCT-ID", "00000000011"), null)),
                0, List.of(), List.of());
        }

        private Fingerprint unpaddedFingerprintOver(final String dataset) {
            return Fingerprint.of(List.of(), List.of(DatasetOutput.ofImages(dataset, XREF_LAYOUT,
                List.of(THIRTY_SIX), StandardCharsets.US_ASCII)), null, 0, List.of());
        }

        @ParameterizedTest(name = "{0} is eligible for the cross-reference pad")
        @ValueSource(strings = {"CCXREF", "CXACAIX", "XREFFILE", "XREFFIL1", "CARDXREF"})
        @DisplayName("Every binding key naming the cross-reference cluster is padded, as before")
        void everyCrossReferenceKeyIsStillPadded(final String dataset) {
            ParityCase parityCase = caseOver(dataset);

            DiffResult result = DIFFER.compare(parityCase, fingerprintOver(dataset));

            assertThat(outputIsClean(result)).as("%s must still be padded", dataset).isTrue();
            assertThat(parityCase.normalisations()).singleElement().satisfies(declared -> {
                assertThat(declared.dataset()).isEqualTo(dataset);
                assertThat(declared.kind().copybook()).isEqualTo("CVACT03Y");
                assertThat(declared.normaliseSeedRow(THIRTY_SIX)).hasSize(50);
            });
        }

        @Test
        @DisplayName("An unrelated dataset of the same width is NOT padded, it is reported")
        void anUnrelatedDatasetOfTheSameWidthIsReported() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetNormalisation("TCATBALF",
                    Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                .withMessageContaining("does not describe");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.appliesTo("TCATBALF")).isFalse();

            DiffResult result = DIFFER.compare(unpaddedCaseOver("TCATBALF"),
                unpaddedFingerprintOver("TCATBALF"));

            assertThat(outputIsClean(result)).isFalse();
            assertThat(outputDiffs(result)).anySatisfy(diff ->
                assertThat(diff.kind()).isEqualTo(DiffKind.RECORD_WIDTH_MISMATCH));
        }

        @Test
        @DisplayName("The security pad is bound to USRSEC alone")
        void theSecurityPadIsBoundToUsrsecAlone() {
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.eligibleDatasets())
                .containsExactly("USRSEC");
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.appliesTo("USRSEC")).isTrue();
            assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80.appliesTo("CCXREF")).isFalse();
        }

        @Test
        @DisplayName("The two normalisations share no dataset, so neither can stand in for the other")
        void theTwoNormalisationsShareNoDataset() {
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.eligibleDatasets())
                .doesNotContainAnyElementsOf(
                    Normalisation.USRSEC_FILLER_PAD_57_TO_80.eligibleDatasets());
        }

        @Test
        @DisplayName("Every normalisation names at least one dataset, so none is unusable")
        void everyNormalisationNamesAtLeastOneDataset() {
            for (Normalisation normalisation : Normalisation.values()) {
                assertThat(normalisation.eligibleDatasets())
                    .as("%s", normalisation).isNotEmpty();
                assertThat(normalisation.targetWidth())
                    .as("%s pads upward", normalisation)
                    .isGreaterThan(normalisation.sourceWidth());
            }
        }
    }

    @Nested
    @DisplayName("A fixture name is confined to the fixture directory - F17")
    class FixtureConfinement {
        @ParameterizedTest(name = "\"{0}\" is accepted")
        @ValueSource(strings = {
            "acctdata.txt", "carddata.txt", "cardxref.txt", "custdata.txt", "dailytran.txt",
            "discgrp.txt", "tcatbal.txt", "trancatg.txt", "trantype.txt",
        })
        @DisplayName("Each of the nine fixtures derived from app/data/ASCII is accepted")
        void eachOfTheNineFixturesIsAccepted(final String fixture) {
            DatasetInput input = new DatasetInput(List.of(), fixture, null, null, null, null, null);

            assertThat(input.fixture()).isEqualTo(fixture);
            assertThat(input.fixtureResourcePath()).isEqualTo("fixtures/" + fixture);
        }

        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {
            "../application.yml",
            "../../main/resources/application.yml",
            "/etc/passwd",
            "fixtures/acctdata.txt",
            "./acctdata.txt",
            "sub/acctdata.txt",
            "sub\\acctdata.txt",
            "file:acctdata.txt",
            "http://example.test/acctdata.txt",
            "C:acctdata.txt",
        })
        @DisplayName("A name that is not a bare file name is refused, whatever shape it takes")
        void aNameThatIsNotABareFileNameIsRefused(final String fixture) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), fixture, null, null, null, null, null))
                .withMessageContaining("DatasetInput.fixture must be a bare file name")
                .withMessageContaining("could address something outside it");
        }

        @Test
        @DisplayName("Both barriers hold independently, which is what makes them defence in depth")
        void bothBarriersHoldIndependently() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "../acctdata.txt", null, null, null, null, null))
                .withMessageContaining("must be a bare file name");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "acctdata.dat", null, null, null, null, null))
                .withMessageContaining("not one of the nine fixtures");
        }

        @Test
        @DisplayName("A bare name that is not one of the nine is refused, and the nine are listed")
        void aBareNameOutsideTheWhitelistIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "application.yml", null, null, null, null, null))
                .withMessageContaining("not one of the nine fixtures")
                .withMessageContaining("acctdata.txt");
        }

        @Test
        @DisplayName("Blank and whitespace-padded names are refused with their own diagnostics")
        void blankAndPaddedNamesAreRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "   ", null, null, null, null, null))
                .withMessageContaining("present but blank");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), " acctdata.txt ", null, null, null, null, null))
                .withMessageContaining("padded with whitespace");
        }

        @Test
        @DisplayName("Containment is by construction: the path is composed, never taken from the case")
        void containmentIsByConstruction() {
            for (String fixture : ParityCase.FIXTURE_NAMES) {
                DatasetInput input = new DatasetInput(List.of(), fixture, null, null, null, null, null);

                assertThat(input.fixtureResourcePath())
                    .startsWith(ParityCase.FIXTURE_ROOT)
                    .doesNotContain("..")
                    .isEqualTo(ParityCase.FIXTURE_ROOT + fixture);
            }
        }

        @Test
        @DisplayName("An inline input has no fixture and therefore no resource path")
        void anInlineInputHasNoResourcePath() {
            DatasetInput inline = new DatasetInput(List.of("0000000001"), null, null, null, null, null, null);

            assertThat(inline.fixture()).isNull();
            assertThat(inline.fixtureResourcePath()).isNull();
        }

        @Test
        @DisplayName("The whitelist holds exactly the nine fixtures and is immutable")
        void theWhitelistHoldsExactlyNineAndIsImmutable() {
            assertThat(ParityCase.FIXTURE_NAMES).hasSize(9)
                .allSatisfy(name -> assertThat(name).endsWith(".txt"));
            assertThat(ParityCase.FIXTURE_ROOT).isEqualTo("fixtures/");
        }
    }

    @Nested
    @DisplayName("The shipped case corpus still loads under all four changes")
    class ShippedCaseCorpus {
        private static final String PROGRAM = "COUSR02C";

        private static ObjectMapper strictMapper() {
            return new ObjectMapper();
        }

        private ParityCase load(final String caseId) throws Exception {
            String resource = "parity/" + PROGRAM + "/case" + caseId + ".json";
            try (InputStream stream =
                     getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(stream).as("shipped case %s must be on the test classpath", resource)
                    .isNotNull();
                return strictMapper().readValue(stream, ParityCase.class);
            }
        }

        @ParameterizedTest(name = "case{0}.json still deserializes")
        @ValueSource(strings = {
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
        })
        @DisplayName("All 20 cases deserialize, so no change here invalidated the shipped corpus")
        void allTwentyCasesStillDeserialize(final String caseId) throws Exception {
            ParityCase parityCase = load(caseId);

            assertThat(parityCase.program()).isEqualTo(PROGRAM);
            assertThat(parityCase.caseId()).isEqualTo("case" + caseId);
            assertThat(parityCase.expectedFinalState())
                .as("case%s pins the dataset's rows as the run leaves them", caseId)
                .isNotEmpty();
        }

        @Test
        @DisplayName("Every input seeds from inline rows, so the fixture whitelist cannot affect them")
        void everyInputSeedsFromInlineRows() throws Exception {
            for (int number = 1; number <= 20; number++) {
                final int caseNumber = number;
                ParityCase parityCase = load(String.format("%02d", caseNumber));

                assertThat(parityCase.inputs()).isNotEmpty();
                parityCase.inputs().forEach((dataset, input) -> {
                    assertThat(input.fixture())
                        .as("case%02d input %s names no fixture", caseNumber, dataset).isNull();
                    assertThat(input.fixtureResourcePath())
                        .as("and therefore resolves no resource path").isNull();
                    assertThat(input.rows()).isNotEmpty();
                });
            }
        }

        @Test
        @DisplayName("The 57-byte USRSEC rows remain authorised for the pad they need")
        void theUsrsecRowsRemainAuthorisedForTheirPad() throws Exception {
            for (int number = 1; number <= 20; number++) {
                ParityCase parityCase = load(String.format("%02d", number));

                assertThat(parityCase.normalisations())
                    .as("case%02d declares the security pad", number)
                    .extracting(DatasetNormalisation::kind)
                    .contains(Normalisation.USRSEC_FILLER_PAD_57_TO_80);

                for (Map.Entry<String, DatasetInput> input : parityCase.inputs().entrySet()) {
                    for (String row : input.getValue().rows()) {
                        assertThat(row.length()).isEqualTo(57);
                        assertThat(parityCase.normalisations()).anySatisfy(normalisation -> {
                            assertThat(normalisation.dataset())
                                .as("a declared normalisation is bound to dataset %s",
                                    input.getKey())
                                .isEqualTo(input.getKey());
                            assertThat(normalisation.kind().appliesTo(input.getKey()))
                                .as("and covers it")
                                .isTrue();
                        });
                    }
                }
            }
        }

        @Test
        @DisplayName("A dataset the corpus expects but no normalisation covers needs no padding")
        void theScreenDatasetNeedsNoPadding() throws Exception {
            for (int number = 1; number <= 20; number++) {
                final int caseNumber = number;
                ParityCase parityCase = load(String.format("%02d", caseNumber));

                parityCase.expectedFinalState().stream()
                    .filter(record -> !"USRSEC".equals(record.dataset()))
                    .forEach(record -> {
                        assertThat(Normalisation.USRSEC_FILLER_PAD_57_TO_80
                            .appliesTo(record.dataset())).isFalse();
                        assertThat(record.expectedBytes())
                            .as("case%02d %s row %d pins fields, not a record image",
                                caseNumber, record.dataset(), record.rowIndex())
                            .isNull();
                    });
            }
        }

        @Test
        @DisplayName("Every USRSEC expectation is already 80 bytes, so it needs no pad either")
        void everyUsrsecExpectationIsAlreadyFullWidth() throws Exception {
            for (int number = 1; number <= 20; number++) {
                final int caseNumber = number;
                load(String.format("%02d", caseNumber)).expectedFinalState().stream()
                    .filter(record -> "USRSEC".equals(record.dataset()))
                    .forEach(record -> assertThat(record.expectedBytes())
                        .as("case%02d USRSEC row %d", caseNumber, record.rowIndex())
                        .hasSize(80));
            }
        }
    }
}
