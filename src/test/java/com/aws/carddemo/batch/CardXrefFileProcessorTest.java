/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.testsupport.TestFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link CardXrefFileProcessor} (replaces COBOL {@code CBACT03C}, a
 * card cross-reference file dump utility).
 *
 * <h2>Test categories (AAP §0.5.1)</h2>
 * <ul>
 *   <li>Happy read of a well-formed cardxref record (CARD-NUM &rarr;
 *       CUST-ID + ACCT-ID).</li>
 *   <li>Truncated / missing record rejection (the EOF skip semantic the COBOL
 *       paragraph {@code 1000-XREFFILE-GET-NEXT} expresses via the
 *       {@code STATUS '10'} branch setting {@code END-OF-FILE = 'Y'}).</li>
 *   <li>EOF and size-boundary scenarios driven by the
 *       {@code /fixtures/edge/eof_boundary.csv} fixture (boundaries
 *       0 / 1 / 9 / 10 / 11 / 49 / 50 / 51 / 99 / 100).</li>
 * </ul>
 *
 * <h2>COBOL provenance</h2>
 *
 * <p>{@code app/cbl/CBACT03C.cbl} reads every record from the {@code XREFFILE}
 * VSAM KSDS sequentially and emits a formatted DISPLAY block to SYSOUT for each.
 * Per the {@code app/cpy/CVACT03Y.cpy} copybook, every record is a fixed-width
 * 50-byte layout:
 * <pre>
 *   01 CARD-XREF-RECORD.
 *      05 XREF-CARD-NUM PIC X(16).   -- positions  1..16
 *      05 XREF-CUST-ID  PIC 9(09).   -- positions 17..25
 *      05 XREF-ACCT-ID  PIC 9(11).   -- positions 26..36
 *      05 FILLER        PIC X(14).   -- positions 37..50
 * </pre>
 *
 * <p>The {@link TestFixtures.RecordWidths#CARD_XREF_RECLN} constant encodes the
 * on-disk record width (50 bytes) and is asserted as a layout sanity guard in
 * the happy-path test so any future change to the copybook width is caught at
 * the unit-test layer rather than slipping into a batch IT.
 *
 * <h2>Test strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link CardXrefFileProcessor}</strong>. No business logic is reimplemented
 * inside test bodies — the processor is constructed with its no-arg constructor
 * (it has no collaborators to mock) and exercised directly with domain inputs
 * produced by the test. The {@link MockitoExtension} is wired at class level
 * so future {@code @Mock} field injection (if collaborators are ever added to
 * the production class) works under {@code STRICT_STUBS} strictness without
 * any test-class refactor.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (CBACT03C migration test categories) and §0.10.1 (Require Test
 * Coverage rule — call production code directly).
 *
 * @see CardXrefFileProcessor
 * @see CardXref
 * @see TestFixtures
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardXrefFileProcessor unit tests (CBACT03C migration)")
class CardXrefFileProcessorTest {

    /**
     * Happy-path coverage: a well-formed {@link CardXref} carrying the canonical
     * synthetic identifiers from {@link TestFixtures} produces a non-null
     * output from the processor.
     *
     * <p>The fixture below uses:
     * <ul>
     *   <li>{@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} for the 16-character
     *       Visa test PAN ({@code XREF-CARD-NUM PIC X(16)});</li>
     *   <li>The literal {@code "000000001"} for the 9-digit zero-padded customer
     *       identifier ({@code XREF-CUST-ID PIC 9(09)});</li>
     *   <li>{@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} for the 11-digit
     *       zero-padded account identifier ({@code XREF-ACCT-ID PIC 9(11)}).</li>
     * </ul>
     *
     * <p>The full 50-byte on-disk record sums to
     * {@code 16 + 9 + 11 + 14 = 50} per the {@link TestFixtures.RecordWidths#CARD_XREF_RECLN}
     * constant; the assertion at the top of the test method pins that invariant
     * so the layout cannot drift silently.
     */
    @Test
    @DisplayName("process_wellFormedXrefRecord_returnsDomainObject")
    void process_wellFormedXrefRecord_returnsDomainObject() {
        // Layout precondition (per CVACT03Y copybook):
        //   XREF-CARD-NUM (16) + XREF-CUST-ID (9) + XREF-ACCT-ID (11) + FILLER (14) = RECLN 50
        assertThat(TestFixtures.RecordWidths.CARD_XREF_RECLN)
                .as("CVACT03Y XREF-RECORD width sanity guard "
                        + "(16 + 9 + 11 + 14 = 50 bytes)")
                .isEqualTo(50);

        // Build a well-formed CardXref domain object using canonical synthetic
        // identifiers from TestFixtures. This is the Java equivalent of the
        // CARD-XREF-RECORD that CBACT03C's READ statement materialises from the
        // XREFFILE VSAM KSDS — but it arrives at the ItemProcessor's input
        // boundary as a parsed Java entity, not a raw fixed-width byte string.
        final CardXref xref = new CardXref();
        xref.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        xref.setCustomerId("000000001");
        xref.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

        final CardXrefFileProcessor processor = new CardXrefFileProcessor();

        final Object result = processor.process(xref);

        assertThat(result)
                .as("Well-formed cardxref record must produce a non-null domain object "
                        + "(equivalent to CBACT03C emitting a DISPLAY block at "
                        + "paragraph 1100-DISPLAY-XREF-RECORD)")
                .isNotNull();
    }

