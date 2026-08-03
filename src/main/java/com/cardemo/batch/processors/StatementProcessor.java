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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *   </ol>
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
 *   </ul>
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
 * The planned {@code DECISION_LOG.md} will record this as <em>self-modifying code eliminated by static
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
 *   <li>Transaction residency defaults to <strong>one card group at a time</strong>, bounded only
 *       by the loud safety limit {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}. The legacy 510-record
 *       ceiling is deliberately not reinstated; see {@link #readNextCardGroup()}.</li>
 *   <li>Input ordering default: ascending by card number, required by
 *       {@link #emitTransactionsForCard}. See <em>Findings</em>.</li>
 *   <li>All case folding and numeric formatting uses {@link Locale#ROOT}, so behaviour does not
 *       vary with the host locale.</li>
 *   </ul>
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
 *   </ul>
 *
 * <h2>Findings carried by this file, classified per Rule 1 Clause F</h2>
 *
 * <ul>
 *   <li><strong>High, remediated</strong> - persisted text reached the markup sink unescaped. Eleven
 *       of this class's HTML lines interpolate stored data into markup: the account banner
 *       ({@code app/cbl/CBSTM03A.CBL:L529}), the customer name line ({@code :L560-L568}), the three
 *       address lines ({@code :L569-L592}), the three basic-detail lines ({@code :L613-L633}) and the
 *       three transaction cells ({@code :L686-L716}). Every field feeding them is {@code PIC X} and
 *       therefore accepts {@code <} as readily as a letter - {@code CUST-FIRST-NAME},
 *       {@code CUST-MIDDLE-NAME}, {@code CUST-LAST-NAME} and {@code CUST-ADDR-LINE-1} through
 *       {@code -3} at {@code app/cpy/CVCUS01Y.cpy:6-11}, and the 100-character
 *       {@code TRNX-DESC} at {@code app/cpy/COSTM01.CPY:29} - so a statement carried whatever markup
 *       an upstream path had stored and the consumer that opened it interpreted that markup
 *       (CWE-79, CWE-116). <strong>Remediated here</strong>: every one of the eleven sites is
 *       composed through {@link #emitHtmlValueLine(java.util.List, String, String, String)}, whose value
 *       is escaped for a text node by {@link #escapeHtml(String)} and is then fitted to the room the line's
 *       own prefix and suffix leave, so no entity is split and no line can exceed
 *       {@link #HTML_LINE_WIDTH}. The same pass neutralises every
 *       control character, because a carriage return or line feed inside a fixed-length record is exactly
 *       the byte that makes a consumer treating the object as line-delimited disagree with one
 *       treating it as {@code RECFM=FB}. This is a <strong>labelled deviation, not parity</strong> -
 *       see {@link #escapeHtml(String)} for the written justification. The 80-character text sink is
 *       deliberately <strong>not</strong> escaped and stays byte-faithful: it is not markup and
 *       interprets nothing. Parity is intact where observable, because data containing none of the five
 *       markup characters is emitted character-identically.</li>
 *   <li><strong>High, mitigated</strong> - the legacy transaction table is 51 cards by 10
 *       transactions ({@code app/cbl/CBSTM03A.CBL:L225-L233}), a hard ceiling of 510 records per
 *       run, and the building loop increments both subscripts with no bounds check whatsoever
 *       before the subscripted moves at {@code :L827-L829}. That is a latent storage overrun.
 *       This class uses unbounded collections, which removes it. This is a
 *       <strong>labelled deviation, not parity</strong>; the written justification required by
 *       Rule 1 Clause A5 is on {@link #readNextCardGroup()}. Remediation for a reviewer
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
 *   <li><strong>Major, CLOSED in this file</strong> - the source interpolates customer name,
 *       address, account identifier, balance, credit score, transaction identifier, description
 *       and amount into markup with <strong>no escaping of any kind</strong>
 *       ({@code app/cbl/CBSTM03A.CBL:L562-L632}, {@code :L687-L715}). The fixed fragments are safe
 *       because they are source literals, but the interpolated values are data (CWE-79).
 *       <p>An earlier revision declined to escape, on the reasoning that escaping would change
 *       byte offsets and break the parity comparison. That reasoning does not hold, and the
 *       correction is worth stating in full because it is the kind of argument that sounds
 *       principled:
 *       <ul>
 *         <li><em>Not escaping is not parity.</em> The source wrote a dataset that nothing
 *             rendered. {@code StatementWriter} uploads the identical stream as
 *             {@code text/html}, so a value containing {@code <script>} is not text but code.
 *             Preserving the absence of encoding therefore does not preserve the source's
 *             behaviour - it newly grants the data the ability to execute, which is a behaviour
 *             change in the one direction parity was invoked to prevent.</li>
 *         <li><em>Byte offsets are not disturbed.</em> {@link #escapeHtml(String)} runs
 *             <strong>after</strong> each value has been moved into its declared COBOL width, so
 *             every source truncation still cuts the raw value at exactly the byte the source cuts
 *             it at, and every line is still exactly 100 characters - the width declared for
 *             {@code HTMLFILE} at {@code app/jcl/CREASTMT.JCL:STEP040} and confirmed by
 *             {@code HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149}. The record
 *             geometry the comparison depends on is unchanged.</li>
 *         <li><em>The closing tag is never the casualty.</em> Escaping expands a value, so a line
 *             that fitted before encoding can exceed the record afterwards - and the right-hand end
 *             of the line is where the closing tag is. {@link #emitHtmlValueLine} therefore spends
 *             the line's remaining width on the value rather than clamping the assembled line, so
 *             the element closes even when the value had to be shortened. This is not an
 *             adversarial-input concern only: an address reading {@code SMITH &amp; SONS} is
 *             ordinary data that expands.</li>
 *         <li><em>The residual difference is bounded and visible.</em> Only a value containing one
 *             of the five syntactic characters renders differently from the baseline, and it
 *             renders as the entity for the character the data actually held. The fixture data
 *             contains none, so the baseline comparison is byte-identical on it.</li>
 *   </ul>
 * Remediation applied: encode at the processor boundary, clamp entity-safely to the declared
 * width, and assert both against hostile values. The deployment-level advice still stands as
 * defence in depth but is no longer the only control.</li>
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
 *   </ul>
 *
 * <h2>The one Rule 1 conflict, and its resolution</h2>
 *
 * <p>Clause B1 forbids dead code. Behavioural parity requires reproducing reachable no-ops. These collide at exactly
 * one site in this file, the redundant {@code MOVE 1 TO CR-JMP} at {@code app/cbl/CBSTM03A.CBL:L324}, which is
 * immediately overwritten by the {@code PERFORM VARYING CR-JMP FROM 1 BY 1} at {@code :L417-L418}. <strong>Parity
 * governs</strong>, because Clause B1 forbids <em>untracked</em> dead code and deferred work without an owner: this
 * no-op is cited, marked and owed an entry in the planned {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md},
 * so it is a documented faithful reproduction rather than abandoned residue. Deleting it would break the paragraph
 * map the scope-coverage gate verifies.
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
     * Property key through which an operator may lower the run-wide retention bound to fit a heap.
     *
     * <p>Declared as a key rather than only as a constant because the bound decides how much work a malformed
     * dataset can extract, and a limit an operator cannot influence is exactly the "hidden constant" Rule 1
     * Clause E objects to. It is also <strong>declared in {@code src/main/resources/application.yml}</strong>
     * at exactly {@link #MAX_TRANSACTIONS_PER_RUN}, so declaring it changes no behaviour: a key that is read
     * at runtime but appears in no profile is invisible to whoever has to operate the job, which is the same
     * defect the four reader page sizes were corrected for. The warning that it is a safety limit and not a
     * routine tuning knob is carried in the comment on that declaration, where an operator will actually read
     * it, rather than by withholding the declaration.
     */
    public static final String KEY_MAX_TRANSACTIONS_PER_RUN =
            "carddemo.batch.statement-processor.max-transactions-per-run";

    /**
     * Default run-wide bound: the number of transactions one statement run may admit in total.
     *
     * <p><strong>Why a run bound is needed even though the table is not resident for the whole run.</strong>
     * The stream advances one control-break group at a time, so what is <em>resident</em> is one card's
     * transactions and is bounded by {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}. The group bound alone,
     * however, admits an endless sequence of <em>distinct</em> card numbers: each group closes within the
     * limit and the run never terminates. So the two bounds answer two different hazards and both are
     * checked - the group bound for heap, this one for work. Exceeding either raises
     * {@link FatalProcessingException}: it fails loudly and never silently truncates, which is precisely what
     * the legacy overrun did.
     *
     * <p><strong>Measured, not estimated.</strong> The whole fixture set for this job is 300 daily
     * transactions ({@code app/data/ASCII/dailytran.txt}), so a parity run admits 300 - four orders of
     * magnitude below this default. A deployment whose legitimate input could approach it lowers
     * {@value #KEY_MAX_TRANSACTIONS_PER_RUN} rather than discovering the limit as a failed job.
     */
    public static final int MAX_TRANSACTIONS_PER_RUN = 1_000_000;

    /**
     * Loud safety limit on the transactions held resident for <strong>one card group</strong>.
     *
     * <p>Nothing else is held. The stream advances one control-break group at a time, so the resident
     * set is one card's transactions and not the run's, and a run-wide ceiling would therefore bound
     * nothing that is actually resident - which is why {@link #MAX_TRANSACTIONS_PER_RUN} bounds the work
     * and this constant bounds the heap. What needs a resident bound is the single group, because a
     * malformed dataset that repeats one card number forever would otherwise grow that group without
     * limit (Rule 1 Clause A2, which requires inputs to be treated as untrusted). Exceeding it raises
     * {@link FatalProcessingException}: it fails loudly and never silently truncates, which is
     * precisely what the legacy overrun did.
     *
     * <p><strong>The arithmetic, so the worst case is a calculation rather than a surprise.</strong> Each
     * retained entry is one projected record: a 16-character card number, a 16-character identifier and the
     * 318-character remainder, so {@value StatementTransaction#RECORD_LENGTH} characters of payload. At two
     * bytes per {@code char} plus per-object and per-list overhead that is on the order of 800 bytes retained
     * per transaction, so this bound caps one group at roughly 800 MB - which a default JVM heap will not
     * accommodate. That is deliberate and is why it is a <em>safety limit</em>: it sits five orders of
     * magnitude above the largest fixture card group, so no legitimate run can reach it.
     */
    public static final int MAX_TRANSACTIONS_PER_CARD_GROUP = 1_000_000;

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
     * of the final digit: {@code '&#123;'} is {@code +0} and {@code 'A'} through {@code 'I'} are {@code +1}
     * through {@code +9}.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Trailing-sign overpunch characters for a negative zoned-decimal field, indexed by the value of
     * the final digit: {@code '&#125;'} is {@code -0} and {@code 'J'} through {@code 'R'} are {@code -1}
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

    /**
     * Initial spare capacity given to the escape buffer, sized so that a handful of encoded characters do not
     * force a reallocation. Five is the number of characters {@link #escapeHtml(String)} recognises.
     */
    private static final int HTML_ESCAPE_HEADROOM = 16;

    /**
     * Length of the longest character reference {@link #escapeHtml(String)} emits, {@code &quot;}. It bounds
     * the backward scan in {@link #clampToHtmlLine(String)} so that truncation can never cut an entity in half
     * and can never walk further than six characters however hostile the input.
     */
    private static final int HTML_LONGEST_ENTITY = 6;

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
    // Markup escaping for the HTML sink. The 80-character text sink is byte-faithful to the legacy
    // layout and is deliberately untouched by any of this: it is not markup, so it interprets
    // nothing. These mirror the helpers in com.cardemo.batch.writers.StatementWriter rather than
    // being shared with them, for the reason that class already documents: the two statement sinks
    // own their emission independently and share no fixed-width codec. The mirroring is deliberate
    // and is why the escaping contract is asserted against BOTH classes by the same test.
    // ==========================================================================================

    /**
     * The replacement for {@code &}, applied unconditionally so an already-escaped value is escaped again
     * rather than passed through: double encoding is a display defect, whereas recognising an entity and
     * leaving it alone is an injection. Value {@value}.
     */
    private static final String HTML_ENTITY_AMP = "&amp;";

    /** The replacement for {@code <}, the character that opens a tag. Value {@value}. */
    private static final String HTML_ENTITY_LT = "&lt;";

    /** The replacement for {@code >}, which closes a tag a consumer may have opened. Value {@value}. */
    private static final String HTML_ENTITY_GT = "&gt;";

    /** The replacement for the double quote, which would otherwise close an attribute. Value {@value}. */
    private static final String HTML_ENTITY_QUOT = "&quot;";

    /**
     * The replacement for the apostrophe, which would otherwise close a single-quoted attribute. The numeric
     * form is used because it is defined in every HTML version this markup could be parsed as. Value
     * {@value}.
     */
    private static final String HTML_ENTITY_APOS = "&#39;";

    /**
     * What a control character becomes in the markup sink: a single space, chosen because the substitution
     * is length-neutral and therefore cannot disturb the record geometry. A carriage return or line feed
     * inside a fixed-length record is exactly the byte that makes a consumer treating the object as
     * line-delimited disagree with one treating it as {@code RECFM=FB}.
     */
    private static final char CONTROL_REPLACEMENT = ' ';

    /** First code point above the C0 control block; every code point below it is a C0 control. */
    private static final char FIRST_PRINTABLE_CHARACTER = 0x20;

    /** The delete character, a control that sits above the C0 block rather than inside it. */
    private static final char DELETE_CHARACTER = 0x7F;

    /** First code point of the C1 control block, which ISO 8859-1 leaves to controls. */
    private static final char FIRST_C1_CONTROL = 0x80;

    /** Last code point of the C1 control block. */
    private static final char LAST_C1_CONTROL = 0x9F;

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
    // by #openAndPrimeTransactionFile() into the #pendingRecord lookahead, from which
    // #readNextCardGroup() takes the group's card number. Both paragraphs survive as their own
    // methods; the shared 16-byte save area narrows to one field with a single writer.
    // ==========================================================================================

    /** The file-access collaborator standing in for {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. */
    private final FileService fileService;

    /**
     * The one card group currently held: the streamed replacement for
     * {@code 01 WS-TRNX-TABLE} at {@code app/cbl/CBSTM03A.CBL:L225-L233} together with its companion
     * counter table {@code 01 WS-TRN-TBL-CNTR} at {@code :L231-L233}. The group's list size is the
     * counterpart of {@code WS-TRCT (CR-CNT)}, so the two source tables collapse into one structure
     * without losing either.
     *
     * <p>{@code null} before the first group is loaded, after the group has been emitted for its
     * cross-reference row, and once the stream is exhausted. See
     * {@link #advanceToCardGroup(String)} for why one group is sufficient.
     */
    private StatementTransaction.CardGroup currentCardGroup;

    /**
     * The one-record lookahead: the first record of the <em>next</em> card group, already read but not
     * yet consumed.
     *
     * <p>A control break is only detectable by reading one record past the end of a group, and that
     * record must not be lost. The source had the same lookahead in shared working storage - the
     * record sat in {@code TRNX-RECORD} while {@code :L819} compared {@code WS-SAVE-CARD} against it -
     * so this field is that buffer, not an addition.
     */
    private String pendingRecord;

    /** Whether {@code TRNXFILE} has reported end of file, so no further read is issued. */
    private boolean transactionFileExhausted;

    /**
     * Card number of the group most recently loaded, which the ascending-order precondition is checked
     * against. Seeded to the empty string, which precedes every card number under character comparison.
     */
    private String lastGroupCardNumber = "";

    /**
     * Running count of card groups loaded, the streamed counterpart of {@code CR-CNT} at
     * {@code app/cbl/CBSTM03A.CBL:L758}. Kept as a scalar because the groups themselves are no longer
     * retained.
     */
    private long cardGroupsRead;

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

    /**
     * Running count of records admitted across the run. The per-group ceiling
     * {@link #MAX_TRANSACTIONS_PER_CARD_GROUP} is checked against the current group's size, not
     * against this counter, which is a diagnostic total reported at {@link #close()}.
     */
    private long transactionsAccepted;

    /**
     * The effective retention bound for this instance, from {@value #KEY_MAX_TRANSACTIONS_PER_RUN}.
     *
     * <p>{@code final}: the bound of a run cannot change during it, and a mutable bound would make the abend
     * message unreproducible.
     */
    private final int maxTransactionsPerRun;

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
     * @param maxTransactionsPerRun the retention bound, from {@value #KEY_MAX_TRANSACTIONS_PER_RUN} and
     *     defaulting to {@value #MAX_TRANSACTIONS_PER_RUN}. Bounds the number of projected records this run may
     *     hold resident, so the heap footprint is an operator decision rather than a hidden constant; see the
     *     arithmetic on {@link #MAX_TRANSACTIONS_PER_RUN}
     * @throws NullPointerException if {@code fileService} is {@code null}.
     * @throws IllegalArgumentException if the retention bound is not positive, because a bound of zero or less
     *     would abend on the first record and a negative bound would never trip at all
     */
    // @Autowired is required, and is the only place in this tree that needs it: two public constructors with
    // no marker leave the container with no way to choose, and it would fail looking for a default one.
    @Autowired
    public StatementProcessor(FileService fileService,
            @Value("${" + KEY_MAX_TRANSACTIONS_PER_RUN + ":" + MAX_TRANSACTIONS_PER_RUN + "}")
            int maxTransactionsPerRun) {
        this.fileService = Objects.requireNonNull(fileService, "fileService must not be null");
        if (maxTransactionsPerRun < 1) {
            throw new IllegalArgumentException(KEY_MAX_TRANSACTIONS_PER_RUN
                    + " must be at least 1 but was " + maxTransactionsPerRun
                    + "; it bounds the resident transaction table, so a non-positive value would either abend "
                    + "on the first record or never trip at all");
        }
        this.maxTransactionsPerRun = maxTransactionsPerRun;
    }

    /**
     * Convenience constructor applying the default retention bound, for the unit tier and for any caller that
     * has no reason to narrow it.
     *
     * @param fileService the DD-keyed file-access service, never {@code null}
     * @throws NullPointerException if {@code fileService} is {@code null}
     */
    public StatementProcessor(FileService fileService) {
        this(fileService, MAX_TRANSACTIONS_PER_RUN);
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
         * Validates, neutralises every control character and defensively copies.
         *
         * <p><b>Why the control sweep is applied here and to both streams.</b> {@link #escapeHtml(String)}
         * neutralises controls on its way past, so the markup sink was already covered - but only the markup
         * sink, and only for values that pass through {@link #escapeHtml(String)}. The
         * {@value StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH}-character text sink is deliberately not
         * escaped, because it is not markup and interprets nothing, so a control byte persisted in a customer
         * name or an address line reached a composed text line untouched. Both statement objects are
         * <b>unblocked and undelimited</b> - {@code app/jcl/CREASTMT.JCL:STEP040} declares {@code LRECL=80}
         * and {@code LRECL=100} - so a consumer finds record boundaries by counting bytes and by nothing
         * else, and {@code StatementWriter} refuses any record carrying a character outside the printable
         * single-byte set rather than emit one. Left unswept, therefore, a single control byte in one
         * persisted field did not corrupt a statement: it abended the whole step, turning stored data into an
         * outage. Sweeping here removes that outcome without weakening the writer's guard, which stays as the
         * boundary check it is.
         *
         * <p>This record constructor is the one boundary every composed line of both streams crosses on its
         * way to the emitter, which is why the sweep belongs here rather than at the sixteen text
         * {@code add} sites. It runs before the width check because it is length-neutral by construction -
         * one control becomes one space - so the geometry it validates is the geometry that is emitted.
         *
         * <p>Parity is untouched: no record in {@code app/data/ASCII} carries a control character, so the
         * transform is the identity for the Gate 1 comparison and for every legitimate record.
         *
         * @throws NullPointerException if any component is {@code null}, or any line is {@code null}
         * @throws IllegalArgumentException if any line is not exactly its stream's declared width.
         */
        public Statement {
            Objects.requireNonNull(accountId, "accountId must not be null");
            Objects.requireNonNull(totalExpenditure, "totalExpenditure must not be null");
            Objects.requireNonNull(textLines, "textLines must not be null");
            Objects.requireNonNull(htmlLines, "htmlLines must not be null");
            textLines = withoutControlCharacters(textLines, "textLines");
            htmlLines = withoutControlCharacters(htmlLines, "htmlLines");
            requireUniformWidth(textLines, TEXT_LINE_WIDTH, "textLines");
            requireUniformWidth(htmlLines, HTML_LINE_WIDTH, "htmlLines");
        }

        /**
         * Copies the lines, replacing every control character with a space as it goes.
         *
         * @param lines the composed lines, never {@code null}
         * @param component the record component name, used verbatim in the failure message
         * @return an unmodifiable copy in which no line carries a control character
         * @throws NullPointerException if any line is {@code null}
         */
        private static List<String> withoutControlCharacters(List<String> lines, String component) {
            List<String> swept = new ArrayList<>(lines.size());
            for (int index = 0; index < lines.size(); index++) {
                String line = Objects.requireNonNull(lines.get(index),
                        () -> component + " must not carry a null line");
                swept.add(neutraliseControlCharacters(line));
            }
            return List.copyOf(swept);
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
     * <p>Hence five calls in that exact order followed by the mainline. Owed an entry in the planned
     * {@code DECISION_LOG.md} as: self-modifying code eliminated by static flow analysis with observable order
     * preserved. The DD-keyed strategy map lives in {@link FileService}, where {@code app/cbl/CBSTM03B.CBL}'s
     * four-dataset by six-operation matrix genuinely varies; putting one here would model a variability that does not
     * exist.
     *
     * <p>{@code OPEN OUTPUT STMT-FILE HTML-FILE} at {@code :L293} is deliberately absent: the two
     * output streams belong to {@code com.cardemo.batch.writers.StatementWriter}, which opens them
     * from its {@code StepExecutionListener} callback. This class produces lines; it never writes.
     *
     * @throws com.cardemo.exception.FatalProcessingException if any of the four datasets cannot be
     * opened or primed. Breaches of {@link #MAX_TRANSACTIONS_PER_CARD_GROUP} or of the ascending
     * card-number precondition are raised later, as each group is read, because no group is read
     * here.
     */
    public void initialise() {
        if (initialised) {
            return;
        }
        initialiseTransactionStream();
        pendingRecord = openAndPrimeTransactionFile();
        openCrossReferenceFile();
        openCustomerFile();
        openAccountFile();
        initialised = true;
        LOG.info("Statement initialisation complete: the transaction stream is primed and the four "
                + "datasets are open; card groups are consumed one control break at a time");
    }

    /**
     * {@code INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR}, {@code app/cbl/CBSTM03A.CBL:L294}.
     *
     * <p>The source zeroed a fixed 51-by-10 table and its 51 counters in place. Here the streamed
     * equivalents are cleared instead - the held group, the lookahead record, the exhaustion flag, the
     * order-checking position and the two subscript counters that {@code CR-CNT} and {@code TR-CNT}
     * seeded at {@code :L758-L759} - which is the same observable start state.
     */
    private void initialiseTransactionStream() {
        currentCardGroup = null;
        pendingRecord = null;
        transactionFileExhausted = false;
        lastGroupCardNumber = "";
        cardGroupsRead = 0L;
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
     * instead, so {@link #readNextCardGroup()} takes the group's card number from the lookahead
     * record rather than from shared mutable state.
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
    // The transaction stream. app/cbl/CBSTM03A.CBL:L818-L853. Step 2 of the pipeline.
    //
    // The source loaded every record into a fixed table before producing any statement. This class
    // consumes the same records one control-break group at a time, because the two streams the
    // statement run joins are both already ordered by card number - TRNXFILE by the sort of
    // app/jcl/CREASTMT.JCL:L53, XREFFILE by its XREF-CARD-NUM record key - so a forward merge join
    // sees exactly what the table lookup saw while holding one group instead of the whole run.
    // ==========================================================================================

    /**
     * {@code 8500-READTRNX-READ} ({@code app/cbl/CBSTM03A.CBL:L818-L847}) together with its exit
     * paragraph {@code 8599-EXIT} ({@code :L849-L853}), positioned onto the card group a
     * cross-reference row is asking for.
     *
     * <h4>DEVIATION — the 510-transaction ceiling is removed. Severity of the defect it removes:
     * High. Classification: labelled deviation, not parity.</h4>
     *
     * <p>The source table is declared {@code 05 WS-CARD-TBL OCCURS 51 TIMES} containing
     * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at {@code app/cbl/CBSTM03A.CBL:L225-L233}, so its hard
     * capacity is 51 cards multiplied by 10 transactions, or
     * {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions per run. The building
     * loop increments <em>both</em> subscripts with no bounds check whatsoever and then writes through
     * them at {@code :L827-L829} ({@code MOVE TRNX-CARD-NUM TO WS-CARD-NUM (CR-CNT)},
     * {@code MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT)},
     * {@code MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT)}). A 52nd card or an 11th transaction on
     * any card therefore writes past the end of the table into whatever follows it in WORKING-STORAGE:
     * a latent storage overrun that corrupts data silently and produces a plausible but wrong
     * statement.
     *
     * <p><strong>This implementation streams, so there is no table to overrun and no ceiling at
     * all.</strong>
     *
     * <p><strong>Justification, as Rule 1 Clause A5 requires for a tradeoff.</strong> The change
     * removes a memory-corruption defect and, as a consequence, an unbounded heap cost. Three
     * alternatives were considered and rejected. Reinstating the ceiling and truncating at 510 would
     * preserve the number but not the behaviour, because the source does not truncate - it overruns; a
     * faithful reproduction of the overrun is impossible in Java and undesirable in any language.
     * Reinstating the ceiling and failing at 510 would turn a run the legacy system completed
     * (incorrectly) into a run this system refuses, which is a behaviour change affecting every
     * dataset larger than the fixtures. Materialising the whole run without a ceiling - the shape this
     * class previously had - traded a silent corruption for a silent heap exhaustion and, worse,
     * needed a synthetic million-record cap to bound something that never had to be resident. The
     * design adopted holds one card group, so the resident set is proportional to the largest card
     * group rather than to the run, and that single group is bounded loudly at
     * {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}. A WARN is emitted the first time a run crosses either
     * legacy threshold, so the divergence from historical capacity is visible in the log rather than
     * inferred.
     *
     * <p>The legacy capacity limit of {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}
     * is <strong>owed an entry in the planned {@code TRACEABILITY_MATRIX.md}</strong> as this program's
     * historical capacity, and this deviation is <strong>owed an entry in the planned
     * {@code DECISION_LOG.md}</strong>. Measured at this commit neither file exists, so this Javadoc is the
     * register of record for both and neither may be described as already recorded.
     *
     * <h4>Self-recursion converted to iteration. Severity: Low.</h4>
     *
     * <p>{@code :L840} is {@code GO TO 8500-READTRNX-READ} - the paragraph branches to itself once per
     * record. A Java method that called itself once per transaction would overflow the stack on any
     * realistic volume, which is the class of obvious inefficiency Clause A5 forbids, so the recursion
     * becomes the {@code while} loops below. The observable order is unchanged and the final-counter
     * flush of {@code :L850} is preserved: the last group is closed by end of file rather than by a
     * control break, exactly as {@code 8599-EXIT} closed it.
     *
     * <h4>Ascending card-number precondition. Severity: Low. Option (1) — preserved.</h4>
     *
     * <p>{@link #emitTransactionsForCard(String, java.util.List, java.util.List)} keeps the
     * order-dependent early exit of
     * {@code :L417-L419} verbatim, which is only correct while the table ascends by card number.
     * {@code app/jcl/CREASTMT.JCL:L53} guarantees it with
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}. Rather than relax the lookup and diverge, the
     * precondition is asserted as each group is closed, so an unsorted input fails at the boundary
     * with a precise message instead of silently producing short statements. Asserting per group
     * rather than over the whole file up front is the one observable difference streaming makes: a run
     * that ends before reaching a misordered record no longer reports it, which is a diagnostic
     * difference and not a behavioural one, because the source's lookup would equally never have
     * reached it.
     *
     * @param soughtCardNumber the card number of the cross-reference row being served, already fitted
     * to {@value StatementTransaction#CARD_NUMBER_LENGTH} characters. Never {@code null}
     * @return the group for that card number, or empty when the stream holds no such group - either
     * because it is exhausted or because the next group belongs to a later card
     * @throws com.cardemo.exception.FatalProcessingException on a read failure, on a record shorter
     * than the projection writes, on input that is not ascending by card number, or on a single card
     * group larger than {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}
     */
    private Optional<StatementTransaction.CardGroup> advanceToCardGroup(String soughtCardNumber) {
        Objects.requireNonNull(soughtCardNumber, "soughtCardNumber must not be null");

        while (true) {
            if (currentCardGroup == null) {
                currentCardGroup = readNextCardGroup();
                if (currentCardGroup == null) {
                    // 8599-EXIT reached: the stream is exhausted, so no group can match.
                    return Optional.empty();
                }
            }

            String heldCardNumber = truncateOrPad(currentCardGroup.cardNumber(),
                    XREF_CARD_NUMBER_LENGTH);
            int ordering = heldCardNumber.compareTo(soughtCardNumber);
            if (ordering == 0) {
                // :L420 IF XREF-CARD-NUM = WS-CARD-NUM (CR-JMP). The group is consumed here, so the
                // next cross-reference row starts from the group after it.
                StatementTransaction.CardGroup matched = currentCardGroup;
                currentCardGroup = null;
                return Optional.of(matched);
            }
            if (ordering > 0) {
                // :L418 the early exit: WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM. The group is retained
                // for a later cross-reference row rather than discarded.
                return Optional.empty();
            }
            // :L421 the implicit continue: this card precedes the one sought, so it has no
            // cross-reference row of its own and is skipped, exactly as the source's loop skipped it.
            currentCardGroup = null;
        }
    }

    /**
     * Reads one complete card group, stopping at the control break or at end of file.
     *
     * <p>This is the body of {@code 8500-READTRNX-READ} for a single group. The lookahead record left
     * by the previous call seeds it, the group grows while the card number is unchanged
     * ({@code :L819-L821}), and the first record of the next card is left in the lookahead
     * ({@code :L830} moving the new card into {@code WS-SAVE-CARD}) rather than discarded.
     *
     * @return the completed group, or {@code null} when the stream is exhausted, which is
     * {@code 8599-EXIT}
     * @throws com.cardemo.exception.FatalProcessingException on a read failure, on a record shorter
     * than the projection writes, on input that is not ascending by card number, or on a group larger
     * than {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}
     */
    private StatementTransaction.CardGroup readNextCardGroup() {
        if (pendingRecord == null) {
            return null;
        }

        // WS-SAVE-CARD, seeded by MOVE TRNX-CARD-NUM TO WS-SAVE-CARD at :L757.
        String groupCardNumber = readCardNumber(pendingRecord);
        List<StatementTransaction> transactions = new ArrayList<>();

        while (pendingRecord != null) {
            String cardNumber = readCardNumber(pendingRecord);

            // :L819 IF WS-SAVE-CARD = TRNX-CARD-NUM. A different card is the control break, and the
            // record that broke it stays in the lookahead for the next group.
            if (!groupCardNumber.equals(cardNumber)) {
                break;
            }

            // :L828-L829 MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT) and
            // MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT). The key and the 318-byte remainder are
            // the whole record, so one parsed carrier holds both.
            transactions.add(parseProjectedRecord(pendingRecord));
            admitTransaction(transactions.size());

            // :L832-L846. Next read, then EVALUATE WS-M03B-RC accepting '00' and '10' only - no '04'
            // leniency at this site, unlike the priming read at :L748.
            Optional<String> nextRecord = readNextTransactionRecord();
            // :L839 MOVE WS-M03B-FLDT TO TRNX-RECORD, then :L840 GO TO 8500-READTRNX-READ.
            pendingRecord = nextRecord.isPresent() ? toProjectedRecord(nextRecord.get()) : null;
        }

        // :L822-L823 the flush and ADD 1 TO CR-CNT, or :L850 MOVE TR-CNT TO WS-TRCT (CR-CNT) when end
        // of file closed the group instead of a control break. Both are this one append.
        requireAscendingCardNumber(lastGroupCardNumber, groupCardNumber);
        lastGroupCardNumber = groupCardNumber;
        cardGroupsRead++;
        if (cardGroupsRead == StatementTransaction.LEGACY_MAX_CARDS_PER_RUN + 1L) {
            LOG.warn("Card count has passed the legacy capacity of {} cards"
                            + " (WS-CARD-TBL OCCURS 51, app/cbl/CBSTM03A.CBL:L226);"
                            + " this run exceeds what the COBOL table could hold without overrun",
                    StatementTransaction.LEGACY_MAX_CARDS_PER_RUN);
        }
        return new StatementTransaction.CardGroup(groupCardNumber, transactions);
    }

    /**
     * The read half of {@code 8500-READTRNX-READ}, {@code app/cbl/CBSTM03A.CBL:L832-L846}.
     *
     * @return the next payload, or empty at end of file
     * @throws com.cardemo.exception.FatalProcessingException for any status other than {@code '00'}
     * or {@code '10'}, {@code '04'} included - the guard of {@code :L837}.
     */
    private Optional<String> readNextTransactionRecord() {
        if (transactionFileExhausted) {
            return Optional.empty();
        }
        Optional<String> payload;
        try {
            payload = fileService.readNext(FileService.Dd.TRNXFILE);
        } catch (CardDemoException failure) {
            LOG.error("{}", ERROR_READING + FileService.Dd.TRNXFILE.ddName(), failure);
            throw failure;
        }
        if (payload.isEmpty()) {
            // :L841-L842 WHEN '10' GO TO 8599-EXIT.
            transactionFileExhausted = true;
        }
        return payload;
    }

    /**
     * Counts one admitted record against the loud limit and warns on the first crossing of the legacy
     * per-card capacity.
     *
     * <p>This is one of the bounds checks the source does not have. It exists because the group is an
     * unbounded collection, and it fails rather than truncates.
     *
     * <p>Two bounds are checked, and they answer two different hazards. The <em>group</em> bound
     * {@link #MAX_TRANSACTIONS_PER_CARD_GROUP} bounds what is actually resident, because the stream advances
     * one control-break group at a time. The <em>run</em> bound {@link #MAX_TRANSACTIONS_PER_RUN}, lowerable
     * through {@value #KEY_MAX_TRANSACTIONS_PER_RUN}, bounds the whole run, so the total work a malformed
     * dataset can extract is an operator decision rather than a hidden constant - the group bound alone would
     * admit an endless sequence of distinct card numbers.
     *
     * @param transactionsOnCurrentCard the size of the group the record was just added to
     * @throws com.cardemo.exception.FatalProcessingException if the group exceeds
     * {@link #MAX_TRANSACTIONS_PER_CARD_GROUP}, or the run exceeds the effective
     * {@value #KEY_MAX_TRANSACTIONS_PER_RUN}.
     */
    private void admitTransaction(int transactionsOnCurrentCard) {
        transactionsAccepted++;
        if (transactionsOnCurrentCard > MAX_TRANSACTIONS_PER_CARD_GROUP) {
            throw abend("One card group exceeded the safety limit of " + MAX_TRANSACTIONS_PER_CARD_GROUP
                    + " transactions. The legacy table held at most "
                    + StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD
                    + " per card (app/cbl/CBSTM03A.CBL:L228), so a group this large indicates a"
                    + " malformed or repeating TRNXFILE rather than a legitimate workload");
        }
        if (transactionsAccepted > this.maxTransactionsPerRun) {
            throw abend("The statement run exceeded the safety limit of " + this.maxTransactionsPerRun
                    + " transactions (" + KEY_MAX_TRANSACTIONS_PER_RUN
                    + "). The legacy table held at most "
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
        // It is retained rather than deleted because deleting it would break the paragraph-level correspondence that
        // the planned TRACEABILITY_MATRIX.md will assert and Gate 7 verifies. Rule 1 Clause B1 forbids UNTRACKED dead
        // code; this statement is cited to its source line, marked here, and owed an entry in the planned
        // DECISION_LOG.md as one of the tree's retained-for-parity artefacts, so it is tracked rather than abandoned.
        // This is the one documented Rule 1 conflict in this file and parity governs it. It is deliberately not a
        // deferred-work marker: nothing is deferred.
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
        emitHtmlValueLine(htmlLines, HTML_L11_PREFIX,
                escapeHtml(truncateOrPad(renderAccountId(account.getAccountId()), L11_ACCT_WIDTH)),
                HTML_L11_SUFFIX);

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
     * <p><strong>Every dynamic value is HTML-escaped. Severity of the defect it closes: High
     * (CWE-79).</strong> The source interpolates customer name and address straight into markup with
     * no escaping anywhere in {@code :L558-L672}, so a customer record whose name or address line
     * carried {@code <script>} would have produced an executable document from persisted data - stored
     * cross-site scripting, injected wherever the statement is later rendered. Each value is therefore
     * passed through {@link #escapeHtml(String)} before it reaches the markup.
     *
     * <p><strong>Parity is preserved on every input that is not an injection.</strong> Escaping is the
     * identity transform for any value containing none of {@code & < > " '}, and no fixture in
     * {@code app/data/ASCII} contains one, so the emitted bytes are unchanged for the Gate 1
     * comparison and for every realistic record. The only inputs whose bytes change are exactly the
     * inputs that would otherwise be an attack, which is the one case where byte parity would be a
     * defect rather than a contract. The 34 markup fragments are fixed literals from the source, carry
     * no interpolation at all, and are never escaped.
     *
     * <p><strong>Geometry is preserved absolutely.</strong> An escaped value can be longer than the raw
     * one, so {@link #emitHtmlValueLine(java.util.List, String, String, String)} fits the escaped form to
     * the space the line has, cutting it only at an entity boundary by way of
     * {@link #entitySafeCut(String, int)}, which is why no entity is ever split and why every emitted line is
     * still exactly {@value StatementTransaction#STATEMENT_HTML_RECORD_LENGTH} characters. That fitting is
     * what makes escaping compatible with parity at all: the objection that escaping must change the byte
     * offsets of every affected line, and so break the Gate 1 comparison, is answered by fitting the escaped
     * form to the room the line already leaves rather than by letting the line grow.
     *
     * <p><strong>The delivery boundary is hardened as well, and deliberately in addition rather than
     * instead.</strong> Escaping removes the markup; it does not decide how the object is served.
     * {@code com.cardemo.batch.writers.StatementWriter} stores both objects with
     * {@code Content-Disposition: attachment} and {@code Cache-Control: no-store}, so the markup object is
     * never rendered in the bucket's origin and no intermediary retains it, and no controller in the tree
     * produces {@code text/html}. Either control alone would leave a gap: the disposition header does not
     * help a consumer that reads the object and renders it itself, and the escape does not help against an
     * object served as active content from a shared origin.
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
        emitHtmlValueLine(htmlLines, HTML_L23_PREFIX,
                escapeHtml(stringDelimitedByDoubleSpace(truncateOrPad(assembledName, L23_NAME_WIDTH))),
                HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);

        // :L569-L592, three address lines through HTML-ADDR-LN.
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN,
                escapeHtml(stringDelimitedByDoubleSpace(addressLine1)),
                HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN,
                escapeHtml(stringDelimitedByDoubleSpace(addressLine2)),
                HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN,
                escapeHtml(stringDelimitedByDoubleSpace(addressLine3)),
                HTML_DOUBLE_SPACE + HTML_PARAGRAPH_CLOSE);

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
        emitHtmlValueLine(htmlLines, HTML_LABEL_ACCOUNT_ID, escapeHtml(renderedAccountId), HTML_PARAGRAPH_CLOSE);
        emitHtmlValueLine(htmlLines, HTML_LABEL_CURRENT_BALANCE, escapeHtml(renderedBalance), HTML_PARAGRAPH_CLOSE);
        emitHtmlValueLine(htmlLines, HTML_LABEL_FICO_SCORE, escapeHtml(renderedFicoScore), HTML_PARAGRAPH_CLOSE);

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
     * {@link #readNextCardGroup()} asserts it as each group closes. The alternative — making the
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
        // The scan is the same scan; it walks a forward cursor rather than an in-memory table, so
        // crJmp is the one-based group ordinal it reached rather than an index into a list.
        Optional<StatementTransaction.CardGroup> matched = advanceToCardGroup(soughtCardNumber);
        crJmp = (int) Math.min(cardGroupsRead, Integer.MAX_VALUE);
        if (matched.isPresent()) {
            // :L420 IF XREF-CARD-NUM = WS-CARD-NUM (CR-JMP).
            List<StatementTransaction> transactions = matched.get().transactions();
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
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN, escapeHtml(renderedId), HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L61);
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN, escapeHtml(renderedDescription), HTML_PARAGRAPH_CLOSE);
        emitHtml(htmlLines, HTML_LTDE);
        emitHtml(htmlLines, HTML_L64);
        emitHtmlValueLine(htmlLines, HTML_PARAGRAPH_OPEN, escapeHtml(renderedAmount), HTML_PARAGRAPH_CLOSE);
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
                statementsProduced, cardGroupsRead, transactionsAccepted);
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
     * <p>This ordering is the precondition that {@link #readNextCardGroup()} asserts and
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
     * well-formed timestamp at all. Owed an entry in the planned {@code DECISION_LOG.md}.
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
     * The single card group currently held, the streamed replacement for {@code 01 WS-TRNX-TABLE} at
     * {@code app/cbl/CBSTM03A.CBL:L225-L233}.
     *
     * <p>Empty before the first group is loaded, immediately after a group has been emitted for its
     * cross-reference row, and once the stream is exhausted. Exposed so that a test can observe the
     * bounded resident set directly - the assertion that matters is that <em>one</em> group is held,
     * never the run - and so that a diagnostic can name the group a run is positioned on.
     *
     * @return the held group, or empty when none is held, never {@code null}
     */
    public Optional<StatementTransaction.CardGroup> currentCardGroup() {
        return Optional.ofNullable(currentCardGroup);
    }

    /**
     * The number of card groups the stream has closed so far, the counterpart of {@code CR-CNT} at
     * {@code app/cbl/CBSTM03A.CBL:L758}.
     *
     * @return the running group count, never negative
     */
    public long cardGroupsRead() {
        return cardGroupsRead;
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
        sink.add(clampToHtmlLine(fragment));
    }

    /**
     * Emits one markup line assembled from a leading literal, an escaped value and a trailing literal,
     * spending the line's remaining width on the value so that the trailing literal always survives.
     *
     * <p><strong>Why the value is budgeted rather than the line clamped.</strong>
     * {@link #escapeHtml(String)} expands its input: one {@code '&'} becomes five characters and one
     * {@code '\''} becomes five. A line that fitted {@value #HTML_LINE_WIDTH} characters before escaping can
     * therefore exceed it afterwards, and clamping the assembled line would cut from the right - which is
     * where the closing tag is. That would drop {@code </p>} from a paragraph whose address merely contained
     * an ampersand, so it is not an adversarial-input concern only: {@code SMITH &amp; SONS} is ordinary data.
     * Truncating the <em>value</em> instead keeps the element closed in every case, and the characters lost
     * are the trailing characters of a value that the record could not hold once encoded.
     *
     * <p>This runs after the COBOL width has already been applied by
     * {@link #truncateOrPad(String, int)}, so it never shortens a value that fits: for every value whose
     * encoded form is no longer than the space between the two literals - which is every value that contains
     * none of the five characters {@code escapeHtml} recognises - the line is byte-identical to what the
     * source produced.
     *
     * @param sink the line accumulator, never {@code null}
     * @param prefix the literal that opens the line, never {@code null}
     * @param escapedValue the already-escaped value, never {@code null}
     * @param suffix the literal that closes the line, never {@code null}
     */
    private static void emitHtmlValueLine(List<String> sink, String prefix, String escapedValue,
            String suffix) {
        int budget = HTML_LINE_WIDTH - prefix.length() - suffix.length();
        if (budget < 0) {
            // The two literals alone exceed the record. No fragment in this class does, so this is a guard
            // rather than a path; the whole-line clamp is the only defined answer if one ever did.
            sink.add(clampToHtmlLine(prefix + escapedValue + suffix));
            return;
        }
        String value = escapedValue.length() > budget
                ? escapedValue.substring(0, entitySafeCut(escapedValue, budget))
                : escapedValue;
        sink.add(padRight(prefix + value + suffix, HTML_LINE_WIDTH));
    }

    /**
     * Escapes a value for interpolation into the markup stream, so that record content cannot become markup.
     *
     * <p><strong>Why this exists.</strong> The source builds each markup line by moving record content into
     * {@code HTML-FIXED-LN PIC X(100)} and writing the field, with no encoding of any kind - the customer
     * name, the three address lines, the transaction description and the rendered amounts all reach the file
     * verbatim ({@code app/cbl/CBSTM03A.CBL:L529-L723}). On a 3270 that was harmless: the output was a
     * dataset, and nothing rendered it as a document. The Java target uploads the same stream as
     * {@code text/html}, where a value containing {@code <script>} is not text but code (CWE-79). Preserving
     * the absence of encoding would therefore not preserve the source's behaviour, it would newly grant the
     * data the ability to execute.
     *
     * <p><strong>Why it is applied here and not earlier.</strong> Escaping happens <em>after</em> each value
     * has been moved into its declared COBOL width by {@link #truncateOrPad(String, int)}, so every source
     * truncation - the 50-character name field, the 49-character description - still cuts the raw value at
     * exactly the byte the source cuts it at. Escaping first would let an expanded entity consume field width
     * that the source spends on data, which would change which characters survive.
     *
     * <p>The five characters encoded are the five that XML and HTML give syntactic meaning to. Single and
     * double quotes are included even though every interpolation below lands in element text rather than in an
     * attribute value, because a value is one edit away from being placed in an attribute and an encoder that
     * is only correct for one context is a trap.
     *
     * <p><strong>Substitution is unconditional</strong>, never conditional on whether a value already looks
     * escaped. Double encoding is a display defect in data that should not have contained markup, whereas
     * recognising an entity and passing it through is an injection. A single pass over the characters is what
     * makes that unconditional: there is no second pass for an already-substituted entity to be seen by, which
     * is the failure mode of applying five ordered string replacements one after another.
     *
     * <p><strong>Every control character is neutralised on the same pass</strong>, by
     * {@link #neutraliseControlCharacters(String)}. A carriage return or line feed inside a fixed-length
     * record is exactly the byte that makes a consumer treating the object as line-delimited disagree with one
     * treating it as {@code RECFM=FB} (CWE-116), and it cannot be caught downstream because by then the
     * record boundaries are already wrong. The sweep is length-neutral by construction - one control becomes
     * one space - so it cannot disturb the record geometry and the budget arithmetic in
     * {@link #emitHtmlValueLine(java.util.List, String, String, String)} is unaffected by it.
     *
     * <p>Pure function; no side effect; consults no locale, charset or clock. A {@code null} argument yields
     * the empty string rather than the four characters {@code null}.
     *
     * @param value the field content, already at its declared COBOL width; may be {@code null}
     * @return the encoded text, never {@code null}, and never shorter than the input
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(value.length() + HTML_ESCAPE_HEADROOM);
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            switch (character) {
                case '&' -> encoded.append(HTML_ENTITY_AMP);
                case '<' -> encoded.append(HTML_ENTITY_LT);
                case '>' -> encoded.append(HTML_ENTITY_GT);
                case '"' -> encoded.append(HTML_ENTITY_QUOT);
                case '\'' -> encoded.append(HTML_ENTITY_APOS);
                default -> encoded.append(character);
            }
        }
        return neutraliseControlCharacters(encoded.toString());
    }

    /**
     * Brings a markup line to exactly {@link #HTML_LINE_WIDTH} characters, padding short and truncating long
     * without ever cutting an escape entity in half.
     *
     * <p><strong>This is the COBOL field, not a new policy.</strong> Every markup line in the source is a
     * {@code MOVE} into {@code HTML-FIXED-LN PIC X(100)} followed by
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}, and a COBOL alphanumeric move left-justifies, pads
     * short and truncates long. The 100-character width is independently confirmed by the record length
     * declared for {@code HTMLFILE} at {@code app/jcl/CREASTMT.JCL:STEP040}, so it is a parity contract rather
     * than a formatting choice: emitting anything other than exactly 100 characters per line breaks the
     * side-by-side comparison against the baseline.
     *
     * <p><strong>Why truncation is now reached rather than rejected.</strong> An earlier revision threw when a
     * line exceeded the width, on the reasoning that a fixed fragment longer than its field is a defect. That
     * remains true of the fixed fragments - and a test asserts each of the 34 fits - but it is no longer true
     * of the interpolated lines: {@link #escapeHtml(String)} legitimately expands a value, so an address line
     * dense in ampersands can push a line past 100 characters. Throwing there would turn hostile input into a
     * failed batch run, which is a denial of service rather than a defence; truncating is what the source
     * field does.
     *
     * <p><strong>Why the entity guard.</strong> Cutting at a fixed offset could leave a partial entity such as
     * {@code &am} at the end of a line, which a browser may render as literal text or, worse, recombine with
     * the next line's opening characters. The scan therefore walks back over a trailing incomplete
     * {@code &...} run and pads the gap with spaces, so a line always ends on a complete character reference
     * and always occupies exactly the declared width.
     *
     * @param line the assembled markup line, never {@code null}
     * @return exactly {@link #HTML_LINE_WIDTH} characters
     */
    private static String clampToHtmlLine(String line) {
        if (line.length() == HTML_LINE_WIDTH) {
            return line;
        }
        if (line.length() < HTML_LINE_WIDTH) {
            return padRight(line, HTML_LINE_WIDTH);
        }

        return padRight(line.substring(0, entitySafeCut(line, HTML_LINE_WIDTH)), HTML_LINE_WIDTH);
    }

    /**
     * Moves a proposed cut backwards, if it lands inside a character reference, to the reference's start.
     *
     * <p>Cutting {@code &amp;} into {@code &am} would leave a line ending in a sequence a browser reads as
     * the beginning of an entity and then repairs against whatever follows it in the document. Walking back
     * to the {@code '&'} drops the reference whole instead.
     *
     * <p>The scan is bounded by {@value #HTML_LONGEST_ENTITY}, the longest reference
     * {@link #escapeHtml(String)} emits, so its cost does not depend on the input and adversarial content
     * cannot lengthen it. A {@code ';'} encountered first means the cut is past a complete reference and the
     * proposed cut stands.
     *
     * @param line the assembled or escaped text, never {@code null}
     * @param proposedCut the index the caller would cut at; must not exceed {@code line.length()}
     * @return the cut index to use, never greater than {@code proposedCut}
     */
    private static int entitySafeCut(String line, int proposedCut) {
        int floor = Math.max(0, proposedCut - HTML_LONGEST_ENTITY);
        for (int scan = proposedCut - 1; scan >= floor; scan--) {
            char character = line.charAt(scan);
            if (character == ';') {
                return proposedCut;
            }
            if (character == '&') {
                return scan;
            }
        }
        return proposedCut;
    }

    /**
     * Replaces every control character with a single space, leaving the length unchanged.
     *
     * @param value the escaped value, never {@code null}
     * @return the value with no C0 control, no delete character and no C1 control
     */
    private static String neutraliseControlCharacters(String value) {
        StringBuilder safe = null;
        for (int index = 0; index < value.length(); index++) {
            if (isMarkupUnsafeControl(value.charAt(index))) {
                if (safe == null) {
                    safe = new StringBuilder(value);
                }
                safe.setCharAt(index, CONTROL_REPLACEMENT);
            }
        }
        return safe == null ? value : safe.toString();
    }

    /**
     * Answers whether a character is a control character with no place in the markup sink.
     *
     * <p>The C0 block, the delete character and the C1 block are all rejected. The C1 range matters
     * specifically because the record charset assigns it to controls, so those code points would reach the
     * object as the control bytes {@code 0x80} to {@code 0x9F} rather than as printable text.
     *
     * <p>Pure function of its argument.
     *
     * @param character the character to examine
     * @return {@code true} when the character must be replaced by a space
     */
    private static boolean isMarkupUnsafeControl(char character) {
        return character < FIRST_PRINTABLE_CHARACTER
                || character == DELETE_CHARACTER
                || (character >= FIRST_C1_CONTROL && character <= LAST_C1_CONTROL);
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
     * {@code '&#123;'} is {@code +0} and {@code 'A'} through {@code 'I'} are {@code +1} through
     * {@code +9}; {@code '&#125;'} is {@code -0} and {@code 'J'} through {@code 'R'} are {@code -1}
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
     * Encodes a signed value into zoned decimal with a trailing overpunch sign, the exact inverse of the
     * decode this class applies to every record it reads.
     *
     * <p>Published as the encode half of the {@code CBSTM03B} record-image contract. A repository-backed
     * dataset binding has to render an entity back into the fixed-width image that
     * {@code WS-M03B-FLDT PIC X(1000)} carried at {@code app/cbl/CBSTM03A.CBL:L82}, and the signed money
     * fields of {@code app/cpy/CVACT01Y.cpy} are zoned decimal with the sign riding on the final digit.
     * Exposing this method is what keeps the overpunch table in one place instead of copying it into every
     * binding: the decoder that consumes the result lives in this same class, so encoder and decoder
     * cannot drift apart.
     *
     * <p>The mapping is <code>'{'</code> for {@code +0} through {@code 'I'} for {@code +9}, and <code>'}'</code> for
     * {@code -0} through {@code 'R'} for {@code -9}, as evidenced by {@code app/data/ASCII/acctdata.txt:L1}
     * and {@code app/data/ASCII/discgrp.txt:L18}. A {@code null} value encodes as positive zero, which is
     * what a {@code COMP-3} field declared {@code VALUE 0} holds. Arithmetic is {@link BigDecimal}
     * throughout; no binary floating-point type appears on this path.
     *
     * <p>This method is a pure function of its arguments.
     *
     * @param value the amount, possibly {@code null}, which encodes as positive zero
     * @param width the declared field width in characters, digits only, the sign riding on the last of them
     * @return exactly {@code width} characters, never {@code null}
     */
    public static String encodeZonedDecimal(BigDecimal value, int width) {
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
