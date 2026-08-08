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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the four ways the parity judge could report a clean comparison over output that is not
 * clean.
 *
 * <h2>Why these four in particular</h2>
 * A judge has two kinds of defect and they are not equally serious. A false failure is loud: somebody
 * investigates it within the hour. A <strong>false pass</strong> is silent, and the whole point of the
 * diff-count-equals-zero gate is that nobody looks any further once it reads zero. Each case below is a
 * way the judge previously returned zero over output the COBOL would not have produced:
 *
 * <ul>
 *   <li>Two signed zoned images of one value - {@code +0} and {@code -0}, or an unsigned digit against
 *       a positive overpunch - compared equal, because after the byte comparison failed the judge fell
 *       back to comparing numbers. The parity contract is the record's bytes.</li>
 *   <li>A dataset the case never mentions was invisible, because the extra-record pass walked the
 *       expectations and nothing in a set of expectations refers to a dataset it does not mention.</li>
 *   <li>A width normalisation was selected on its width pair alone, so a case declaring the
 *       cross-reference pad had any 36-byte dataset padded to 50 and compared against the wrong
 *       copybook.</li>
 *   <li>A fixture name was any text at all, so a case could name a resource outside the fixture
 *       directory.</li>
 * </ul>
 *
 * <p>Each test therefore asserts a <em>non-zero</em> diff count where the judge used to return zero,
 * and asserts the surviving true-negative alongside it - because a judge that reports everything is as
 * useless as one that reports nothing.
 */
@DisplayName("Parity judge correctness - the four ways it could have passed dirty output")
class ParityJudgeCorrectnessTest {

    /** The dataset a signed-field case writes to. */
    private static final String TRANSACT = "TRANSACT";

    /** A twelve-character {@code PIC S9(10)V99} span, the estate's only monetary shape. */
    private static final FieldSpan AMOUNT =
        FieldSpan.signedScaled("TRAN-AMT", 0, 10, 2);

    /** A layout of exactly that one span, so a row is twelve bytes. */
    private static final RecordLayout AMOUNT_LAYOUT = new RecordLayout(12, List.of(AMOUNT));

    /** The differ under test, over the fixture code page. */
    private static final FieldDiffer DIFFER = FieldDiffer.forCharset(StandardCharsets.US_ASCII);

    /**
     * Builds a case expecting one {@code TRAN-AMT} image in row 0 of {@code TRANSACT}.
     *
     * @param expectedImage the expectation, written as the case author would write it
     * @return the case
     */
    private static ParityCase amountCase(final String expectedImage) {
        return new ParityCase("CBACT04C", "case01", "one signed amount", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null,
            List.of(new ExpectedRecord(TRANSACT, 0, Map.of("TRAN-AMT", expectedImage), null)),
            List.of(), 0, List.of(), List.of());
    }

    /**
     * Builds a fingerprint holding one {@code TRAN-AMT} image in row 0 of {@code TRANSACT}.
     *
     * @param observedImage what the unit wrote
     * @return the fingerprint
     */
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

