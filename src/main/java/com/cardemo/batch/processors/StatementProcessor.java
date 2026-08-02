/*
 * ******************************************************************
 * Component   : StatementProcessor.java
 * Application : CardDemo
 * Type        : Spring Batch ItemProcessor (Java 25 / Spring Boot 3.5.11)
 * Function    : Per-account statement aggregation and dual-format emission.
 * Source      : app/cbl/CBSTM03A.CBL (924 lines, 26 paragraphs) @ 7756d89
 *               app/cbl/CBSTM03B.CBL (230 lines) - file access call contract
 *               app/cpy/COSTM01.CPY  - 350-byte statement record, 32-byte key
 *               app/jcl/CREASTMT.JCL - 5 steps, STEP010 sort + OUTREC projection
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.batch.processors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.service.shared.FileService;

/**
 * Produces one customer account statement in two fixed-width formats, translating
 * {@code CBSTM03A} - {@code app/cbl/CBSTM03A.CBL}, 924 lines and 26 paragraphs at commit
 * {@code 7756d89}, whose own banner describes it as printing account statements from transaction
 * data "in two formats : 1/plain text and 2/HTML" ({@code app/cbl/CBSTM03A.CBL:L8-L9}).
 *
 * <h2>What it does</h2>
 *
 * <p>One call to {@link #process(CardCrossReference)} consumes one card cross-reference row and
 * returns one {@link Statement}: the ordered 80-character plain-text lines and the ordered
 * 100-character HTML lines for that account, plus the account identifier and the accumulated
 * transaction total. It writes nothing itself. The two record widths are fixed by the job that
 * runs the legacy program - {@code STMTFILE DCB=(LRECL=80,...)} at
 * {@code app/jcl/CREASTMT.JCL:L89} and {@code HTMLFILE DCB=(LRECL=100,...)} at
 * {@code app/jcl/CREASTMT.JCL:L94} - and independently corroborated in the program itself by
 * {@code 01 FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45} and
 * {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code :L47}.
 *
 * <p>Responsibilities, in the order the source performs them:</p>
 * <ol>
 *   <li>{@link #initialise()} runs the ordered five-call start-up pipeline that the source reaches
 *       through self-modifying code. See <em>The {@code ALTER} chain</em> below.</li>
 *   <li>{@link #readNextCrossReference()} translates {@code 1000-XREFFILE-GET-NEXT}
 *       ({@code :L345-L366}), the driving read of the mainline loop.</li>
 *   <li>{@link #process(CardCrossReference)} translates {@code 1000-MAINLINE}
 *       ({@code :L316-L329}): fetch the customer, fetch the account, build the statement head,
 *       then emit the card's transactions and the totals.</li>
 *   <li>{@link #close()} translates the four closes performed at {@code :L331-L337} - each of
 *       {@code 9100-TRNXFILE-CLOSE} ({@code :L856-L871}), {@code 9200-XREFFILE-CLOSE}
 *       ({@code :L873-L887}), {@code 9300-CUSTFILE-CLOSE} ({@code :L889-L903}) and
 *       {@code 9400-ACCTFILE-CLOSE} ({@code :L905-L919}) keeping its own method - and
 *       {@code 9999-GOBACK} ({@code :L341-L342}).</li>
 * </ol>
 *
 * <p>The DFSORT step that prepares this program's input is also modelled here, because this class
 * owns the {@code COSTM01} layout knowledge that the projection needs:
 * {@link #statementSortComparator()} is {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} and
 * {@link #projectBaseRecord(String)} is {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)},
 * both at {@code app/jcl/CREASTMT.JCL:L53-L54}.</p>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not emit bytes.</strong> {@code com.cardemo.batch.writers.StatementWriter}
 *       owns emission and exposes {@code writeStatementLine(String)} for an 80-character text line
 *       and {@code writeHtmlFragment(String)} for a 100-character HTML line. This class produces
 *       exactly those two line lists, already padded to width, and the writer emits them. That
 *       class is not a declared dependency of this one and is deliberately not imported here.</li>
 *   <li><strong>It does not implement the file-access matrix.</strong> {@link FileService} is the
 *       Java counterpart of {@code CALL 'CBSTM03B' USING WS-M03B-AREA} and owns the four-dataset
 *       by six-operation matrix, the 1040-byte shared area, and every return-code decision -
 *       including the {@code '00'} or {@code '04'} leniency of the nine guard sites at
 *       {@code app/cbl/CBSTM03A.CBL:L736}, {@code :L748}, {@code :L771}, {@code :L789},
 *       {@code :L807}, {@code :L862}, {@code :L879}, {@code :L895} and {@code :L911}. Not one of
 *       those decisions is re-taken here.</li>
 *   <li><strong>It does not render a file status.</strong> The four-character
 *       {@code FILE STATUS IS: NNNN} rendering has a single implementation tree-wide, in
 *       {@code com.cardemo.service.shared.FileStatusMapper}, which {@link FileService} already
 *       consumes on every call this class makes.</li>
 *   <li><strong>It does not own the two output streams.</strong> {@code FILE-CONTROL}
 *       ({@code app/cbl/CBSTM03A.CBL:L38-L40}) selects only {@code STMT-FILE ASSIGN TO STMTFILE}
 *       and {@code HTML-FILE ASSIGN TO HTMLFILE}, and their {@code FD} record descriptions
 *       ({@code :L44-L47}) declare the two widths quoted above. Those two selects, the two
 *       {@code FD}s, the {@code OPEN OUTPUT STMT-FILE HTML-FILE} at {@code :L293} and the
 *       {@code CLOSE STMT-FILE HTML-FILE} at {@code :L339} have no counterpart here: they are the
 *       writer's lifecycle, not the processor's. The four input datasets are absent from
 *       {@code FILE-CONTROL} altogether because {@code CBSTM03B} owns them, which is exactly why
 *       {@link FileService} - and not a reader in this class - reaches them.</li>
 *   <li><strong>It does not read mainframe control blocks.</strong> See <em>Findings</em>,
 *       {@code PSAPTR}.</li>
 *   <li><strong>It sets no return code.</strong> {@code CBSTM03A} contains no {@code RETURN-CODE}
 *       statement at all - verified by exhaustive search of the member - so there is no
 *       completed-with-rejects path here and none is invented. The reject-count-driven return code
 *       4 belongs to {@code CBTRN02C} alone.</li>
 * </ul>
 *
 * <h2>The {@code ALTER} chain is a static initialisation pipeline, not a strategy table</h2>
 *
 * <p>{@code 0000-START} ({@code app/cbl/CBSTM03A.CBL:L296-L314}) switches on {@code WS-FL-DD} and
 * rewrites the branch target of {@code 8100-FILE-OPEN} with four
 * {@code ALTER ... TO PROCEED TO ...} statements at {@code :L300}, {@code :L303}, {@code :L306}
 * and {@code :L309}; a fifth state, {@code 'READTRNX'}, is reached by a direct {@code GO TO} at
 * {@code :L311-L312} with no {@code ALTER} at all. {@code 8100-FILE-OPEN} ({@code :L726-L728}) is
 * a paragraph whose entire body is the unconditional branch that {@code ALTER} overwrites.
 *
 * <p>This reads like a dispatch table and is not one. Every state transition is hard-coded in the
 * tail of its handler, so the machine is deterministic and has exactly one possible execution
 * order: {@code :L760-L761} moves {@code 'READTRNX'} and re-enters; {@code :L851-L852} moves
 * {@code 'XREFFILE'}; {@code :L779-L780} moves {@code 'CUSTFILE'}; {@code :L797-L798} moves
 * {@code 'ACCTFILE'}; and {@code :L815} branches to {@code 1000-MAINLINE}, leaving the machine
 * permanently. It therefore collapses to straight-line code, which is what
 * {@link #initialise()} is: five ordinary sequential calls, then the mainline. There is no
 * dispatch table, no state machine, no enum-keyed map and no strategy object in this class, and
 * {@code WS-FL-DD} has no counterpart at all because the selector disappears with the machine.
 *
 * <p>The genuinely varying dispatch in this program is one level down, in
 * {@code CBSTM03B}'s dataset-by-operation matrix, and it lives in {@link FileService}.
 * {@code DECISION_LOG.md} records this as <em>self-modifying code eliminated by static flow
 * analysis with observable order preserved</em>.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>No property, environment variable or system property is read by this class. There is
 *       nothing to configure and no {@code System.getenv} call.</li>
 *   <li>Text line width defaults to 80 characters and HTML line width to 100, both taken from
 *       {@link StatementTransaction} rather than restated.</li>
 *   <li>The processing timestamp default is <strong>24 significant characters</strong>, not 26.
 *       See <em>Findings</em>.</li>
 *   <li>Amounts default to scale 2 with {@link RoundingMode#HALF_EVEN}; no binary floating-point
 *       type appears anywhere in this file.</li>
 *   <li>Transaction-table capacity defaults to <strong>unbounded</strong>, bounded only by the
 *       loud safety limit {@link #MAX_TRANSACTIONS_PER_RUN}. The legacy 510-record ceiling is
 *       deliberately not reinstated; see {@link #buildTransactionTable(String)}.</li>
 *   <li>Input ordering default: ascending by card number, required by
 *       {@link #emitTransactionsForCard}. See <em>Findings</em>.</li>
 *   <li>All case folding and numeric formatting uses {@link Locale#ROOT}, so behaviour does not
 *       vary with the host locale.</li>
 * </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean compile} and run the unit tier with
 * {@code ./mvnw -B -ntp test}; coverage is enforced at {@code verify}. The build compiles with
 * {@code -Xlint:all -Werror} and {@code failOnWarning}, so any warning in a category
 * {@code javac} 25 publishes fails it. The tests for this class belong at
 * {@code src/test/java/com/cardemo/unit/batch/StatementProcessorTest.java}, which does not exist
 * at this commit, and must assert at minimum that: the projection yields a 24-character
 * processing timestamp; every HTML line is exactly 100 characters and every text line exactly 80;
 * {@link #initialise()} performs its five steps in the documented order; a run of more than 510
 * transactions completes without loss; a card with no transactions still yields a complete
 * statement; and a projected record shorter than 328 characters is rejected rather than padded.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>{@link FatalProcessingException} carrying an {@code ERROR OPENING} or
 *       {@code ERROR READING} literal.</strong> A dataset was unavailable or returned an
 *       unexpected status. The literal identifies the DD name exactly as the source
 *       {@code DISPLAY} did; the return code travels in the cause raised by {@link FileService}.
 *       Check that the statement step bound a dataset for that DD - {@code FileService.isBound}
 *       answers this without provoking an abend.</li>
 *   <li><strong>{@link IllegalStateException} reporting that the pipeline is not initialised.</strong>
 *       {@link #process(CardCrossReference)} was called before {@link #initialise()}. The step
 *       must invoke {@link #initialise()} once before the first item.</li>
 *   <li><strong>A statement whose transaction section is empty when transactions exist.</strong>
 *       Almost always an ordering fault: the early exit at {@code app/cbl/CBSTM03A.CBL:L417-L419}
 *       stops scanning at the first table entry greater than the sought card number and is only
 *       correct for input ascending by card number. Confirm the upstream sort of
 *       {@code app/jcl/CREASTMT.JCL:L53} was applied, or equivalently that
 *       {@link #statementSortComparator()} ordered the input.</li>
 *   <li><strong>Statement output differing from the legacy baseline in the last two characters of
 *       the processing timestamp.</strong> Expected, and not a defect. Those positions are pad,
 *       never data.</li>
 *   <li><strong>An HTML name or address line that stops at the first word.</strong> Expected for
 *       a customer with an empty middle name or address component. See <em>Findings</em>.</li>
 *   <li><strong>A transaction description truncated at 49 characters in the text format but
 *       running to 100 in the HTML format.</strong> Expected: {@code ST-TRANDT PIC X(49)} at
 *       {@code app/cbl/CBSTM03A.CBL:L135} receives {@code TRNX-DESC PIC X(100)}, and an
 *       alphanumeric move truncates on the right.</li>
 *   <li><strong>Amounts that print identically but compare unequal.</strong> Use
 *       {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}; this
 *       class does so throughout.</li>
 * </ul>
 *
 * <h2>Findings carried by this file, classified per Rule 1 Clause F</h2>
 *
 * <ul>
 *   <li><strong>High, mitigated</strong> - the legacy transaction table is 51 cards by 10
 *       transactions ({@code app/cbl/CBSTM03A.CBL:L225-L233}), a hard ceiling of 510 records per
 *       run, and the building loop increments both subscripts with no bounds check whatsoever
 *       before the subscripted moves at {@code :L827-L829}. That is a latent storage overrun.
 *       This class uses unbounded collections, which removes it. This is a
 *       <strong>labelled deviation, not parity</strong>; the written justification required by
 *       Rule 1 Clause A5 is on {@link #buildTransactionTable(String)}. Remediation for a reviewer
 *       comparing against a mainframe baseline: any run exceeding 510 records has no valid
 *       baseline, because the legacy run would have corrupted storage rather than produced
 *       output.</li>
 *   <li><strong>Medium</strong> - the {@code HTMLFILE} record length is declared inconsistently
 *       within one job: {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL:L69} in the pre-delete
 *       step, {@code LRECL=100} at {@code :L94} in the execution step. <strong>100 is
 *       correct</strong>, independently confirmed by {@code HTML-FIXED-LN PIC X(100)} at
 *       {@code app/cbl/CBSTM03A.CBL:L149}. This class uses 100. The mismatch is logged and left
 *       unrepaired, because {@code app/} is frozen. Remediation, out of scope here: correct
 *       {@code :L69} to 100.</li>
 *   <li><strong>Medium</strong> - {@code app/jcl/CREASTMT.JCL:L90} is a corrupted DD continuation
 *       line reading {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS},
 *       plainly the wreckage of an overlapping edit. Logged, not repaired; the frozen corpus is
 *       the record. It does not affect this class, whose widths come from the copybooks.</li>
 *   <li><strong>Medium</strong> - the source interpolates customer name, address, account
 *       identifier, balance and credit score into markup with <strong>no escaping of any
 *       kind</strong> ({@code app/cbl/CBSTM03A.CBL:L562-L632}, {@code :L687-L715}). The fixed
 *       fragments are safe because they are source literals, but the interpolated values are
 *       data. Because parity is the contract and the 100-byte record width is load bearing,
 *       escaping is <strong>not</strong> introduced: it would change byte offsets and break the
 *       Gate 1 comparison. Remediation: the emitted HTML must be treated as untrusted by any
 *       consumer that renders it, and served with a content type and disposition that prevents
 *       active content, which is a deployment concern rather than a code change here.</li>
 *   <li><strong>Low</strong> - the redundant {@code MOVE 1 TO CR-JMP} is at
 *       {@code app/cbl/CBSTM03A.CBL:L324}, not {@code :L325}; {@code :L325} is
 *       {@code MOVE ZERO TO WS-TOTAL-AMT}. Verified by direct inspection. The assignment is
 *       retained as an intentional no-op in {@link #process(CardCrossReference)}.</li>
 *   <li><strong>Low</strong> - {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBSTM03A.CBL:L921-L923})
 *       is only {@code DISPLAY 'ABENDING PROGRAM'} followed by {@code CALL 'CEE3ABD'}. Unlike
 *       {@code CBTRN02C}, it sets no abend code and zeroes no timing field. The Java abend still
 *       carries abend code 999 and return code 12, taken from
 *       {@link FatalProcessingException} and not restated here.</li>
 *   <li><strong>Low</strong> - {@code PSAPTR}, {@code BUMP-TIOT} and {@code TIOT-INDEX}
 *       ({@code app/cbl/CBSTM03A.CBL:L235-L237}), the linkage-section control blocks at
 *       {@code :L239-L260} and the prologue at {@code :L266-L291} that walks the prefixed save
 *       area, the task control block and the task input/output table are
 *       <strong>deliberately not translated</strong>. They exist to demonstrate mainframe control
 *       block addressing ({@code :L30}) and have no portable meaning; reproducing them would
 *       require pointer arithmetic, {@code sun.misc.Unsafe} or reflection into virtual machine
 *       internals, all of which are prohibited and none of which would compute anything. The two
 *       diagnostic {@code DISPLAY} statements they drive, {@code 'Running JCL : '} at {@code :L270}
 *       and {@code 'DD Names from TIOT: '} at {@code :L275}, are consequently also absent; job
 *       and step identity is carried by the batch metadata and the correlation identifier
 *       instead.</li>
 *   <li><strong>Low</strong> - {@code HTML-LTDS} ({@code app/cbl/CBSTM03A.CBL:L161}) is declared
 *       and never activated: the source opens every table cell with a styled {@code <td ...>}
 *       variant instead. It is retained in {@link #htmlFragments()} so the fragment table matches
 *       the declaration, and is never emitted, matching the source exactly.</li>
 *   <li><strong>Low</strong> - the name and address fragments are assembled with
 *       {@code DELIMITED BY '  '} ({@code app/cbl/CBSTM03A.CBL:L563}, {@code :L571},
 *       {@code :L579}, {@code :L587}), which stops copying at the first pair of adjacent spaces.
 *       A customer with an empty middle name therefore produces {@code ST-NAME} containing two
 *       adjacent spaces, and the HTML name line silently loses the surname while the text line
 *       keeps it. Preserved exactly; it is behaviour, not noise.</li>
 *   <li><strong>Low</strong> - the source's self-recursive table-building loop
 *       ({@code GO TO 8500-READTRNX-READ} at {@code app/cbl/CBSTM03A.CBL:L840}) is translated as
 *       iteration. A Java method recursing once per input record would exhaust the stack on real
 *       volumes. Observable order and the final-counter flush at {@code :L850} are preserved
 *       exactly.</li>
 *   <li><strong>Not available</strong> - the reader and writer types and the chunk size for the
 *       statement step. {@code com.cardemo.batch.jobs} and a statement reader do not exist at this
 *       commit, so no concrete type name or chunk size is asserted. What is needed: the
 *       {@code StatementGenerationJob} step definition. This class is deliberately independent of
 *       all three.</li>
 *   <li><strong>Not available</strong> - {@code com.cardemo.batch.package-info.java} does not
 *       exist at this commit, so Rule 1 Clause E is discharged entirely by this class-level
 *       documentation. What is needed: that file, which this class must not create.</li>
 *   <li><strong>Not available</strong> - no service-level objective for statement generation
 *       exists anywhere in the corpus, so none is asserted. What is needed: a measured baseline
 *       from a running system.</li>
 * </ul>
 *
 * <h2>The one Rule 1 conflict, and its resolution</h2>
 *
 * <p>Clause B1 forbids dead code. Behavioural parity requires reproducing reachable no-ops.
 * These collide at exactly one site in this file, the redundant {@code MOVE 1 TO CR-JMP} at
 * {@code app/cbl/CBSTM03A.CBL:L324}, which is immediately overwritten by the
 * {@code PERFORM VARYING CR-JMP FROM 1 BY 1} at {@code :L417-L418}.
 * <strong>Parity governs</strong>, because Clause B1 forbids <em>untracked</em> dead code and
 * deferred work without an owner: this no-op is cited, marked and tracked in
 * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}, so it is a documented faithful
 * reproduction rather than abandoned residue. Deleting it would break the paragraph map the
 * scope-coverage gate verifies.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>Not thread safe, by design.</strong> This bean is {@code @StepScope}, so one
 * instance serves one step execution and its mutable state - the transaction table, the card and
 * transaction counters and the saved card number, all counterparts of
 * {@code WORKING-STORAGE} items at {@code app/cbl/CBSTM03A.CBL:L60-L70} - is confined to that
 * step. Every field is an instance field; there is no static mutable state anywhere in this
 * class, and no collaborator is constructed here.
 *
 * @see StatementTransaction
 * @see FileService
 * @see FatalProcessingException
 */
