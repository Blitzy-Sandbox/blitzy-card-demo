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

// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only,
// never JUnit 4 / Vintage). @Test marks each of the four parity test
// methods; @DisplayName carries the human-readable scenario name on the
// class and each test method (per AAP §0.10.6 naming convention);
// @ExtendWith wires the MockitoExtension below.
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito 5 JUnit Jupiter integration (AAP §0.6.1 — BOM-managed by
// spring-boot-starter-test 3.3.5). MockitoExtension activates STRICT_STUBS
// strictness (AAP §0.10.1: "Mockito strictness is STRICT_STUBS ... unused
// stubs raise UnnecessaryStubbingException"). Although this particular
// test class declares no @Mock fields (the system under test is pure-Java
// in-memory merge logic with no external collaborators), the extension is
// retained both as the project-wide test-class convention and as a
// future-proofing seam should a later production refactor introduce a
// boundary collaborator (e.g., a metrics counter or a record-format
// validator) that must be mocked under the Require Test Coverage rule.
import org.mockito.junit.jupiter.MockitoExtension;

// Java standard library collections (AAP §0.6.1 — JDK 17 runtime).
// List<String> is the parameter and return type of the system under test's
// merge() entry point: each element represents one CVTRA05Y.cpy fixed-width
// TRAN-RECORD (RECLN 350; TRAN-ID at positions 1-16, PIC X(16)) — one for
// the POSTED input stream, one for the SYSTRAN input stream, per the two
// concatenated DD statements in COMBTRAN.jcl (SORTIN DSN=...TRANSACT.BKUP +
// DSN=...SYSTRAN). List.of(...) produces immutable fixture lists, ensuring
// the production code cannot mutate the test inputs.
import java.util.List;

// AssertJ fluent assertion library (AAP §0.10.10 — AssertJ exclusively).
// Static-imported assertThat is used for every assertion in this class:
//   - hasSize(int)              -- collection cardinality (merge output size)
//   - isEmpty()                 -- empty-list invariant
//   - containsExactly(T...)     -- element-by-element equality with ordering
//   - isGreaterThanOrEqualTo(...) -- lexicographic ordering of TRAN-IDs
// AssertJ is preferred over JUnit's Assertions or Hamcrest matchers for
// chainable, expressive failure messages.
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link CombineTransactionsProcessor} — the Java migration of the
 * {@code COMBTRAN.jcl} DFSORT step (pure sort/merge, no COBOL program).
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/jcl/COMBTRAN.jcl} step {@code STEP05R}:
 * <pre>
 *     //STEP05R  EXEC PGM=SORT
 *     //SORTIN   DD DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
 *     //         DD DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
 *     //SYMNAMES DD *
 *     TRAN-ID,1,16,CH
 *     //SYSIN    DD *
 *      SORT FIELDS=(TRAN-ID,A)
 * </pre>
 *
 * <p>The two SORTIN DD statements are concatenated by JES and presented to
 * DFSORT as a single logical input stream. DFSORT then sorts the combined
 * stream ascending by the 16-byte character field at positions 1–16 (the
 * {@code TRAN-ID} from {@code app/cpy/CVTRA05Y.cpy}) and writes the result
 * to {@code SORTOUT} (DSN {@code TRANSACT.COMBINED}). The downstream
 * {@code STEP10 EXEC PGM=IDCAMS} REPRO step then loads the sorted file
 * into the {@code TRANSACT.VSAM.KSDS} master.
 *
 * <p>The Java migration replaces the SORT utility with a pure in-memory
 * {@link CombineTransactionsProcessor#merge(List, List)} entry point that
 * receives the two input lists (POSTED + SYSTRAN), produces a single
 * ascending-by-TRAN-ID output list, and lets the surrounding Spring Batch
 * step write that list through a {@code FlatFileItemWriter} (or persist it
 * via the JPA {@code TransactionRepository}) — preserving the JCL contract
 * without re-introducing DFSORT.
 *
 * <h2>Test Categories (AAP §0.5.1)</h2>
 *
 * <ul>
 *   <li><strong>Merge ordering by composite key.</strong> The output must
 *       be sorted ascending by TRAN-ID (positions 1–16, character compare).
 *       Verified by {@link #merge_orderedInputs_preservesAscendingTranId()}
 *       (inputs already in ascending order — invariant must be preserved)
 *       and {@link #merge_unorderedInputs_producesAscendingOutput()}
 *       (inputs in reverse order — invariant must be restored).</li>
 *
 *   <li><strong>Dedup behaviour.</strong> TRAN-IDs are globally unique by
 *       construction in the CardDemo system (POSTTRAN uses 16-byte
 *       suffixed IDs; INTCALC uses {@code PARM-DATE + 6-digit suffix}); the
 *       DFSORT step does NOT specify {@code SUM FIELDS=NONE} (which would
 *       dedup), so the migrated processor must also preserve all input
 *       records. The empty-list case in
 *       {@link #merge_emptyInputs_producesEmptyOutput()} establishes the
 *       "no records dropped on the floor" baseline; the
 *       {@link #merge_oneEmptyInput_returnsTheOther()} case extends that
 *       baseline to the asymmetric input case.</li>
 *
 *   <li><strong>Key-collision handling.</strong> If two records share a
 *       TRAN-ID (a programming error in the upstream generators — POSTTRAN
 *       and INTCALC produce disjoint ID spaces by construction), the
 *       processor's behaviour must be stable and deterministic.
 *       The pairwise {@code prev <= curr} assertion across positions in
 *       {@link #merge_orderedInputs_preservesAscendingTranId()} and
 *       {@link #merge_unorderedInputs_producesAscendingOutput()} tolerates
 *       equality (DFSORT's {@code FIELDS=(TRAN-ID,A)} is a stable sort that
 *       preserves equal-key input order) — the test thus accepts any
 *       deterministic stable ordering without re-implementing it.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Discipline (AAP §0.10.1)</h2>
 *
 * <p>This test class drives the real {@link CombineTransactionsProcessor}
 * — it never re-implements the sort comparator. The assertions verify
 * an <em>invariant</em> of the output (ascending pairwise ordering) rather
 * than re-computing the expected output by calling
 * {@code Collections.sort(...)} or {@code Stream.sorted(...)} in the test
 * body. Re-sorting in the test would re-introduce the sort algorithm we
 * are trying to verify and would mask any bug in the production sort.
 *
 * <p>No mocks are required: the processor's merge method is a pure function
 * over two input lists, with no JPA, no file I/O, no AWS SDK, no clock,
 * and no random-number boundaries to stub. Per AAP §0.10.1, mocks are
 * limited to those external boundaries.
 *
 * <h2>Financial-Precision Note</h2>
 *
 * <p>AAP §0.10.3 mandates BigDecimal HALF_EVEN for monetary calculations;
 * however, this processor performs no monetary arithmetic — it only sorts
 * by the lexicographic TRAN-ID prefix. No BigDecimal assertions are
 * therefore needed in this test class. The TRAN-AMT field
 * ({@code PIC S9(09)V99}, positions 48–60 in {@code CVTRA05Y.cpy}) flows
 * through the merge as opaque bytes and is verified by downstream
 * baseline-parity ITs (see {@link CombineTransactionsBaselineParityIT}).
 *
 * <h2>Scale Note</h2>
 *
 * <p>Unit tests focus on contract correctness, not throughput. The fixtures
 * here are minimal (1–2 records per input list) — full 350-record fixtures
 * driven through the surrounding Spring Batch job are exercised by
 * {@code CombineTransactionsJobIT} and byte-equality is verified by
 * {@code CombineTransactionsBaselineParityIT}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — CombineTransactionsProcessor
 * supporting class for the COMBTRAN.jcl migration),
 * §0.5.1 (File-by-File Test Plan — merge ordering / dedup /
 * key-collision categories),
 * §0.10.1 (Require Test Coverage rule),
 * §0.10.6 (Test Naming and Location — {@code [ClassName]Test.java}),
 * §0.10.7 (Framework Constraint — JUnit 5 + Mockito only).
 *
 * @see CombineTransactionsProcessor
 * @see CombineTransactionsJobIT
 * @see CombineTransactionsBaselineParityIT
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CombineTransactionsProcessor unit tests (COMBTRAN.jcl DFSORT migration)")
class CombineTransactionsProcessorTest {

    /**
     * Width in bytes of one {@code TRAN-RECORD} per {@code app/cpy/CVTRA05Y.cpy}
     * (RECLN 350). Every fixture record produced by {@link #pad(String, int)} is
     * exactly this wide so the production processor sees the same fixed-width
     * layout it would see from a {@code FlatFileItemReader} reading the
     * concatenated POSTED + SYSTRAN inputs.
     */
    private static final int TRAN_RECORD_WIDTH = 350;

    /**
     * Width in bytes of the {@code TRAN-ID} field per {@code app/cpy/CVTRA05Y.cpy}
     * ({@code PIC X(16)}, positions 1–16). This is the sort key declared in
     * {@code COMBTRAN.jcl} ({@code TRAN-ID,1,16,CH} in SYMNAMES,
     * {@code SORT FIELDS=(TRAN-ID,A)} in SYSIN). Tests slice this prefix from
     * the merged output to verify the ascending ordering invariant.
     */
    private static final int TRAN_ID_WIDTH = 16;

    /**
     * Verifies that two single-record input lists, each already in their own
     * trivially-sorted order, produce a 2-record merged output that preserves
     * the ascending {@code TRAN-ID} ordering.
     *
     * <p>Scenario: POSTED carries TRAN-ID {@code "0000000000000001"};
     * SYSTRAN carries TRAN-ID {@code "0000000000000002"}. The merge must
     * place {@code 0000000000000001} before {@code 0000000000000002} in the
     * output (which is also the natural order of the inputs — this test
     * specifically guards against an off-by-one bug that might inadvertently
     * <em>reverse</em> the inputs).
     *
     * <p>The pairwise assertion {@code prev.compareTo(curr) <= 0} (expressed
     * via AssertJ's {@code isGreaterThanOrEqualTo}) is the canonical sort
     * invariant: it tolerates equal keys (stable sort) without re-implementing
     * the production sort algorithm in the test body. Re-sorting the output
     * in the test and comparing equality would violate AAP §0.10.1
     * (Require Test Coverage rule).
     */
    @Test
    @DisplayName("merge_orderedInputs_preservesAscendingTranId")
    void merge_orderedInputs_preservesAscendingTranId() {
        // Arrange — two TRAN-RECORDs already in ascending TRAN-ID order,
        // each padded to the COBOL-mandated 350-byte record width so the
        // production processor sees the same fixed-width layout it would
        // see from a FlatFileItemReader against the COMBTRAN.jcl SORTIN DDs.
        final String tranA = pad("0000000000000001", TRAN_RECORD_WIDTH);
        final String tranB = pad("0000000000000002", TRAN_RECORD_WIDTH);

        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the real production processor; no mocks.
        final List<String> combined = processor.merge(List.of(tranA), List.of(tranB));

        // Assert — output cardinality matches input total (no records dropped),
        // and pairwise TRAN-ID ordering is preserved across every adjacent
        // pair in the output (the sort invariant).
        assertThat(combined)
                .as("Merge of two ordered single-element inputs must produce a 2-record ordered output")
                .hasSize(2);
        for (int i = 1; i < combined.size(); i++) {
            final String prevId = combined.get(i - 1).substring(0, TRAN_ID_WIDTH);
            final String currId = combined.get(i).substring(0, TRAN_ID_WIDTH);
            assertThat(currId)
                    .as("Position %d: ascending TRAN-ID ordering must be preserved", i)
                    .isGreaterThanOrEqualTo(prevId);
        }
    }

    /**
     * Verifies that two single-record input lists in <em>reverse</em> order
     * still produce an ascending-by-{@code TRAN-ID} merged output.
     *
     * <p>Scenario: POSTED carries the larger ID {@code "0000000000000003"};
     * SYSTRAN carries the smaller ID {@code "0000000000000001"}. The merge
     * must place {@code 0000000000000001} before {@code 0000000000000003}
     * regardless of which input list contributed each record. This is the
     * core DFSORT contract: the SORT step is order-of-input-DD-agnostic.
     *
     * <p>This test guards against a defective implementation that might
     * simply concatenate the two inputs in argument order (which would
     * yield {@code [3, 1]} here — failing the pairwise ordering assertion
     * at position 1).
     */
    @Test
    @DisplayName("merge_unorderedInputs_producesAscendingOutput")
    void merge_unorderedInputs_producesAscendingOutput() {
        // Arrange — two TRAN-RECORDs deliberately in reverse argument order
        // (tranA has the larger TRAN-ID than tranB). The processor must still
        // produce an ascending output, exercising the actual sort path.
        final String tranA = pad("0000000000000003", TRAN_RECORD_WIDTH);
        final String tranB = pad("0000000000000001", TRAN_RECORD_WIDTH);

        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the real production processor.
        final List<String> combined = processor.merge(List.of(tranA), List.of(tranB));

        // Assert — output size equals input total (no records dropped) and
        // pairwise ascending TRAN-ID ordering holds across every adjacent
        // pair in the output, regardless of the order in which the inputs
        // were supplied.
        assertThat(combined)
                .as("Merge of two unordered single-element inputs must produce a 2-record ordered output")
                .hasSize(2);
        for (int i = 1; i < combined.size(); i++) {
            final String prevId = combined.get(i - 1).substring(0, TRAN_ID_WIDTH);
            final String currId = combined.get(i).substring(0, TRAN_ID_WIDTH);
            assertThat(currId)
                    .as("Position %d: output must be sorted ascending", i)
                    .isGreaterThanOrEqualTo(prevId);
        }
    }

    /**
     * Verifies the empty-inputs boundary: two empty input lists must produce
     * an empty output list.
     *
     * <p>Scenario: neither POSTED nor SYSTRAN carries any transactions for a
     * given execution day (rare but legitimate — e.g., a holiday with no
     * postings and no system-generated reversals). The downstream
     * {@code STEP10 EXEC PGM=IDCAMS REPRO} would be a no-op, but the SORT
     * step must still produce a valid empty output file (not crash, not throw,
     * not produce a one-record sentinel).
     *
     * <p>This test also guards against an off-by-one bug that might emit a
     * spurious sentinel or header record in the empty case.
     */
    @Test
    @DisplayName("merge_emptyInputs_producesEmptyOutput")
    void merge_emptyInputs_producesEmptyOutput() {
        // Arrange — empty production processor and two empty input lists.
        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the merge with two immutable empty lists.
        final List<String> combined = processor.merge(List.of(), List.of());

        // Assert — the output must be empty (no spurious records, no
        // sentinel, no header line).
        assertThat(combined)
                .as("Merge of two empty inputs must produce an empty output")
                .isEmpty();
    }

    /**
     * Verifies the asymmetric-inputs boundary: when one input list is empty
     * and the other contains records, the merge must return the contents of
     * the non-empty input unchanged (preserving order and not dropping any
     * records).
     *
     * <p>Scenario: POSTED carries a single transaction for the day; SYSTRAN
     * carries nothing (e.g., no interest-calculation records were generated
     * for that cycle). The merged output must equal POSTED exactly — same
     * record, same byte content, no synthetic padding or wrapper.
     *
     * <p>{@code containsExactly(tranA)} verifies element-level equality with
     * positional ordering, asserting both the size (1) and the identity of
     * the single output record in one chain. This is a stronger assertion
     * than {@code hasSize(1)} alone because it also guards against silent
     * corruption of the record contents (e.g., truncation, re-padding, or
     * field re-formatting by the production processor).
     */
    @Test
    @DisplayName("merge_oneEmptyInput_returnsTheOther")
    void merge_oneEmptyInput_returnsTheOther() {
        // Arrange — one fixed-width TRAN-RECORD, one empty input.
        final String tranA = pad("0000000000000001", TRAN_RECORD_WIDTH);

        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the real production processor with a single record on
        // the POSTED side and an empty SYSTRAN side.
        final List<String> combined = processor.merge(List.of(tranA), List.of());

        // Assert — the output is exactly the non-empty input, byte-for-byte
        // and position-for-position (no truncation, no padding, no reordering).
        assertThat(combined)
                .as("Merge of non-empty + empty inputs must return the non-empty input contents")
                .hasSize(1)
                .containsExactly(tranA);
    }

    /**
     * Right-pads a TRAN-ID prefix with ASCII spaces to the requested record
     * width — the same on-disk representation a real {@code FlatFileItemReader}
     * would surface for a {@code CVTRA05Y.cpy} record (RECLN 350) where the
     * 16-byte {@code TRAN-ID} occupies the first 16 characters and the
     * remaining 334 characters are the rest of the TRAN-RECORD (TRAN-TYPE-CD,
     * TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC, TRAN-AMT, ... FILLER).
     *
     * <p>The padding character is ASCII space (0x20) rather than NUL (0x00)
     * to match the EBCDIC-to-ASCII conversion of COBOL low-values, which
     * standard z/OS DFSORT writes as EBCDIC space (0x40) and which the
     * conversion pipeline downstream of {@code app/data/ASCII/*.txt}
     * normalises to ASCII space.
     *
     * <p>This helper deliberately does NOT decode sign-overpunch, parse
     * fixed-width fields, or compute any monetary value — those are
     * production responsibilities per AAP §0.10.1 (Require Test Coverage
     * rule). It only manufactures a string of the correct width with the
     * given TRAN-ID prefix; the production sort key extraction logic must
     * still read positions 1–16 from the result.
     *
     * @param s     the TRAN-ID prefix (typically 16 characters; longer inputs
     *              are truncated, shorter inputs are space-padded)
     * @param width the target record width (350 for CVTRA05Y.cpy)
     * @return a fixed-width string of exactly {@code width} characters whose
     *         first {@code min(s.length(), width)} characters are the input
     *         prefix and whose remaining characters are ASCII spaces
     */
    private static String pad(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        final StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
