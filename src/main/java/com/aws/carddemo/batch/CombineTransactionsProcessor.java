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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Java migration of the {@code COMBTRAN.jcl} DFSORT step — combines the
 * daily POSTED transactions backup with the SYSTEM-generated transactions
 * (typically interest postings from {@code INTCALC.jcl}) into a single
 * stream sorted ascending by {@code TRAN-ID}, ready to be loaded into the
 * {@code TRANSACT.VSAM.KSDS} master file by the downstream IDCAMS REPRO
 * step.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/jcl/COMBTRAN.jcl} step {@code STEP05R}:
 * <pre>
 *     //STEP05R  EXEC PGM=SORT
 *     //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
 *     //         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
 *     //SYMNAMES DD *
 *     TRAN-ID,1,16,CH
 *     //SYSIN    DD *
 *      SORT FIELDS=(TRAN-ID,A)
 * </pre>
 *
 * <p>The two SORTIN DD statements are concatenated by JES and presented to
 * DFSORT as a single logical input stream. DFSORT then sorts that combined
 * stream ascending by the 16-byte character field at positions 1–16 (the
 * {@code TRAN-ID} from {@code app/cpy/CVTRA05Y.cpy}) and writes the result
 * to {@code SORTOUT} (DSN {@code TRANSACT.COMBINED}). The downstream
 * {@code STEP10 EXEC PGM=IDCAMS} REPRO step then loads the sorted file
 * into the {@code TRANSACT.VSAM.KSDS} master.
 *
 * <h2>Record Layout</h2>
 *
 * <p>Each input/output element of {@link #merge(List, List)} represents
 * one fixed-width {@code TRAN-RECORD} per {@code app/cpy/CVTRA05Y.cpy}
 * (RECLN = 350). The first field — {@code TRAN-ID PIC X(16)} — occupies
 * positions 1–16 (1-based COBOL convention; positions 0–15 in Java's
 * 0-based {@link String#substring(int, int)} convention) and is the sort
 * key declared in COMBTRAN.jcl's SYMNAMES DD.
 *
 * <h2>Contract</h2>
 *
 * <p>{@link #merge(List, List)} is a pure function:
 * <ul>
 *   <li><strong>Concatenation.</strong> All records from {@code posted}
 *       followed by all records from {@code systran} are gathered into a
 *       single working list. This mirrors the JES-level concatenation of
 *       the two SORTIN DD statements: DFSORT receives one logical input
 *       stream, not two.</li>
 *   <li><strong>Sort.</strong> The combined working list is sorted
 *       ascending by the character sequence at positions 1–16 of each
 *       record (the {@code TRAN-ID}). The sort is <em>stable</em>: when
 *       two records share a key, their relative order from the
 *       concatenation step is preserved. DFSORT's
 *       {@code SORT FIELDS=(TRAN-ID,A)} without an {@code EQUALS=NO}
 *       override behaves the same way, and {@link List#sort(Comparator)}
 *       in Java 17 is guaranteed stable.</li>
 *   <li><strong>No deduplication.</strong> COMBTRAN.jcl's SYSIN does
 *       <em>not</em> include a {@code SUM FIELDS=NONE} clause, so DFSORT
 *       does not collapse duplicate-key records. The Java implementation
 *       must likewise preserve every input record (even in the rare case
 *       of a defective upstream that produces a duplicate TRAN-ID). This
 *       parity guarantee is exercised by
 *       {@code CombineTransactionsProcessorTest}.</li>
 *   <li><strong>Input immutability.</strong> Neither {@code posted} nor
 *       {@code systran} is modified. The caller may pass immutable lists
 *       (e.g., {@link List#of()}); the method copies references into an
 *       internal mutable buffer before sorting.</li>
 *   <li><strong>Output immutability.</strong> The returned list is
 *       wrapped with {@link Collections#unmodifiableList(List)} to match
 *       the project-wide convention established by
 *       {@code com.aws.carddemo.testsupport.FixtureLoader} — callers may
 *       freely iterate but must not mutate the result.</li>
 *   <li><strong>Empty inputs.</strong> Two empty inputs produce an empty
 *       result (no spurious sentinel, no header). One empty input plus a
 *       non-empty input produces a result containing exactly the records
 *       of the non-empty input, sorted by TRAN-ID. DFSORT writes an empty
 *       SORTOUT file in this case; the IDCAMS REPRO is a no-op but does
 *       not fail.</li>
 *   <li><strong>Null safety.</strong> Either input being {@code null}
 *       triggers a fail-fast {@link NullPointerException} via
 *       {@link Objects#requireNonNull(Object, String)}. This is stricter
 *       than DFSORT — which would simply fail with JCL DD-not-allocated
 *       — and surfaces caller-side wiring bugs at the closest possible
 *       boundary.</li>
 * </ul>
 *
 * <h2>Financial Precision Note (AAP §0.10.3)</h2>
 *
 * <p>This processor performs no monetary arithmetic. The TRAN-AMT field
 * ({@code PIC S9(09)V99}, positions 48–60 in CVTRA05Y.cpy) flows through
 * the merge as opaque bytes inside each TRAN-RECORD string and is never
 * decoded, parsed, or recomputed here. Byte-identical preservation of
 * TRAN-AMT (and every other non-key field) is therefore guaranteed by
 * construction — no {@code float}, {@code double}, or {@link java.math.BigDecimal}
 * boundary is crossed.
 *
 * <h2>Minimal Change Clause (AAP §0.10.2)</h2>
 *
 * <p>This class deliberately does <em>not</em>:
 * <ul>
 *   <li>Decode or validate any field other than the TRAN-ID sort key.
 *       Field-level validation belongs in
 *       {@code TransactionValidationProcessor} (the CBTRN01C migration),
 *       not here.</li>
 *   <li>Use {@code @Service}, {@code @Component}, or any Spring stereotype.
 *       The surrounding Spring Batch step (added in a subsequent
 *       checkpoint) will wrap this class behind a {@code Tasklet} or
 *       {@code ItemProcessor} bean — at which point the wiring layer
 *       owns the Spring lifecycle, not this pure-function core.</li>
 *   <li>Read or write files. {@code FlatFileItemReader} and
 *       {@code FlatFileItemWriter} live in the surrounding job
 *       configuration. This class operates on already-materialised
 *       {@code List<String>} inputs so that it remains unit-testable
 *       without I/O mocks (per AAP §0.10.1 — mocks are limited to
 *       genuine external boundaries; an in-memory list is not one).</li>
 *   <li>Log any record content. AAP §0.10.5 prohibits writing financial
 *       data (account numbers, card numbers, balances) to logs at any
 *       level; the safest implementation of that rule is to log nothing
 *       at all from this hot-path component.</li>
 * </ul>
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — supporting class table:
 * "Replaces COMBTRAN.jcl DFSORT step (no COBOL program)"),
 * §0.5.1 (File-by-File Test Plan — merge ordering, dedup, key collision
 * categories owned by {@code CombineTransactionsProcessorTest}),
 * §0.10.1 (Require Test Coverage rule — production class instantiated
 * directly by its test),
 * §0.10.2 (Minimal Change Clause — no abstractions beyond what the
 * migration requires),
 * §0.10.4 (Immutable Boundaries — output record layout preserved
 * byte-for-byte).
 *
 * @see com.aws.carddemo.batch.CombineTransactionsProcessorTest
 */
public class CombineTransactionsProcessor {

    /**
     * Starting offset (inclusive, 0-based) of the {@code TRAN-ID} field
     * within each {@code TRAN-RECORD} per {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>COBOL positions are 1-based; the SYMNAMES DD declares
     * {@code TRAN-ID,1,16,CH}. Translated to Java's 0-based
     * {@link String#substring(int, int)} convention this becomes the
     * half-open range {@code [0, 16)}.
     */
    private static final int TRAN_ID_OFFSET = 0;

    /**
     * End offset (exclusive, 0-based) of the {@code TRAN-ID} field within
     * each {@code TRAN-RECORD} — equivalent to the length-16 declaration
     * in COBOL ({@code PIC X(16)}) and in the COMBTRAN.jcl SYMNAMES DD
     * ({@code TRAN-ID,1,16,CH}).
     */
    private static final int TRAN_ID_END = 16;

    /**
     * Merges the daily POSTED transactions with the SYSTEM-generated
     * transactions into a single ascending-by-TRAN-ID list, faithfully
     * replicating the JES-concatenated DFSORT step
     * {@code STEP05R EXEC PGM=SORT} from {@code COMBTRAN.jcl}.
     *
     * <p>The two input lists are concatenated (in the order {@code posted}
     * then {@code systran}, matching the SORTIN DD ordering in the JCL),
     * then stably sorted ascending on the 16-character {@code TRAN-ID}
     * prefix of each record. No records are dropped, no records are
     * synthesised, and no field is mutated.
     *
     * @param posted the POSTED transactions backup
     *               (DSN {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} in the
     *               original JCL); each element is a fixed-width
     *               {@code TRAN-RECORD} per {@code CVTRA05Y.cpy} whose
     *               first 16 characters carry the {@code TRAN-ID}
     *               sort key. Must not be {@code null}; may be empty.
     * @param systran the SYSTEM-generated transactions
     *               (DSN {@code AWS.M2.CARDDEMO.SYSTRAN(0)} in the original
     *               JCL); same record layout as {@code posted}. Must not
     *               be {@code null}; may be empty.
     * @return an unmodifiable list containing every record from both
     *         inputs, sorted ascending by {@code TRAN-ID}. Returns an
     *         empty list when both inputs are empty. The returned list is
     *         a snapshot — subsequent mutations of the input lists by the
     *         caller do not affect it.
     * @throws NullPointerException if {@code posted} or {@code systran} is
     *         {@code null}
     */
    public List<String> merge(final List<String> posted, final List<String> systran) {
        Objects.requireNonNull(posted, "posted must not be null");
        Objects.requireNonNull(systran, "systran must not be null");

        // Stage 1 — concatenate both input streams into a single working
        // list. Mirrors the JES-level concatenation of the two SORTIN DD
        // statements (TRANSACT.BKUP followed by SYSTRAN) that DFSORT
        // receives as one logical input. A fresh ArrayList ensures the
        // caller's lists (which may be List.of(...) immutable instances)
        // are never mutated and that the sort step below operates on a
        // private buffer.
        final List<String> combined = new ArrayList<>(posted.size() + systran.size());
        combined.addAll(posted);
        combined.addAll(systran);

        // Stage 2 — stable ascending sort on TRAN-ID (positions 1–16,
        // CHARACTER comparison per the COMBTRAN.jcl SYMNAMES declaration
        // "TRAN-ID,1,16,CH"). String#compareTo performs a char-by-char
        // unsigned comparison, which is the closest Java equivalent of
        // DFSORT's CH (character) compare for ASCII-encoded fixtures and
        // matches the natural ordering the test class asserts via
        // assertThat(currId).isGreaterThanOrEqualTo(prevId).
        //
        // List#sort is documented as stable in the Java 17 API; equal-key
        // records therefore retain their concatenation-order positions —
        // matching DFSORT's default EQUALS behaviour for SORT FIELDS=...
        // when no EQUALS=NO override is specified in SYSIN.
        combined.sort(Comparator.comparing(CombineTransactionsProcessor::extractTranId));

        // Stage 3 — defensive output. An unmodifiable wrapper matches the
        // project-wide test-support convention (cf. FixtureLoader) and
        // signals to callers that the snapshot is final. A snapshot is
        // safer than returning the live ArrayList because downstream
        // Spring Batch writers may iterate the result on a separate
        // thread.
        return Collections.unmodifiableList(combined);
    }

    /**
     * Extracts the {@code TRAN-ID} sort key from one {@code TRAN-RECORD}.
     *
     * <p>The TRAN-ID occupies positions 1–16 of each record (1-based COBOL
     * convention; offsets {@code [0, 16)} in Java's 0-based
     * {@link String#substring(int, int)}). For well-formed fixed-width
     * records the substring is always exactly 16 characters wide; for
     * records shorter than 16 characters (e.g., manually-authored test
     * fixtures that omit padding) the entire record is treated as the
     * key, which is what DFSORT would also do after right-space-padding
     * the short record up to the declared field width.
     *
     * @param record one TRAN-RECORD string; never {@code null} (callers
     *               supply via {@link List} elements which the JDK
     *               collections framework cannot insert {@code null} into
     *               under {@link List#of()} factories)
     * @return the 16-character TRAN-ID prefix when {@code record.length() &gt;= 16};
     *         the entire {@code record} otherwise
     */
    private static String extractTranId(final String record) {
        if (record.length() <= TRAN_ID_END) {
            return record;
        }
        return record.substring(TRAN_ID_OFFSET, TRAN_ID_END);
    }
}