@Component
@StepScope
public class StatementProcessor implements ItemProcessor<CardCrossReference, StatementProcessor.Statement> {

    /**
     * Structured log sink, replacing the {@code DISPLAY} statements of the source, which had no
     * sink but SYSOUT and no severity at all. The literals are reproduced verbatim; only the
     * level is a target-side decision. Every literal that precedes an abend is {@code ERROR};
     * per-statement progress is {@code DEBUG}, because one statement per account would otherwise
     * bury a run; the two capacity notices are {@code WARN}, because they mark the point beyond
     * which a legacy baseline cannot exist.
     *
     * <p>No log statement in this class emits a card number, a customer name, an address, a
     * social security number or a balance. The statement body legitimately carries all of them,
     * but log output must not, so diagnostics are limited to DD names, counts and the account
     * identifier.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StatementProcessor.class);

    // ==========================================================================================
    // Record geometry. Every width is taken from StatementTransaction rather than restated, so
    // there is exactly one declaration of each per tree (Rule 1 Clause C3).
    // ==========================================================================================

    /**
     * Width of one plain-text statement line, {@code 01 FD-STMTFILE-REC PIC X(80)} at
     * {@code app/cbl/CBSTM03A.CBL:L45}, corroborated by {@code STMTFILE DCB=(LRECL=80,...)} at
     * {@code app/jcl/CREASTMT.JCL:L89}.
     */
    private static final int TEXT_LINE_WIDTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /**
     * Width of one HTML statement line, {@code 05 HTML-FIXED-LN PIC X(100)} at
     * {@code app/cbl/CBSTM03A.CBL:L149}, corroborated by {@code HTMLFILE DCB=(LRECL=100,...)} at
     * {@code app/jcl/CREASTMT.JCL:L94}.
     *
     * <p>100, not 80. The same job declares {@code LRECL=80} for the same DD at
     * {@code app/jcl/CREASTMT.JCL:L69}; that is the logged Medium finding, and the program's own
     * field width settles it.
     */
    private static final int HTML_LINE_WIDTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /**
     * Loud safety limit on transactions accepted into the table in one run. The source had a hard
     * ceiling of 510 and no check; this class has no ceiling, so it needs a bound that stops a
     * malformed or endlessly repeating dataset from exhausting the heap (Rule 1 Clause A2, which
     * requires inputs to be treated as untrusted). Exceeding it raises
     * {@link FatalProcessingException}: it fails loudly and never silently truncates, which is
     * precisely what the legacy overrun did. Chosen roughly three orders of magnitude above the
     * largest fixture so that no legitimate run can reach it.
     */
    public static final int MAX_TRANSACTIONS_PER_RUN = 1_000_000;

    /** Minimum length of a projected record: the last position the projection writes. */
    private static final int MINIMUM_PROJECTED_LENGTH =
            StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION;

    /**
     * Number of 88-level markup fragments declared under {@code 01 HTML-LINES},
     * {@code app/cbl/CBSTM03A.CBL:L148-L211}. Asserted when the fragment table is built so that a lost
     * or duplicated fragment fails immediately instead of producing a subtly short document.
     */
    private static final int HTML_FRAGMENT_COUNT = 34;

    /**
     * Trailing-sign overpunch characters for a non-negative zoned-decimal field, indexed by the value
     * of the final digit: {@code '{'} is {@code +0} and {@code 'A'} through {@code 'I'} are {@code +1}
     * through {@code +9}.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Trailing-sign overpunch characters for a negative zoned-decimal field, indexed by the value of
     * the final digit: {@code '}'} is {@code -0} and {@code 'J'} through {@code 'R'} are {@code -1}
     * through {@code -9}.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Trailing digits of a card number retained when one has to be named in a diagnostic. */
    private static final int MASKED_CARD_DIGITS = 4;

    // ==========================================================================================
    // The 34 fixed markup fragments of 01 HTML-LINES, app/cbl/CBSTM03A.CBL:L148-L211.
    //
    // The source declares each fragment as an 88-level condition name over the single
    // HTML-FIXED-LN PIC X(100) field and emits it with the two-statement idiom
    // "SET <condition> TO TRUE" then "WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN". A condition
    // name over a fixed-width field is a constant, so the table below is the faithful
    // translation and the SET/WRITE pair becomes one call to the fixed-width sink.
    //
    // Constant names preserve the COBOL condition names with hyphens rendered as underscores, so
    // the correspondence is mechanically checkable. Two fragments are continued literals in the
    // source, joined at column 72 with no padding: HTML_L08 (:L157-L158) and each of the nine
    // styled cell openers. The longest joined fragment is 85 characters, so all 34 fit X(100).
    // ==========================================================================================

    /** {@code HTML-L01}, {@code app/cbl/CBSTM03A.CBL:L150}. */
    private static final String HTML_L01 = "<!DOCTYPE html>";

    /** {@code HTML-L02}, {@code app/cbl/CBSTM03A.CBL:L151}. */
    private static final String HTML_L02 = "<html lang=\"en\">";

    /** {@code HTML-L03}, {@code app/cbl/CBSTM03A.CBL:L152}. */
    private static final String HTML_L03 = "<head>";

    /** {@code HTML-L04}, {@code app/cbl/CBSTM03A.CBL:L153}. */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";

    /** {@code HTML-L05}, {@code app/cbl/CBSTM03A.CBL:L154}. */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";

    /** {@code HTML-L06}, {@code app/cbl/CBSTM03A.CBL:L155}. */
    private static final String HTML_L06 = "</head>";

    /** {@code HTML-L07}, {@code app/cbl/CBSTM03A.CBL:L156}. */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";

    /**
     * {@code HTML-L08}, a continued literal joined from {@code app/cbl/CBSTM03A.CBL:L157-L158}.
     * The two spaces after {@code <table} are in the source and are preserved.
     */
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    /** {@code HTML-LTRS}, {@code app/cbl/CBSTM03A.CBL:L159}. */
    private static final String HTML_LTRS = "<tr>";

    /** {@code HTML-LTRE}, {@code app/cbl/CBSTM03A.CBL:L160}. */
    private static final String HTML_LTRE = "</tr>";

    /**
     * {@code HTML-LTDS}, {@code app/cbl/CBSTM03A.CBL:L161}. Declared and never activated: every
     * cell in the emitted document is opened by a styled variant instead. Retained so the
     * fragment table matches the declaration; never emitted, exactly as in the source.
     */
    private static final String HTML_LTDS = "<td>";

    /** {@code HTML-LTDE}, {@code app/cbl/CBSTM03A.CBL:L162}. */
    private static final String HTML_LTDE = "</td>";

    /** {@code HTML-L10}, joined from {@code app/cbl/CBSTM03A.CBL:L163-L164}. */
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    /** {@code HTML-L15}, joined from {@code app/cbl/CBSTM03A.CBL:L165-L166}. */
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    /** {@code HTML-L16}, {@code app/cbl/CBSTM03A.CBL:L167-L168}. */
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    /** {@code HTML-L17}, {@code app/cbl/CBSTM03A.CBL:L169-L170}. */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";

    /** {@code HTML-L18}, {@code app/cbl/CBSTM03A.CBL:L171-L172}. */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";

    /** {@code HTML-L22-35}, joined from {@code app/cbl/CBSTM03A.CBL:L173-L175}. */
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    /** {@code HTML-L30-42}, joined from {@code app/cbl/CBSTM03A.CBL:L176-L178}. */
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

    /** {@code HTML-L31}, {@code app/cbl/CBSTM03A.CBL:L179-L180}. */
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";

    /** {@code HTML-L43}, {@code app/cbl/CBSTM03A.CBL:L181-L182}. */
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";

    /** {@code HTML-L47}, joined from {@code app/cbl/CBSTM03A.CBL:L183-L185}. */
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    /** {@code HTML-L48}, {@code app/cbl/CBSTM03A.CBL:L186-L187}. */
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";

    /** {@code HTML-L50}, joined from {@code app/cbl/CBSTM03A.CBL:L188-L190}. */
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    /** {@code HTML-L51}, {@code app/cbl/CBSTM03A.CBL:L191-L192}. */
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";

    /** {@code HTML-L53}, joined from {@code app/cbl/CBSTM03A.CBL:L193-L195}. */
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";

    /** {@code HTML-L54}, {@code app/cbl/CBSTM03A.CBL:L196-L197}. */
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";

    /** {@code HTML-L58}, joined from {@code app/cbl/CBSTM03A.CBL:L198-L200}. */
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    /** {@code HTML-L61}, joined from {@code app/cbl/CBSTM03A.CBL:L201-L203}. */
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    /** {@code HTML-L64}, joined from {@code app/cbl/CBSTM03A.CBL:L204-L206}. */
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    /** {@code HTML-L75}, {@code app/cbl/CBSTM03A.CBL:L207-L208}. */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";

    /** {@code HTML-L78}, {@code app/cbl/CBSTM03A.CBL:L209}. */
    private static final String HTML_L78 = "</table>";

    /** {@code HTML-L79}, {@code app/cbl/CBSTM03A.CBL:L210}. */
    private static final String HTML_L79 = "</body>";

    /** {@code HTML-L80}, {@code app/cbl/CBSTM03A.CBL:L211}. */
    private static final String HTML_L80 = "</html>";

    /**
     * The 34 fragments above, keyed by their COBOL condition name with hyphens rendered as
     * underscores, in source declaration order so that iteration is deterministic. Built once,
     * unmodifiable, and therefore a constant rather than global mutable state (Rule 1 Clause B3).
     */
    private static final Map<String, String> HTML_FRAGMENTS = buildHtmlFragmentTable();

    // ==========================================================================================
    // The structured HTML groups, app/cbl/CBSTM03A.CBL:L212-L223. Unlike the 34 condition names
    // these carry interpolated data, so each is a prefix plus a fixed-width slot.
    // ==========================================================================================

    /**
     * Prefix of {@code HTML-L11}, {@code FILLER PIC X(34)} at
     * {@code app/cbl/CBSTM03A.CBL:L213-L214}, followed by {@code L11-ACCT PIC X(20)} and the
     * {@code FILLER PIC X(05)} closing tag at {@code :L216}, for 59 declared bytes.
     */
    private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: ";

    /** Suffix of {@code HTML-L11}, {@code FILLER PIC X(05)} at {@code app/cbl/CBSTM03A.CBL:L216}. */
    private static final String HTML_L11_SUFFIX = "</h3>";

    /**
     * Prefix of {@code HTML-L23}, {@code FILLER PIC X(26)} at
     * {@code app/cbl/CBSTM03A.CBL:L218-L219}, and the same literal the name {@code STRING} at
     * {@code :L562} opens with.
     */
    private static final String HTML_L23_PREFIX = "<p style=\"font-size:16px\">";

    /** Opening paragraph tag used by every {@code STRING} into an address, basic or transaction line. */
    private static final String HTML_PARAGRAPH_OPEN = "<p>";

    /** Closing paragraph tag used by every {@code STRING} into an address, basic or transaction line. */
    private static final String HTML_PARAGRAPH_CLOSE = "</p>";

    /**
     * The two-space delimiter of the name and address {@code STRING} statements at
     * {@code app/cbl/CBSTM03A.CBL:L563}, {@code :L571}, {@code :L579} and {@code :L587}. It is
     * both the {@code DELIMITED BY} value that stops the copy and, at {@code :L564} and its
     * siblings, the {@code DELIMITED BY SIZE} literal appended immediately afterwards.
     */
    private static final String HTML_DOUBLE_SPACE = "  ";

    /** {@code '<p>Account ID         : '}, {@code app/cbl/CBSTM03A.CBL:L614}, 24 bytes. */
    private static final String HTML_LABEL_ACCOUNT_ID = "<p>Account ID         : ";

    /** {@code '<p>Current Balance    : '}, {@code app/cbl/CBSTM03A.CBL:L621}, 24 bytes. */
    private static final String HTML_LABEL_CURRENT_BALANCE = "<p>Current Balance    : ";

    /** {@code '<p>FICO Score         : '}, {@code app/cbl/CBSTM03A.CBL:L628}, 24 bytes. */
    private static final String HTML_LABEL_FICO_SCORE = "<p>FICO Score         : ";

    // ==========================================================================================
    // 01 STATEMENT-LINES, app/cbl/CBSTM03A.CBL:L85-L146. Sixteen 80-byte group items. Every
    // FILLER width below was read from the source; each line's parts sum to exactly 80, which is
    // the invariant the fixed-width builders assert.
    //
    // INITIALIZE STATEMENT-LINES at :L459 resets the named data fields to spaces and zeros but
    // leaves every FILLER VALUE clause intact, so the asterisk rules, dash rules and column
    // headings persist across accounts. That is why they are constants here rather than state.
    // ==========================================================================================

    /** {@code ST-LINE0}, {@code app/cbl/CBSTM03A.CBL:L86-L89}: 31 asterisks, the banner, 31 asterisks. */
    private static final String ST_LINE0 = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /**
     * The 80-dash rule. The source declares this three separate times with identical content —
     * {@code ST-LINE5} at {@code app/cbl/CBSTM03A.CBL:L101-L102}, {@code ST-LINE10} at
     * {@code :L120-L121} and {@code ST-LINE12} at {@code :L126-L127}, each
     * {@code FILLER VALUE ALL '-' PIC X(80)}. One constant serves all three because the emitted
     * bytes are identical; the three emission sites are cited individually where they are used,
     * so the paragraph correspondence is not lost (Rule 1 Clause C3, avoid duplication).
     */
    private static final String ST_RULE_LINE = "-".repeat(TEXT_LINE_WIDTH);

    /**
     * {@code ST-LINE6}, {@code app/cbl/CBSTM03A.CBL:L103-L106}. The literal is 13 characters in
     * an {@code X(14)} field, so it carries one trailing space before the right-hand filler.
     */
    private static final String ST_LINE6 = " ".repeat(33) + padRight("Basic Details", 14) + " ".repeat(33);

    /** {@code ST-LINE11}, {@code app/cbl/CBSTM03A.CBL:L122-L125}. The literal already carries its trailing space. */
    private static final String ST_LINE11 = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

    /**
     * {@code ST-LINE13}, {@code app/cbl/CBSTM03A.CBL:L128-L131}: the column headings. The middle
     * literal is 16 characters in an {@code X(51)} field, so it is right-padded to 51.
     */
    private static final String ST_LINE13 =
            "Tran ID         " + padRight("Tran Details    ", 51) + "  Tran Amount";

    /** {@code ST-LINE15}, {@code app/cbl/CBSTM03A.CBL:L143-L146}: 32 asterisks, the banner, 32 asterisks. */
    private static final String ST_LINE15 = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /** {@code ST-LINE7} label, {@code app/cbl/CBSTM03A.CBL:L108}, {@code PIC X(20)}. */
    private static final String ST_LABEL_ACCOUNT_ID = "Account ID         :";

    /** {@code ST-LINE8} label, {@code app/cbl/CBSTM03A.CBL:L112}, {@code PIC X(20)}. */
    private static final String ST_LABEL_CURRENT_BALANCE = "Current Balance    :";

    /** {@code ST-LINE9} label, {@code app/cbl/CBSTM03A.CBL:L117}, {@code PIC X(20)}. */
    private static final String ST_LABEL_FICO_SCORE = "FICO Score         :";

    /** {@code ST-LINE14A} label, {@code app/cbl/CBSTM03A.CBL:L139}, {@code PIC X(10)}. */
    private static final String ST_LABEL_TOTAL_EXPENDITURE = "Total EXP:";

    /** The currency sign filler of {@code ST-LINE14:L136} and {@code ST-LINE14A:L141}, {@code PIC X(01)}. */
    private static final String ST_CURRENCY_SIGN = "$";

    /** {@code ST-NAME PIC X(75)}, {@code app/cbl/CBSTM03A.CBL:L91}. */
    private static final int ST_NAME_WIDTH = 75;

    /** Trailing filler of {@code ST-LINE1}, {@code app/cbl/CBSTM03A.CBL:L92}, {@code PIC X(05)}. */
    private static final int ST_LINE1_FILLER_WIDTH = 5;

    /** {@code ST-ADD1} and {@code ST-ADD2}, {@code app/cbl/CBSTM03A.CBL:L94} and {@code :L97}. */
    private static final int ST_ADDRESS_WIDTH = 50;

    /** Trailing filler of {@code ST-LINE2} and {@code ST-LINE3}, {@code :L95} and {@code :L98}. */
    private static final int ST_ADDRESS_FILLER_WIDTH = 30;

    /** {@code ST-ADD3 PIC X(80)}, {@code app/cbl/CBSTM03A.CBL:L100}: a whole line on its own. */
    private static final int ST_ADD3_WIDTH = TEXT_LINE_WIDTH;

    /** {@code ST-ACCT-ID} and {@code ST-FICO-SCORE}, {@code :L109} and {@code :L118}, both {@code PIC X(20)}. */
    private static final int ST_LABELLED_VALUE_WIDTH = 20;

    /** Trailing filler of {@code ST-LINE7} and {@code ST-LINE9}, {@code :L110} and {@code :L119}. */
    private static final int ST_LABELLED_LINE_FILLER_WIDTH = 40;

