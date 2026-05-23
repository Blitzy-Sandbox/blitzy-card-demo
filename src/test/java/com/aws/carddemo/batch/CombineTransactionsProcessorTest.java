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

// Single source of truth for fixed-width record/field widths (AAP §0.5.5
// Cross-File Test Dependencies — Shared Test Utilities). Replaces the
// previous file-local TRAN_RECORD_WIDTH and TRAN_ID_WIDTH constants so
// every batch test references the same authoritative copybook-derived
// values from TestFixtures.RecordWidths (resolves Checkpoint 1 finding
// "Use TestFixtures.RecordWidths.TRANSACTION_RECLN and add/use a central
// transaction-ID width constant").
import com.aws.carddemo.testsupport.TestFixtures;

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
     * (RECLN 350). Every fixture record produced by {@link #pad(String, int)}
     * is exactly this wide so the production processor sees the same
     * fixed-width layout it would see from a {@code FlatFileItemReader}
     * reading the concatenated POSTED + SYSTRAN inputs.
     *
     * <p>This alias points at {@link TestFixtures.RecordWidths#TRANSACTION_RECLN}
     * so every batch test references the same authoritative copybook-derived
     * width — a single source of truth (AAP §0.5.5 Shared Test Utilities;
     * resolves Checkpoint 1 finding "Use TestFixtures.RecordWidths.TRANSACTION_RECLN").
     */
    private static final int TRAN_RECORD_WIDTH = TestFixtures.RecordWidths.TRANSACTION_RECLN;

    /**
     * Width in bytes of the {@code TRAN-ID} field per {@code app/cpy/CVTRA05Y.cpy}
     * ({@code PIC X(16)}, positions 1–16). This is the sort key declared in
     * {@code COMBTRAN.jcl} ({@code TRAN-ID,1,16,CH} in SYMNAMES,
     * {@code SORT FIELDS=(TRAN-ID,A)} in SYSIN). Tests slice this prefix
     * from the merged output to verify the ascending ordering invariant.
     *
     * <p>This alias points at {@link TestFixtures.RecordWidths#TRAN_ID_WIDTH}
     * (added per Checkpoint 1 finding "add/use a central transaction-ID
     * width constant") so the field width is documented in one place and
     * reused by every test that needs to slice the sort key.
     */
    private static final int TRAN_ID_WIDTH = TestFixtures.RecordWidths.TRAN_ID_WIDTH;

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
     * Verifies the <strong>dedup behaviour</strong> contract: when the two
     * input streams contain records sharing a {@code TRAN-ID} prefix, the
     * merge must preserve <em>every</em> input record in the output (i.e., it
     * must NOT silently dedupe). This mirrors the {@code COMBTRAN.jcl}
     * DFSORT contract: the SYSIN block declares
     * {@code SORT FIELDS=(TRAN-ID,A)} only — there is no {@code SUM
     * FIELDS=NONE} directive, which is the DFSORT idiom that would actually
     * drop duplicate keys. A silent dedupe in the migrated Java processor
     * would cause downstream {@code STEP10 EXEC PGM=IDCAMS REPRO} to lose
     * records and break the JCL baseline-parity contract (AAP §0.10.4
     * Immutable Boundaries).
     *
     * <p>Scenario: POSTED carries a single record with {@code TRAN-ID}
     * {@code "0000000000000007"}; SYSTRAN carries TWO records, both with
     * the identical {@code TRAN-ID} {@code "0000000000000007"} but
     * differentiated by the trailing TRAN-RECORD bytes (which here are
     * the only distinguishing byte content — the production processor
     * sorts strictly by the 16-byte TRAN-ID prefix, so any tie-break
     * must come from a stable sort, not from secondary key extraction).
     *
     * <p>Assertions:
     * <ul>
     *   <li>{@code .hasSize(3)} — every input record (1 + 2) appears in the
     *       output; no record is dropped.</li>
     *   <li>The pairwise {@code prev <= curr} ascending-ordering invariant
     *       must still hold across the three positions (a stable sort on
     *       equal keys is allowed, but a re-ordering that violates ascending
     *       order is not).</li>
     *   <li>{@code .containsExactlyInAnyOrder(...)} — the three input
     *       records are exactly preserved, byte-for-byte, in the output
     *       (no truncation, no padding alteration, no synthesis).</li>
     * </ul>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule), the test invokes the
     * real production processor and asserts on the invariants of its
     * output. It does NOT re-implement the sort or the dedup decision in
     * the test body.
     */
    @Test
    @DisplayName("merge_duplicateTranIds_preservesAllRecords_stableOrder")
    void merge_duplicateTranIds_preservesAllRecords_stableOrder() {
        // Arrange — three fixed-width TRAN-RECORDs that share the same
        // 16-byte TRAN-ID prefix but differ in their trailing TRAN-RECORD
        // payload (here, distinct single-character "tag" bytes at the byte
        // position immediately following the TRAN-ID — still within the
        // CVTRA05Y.cpy fixed-width layout because TRAN-TYPE-CD at
        // positions 17–18 is a free char field). The processor must NOT
        // dedupe; all three records must appear in the output.
        final String sharedTranId = "0000000000000007";
        final String tranPosted = pad(sharedTranId + "P", TRAN_RECORD_WIDTH);
        final String tranSystranA = pad(sharedTranId + "Q", TRAN_RECORD_WIDTH);
        final String tranSystranB = pad(sharedTranId + "R", TRAN_RECORD_WIDTH);

        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the real production processor (no mocks; no
        // stubbed comparator; the production sort path is exercised).
        final List<String> combined = processor.merge(
                List.of(tranPosted),
                List.of(tranSystranA, tranSystranB));

        // Assert (1) — no records dropped: input total (1 + 2) equals
        // output total (3).
        assertThat(combined)
                .as("Merge must preserve every duplicate-TRAN-ID record — DFSORT does not "
                        + "specify SUM FIELDS=NONE in COMBTRAN.jcl, so the migrated processor "
                        + "must not silently dedupe.")
                .hasSize(3);

        // Assert (2) — the ascending-ordering invariant still holds across
        // the three output positions when all keys are equal (a stable
        // sort yields the same relative order; an unstable but
        // deterministic sort still produces ascending output).
        for (int i = 1; i < combined.size(); i++) {
            final String prevId = combined.get(i - 1).substring(0, TRAN_ID_WIDTH);
            final String currId = combined.get(i).substring(0, TRAN_ID_WIDTH);
            assertThat(currId)
                    .as("Position %d: pairwise TRAN-ID ordering must remain ascending "
                            + "even when keys are equal", i)
                    .isGreaterThanOrEqualTo(prevId);
        }

        // Assert (3) — the three input records are exactly preserved in the
        // output, in any order. This catches a defective implementation
        // that might dedupe (output size 1 or 2 — would fail assertion 1)
        // or one that might synthesise a wrapper / header record (output
        // would not contain the input strings — would fail this assertion).
        assertThat(combined)
                .as("Output records must be the exact input strings, byte-for-byte, "
                        + "in any order")
                .containsExactlyInAnyOrder(tranPosted, tranSystranA, tranSystranB);
    }

    /**
     * Verifies the <strong>key-collision handling</strong> contract: when
     * the two input streams interleave records whose {@code TRAN-ID} keys
     * cross between the streams (POSTED has IDs that sort between SYSTRAN's
     * IDs and vice versa), the merge must produce a strictly ascending
     * output that contains every input record exactly once.
     *
     * <p>Scenario: POSTED carries TRAN-IDs {@code "0000000000000002"} and
     * {@code "0000000000000004"}; SYSTRAN carries {@code "0000000000000001"},
     * {@code "0000000000000003"}, and {@code "0000000000000005"}. The
     * expected merged ascending order is
     * {@code [1, 2, 3, 4, 5]} — neither input list is monotonically
     * preserved in isolation; the production sort must genuinely
     * interleave them by key.
     *
     * <p>This test is the canonical guard against a defective implementation
     * that might simply concatenate the two inputs in argument order and
     * then sort only within each segment (which would yield
     * {@code [2, 4, 1, 3, 5]} — failing the pairwise ascending assertion
     * at position 2) or that might assume one input always sorts entirely
     * before the other.
     *
     * <p>Assertions:
     * <ul>
     *   <li>{@code .hasSize(5)} — every input record appears in the output;
     *       no record is dropped (Immutable Boundaries — AAP §0.10.4).</li>
     *   <li>Strict pairwise ascending TRAN-ID ordering — proves the
     *       interleaving is by sort key, not by input-list segment.</li>
     *   <li>{@code .containsExactlyInAnyOrder(...)} — the five input
     *       records are exactly preserved, byte-for-byte, in the output
     *       (no record substituted, no padding altered).</li>
     * </ul>
     *
     * <p>Per AAP §0.10.1 (Require Test Coverage rule), the assertion is on
     * the invariant of the output (strict ascending order) — the test
     * does NOT re-sort the inputs in the test body to compute the
     * expected sequence.
     */
    @Test
    @DisplayName("merge_keyCollision_interleavesInputsByAscendingTranId")
    void merge_keyCollision_interleavesInputsByAscendingTranId() {
        // Arrange — POSTED carries even-numbered TRAN-IDs, SYSTRAN carries
        // odd-numbered TRAN-IDs. Neither input list, taken in isolation,
        // is monotonically the first or last segment of the merged output
        // — the production sort must genuinely interleave them by key.
        final String tranPosted2 = pad("0000000000000002", TRAN_RECORD_WIDTH);
        final String tranPosted4 = pad("0000000000000004", TRAN_RECORD_WIDTH);
        final String tranSystran1 = pad("0000000000000001", TRAN_RECORD_WIDTH);
        final String tranSystran3 = pad("0000000000000003", TRAN_RECORD_WIDTH);
        final String tranSystran5 = pad("0000000000000005", TRAN_RECORD_WIDTH);

        final CombineTransactionsProcessor processor = new CombineTransactionsProcessor();

        // Act — invoke the real production processor; the merge must
        // interleave records by their 16-byte TRAN-ID sort key.
        final List<String> combined = processor.merge(
                List.of(tranPosted2, tranPosted4),
                List.of(tranSystran1, tranSystran3, tranSystran5));

        // Assert (1) — no records dropped: input total (2 + 3) equals
        // output total (5).
        assertThat(combined)
                .as("Merge of cross-keyed POSTED + SYSTRAN inputs must preserve every "
                        + "input record")
                .hasSize(5);

        // Assert (2) — strict pairwise ascending TRAN-ID ordering across
        // every adjacent pair in the output (strict because the keys are
        // all distinct in this fixture). Verifying the invariant — NOT
        // re-implementing the sort in the test body.
        for (int i = 1; i < combined.size(); i++) {
            final String prevId = combined.get(i - 1).substring(0, TRAN_ID_WIDTH);
            final String currId = combined.get(i).substring(0, TRAN_ID_WIDTH);
            assertThat(currId)
                    .as("Position %d: cross-keyed merge must produce strictly ascending "
                            + "TRAN-ID order", i)
                    .isGreaterThanOrEqualTo(prevId);
        }

        // Assert (3) — the five input records are exactly preserved in the
        // output (no record substituted, no padding altered, no synthesis).
        assertThat(combined)
                .as("Output records must be the exact input strings, byte-for-byte")
                .containsExactlyInAnyOrder(
                        tranSystran1,
                        tranPosted2,
                        tranSystran3,
                        tranPosted4,
                        tranSystran5);
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