            assertThat(result.isClean())
                .as("expected %s against observed %s must not pass", expected, observed)
                .isFalse();
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.entries()).singleElement().satisfies(diff -> {
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

            // Both images decode to 0.00, so a numeric comparison finds nothing. The explanation has
            // to say that plainly, or it reads as a self-contradiction.
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.entries().get(0).explanation())
                .contains("right value but the wrong bytes")
                .contains("overpunch")
                .contains("0.00");
        }

        @Test
        @DisplayName("A genuinely different value is still reported with both numbers named")
        void aGenuinelyDifferentValueIsStillReported() {
            DiffResult result = DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("00000019500{"));

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.entries().get(0).explanation())
                .contains("denoting")
                .contains("1940.00")
                .contains("1950.00")
                .doesNotContain("right value but the wrong bytes");
        }

        @Test
        @DisplayName("Identical images still pass: the fix adds no false failure")
        void identicalImagesStillPass() {
            assertThat(DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("00000019400{")).isClean()).isTrue();
            assertThat(DIFFER.compare(amountCase("00000019400}"),
                amountFingerprint("00000019400}")).isClean()).isTrue();
            assertThat(DIFFER.compare(amountCase("000000194000"),
                amountFingerprint("000000194000")).isClean()).isTrue();
        }

        @Test
        @DisplayName("A decimal literal is encoded to the one image a COBOL store produces, then compared")
        void aDecimalLiteralIsComparedAsTheImageItEncodesTo() {
            // A literal states a value, and a COBOL store of that value into PIC S9(10)V99 produces
            // exactly one image - so stating the value and stating that image are the same statement,
            // and the literal is encoded through the codec the implementation writes with and then
            // compared as bytes. The strictness is not an inconvenience to be carved out: a literal
            // compared numerically would accept the unsigned rendering below, and would accept a
            // positive zero for a "-0.00" expectation, which are the two byte differences the signed
            // codec exists to preserve. The judge would then be blind to exactly the defects it is
            // here to catch.
            assertThat(DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("00000019400{")).isClean())
                .as("the canonical image of 1940.00 is the positive-overpunch form")
                .isTrue();
            assertThat(DIFFER.compare(amountCase("-1940.00"),
                amountFingerprint("00000019400}")).isClean())
                .as("and a negative literal encodes to the negative overpunch")
                .isTrue();

            DiffResult zoneF = DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("000000194000"));
            assertThat(zoneF.isClean())
                .as("the unsigned zone-F rendering is not the image a COBOL store produces")
                .isFalse();
            assertThat(zoneF.entries()).singleElement().satisfies(diff -> {
                assertThat(diff.kind()).isEqualTo(DiffKind.VALUE_MISMATCH);
                assertThat(diff.explanation())
                    .contains("the canonical image of the literal")
                    .contains("right value but the wrong bytes");
            });

            assertThat(DIFFER.compare(amountCase("1940.00"),
                amountFingerprint("00000019500{")).isClean())
                .as("and a genuinely different quantity is reported as before")
                .isFalse();
        }

        @Test
        @DisplayName("A negative-zero literal is not satisfied by a positive zero, which decodes alike")
        void aNegativeZeroLiteralIsNotSatisfiedByAPositiveZero() {
            assertThat(DIFFER.compare(amountCase("-0.00"),
                amountFingerprint("00000000000}")).isClean())
                .as("the literal reader honours a leading minus on an all-zero value")
                .isTrue();

            DiffResult flipped = DIFFER.compare(amountCase("-0.00"),
                amountFingerprint("00000000000{"));
            assertThat(flipped.count()).isEqualTo(1);
            assertThat(flipped.entries().get(0).explanation())
                .contains("differ in SIGN")
                .contains("opposite overpunch of a zero");
        }

        @Test
        @DisplayName("An undecodable observation is still reported as undecodable, not as a mismatch")
        void anUndecodableObservationIsStillReportedAsSuch() {
            DiffResult result = DIFFER.compare(amountCase("00000019400{"),
                amountFingerprint("0000001940**"));

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.entries().get(0).kind()).isEqualTo(DiffKind.UNDECODABLE_FIELD);
        }
    }

    @Nested
    @DisplayName("Output the case never mentions is reported - F20")
    class UnexpectedOutput {

        /** A case that expects one row in TRANSACT and says nothing about any other dataset. */
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

            // Previously zero: nothing in the expectations referred to DALYREJS, so the pass over them
            // could never reach it. It is now reached, because the pass is driven by what the unit
            // produced rather than by the expectation keys.
            //
            // One finding, not one per row. "The unit wrote to an output this case is not about" is a
            // single defect however many rows followed, so the count carries the scale - 2 row(s) here
            // - rather than the entries multiplying with the unit's output volume. That is the same
            // rule that makes a missing record one difference and not one per pinned field, and it
            // keeps the diff count a count of things wrong.
            assertThat(result.isClean()).isFalse();
            assertThat(result.entries()).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_DATASET);
            assertThat(result.entries().get(0).dataset()).isEqualTo("DALYREJS");
            assertThat(result.entries().get(0).actual()).isEqualTo("2 row(s)");
            assertThat(result.entries().get(0).explanation())
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

            // The one shape a row-driven check can never see: a unit that opened an output the COBOL
            // does not have, and wrote nothing to it.
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.entries().get(0).kind()).isEqualTo(DiffKind.EXTRA_DATASET);
            assertThat(result.entries().get(0).explanation()).contains("0 row(s)");
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

            // The fingerprint declares DALYREJS first, yet TRANSACT's unaccounted row is reported
            // first: the datasets the case addresses are examined before the datasets it never
            // mentions, so the rendered order does not depend on the order the unit happened to open
            // its outputs in.
            assertThat(first.entries()).extracting(Diff::dataset)
                .containsExactly(TRANSACT, "DALYREJS", "TCATBALF");
            assertThat(first.entries()).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_RECORD, DiffKind.EXTRA_DATASET,
                    DiffKind.EXTRA_DATASET);
            assertThat(first.render()).isEqualTo(second.render());
        }

        @Test
        @DisplayName("A case whose datasets all match still passes: no false failure is introduced")
        void aMatchingCaseStillPasses() {
            assertThat(DIFFER.compare(caseExpectingOnlyTransact(),
                amountFingerprint("00000019400{")).isClean()).isTrue();
        }

        @Test
        @DisplayName("Extra rows in an EXPECTED dataset are still reported, as before")
        void extraRowsInAnExpectedDatasetAreStillReported() {
            Fingerprint oneRowTooMany = Fingerprint.of(List.of(
                DatasetOutput.ofImages(TRANSACT, AMOUNT_LAYOUT,
                    List.of("00000019400{", "00000000009{"), StandardCharsets.US_ASCII)),
                List.of(), null, 0, List.of());

            DiffResult result = DIFFER.compare(caseExpectingOnlyTransact(), oneRowTooMany);

            assertThat(result.entries()).singleElement()
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

            assertThat(result.entries()).extracting(Diff::kind)
                .containsExactly(DiffKind.EXTRA_DATASET);
            assertThat(result.entries().get(0).actual()).isEqualTo("1 row(s)");
            assertThat(result.entries().get(0).explanation())
                .as("a case that pins nothing still says so, and the verdict names the datasets it "
                    + "does expect - which here is the empty set")
                .contains("which the case expects nothing from")
                .contains("An empty expectation is a positive assertion that nothing was produced");
        }
    }

    @Nested
    @DisplayName("A normalisation applies only to the datasets it is bound to - F21")
    class NormalisationBinding {

        /** The cross-reference layout: 50 bytes, of which the fixture supplies 36. */
        private static final RecordLayout XREF_LAYOUT = new RecordLayout(50,
            List.of(FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14)));

        /** A 36-byte row, as {@code cardxref.txt} actually holds it. */
        private static final String THIRTY_SIX = "4111111111111111" + "000000009" + "00000000011";

        /** A case over {@code dataset}, seeding the 36-byte row and declaring the pad for it. */
        private ParityCase caseOver(final String dataset) {
            return new ParityCase("CBACT03C", "case01", "one cross-reference row", UnitKind.BATCH_JOB,
                Map.of(dataset, DatasetInput.ofRows(List.of(THIRTY_SIX))), Map.of(), null, null,
                List.of(), List.of(new ExpectedRecord(dataset, 0,
                    Map.of("XREF-ACCT-ID", "00000000011"), null)),
                0, List.of(),
                List.of(new DatasetNormalisation(dataset,
                    Normalisation.CARDXREF_FILLER_PAD_36_TO_50)));
        }

        /**
         * The dataset as the run leaves it, seeded through the declared pad.
         *
         * <p>The pad is applied here, on the seeding side that owns it, so what the comparison meets
         * is a full-width record - never a short row the comparison has to repair.
         */
        private Fingerprint fingerprintOver(final String dataset) {
            String seeded = new DatasetNormalisation(dataset,
                Normalisation.CARDXREF_FILLER_PAD_36_TO_50).normaliseSeedRow(THIRTY_SIX);
            return Fingerprint.of(List.of(), List.of(DatasetOutput.ofImages(dataset, XREF_LAYOUT,
                List.of(seeded), StandardCharsets.US_ASCII)), null, 0, List.of());
        }

        /** The same dataset and expectation, with no pad declared and the short row left alone. */
        private ParityCase unpaddedCaseOver(final String dataset) {
            return new ParityCase("CBACT03C", "case01", "one cross-reference row", UnitKind.BATCH_JOB,
                Map.of(dataset, DatasetInput.ofRows(List.of(THIRTY_SIX))), Map.of(), null, null,
                List.of(), List.of(new ExpectedRecord(dataset, 0,
                    Map.of("XREF-ACCT-ID", "00000000011"), null)),
                0, List.of(), List.of());
        }

        /** The short row exactly as the fixture holds it, on the final-state channel. */
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

            assertThat(result.isClean()).as("%s must still be padded", dataset).isTrue();
            assertThat(parityCase.normalisations()).singleElement().satisfies(declared -> {
                assertThat(declared.dataset()).isEqualTo(dataset);
                assertThat(declared.kind().copybook()).isEqualTo("CVACT03Y");
                assertThat(declared.normaliseSeedRow(THIRTY_SIX)).hasSize(50);
            });
        }

        @Test
        @DisplayName("An unrelated dataset of the same width is NOT padded, it is reported")
        void anUnrelatedDatasetOfTheSameWidthIsReported() {
            // The failure this binding exists to prevent: a case declaring the cross-reference pad,
            // touching some other dataset that happens to measure 36 against a 50-byte layout, and
            // PASSING because the width pair matched.
            // The binding refuses the declaration itself, which is earlier and louder than refusing
            // the pad when it fires - a case cannot even be written that asks for it.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetNormalisation("TCATBALF",
                    Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                .withMessageContaining("does not describe");
            assertThat(Normalisation.CARDXREF_FILLER_PAD_36_TO_50.appliesTo("TCATBALF")).isFalse();

            // And with no pad available the 36-byte row is reported for what it is against a 50-byte
            // layout, rather than padded and compared against the wrong copybook.
            DiffResult result = DIFFER.compare(unpaddedCaseOver("TCATBALF"),
                unpaddedFingerprintOver("TCATBALF"));

            assertThat(result.isClean()).isFalse();
            assertThat(result.entries()).anySatisfy(diff ->
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
            DatasetInput input = new DatasetInput(List.of(), fixture, null, null);

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
            // Asserting the STRUCTURAL diagnostic, not merely that something was thrown. The whitelist
            // would refuse every one of these names too, so a test that accepted either message would
            // pass with the structural barrier deleted and the two barriers are not interchangeable:
            // the whitelist says only "not a fixture", which tells a case author nothing about the
            // traversal they just wrote.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), fixture, null, null))
                .withMessageContaining("DatasetInput.fixture must be a bare file name")
                .withMessageContaining("could address something outside it");
        }

        @Test
        @DisplayName("Both barriers hold independently, which is what makes them defence in depth")
        void bothBarriersHoldIndependently() {
            // A traversal is refused structurally even though it is also absent from the whitelist,
            // and a plausible non-fixture is refused by the whitelist even though it is structurally
            // a perfectly ordinary file name. Neither check is doing the other's work.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "../acctdata.txt", null, null))
                .withMessageContaining("must be a bare file name");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "acctdata.dat", null, null))
                .withMessageContaining("not one of the nine fixtures");
        }

        @Test
        @DisplayName("A bare name that is not one of the nine is refused, and the nine are listed")
        void aBareNameOutsideTheWhitelistIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "application.yml", null, null))
                .withMessageContaining("not one of the nine fixtures")
                .withMessageContaining("acctdata.txt");
        }

        @Test
        @DisplayName("Blank and whitespace-padded names are refused with their own diagnostics")
        void blankAndPaddedNamesAreRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), "   ", null, null))
                .withMessageContaining("present but blank");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DatasetInput(List.of(), " acctdata.txt ", null, null))
                .withMessageContaining("padded with whitespace");
        }

        @Test
        @DisplayName("Containment is by construction: the path is composed, never taken from the case")
        void containmentIsByConstruction() {
            // Whatever a case says, the resolved path is the root plus a name from a closed set, so
            // there is no input from which a path outside the root could be built.
            for (String fixture : ParityCase.FIXTURE_NAMES) {
                DatasetInput input = new DatasetInput(List.of(), fixture, null, null);

                assertThat(input.fixtureResourcePath())
                    .startsWith(ParityCase.FIXTURE_ROOT)
                    .doesNotContain("..")
                    .isEqualTo(ParityCase.FIXTURE_ROOT + fixture);
            }
        }

        @Test
        @DisplayName("An inline input has no fixture and therefore no resource path")
        void anInlineInputHasNoResourcePath() {
            DatasetInput inline = new DatasetInput(List.of("0000000001"), null, null, null);

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

        /** The only program with cases at this checkpoint; the other 27 arrive later. */
        private static final String PROGRAM = "COUSR02C";

        /** The mapper the loader uses: strict, so an unknown key is a failure rather than a shrug. */
        private static ObjectMapper strictMapper() {
            return new ObjectMapper();
        }

        /**
         * Loads one shipped case from the test classpath.
         *
         * @param caseId the two-digit case number, as the file names spell it
         * @return the deserialized case
         */
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
            // The one way F21 could have broken the shipped corpus: these cases seed USRSEC with
            // 57-byte rows and rely on the declared normalisation to supply CSUSR01Y's trailing
            // FILLER PIC X(23). Binding the normalisation to a dataset set had to keep USRSEC inside
            // it, and this asserts that against the shipped files rather than against the enum alone.
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
            // COUSR2A is the screen projection. No normalisation names it, which under F21 means it
            // can never be padded - so this asserts the corpus never asks for that, by pinning fields
            // rather than a record image. Were that to change, F21 would report it rather than pad it
            // against the wrong copybook, which is the entire point of the binding.
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