    /**
     * Combined trailing filler of {@code ST-LINE8}: the source declares two adjacent
     * {@code VALUE SPACES} items, {@code PIC X(07)} at {@code app/cbl/CBSTM03A.CBL:L114} and
     * {@code PIC X(40)} at {@code :L115}. They are contiguous spaces, so 47 emits the same bytes.
     */
    private static final int ST_LINE8_FILLER_WIDTH = 47;

    /** {@code ST-TRANID PIC X(16)}, {@code app/cbl/CBSTM03A.CBL:L133}. */
    private static final int ST_TRANID_WIDTH = 16;

    /** Separator filler of {@code ST-LINE14}, {@code app/cbl/CBSTM03A.CBL:L134}, {@code VALUE ' '}. */
    private static final int ST_LINE14_SEPARATOR_WIDTH = 1;

    /**
     * {@code ST-TRANDT PIC X(49)}, {@code app/cbl/CBSTM03A.CBL:L135}. It receives
     * {@code TRNX-DESC PIC X(100)} at {@code :L677}, so the description is truncated to 49
     * characters on both the text and the HTML line. Preserved, not widened.
     */
    private static final int ST_TRANDT_WIDTH = 49;

    /** Middle filler of {@code ST-LINE14A}, {@code app/cbl/CBSTM03A.CBL:L140}, {@code PIC X(56)}. */
    private static final int ST_LINE14A_FILLER_WIDTH = 56;

    /** Width of both edited amount masks, {@code 9(9).99-} and {@code Z(9).99-}: 9 + 1 + 2 + 1. */
    private static final int EDITED_AMOUNT_WIDTH = 13;

    /** Integer digit positions in both edited amount masks, {@code app/cbl/CBSTM03A.CBL:L113} and {@code :L137}. */
    private static final int EDITED_AMOUNT_INTEGER_DIGITS = StatementTransaction.AMOUNT_INTEGER_DIGITS;

    /** Decimal digit positions in both edited amount masks; always printed, never suppressed. */
    private static final int EDITED_AMOUNT_SCALE = StatementTransaction.AMOUNT_SCALE;

    /** {@code L11-ACCT PIC X(20)}, {@code app/cbl/CBSTM03A.CBL:L215}. */
    private static final int L11_ACCT_WIDTH = 20;

    /**
     * {@code L23-NAME PIC X(50)}, {@code app/cbl/CBSTM03A.CBL:L220}. It receives
     * {@code ST-NAME PIC X(75)} at {@code :L560}, so the assembled name is truncated to 50 on the
     * HTML side while the text side keeps all 75. Preserved.
     */
    private static final int L23_NAME_WIDTH = 50;

    /** {@code ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT01Y.cpy}. Rendered zero-padded to 11 digits. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int FICO_SCORE_DIGITS = 3;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_STATE_CODE_WIDTH = 2;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_COUNTRY_CODE_WIDTH = 3;

    /** {@code CUST-ADDR-ZIP PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_ZIP_WIDTH = 10;

    /** {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME}, {@code CUST-LAST-NAME}, each {@code PIC X(25)}. */
    private static final int CUSTOMER_NAME_PART_WIDTH = 25;

    /** {@code CUST-ADDR-LINE-1}, {@code -2}, {@code -3}, each {@code PIC X(50)}. */
    private static final int CUSTOMER_ADDRESS_LINE_WIDTH = 50;

    /** The single-space {@code DELIMITED BY} value of the two text {@code STRING}s, {@code :L462} and {@code :L472}. */
    private static final String SINGLE_SPACE = " ";

    // ==========================================================================================
    // Geometry of the three companion records this program reads. Widths come from the copybooks
    // that app/cbl/CBSTM03A.CBL:L51-L57 COPYs, and are corroborated by the FD record descriptions
    // of app/cbl/CBSTM03B.CBL and by the catalogued average record lengths in
    // app/catlg/LISTCAT.txt.
    // ==========================================================================================

    /**
     * {@code 01 CARD-XREF-RECORD}, {@code app/cpy/CVACT03Y.cpy}:
     * {@code XREF-CARD-NUM X(16) + XREF-CUST-ID 9(09) + XREF-ACCT-ID 9(11) + FILLER X(14)}.
     * 36 populated bytes in a 50-byte slot; the 14-byte tail is filler and is never interpreted.
     */
    private static final int CARD_XREF_RECORD_LENGTH = 50;

    /** {@code XREF-CARD-NUM PIC X(16)}, offsets 1-16 of the cross-reference record. */
    private static final int XREF_CARD_NUMBER_LENGTH = StatementTransaction.CARD_NUMBER_LENGTH;

    /** {@code XREF-CUST-ID PIC 9(09)}, offsets 17-25, and the {@code CUSTFILE} key length. */
    private static final int CUSTOMER_KEY_LENGTH = 9;

    /** {@code XREF-ACCT-ID PIC 9(11)}, offsets 26-36, and the {@code ACCTFILE} key length. */
    private static final int ACCOUNT_KEY_LENGTH = ACCOUNT_ID_DIGITS;

    /** {@code 01 CUSTOMER-RECORD}, {@code app/cpy/CVCUS01Y.cpy}, {@code 05 CUSTOMER-RECORD-RECLN 500}. */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /** {@code 01 ACCOUNT-RECORD}, {@code app/cpy/CVACT01Y.cpy}, {@code 05 ACCOUNT-RECORD-RECLN 300}. */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /** Every {@code ACCT-*} money field is {@code PIC S9(10)V99}: 10 integer digits plus 2 decimals. */
    private static final int ACCOUNT_MONEY_FIELD_WIDTH = 12;

    /** {@code CUST-PHONE-NUM-1} and {@code -2}, each {@code PIC X(15)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_PHONE_WIDTH = 15;

    /** {@code CUST-SSN PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy}. Parsed, carried, never logged. */
    private static final int CUSTOMER_SSN_WIDTH = 9;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_GOVT_ID_WIDTH = 20;

    /**
     * Width of every {@code X(10)} date field: {@code CUST-DOB-YYYY-MM-DD} in
     * {@code app/cpy/CVCUS01Y.cpy} and {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} and
     * {@code ACCT-REISSUE-DATE} in {@code app/cpy/CVACT01Y.cpy}.
     */
    private static final int CUSTOMER_DATE_WIDTH = 10;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int CUSTOMER_EFT_ACCOUNT_WIDTH = 10;

    /**
     * Width of the two single-character indicators: {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} in
     * {@code app/cpy/CVCUS01Y.cpy} and {@code ACCT-ACTIVE-STATUS PIC X(01)} in
     * {@code app/cpy/CVACT01Y.cpy}.
     */
    private static final int CUSTOMER_INDICATOR_WIDTH = 1;

    /** {@code ACCT-GROUP-ID PIC X(10)}, {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    // ==========================================================================================
    // Step-scoped state. Every item below is the counterpart of a WORKING-STORAGE field of
    // app/cbl/CBSTM03A.CBL. They are instance fields on a @StepScope bean, so their lifetime is
    // one step execution — the same lifetime the COBOL WORKING-STORAGE had, which was one run of
    // the program. NOT ONE OF THEM IS STATIC (Rule 1 Clause B3).
    //
    // WS-FL-DD PIC X(8) VALUE 'TRNXFILE' at :L67 has NO counterpart and is deliberately absent:
    // it existed only to select the next ALTER target, and once the dispatch collapses to the
    // ordered pipeline of #initialise() there is nothing left for it to select. See the class
    // comment on the ALTER chain.
    //
    // WS-SAVE-CARD PIC X(16) at :L69 likewise has no field counterpart. The source seeded it in
    // 8100-TRNXFILE-OPEN at :L757 and consumed it in 8500-READTRNX-READ at :L819 and :L830, i.e.
    // it was shared working storage spanning two paragraphs. Here the primed record is returned
    // by #openAndPrimeTransactionFile() and passed to #buildTransactionTable(), so the value is a
    // parameter and a local instead of shared mutable state. Both paragraphs survive as their own
    // methods; only the coupling between them narrows.
    // ==========================================================================================

    /** The file-access collaborator standing in for {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. */
    private final FileService fileService;

    /**
     * The transaction table, {@code 01 WS-TRNX-TABLE} at {@code app/cbl/CBSTM03A.CBL:L225-L233},
     * together with its companion counter table {@code 01 WS-TRN-TBL-CNTR} at {@code :L231-L233}.
     * One {@link StatementTransaction.CardGroup} per card in ascending card-number order; the
     * group's list size is the counterpart of {@code WS-TRCT (CR-CNT)}, so the two source tables
     * collapse into one structure without losing either.
     *
     * <p>Deliberately UNBOUNDED. See {@link #buildTransactionTable(String)} for the written
     * justification of removing the legacy 510-transaction ceiling.
     */
    private final List<StatementTransaction.CardGroup> transactionTable = new ArrayList<>();

    /**
     * {@code CR-JMP PIC S9(4) VALUE 0} at {@code app/cbl/CBSTM03A.CBL:L62}: the outer subscript of
     * the transaction-table scan. Held as a field rather than a local precisely because the source
     * holds it in WORKING-STORAGE and writes it from two places — the redundant
     * {@code MOVE 1 TO CR-JMP} at {@code :L324} and the {@code PERFORM VARYING} at {@code :L417}.
     * Keeping it a field is what lets the retained no-op remain observable.
     */
    private int crJmp;

    /**
     * {@code TR-JMP PIC S9(4) VALUE 0} at {@code app/cbl/CBSTM03A.CBL:L63}: the inner subscript,
     * driven by the {@code PERFORM VARYING} at {@code :L422}.
     */
    private int trJmp;

    /**
     * {@code WS-TOTAL-AMT PIC S9(9)V99 COMP-3} at {@code app/cbl/CBSTM03A.CBL:L65}: the per-account
     * expenditure accumulator. {@link BigDecimal} at scale 2, never {@code double} — packed decimal
     * is exact and binary floating point is not. Reset to zero per account at {@code :L325} and
     * accumulated at {@code :L429}.
     */
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(EDITED_AMOUNT_SCALE, RoundingMode.HALF_EVEN);

    /**
     * {@code END-OF-FILE PIC X(01) VALUE 'N'} at {@code app/cbl/CBSTM03A.CBL:L70}. Set when the
     * cross-reference sequential read reports {@code '10'} at {@code :L357}, which is the sole
     * termination condition of the {@code 1000-MAINLINE} loop at {@code :L316}.
     */
    private boolean endOfFile;

    /** Guards {@link #initialise()} so the ordered pipeline runs exactly once per step. */
    private boolean initialised;

    /** Running count of records admitted to the table, checked against {@link #MAX_TRANSACTIONS_PER_RUN}. */
    private long transactionsAccepted;

    /** Running count of statements emitted, reported once at {@link #close()} in place of the absent counters. */
    private long statementsProduced;

    /**
     * Creates the processor.
     *
     * <p>Constructor injection only, and exactly one collaborator. {@link FileService} is the whole
     * data path because the source's whole data path is {@code CALL 'CBSTM03B' USING WS-M03B-AREA}
     * — four datasets behind one call contract. The JPA repositories are deliberately NOT injected:
     * routing the same four reads through both {@code FileService} and a repository would give this
     * class two competing access paths for one source construct, which Rule 1 Clause C3 forbids as
     * duplication. {@code FileStatusMapper} is likewise not injected, because {@code FileService}
     * already delegates every status decision to it; a second status authority in this class would
     * be unreachable code under Clause B1. No {@link java.time.Clock} is injected either:
     * {@code app/cbl/CBSTM03A.CBL} generates no timestamp anywhere — it only relays
     * {@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} as characters — so a clock would be an unused
     * dependency.
     *
     * @param fileService the DD-keyed file-access service, never {@code null}
     * @throws NullPointerException if {@code fileService} is {@code null}.
     */
    public StatementProcessor(FileService fileService) {
        this.fileService = Objects.requireNonNull(fileService, "fileService must not be null");
    }