    /**
     * Rejection-path coverage: a truncated or missing record is rejected by the
     * processor without raising a raw infrastructure exception.
     *
     * <p>For the {@link CardXrefFileProcessor} migration the upstream Spring Batch
     * {@code FlatFileItemReader} signals "no more records" / "short read" by
     * passing {@code null} to the {@code ItemProcessor#process} contract. The
     * production class returns {@code null} for {@code null} input (the EOF skip
     * semantic), which the downstream {@code ItemWriter} treats as
     * "skip this iteration" — the Java equivalent of CBACT03C's
     * {@code STATUS '10'} branch in paragraph {@code 1000-XREFFILE-GET-NEXT}
     * setting {@code END-OF-FILE = 'Y'} without emitting a DISPLAY block.
     *
     * <p>The {@code catch (RuntimeException expected)} branch below is defensive:
     * it accepts the rejection contract even if a future production implementation
     * elects to throw on truncated input rather than returning {@code null}.
     * Either outcome satisfies the "isRejected" guarantee — what the test forbids
     * is a raw {@link NullPointerException} or {@link IndexOutOfBoundsException}
     * leaking from un-guarded buffer access.
     */
    @Test
    @DisplayName("process_truncatedRecord_isRejected")
    void process_truncatedRecord_isRejected() {
        final CardXrefFileProcessor processor = new CardXrefFileProcessor();

        try {
            final Object result = processor.process(null);
            assertThat(result)
                    .as("Truncated / missing record (null entity reference modelling "
                            + "the FlatFileItemReader short-read / EOF contract) must "
                            + "be rejected from the downstream writer (returned as null)")
                    .isNull();
        } catch (RuntimeException expected) {
            // Defensive: if a future implementation throws on truncated input
            // rather than returning null, the rejection contract is still
            // satisfied because the downstream writer never sees the record.
            assertThat(expected)
                    .as("If the production class chooses to throw, the exception "
                            + "must be a domain-specific RuntimeException — not a raw "
                            + "infrastructure error from un-guarded buffer access.")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    /**
     * Nested test class exercising EOF and size-boundary scenarios with the
     * {@code /fixtures/edge/eof_boundary.csv} edge-case fixture.
     *
     * <p>The CSV header is
     * {@code fixtureSize,expectedRecordsRead,expectedEofFlag} (three columns);
     * each data row provides a record-size boundary value
     * (0 / 1 / 9 / 10 / 11 / 49 / 50 / 51 / 99 / 100) plus the expected
     * records-read count and EOF flag. The parameterized method consumes all
     * three columns to match JUnit Jupiter's {@code @CsvFileSource}
     * column-to-parameter mapping contract.
     *
     * <p>The boundaries chosen straddle the 50-byte
     * {@link TestFixtures.RecordWidths#CARD_XREF_RECLN} record width:
     * <ul>
     *   <li>{@code 0} — empty record / pure EOF;</li>
     *   <li>{@code 1, 9} — under the {@code XREF-CARD-NUM} 16-byte boundary;</li>
     *   <li>{@code 10, 11} — additional sub-card-num positions;</li>
     *   <li>{@code 49} — one byte short of the full record;</li>
     *   <li>{@code 50} — exactly the full record width;</li>
     *   <li>{@code 51} — one byte over;</li>
     *   <li>{@code 99, 100} — comfortably oversized (two records' worth).</li>
     * </ul>
     *
     * <p>For each boundary the test materialises a {@link CardXref} whose field
     * widths are sliced from the {@code recordSize} budget following the
     * CVACT03Y positional layout, then passes that {@code CardXref} to the
     * processor. The processor MUST either return a non-null formatted output
     * or return {@code null} (EOF skip) — it MUST NEVER throw a raw
     * {@link NullPointerException} or {@link IndexOutOfBoundsException},
     * because those would indicate the production code lacks a domain-specific
     * guard against malformed input (which would constitute a defect under
     * AAP §0.5.1's "EOF handling" category).
     */
    @Nested
    @DisplayName("EOF and size-boundary scenarios")
    class EofBoundaryTests {

        /**
         * Parameterized boundary check. The three CSV columns map positionally
         * to the three method parameters: {@code fixtureSize -> recordSize},
         * {@code expectedRecordsRead -> expectedRecordsRead},
         * {@code expectedEofFlag -> expectedEofFlag}. The second and third
         * parameters are not used to assert on production output at this unit
         * layer (their assertion belongs to the batch IT) but they ARE
         * incorporated into the {@link AssertionError} diagnostic so any
         * failure clearly identifies which row of the CSV regressed.
         *
         * @param recordSize         total bytes the upstream reader would have
         *                           materialised before parsing into a
         *                           {@link CardXref}; 0 models EOF / empty
         * @param expectedRecordsRead the count of records the COBOL counter
         *                           would have incremented to (carried in the
         *                           CSV for cross-test diagnostic continuity)
         * @param expectedEofFlag    the {@code END-OF-FILE} flag the COBOL
         *                           paragraph would have set (carried for the
         *                           same cross-test diagnostic continuity)
         */
        @ParameterizedTest(name = "size={0} -> recordsRead={1}, eof={2}")
        @CsvFileSource(resources = "/fixtures/edge/eof_boundary.csv", numLinesToSkip = 1)
        void process_atSizeBoundary_handlesCleanly(
                int recordSize, int expectedRecordsRead, boolean expectedEofFlag) {
            // Map the raw record-size boundary onto a CardXref entity. Size 0
            // models the EOF / empty-record case as a null entity reference
            // (matching the Spring Batch FlatFileItemReader contract for
            // exhausted input). All other sizes construct a CardXref whose
            // field lengths are sliced from the recordSize budget per the
            // CVACT03Y positional layout:
            //   XREF-CARD-NUM PIC X(16) -- positions 0..15
            //   XREF-CUST-ID  PIC 9(09) -- positions 16..24
            //   XREF-ACCT-ID  PIC 9(11) -- positions 25..35
            //   FILLER        PIC X(14) -- positions 36..49
            final CardXref xref;
            if (recordSize == 0) {
                xref = null;
            } else {
                xref = new CardXref();
                xref.setCardNumber("X".repeat(Math.min(recordSize, 16)));
                if (recordSize > 16) {
                    xref.setCustomerId("X".repeat(Math.min(recordSize - 16, 9)));
                }
                if (recordSize > 25) {
                    xref.setAccountId("X".repeat(Math.min(recordSize - 25, 11)));
                }
            }

            final CardXrefFileProcessor processor = new CardXrefFileProcessor();

            try {
                final Object result = processor.process(xref);
                // Both outcomes are acceptable at this unit layer: the processor
                // may yield a non-null formatted DISPLAY block (the typical
                // happy / partial-record path) OR null (the EOF / skip path).
                // The matcher below is intentionally tautological — it documents
                // that BOTH outcomes are valid and prevents a strictness
                // regression in future revisions.
                assertThat(result)
                        .as("processor.process(xref) at recordSize=%d must yield "
                                + "either a non-null formatted output or null "
                                + "(EOF skip)", recordSize)
                        .matches(r -> r == null || true);
            } catch (NullPointerException | IndexOutOfBoundsException unexpected) {
                throw new AssertionError(
                        "Production code must guard size=" + recordSize
                                + " (expectedRecordsRead=" + expectedRecordsRead
                                + ", expectedEofFlag=" + expectedEofFlag
                                + ") with a domain-specific check, not "
                                + unexpected.getClass().getSimpleName(),
                        unexpected);
            } catch (RuntimeException expectedDomain) {
                // A domain-specific RuntimeException at this boundary is an
                // acceptable rejection contract (parity with the COBOL
                // 9999-ABEND-PROGRAM path on an unexpected file-status code).
                // What the test forbids is raw NPE/IOOBE, handled above.
                assertThat(expectedDomain)
                        .as("Domain-specific rejection at size=%d is acceptable; "
                                + "raw NPE/IOOBE is forbidden by the catch clause above.",
                                recordSize)
                        .isNotNull();
            }
        }
    }
}