    /**
     * One account's rendered statement: the fixed-width lines of both output streams, ready for
     * {@code com.cardemo.batch.writers.StatementWriter} to emit unchanged.
     *
     * <p>A nested record, not a separate file: the package admits no sixth class.
     *
     * <p>The compact constructor asserts the two width invariants that the whole translation exists
     * to protect — every text line is exactly {@value StatementProcessor#TEXT_LINE_WIDTH} characters and every HTML
     * line exactly {@value StatementProcessor#HTML_LINE_WIDTH}. Asserting them here rather than trusting the builders
     * means a geometry regression fails at the point of construction instead of surfacing as a
     * mismatched baseline much later (Rule 1 Clause B2).
     *
     * @param accountId the statement's account identifier, as rendered into
     * {@code ST-ACCT-ID} at {@code app/cbl/CBSTM03A.CBL:L483}, never {@code null}
     * @param totalExpenditure the accumulated {@code WS-TOTAL-AMT} of {@code :L429}, at scale 2,
     * never {@code null}
     * @param textLines the {@code STMTFILE} lines, each exactly {@value StatementProcessor#TEXT_LINE_WIDTH} characters
     * @param htmlLines the {@code HTMLFILE} lines, each exactly {@value StatementProcessor#HTML_LINE_WIDTH} characters
     */
    public record Statement(
            String accountId,
            BigDecimal totalExpenditure,
            List<String> textLines,
            List<String> htmlLines) {

        /**
         * Validates and defensively copies.
         *
         * @throws NullPointerException if any component is {@code null}, or any line is {@code null}
         * @throws IllegalArgumentException if any line is not exactly its stream's declared width.
         */
        public Statement {
            Objects.requireNonNull(accountId, "accountId must not be null");
            Objects.requireNonNull(totalExpenditure, "totalExpenditure must not be null");
            Objects.requireNonNull(textLines, "textLines must not be null");
            Objects.requireNonNull(htmlLines, "htmlLines must not be null");
            textLines = List.copyOf(textLines);
            htmlLines = List.copyOf(htmlLines);
            requireUniformWidth(textLines, TEXT_LINE_WIDTH, "textLines");
            requireUniformWidth(htmlLines, HTML_LINE_WIDTH, "htmlLines");
        }

        /**
         * Rejects any line whose length is not exactly the declared record width.
         *
         * @param lines the lines to check, already copied and known non-{@code null}
         * @param width the declared fixed-record width
         * @param component the record component name, used verbatim in the failure message
         * @throws IllegalArgumentException if any line has the wrong width.
         */
        private static void requireUniformWidth(List<String> lines, int width, String component) {
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.length() != width) {
                    throw new IllegalArgumentException(component + '[' + index
                            + "] must be exactly " + width + " characters to satisfy the fixed record"
                            + " length, but was " + line.length());
                }
            }
        }

        /**
         * Returns a description that names the account but discloses no statement content.
         *
         * <p>The statement body legitimately carries the customer's name, address, credit score and
         * balance. None of it belongs in a log, so none of it appears here (Rule 1 Clause D1).
         *
         * @return a safe summary carrying only the account identifier and the two line counts.
         */
        @Override
        public String toString() {
            return "Statement[accountId=" + accountId
                    + ", textLines=" + textLines.size()
                    + ", htmlLines=" + htmlLines.size() + ']';
        }
    }

    // ==========================================================================================
    // Failure text. Reproduced verbatim from the DISPLAY statements of the guard sites so that log
    // output remains greppable against the legacy job log (Rule 1 Clause A4). The 'RETURN CODE: '
    // second line is NOT reproduced here: FileStatusMapper already emits it through
    // FILE_STATUS_RETURN_CODE text on the abend it raises, and duplicating it would put the same
    // string in two places.
    // ==========================================================================================

    /**
     * {@code DISPLAY 'ERROR OPENING <DD>'} — {@code app/cbl/CBSTM03A.CBL:L737} for {@code TRNXFILE},
     * {@code :L772} for {@code XREFFILE}, {@code :L790} for {@code CUSTFILE}, {@code :L808} for
     * {@code ACCTFILE}. The four literals differ only in the DD name, so the prefix is shared and
     * the name appended.
     */
    private static final String ERROR_OPENING = "ERROR OPENING ";

    /**
     * {@code DISPLAY 'ERROR READING <DD>'} — {@code app/cbl/CBSTM03A.CBL:L749} and {@code :L844}
     * for {@code TRNXFILE}, {@code :L359} for {@code XREFFILE}, {@code :L383} for {@code CUSTFILE},
     * {@code :L407} for {@code ACCTFILE}.
     */
    private static final String ERROR_READING = "ERROR READING ";

    /**
     * {@code DISPLAY 'ERROR CLOSING <DD>'} — {@code app/cbl/CBSTM03A.CBL:L863} for {@code TRNXFILE}
     * and the same shape in {@code 9200}, {@code 9300} and {@code 9400} at {@code :L880},
     * {@code :L896} and {@code :L912}.
     */
    private static final String ERROR_CLOSING = "ERROR CLOSING ";

    /** {@code DISPLAY 'ABENDING PROGRAM'}, {@code app/cbl/CBSTM03A.CBL:L922}. */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    // ==========================================================================================
    // Initialisation. app/cbl/CBSTM03A.CBL:L293-L314 plus the five handlers it reaches.
    // ==========================================================================================

    /**
     * Runs the ordered initialisation pipeline that replaces the {@code ALTER} dispatch, then leaves
     * the caller ready to drive {@code 1000-MAINLINE}. Idempotent: a second call is a no-op.
     *
     * <p><strong>Why this is a straight line and not a state machine.</strong> {@code 0000-START}
     * ({@code app/cbl/CBSTM03A.CBL:L296-L314}) is an {@code EVALUATE WS-FL-DD} that rewrites the
     * branch target of {@code 8100-FILE-OPEN} — a paragraph whose entire body is one unconditional
     * {@code GO TO} ({@code :L726-L728}) — with {@code ALTER ... TO PROCEED TO} at {@code :L300},
     * {@code :L303}, {@code :L306} and {@code :L309}, and reaches the fifth state by a direct
     * {@code GO TO 8500-READTRNX-READ} at {@code :L311-L312} with no {@code ALTER} at all. That
     * looks like a dispatch table, and it is not one: every transition is hard-coded in its
     * handler's tail, so the machine is deterministic and admits exactly one path.
     *
     * <ol>
     *   <li>{@code 8100-TRNXFILE-OPEN} opens and primes {@code TRNXFILE}, then
     *       {@code MOVE 'READTRNX' TO WS-FL-DD} and {@code GO TO 0000-START} at {@code :L760-L761}</li>
     *   <li>{@code 8500-READTRNX-READ} builds the table, then {@code MOVE 'XREFFILE'} and
     *       {@code GO TO 0000-START} at {@code :L851-L852}</li>
     *   <li>{@code 8200-XREFFILE-OPEN}, then {@code MOVE 'CUSTFILE'} at {@code :L779-L780}</li>
     *   <li>{@code 8300-CUSTFILE-OPEN}, then {@code MOVE 'ACCTFILE'} at {@code :L797-L798}</li>
     *   <li>{@code 8400-ACCTFILE-OPEN}, then {@code GO TO 1000-MAINLINE} at {@code :L815} —
     *       leaving the state machine permanently</li>
     * </ol>
     *
     * <p>Hence five calls in that exact order followed by the mainline. Recorded in
     * {@code DECISION_LOG.md} as: self-modifying code eliminated by static flow analysis with
     * observable order preserved. The DD-keyed strategy map lives in {@link FileService}, where
     * {@code app/cbl/CBSTM03B.CBL}'s four-dataset by six-operation matrix genuinely varies; putting
     * one here would model a variability that does not exist.
     *
     * <p>{@code OPEN OUTPUT STMT-FILE HTML-FILE} at {@code :L293} is deliberately absent: the two
     * output streams belong to {@code com.cardemo.batch.writers.StatementWriter}, which opens them
     * from its {@code StepExecutionListener} callback. This class produces lines; it never writes.
     *
     * @throws com.cardemo.exception.FatalProcessingException if any of the four datasets cannot be
     * opened or primed, or if the transaction table breaches
     * {@link #MAX_TRANSACTIONS_PER_RUN} or the ascending card-number precondition.
     */
    public void initialise() {
        if (initialised) {
            return;
        }
        initialiseTransactionTable();
        String primedRecord = openAndPrimeTransactionFile();
        buildTransactionTable(primedRecord);
        openCrossReferenceFile();
        openCustomerFile();
        openAccountFile();
        initialised = true;
        LOG.info("Statement initialisation complete: cards={} transactions={}",
                transactionTable.size(), transactionsAccepted);
    }

    /**
     * {@code INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR}, {@code app/cbl/CBSTM03A.CBL:L294}.
     *
     * <p>The source zeroed a fixed 51-by-10 table and its 51 counters in place. Here the collection
     * is emptied, which is the same observable start state, and the two running counters that
     * {@code CR-CNT} and {@code TR-CNT} seeded at {@code :L758-L759} are re-derived from it.
     */
    private void initialiseTransactionTable() {
        transactionTable.clear();
        transactionsAccepted = 0L;
        endOfFile = false;
        crJmp = 0;
        trJmp = 0;
        totalAmount = BigDecimal.ZERO.setScale(EDITED_AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * {@code 8100-TRNXFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L730-L762}. Step 1 of the pipeline.
     *
     * <p>Opens {@code TRNXFILE} under the lenient guard at {@code :L736} and takes the priming read
     * under the equally lenient guard at {@code :L748} — both accept {@code '00'} or {@code '04'}.
     * This dataset is the sorted, projected work cluster that {@code app/jcl/CREASTMT.JCL} STEP020
     * REPROs from STEP010's {@code SORTOUT}, so the record read back is already in the 328-written,
     * 350-padded projection shape.
     *
     * <p>{@code MOVE WS-M03B-FLDT TO TRNX-RECORD} at {@code :L756} truncates the 1000-character
     * payload to the 350-character record; that truncation is reproduced here.
     * {@code MOVE TRNX-CARD-NUM TO WS-SAVE-CARD} at {@code :L757} together with
     * {@code MOVE 1 TO CR-CNT} at {@code :L758} and {@code MOVE 0 TO TR-CNT} at {@code :L759} seeded
     * shared working storage for the next paragraph; that seed travels as this method's return value
     * instead, so {@link #buildTransactionTable(String)} needs no shared mutable state.
     *
     * @return the primed 350-character transaction record, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the open or the priming read fails.
     */
    private String openAndPrimeTransactionFile() {
        try {
            fileService.open(FileService.Dd.TRNXFILE);
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_OPENING + FileService.Dd.TRNXFILE.ddName(), failure);
            throw failure;
        }
        try {
            return toProjectedRecord(
                    fileService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE));
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.TRNXFILE.ddName(), failure);
            throw failure;
        }
    }

    /**
     * {@code 8200-XREFFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L765-L781}. Step 3 of the pipeline.
     *
     * @throws com.cardemo.exception.FatalProcessingException if the open fails, guard at {@code :L771}.
     */
    private void openCrossReferenceFile() {
        openDataset(FileService.Dd.XREFFILE);
    }

    /**
     * {@code 8300-CUSTFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L783-L799}. Step 4 of the pipeline.
     *
     * @throws com.cardemo.exception.FatalProcessingException if the open fails, guard at {@code :L789}.
     */
    private void openCustomerFile() {
        openDataset(FileService.Dd.CUSTFILE);
    }

    /**
     * {@code 8400-ACCTFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L801-L816}. Step 5 of the pipeline,
     * and the one that leaves the collapsed state machine for good at {@code :L815}.
     *
     * @throws com.cardemo.exception.FatalProcessingException if the open fails, guard at {@code :L807}.
     */
    private void openAccountFile() {
        openDataset(FileService.Dd.ACCTFILE);
    }

    /**
     * The common body of {@code 8200}, {@code 8300} and {@code 8400}, which differ only in DD name.
     *
     * <p>Sharing the body is not consolidating paragraphs: each paragraph keeps its own method
     * above, with its own citation, and only the identical seven-line tail is expressed once
     * (Rule 1 Clause C3).
     *
     * @param dd the dataset to open
     * @throws com.cardemo.exception.FatalProcessingException if the open fails.
     */
    private void openDataset(FileService.Dd dd) {
        try {
            fileService.open(dd);
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_OPENING + dd.ddName(), failure);
            throw failure;
        }
    }

    // ==========================================================================================
    // The transaction table. app/cbl/CBSTM03A.CBL:L818-L853. Step 2 of the pipeline.
    // ==========================================================================================

    /**
     * {@code 8500-READTRNX-READ} ({@code app/cbl/CBSTM03A.CBL:L818-L847}) together with its exit
     * paragraph {@code 8599-EXIT} ({@code :L849-L853}). Loads every projected transaction record
     * into {@link #transactionTable}, grouped by card number, in the order read.
     *
     * <h2>DEVIATION — the 510-transaction ceiling is removed. Severity of the defect it removes:
     * High. Classification: labelled deviation, not parity.</h2>
     *
     * <p>The source table is declared {@code 05 WS-CARD-TBL OCCURS 51 TIMES} containing
     * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at {@code app/cbl/CBSTM03A.CBL:L225-L233}, so its hard
     * capacity is 51 cards multiplied by 10 transactions, or
     * {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}
     * transactions per run. The building loop increments <em>both</em> subscripts with no bounds
     * check whatsoever and then writes through them at {@code :L827-L829}
     * ({@code MOVE TRNX-CARD-NUM TO WS-CARD-NUM (CR-CNT)},
     * {@code MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT)},
     * {@code MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT)}). A 52nd card or an 11th transaction
     * on any card therefore writes past the end of the table into whatever follows it in
     * WORKING-STORAGE: a latent storage-overrun that corrupts data silently and produces a plausible
     * but wrong statement.
     *
     * <p><strong>This implementation uses unbounded collections, so the ceiling is gone.</strong>
     *
     * <p><strong>Justification, as Rule 1 Clause A5 requires for a tradeoff.</strong> This change is
     * made to remove a memory-corruption defect, <em>not</em> to make anything faster. Three
     * alternatives were considered and rejected. Reinstating the ceiling and truncating at 510 would
     * preserve the number but not the behaviour, because the source does not truncate — it overruns;
     * a faithful reproduction of the overrun is impossible in Java and undesirable in any language.
     * Reinstating the ceiling and failing at 510 would turn a run that the legacy system completed
     * (incorrectly) into a run that this system refuses, which is a behaviour change affecting every
     * dataset larger than the fixtures. Leaving the collection wholly unbounded with no limit at all
     * would trade a silent corruption for a silent heap exhaustion. The design adopted therefore
     * grows without a functional ceiling but fails loudly and immediately at
     * {@link #MAX_TRANSACTIONS_PER_RUN}, and emits a WARN the first time a run crosses either legacy
     * threshold so that the divergence from historical capacity is visible in the log rather than
     * inferred. The cost is unbounded heap proportional to input size; that is accepted because the
     * whole table was already resident in the source, only at a fixed 51-by-10 size.
     *
     * <p>The legacy capacity limit of {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}
     * is recorded in {@code TRACEABILITY_MATRIX.md} as this program's historical capacity, and this
     * deviation is recorded in {@code DECISION_LOG.md}.
     *
     * <h2>Self-recursion converted to iteration. Severity: Low.</h2>
     *
     * <p>{@code :L840} is {@code GO TO 8500-READTRNX-READ} — the paragraph branches to itself once
     * per record. A Java method that called itself once per transaction would overflow the stack on
     * any realistic volume, which is the class of obvious inefficiency Clause A5 forbids, so the
     * recursion becomes the {@code while} loop below. The observable order is unchanged and the
     * final-counter flush of {@code :L850} is preserved as the unconditional append after the loop.
     *
     * <h2>Ascending card-number precondition. Severity: Low. Option (1) — preserved.</h2>
     *
     * <p>{@link #emitTransactionsForCard(String, java.util.List, java.util.List)} keeps the order-dependent early exit of
     * {@code :L417-L419} verbatim, which is only correct while the table ascends by card number.
     * {@code app/jcl/CREASTMT.JCL:L53} guarantees it with
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}. Rather than relax the lookup and diverge, the
     * precondition is asserted here at load time, so an unsorted input fails at the boundary with a
     * precise message instead of silently producing short statements.
     *
     * @param primedRecord the 350-character record already read by
     * {@link #openAndPrimeTransactionFile()}, standing in for the {@code WS-SAVE-CARD},
     * {@code CR-CNT} and {@code TR-CNT} seed of {@code :L757-L759}. Never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException on a read failure, on a record that is
     * shorter than the projection writes, on input that is not ascending by card number, or on more
     * than {@link #MAX_TRANSACTIONS_PER_RUN} records.
     */
    private void buildTransactionTable(String primedRecord) {
        Objects.requireNonNull(primedRecord, "primedRecord must not be null");

        String currentRecord = primedRecord;
        // WS-SAVE-CARD, seeded by MOVE TRNX-CARD-NUM TO WS-SAVE-CARD at :L757.
        String saveCard = readCardNumber(currentRecord);
        String currentCardNumber = saveCard;
        List<StatementTransaction> currentCardTransactions = new ArrayList<>();

        while (true) {
            String cardNumber = readCardNumber(currentRecord);

            // :L819-L825. IF WS-SAVE-CARD = TRNX-CARD-NUM then ADD 1 TO TR-CNT, ELSE flush the
            // completed card's counter at :L822, ADD 1 TO CR-CNT at :L823 and restart the inner
            // count at :L824. Appending the finished group is the flush and the increment together.
            if (!saveCard.equals(cardNumber)) {
                requireAscendingCardNumber(saveCard, cardNumber);
                appendCardGroup(currentCardNumber, currentCardTransactions);
                currentCardTransactions = new ArrayList<>();
            }

            // :L827 MOVE TRNX-CARD-NUM TO WS-CARD-NUM (CR-CNT) — unconditional, both branches.
            currentCardNumber = cardNumber;

            // :L828-L829 MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT) and
            // MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT). The key and the 318-byte remainder
            // are the whole record, so one parsed carrier holds both.
            currentCardTransactions.add(parseProjectedRecord(currentRecord));
            admitTransaction(currentCardTransactions.size());

            // :L830 MOVE TRNX-CARD-NUM TO WS-SAVE-CARD.
            saveCard = cardNumber;

            // :L832-L846. Next read, then EVALUATE WS-M03B-RC accepting '00' and '10' only —
            // no '04' leniency at this site, unlike the priming read at :L748.
            Optional<String> nextRecord = readNextTransactionRecord();
            if (nextRecord.isEmpty()) {
                // :L841-L842 WHEN '10' GO TO 8599-EXIT.
                break;
            }
            // :L839 MOVE WS-M03B-FLDT TO TRNX-RECORD, then :L840 GO TO 8500-READTRNX-READ.
            currentRecord = toProjectedRecord(nextRecord.get());
        }

        // 8599-EXIT :L850 MOVE TR-CNT TO WS-TRCT (CR-CNT). The final flush, without which the last
        // card's transaction count stays zero and its statement comes out empty.
        appendCardGroup(currentCardNumber, currentCardTransactions);
    }

    /**
     * The read half of {@code 8500-READTRNX-READ}, {@code app/cbl/CBSTM03A.CBL:L832-L846}.
     *
     * @return the next payload, or empty at end of file
     * @throws com.cardemo.exception.FatalProcessingException for any status other than {@code '00'}
     * or {@code '10'}, {@code '04'} included — the guard of {@code :L837}.
     */
    private Optional<String> readNextTransactionRecord() {
        try {
            return fileService.readNext(FileService.Dd.TRNXFILE);
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.TRNXFILE.ddName(), failure);
            throw failure;
        }
    }

    /**
     * Appends one completed card group, standing in for
     * {@code MOVE TR-CNT TO WS-TRCT (CR-CNT)} at {@code app/cbl/CBSTM03A.CBL:L822} and
     * {@code :L850}. Warns the first time a run exceeds the legacy card capacity.
     *
     * @param cardNumber the group's card number, never {@code null}
     * @param transactions the group's transactions in read order, never {@code null}
     */
    private void appendCardGroup(String cardNumber, List<StatementTransaction> transactions) {
        transactionTable.add(new StatementTransaction.CardGroup(cardNumber, transactions));
        if (transactionTable.size() == StatementTransaction.LEGACY_MAX_CARDS_PER_RUN + 1) {
            LOG.warn("Card count has passed the legacy capacity of {} cards"
                            + " (WS-CARD-TBL OCCURS 51, app/cbl/CBSTM03A.CBL:L226);"
                            + " this run exceeds what the COBOL table could hold without overrun",
                    StatementTransaction.LEGACY_MAX_CARDS_PER_RUN);
        }
    }

    /**
     * Counts one admitted record against the loud limit and warns on the first crossing of the
     * legacy per-card capacity.
     *
     * <p>This is one of the bounds checks the source does not have. It exists because the
     * collections are unbounded, and it fails rather than truncates.
     *
     * @param transactionsOnCurrentCard the size of the group the record was just added to
     * @throws com.cardemo.exception.FatalProcessingException if the run exceeds
     * {@link #MAX_TRANSACTIONS_PER_RUN}.
     */
    private void admitTransaction(int transactionsOnCurrentCard) {
        transactionsAccepted++;
        if (transactionsAccepted > MAX_TRANSACTIONS_PER_RUN) {
            throw abend("The statement run exceeded the safety limit of " + MAX_TRANSACTIONS_PER_RUN
                    + " transactions. The legacy table held at most "
                    + StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN
                    + " (app/cbl/CBSTM03A.CBL:L225-L233), so an input this large indicates a"
                    + " malformed or repeating TRNXFILE rather than a legitimate workload");
        }
        if (transactionsOnCurrentCard == StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD + 1) {
            LOG.warn("A card has passed the legacy capacity of {} transactions"
                            + " (WS-TRAN-TBL OCCURS 10, app/cbl/CBSTM03A.CBL:L228);"
                            + " the COBOL table would have overrun at this point",
                    StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD);
        }
    }

    /**
     * Enforces the ascending card-number precondition that the early exit of
     * {@code app/cbl/CBSTM03A.CBL:L417-L419} depends on and that
     * {@code app/jcl/CREASTMT.JCL:L53} guarantees.
     *
     * @param previousCardNumber the card number of the group just completed
     * @param nextCardNumber the card number that broke the group
     * @throws com.cardemo.exception.FatalProcessingException if the sequence descends or repeats a
     * card that was already closed.
     */
    private void requireAscendingCardNumber(String previousCardNumber, String nextCardNumber) {
        if (nextCardNumber.compareTo(previousCardNumber) <= 0) {
            throw abend("TRNXFILE must ascend by card number, because the transaction-table lookup"
                    + " at app/cbl/CBSTM03A.CBL:L417-L419 exits early on the first card greater than"
                    + " the one sought, and app/jcl/CREASTMT.JCL:L53 sorts the input to guarantee it."
                    + " A card ordered at or before its predecessor would be silently skipped");
        }
    }

    // ==========================================================================================
    // 1000-MAINLINE, app/cbl/CBSTM03A.CBL:L316-L342, and the three reads it drives.
    // ==========================================================================================

    /**
     * Produces one account's statement, reproducing the body of the {@code 1000-MAINLINE} loop at
     * {@code app/cbl/CBSTM03A.CBL:L316-L329}.
     *
     * <p>The source loop and this method divide the same work differently, and deliberately. The
     * source drove its own iteration with {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L316},
     * fetching each cross-reference row itself at {@code :L320}; under Spring Batch the framework
     * owns iteration and hands one item in at a time. The fetch therefore lives in
     * {@link #readNextCrossReference()}, which a reader delegates to, and everything the loop body
     * did with a fetched row lives here. Both paragraphs survive; only who calls the loop changes.
     *
     * <p>Order of operations, exactly as the source has them:
     * <ol>
     *   <li>{@code PERFORM 2000-CUSTFILE-GET} at {@code :L322}</li>
     *   <li>{@code PERFORM 3000-ACCTFILE-GET} at {@code :L323}</li>
     *   <li>{@code PERFORM 5000-CREATE-STATEMENT} — the header, name, address and basic details</li>
     *   <li>{@code MOVE 1 TO CR-JMP} at {@code :L324} — the retained no-op, see below</li>
     *   <li>{@code MOVE ZERO TO WS-TOTAL-AMT} at {@code :L325}</li>
     *   <li>{@code PERFORM 4000-TRNXFILE-GET} at {@code :L326} — transactions, totals and footer</li>
     * </ol>
     *
     * <p>There is no {@code ELSE} anywhere in the source loop, so unlike the interest program there
     * is no end-of-data flush to reproduce here; the footer is written per account inside step 6.
     *
     * @param crossReference the cross-reference row for one account, as supplied by the reader,
     * never {@code null}
     * @return the rendered statement for that account, never {@code null}
     * @throws NullPointerException if {@code crossReference} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the customer or the account record
     * cannot be read, which in the source abends because neither keyed guard has a
     * {@code WHEN '10'} branch, or if a rendered line is not its declared fixed width.
     */
    @Override
    public Statement process(CardCrossReference crossReference) {
        Objects.requireNonNull(crossReference, "crossReference must not be null");
        initialise();

        List<String> textLines = new ArrayList<>();
        List<String> htmlLines = new ArrayList<>();

        // :L322 PERFORM 2000-CUSTFILE-GET.
        Customer customer = readCustomerRecord(crossReference.getCustomerId());
        // :L323 PERFORM 3000-ACCTFILE-GET.
        Account account = readAccountRecord(crossReference.getAccountId());

        // PERFORM 5000-CREATE-STATEMENT.
        createStatement(customer, account, textLines, htmlLines);

        // ------------------------------------------------------------------------------------
        // :L324 MOVE 1 TO CR-JMP.
        //
        // INTENTIONAL NO-OP, RETAINED FOR CONTROL-FLOW PARITY. Severity: Low.
        //
        // This assignment has no effect. The very next thing that reads crJmp is the
        // PERFORM VARYING at :L417-L418, which re-initialises it to 1 before its first test, so
        // the value written here is overwritten before it is ever observed.
        //
        // It is retained rather than deleted because deleting it would break the paragraph-level
        // correspondence that TRACEABILITY_MATRIX.md asserts and Gate 7 verifies. Rule 1 Clause B1
        // forbids UNTRACKED dead code; this statement is cited to its source line, marked here, and
        // registered in DECISION_LOG.md as one of the tree's retained-for-parity artefacts, so it is
        // tracked rather than abandoned. This is the one documented Rule 1 conflict in this file and
        // parity governs it. It is deliberately not a deferred-work marker: nothing is deferred.
        //
        // LOCATOR CORRECTION: the folder requirements place this statement at :L325. It is at :L324;
        // :L325 is MOVE ZERO TO WS-TOTAL-AMT, immediately below. The source wins.
        // ------------------------------------------------------------------------------------
        crJmp = 1;

        // :L325 MOVE ZERO TO WS-TOTAL-AMT.
        totalAmount = BigDecimal.ZERO.setScale(EDITED_AMOUNT_SCALE, RoundingMode.HALF_EVEN);

        // :L326 PERFORM 4000-TRNXFILE-GET.
        emitTransactionsForCard(crossReference.getCardNumber(), textLines, htmlLines);

        statementsProduced++;
        return new Statement(renderAccountId(account.getAccountId()), totalAmount, textLines, htmlLines);
    }

    /**
     * {@code 1000-XREFFILE-GET-NEXT}, {@code app/cbl/CBSTM03A.CBL:L345-L366}.
     *
     * <p>Reads the next cross-reference row under the get-next guard at {@code :L353}, which accepts
     * {@code '00'}, treats {@code '10'} as end of file by setting {@code END-OF-FILE} to {@code 'Y'}
     * at {@code :L357}, and abends on anything else — {@code '04'} included. The
     * {@code MOVE WS-M03B-FLDT TO CARD-XREF-RECORD} of {@code :L364} becomes the parse below.
     *
     * <p>Public because the driving stream is this paragraph and nothing else: a Spring Batch reader
     * over {@code XREFFILE} delegates here rather than duplicating the guard. The
     * {@code IF END-OF-FILE = 'N'} tests of {@code :L317} and {@code :L321} are honoured by the
     * short-circuit at the top, so a caller that keeps asking after the end keeps getting nothing
     * instead of driving a further read.
     *
     * @return the next cross-reference row, or empty once the dataset is exhausted, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException for any status other than {@code '00'}
     * or {@code '10'}.
     */
    public Optional<CardCrossReference> readNextCrossReference() {
        initialise();
        if (endOfFile) {
            return Optional.empty();
        }
        Optional<String> payload;
        try {
            payload = fileService.readNext(FileService.Dd.XREFFILE);
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.XREFFILE.ddName(), failure);
            throw failure;
        }
        if (payload.isEmpty()) {
            // :L356-L357 WHEN '10' MOVE 'Y' TO END-OF-FILE.
            endOfFile = true;
            return Optional.empty();
        }
        return Optional.of(parseCrossReferenceRecord(
                truncateOrPad(payload.get(), CARD_XREF_RECORD_LENGTH)));
    }

    /**
     * {@code 2000-CUSTFILE-GET}, {@code app/cbl/CBSTM03A.CBL:L368-L390}.
     *
     * <p>Keyed read on {@code XREF-CUST-ID} with the key length computed by
     * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID} at {@code :L374}, which is 9. The
     * guard at {@code :L379} accepts {@code '00'} and nothing else and carries no
     * {@code WHEN '10'} branch at all, so a missing customer stops the run rather than skipping the
     * account. That asymmetry against the sequential sites is preserved by using the keyed read.
     *
     * @param customerId the cross-reference row's customer identifier, never {@code null}
     * @return the customer record, never {@code null}
     * @throws NullPointerException if {@code customerId} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the record is absent or the read fails.
     */
    private Customer readCustomerRecord(Long customerId) {
        Objects.requireNonNull(customerId, "customerId must not be null");
        String key = renderKey(customerId, CUSTOMER_KEY_LENGTH);
        try {
            String payload = fileService.readByKey(FileService.Dd.CUSTFILE, key, CUSTOMER_KEY_LENGTH);
            // :L388 MOVE WS-M03B-FLDT TO CUSTOMER-RECORD.
            return parseCustomerRecord(truncateOrPad(payload, CUSTOMER_RECORD_LENGTH));
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.CUSTFILE.ddName(), failure);
            throw failure;
        }
    }

    /**
     * {@code 3000-ACCTFILE-GET}, {@code app/cbl/CBSTM03A.CBL:L392-L414}.
     *
     * <p>Keyed read on {@code XREF-ACCT-ID} with the key length computed at {@code :L398}, which is
     * 11. The guard at {@code :L403} is the same strict shape as its customer sibling.
     *
     * @param accountId the cross-reference row's account identifier, never {@code null}
     * @return the account record, never {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the record is absent or the read fails.
     */
    private Account readAccountRecord(Long accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        String key = renderKey(accountId, ACCOUNT_KEY_LENGTH);
        try {
            String payload = fileService.readByKey(FileService.Dd.ACCTFILE, key, ACCOUNT_KEY_LENGTH);
            // :L412 MOVE WS-M03B-FLDT TO ACCOUNT-RECORD.
            return parseAccountRecord(truncateOrPad(payload, ACCOUNT_RECORD_LENGTH));
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.ACCTFILE.ddName(), failure);
            throw failure;
        }
    }

    // ==========================================================================================
    // Statement body. app/cbl/CBSTM03A.CBL:L458-L723.
    // ==========================================================================================

    /**
     * {@code 5000-CREATE-STATEMENT}, {@code app/cbl/CBSTM03A.CBL:L458-L504}. Emits the opening
     * banner, the assembled name, the three address lines and the three basic-detail lines on both
     * streams.
     *
     * <p>{@code INITIALIZE STATEMENT-LINES} at {@code :L459} resets the named data fields but leaves
     * every {@code FILLER VALUE} clause standing, which is why the banners, rules and column
     * headings are constants here and are re-emitted verbatim for every account.
     *
     * <p>The fifteen {@code WRITE}s at {@code :L488-L502} are emitted in the source's exact order,
     * which repeats two lines: {@code ST-LINE5} at {@code :L492} and again at {@code :L494}, and
     * {@code ST-LINE12} at {@code :L500} and again at {@code :L502}. The repetition is intentional in
     * the source layout and is reproduced.
     *
     * @param customer the customer record from {@code 2000-CUSTFILE-GET}, never {@code null}
     * @param account the account record from {@code 3000-ACCTFILE-GET}, never {@code null}
     * @param textLines the {@code STMTFILE} accumulator, appended to
     * @param htmlLines the {@code HTMLFILE} accumulator, appended to
     */
    private void createStatement(Customer customer, Account account,
            List<String> textLines, List<String> htmlLines) {
        // :L460 WRITE FD-STMTFILE-REC FROM ST-LINE0.
        textLines.add(ST_LINE0);

        // :L461 PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT.
        writeHtmlHeader(account, htmlLines);

        // :L462-L469 STRING ... INTO ST-NAME. Each name part is copied up to its FIRST space, and a
        // single space is appended after every one of the three - including the last, which is the
        // trailing ' ' DELIMITED BY SIZE at :L467.
        String assembledName = truncateOrPad(
                stringDelimitedBySpace(customer.getFirstName(), CUSTOMER_NAME_PART_WIDTH) + SINGLE_SPACE
                        + stringDelimitedBySpace(customer.getMiddleName(), CUSTOMER_NAME_PART_WIDTH) + SINGLE_SPACE
                        + stringDelimitedBySpace(customer.getLastName(), CUSTOMER_NAME_PART_WIDTH) + SINGLE_SPACE,
                ST_NAME_WIDTH);

        // :L470-L471 plain MOVEs, so the whole 50-byte address line is taken, spaces and all.
        String addressLine1 = truncateOrPad(customer.getAddressLine1(), ST_ADDRESS_WIDTH);
        String addressLine2 = truncateOrPad(customer.getAddressLine2(), ST_ADDRESS_WIDTH);

        // :L472-L481 STRING ... INTO ST-ADD3, same shape as the name: four parts, each up to its
        // first space, each followed by one space.
        String addressLine3 = truncateOrPad(
                stringDelimitedBySpace(customer.getAddressLine3(), CUSTOMER_ADDRESS_LINE_WIDTH) + SINGLE_SPACE
                        + stringDelimitedBySpace(customer.getAddressStateCode(), CUSTOMER_STATE_CODE_WIDTH)
                        + SINGLE_SPACE
                        + stringDelimitedBySpace(customer.getAddressCountryCode(), CUSTOMER_COUNTRY_CODE_WIDTH)
                        + SINGLE_SPACE
                        + stringDelimitedBySpace(customer.getAddressZip(), CUSTOMER_ZIP_WIDTH) + SINGLE_SPACE,
                ST_ADD3_WIDTH);

        // :L483 MOVE ACCT-ID TO ST-ACCT-ID: PIC 9(11) into PIC X(20) is left justified, space filled.
        String renderedAccountId = truncateOrPad(renderAccountId(account.getAccountId()), ST_LABELLED_VALUE_WIDTH);
        // :L484 MOVE ACCT-CURR-BAL TO ST-CURR-BAL: the unsuppressed 9(9).99- mask.
        String renderedBalance = formatUnsuppressedAmount(account.getCurrentBalance());
        // :L485 MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE: PIC 9(03) into PIC X(20).
        String renderedFicoScore = truncateOrPad(
                renderFicoScore(customer.getFicoCreditScore()), ST_LABELLED_VALUE_WIDTH);

        // :L486 PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT.
        writeHtmlNameAddressBasics(assembledName, addressLine1, addressLine2, addressLine3,
                renderedAccountId, renderedBalance, renderedFicoScore, htmlLines);

        // :L488-L502, in order, with ST-LINE5 and ST-LINE12 each written twice.
        textLines.add(truncateOrPad(assembledName, ST_NAME_WIDTH) + spaces(ST_LINE1_FILLER_WIDTH));
        textLines.add(addressLine1 + spaces(ST_ADDRESS_FILLER_WIDTH));
        textLines.add(addressLine2 + spaces(ST_ADDRESS_FILLER_WIDTH));
        textLines.add(addressLine3);
        textLines.add(ST_RULE_LINE);
        textLines.add(ST_LINE6);
        textLines.add(ST_RULE_LINE);
        textLines.add(ST_LABEL_ACCOUNT_ID + renderedAccountId + spaces(ST_LABELLED_LINE_FILLER_WIDTH));
        textLines.add(ST_LABEL_CURRENT_BALANCE + renderedBalance + spaces(ST_LINE8_FILLER_WIDTH));
        textLines.add(ST_LABEL_FICO_SCORE + renderedFicoScore + spaces(ST_LABELLED_LINE_FILLER_WIDTH));
        textLines.add(ST_RULE_LINE);
        textLines.add(ST_LINE11);
        textLines.add(ST_RULE_LINE);
        textLines.add(ST_LINE13);
        textLines.add(ST_RULE_LINE);
    }

    /**
     * {@code 5100-WRITE-HTML-HEADER} through {@code 5100-EXIT},
     * {@code app/cbl/CBSTM03A.CBL:L506-L555}. Twenty-three HTML lines: the document preamble, the
     * account-number heading and the bank's own name and address block.
     *
     * <p>Every line is one {@code SET <condition> TO TRUE} followed by
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}, except the account heading at
     * {@code :L529-L530}, which fills {@code L11-ACCT} and writes the {@code HTML-L11} group instead.
     *
     * @param account the account whose identifier goes into {@code L11-ACCT}, never {@code null}
     * @param htmlLines the {@code HTMLFILE} accumulator, appended to
     */
    private void writeHtmlHeader(Account account, List<String> htmlLines) {
        emitHtml(htmlLines, HTML_L01);
        emitHtml(htmlLines, HTML_L02);
        emitHtml(htmlLines, HTML_L03);
        emitHtml(htmlLines, HTML_L04);
        emitHtml(htmlLines, HTML_L05);
        emitHtml(htmlLines, HTML_L06);
        emitHtml(htmlLines, HTML_L07);
        emitHtml(htmlLines, HTML_L08);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L10);

        // :L529 MOVE ACCT-ID TO L11-ACCT ; :L530 WRITE FD-HTMLFILE-REC FROM HTML-L11.
        emitHtml(htmlLines, HTML_L11_PREFIX
                + truncateOrPad(renderAccountId(account.getAccountId()), L11_ACCT_WIDTH)
                + HTML_L11_SUFFIX);

        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L15);
        emitHtml(htmlLines, HTML_L16);
        emitHtml(htmlLines, HTML_L17);
        emitHtml(htmlLines, HTML_L18);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L22_35);
    }

    /**
     * {@code 5200-WRITE-HTML-NMADBS} through {@code 5200-EXIT},
     * {@code app/cbl/CBSTM03A.CBL:L558-L672}. The customer's name, the three address lines, the three
     * basic-detail lines, and the table scaffolding and column headings that follow them.
     *
     * <p><strong>The name and address lines lose data, and that is preserved. Severity: Low.</strong>
     * {@code :L560} moves {@code ST-NAME PIC X(75)} into {@code L23-NAME PIC X(50)}, truncating at 50.
     * Then each of the four {@code STRING}s at {@code :L562}, {@code :L570}, {@code :L578} and
     * {@code :L586} copies its value {@code DELIMITED BY '  '} — two spaces — which stops at the
     * first pair of adjacent spaces. Because the name assembled at {@code :L462-L469} inserts a
     * separator space after each part, a customer with an empty middle name yields a double space
     * immediately after the first name, so the HTML name line silently drops the surname while the
     * text line keeps it. The same applies to any address line with an internal double space.
     * Reproduced exactly; not repaired.
     *
     * <p><strong>The three basic-detail lines use {@code DELIMITED BY '*'} on the data operand</strong>
     * ({@code :L615}, {@code :L622}, {@code :L629}). No asterisk occurs in those values, so the
     * delimiter never matches and the whole padded field is copied — trailing spaces included. That
     * is why those three lines are built from the already-padded values rather than trimmed ones.
     *
     * <p><strong>No HTML escaping is performed, and none is introduced. Severity: Medium.</strong>
     * The source interpolates customer name and address straight into markup with no escaping
     * anywhere in {@code :L558-L672}. Adding escaping would change the byte offsets of every affected
     * line and so break the Gate 1 comparison against the legacy baseline, so the omission is logged
     * in {@code DECISION_LOG.md} rather than fixed. The 34 markup fragments are fixed literals from
     * the source and carry no interpolation at all.
     *
     * @param assembledName {@code ST-NAME}, already 75 characters
     * @param addressLine1 {@code ST-ADD1}, already 50 characters
     * @param addressLine2 {@code ST-ADD2}, already 50 characters
     * @param addressLine3 {@code ST-ADD3}, already 80 characters
     * @param renderedAccountId {@code ST-ACCT-ID}, already 20 characters
     * @param renderedBalance {@code ST-CURR-BAL}, already 13 characters
     * @param renderedFicoScore {@code ST-FICO-SCORE}, already 20 characters
     * @param htmlLines the {@code HTMLFILE} accumulator, appended to
     */
    private void writeHtmlNameAddressBasics(String assembledName, String addressLine1, String addressLine2,
            String addressLine3, String renderedAccountId, String renderedBalance, String renderedFicoScore,
            List<String> htmlLines) {
        // :L560-L568. Note the truncation to L23-NAME's 50 characters happens BEFORE the two-space
        // delimiter is applied, so both effects compound exactly as in the source.
        emitHtml(htmlLines, HTML_L23_PREFIX
                + stringDelimitedByDoubleSpace(truncateOrPad(assembledName, L23_NAME_WIDTH))
                + HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);

        // :L569-L592, three address lines through HTML-ADDR-LN.
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + stringDelimitedByDoubleSpace(addressLine1)
                + HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + stringDelimitedByDoubleSpace(addressLine2)
                + HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + stringDelimitedByDoubleSpace(addressLine3)
                + HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);

        // :L594-L611.
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L30_42);
        emitHtml(htmlLines, HTML_L31);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L22_35);

        // :L613-L633, three basic-detail lines through HTML-BSIC-LN.
        emitHtml(htmlLines, HTML_LABEL_ACCOUNT_ID + renderedAccountId + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LABEL_CURRENT_BALANCE + renderedBalance + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LABEL_FICO_SCORE + renderedFicoScore + HTML_PARAGRAPH_CLOSE);

        // :L634-L669.
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L30_42);
        emitHtml(htmlLines, HTML_L43);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L47);
        emitHtml(htmlLines, HTML_L48);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L50);
        emitHtml(htmlLines, HTML_L51);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L53);
        emitHtml(htmlLines, HTML_L54);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
    }

    /**
     * {@code 4000-TRNXFILE-GET}, {@code app/cbl/CBSTM03A.CBL:L416-L456}. Walks the transaction table
     * for one card, emits a line per transaction on both streams, then emits the per-account total
     * and the closing banner and markup.
     *
     * <p><strong>The early exit is order-dependent, and option (1) is taken: it is preserved
     * verbatim. Severity: Low.</strong> The outer {@code PERFORM VARYING} at {@code :L417-L419} stops
     * as soon as {@code WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM}, which is correct only while the table
     * ascends by card number. {@code app/jcl/CREASTMT.JCL:L53} sorts the input to guarantee that, and
     * {@link #buildTransactionTable(String)} asserts it at load time. The alternative — making the
     * lookup order-independent — was rejected because it changes which records are found when the
     * input is unsorted, and that is a divergence rather than a translation.
     *
     * <p>{@code :L420} carries a further equality test inside the loop, so a card that sorts before
     * the one sought is skipped rather than matched. Both tests are reproduced.
     *
     * <p>{@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code :L429} happens <em>after</em>
     * {@code PERFORM 6000-WRITE-TRANS} at {@code :L428}, so the per-transaction line is emitted
     * before the running total is updated. The order is immaterial to the output but is preserved.
     *
     * <p>{@code :L433-L434} move the total through {@code WS-TRN-AMT PIC S9(9)V99} before it reaches
     * {@code ST-TOTAL-TRAMT}. Both items hold nine integer digits and two decimals, so the
     * intermediate move is value-preserving and collapses harmlessly; noted for completeness
     * (severity Low). The footer at {@code :L435-L437} is written unconditionally, so a card with no
     * transactions at all still produces a total line reading zero — reproduced.
     *
     * @param cardNumber {@code XREF-CARD-NUM} for the account being rendered, never {@code null}
     * @param textLines the {@code STMTFILE} accumulator, appended to
     * @param htmlLines the {@code HTMLFILE} accumulator, appended to
     */
    private void emitTransactionsForCard(String cardNumber, List<String> textLines, List<String> htmlLines) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        String soughtCardNumber = truncateOrPad(cardNumber, XREF_CARD_NUMBER_LENGTH);

        // :L417-L419 PERFORM VARYING CR-JMP FROM 1 BY 1 UNTIL CR-JMP > CR-CNT
        //                OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM).
        // crJmp is one-based here because the COBOL subscript is; the list index is crJmp - 1.
        for (crJmp = 1; crJmp <= transactionTable.size(); crJmp++) {
            StatementTransaction.CardGroup group = transactionTable.get(crJmp - 1);
            String tableCardNumber = truncateOrPad(group.cardNumber(), XREF_CARD_NUMBER_LENGTH);
            if (tableCardNumber.compareTo(soughtCardNumber) > 0) {
                break;
            }
            // :L420 IF XREF-CARD-NUM = WS-CARD-NUM (CR-JMP).
            if (!tableCardNumber.equals(soughtCardNumber)) {
                continue;
            }
            List<StatementTransaction> transactions = group.transactions();
            // :L422-L423 PERFORM VARYING TR-JMP FROM 1 BY 1 UNTIL (TR-JMP > WS-TRCT (CR-JMP)).
            for (trJmp = 1; trJmp <= transactions.size(); trJmp++) {
                StatementTransaction transaction = transactions.get(trJmp - 1);
                // :L428 PERFORM 6000-WRITE-TRANS.
                writeTransaction(transaction, textLines, htmlLines);
                // :L429 ADD TRNX-AMT TO WS-TOTAL-AMT.
                totalAmount = totalAmount.add(amountOf(transaction))
                        .setScale(EDITED_AMOUNT_SCALE, RoundingMode.HALF_EVEN);
            }
        }

        // :L433-L436 the total line, and :L435 / :L437 the rule and closing banner.
        textLines.add(ST_RULE_LINE);
        textLines.add(ST_LABEL_TOTAL_EXPENDITURE + spaces(ST_LINE14A_FILLER_WIDTH) + ST_CURRENCY_SIGN
                + formatSuppressedAmount(totalAmount));
        textLines.add(ST_LINE15);

        // :L439-L454, the eight closing fragments.
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L10);
        emitHtml(htmlLines, HTML_L75);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
        emitHtml(htmlLines, HTML_L78);
        emitHtml(htmlLines, HTML_L79);
        emitHtml(htmlLines, HTML_L80);
    }

    /**
     * {@code 6000-WRITE-TRANS}, {@code app/cbl/CBSTM03A.CBL:L675-L723}. One transaction: a single
     * 80-character text line and eleven 100-character HTML lines.
     *
     * <p>{@code MOVE TRNX-DESC TO ST-TRANDT} at {@code :L677} moves {@code PIC X(100)} into
     * {@code PIC X(49)}, truncating the description at 49 characters on both streams. Preserved.
     *
     * @param transaction the table entry to render, never {@code null}
     * @param textLines the {@code STMTFILE} accumulator, appended to
     * @param htmlLines the {@code HTMLFILE} accumulator, appended to
     */
    private void writeTransaction(StatementTransaction transaction,
            List<String> textLines, List<String> htmlLines) {
        // :L676-L678.
        String renderedId = truncateOrPad(transaction.transactionId(), ST_TRANID_WIDTH);
        String renderedDescription = truncateOrPad(transaction.description(), ST_TRANDT_WIDTH);
        String renderedAmount = formatSuppressedAmount(amountOf(transaction));

        // :L679 WRITE FD-STMTFILE-REC FROM ST-LINE14.
        textLines.add(renderedId + spaces(ST_LINE14_SEPARATOR_WIDTH) + renderedDescription
                + ST_CURRENCY_SIGN + renderedAmount);

        // :L681-L721.
        emitHtml(htmlLines, HTML_LTRS);
        emitHtml(htmlLines, HTML_L58);
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + renderedId + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L61);
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + renderedDescription + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L64);
        emitHtml(htmlLines, HTML_PARAGRAPH_OPEN + renderedAmount + HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_LTRE);
    }

    // ==========================================================================================
    // Shutdown. app/cbl/CBSTM03A.CBL:L331-L342 and L856-L923.
    // ==========================================================================================

    /**
     * Closes the four input datasets in the source's order, reproducing {@code :L331-L337} of
     * {@code 1000-MAINLINE} and the four close paragraphs it performs — {@code 9100-TRNXFILE-CLOSE}
     * at {@code app/cbl/CBSTM03A.CBL:L856-L871}, {@code 9200-XREFFILE-CLOSE} at {@code :L873-L887},
     * {@code 9300-CUSTFILE-CLOSE} at {@code :L889-L903} and {@code 9400-ACCTFILE-CLOSE} at
     * {@code :L905-L919}. Each applies the lenient guard accepting {@code '00'} or {@code '04'}, at
     * {@code :L862}, {@code :L879}, {@code :L895} and {@code :L911} respectively.
     *
     * <p>Each of the four keeps its own method - {@link #closeTransactionFile()},
     * {@link #closeCrossReferenceFile()}, {@link #closeCustomerFile()} and
     * {@link #closeAccountFile()} - so the paragraph correspondence stays one-to-one, exactly as the
     * three open paragraphs do. They share {@link #closeDataset(FileService.Dd)} because the four
     * bodies are byte-identical apart from the DD name.
     *
     * <p>{@code CLOSE STMT-FILE HTML-FILE} at {@code :L339} is deliberately absent for the same
     * reason its {@code OPEN} is: the two output streams belong to
     * {@code com.cardemo.batch.writers.StatementWriter}.
     *
     * <p>{@code 9999-GOBACK} at {@code :L341-L342} is the program's return to its caller and has no
     * counterpart beyond this method returning.
     *
     * <p>Every dataset is attempted even if an earlier close fails, so a single failure cannot leak
     * the remaining three; the first failure is the one propagated and any later one is attached to
     * it as suppressed, so no context is lost (Rule 1 Clause B4).
     *
     * @throws com.cardemo.exception.FatalProcessingException if any close reports a status other than
     * {@code '00'} or {@code '04'}.
     */
    public void close() {
        CardDemoException firstFailure = collectFailure(null, closeTransactionFile());
        firstFailure = collectFailure(firstFailure, closeCrossReferenceFile());
        firstFailure = collectFailure(firstFailure, closeCustomerFile());
        firstFailure = collectFailure(firstFailure, closeAccountFile());

        initialised = false;
        LOG.info("Statement run complete: statements={} cards={} transactions={}",
                statementsProduced, transactionTable.size(), transactionsAccepted);
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * {@code 9100-TRNXFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L856-L871}, performed at {@code :L331}.
     *
     * @return the failure if the close reported an unacceptable status, otherwise {@code null}
     */
    private CardDemoException closeTransactionFile() {
        return closeDataset(FileService.Dd.TRNXFILE);
    }

    /**
     * {@code 9200-XREFFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L873-L887}, performed at {@code :L333}.
     *
     * @return the failure if the close reported an unacceptable status, otherwise {@code null}
     */
    private CardDemoException closeCrossReferenceFile() {
        return closeDataset(FileService.Dd.XREFFILE);
    }

    /**
     * {@code 9300-CUSTFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L889-L903}, performed at {@code :L335}.
     *
     * @return the failure if the close reported an unacceptable status, otherwise {@code null}
     */
    private CardDemoException closeCustomerFile() {
        return closeDataset(FileService.Dd.CUSTFILE);
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L905-L919}, performed at {@code :L337}.
     *
     * @return the failure if the close reported an unacceptable status, otherwise {@code null}
     */
    private CardDemoException closeAccountFile() {
        return closeDataset(FileService.Dd.ACCTFILE);
    }

    /**
     * The common body of the four close paragraphs, which differ only in DD name. Each keeps its own
     * method above with its own citation; only the identical body is expressed once.
     *
     * <p>Returns the failure instead of throwing it so that {@link #close()} can attempt every dataset
     * and still surface the first failure with the rest attached as suppressed.
     *
     * @param dd the dataset to close
     * @return the failure, or {@code null} on success
     */
    private CardDemoException closeDataset(FileService.Dd dd) {
        try {
            fileService.close(dd);
            return null;
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_CLOSING + dd.ddName(), failure);
            return failure;
        }
    }

    /**
     * Accumulates close failures: the first is kept and every later one is attached to it as
     * suppressed, so no diagnostic is lost (Rule 1 Clause B4).
     *
     * @param existing the failure kept so far, possibly {@code null}
     * @param candidate the newest failure, possibly {@code null}
     * @return the failure to keep, possibly {@code null}
     */
    private static CardDemoException collectFailure(CardDemoException existing, CardDemoException candidate) {
        if (candidate == null) {
            return existing;
        }
        if (existing == null) {
            return candidate;
        }
        existing.addSuppressed(candidate);
        return existing;
    }

    /**
     * {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBSTM03A.CBL:L921-L923}.
     *
     * <p>The source paragraph is exactly two statements: {@code DISPLAY 'ABENDING PROGRAM'} then
     * {@code CALL 'CEE3ABD'}.
     *
     * <p><strong>Divergence from the corpus norm, severity Low.</strong> Unlike
     * {@code app/cbl/CBTRN02C.cbl:L707-L711}, this program's abend paragraph carries <em>no</em>
     * {@code MOVE 999 TO ABCODE} and <em>no</em> {@code MOVE 0 TO TIMING}: it calls the language
     * environment abend service with whatever those fields already held. The Java abend nonetheless
     * carries abend code {@link FatalProcessingException#BATCH_ABEND_CODE} and return code
     * {@link FatalProcessingException#BATCH_RETURN_CODE}, because those are the tree-wide batch abend
     * contract and are already declared on that class — they are referenced here, never redeclared.
     *
     * <p>Returns the exception rather than throwing it so that call sites read
     * {@code throw abend(...)} and the compiler can see the path terminates.
     *
     * @param reason the diagnostic detail, never {@code null}
     * @return the exception to throw, never {@code null}
     */
    private FatalProcessingException abend(String reason) {
        LOG.error("{}: {}", ABENDING_PROGRAM, reason);
        return new FatalProcessingException(reason);
    }

    // ==========================================================================================
    // app/jcl/CREASTMT.JCL STEP010. The sort and the record projection that produce TRNXFILE.
    // ==========================================================================================

    /**
     * The comparator form of {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
     * {@code app/jcl/CREASTMT.JCL:L53}: ascending by card number at offset 263 for 16 characters,
     * then ascending by transaction identifier at offset 1 for 16 characters.
     *
     * <p>Both keys are {@code CH}, so both compare as characters and not as numbers, which for
     * fixed-width zero-padded digits is the same ordering. Nulls sort first on either key so that a
     * malformed row cannot throw inside the sort.
     *
     * <p>This ordering is the precondition that {@link #buildTransactionTable(String)} asserts and
     * that the early exit of {@code app/cbl/CBSTM03A.CBL:L417-L419} depends on. No external sort
     * process is spawned: {@code Runtime.exec} and {@code ProcessBuilder} are not used anywhere in
     * this class.
     *
     * @return the DFSORT ordering as a comparator, never {@code null}
     */
    public static Comparator<Transaction> statementSortComparator() {
        return Comparator
                .comparing(Transaction::getCardNumber, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Transaction::getTransactionId, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * Reproduces {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at
     * {@code app/jcl/CREASTMT.JCL:L54} exactly, including everything it loses.
     *
     * <table>
     *   <caption>The projection, resolved against {@code app/cpy/CVTRA05Y.cpy} and
     *   {@code app/cpy/COSTM01.CPY}</caption>
     *   <tr><th>Output bytes</th><th>Input bytes</th><th>Content</th></tr>
     *   <tr><td>1-16</td><td>263-278</td>
     *       <td>the card number, relocated to the front as the key and NOT re-emitted mid-record</td></tr>
     *   <tr><td>17-278</td><td>1-262</td>
     *       <td>the original record head, transaction identifier through merchant postal code</td></tr>
     *   <tr><td>279-328</td><td>279-328</td>
     *       <td>the full 26-byte originating timestamp, then only the FIRST 24 of the 26
     *       processing-timestamp bytes</td></tr>
     *   <tr><td>329-330</td><td>—</td><td>NEVER WRITTEN: the last 2 bytes of the processing timestamp</td></tr>
     *   <tr><td>331-350</td><td>—</td><td>NEVER WRITTEN: the 20-byte filler is dropped entirely</td></tr>
     * </table>
     *
     * <p><strong>328 bytes are written and the record is padded to 350</strong> by the
     * {@code LRECL=350} on {@code SORTOUT} at {@code app/jcl/CREASTMT.JCL:L50}. The processing
     * timestamp therefore arrives downstream as a 24-character value in a 26-character field.
     *
     * <p><strong>The two-byte truncation is deliberate and must not be repaired.</strong> Gate 1
     * compares statement output against the legacy baseline, so a "corrected" 26-character timestamp
     * would register as a difference that looks like a Java defect and is not one. This is also why
     * {@code originatingTimestamp} and {@code processingTimestamp} are carried as
     * {@link String} throughout and never as a temporal type: a 24-character truncation is not a
     * well-formed timestamp at all. Recorded in {@code DECISION_LOG.md}.
     *
     * <p>A base record shorter than {@link StatementTransaction#RECORD_LENGTH} is rejected rather
     * than padded. Padding would be silent corruption of exactly the kind Rule 1 Clause A2 forbids:
     * the card number this projection relocates to the front lives at offsets 263-278, so a short
     * record has no card number at all, and padding one would yield a statement record keyed on
     * spaces that groups every such transaction under one phantom card. A longer buffer is truncated,
     * because {@code LRECL=350} at {@code app/jcl/CREASTMT.JCL:L50} is the declared width and an
     * over-allocated buffer is legitimate.
     *
     * @param baseRecord a 350-byte {@code TRAN-RECORD} in {@code app/cpy/CVTRA05Y.cpy} layout; a
     * longer value is truncated to the declared width, never {@code null}
     * @return the projected 350-character record whose last 22 positions are spaces, never {@code null}
     * @throws NullPointerException if {@code baseRecord} is {@code null}
     * @throws IllegalArgumentException if {@code baseRecord} is shorter than
     * {@link StatementTransaction#RECORD_LENGTH}.
     */
    public static String projectBaseRecord(String baseRecord) {
        Objects.requireNonNull(baseRecord, "baseRecord must not be null");
        if (baseRecord.length() < StatementTransaction.RECORD_LENGTH) {
            throw new IllegalArgumentException("A base transaction record must be at least "
                    + StatementTransaction.RECORD_LENGTH + " characters, because SORT FIELDS at"
                    + " app/jcl/CREASTMT.JCL:L53 reads the card number from offsets "
                    + StatementTransaction.BASE_CARD_NUMBER_OFFSET + "-"
                    + (StatementTransaction.BASE_CARD_NUMBER_OFFSET
                        + StatementTransaction.CARD_NUMBER_LENGTH - 1)
                    + ", but only " + baseRecord.length() + " were supplied");
        }
        String source = truncateOrPad(baseRecord, StatementTransaction.RECORD_LENGTH);

        int cardNumberFrom = StatementTransaction.BASE_CARD_NUMBER_OFFSET - 1;
        int cardNumberTo = cardNumberFrom + StatementTransaction.CARD_NUMBER_LENGTH;
        int tailFrom = StatementTransaction.BASE_TAIL_OFFSET - 1;
        int tailTo = tailFrom + StatementTransaction.BASE_TAIL_LENGTH;

        String projected = source.substring(cardNumberFrom, cardNumberTo)
                + source.substring(0, StatementTransaction.BASE_HEAD_LENGTH)
                + source.substring(tailFrom, tailTo);

        if (projected.length() != StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION) {
            throw new IllegalStateException("The OUTREC projection must write exactly "
                    + StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION + " bytes but produced "
                    + projected.length());
        }
        return padRight(projected, StatementTransaction.RECORD_LENGTH);
    }

    /**
     * Renders a {@link Transaction} into its 350-byte {@code app/cpy/CVTRA05Y.cpy} image and then
     * applies {@link #projectBaseRecord(String)}, so that a transaction held in the database can feed
     * the same {@code STEP010} pipeline as one read from the sequential dataset.
     *
     * <p>Signed money is encoded with the trailing zoned-decimal overpunch the fixtures use, so the
     * rendering round-trips through {@link #projectBaseRecord(String)} and back byte for byte.
     *
     * @param transaction the entity to render, never {@code null}
     * @return the projected 350-character record, never {@code null}
     * @throws NullPointerException if {@code transaction} is {@code null}
     */
    public static String projectBaseRecord(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        StringBuilder record = new StringBuilder(StatementTransaction.RECORD_LENGTH);
        record.append(truncateOrPad(transaction.getTransactionId(), StatementTransaction.TRANSACTION_ID_LENGTH));
        record.append(truncateOrPad(transaction.getTypeCode(), StatementTransaction.TYPE_CODE_LENGTH));
        record.append(renderUnsignedDigits(transaction.getCategoryCode(), StatementTransaction.CATEGORY_CODE_LENGTH));
        record.append(truncateOrPad(transaction.getTransactionSource(), StatementTransaction.SOURCE_LENGTH));
        record.append(truncateOrPad(transaction.getDescription(), StatementTransaction.DESCRIPTION_LENGTH));
        record.append(encodeZonedDecimal(transaction.getAmount(), StatementTransaction.AMOUNT_LENGTH));
        record.append(renderUnsignedDigits(transaction.getMerchantId(), StatementTransaction.MERCHANT_ID_LENGTH));
        record.append(truncateOrPad(transaction.getMerchantName(), StatementTransaction.MERCHANT_NAME_LENGTH));
        record.append(truncateOrPad(transaction.getMerchantCity(), StatementTransaction.MERCHANT_CITY_LENGTH));
        record.append(truncateOrPad(transaction.getMerchantZip(), StatementTransaction.MERCHANT_ZIP_LENGTH));
        record.append(truncateOrPad(transaction.getCardNumber(), StatementTransaction.CARD_NUMBER_LENGTH));
        record.append(truncateOrPad(transaction.getOrigTs(), StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH));
        record.append(truncateOrPad(transaction.getProcTs(), StatementTransaction.PROCESSING_TIMESTAMP_LENGTH));
        record.append(spaces(StatementTransaction.FILLER_LENGTH));
        return projectBaseRecord(record.toString());
    }

    // ==========================================================================================
    // Read-only views.
    // ==========================================================================================

    /**
     * The 34 fixed markup fragments of {@code 01 HTML-LINES},
     * {@code app/cbl/CBSTM03A.CBL:L148-L211}, keyed by their COBOL condition name with hyphens
     * rendered as underscores, in source declaration order.
     *
     * <p>Includes {@code HTML_LTDS}, declared at {@code :L161} and never activated anywhere in the
     * procedure division. It is present so the table matches the declaration and absent from every
     * emission path so the output matches the program (severity Low, logged).
     *
     * @return an unmodifiable map of 34 entries, never {@code null}
     */
    public static Map<String, String> htmlFragments() {
        return HTML_FRAGMENTS;
    }

    /**
     * The loaded transaction table, {@code 01 WS-TRNX-TABLE} at
     * {@code app/cbl/CBSTM03A.CBL:L225-L233}, as an unmodifiable view in ascending card-number order.
     *
     * <p>Empty until {@link #initialise()} has run.
     *
     * @return an unmodifiable view of the card groups, never {@code null}
     */
    public List<StatementTransaction.CardGroup> transactionTable() {
        return List.copyOf(transactionTable);
    }

    // ==========================================================================================
    // Record parsing. The Java counterpart of MOVE WS-M03B-FLDT TO <record>.
    // ==========================================================================================

    /**
     * Narrows a 1000-character file-service payload to the projected transaction record and asserts
     * that the projection actually wrote its full 328 bytes.
     *
     * <p>The length check is one of the bounds checks the source lacks. A payload shorter than
     * {@link StatementTransaction#PROJECTION_LAST_WRITTEN_POSITION} cannot be a projected record, and
     * accepting one would silently produce a statement built from spaces.
     *
     * @param payload the raw payload, never {@code null}
     * @return the 350-character record, positions 329-350 being the spaces the projection leaves
     * @throws com.cardemo.exception.FatalProcessingException if the payload is too short to be a
     * projected record.
     */
    private String toProjectedRecord(String payload) {
        if (payload.length() < MINIMUM_PROJECTED_LENGTH) {
            throw abend("A TRNXFILE record must carry at least " + MINIMUM_PROJECTED_LENGTH
                    + " characters, being everything OUTREC FIELDS=(1:263,16,17:1,262,279:279,50) at"
                    + " app/jcl/CREASTMT.JCL:L54 writes, but only " + payload.length()
                    + " were supplied");
        }
        return truncateOrPad(payload, StatementTransaction.RECORD_LENGTH);
    }

    /**
     * {@code TRNX-CARD-NUM}, offsets 1-16 of the projected record, {@code app/cpy/COSTM01.CPY:L22}.
     *
     * @param record a 350-character projected record, never {@code null}
     * @return the 16-character card number
     */
    private static String readCardNumber(String record) {
        return record.substring(0, StatementTransaction.CARD_NUMBER_LENGTH);
    }

    /**
     * Parses a projected record into {@link StatementTransaction}, standing in for the two subscripted
     * moves at {@code app/cbl/CBSTM03A.CBL:L828-L829} that stored {@code TRNX-ID} and the 318-byte
     * {@code TRNX-REST} separately.
     *
     * <p>Field offsets are {@code app/cpy/COSTM01.CPY:L21-L36} in order:
     * {@code TRNX-CARD-NUM X(16)}, {@code TRNX-ID X(16)}, {@code TRNX-TYPE-CD X(02)},
     * {@code TRNX-CAT-CD 9(04)}, {@code TRNX-SOURCE X(10)}, {@code TRNX-DESC X(100)},
     * {@code TRNX-AMT S9(09)V99}, {@code TRNX-MERCHANT-ID 9(09)},
     * {@code TRNX-MERCHANT-NAME X(50)}, {@code TRNX-MERCHANT-CITY X(50)},
     * {@code TRNX-MERCHANT-ZIP X(10)}, {@code TRNX-ORIG-TS X(26)}, {@code TRNX-PROC-TS X(26)},
     * {@code FILLER X(20)}.
     *
     * <p>Values are kept at their declared fixed widths rather than trimmed, so re-emitting them
     * reproduces the original bytes. {@code TRNX-PROC-TS} normally arrives with only 24 significant
     * characters because of the projection truncation; that is the expected case and is not rejected.
     *
     * @param record a 350-character projected record, never {@code null}
     * @return the parsed carrier, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the record is malformed, for instance
     * a money field that is not zoned decimal.
     */
    private StatementTransaction parseProjectedRecord(String record) {
        int cursor = 0;
        String cardNumber = slice(record, cursor, StatementTransaction.CARD_NUMBER_LENGTH);
        cursor += StatementTransaction.CARD_NUMBER_LENGTH;
        String transactionId = slice(record, cursor, StatementTransaction.TRANSACTION_ID_LENGTH);
        cursor += StatementTransaction.TRANSACTION_ID_LENGTH;
        String typeCode = slice(record, cursor, StatementTransaction.TYPE_CODE_LENGTH);
        cursor += StatementTransaction.TYPE_CODE_LENGTH;
        String categoryCode = slice(record, cursor, StatementTransaction.CATEGORY_CODE_LENGTH);
        cursor += StatementTransaction.CATEGORY_CODE_LENGTH;
        String source = slice(record, cursor, StatementTransaction.SOURCE_LENGTH);
        cursor += StatementTransaction.SOURCE_LENGTH;
        String description = slice(record, cursor, StatementTransaction.DESCRIPTION_LENGTH);
        cursor += StatementTransaction.DESCRIPTION_LENGTH;
        String rawAmount = slice(record, cursor, StatementTransaction.AMOUNT_LENGTH);
        cursor += StatementTransaction.AMOUNT_LENGTH;
        String merchantId = slice(record, cursor, StatementTransaction.MERCHANT_ID_LENGTH);
        cursor += StatementTransaction.MERCHANT_ID_LENGTH;
        String merchantName = slice(record, cursor, StatementTransaction.MERCHANT_NAME_LENGTH);
        cursor += StatementTransaction.MERCHANT_NAME_LENGTH;
        String merchantCity = slice(record, cursor, StatementTransaction.MERCHANT_CITY_LENGTH);
        cursor += StatementTransaction.MERCHANT_CITY_LENGTH;
        String merchantZip = slice(record, cursor, StatementTransaction.MERCHANT_ZIP_LENGTH);
        cursor += StatementTransaction.MERCHANT_ZIP_LENGTH;
        String originatingTimestamp = slice(record, cursor, StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH);
        cursor += StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH;
        String processingTimestamp = slice(record, cursor, StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
        cursor += StatementTransaction.PROCESSING_TIMESTAMP_LENGTH;
        String filler = slice(record, cursor, StatementTransaction.FILLER_LENGTH);

        try {
            return new StatementTransaction(cardNumber, transactionId, typeCode, categoryCode, source,
                    description, decodeZonedDecimal(rawAmount, EDITED_AMOUNT_SCALE, "TRNX-AMT"),
                    merchantId, merchantName, merchantCity, merchantZip, originatingTimestamp,
                    processingTimestamp, filler);
        } catch (IllegalArgumentException malformed) {
            throw new FatalProcessingException("A TRNXFILE record could not be interpreted in the"
                    + " app/cpy/COSTM01.CPY layout for card ending "
                    + maskedCardNumber(cardNumber), malformed);
        }
    }

    /**
     * {@code MOVE WS-M03B-FLDT TO CARD-XREF-RECORD}, {@code app/cbl/CBSTM03A.CBL:L364}, in the
     * {@code app/cpy/CVACT03Y.cpy} layout: {@code XREF-CARD-NUM X(16)}, {@code XREF-CUST-ID 9(09)},
     * {@code XREF-ACCT-ID 9(11)}, {@code FILLER X(14)}.
     *
     * @param record a 50-character cross-reference record, never {@code null}
     * @return the parsed entity, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if either identifier is not numeric.
     */
    private CardCrossReference parseCrossReferenceRecord(String record) {
        String cardNumber = slice(record, 0, XREF_CARD_NUMBER_LENGTH);
        String customerId = slice(record, XREF_CARD_NUMBER_LENGTH, CUSTOMER_KEY_LENGTH);
        String accountId = slice(record, XREF_CARD_NUMBER_LENGTH + CUSTOMER_KEY_LENGTH, ACCOUNT_KEY_LENGTH);
        try {
            return new CardCrossReference(cardNumber,
                    parseUnsignedDigits(customerId, "XREF-CUST-ID"),
                    parseUnsignedDigits(accountId, "XREF-ACCT-ID"));
        } catch (IllegalArgumentException malformed) {
            throw new FatalProcessingException("An XREFFILE record could not be interpreted in the"
                    + " app/cpy/CVACT03Y.cpy layout for card ending "
                    + maskedCardNumber(cardNumber), malformed);
        }
    }

    /**
     * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD}, {@code app/cbl/CBSTM03A.CBL:L388}, in the
     * {@code app/cpy/CVCUS01Y.cpy} layout.
     *
     * <p>Only twelve of the eighteen fields are used by the statement, but all eighteen are parsed and
     * carried, because the entity is the record and a partially populated record would be a different
     * thing. Fields are kept at their declared widths, so the customer's name and address arrive
     * space padded exactly as the {@code STRING} statements at {@code :L462} and {@code :L472} expect.
     *
     * @param record a 500-character customer record, never {@code null}
     * @return the parsed entity, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the identifier is not numeric or a
     * field breaches its declared width.
     */
    private Customer parseCustomerRecord(String record) {
        int cursor = 0;
        String customerId = slice(record, cursor, CUSTOMER_KEY_LENGTH);
        cursor += CUSTOMER_KEY_LENGTH;
        String firstName = slice(record, cursor, CUSTOMER_NAME_PART_WIDTH);
        cursor += CUSTOMER_NAME_PART_WIDTH;
        String middleName = slice(record, cursor, CUSTOMER_NAME_PART_WIDTH);
        cursor += CUSTOMER_NAME_PART_WIDTH;
        String lastName = slice(record, cursor, CUSTOMER_NAME_PART_WIDTH);
        cursor += CUSTOMER_NAME_PART_WIDTH;
        String addressLine1 = slice(record, cursor, CUSTOMER_ADDRESS_LINE_WIDTH);
        cursor += CUSTOMER_ADDRESS_LINE_WIDTH;
        String addressLine2 = slice(record, cursor, CUSTOMER_ADDRESS_LINE_WIDTH);
        cursor += CUSTOMER_ADDRESS_LINE_WIDTH;
        String addressLine3 = slice(record, cursor, CUSTOMER_ADDRESS_LINE_WIDTH);
        cursor += CUSTOMER_ADDRESS_LINE_WIDTH;
        String stateCode = slice(record, cursor, CUSTOMER_STATE_CODE_WIDTH);
        cursor += CUSTOMER_STATE_CODE_WIDTH;
        String countryCode = slice(record, cursor, CUSTOMER_COUNTRY_CODE_WIDTH);
        cursor += CUSTOMER_COUNTRY_CODE_WIDTH;
        String zip = slice(record, cursor, CUSTOMER_ZIP_WIDTH);
        cursor += CUSTOMER_ZIP_WIDTH;
        String phoneNumber1 = slice(record, cursor, CUSTOMER_PHONE_WIDTH);
        cursor += CUSTOMER_PHONE_WIDTH;
        String phoneNumber2 = slice(record, cursor, CUSTOMER_PHONE_WIDTH);
        cursor += CUSTOMER_PHONE_WIDTH;
        String ssn = slice(record, cursor, CUSTOMER_SSN_WIDTH);
        cursor += CUSTOMER_SSN_WIDTH;
        String governmentIssuedId = slice(record, cursor, CUSTOMER_GOVT_ID_WIDTH);
        cursor += CUSTOMER_GOVT_ID_WIDTH;
        String dateOfBirth = slice(record, cursor, CUSTOMER_DATE_WIDTH);
        cursor += CUSTOMER_DATE_WIDTH;
        String eftAccountId = slice(record, cursor, CUSTOMER_EFT_ACCOUNT_WIDTH);
        cursor += CUSTOMER_EFT_ACCOUNT_WIDTH;
        String primaryCardHolderIndicator = slice(record, cursor, CUSTOMER_INDICATOR_WIDTH);
        cursor += CUSTOMER_INDICATOR_WIDTH;
        String ficoCreditScore = slice(record, cursor, FICO_SCORE_DIGITS);

        try {
            return new Customer(parseUnsignedDigits(customerId, "CUST-ID"), firstName, middleName,
                    lastName, addressLine1, addressLine2, addressLine3, stateCode, countryCode, zip,
                    phoneNumber1, phoneNumber2, ssn, governmentIssuedId, dateOfBirth, eftAccountId,
                    primaryCardHolderIndicator, ficoCreditScore);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new FatalProcessingException("A CUSTFILE record could not be interpreted in the"
                    + " app/cpy/CVCUS01Y.cpy layout", malformed);
        }
    }

    /**
     * {@code MOVE WS-M03B-FLDT TO ACCOUNT-RECORD}, {@code app/cbl/CBSTM03A.CBL:L412}, in the
     * {@code app/cpy/CVACT01Y.cpy} layout. Every {@code ACCT-*} money field is
     * {@code PIC S9(10)V99} and is decoded from trailing zoned-decimal overpunch.
     *
     * <p>{@code ACCT-EXPIRAION-DATE} keeps the source's spelling. The misspelling is part of the field
     * contract and is not corrected.
     *
     * @param record a 300-character account record, never {@code null}
     * @return the parsed entity, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the identifier or any money field is
     * malformed.
     */
    private Account parseAccountRecord(String record) {
        int cursor = 0;
        String accountId = slice(record, cursor, ACCOUNT_KEY_LENGTH);
        cursor += ACCOUNT_KEY_LENGTH;
        String activeStatus = slice(record, cursor, CUSTOMER_INDICATOR_WIDTH);
        cursor += CUSTOMER_INDICATOR_WIDTH;
        String currentBalance = slice(record, cursor, ACCOUNT_MONEY_FIELD_WIDTH);
        cursor += ACCOUNT_MONEY_FIELD_WIDTH;
        String creditLimit = slice(record, cursor, ACCOUNT_MONEY_FIELD_WIDTH);
        cursor += ACCOUNT_MONEY_FIELD_WIDTH;
        String cashCreditLimit = slice(record, cursor, ACCOUNT_MONEY_FIELD_WIDTH);
        cursor += ACCOUNT_MONEY_FIELD_WIDTH;
        String openDate = slice(record, cursor, CUSTOMER_DATE_WIDTH);
        cursor += CUSTOMER_DATE_WIDTH;
        String expiraionDate = slice(record, cursor, CUSTOMER_DATE_WIDTH);
        cursor += CUSTOMER_DATE_WIDTH;
        String reissueDate = slice(record, cursor, CUSTOMER_DATE_WIDTH);
        cursor += CUSTOMER_DATE_WIDTH;
        String currentCycleCredit = slice(record, cursor, ACCOUNT_MONEY_FIELD_WIDTH);
        cursor += ACCOUNT_MONEY_FIELD_WIDTH;
        String currentCycleDebit = slice(record, cursor, ACCOUNT_MONEY_FIELD_WIDTH);
        cursor += ACCOUNT_MONEY_FIELD_WIDTH;
        String addressZip = slice(record, cursor, CUSTOMER_ZIP_WIDTH);
        cursor += CUSTOMER_ZIP_WIDTH;
        String groupId = slice(record, cursor, ACCOUNT_GROUP_ID_WIDTH);

        try {
            return new Account(parseUnsignedDigits(accountId, "ACCT-ID"), activeStatus,
                    decodeZonedDecimal(currentBalance, EDITED_AMOUNT_SCALE, "ACCT-CURR-BAL"),
                    decodeZonedDecimal(creditLimit, EDITED_AMOUNT_SCALE, "ACCT-CREDIT-LIMIT"),
                    decodeZonedDecimal(cashCreditLimit, EDITED_AMOUNT_SCALE, "ACCT-CASH-CREDIT-LIMIT"),
                    openDate, expiraionDate, reissueDate,
                    decodeZonedDecimal(currentCycleCredit, EDITED_AMOUNT_SCALE, "ACCT-CURR-CYC-CREDIT"),
                    decodeZonedDecimal(currentCycleDebit, EDITED_AMOUNT_SCALE, "ACCT-CURR-CYC-DEBIT"),
                    addressZip, groupId);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new FatalProcessingException("An ACCTFILE record could not be interpreted in the"
                    + " app/cpy/CVACT01Y.cpy layout", malformed);
        }
    }

    // ==========================================================================================
    // Fixed-width primitives. Every one of them is deterministic: Locale.ROOT everywhere, no
    // default locale, no default charset, no ambient time zone (Rule 1 Clause C2). Characters are
    // never converted to bytes in this class at all - the encoding boundary belongs to the writer -
    // so the fixed-width contract is expressed in characters and stays stable under any platform
    // default.
    // ==========================================================================================

    /**
     * Extracts one fixed-width field, tolerating a record that ends early by padding the shortfall
     * with spaces exactly as a COBOL move from a shorter area would.
     *
     * @param record the record to read from, never {@code null}
     * @param offset the zero-based start position
     * @param width the field width
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String slice(String record, int offset, int width) {
        if (offset >= record.length()) {
            return spaces(width);
        }
        int end = Math.min(offset + width, record.length());
        return padRight(record.substring(offset, end), width);
    }

    /**
     * Right pads with spaces to an exact width.
     *
     * @param value the value to pad, never {@code null}
     * @param width the target width
     * @return exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if {@code value} is already longer than {@code width}, which
     * would mean a layout defect rather than data to truncate.
     */
    private static String padRight(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("Value of length " + value.length()
                    + " cannot be padded to the narrower fixed width " + width);
        }
        return value.length() == width ? value : value + spaces(width - value.length());
    }

    /**
     * The Java counterpart of a COBOL {@code MOVE} into a fixed-width alphanumeric field: a shorter
     * value is right padded with spaces, a longer one is truncated on the right, and {@code null}
     * becomes all spaces.
     *
     * @param value the value to fit, possibly {@code null}
     * @param width the declared field width
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String truncateOrPad(String value, int width) {
        if (value == null) {
            return spaces(width);
        }
        return value.length() > width ? value.substring(0, width) : padRight(value, width);
    }

    /**
     * A run of spaces.
     *
     * @param count how many, never negative
     * @return the run, never {@code null}
     */
    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * Appends one HTML line to the accumulator, padded to the {@code HTML-FIXED-LN PIC X(100)} width
     * of {@code app/cbl/CBSTM03A.CBL:L149}. The counterpart of
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}.
     *
     * @param sink the accumulator, appended to
     * @param fragment the markup, never {@code null} and never longer than the record width
     */
    private static void emitHtml(List<String> sink, String fragment) {
        sink.add(padRight(fragment, HTML_LINE_WIDTH));
    }

    /**
     * COBOL {@code STRING ... DELIMITED BY ' '}: copies the fixed-width field up to, but not
     * including, its first space.
     *
     * @param value the field value, possibly {@code null}
     * @param declaredWidth the field's declared COBOL width, applied before the delimiter search so
     * that a short Java string behaves like the space-padded field it stands for
     * @return the copied prefix, possibly empty, never {@code null}
     */
    private static String stringDelimitedBySpace(String value, int declaredWidth) {
        String field = truncateOrPad(value, declaredWidth);
        int delimiter = field.indexOf(SINGLE_SPACE);
        return delimiter < 0 ? field : field.substring(0, delimiter);
    }

    /**
     * COBOL {@code STRING ... DELIMITED BY '  '}: copies the field up to, but not including, its
     * first pair of adjacent spaces.
     *
     * <p>This is the operation that silently drops the surname from the HTML name line whenever the
     * middle name is blank, because the assembled name then carries a double space right after the
     * first name. See {@link #writeHtmlNameAddressBasics}.
     *
     * @param value the already fixed-width field value, never {@code null}
     * @return the copied prefix, possibly empty, never {@code null}
     */
    private static String stringDelimitedByDoubleSpace(String value) {
        int delimiter = value.indexOf(HTML_DOUBLE_SPACE);
        return delimiter < 0 ? value : value.substring(0, delimiter);
    }

    // ==========================================================================================
    // Numeric rendering. Two distinct edited masks and one zoned-decimal codec.
    // ==========================================================================================

    /**
     * {@code PIC 9(9).99-}, the mask of {@code ST-CURR-BAL} at {@code app/cbl/CBSTM03A.CBL:L113}.
     * Nine integer digit positions with <strong>no</strong> zero suppression, a full stop, two decimal
     * digits, then a trailing sign position holding {@code '-'} when negative and a space otherwise.
     *
     * <p>{@code ACCT-CURR-BAL} is {@code PIC S9(10)V99}, one integer digit wider than this mask, so a
     * balance of a billion or more loses its high-order digit. That truncation is what the COBOL
     * {@code MOVE} at {@code :L484} does and it is reproduced rather than rejected (severity Low,
     * preserved legacy behaviour): failing here would refuse statements the legacy system produced.
     *
     * @param value the amount, possibly {@code null}, which renders as zero
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters, never {@code null}
     */
    private static String formatUnsuppressedAmount(BigDecimal value) {
        BigDecimal scaled = scaleForDisplay(value);
        String digits = lowOrderDigits(scaled);
        return digits.substring(0, EDITED_AMOUNT_INTEGER_DIGITS) + '.'
                + digits.substring(EDITED_AMOUNT_INTEGER_DIGITS) + signPosition(scaled);
    }

    /**
     * {@code PIC Z(9).99-}, the mask of {@code ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L137} and of
     * {@code ST-TOTAL-TRAMT} at {@code :L142}. Identical to
     * {@link #formatUnsuppressedAmount(BigDecimal)} except that {@code Z} replaces every leading zero
     * of the integer part with a space.
     *
     * <p>The two decimal positions are {@code 9} rather than {@code Z}, so they are never suppressed
     * and the full stop is always printed: zero renders as nine spaces, {@code ".00"} and a space,
     * not as a blank field.
     *
     * @param value the amount, possibly {@code null}, which renders as zero
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters, never {@code null}
     */
    private static String formatSuppressedAmount(BigDecimal value) {
        BigDecimal scaled = scaleForDisplay(value);
        String digits = lowOrderDigits(scaled);
        String integerPart = digits.substring(0, EDITED_AMOUNT_INTEGER_DIGITS);
        StringBuilder suppressed = new StringBuilder(integerPart);
        for (int index = 0; index < suppressed.length() && suppressed.charAt(index) == '0'; index++) {
            suppressed.setCharAt(index, ' ');
        }
        return suppressed + "." + digits.substring(EDITED_AMOUNT_INTEGER_DIGITS) + signPosition(scaled);
    }

    /**
     * Normalises an amount for display: {@code null} becomes zero and the scale is fixed at
     * {@value #EDITED_AMOUNT_SCALE} with {@link RoundingMode#HALF_EVEN}, which is the rounding packed
     * decimal arithmetic uses and the only rounding mode this tree permits for money.
     *
     * @param value the amount, possibly {@code null}
     * @return the scaled amount, never {@code null}
     */
    private static BigDecimal scaleForDisplay(BigDecimal value) {
        BigDecimal present = value == null ? BigDecimal.ZERO : value;
        return present.setScale(EDITED_AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The low-order digits of an amount's magnitude, as many as both edited masks hold together.
     *
     * @param scaled an amount already at scale {@value #EDITED_AMOUNT_SCALE}, never {@code null}
     * @return exactly {@code EDITED_AMOUNT_INTEGER_DIGITS + EDITED_AMOUNT_SCALE} digit characters
     */
    private static String lowOrderDigits(BigDecimal scaled) {
        int total = EDITED_AMOUNT_INTEGER_DIGITS + EDITED_AMOUNT_SCALE;
        String allDigits = scaled.abs().movePointRight(EDITED_AMOUNT_SCALE).toBigInteger().toString();
        return allDigits.length() > total
                ? allDigits.substring(allDigits.length() - total)
                : leftPadZeros(allDigits, total);
    }

    /**
     * The trailing sign position of both edited masks: {@code '-'} for a negative value, a space for
     * zero or positive.
     *
     * @param scaled the amount already at display scale, never {@code null}
     * @return a one-character string, never {@code null}
     */
    private static String signPosition(BigDecimal scaled) {
        return scaled.signum() < 0 ? "-" : SINGLE_SPACE;
    }

    /**
     * Left pads a digit string with zeros to an exact width.
     *
     * @param digits the digits, never {@code null} and never longer than {@code width}
     * @param width the target width
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String leftPadZeros(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders {@code ACCT-ID PIC 9(11)} as the eleven zero-padded digits a COBOL move would produce.
     *
     * @param accountId the identifier, possibly {@code null}
     * @return exactly {@value #ACCOUNT_ID_DIGITS} digit characters, never {@code null}
     */
    private static String renderAccountId(Long accountId) {
        return renderUnsignedDigits(accountId, ACCOUNT_ID_DIGITS);
    }

    /**
     * Renders {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * <p>The entity carries the score as characters, so a value that is not three digits is fitted
     * rather than reinterpreted: digits are zero padded on the left as the numeric picture requires,
     * and anything else is fitted as an alphanumeric field would be.
     *
     * @param ficoCreditScore the score as held on the record, possibly {@code null}
     * @return exactly {@value #FICO_SCORE_DIGITS} characters, never {@code null}
     */
    private static String renderFicoScore(String ficoCreditScore) {
        if (ficoCreditScore == null) {
            return "0".repeat(FICO_SCORE_DIGITS);
        }
        String trimmed = ficoCreditScore.trim();
        if (!trimmed.isEmpty() && isAllDigits(trimmed)) {
            return trimmed.length() > FICO_SCORE_DIGITS
                    ? trimmed.substring(trimmed.length() - FICO_SCORE_DIGITS)
                    : leftPadZeros(trimmed, FICO_SCORE_DIGITS);
        }
        return truncateOrPad(ficoCreditScore, FICO_SCORE_DIGITS);
    }

    /**
     * Renders the {@code LK-M03B-KEY} value for a keyed read, being the zero-padded digits of a
     * numeric record key.
     *
     * @param value the identifier, never {@code null}
     * @param length the dataset's key length: {@value #CUSTOMER_KEY_LENGTH} for {@code CUSTFILE},
     * {@value #ACCOUNT_KEY_LENGTH} for {@code ACCTFILE}
     * @return exactly {@code length} digit characters, never {@code null}
     */
    private static String renderKey(Long value, int length) {
        return renderUnsignedDigits(value, length);
    }

    /**
     * Renders an unsigned numeric field, zero padded on the left, keeping the low-order digits if the
     * value is wider than the field — which is what a COBOL move into a narrower numeric item does.
     *
     * @param value the number, possibly {@code null}, which renders as all zeros
     * @param width the declared field width
     * @return exactly {@code width} digit characters, never {@code null}
     */
    private static String renderUnsignedDigits(Number value, int width) {
        if (value == null) {
            return "0".repeat(width);
        }
        String digits = String.format(Locale.ROOT, "%d", Math.abs(value.longValue()));
        return digits.length() > width ? digits.substring(digits.length() - width) : leftPadZeros(digits, width);
    }

    /**
     * Parses an unsigned zoned numeric field into a {@link Long}, tolerating the leading spaces a
     * partially populated field can carry.
     *
     * @param field the raw field, never {@code null}
     * @param fieldName the COBOL field name, used verbatim in the failure message
     * @return the parsed value, never {@code null}
     * @throws IllegalArgumentException if the field holds anything other than digits and spaces.
     */
    private static Long parseUnsignedDigits(String field, String fieldName) {
        String candidate = field.trim();
        if (candidate.isEmpty()) {
            return 0L;
        }
        if (!isAllDigits(candidate)) {
            throw new IllegalArgumentException(fieldName + " must hold digits only but held '"
                    + candidate + '\'');
        }
        return Long.valueOf(candidate);
    }

    /**
     * Whether every character is an ASCII digit. Deliberately not
     * {@link Character#isDigit(char)}, which accepts digits from every Unicode script and would let a
     * non-ASCII digit through a fixed-width field that can only hold ASCII.
     *
     * @param value the candidate, never {@code null}
     * @return {@code true} only if every character is {@code '0'} through {@code '9'}
     */
    private static boolean isAllDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes a signed zoned-decimal field with a trailing overpunch sign, the representation the
     * ASCII fixtures use for every {@code PIC S9(n)V99} field.
     *
     * <p>The final character carries both the last digit and the sign:
     * {@code '{'} is {@code +0} and {@code 'A'} through {@code 'I'} are {@code +1} through
     * {@code +9}; {@code '}'} is {@code -0} and {@code 'J'} through {@code 'R'} are {@code -1}
     * through {@code -9}. A plain digit in that position is read as unsigned positive, which is how a
     * field written without an overpunch reads back.
     *
     * <p>Decoding is position-aware and driven by the field's declared scale, never by scanning for
     * sign characters: the same letters occur legitimately inside text fields such as merchant names,
     * so a content-based search would corrupt them.
     *
     * @param field the raw fixed-width field, never {@code null}
     * @param scale the number of implied decimal places from the {@code V} in the picture clause
     * @param fieldName the COBOL field name, used verbatim in the failure message
     * @return the decoded value at {@code scale}, never {@code null}
     * @throws IllegalArgumentException if the field is empty or holds a character that is neither a
     * digit, a space, nor a recognised overpunch.
     */
    private static BigDecimal decodeZonedDecimal(String field, int scale, String fieldName) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be an empty field");
        }
        if (field.isBlank()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        }
        int lastIndex = field.length() - 1;
        char signCarrier = field.charAt(lastIndex);
        boolean negative;
        int lastDigit;
        int positive = POSITIVE_OVERPUNCH.indexOf(signCarrier);
        int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(signCarrier);
        if (positive >= 0) {
            negative = false;
            lastDigit = positive;
        } else if (negativeIndex >= 0) {
            negative = true;
            lastDigit = negativeIndex;
        } else if (signCarrier >= '0' && signCarrier <= '9') {
            negative = false;
            lastDigit = signCarrier - '0';
        } else {
            throw new IllegalArgumentException(fieldName + " ends with '" + signCarrier
                    + "', which is neither a digit nor a zoned-decimal overpunch sign");
        }

        StringBuilder digits = new StringBuilder(field.length());
        for (int index = 0; index < lastIndex; index++) {
            char character = field.charAt(index);
            if (character == ' ') {
                digits.append('0');
            } else if (character >= '0' && character <= '9') {
                digits.append(character);
            } else {
                throw new IllegalArgumentException(fieldName + " holds '" + character
                        + "' at position " + (index + 1) + ", which is not a digit");
            }
        }
        digits.append((char) ('0' + lastDigit));

        BigDecimal magnitude = new BigDecimal(digits.toString())
                .movePointLeft(scale)
                .setScale(scale, RoundingMode.HALF_EVEN);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Encodes a signed value back into zoned decimal with a trailing overpunch sign, so that a record
     * rendered by {@link #projectBaseRecord(Transaction)} decodes to the value it started from.
     *
     * @param value the amount, possibly {@code null}, which encodes as positive zero
     * @param width the declared field width, digits only, the sign riding on the last of them
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String encodeZonedDecimal(BigDecimal value, int width) {
        BigDecimal scaled = scaleForDisplay(value);
        String allDigits = scaled.abs().movePointRight(EDITED_AMOUNT_SCALE).toBigInteger().toString();
        String digits = allDigits.length() > width
                ? allDigits.substring(allDigits.length() - width)
                : leftPadZeros(allDigits, width);
        int lastDigit = digits.charAt(width - 1) - '0';
        char overpunch = scaled.signum() < 0
                ? NEGATIVE_OVERPUNCH.charAt(lastDigit)
                : POSITIVE_OVERPUNCH.charAt(lastDigit);
        return digits.substring(0, width - 1) + overpunch;
    }

    /**
     * The amount of a table entry, treating an absent amount as zero so that a partially populated
     * record cannot make the running total {@code null}.
     *
     * @param transaction the table entry, never {@code null}
     * @return the amount at scale {@value #EDITED_AMOUNT_SCALE}, never {@code null}
     */
    private static BigDecimal amountOf(StatementTransaction transaction) {
        return scaleForDisplay(transaction.amount());
    }

    /**
     * Masks a card number for logging, keeping only its last four characters.
     *
     * <p>Rule 1 Clause D1 keeps secrets and personal data out of logs. A full card number is exactly
     * the kind of value that must never reach one, and diagnostics still need enough to identify the
     * offending record.
     *
     * @param cardNumber the card number, possibly {@code null}
     * @return a masked description, never {@code null}
     */
    private static String maskedCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return "(absent)";
        }
        String trimmed = cardNumber.trim();
        return trimmed.length() <= MASKED_CARD_DIGITS
                ? "(too short to mask)"
                : trimmed.substring(trimmed.length() - MASKED_CARD_DIGITS);
    }

    /**
     * Builds the fragment table from the 34 constants above, in source declaration order.
     *
     * @return an unmodifiable map of 34 entries, never {@code null}
     * @throws IllegalStateException if the table does not hold exactly 34 entries, which would mean a
     * fragment was lost or duplicated relative to {@code app/cbl/CBSTM03A.CBL:L148-L211}.
     */
    private static Map<String, String> buildHtmlFragmentTable() {
        Map<String, String> fragments = new LinkedHashMap<>();
        fragments.put("HTML_L01", HTML_L01);
        fragments.put("HTML_L02", HTML_L02);
        fragments.put("HTML_L03", HTML_L03);
        fragments.put("HTML_L04", HTML_L04);
        fragments.put("HTML_L05", HTML_L05);
        fragments.put("HTML_L06", HTML_L06);
        fragments.put("HTML_L07", HTML_L07);
        fragments.put("HTML_L08", HTML_L08);
        fragments.put("HTML_LTRS", HTML_LTRS);
        fragments.put("HTML_LTRE", HTML_LTRE);
        fragments.put("HTML_LTDS", HTML_LTDS);
        fragments.put("HTML_LTDE", HTML_LTDE);
        fragments.put("HTML_L10", HTML_L10);
        fragments.put("HTML_L15", HTML_L15);
        fragments.put("HTML_L16", HTML_L16);
        fragments.put("HTML_L17", HTML_L17);
        fragments.put("HTML_L18", HTML_L18);
        fragments.put("HTML_L22_35", HTML_L22_35);
        fragments.put("HTML_L30_42", HTML_L30_42);
        fragments.put("HTML_L31", HTML_L31);
        fragments.put("HTML_L43", HTML_L43);
        fragments.put("HTML_L47", HTML_L47);
        fragments.put("HTML_L48", HTML_L48);
        fragments.put("HTML_L50", HTML_L50);
        fragments.put("HTML_L51", HTML_L51);
        fragments.put("HTML_L53", HTML_L53);
        fragments.put("HTML_L54", HTML_L54);
        fragments.put("HTML_L58", HTML_L58);
        fragments.put("HTML_L61", HTML_L61);
        fragments.put("HTML_L64", HTML_L64);
        fragments.put("HTML_L75", HTML_L75);
        fragments.put("HTML_L78", HTML_L78);
        fragments.put("HTML_L79", HTML_L79);
        fragments.put("HTML_L80", HTML_L80);
        if (fragments.size() != HTML_FRAGMENT_COUNT) {
            throw new IllegalStateException("app/cbl/CBSTM03A.CBL:L148-L211 declares "
                    + HTML_FRAGMENT_COUNT + " markup fragments but the table holds " + fragments.size());
        }
        for (Map.Entry<String, String> fragment : fragments.entrySet()) {
            if (fragment.getValue().length() > HTML_LINE_WIDTH) {
                throw new IllegalStateException(fragment.getKey() + " is " + fragment.getValue().length()
                        + " characters and cannot fit HTML-FIXED-LN PIC X(" + HTML_LINE_WIDTH + ')');
            }
        }
        return Collections.unmodifiableMap(fragments);
    }
}
