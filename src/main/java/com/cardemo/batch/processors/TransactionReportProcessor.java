/*
 * ******************************************************************
 * Component   : TransactionReportProcessor.java
 * Application : CardDemo
 * Type        : Spring Batch ItemProcessor (Java 25 / Spring Boot 3.5.11)
 * Function    : Daily transaction report - control break, pagination, 133-byte lines.
 * Source      : app/cbl/CBTRN03C.cbl (649 lines, 27 paragraphs) @ 7756d89
 *               app/cpy/CVTRA07Y.cpy (report line layouts)
 *               app/proc/TRANREPT.prc STEP05R (sort + INCLUDE COND)
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Control break, pagination and report line formatting of the daily transaction report batch step.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the one-to-one Java translation of the report producing paragraphs of
 * {@code app/cbl/CBTRN03C.cbl}, the 649 line batch program that {@code app/proc/TRANREPT.prc:L57}
 * runs as {@code STEP10R}. Per transaction it performs three keyed lookups, decides whether a control
 * break or a page break is due, and returns the fixed width report lines that step's writer must emit.
 * Every line it returns is exactly {@value #REPORT_LINE_LENGTH} characters, the record length declared
 * on the {@code TRANREPT} DD at {@code app/proc/TRANREPT.prc:L76} and {@code app/jcl/TRANREPT.jcl:L78},
 * {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)}.
 *
 * <p>The seven line layouts come from {@code app/cpy/CVTRA07Y.cpy}, copied into the program at
 * {@code app/cbl/CBTRN03C.cbl:L113}. Their <em>content</em> widths differ from the record length and are
 * reproduced exactly - 115, 114, 114, 133, 112, 112, 112 - each then space padded on the right to 133 by
 * the {@code MOVE … TO FD-REPTFILE-REC} that precedes every write, {@code FD-REPTFILE-REC} being
 * {@code PIC X(133)} at {@code app/cbl/CBTRN03C.cbl:L85}.
 *
 * <p><strong>This class formats lines; it does not write them.</strong> The file write of
 * {@code 1111-WRITE-REPORT-REC} belongs to the step's {@code ItemWriter}, the sequential read of
 * {@code 1000-TRANFILE-GET-NEXT} belongs to the step's {@code ItemReader}, file status translation
 * belongs to {@link FileStatusMapper}, and the abend payload belongs to
 * {@link FatalProcessingException}. The boundary is deliberate and is the reason no I/O, no
 * {@code FILE STATUS} rendering and no abend constant is reimplemented here.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build with {@code mvn -B clean compile}; the compiler runs with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so any warning is a build failure. Test with {@code mvn -B clean test}. The unit
 * test for this class lives at
 * {@code src/test/java/com/cardemo/unit/batch/TransactionReportProcessorTest.java} and JaCoCo enforces an
 * 80 percent line floor with no exclusions.
 *
 * <p>This class is <strong>not</strong> annotated as a Spring component, and that is a required
 * difference from its stateless sibling {@link TransactionCombineProcessor}. It carries the six
 * {@code WS-REPORT-VARS} state items of {@code app/cbl/CBTRN03C.cbl:L127-L137}, so a singleton instance
 * would leak one job's pagination and totals into the next. {@code config/BatchConfig.java} must
 * therefore register it as a step scoped bean and supply the two reporting dates from the job
 * parameters:
 *
 * <pre>{@code
 * @Bean
 * @StepScope
 * TransactionReportProcessor transactionReportProcessor(
 *         CardCrossReferenceRepository xrefs,
 *         TransactionTypeRepository types,
 *         TransactionCategoryRepository categories,
 *         FileStatusMapper fileStatusMapper,
 *         @Value("#{jobParameters['startDate']}") String startDate,
 *         @Value("#{jobParameters['endDate']}") String endDate) {
 *     return new TransactionReportProcessor(xrefs, types, categories, fileStatusMapper,
 *             startDate, endDate);
 * }
 * }</pre>
 *
 * <p>Keeping the job parameter expressions in the configuration class rather than here leaves this class
 * free of framework value binding and directly constructible from a test, which is what the coverage
 * floor needs. Every collaborator and both dates arrive through the constructor; there is no setter, no
 * static mutable field and no environment lookup.
 *
 * <p><strong>Lifecycle contract.</strong> Call {@link #process(Transaction)} once per record in sort
 * order, then {@link #finishReport()} exactly once after the last record. {@code finishReport} is the
 * end of data branch of the source loop and emits the closing totals; omitting it truncates the report,
 * and calling it twice double counts the closing page total a second time.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 * <caption>Configuration fixed by the source rather than by this class</caption>
 * <tr><th>Item</th><th>Value</th><th>Authority</th></tr>
 * <tr><td>Report record length</td><td>{@value #REPORT_LINE_LENGTH}</td>
 *     <td>{@code app/proc/TRANREPT.prc:L76}</td></tr>
 * <tr><td>Lines per page</td><td>{@value #PAGE_SIZE}</td>
 *     <td>{@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}, {@code app/cbl/CBTRN03C.cbl:L131-L132}</td></tr>
 * <tr><td>Reporting period</td><td>constructor arguments</td>
 *     <td>{@code WS-START-DATE} and {@code WS-END-DATE}, {@code app/cbl/CBTRN03C.cbl:L123-L125}</td></tr>
 * <tr><td>Detail amount mask</td><td>{@code -ZZZ,ZZZ,ZZZ.ZZ}</td>
 *     <td>{@code app/cpy/CVTRA07Y.cpy:L30}</td></tr>
 * <tr><td>Total amount mask</td><td>{@code +ZZZ,ZZZ,ZZZ.ZZ}</td>
 *     <td>{@code app/cpy/CVTRA07Y.cpy:L54, L60, L66}</td></tr>
 * </table>
 *
 * <p>There is no tuneable setting and no default to override. The page size is not a property because it
 * is not configurable in the source: it is a {@code VALUE} clause on a working storage item, and moving
 * it into configuration would let a deployment change the report geometry that Gate 1 compares.
 *
 * <h2>Error modes</h2>
 *
 * <table border="1">
 * <caption>Every failure this class can raise</caption>
 * <tr><th>Condition</th><th>Outcome</th><th>Authority</th></tr>
 * <tr><td>Cross reference missing for the card number</td><td>{@link FatalProcessingException}</td>
 *     <td>{@code 1500-A-LOOKUP-XREF}, {@code app/cbl/CBTRN03C.cbl:L484-L492}</td></tr>
 * <tr><td>Transaction type missing</td><td>{@link FatalProcessingException}</td>
 *     <td>{@code 1500-B-LOOKUP-TRANTYPE}, {@code app/cbl/CBTRN03C.cbl:L494-L502}</td></tr>
 * <tr><td>Transaction category missing</td><td>{@link FatalProcessingException}</td>
 *     <td>{@code 1500-C-LOOKUP-TRANCATG}, {@code app/cbl/CBTRN03C.cbl:L504-L512}</td></tr>
 * <tr><td>A formatted line is not exactly 133 characters</td><td>{@link FatalProcessingException}</td>
 *     <td>{@code 1111-WRITE-REPORT-REC} guard, {@code app/cbl/CBTRN03C.cbl:L346-L358}</td></tr>
 * <tr><td>{@code null} record, absent key field, out of domain numeric</td>
 *     <td>{@link FatalProcessingException}</td><td>Clause B2 boundary handling</td></tr>
 * <tr><td>Processing timestamp outside the reporting period</td><td>{@code null} - the item is filtered</td>
 *     <td>{@code app/cbl/CBTRN03C.cbl:L173-L178}</td></tr>
 * </table>
 *
 * <p>Every one of the three lookups <strong>abends</strong>. Each performs
 * {@code MOVE 23 TO IO-STATUS}, {@code PERFORM 9910-DISPLAY-IO-STATUS} and then
 * {@code PERFORM 9999-ABEND-PROGRAM}, which reaches {@code CALL 'CEE3ABD'} with abend code 999 at
 * {@code app/cbl/CBTRN03C.cbl:L629-L630}. A missing lookup row therefore terminates the job. It is
 * emphatically <em>not</em> a skip, <em>not</em> a filtered item and <em>not</em> a
 * {@code com.cardemo.exception.RecordNotFoundException}: that type is the general translation of file
 * status {@code '23'}, but here the source does not return the status to a caller, it abends on it, so
 * the faithful target is the fatal type carrying abend 999 and return code 12. Return code 4 has no
 * meaning in this program - it is set only by {@code app/cbl/CBTRN02C.cbl:L230} - so no such path exists
 * here.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>Every line differs from the baseline by trailing spaces.</em> The writer, not this class, is
 *       padding or trimming. This class returns exactly 133 characters; a writer that trims trailing
 *       whitespace or appends a platform line separator of the wrong width breaks the fixed block
 *       record. Remedy: write the string as is with an explicit charset and a single line feed.</li>
 *   <li><em>Amounts carry the wrong grouping separator, or a space where a comma belongs.</em> A locale
 *       sensitive formatter has been introduced somewhere on the path. This class never uses one - the
 *       masks are assembled character by character and every {@code String.format} call passes
 *       {@link Locale#ROOT}. Remedy: look for a {@code NumberFormat}, a {@code DecimalFormat} or a
 *       {@code String.format} without a locale in the reader or writer.</li>
 *   <li><em>An amount field is entirely blank.</em> That is correct for a zero value: the mask is fully
 *       zero suppressed, so a zero amount blanks the whole field. See
 *       {@link #formatEditedAmount(BigDecimal, boolean)}.</li>
 *   <li><em>The grand total exceeds the sum of the detail lines by one record's amount.</em> Also
 *       correct, and preserved on purpose. See the end of data note on {@link #finishReport()}.</li>
 *   <li><em>The last card group has no {@code Account Total} line.</em> Correct, and preserved on
 *       purpose, for the same reason.</li>
 *   <li><em>The job abends with {@code INVALID CARD NUMBER}, {@code INVALID TRANSACTION TYPE} or
 *       {@code INVALID TRAN CATG KEY}.</em> A lookup table is unseeded or the sorted input references a
 *       row the reference data does not contain. Remedy: confirm the three lookup tables were loaded by
 *       {@code src/main/resources/db/migration/V3__seed_data.sql} before the step ran; do not soften the
 *       lookup, because the source abends here by design.</li>
 *   <li><em>No headers appear but totals do.</em> The input was empty. Headers are driven by the first
 *       record at {@code app/cbl/CBTRN03C.cbl:L275-L280}, so an empty stream produces closing totals and
 *       nothing else. See {@link #finishReport()}.</li>
 * </ul>
 *
 * <h2>Findings carried by this translation</h2>
 *
 * <p>Classified per clause F2 and destined for {@code DECISION_LOG.md}. Every finding that alters an
 * emitted report record is reproduced rather than repaired: parity is the contract, and clause B1 forbids
 * <em>untracked</em> dead or defective code rather than forbidding the faithful reproduction of a tracked
 * defect. <strong>Exactly one finding is deliberately not reproduced</strong> - the unexamined
 * {@code FILE STATUS} of the third entry below. It is the sole case where reproducing the source would
 * emit a plausible looking but silently wrong report instead of failing, so the safe direction is taken
 * and labelled a deviation rather than parity.
 *
 * <ol>
 *   <li><strong>High - end of data double count.</strong> The last record's amount is added to the page
 *       and account totals a second time, and reaches the grand total through it. Preserved. See
 *       {@link #finishReport()}.</li>
 *   <li><strong>High - {@code NEXT SENTENCE} filter divergence.</strong> The source's out of range
 *       branch terminates the whole read loop; this class filters the single item instead. Preserved as
 *       a documented divergence. See {@link #withinReportingPeriod(Transaction)}.</li>
 *   <li><strong>High - unexamined {@code FILE STATUS} on the three lookups.</strong> Each lookup
 *       {@code SELECT} declares its own status field ({@code app/cbl/CBTRN03C.cbl:L37},
 *       {@code :L43}, {@code :L49}) yet the reading paragraphs ({@code :L485-L491},
 *       {@code :L495-L501}, {@code :L505-L511}) guard only {@code INVALID KEY} and never inspect it, so
 *       a {@code '9x'} physical failure or a {@code '35'} unavailable file is silently ignored and the
 *       report continues from the previous iteration's stale buffer. <strong>Deliberately not
 *       reproduced</strong>, because a silent failure yields a plausible looking but wrong report. See
 *       {@link #abendProgram(String, String, Throwable)}.</li>
 *   <li><strong>Medium - truncating description moves.</strong> Two 50 character descriptions are moved
 *       into 15 and 29 character report fields and are truncated. Reproduced. See
 *       {@link #writeDetail(List, Transaction, TransactionType, TransactionCategory)}.</li>
 *   <li><strong>Medium - zero value sign rendering.</strong> The two mask forms differ on whether the
 *       fixed sign survives full zero suppression. See {@link #formatEditedAmount(BigDecimal, boolean)}.
 *       </li>
 *   <li><strong>Medium - uninitialised amount on an empty stream.</strong> The source reaches its end of
 *       data branch with an unset record buffer; this class defines that case as a zero contribution.
 *       See {@link #finishReport()}.</li>
 *   <li><strong>Low - {@code Account Total} label over a card number break.</strong> Preserved. See
 *       {@link #writeAccountTotals(List)}.</li>
 *   <li><strong>Low - duplicate {@code STEP05R} step name</strong> at {@code app/jcl/TRANREPT.jcl:L23}
 *       and {@code app/jcl/TRANREPT.jcl:L37}. Logged only; it is a property of the job stream, not of
 *       this class.</li>
 *   <li><strong>Low - procedure name mismatch.</strong> {@code app/proc/TRANREPT.prc:L1} declares
 *       {@code //REPROC PROC} while {@code EXEC PROC=TRANREPT} resolves the member name
 *       {@code TRANREPT}. Logged only.</li>
 *   <li><strong>Low - card number masked in log output.</strong> {@code app/cbl/CBTRN03C.cbl:L180} and
 *       {@code :L487} display a full card number. Clause D1 forbids that, so log output is masked while
 *       the report body keeps the value the layout requires. See {@link #maskCardNumber(String)}.</li>
 * </ol>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li>{@code src/main/java/com/cardemo/batch/jobs/**},
 *       {@code src/main/java/com/cardemo/batch/readers/**} and
 *       {@code src/main/java/com/cardemo/batch/writers/**} are unplanned in this branch, so the concrete
 *       reader type, writer type and chunk size this processor runs under are <strong>Not
 *       available</strong>. Needed to close the gap: the generated
 *       {@code TransactionReportJob}, {@code TransactionBackupReader} and report writer, at which point
 *       the {@link ReportLines} carrier can be matched to the writer's item type.</li>
 *   <li>{@code src/main/java/com/cardemo/batch/package-info.java} does not exist in this branch, so the
 *       package level discharge of clause E is <strong>Not available</strong>; this class documentation
 *       discharges the clause at class level in the interim. Needed to close the gap: that file.</li>
 *   <li>No service level objective for report throughput or latency exists anywhere in the source
 *       corpus, so a performance target is <strong>Not available</strong>. Needed to close the gap: a
 *       stakeholder supplied objective. Until then Gate 3 records a measured baseline and this class
 *       asserts none.</li>
 * </ul>
 *
 * @see FileStatusMapper
 * @see FatalProcessingException
 */
public class TransactionReportProcessor
        implements ItemProcessor<Transaction, TransactionReportProcessor.ReportLines> {

    /**
     * Diagnostic logger. It carries the {@code DISPLAY} statements of the translated paragraphs, which
     * are the only instrumentation the source program has: {@code app/cbl/CBTRN03C.cbl} writes to SYSOUT
     * at {@code :L160}, {@code :L180}, {@code :L198}, {@code :L199}, {@code :L215}, {@code :L232},
     * {@code :L487}, {@code :L497}, {@code :L507} and {@code :L627} and instruments nothing else.
     *
     * <p>No Micrometer instrument is registered here. Exactly four counters exist across the tree, they
     * are owned by {@code com.cardemo.observability.MetricsConfig}, and a fifth would breach that
     * contract. Nothing in this class is ever tagged by card number, account identifier or transaction
     * identifier, all three of which are unbounded and would make a metric unusable.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportProcessor.class);

    /**
     * Length of every record this class emits, and of the report file itself.
     *
     * <p>{@code FD-REPTFILE-REC PIC X(133)} at {@code app/cbl/CBTRN03C.cbl:L85}, corroborated by the
     * {@code TRANREPT} DD statement {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} at
     * {@code app/proc/TRANREPT.prc:L76} and {@code app/jcl/TRANREPT.jcl:L78}. Fixed block means every
     * record is exactly this long, so the value is a parity contract rather than a maximum.
     */
    private static final int REPORT_LINE_LENGTH = 133;

    /**
     * Lines per page, from {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at
     * {@code app/cbl/CBTRN03C.cbl:L131-L132}.
     *
     * <p>It is the divisor of the page break test at {@code app/cbl/CBTRN03C.cbl:L282},
     * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}. Because the test is a modulus of the
     * running line counter and not a count of detail lines, the number of <em>detail</em> lines between
     * two page breaks is not 20: the totals and header lines each advance the counter as well.
     */
    private static final int PAGE_SIZE = 20;

    /**
     * Characters of the processing timestamp the date filter compares, from the reference modifier
     * {@code TRAN-PROC-TS (1:10)} at {@code app/cbl/CBTRN03C.cbl:L173-L174}.
     *
     * <p>It agrees with the DFSORT symbol {@code TRAN-PROC-DT,305,10,CH} at
     * {@code app/proc/TRANREPT.prc:L40}: ten characters at offset 305 of the 350 byte record, which is
     * the leading date of {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17}.
     */
    private static final int PROC_DATE_LENGTH = 10;

    /** Width of {@code WS-CURR-CARD-NUM PIC X(16)} at {@code app/cbl/CBTRN03C.cbl:L137}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The value {@code WS-CURR-CARD-NUM} holds before the first record, from
     * {@code PIC X(16) VALUE SPACES} at {@code app/cbl/CBTRN03C.cbl:L137}.
     *
     * <p>Sixteen spaces cannot collide with any real card number, which is what makes the control break
     * at {@code app/cbl/CBTRN03C.cbl:L181} fire unconditionally on the first record.
     */
    private static final String NO_CURRENT_CARD = " ".repeat(CARD_NUMBER_WIDTH);

    /**
     * Digits of the card number left visible in log output, the remainder being masked.
     *
     * <p>Four is the customary last group. See {@link #maskCardNumber(String)} for why masking happens at
     * all when the source displays the value in full.
     */
    private static final int CARD_NUMBER_VISIBLE_DIGITS = 4;

    /**
     * The component name reported as {@code ABEND-CULPRIT} on every abend this class raises.
     *
     * <p>{@code ABEND-CULPRIT} is {@code PIC X(8)} in the abend work area copybook, and
     * {@code CBTRN03C} is exactly eight characters, so the value needs no padding and no truncation.
     */
    private static final String ABEND_CULPRIT = "CBTRN03C";

    /**
     * The file status the three lookup paragraphs move before displaying it and abending, from
     * {@code MOVE 23 TO IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:L488}, {@code :L498} and {@code :L508}.
     *
     * <p>The source literal is the <em>unquoted numeric</em> {@code 23} moved into a two byte
     * alphanumeric group, which lands as the characters {@code '23'}. Passed to
     * {@link FileStatusMapper#displayIoStatus(String)} it renders the legacy line
     * {@code FILE STATUS IS: NNNN0023}, because {@code 9910-DISPLAY-IO-STATUS} at
     * {@code app/cbl/CBTRN03C.cbl:L634-L644} takes its numeric branch for a status whose first byte is
     * not {@code '9'}.
     */
    private static final String LOOKUP_FAILURE_IO_STATUS = "23";

    /** {@code DISPLAY 'ABENDING PROGRAM'}, {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBTRN03C.cbl:L627}. */
    private static final String ABENDING_PROGRAM_TEXT = "ABENDING PROGRAM";

    /** {@code DISPLAY 'INVALID CARD NUMBER : '}, {@code app/cbl/CBTRN03C.cbl:L487}. */
    private static final String INVALID_CARD_NUMBER_TEXT = "INVALID CARD NUMBER : ";

    /** {@code DISPLAY 'INVALID TRANSACTION TYPE : '}, {@code app/cbl/CBTRN03C.cbl:L497}. */
    private static final String INVALID_TRANSACTION_TYPE_TEXT = "INVALID TRANSACTION TYPE : ";

    /** {@code DISPLAY 'INVALID TRAN CATG KEY : '}, {@code app/cbl/CBTRN03C.cbl:L507}. */
    private static final String INVALID_TRAN_CATG_KEY_TEXT = "INVALID TRAN CATG KEY : ";

    /**
     * {@code DISPLAY 'ERROR WRITING REPTFILE'}, the failure text of the {@code 1111-WRITE-REPORT-REC}
     * guard at {@code app/cbl/CBTRN03C.cbl:L354}.
     */
    private static final String ERROR_WRITING_REPTFILE_TEXT = "ERROR WRITING REPTFILE";

    /**
     * Width of the edited amount fields, {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy:L30}
     * and {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at {@code :L54}, {@code :L60} and {@code :L66}.
     *
     * <p>One sign, nine digit positions, two grouping commas, one decimal point and two decimal digits:
     * {@code 1 + 3 + 1 + 3 + 1 + 3 + 1 + 2 = 15}.
     */
    private static final int EDITED_AMOUNT_WIDTH = 15;

    /**
     * Integer digit positions in both amount masks: the nine {@code Z} symbols of
     * {@code ZZZ,ZZZ,ZZZ}.
     *
     * <p>It matches the sending fields exactly. {@code TRAN-AMT} is {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10} and the three accumulators are {@code PIC S9(09)V99} at
     * {@code app/cbl/CBTRN03C.cbl:L134-L136}, so a single value always fits. A running total need not:
     * see the truncation note on {@link #formatEditedAmount(BigDecimal, boolean)}.
     */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digit positions in both amount masks: the two {@code Z} symbols after the point. */
    private static final int AMOUNT_SCALE = 2;

    /** {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'}, {@code app/cpy/CVTRA07Y.cpy:L5-L6}. */
    private static final String REPT_SHORT_NAME_TEXT = "DALYREPT";

    /** Declared width of {@code REPT-SHORT-NAME}, {@code app/cpy/CVTRA07Y.cpy:L5}. */
    private static final int REPT_SHORT_NAME_WIDTH = 38;

    /**
     * {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'},
     * {@code app/cpy/CVTRA07Y.cpy:L7-L8}.
     */
    private static final String REPT_LONG_NAME_TEXT = "Daily Transaction Report";

    /** Declared width of {@code REPT-LONG-NAME}, {@code app/cpy/CVTRA07Y.cpy:L7}. */
    private static final int REPT_LONG_NAME_WIDTH = 41;

    /**
     * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '},
     * {@code app/cpy/CVTRA07Y.cpy:L9-L10}. The literal is exactly twelve characters including its
     * trailing space, so it fills the field with no padding.
     */
    private static final String REPT_DATE_HEADER_TEXT = "Date Range: ";

    /** Declared width of {@code REPT-DATE-HEADER}, {@code app/cpy/CVTRA07Y.cpy:L9}. */
    private static final int REPT_DATE_HEADER_WIDTH = 12;

    /**
     * Width of {@code REPT-START-DATE} and {@code REPT-END-DATE}, both {@code PIC X(10) VALUE SPACES} at
     * {@code app/cpy/CVTRA07Y.cpy:L11} and {@code :L13}.
     */
    private static final int REPT_DATE_WIDTH = 10;

    /**
     * The value {@code REPT-START-DATE} and {@code REPT-END-DATE} hold before
     * {@code 1100-WRITE-TRANSACTION-REPORT} moves the reporting period into them, from
     * {@code VALUE SPACES} at {@code app/cpy/CVTRA07Y.cpy:L11} and {@code :L13}.
     */
    private static final String UNSET_REPORT_DATE = " ".repeat(REPT_DATE_WIDTH);

    /**
     * {@code FILLER PIC X(04) VALUE ' to '}, {@code app/cpy/CVTRA07Y.cpy:L12}. Four characters, so the
     * leading and trailing spaces are part of the literal rather than padding.
     */
    private static final String DATE_RANGE_SEPARATOR_TEXT = " to ";

    /** Declared width of the {@code ' to '} filler, {@code app/cpy/CVTRA07Y.cpy:L12}. */
    private static final int DATE_RANGE_SEPARATOR_WIDTH = 4;

    /** {@code FILLER PIC X(17) VALUE 'Transaction ID'}, {@code app/cpy/CVTRA07Y.cpy:L34-L35}. */
    private static final String HEADING_TRANSACTION_ID_TEXT = "Transaction ID";

    /** Declared width of the transaction identifier heading, {@code app/cpy/CVTRA07Y.cpy:L34}. */
    private static final int HEADING_TRANSACTION_ID_WIDTH = 17;

    /** {@code FILLER PIC X(12) VALUE 'Account ID'}, {@code app/cpy/CVTRA07Y.cpy:L36-L37}. */
    private static final String HEADING_ACCOUNT_ID_TEXT = "Account ID";

    /** Declared width of the account identifier heading, {@code app/cpy/CVTRA07Y.cpy:L36}. */
    private static final int HEADING_ACCOUNT_ID_WIDTH = 12;

    /** {@code FILLER PIC X(19) VALUE 'Transaction Type'}, {@code app/cpy/CVTRA07Y.cpy:L38-L39}. */
    private static final String HEADING_TRANSACTION_TYPE_TEXT = "Transaction Type";

    /** Declared width of the transaction type heading, {@code app/cpy/CVTRA07Y.cpy:L38}. */
    private static final int HEADING_TRANSACTION_TYPE_WIDTH = 19;

    /** {@code FILLER PIC X(35) VALUE 'Tran Category'}, {@code app/cpy/CVTRA07Y.cpy:L40-L41}. */
    private static final String HEADING_TRAN_CATEGORY_TEXT = "Tran Category";

    /** Declared width of the category heading, {@code app/cpy/CVTRA07Y.cpy:L40}. */
    private static final int HEADING_TRAN_CATEGORY_WIDTH = 35;

    /** {@code FILLER PIC X(14) VALUE 'Tran Source'}, {@code app/cpy/CVTRA07Y.cpy:L42-L43}. */
    private static final String HEADING_TRAN_SOURCE_TEXT = "Tran Source";

    /** Declared width of the source heading, {@code app/cpy/CVTRA07Y.cpy:L42}. */
    private static final int HEADING_TRAN_SOURCE_WIDTH = 14;

    /**
     * {@code FILLER PIC X VALUE SPACES}, the single space between the source and amount headings at
     * {@code app/cpy/CVTRA07Y.cpy:L44}. A bare {@code PIC X} is one character.
     */
    private static final int HEADING_GAP_WIDTH = 1;

    /**
     * {@code FILLER PIC X(16) VALUE '        Amount'}, {@code app/cpy/CVTRA07Y.cpy:L45-L46}.
     *
     * <p><strong>Eight leading spaces then the six characters of {@code Amount}</strong>, fourteen
     * characters of literal in a sixteen character field, so two trailing spaces of padding follow. The
     * leading spaces right align the heading over the fifteen character amount column and are part of the
     * literal, not of the padding.
     */
    private static final String HEADING_AMOUNT_TEXT = "        Amount";

    /** Declared width of the amount heading, {@code app/cpy/CVTRA07Y.cpy:L45}. */
    private static final int HEADING_AMOUNT_WIDTH = 16;

    /** {@code FILLER PIC X(11) VALUE 'Page Total'}, {@code app/cpy/CVTRA07Y.cpy:L51-L52}. */
    private static final String PAGE_TOTAL_LABEL_TEXT = "Page Total";

    /** Declared width of the page total label, {@code app/cpy/CVTRA07Y.cpy:L51}. */
    private static final int PAGE_TOTAL_LABEL_WIDTH = 11;

    /** {@code FILLER PIC X(86) VALUE ALL '.'}, the page total leader, {@code app/cpy/CVTRA07Y.cpy:L53}. */
    private static final int PAGE_TOTAL_LEADER_WIDTH = 86;

    /**
     * {@code FILLER PIC X(13) VALUE 'Account Total'}, {@code app/cpy/CVTRA07Y.cpy:L57-L58}. Exactly
     * thirteen characters, so the field is filled with no padding.
     */
    private static final String ACCOUNT_TOTAL_LABEL_TEXT = "Account Total";

    /** Declared width of the account total label, {@code app/cpy/CVTRA07Y.cpy:L57}. */
    private static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;

    /**
     * {@code FILLER PIC X(84) VALUE ALL '.'}, the account total leader,
     * {@code app/cpy/CVTRA07Y.cpy:L59}. Two characters shorter than the other two leaders because the
     * label is two characters longer, which is what keeps all three amount columns aligned at 112.
     */
    private static final int ACCOUNT_TOTAL_LEADER_WIDTH = 84;

    /**
     * {@code FILLER PIC X(11) VALUE 'Grand Total'}, {@code app/cpy/CVTRA07Y.cpy:L63-L64}. Exactly eleven
     * characters, so the field is filled with no padding.
     */
    private static final String GRAND_TOTAL_LABEL_TEXT = "Grand Total";

    /** Declared width of the grand total label, {@code app/cpy/CVTRA07Y.cpy:L63}. */
    private static final int GRAND_TOTAL_LABEL_WIDTH = 11;

    /** {@code FILLER PIC X(86) VALUE ALL '.'}, the grand total leader, {@code app/cpy/CVTRA07Y.cpy:L65}. */
    private static final int GRAND_TOTAL_LEADER_WIDTH = 86;

    /** {@code TRAN-REPORT-TRANS-ID PIC X(16)}, {@code app/cpy/CVTRA07Y.cpy:L16}. */
    private static final int DETAIL_TRANS_ID_WIDTH = 16;

    /** {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, {@code app/cpy/CVTRA07Y.cpy:L18}. */
    private static final int DETAIL_ACCOUNT_ID_WIDTH = 11;

    /** {@code TRAN-REPORT-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA07Y.cpy:L20}. */
    private static final int DETAIL_TYPE_CODE_WIDTH = 2;

    /**
     * {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, {@code app/cpy/CVTRA07Y.cpy:L22}. Fifteen characters
     * receiving a fifty character description, which truncates. See
     * {@link #writeDetail(List, Transaction, TransactionType, TransactionCategory)}.
     */
    private static final int DETAIL_TYPE_DESC_WIDTH = 15;

    /** {@code TRAN-REPORT-CAT-CD PIC 9(04)}, {@code app/cpy/CVTRA07Y.cpy:L24} - numeric, zero padded. */
    private static final int DETAIL_CATEGORY_CODE_WIDTH = 4;

    /**
     * {@code TRAN-REPORT-CAT-DESC PIC X(29)}, {@code app/cpy/CVTRA07Y.cpy:L26}. Twenty nine characters
     * receiving a fifty character description, which truncates.
     */
    private static final int DETAIL_CATEGORY_DESC_WIDTH = 29;

    /** {@code TRAN-REPORT-SOURCE PIC X(10)}, {@code app/cpy/CVTRA07Y.cpy:L28}. */
    private static final int DETAIL_SOURCE_WIDTH = 10;

    /**
     * The single space {@code FILLER PIC X(01) VALUE SPACES} that separates most detail fields, at
     * {@code app/cpy/CVTRA07Y.cpy:L17}, {@code :L19}, {@code :L23}, {@code :L27} and {@code :L31}.
     */
    private static final String DETAIL_SPACE_FILLER = " ";

    /**
     * {@code FILLER PIC X(01) VALUE '-'}, the two hyphen separators at
     * {@code app/cpy/CVTRA07Y.cpy:L21} and {@code :L25}.
     *
     * <p>They survive the {@code INITIALIZE} at {@code app/cbl/CBTRN03C.cbl:L362} because
     * {@code INITIALIZE} leaves {@code FILLER} items untouched, which is exactly why the detail line
     * carries {@code nn-description} and {@code nnnn-description} rather than two spaces.
     */
    private static final String DETAIL_HYPHEN_FILLER = "-";

    /** {@code FILLER PIC X(04) VALUE SPACES} before the amount, {@code app/cpy/CVTRA07Y.cpy:L29}. */
    private static final String DETAIL_AMOUNT_PREFIX_FILLER = "    ";

    /** {@code FILLER PIC X(02) VALUE SPACES} closing the detail line, {@code app/cpy/CVTRA07Y.cpy:L31}. */
    private static final String DETAIL_TRAILING_FILLER = "  ";

    /**
     * The lowest value {@code TRAN-REPORT-ACCOUNT-ID} and {@code TRAN-REPORT-CAT-CD} can carry.
     *
     * <p>Both receiving fields are unsigned - {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7} and {@code TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:L7} - so a negative value is outside the domain the layout can express
     * and is rejected rather than rendered with a sign the field has no room for.
     */
    private static final long MIN_UNSIGNED_DISPLAY_VALUE = 0L;

    /**
     * The body of both amount masks, exactly as the copybook declares it after the fixed sign symbol.
     *
     * <p>{@code app/cpy/CVTRA07Y.cpy:L30} declares {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} and {@code :L54},
     * {@code :L60} and {@code :L66} declare {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}. Removing the leading sign symbol
     * leaves the fourteen character body that both share, and one plus fourteen is the
     * {@value #EDITED_AMOUNT_WIDTH} character field width.
     *
     * <p>{@link #formatEditedAmount(BigDecimal, boolean)} walks this string symbol by symbol rather than
     * indexing hard coded comma and point positions, so the copybook's own layout is the algorithm. Change
     * the mask here and the renderer follows.
     */
    private static final String AMOUNT_MASK_BODY = "ZZZ,ZZZ,ZZZ.ZZ";

    /** The COBOL zero suppression symbol, one digit position, from the masks of {@link #AMOUNT_MASK_BODY}. */
    private static final char ZERO_SUPPRESSION_SYMBOL = 'Z';

    /** The mask's grouping separator, printed only outside the suppressed region. */
    private static final char GROUPING_SEPARATOR = ',';

    /** The mask's decimal point, always printed, and the point at which zero suppression stops. */
    private static final char DECIMAL_POINT = '.';

    /** The negative sign both masks print in position one, from {@code app/cpy/CVTRA07Y.cpy:L30}. */
    private static final char SIGN_NEGATIVE = '-';

    /** The mandatory positive sign of the total masks, from {@code app/cpy/CVTRA07Y.cpy:L54}. */
    private static final char SIGN_POSITIVE = '+';

    /**
     * The space a single {@code -} sign symbol prints for a non negative value, and the space a suppressed
     * digit or grouping position prints.
     */
    private static final char SIGN_BLANK = ' ';

    /**
     * The leader character of the three total lines.
     *
     * <p>{@code FILLER PIC X(86) VALUE ALL '.'} at {@code app/cpy/CVTRA07Y.cpy:L52}, {@code X(84)} at
     * {@code :L58} and {@code X(86)} at {@code :L64}.
     */
    private static final char LEADER_CHARACTER = '.';

    /**
     * The rule character of {@code TRANSACTION-HEADER-2}.
     *
     * <p>{@code PIC X(133) VALUE ALL '-'} at {@code app/cpy/CVTRA07Y.cpy:L48}.
     */
    private static final char RULE_CHARACTER = '-';

    /**
     * The {@code DATEPARM} failure text of {@code 0550-DATEPARM-READ}.
     *
     * <p>{@code DISPLAY 'ERROR READING DATEPARM FILE'} at {@code app/cbl/CBTRN03C.cbl:L238}, reached by the
     * {@code OTHER} branch of the {@code EVALUATE} at {@code :L237}. The reporting period arrives as job
     * parameters rather than as an eighty byte record here, so this text carries the equivalent condition:
     * a period that cannot be used.
     */
    private static final String ERROR_READING_DATEPARM_TEXT = "ERROR READING DATEPARM FILE";

    /**
     * The stand in a log statement uses for a value that is absent.
     *
     * <p>A COBOL field cannot be absent, so the source has no counterpart. A JPA attribute can be, and a
     * log line reading {@code (absent)} is unambiguous where an empty string would be invisible.
     */
    private static final String ABSENT_VALUE = "(absent)";

    /**
     * The character a log statement substitutes for a control character.
     *
     * <p>Report data reaches the log through {@link #logSafe(String)}. A control character in a persisted
     * field would otherwise let a crafted value forge a line break and a second log record, so every
     * character below the space is replaced. {@code '?'} is chosen over {@code '.'} because the report's own
     * total lines are made of dots, and a substitution must not be mistakable for report content.
     */
    private static final char LOG_SUBSTITUTE_CHARACTER = '?';

    /**
     * The character {@link #maskCardNumber(String)} substitutes for a hidden card digit.
     *
     * <p>The source displays the card number in full at {@code app/cbl/CBTRN03C.cbl:L487}; the no secrets
     * clause of the project standard does not permit that on an aggregated log stream. See
     * {@link #maskCardNumber(String)} for the full finding.
     */
    private static final char CARD_MASK_CHARACTER = '*';

    /**
     * The fixed width report lines one input record produces, in emission order.
     *
     * <p>A carrier is needed because a single transaction does not map to a single output line. The
     * source can emit up to seven lines for one record: the four line header block of
     * {@code 1120-WRITE-HEADERS} at {@code app/cbl/CBTRN03C.cbl:L324-L341}, the two line page total block
     * of {@code 1110-WRITE-PAGE-TOTALS} at {@code :L293-L304} - both reachable from the page break at
     * {@code :L282-L284} - and the one detail line of {@code 1120-WRITE-DETAIL} at {@code :L289}. A
     * control break adds the two line account total block of {@code :L306-L316} on top of that.
     *
     * <p>It is a nested record rather than a separate file on purpose: the batch processor package holds
     * the five processor classes and nothing else, so a standalone carrier would breach that structure
     * for a type with no meaning outside this class.
     *
     * <p>The constructor is the single enforcement point for the geometry invariant. Every line is
     * checked to be exactly {@value #REPORT_LINE_LENGTH} characters, and the list is copied, so an
     * instance is immutable and cannot carry a record the report file could not hold. That check is what
     * makes the Gate 1 obligation - every emitted line is exactly 133 characters - hold by construction
     * rather than by inspection.
     *
     * @param lines the report lines, in the order the writer must emit them, never {@code null}, never
     * empty and each exactly {@value #REPORT_LINE_LENGTH} characters long.
     */
    public record ReportLines(List<String> lines) {

        /**
         * Validates the geometry of every line and takes an immutable copy.
         *
         * @throws FatalProcessingException if the list is {@code null}, is empty, contains a
         * {@code null}, or contains a line whose length is not {@value #REPORT_LINE_LENGTH}. The abend
         * carries the {@code 1111-WRITE-REPORT-REC} failure text, because a record of the wrong length is
         * precisely what that paragraph's guard at {@code app/cbl/CBTRN03C.cbl:L346-L358} exists to stop
         * reaching the report file.
         */
        public ReportLines {
            if (lines == null) {
                throw abendProgram("the report line list is null, so no record can be written",
                        ERROR_WRITING_REPTFILE_TEXT);
            }
            if (lines.isEmpty()) {
                throw abendProgram("the report line list is empty, so the record set carries nothing to write",
                        ERROR_WRITING_REPTFILE_TEXT);
            }
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line == null) {
                    throw abendProgram(String.format(Locale.ROOT,
                            "report line %d of %d is null", index + 1, lines.size()),
                            ERROR_WRITING_REPTFILE_TEXT);
                }
                if (line.length() != REPORT_LINE_LENGTH) {
                    throw abendProgram(String.format(Locale.ROOT,
                            "report line %d of %d is %d characters, and the fixed block record length is %d",
                            index + 1, lines.size(), line.length(), REPORT_LINE_LENGTH),
                            ERROR_WRITING_REPTFILE_TEXT);
                }
            }
            lines = List.copyOf(lines);
        }
    }

    /**
     * Cross reference access, replacing CICS file {@code CARDXREF} declared at
     * {@code app/cbl/CBTRN03C.cbl:L33-L37} with {@code RECORD KEY IS FD-XREF-CARD-NUM}.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Transaction type access, replacing CICS file {@code TRANTYPE} declared at
     * {@code app/cbl/CBTRN03C.cbl:L39-L43} with {@code RECORD KEY IS FD-TRAN-TYPE}.
     */
    private final TransactionTypeRepository transactionTypeRepository;

    /**
     * Transaction category access, replacing CICS file {@code TRANCATG} declared at
     * {@code app/cbl/CBTRN03C.cbl:L45-L49} with {@code RECORD KEY IS FD-TRAN-CAT-KEY}, the six byte
     * composite key of {@code app/cbl/CBTRN03C.cbl:L79-L81}.
     */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * The single tree wide translator of {@code FILE STATUS} values and the single implementation of the
     * {@code 9910-DISPLAY-IO-STATUS} rendering.
     *
     * <p>It is injected rather than reimplemented. This class uses only
     * {@link FileStatusMapper#displayIoStatus(String)}, to reproduce the display line the three lookup
     * paragraphs emit before they abend.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Inclusive lower bound of the reporting period, holding {@code WS-START-DATE PIC X(10)} from
     * {@code app/cbl/CBTRN03C.cbl:L123}.
     *
     * <p>It is compared as a string, never parsed. See {@link #withinReportingPeriod(Transaction)}.
     */
    private final String startDate;

    /**
     * Inclusive upper bound of the reporting period, holding {@code WS-END-DATE PIC X(10)} from
     * {@code app/cbl/CBTRN03C.cbl:L125}.
     */
    private final String endDate;

    /**
     * {@code WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0}, {@code app/cbl/CBTRN03C.cbl:L129-L130}.
     *
     * <p>Advanced only where the source advances it, because the page break test at
     * {@code app/cbl/CBTRN03C.cbl:L282} is a modulus of this value: two by
     * {@link #writePageTotals(List)}, two by {@link #writeAccountTotals(List)}, four by
     * {@link #writeHeaders(List)}, one by
     * {@link #writeDetail(List, Transaction, TransactionType, TransactionCategory)} and
     * <strong>none</strong> by {@link #writeGrandTotals(List)}. A {@code long} holds the nine digit
     * display domain with room to spare.
     */
    private long lineCounter;

    /**
     * {@code WS-PAGE-TOTAL PIC S9(09)V99 VALUE 0}, {@code app/cbl/CBTRN03C.cbl:L134}.
     *
     * <p>Accumulated per detail line at {@code app/cbl/CBTRN03C.cbl:L287-L288}, rolled into
     * {@link #grandTotal} and reset by {@link #writePageTotals(List)}.
     */
    private BigDecimal pageTotal;

    /**
     * {@code WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0}, {@code app/cbl/CBTRN03C.cbl:L135}.
     *
     * <p>Accumulated alongside {@link #pageTotal} and reset by {@link #writeAccountTotals(List)}. It never
     * contributes to {@link #grandTotal}: the only rollup in the program is the page total one at
     * {@code app/cbl/CBTRN03C.cbl:L297}.
     */
    private BigDecimal accountTotal;

    /**
     * {@code WS-GRAND-TOTAL PIC S9(09)V99 VALUE 0}, {@code app/cbl/CBTRN03C.cbl:L136}.
     *
     * <p>Fed exclusively by {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL} at
     * {@code app/cbl/CBTRN03C.cbl:L297}, so it is a sum of page totals and not an independent sum of
     * detail amounts. That distinction is why the end of data double count reaches it.
     */
    private BigDecimal grandTotal;

    /**
     * {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES}, {@code app/cbl/CBTRN03C.cbl:L137}.
     *
     * <p>The control break key of {@code app/cbl/CBTRN03C.cbl:L181}. Always held space padded to sixteen
     * characters so the comparison is the fixed width one the source performs.
     */
    private String currentCardNumber;

    /**
     * {@code WS-FIRST-TIME PIC X VALUE 'Y'}, {@code app/cbl/CBTRN03C.cbl:L128}, as a boolean where
     * {@code true} is {@code 'Y'}.
     *
     * <p>It is read twice and written once. {@code app/cbl/CBTRN03C.cbl:L182} suppresses the account
     * total block on the first record, and {@code :L275-L276} writes the header block and clears the flag.
     * The write happens inside {@code 1100-WRITE-TRANSACTION-REPORT}, <em>after</em> the control break
     * test, which is exactly why the first record produces no account total line.
     */
    private boolean firstTime;

    /**
     * The cross reference record last read by {@code 1500-A-LOOKUP-XREF}, holding
     * {@code CARD-XREF-RECORD} from {@code app/cbl/CBTRN03C.cbl:L98}.
     *
     * <p>It is state rather than a local because the source reads it only on a control break at
     * {@code app/cbl/CBTRN03C.cbl:L187} while the detail line consumes {@code XREF-ACCT-ID} on
     * <em>every</em> record at {@code :L364}. Records two onward of a card group therefore read the
     * account identifier out of this buffer, which is also why no repeat lookup is issued for them.
     */
    private CardCrossReference currentCrossReference;

    /**
     * {@code REPT-START-DATE PIC X(10) VALUE SPACES}, {@code app/cpy/CVTRA07Y.cpy:L11}.
     *
     * <p>Held separately from {@link #startDate} so the {@code MOVE WS-START-DATE TO REPT-START-DATE} of
     * {@code app/cbl/CBTRN03C.cbl:L277} is reproduced as the assignment it is. The distinction is
     * observable: on an empty input stream the move never happens, and the header line that would have
     * carried it is never written either.
     */
    private String reportStartDate;

    /** {@code REPT-END-DATE PIC X(10) VALUE SPACES}, {@code app/cpy/CVTRA07Y.cpy:L13}. */
    private String reportEndDate;

    /**
     * The transaction amount left in the record buffer when the read at end of data fails, which the end
     * of data branch at {@code app/cbl/CBTRN03C.cbl:L198-L201} then consumes.
     *
     * <p>COBOL's {@code READ … INTO TRAN-RECORD} at {@code app/cbl/CBTRN03C.cbl:L249} does not clear the
     * buffer when it reports end of file, so {@code TRAN-AMT} still holds the last record that was read.
     * This field is that buffer. It is assigned at the very top of {@link #process(Transaction)}, before
     * the date filter, because the source's assignment - the {@code READ} itself - likewise precedes the
     * filter test at {@code :L173}. A record the filter rejects has still been read, and would still be
     * the value left behind at end of data.
     *
     * <p>{@code null} means no record was ever read. See {@link #finishReport()} for how that is resolved.
     */
    private BigDecimal lastAmount;

    /**
     * Creates a processor for one step execution of the transaction report job.
     *
     * <p>Every collaborator and both bounds of the reporting period are supplied here; the instance holds
     * no other input and reads no environment variable, system property or configuration file. The two
     * dates replace the {@code DATEPARM} read of {@code 0550-DATEPARM-READ} at
     * {@code app/cbl/CBTRN03C.cbl:L220-L243}, whose eighty byte record - {@code FD-DATEPARM-REC PIC X(80)}
     * at {@code :L88} - carries only the twenty one bytes of {@code WS-DATEPARM-RECORD} at
     * {@code :L122-L125}: a ten character start date, a one character separator and a ten character end
     * date. A control dataset holding two dates is a job parameter in Spring Batch, so the read becomes
     * this constructor and the validation it performs.
     *
     * <p>The state items of {@code WS-REPORT-VARS} at {@code app/cbl/CBTRN03C.cbl:L127-L137} are
     * initialised here to their {@code VALUE} clauses. Because that state advances as records arrive, an
     * instance is good for exactly one step execution - see the registration contract on the class
     * documentation.
     *
     * @param cardCrossReferenceRepository cross reference access, replacing CICS file {@code CARDXREF}.
     * @param transactionTypeRepository transaction type access, replacing CICS file {@code TRANTYPE}.
     * @param transactionCategoryRepository category access, replacing CICS file {@code TRANCATG}.
     * @param fileStatusMapper the tree wide file status translator and status line renderer.
     * @param startDate inclusive first processing date of the report, ten characters, compared as a
     * string and never parsed, carrying {@code WS-START-DATE}.
     * @param endDate inclusive last processing date of the report, ten characters, carrying
     * {@code WS-END-DATE}.
     * @throws FatalProcessingException if any collaborator is {@code null}, if either date is
     * {@code null} or blank, or if the period is inverted. Each is a condition the source could not reach
     * - a missing DD abends at open, and an inverted period would silently produce an empty report - so
     * each is reported here rather than allowed to yield a report that looks complete and is not.
     */
    public TransactionReportProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            FileStatusMapper fileStatusMapper,
            String startDate,
            String endDate) {
        this.cardCrossReferenceRepository = requireCollaborator(cardCrossReferenceRepository,
                "CardCrossReferenceRepository", "CARDXREF");
        this.transactionTypeRepository = requireCollaborator(transactionTypeRepository,
                "TransactionTypeRepository", "TRANTYPE");
        this.transactionCategoryRepository = requireCollaborator(transactionCategoryRepository,
                "TransactionCategoryRepository", "TRANCATG");
        this.fileStatusMapper = requireCollaborator(fileStatusMapper, "FileStatusMapper", "IO-STATUS");
        this.startDate = requireReportingDate(startDate, "WS-START-DATE");
        this.endDate = requireReportingDate(endDate, "WS-END-DATE");
        requireOrderedPeriod(this.startDate, this.endDate);

        this.lineCounter = 0L;
        this.pageTotal = BigDecimal.ZERO;
        this.accountTotal = BigDecimal.ZERO;
        this.grandTotal = BigDecimal.ZERO;
        this.currentCardNumber = NO_CURRENT_CARD;
        this.firstTime = true;
        this.currentCrossReference = null;
        this.reportStartDate = UNSET_REPORT_DATE;
        this.reportEndDate = UNSET_REPORT_DATE;
        this.lastAmount = null;

        dateParmRead(this.startDate, this.endDate);
    }

    /**
     * Produces the report lines for one transaction, or filters it out.
     *
     * <p>This is the body of the read loop of {@code app/cbl/CBTRN03C.cbl:L170-L206} for a single
     * iteration in which a record was successfully read - the {@code IF END-OF-FILE = 'N'} branch at
     * {@code :L179}. In source order it captures the record buffer, applies the date re-filter of
     * {@code :L173-L178}, displays the record at {@code :L180}, evaluates the control break of
     * {@code :L181-L188}, performs the type lookup of {@code :L189-L190} and the composite category lookup
     * of {@code :L191-L195}, and finally performs {@code 1100-WRITE-TRANSACTION-REPORT} at {@code :L196}.
     *
     * <p>The complementary {@code ELSE} branch at {@code :L197-L203}, taken when the read reported end of
     * file, is {@link #finishReport()}.
     *
     * <p><strong>Side effects.</strong> Advances the line counter, the three accumulators, the control
     * break key, the first record flag, the cross reference buffer and the record buffer - the six
     * {@code WS-REPORT-VARS} items plus the cross reference record. It performs three keyed reads and
     * writes nothing to any file.
     *
     * @param item the transaction to report, delivered by the step's reader in the card number ascending
     * order that {@code app/proc/TRANREPT.prc:L44} establishes. The ordering is a precondition this class
     * relies on and cannot verify from a single item: it is what makes a control break on change of card
     * number equivalent to one group per card.
     * @return the report lines this record produces, between one and seven of them, each exactly
     * {@value #REPORT_LINE_LENGTH} characters; or {@code null} when the record's processing date falls
     * outside the reporting period, which is Spring Batch's signal to filter the item.
     * @throws FatalProcessingException if the item is {@code null}, if a required key field is absent, or
     * if any of the three lookups finds no row - the last reproducing the abend that
     * {@code app/cbl/CBTRN03C.cbl:L490}, {@code :L500} and {@code :L510} perform.
     */
    @Override
    public ReportLines process(Transaction item) {
        if (item == null) {
            throw abendProgram("the reader supplied a null record, so there is nothing to report on",
                    ABENDING_PROGRAM_TEXT);
        }

        // app/cbl/CBTRN03C.cbl:L172 - the READ has already happened in the step's reader, and the record
        // buffer it fills is what the end of data branch reads back. Capture it before the filter, for the
        // reason set out on the lastAmount field.
        this.lastAmount = requireAmount(item);

        // app/cbl/CBTRN03C.cbl:L173-L178 - the date re-filter.
        if (!withinReportingPeriod(item)) {
            return null;
        }

        List<String> lines = new ArrayList<>();

        // app/cbl/CBTRN03C.cbl:L180 - DISPLAY TRAN-RECORD.
        displayTranRecord(item);

        // app/cbl/CBTRN03C.cbl:L181-L188 - the control break.
        String cardNumber = fixedWidth(item.getCardNumber(), CARD_NUMBER_WIDTH);
        if (!this.currentCardNumber.equals(cardNumber)) {
            if (!this.firstTime) {
                writeAccountTotals(lines);
            }
            this.currentCardNumber = cardNumber;
            this.currentCrossReference = lookupXref(cardNumber);
        }

        // app/cbl/CBTRN03C.cbl:L189-L190 - MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE, then the type lookup.
        TransactionType transactionType = lookupTranType(item);

        // app/cbl/CBTRN03C.cbl:L191-L195 - build FD-TRAN-CAT-KEY, then the category lookup.
        TransactionCategory transactionCategory = lookupTranCatg(item);

        // app/cbl/CBTRN03C.cbl:L196.
        writeTransactionReport(lines, item, transactionType, transactionCategory);

        return new ReportLines(lines);
    }

    /**
     * Produces the closing report lines, and reproduces two defects while doing so.
     *
     * <p>This is the {@code ELSE} branch of {@code app/cbl/CBTRN03C.cbl:L197-L203}, reached when
     * {@code 1000-TRANFILE-GET-NEXT} sets the end of file flag. Call it exactly once, after the last
     * record. In source order it displays the amount and the page total, adds the amount to the page and
     * account totals at {@code :L200-L201}, performs {@code 1110-WRITE-PAGE-TOTALS} at {@code :L202} and
     * {@code 1110-WRITE-GRAND-TOTALS} at {@code :L203}. Three lines: the page total, its underscore rule,
     * and the grand total.
     *
     * <p><strong>Reachability.</strong> This branch is genuinely reachable, and the reason is worth
     * recording because the same idiom elsewhere in the corpus is not. The {@code ELSE} at {@code :L197}
     * belongs to the <em>inner</em> {@code IF END-OF-FILE = 'N'} at {@code :L179}, which is evaluated
     * <em>after</em> the read inside the same iteration, so the iteration that discovers end of file takes
     * it. Contrast {@code app/cbl/CBACT04C.cbl:L219-L220}, where the {@code ELSE} belongs to the
     * <em>outer</em> {@code IF} at {@code :L189}: the loop condition at {@code :L188} has already exited
     * by then, so that branch is unreachable. Identical shape, opposite outcome, decided purely by which
     * {@code IF} owns the {@code ELSE}.
     *
     * <p><strong>Finding, High severity - the end of data double count. Preserved, not repaired.</strong>
     * COBOL's failing {@code READ} at {@code app/cbl/CBTRN03C.cbl:L249} leaves the previous record in
     * {@code TRAN-RECORD}, so {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} at {@code :L200-L201}
     * adds the <strong>last record's amount a second time</strong> - it was already added at
     * {@code :L287-L288} on the previous iteration. {@code 1110-WRITE-PAGE-TOTALS} then rolls that
     * inflated page total into the grand total at {@code :L297}. Two consequences follow, and both are
     * reproduced here: the closing page total and the grand total each include the last record twice, and
     * because no final {@code 1120-WRITE-ACCOUNT-TOTALS} is performed, <strong>the last card group never
     * gets an {@code Account Total} line at all</strong>. No flush is added and the addition is not
     * deduplicated. Remediation, for whoever later chooses parity break over parity: move the
     * {@code ADD} inside the record branch and perform a final account total block before the closing
     * page total. Do not apply it while Gate 1 compares against the legacy baseline. Destined for
     * {@code DECISION_LOG.md}.
     *
     * <p><strong>Finding, Medium severity - the uninitialised amount on an empty stream.</strong> If no
     * record was ever read, the source reaches {@code :L198-L201} with {@code TRAN-AMT} never assigned.
     * {@code TRAN-RECORD} comes from the copybook at {@code app/cbl/CBTRN03C.cbl:L93} with no
     * {@code VALUE} clause, so its content is whatever the region left there and the {@code ADD} is
     * undefined - in practice a data exception on a non numeric field. Undefined behaviour cannot be
     * reproduced, so it is <em>defined</em> here as a zero contribution: the closing totals are emitted
     * and read zero. Note what still holds - the header block is driven by the first record at
     * {@code :L275-L280}, so an empty stream yields these three closing lines and <strong>no
     * headers</strong>. Destined for {@code DECISION_LOG.md}.
     *
     * <p><strong>Side effects.</strong> Advances the page and account totals, the grand total and the line
     * counter by two. Performs no lookup and writes nothing to any file.
     *
     * @return the three closing report lines, each exactly {@value #REPORT_LINE_LENGTH} characters.
     * @throws FatalProcessingException if a formatted line does not match the record length.
     */
    public ReportLines finishReport() {
        List<String> lines = new ArrayList<>();

        // The stale record buffer. See the field documentation and the Medium finding above.
        BigDecimal staleAmount = this.lastAmount == null ? BigDecimal.ZERO : this.lastAmount;

        // app/cbl/CBTRN03C.cbl:L198 - DISPLAY 'TRAN-AMT ' TRAN-AMT. The literal carries its own trailing
        // space, so the value abuts it with exactly one space between.
        LOG.info("TRAN-AMT {}", staleAmount.toPlainString());

        // app/cbl/CBTRN03C.cbl:L199 - DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL. The two spaces in the source
        // separate the operands of the statement and are not emitted; the literal has no trailing space,
        // so the displayed line runs the value straight on. Reproduced exactly, with no space.
        LOG.info("WS-PAGE-TOTAL{}", this.pageTotal.toPlainString());

        // app/cbl/CBTRN03C.cbl:L200-L201 - the double count. Preserved.
        this.pageTotal = this.pageTotal.add(staleAmount);
        this.accountTotal = this.accountTotal.add(staleAmount);

        // app/cbl/CBTRN03C.cbl:L202.
        writePageTotals(lines);

        // app/cbl/CBTRN03C.cbl:L203. No 1120-WRITE-ACCOUNT-TOTALS follows, by design.
        writeGrandTotals(lines);

        return new ReportLines(lines);
    }

    /**
     * Returns the running line counter, {@code WS-LINE-COUNTER} of
     * {@code app/cbl/CBTRN03C.cbl:L129-L130}.
     *
     * <p>Exposed read only so a test can assert that the page break of {@code :L282} fires exactly when
     * the counter is a multiple of {@value #PAGE_SIZE}, and so the surrounding step can report progress
     * without holding a second counter of its own.
     *
     * @return the number of report lines emitted so far by the paragraphs that advance the counter, which
     * excludes the grand total line because {@code 1110-WRITE-GRAND-TOTALS} does not advance it.
     */
    public long lineCounter() {
        return this.lineCounter;
    }

    /**
     * Returns the current page total, {@code WS-PAGE-TOTAL} of {@code app/cbl/CBTRN03C.cbl:L134}.
     *
     * @return the sum of the detail amounts on the current page, reset to zero by every page total block.
     */
    public BigDecimal pageTotal() {
        return this.pageTotal;
    }

    /**
     * Returns the current account total, {@code WS-ACCOUNT-TOTAL} of {@code app/cbl/CBTRN03C.cbl:L135}.
     *
     * @return the sum of the detail amounts in the current card group, reset to zero by every account
     * total block.
     */
    public BigDecimal accountTotal() {
        return this.accountTotal;
    }

    /**
     * Returns the running grand total, {@code WS-GRAND-TOTAL} of {@code app/cbl/CBTRN03C.cbl:L136}.
     *
     * <p>Exposed read only because it is the value the High severity end of data finding is asserted
     * against: after a two record report it includes the second record's amount twice.
     *
     * @return the sum of the page totals rolled up at {@code app/cbl/CBTRN03C.cbl:L297}.
     */
    public BigDecimal grandTotal() {
        return this.grandTotal;
    }

    /**
     * Returns the control break key, {@code WS-CURR-CARD-NUM} of {@code app/cbl/CBTRN03C.cbl:L137}.
     *
     * @return the card number of the group being reported, space padded to
     * {@value #CARD_NUMBER_WIDTH} characters, or sixteen spaces before the first record.
     */
    public String currentCardNumber() {
        return this.currentCardNumber;
    }

    /**
     * {@code 0550-DATEPARM-READ}, {@code app/cbl/CBTRN03C.cbl:L220-L243}.
     *
     * <p>The source reads the {@code DATEPARM} control dataset into {@code WS-DATEPARM-RECORD} and
     * branches on the file status: {@code '00'} displays the period, {@code '10'} sets the end of file flag
     * so the report produces nothing, and anything else displays
     * {@code 'ERROR READING DATEPARM FILE'} and abends.
     *
     * <p><strong>Substitution.</strong> The dataset becomes two job parameters, so there is no read, no
     * file status and therefore no three way branch. What survives is the paragraph's observable effect:
     * the display of the period at {@code :L232-L233}. Its two failure outcomes are covered by the
     * constructor's validation instead - an absent parameter is the {@code '10'} case, which the source
     * turned into an empty report and which is reported here rather than silently producing one, and a
     * malformed parameter is the {@code OTHER} case, which abended there and abends here.
     *
     * <p>Declared {@code static} deliberately: it is called from the constructor, and a private static
     * method cannot be overridden, so no partially built instance can escape.
     *
     * @param start the validated inclusive start date, carrying {@code WS-START-DATE}.
     * @param end the validated inclusive end date, carrying {@code WS-END-DATE}.
     */
    private static void dateParmRead(String start, String end) {
        // app/cbl/CBTRN03C.cbl:L232-L233 - DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE.
        LOG.info("Reporting from {} to {}", logSafe(start), logSafe(end));
    }

    /**
     * The inclusive date re-filter of {@code app/cbl/CBTRN03C.cbl:L173-L178}.
     *
     * <p>The source reads:
     *
     * <pre>{@code
     * IF TRAN-PROC-TS (1:10) >= WS-START-DATE
     *    AND TRAN-PROC-TS (1:10) <= WS-END-DATE
     *    CONTINUE
     * ELSE
     *    NEXT SENTENCE
     * END-IF
     * }</pre>
     *
     * <p>Both bounds are inclusive, the compared value is the first ten characters of the
     * <strong>processing</strong> timestamp, and the comparison is an alphanumeric one on
     * {@code PIC X} operands - so it is a plain string comparison here, with neither side parsed into a
     * {@code java.time.LocalDate}. Parsing would change behaviour, not merely representation: it would
     * reject a value the source compares happily and would impose a calendar the source never consults.
     *
     * <p>The filter is applied even though {@code app/proc/TRANREPT.prc:L45-L46} has already applied it
     * upstream - {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
     * on the symbol {@code TRAN-PROC-DT,305,10,CH} declared at {@code :L40}. The source re-applies it, so
     * so does this class.
     *
     * <p><strong>Finding, High severity - divergence from {@code NEXT SENTENCE}. Documented, not
     * repaired.</strong> Under IBM Enterprise COBOL, {@code NEXT SENTENCE} transfers control past the
     * period that ends the current <em>sentence</em>. The enclosing sentence here is the inline
     * {@code PERFORM UNTIL … END-PERFORM.} whose period is at {@code app/cbl/CBTRN03C.cbl:L206}, so the
     * out of range branch does not skip the record - it <strong>terminates the entire read loop</strong>
     * and jumps to the close paragraphs at {@code :L208}, abandoning every remaining record and the end of
     * data block with it. In the sanctioned job stream the branch is effectively unreachable, because
     * DFSORT has already removed every out of range record before {@code CBTRN03C} ever sees one.
     *
     * <p>This class returns {@code null} instead, Spring Batch's sanctioned skip signal, so an out of
     * range record is filtered and processing continues. The divergence is deliberate and is the option
     * the plan directs, on the ground that a chunk oriented step has no equivalent of "abandon the reader
     * mid stream and still run the closing paragraphs", and that silently truncating a report is a worse
     * failure than filtering a record the upstream sort should already have removed. Remediation, if
     * literal fidelity is ever required: signal the step to stop by throwing from the reader rather than
     * filtering here, and accept that the closing totals are then not emitted. Destined for
     * {@code DECISION_LOG.md}.
     *
     * <p>The length guard is clause A2 and B2 work, not source behaviour: {@code TRAN-PROC-TS} is
     * {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17} and a fixed width field is always at least ten
     * characters, but a value arriving through JPA from a column that was trimmed, or a genuinely blank
     * timestamp, is shorter. A shorter value cannot satisfy an inclusive range on a ten character key, so
     * it is filtered rather than allowed to raise an index error.
     *
     * @param item the transaction being tested, already known to be non-{@code null}.
     * @return {@code true} when the processing date lies within the inclusive period and the record must
     * be reported, {@code false} when the record must be filtered.
     */
    private boolean withinReportingPeriod(Transaction item) {
        String procTs = item.getProcTs();
        if (procTs == null || procTs.length() < PROC_DATE_LENGTH) {
            LOG.debug("Filtering transaction whose TRAN-PROC-TS is shorter than {} characters and "
                    + "therefore cannot satisfy the inclusive reporting period", PROC_DATE_LENGTH);
            return false;
        }
        String procDate = procTs.substring(0, PROC_DATE_LENGTH);
        return procDate.compareTo(this.startDate) >= 0 && procDate.compareTo(this.endDate) <= 0;
    }

    /**
     * {@code DISPLAY TRAN-RECORD}, {@code app/cbl/CBTRN03C.cbl:L180}.
     *
     * <p>The source writes the whole 350 byte record image to SYSOUT for every reported transaction.
     *
     * <p><strong>Finding, Low severity - the card number is masked.</strong> The record image contains
     * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}, so an unaltered reproduction
     * would put a full card number in the application log on every record. Clause D1 forbids that
     * outright, and it governs: the fields are rendered individually with the card number masked, at debug
     * rather than info so a production profile does not carry them at all. The report <em>body</em> keeps
     * whatever the layout requires - that is the deliverable, not a log - and no card number is written to
     * the log by any path in this class. Destined for {@code DECISION_LOG.md}.
     *
     * @param item the transaction being reported, already known to be non-{@code null}.
     */
    private static void displayTranRecord(Transaction item) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        LOG.debug("TRAN-RECORD id={} type={} category={} source={} card={} procTs={} amount={}",
                logSafe(item.getTransactionId()),
                logSafe(item.getTypeCode()),
                item.getCategoryCode(),
                logSafe(item.getTransactionSource()),
                maskCardNumber(item.getCardNumber()),
                logSafe(item.getProcTs()),
                item.getAmount() == null ? null : item.getAmount().toPlainString());
    }

    /**
     * {@code 1100-WRITE-TRANSACTION-REPORT}, {@code app/cbl/CBTRN03C.cbl:L274-L290}.
     *
     * <p>Three things in strict source order.
     *
     * <ol>
     *   <li>{@code :L275-L280} - on the first record only: clear {@code WS-FIRST-TIME}, move the reporting
     *       period into the two header date fields, and perform {@code 1120-WRITE-HEADERS}. Because the
     *       flag is cleared <em>here</em> and the control break at {@code :L182} reads it
     *       <em>earlier</em>, the first record can never produce an account total line.</li>
     *   <li>{@code :L282-L285} - the page break: {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}
     *       performs {@code 1110-WRITE-PAGE-TOTALS} and then {@code 1120-WRITE-HEADERS}. This is a
     *       <strong>modulus of the running line counter</strong>, not a count of detail lines, so it fires
     *       whenever the counter happens to be a multiple of {@value #PAGE_SIZE} at this point - which is
     *       why the counter must be advanced exactly where the source advances it and nowhere else. The
     *       first record cannot trigger it: the header block has just taken the counter to four.</li>
     *   <li>{@code :L287-L289} - accumulate {@code TRAN-AMT} into the page and account totals, then
     *       perform {@code 1120-WRITE-DETAIL}.</li>
     * </ol>
     *
     * @param lines the accumulating line list for this record, appended to in emission order.
     * @param item the transaction being reported.
     * @param transactionType the type row already found by {@code 1500-B-LOOKUP-TRANTYPE}.
     * @param transactionCategory the category row already found by {@code 1500-C-LOOKUP-TRANCATG}.
     */
    private void writeTransactionReport(List<String> lines, Transaction item,
            TransactionType transactionType, TransactionCategory transactionCategory) {
        // app/cbl/CBTRN03C.cbl:L275-L280.
        if (this.firstTime) {
            this.firstTime = false;
            this.reportStartDate = fixedWidth(this.startDate, REPT_DATE_WIDTH);
            this.reportEndDate = fixedWidth(this.endDate, REPT_DATE_WIDTH);
            writeHeaders(lines);
        }

        // app/cbl/CBTRN03C.cbl:L282-L285.
        if (this.lineCounter % PAGE_SIZE == 0) {
            writePageTotals(lines);
            writeHeaders(lines);
        }

        // app/cbl/CBTRN03C.cbl:L287-L288.
        BigDecimal amount = requireAmount(item);
        this.pageTotal = this.pageTotal.add(amount);
        this.accountTotal = this.accountTotal.add(amount);

        // app/cbl/CBTRN03C.cbl:L289.
        writeDetail(lines, item, transactionType, transactionCategory);
    }

    /**
     * {@code 1110-WRITE-PAGE-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L293-L304}.
     *
     * <p>Two lines and, uniquely in this program, the grand total rollup.
     *
     * <ol>
     *   <li>{@code :L294-L296} - move the page total into {@code REPT-PAGE-TOTAL}, move
     *       {@code REPORT-PAGE-TOTALS} into the output record and write it. The move at {@code :L294}
     *       precedes the rollup at {@code :L297} and the reset at {@code :L298}, so the printed figure is
     *       the page's own total.</li>
     *   <li>{@code :L297} - {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL}. <strong>This is the only
     *       statement in the program that feeds the grand total.</strong> The grand total is therefore a
     *       sum of page totals, not an independent sum of detail amounts, and
     *       {@code 1120-WRITE-ACCOUNT-TOTALS} contributes nothing to it.</li>
     *   <li>{@code :L298-L299} - reset the page total to zero, advance the counter.</li>
     *   <li>{@code :L300-L302} - write {@code TRANSACTION-HEADER-2}, the underscore rule, and advance the
     *       counter again.</li>
     * </ol>
     *
     * <p>Net counter effect: <strong>plus two</strong>.
     *
     * <p>This is one of the two paragraphs whose label begins {@code 1110-}; the other is
     * {@link #writeGrandTotals(List)} at {@code app/cbl/CBTRN03C.cbl:L318}. They are separate paragraphs
     * that happen to share a numeric prefix, and they are kept as separate methods: they emit different
     * layouts, different line counts and different counter effects, and merging them on the strength of
     * the prefix would break the paragraph map the coverage gate verifies.
     *
     * @param lines the accumulating line list, appended to in emission order.
     */
    private void writePageTotals(List<String> lines) {
        // app/cbl/CBTRN03C.cbl:L294-L296.
        writeReportRec(lines, reportPageTotalsLine(this.pageTotal));

        // app/cbl/CBTRN03C.cbl:L297 - the only grand total rollup in the program.
        this.grandTotal = this.grandTotal.add(this.pageTotal);

        // app/cbl/CBTRN03C.cbl:L298-L299.
        this.pageTotal = BigDecimal.ZERO;
        this.lineCounter++;

        // app/cbl/CBTRN03C.cbl:L300-L302.
        writeReportRec(lines, transactionHeader2Line());
        this.lineCounter++;
    }

    /**
     * {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L306-L316}.
     *
     * <p>Two lines, mirroring the page total block but <strong>without</strong> any rollup.
     *
     * <ol>
     *   <li>{@code :L307-L309} - move the account total into {@code REPT-ACCOUNT-TOTAL}, move
     *       {@code REPORT-ACCOUNT-TOTALS} into the output record and write it.</li>
     *   <li>{@code :L310-L311} - reset the account total to zero, advance the counter.</li>
     *   <li>{@code :L312-L314} - write the underscore rule, advance the counter again.</li>
     * </ol>
     *
     * <p>Net counter effect: <strong>plus two</strong>. There is deliberately no
     * {@code ADD WS-ACCOUNT-TOTAL TO WS-GRAND-TOTAL}: the grand total is fed only by
     * {@code app/cbl/CBTRN03C.cbl:L297}, and adding a rollup here would double the report's grand total.
     *
     * <p><strong>Finding, Low severity - the label does not describe the break key. Preserved, not
     * repaired.</strong> The control break at {@code app/cbl/CBTRN03C.cbl:L181} tests
     * {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}, so the group being closed is a
     * <strong>card number</strong> group. The layout it emits is {@code REPORT-ACCOUNT-TOTALS} at
     * {@code app/cpy/CVTRA07Y.cpy:L56-L60}, whose field is {@code REPT-ACCOUNT-TOTAL} and whose printed
     * literal is {@code 'Account Total'}. Since one account can hold several cards - the cross reference
     * is keyed by card and carries the account, {@code app/cpy/CVACT03Y.cpy:L5-L7} - a card group is not
     * an account group, and a multi card account produces one "Account Total" line per card. Both halves
     * are preserved: the break stays on the card number and the label still reads {@code Account Total}.
     * Remediation, should the label ever be corrected: change the literal only, never the break key, since
     * the break key determines which records are grouped. Destined for {@code DECISION_LOG.md}.
     *
     * <p>This is one of the three paragraphs whose label begins {@code 1120-}; the others are
     * {@link #writeHeaders(List)} at {@code :L324} and
     * {@link #writeDetail(List, Transaction, TransactionType, TransactionCategory)} at {@code :L361}. All
     * three are kept separate for the reason given on {@link #writePageTotals(List)}.
     *
     * @param lines the accumulating line list, appended to in emission order.
     */
    private void writeAccountTotals(List<String> lines) {
        // app/cbl/CBTRN03C.cbl:L307-L309.
        writeReportRec(lines, reportAccountTotalsLine(this.accountTotal));

        // app/cbl/CBTRN03C.cbl:L310-L311.
        this.accountTotal = BigDecimal.ZERO;
        this.lineCounter++;

        // app/cbl/CBTRN03C.cbl:L312-L314.
        writeReportRec(lines, transactionHeader2Line());
        this.lineCounter++;
    }

    /**
     * {@code 1110-WRITE-GRAND-TOTALS}, {@code app/cbl/CBTRN03C.cbl:L318-L322}.
     *
     * <p>One line, and <strong>no counter advance</strong>. The paragraph is three statements -
     * {@code :L319} moves the grand total into {@code REPT-GRAND-TOTAL}, {@code :L320} moves
     * {@code REPORT-GRAND-TOTALS} into the output record, {@code :L321} performs
     * {@code 1111-WRITE-REPORT-REC} - and then exits. Neither an {@code ADD 1 TO WS-LINE-COUNTER} nor an
     * underscore rule follows, unlike the other two total blocks.
     *
     * <p>Omitting the counter advance is harmless in the sanctioned flow because the paragraph is performed
     * once, at {@code :L203}, immediately before the program closes its files - so nothing reads the
     * counter afterwards. It is reproduced anyway: the counter is observable through
     * {@link #lineCounter()}, and inventing an advance the source does not make would put this class out of
     * step with the paragraph map.
     *
     * <p>Second of the two {@code 1110-} labelled paragraphs; see {@link #writePageTotals(List)}.
     *
     * @param lines the accumulating line list, appended to in emission order.
     */
    private void writeGrandTotals(List<String> lines) {
        // app/cbl/CBTRN03C.cbl:L319-L321. No counter advance follows, by design.
        writeReportRec(lines, reportGrandTotalsLine(this.grandTotal));
    }

    /**
     * {@code 1120-WRITE-HEADERS}, {@code app/cbl/CBTRN03C.cbl:L324-L341}.
     *
     * <p>Four lines, each followed by its own {@code ADD 1 TO WS-LINE-COUNTER}, so the net counter effect
     * is <strong>plus four</strong>:
     *
     * <ol>
     *   <li>{@code :L325-L327} - {@code REPORT-NAME-HEADER}, the report name and the date range.</li>
     *   <li>{@code :L329-L331} - {@code WS-BLANK-LINE}, 133 spaces from
     *       {@code app/cbl/CBTRN03C.cbl:L133}.</li>
     *   <li>{@code :L333-L335} - {@code TRANSACTION-HEADER-1}, the column headings.</li>
     *   <li>{@code :L337-L339} - {@code TRANSACTION-HEADER-2}, the underscore rule.</li>
     * </ol>
     *
     * <p>Performed from two places: the first record block at {@code :L279} and the page break at
     * {@code :L284}. Both reach the same four lines, and the date fields they carry were set once at
     * {@code :L277-L278}.
     *
     * <p>Second of the three {@code 1120-} labelled paragraphs; see {@link #writeAccountTotals(List)}.
     *
     * @param lines the accumulating line list, appended to in emission order.
     */
    private void writeHeaders(List<String> lines) {
        // app/cbl/CBTRN03C.cbl:L325-L327.
        writeReportRec(lines, reportNameHeaderLine());
        this.lineCounter++;

        // app/cbl/CBTRN03C.cbl:L329-L331.
        writeReportRec(lines, blankLine());
        this.lineCounter++;

        // app/cbl/CBTRN03C.cbl:L333-L335.
        writeReportRec(lines, transactionHeader1Line());
        this.lineCounter++;

        // app/cbl/CBTRN03C.cbl:L337-L339.
        writeReportRec(lines, transactionHeader2Line());
        this.lineCounter++;
    }

    /**
     * {@code 1111-WRITE-REPORT-REC}, {@code app/cbl/CBTRN03C.cbl:L343-L359}.
     *
     * <p>The source performs {@code WRITE FD-REPTFILE-REC} at {@code :L345} and then applies the universal
     * guard: {@code IF TRANREPT-STATUS = '00'} at {@code :L346} accepts the write and anything else
     * displays {@code 'ERROR WRITING REPTFILE'} at {@code :L354}, renders the status through
     * {@code 9910-DISPLAY-IO-STATUS} and abends. Only {@code '00'} is accepted - there is no secondary
     * success status on this path, unlike the {@code '04'} the statement file service tolerates.
     *
     * <p><strong>Substitution.</strong> The write itself belongs to the step's {@code ItemWriter}, so what
     * this method reproduces is the guard's <em>precondition</em>: a record that is not exactly
     * {@value #REPORT_LINE_LENGTH} characters is not a record the fixed block report file can hold, and
     * handing one to the writer is the Java equivalent of the failed {@code WRITE} the guard exists to
     * catch. Checking here rather than at the writer keeps the diagnosis next to the layout that produced
     * the line. {@link ReportLines} repeats the check over the assembled list, so a line cannot reach a
     * caller unverified even if this method is bypassed.
     *
     * @param lines the accumulating line list, appended to.
     * @param line the formatted report record, expected to be exactly {@value #REPORT_LINE_LENGTH}
     * characters.
     * @throws FatalProcessingException if the line is {@code null} or of the wrong length, carrying the
     * {@code 'ERROR WRITING REPTFILE'} text of {@code app/cbl/CBTRN03C.cbl:L354}.
     */
    private void writeReportRec(List<String> lines, String line) {
        if (line == null || line.length() != REPORT_LINE_LENGTH) {
            throw abendProgram(String.format(Locale.ROOT,
                    "a report record of %s characters cannot be written to a fixed block file of "
                            + "record length %d",
                    line == null ? "no" : Integer.toString(line.length()), REPORT_LINE_LENGTH),
                    ERROR_WRITING_REPTFILE_TEXT);
        }
        lines.add(line);
    }

    /**
     * {@code 1120-WRITE-DETAIL}, {@code app/cbl/CBTRN03C.cbl:L361-L374}.
     *
     * <p>{@code :L362} performs {@code INITIALIZE TRANSACTION-DETAIL-REPORT}, then {@code :L363-L370} move
     * eight values in, {@code :L371} moves the group into the output record, {@code :L372} writes it and
     * {@code :L373} advances the counter by one.
     *
     * <p><strong>The {@code INITIALIZE} matters.</strong> It sets alphanumeric elementary items to spaces
     * and numeric ones to zero but <strong>leaves {@code FILLER} items untouched</strong>, so the two
     * {@code FILLER PIC X(01) VALUE '-'} separators at {@code app/cpy/CVTRA07Y.cpy:L21} and {@code :L25}
     * survive it. That is why a detail line reads {@code nn-description} after the type code and
     * {@code nnnn-description} after the category code rather than carrying spaces there. Assembling the
     * line from its declared pieces reproduces this directly.
     *
     * <p><strong>Finding, Medium severity - two truncating moves. Reproduced, not corrected.</strong> A
     * COBOL alphanumeric {@code MOVE} is left justified and truncates on the right, and two of the eight
     * moves send a wider field into a narrower one:
     *
     * <ul>
     *   <li>{@code :L366} moves {@code TRAN-TYPE-DESC PIC X(50)}
     *       ({@code app/cpy/CVTRA03Y.cpy:L6}) into {@code TRAN-REPORT-TYPE-DESC PIC X(15)}
     *       ({@code app/cpy/CVTRA07Y.cpy:L22}), losing 35 characters.</li>
     *   <li>{@code :L368} moves {@code TRAN-CAT-TYPE-DESC PIC X(50)}
     *       ({@code app/cpy/CVTRA04Y.cpy:L8}) into {@code TRAN-REPORT-CAT-DESC PIC X(29)}
     *       ({@code app/cpy/CVTRA07Y.cpy:L26}), losing 21 characters.</li>
     * </ul>
     *
     * <p>Neither is widened and neither is elided with an ellipsis: the column widths are fixed by the
     * 133 byte layout, so any other choice would move every field to its right. Remediation, if the
     * descriptions ever need to be complete: widen the report line, which is a change to the record
     * length and therefore to the DD statement at {@code app/proc/TRANREPT.prc:L76}. Destined for
     * {@code DECISION_LOG.md}.
     *
     * <p>Two further moves change representation rather than width. {@code :L364} moves
     * {@code XREF-ACCT-ID PIC 9(11)} into {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, which renders eleven
     * zero padded digits, and {@code :L367} moves {@code TRAN-CAT-CD PIC 9(04)} into
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)}, four zero padded digits - so category 1 prints as
     * {@code 0001}, not as {@code 1} or {@code "   1"}.
     *
     * <p>Third of the three {@code 1120-} labelled paragraphs; see {@link #writeAccountTotals(List)}.
     *
     * @param lines the accumulating line list, appended to.
     * @param item the transaction supplying six of the eight moved values.
     * @param transactionType the type row supplying {@code TRAN-TYPE-DESC}.
     * @param transactionCategory the category row supplying {@code TRAN-CAT-TYPE-DESC}.
     */
    private void writeDetail(List<String> lines, Transaction item, TransactionType transactionType,
            TransactionCategory transactionCategory) {
        // app/cbl/CBTRN03C.cbl:L362-L371 - assembled in declaration order from app/cpy/CVTRA07Y.cpy:L15-L31.
        StringBuilder detail = new StringBuilder(REPORT_LINE_LENGTH);

        // :L363 - MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID.
        detail.append(fixedWidth(item.getTransactionId(), DETAIL_TRANS_ID_WIDTH));
        detail.append(DETAIL_SPACE_FILLER);

        // :L364 - MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID, eleven zero padded digits.
        detail.append(unsignedDisplayDigits(requireAccountId(), DETAIL_ACCOUNT_ID_WIDTH,
                "XREF-ACCT-ID"));
        detail.append(DETAIL_SPACE_FILLER);

        // :L365 - MOVE TRAN-TYPE-CD TO TRAN-REPORT-TYPE-CD.
        detail.append(fixedWidth(item.getTypeCode(), DETAIL_TYPE_CODE_WIDTH));

        // app/cpy/CVTRA07Y.cpy:L21 - FILLER PIC X(01) VALUE '-', preserved through the INITIALIZE.
        detail.append(DETAIL_HYPHEN_FILLER);

        // :L366 - MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC, truncating 50 characters to 15.
        detail.append(fixedWidth(transactionType.getTypeDescription(), DETAIL_TYPE_DESC_WIDTH));
        detail.append(DETAIL_SPACE_FILLER);

        // :L367 - MOVE TRAN-CAT-CD TO TRAN-REPORT-CAT-CD, four zero padded digits.
        detail.append(unsignedDisplayDigits(requireCategoryCode(item), DETAIL_CATEGORY_CODE_WIDTH,
                "TRAN-CAT-CD"));

        // app/cpy/CVTRA07Y.cpy:L25 - the second FILLER PIC X(01) VALUE '-'.
        detail.append(DETAIL_HYPHEN_FILLER);

        // :L368 - MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC, truncating 50 characters to 29.
        detail.append(fixedWidth(transactionCategory.getCategoryDescription(), DETAIL_CATEGORY_DESC_WIDTH));
        detail.append(DETAIL_SPACE_FILLER);

        // :L369 - MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE.
        detail.append(fixedWidth(item.getTransactionSource(), DETAIL_SOURCE_WIDTH));
        detail.append(DETAIL_AMOUNT_PREFIX_FILLER);

        // :L370 - MOVE TRAN-AMT TO TRAN-REPORT-AMT, the -ZZZ,ZZZ,ZZZ.ZZ mask.
        detail.append(formatDetailAmount(requireAmount(item)));
        detail.append(DETAIL_TRAILING_FILLER);

        // :L371-L372.
        writeReportRec(lines, fixedWidth(detail.toString(), REPORT_LINE_LENGTH));

        // :L373.
        this.lineCounter++;
    }

    /**
     * {@code 1500-A-LOOKUP-XREF}, {@code app/cbl/CBTRN03C.cbl:L484-L492}.
     *
     * <p>{@code READ XREF-FILE INTO CARD-XREF-RECORD} on the key {@code FD-XREF-CARD-NUM} that
     * {@code :L186} has just moved the card number into. On {@code INVALID KEY} the source displays
     * {@code 'INVALID CARD NUMBER : '} with the key at {@code :L487}, moves {@code 23} into
     * {@code IO-STATUS} at {@code :L488}, renders it through {@code 9910-DISPLAY-IO-STATUS} at {@code :L489}
     * and performs {@code 9999-ABEND-PROGRAM} at {@code :L490}.
     *
     * <p><strong>A missing cross reference therefore abends the job.</strong> It is not a skip, not a
     * filtered item and not a {@code com.cardemo.exception.RecordNotFoundException}: that type is the
     * general translation of file status {@code '23'} for code that returns the status to a caller, whereas
     * this paragraph abends on it, so the faithful target is the fatal type carrying abend 999 and return
     * code 12. Softening it would produce a report with silently missing rows.
     *
     * <p>The read is a primary key read. The cross reference key is
     * {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}, mapped to a
     * {@code CHAR(16)} column, so the space padded sixteen character key is passed exactly as the fixed
     * width VSAM key would have been.
     *
     * @param cardNumber the control break key, already space padded to {@value #CARD_NUMBER_WIDTH}
     * characters.
     * @return the cross reference row, never {@code null}.
     * @throws FatalProcessingException if no row carries that card number.
     */
    private CardCrossReference lookupXref(String cardNumber) {
        Optional<CardCrossReference> found;
        try {
            found = this.cardCrossReferenceRepository.findById(cardNumber);
        } catch (RuntimeException cause) {
            // The physical failure app/cbl/CBTRN03C.cbl:L485-L491 leaves unexamined. Never swallowed here;
            // see abendProgram(String, String, Throwable) for the finding and the reasoning.
            throw abendProgram("the CARDXREF read failed physically, and app/cbl/CBTRN03C.cbl:L485-L491 "
                    + "guards only INVALID KEY, so the source would have continued from a stale buffer",
                    INVALID_CARD_NUMBER_TEXT.strip(), cause);
        }
        if (found.isEmpty()) {
            // app/cbl/CBTRN03C.cbl:L487 - the key is masked here; see maskCardNumber.
            LOG.error("{}{}", INVALID_CARD_NUMBER_TEXT, maskCardNumber(cardNumber));
            // app/cbl/CBTRN03C.cbl:L488-L489.
            LOG.error(this.fileStatusMapper.displayIoStatus(LOOKUP_FAILURE_IO_STATUS));
            // app/cbl/CBTRN03C.cbl:L490.
            throw abendProgram("CARDXREF holds no row for the card number of the current control break "
                    + "group, and the report cannot resolve its account identifier",
                    INVALID_CARD_NUMBER_TEXT.strip());
        }
        return found.get();
    }

    /**
     * {@code 1500-B-LOOKUP-TRANTYPE}, {@code app/cbl/CBTRN03C.cbl:L494-L502}.
     *
     * <p>{@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} on the key {@code FD-TRAN-TYPE} that
     * {@code :L189} has just moved {@code TRAN-TYPE-CD} into. On {@code INVALID KEY} the source displays
     * {@code 'INVALID TRANSACTION TYPE : '} at {@code :L497} and abends by the same three step sequence as
     * {@link #lookupXref(String)}.
     *
     * <p>Performed for <strong>every</strong> record, not only on a control break - the source repeats the
     * read at {@code :L190} on each iteration - so no caching is introduced here. Adding one would change
     * the number of reads the step issues and would mask a reference table modified mid run.
     *
     * <p>The key is {@code TRAN-TYPE PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy:L5}, mapped to a
     * {@code CHAR(2)} column, so the type code is passed padded to its declared two characters.
     *
     * @param item the transaction supplying {@code TRAN-TYPE-CD}.
     * @return the transaction type row, never {@code null}.
     * @throws FatalProcessingException if no row carries that type code.
     */
    private TransactionType lookupTranType(Transaction item) {
        // app/cbl/CBTRN03C.cbl:L189 - MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE.
        String typeCode = fixedWidth(item.getTypeCode(), DETAIL_TYPE_CODE_WIDTH);
        Optional<TransactionType> found;
        try {
            found = this.transactionTypeRepository.findById(typeCode);
        } catch (RuntimeException cause) {
            // The physical failure app/cbl/CBTRN03C.cbl:L495-L501 leaves unexamined.
            throw abendProgram("the TRANTYPE read failed physically, and app/cbl/CBTRN03C.cbl:L495-L501 "
                    + "guards only INVALID KEY, so the source would have continued from a stale buffer",
                    INVALID_TRANSACTION_TYPE_TEXT.strip(), cause);
        }
        if (found.isEmpty()) {
            // app/cbl/CBTRN03C.cbl:L497.
            LOG.error("{}{}", INVALID_TRANSACTION_TYPE_TEXT, logSafe(typeCode));
            // app/cbl/CBTRN03C.cbl:L498-L499.
            LOG.error(this.fileStatusMapper.displayIoStatus(LOOKUP_FAILURE_IO_STATUS));
            // app/cbl/CBTRN03C.cbl:L500.
            throw abendProgram(String.format(Locale.ROOT,
                    "TRANTYPE holds no row for transaction type code '%s'", logSafe(typeCode)),
                    INVALID_TRANSACTION_TYPE_TEXT.strip());
        }
        return found.get();
    }

    /**
     * {@code 1500-C-LOOKUP-TRANCATG}, {@code app/cbl/CBTRN03C.cbl:L504-L512}.
     *
     * <p>{@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} on the composite key {@code FD-TRAN-CAT-KEY} that
     * {@code :L191-L194} has just assembled. On {@code INVALID KEY} the source displays
     * {@code 'INVALID TRAN CATG KEY : '} at {@code :L507} and abends.
     *
     * <p>The key is built in <strong>copybook field order</strong>: {@code TRAN-TYPE-CD PIC X(02)} then
     * {@code TRAN-CAT-CD PIC 9(04)}, six bytes in total, from {@code app/cpy/CVTRA04Y.cpy:L5-L7} and
     * mirrored by the file definition at {@code app/cbl/CBTRN03C.cbl:L79-L81}. The catalogued key length
     * confirms it: {@code app/catlg/LISTCAT.txt} reports {@code KEYLEN 6} for the {@code TRANCATG} cluster.
     * {@link TransactionCategoryId} takes its two components in that same order, so the constructor call
     * reads as the key does.
     *
     * <p>Performed for every record, for the reason given on {@link #lookupTranType(Transaction)}.
     *
     * @param item the transaction supplying both key components.
     * @return the transaction category row, never {@code null}.
     * @throws FatalProcessingException if the category code is absent or no row carries that composite key.
     */
    private TransactionCategory lookupTranCatg(Transaction item) {
        // app/cbl/CBTRN03C.cbl:L191-L194 - the two component moves, in copybook field order.
        String typeCode = fixedWidth(item.getTypeCode(), DETAIL_TYPE_CODE_WIDTH);
        Integer categoryCode = item.getCategoryCode();
        if (categoryCode == null) {
            throw abendProgram("TRAN-CAT-CD is absent, so the six byte TRANCATG key cannot be assembled",
                    INVALID_TRAN_CATG_KEY_TEXT.strip());
        }
        TransactionCategoryId key = new TransactionCategoryId(typeCode, categoryCode);

        // app/cbl/CBTRN03C.cbl:L195.
        Optional<TransactionCategory> found;
        try {
            found = this.transactionCategoryRepository.findById(key);
        } catch (RuntimeException cause) {
            // The physical failure app/cbl/CBTRN03C.cbl:L505-L511 leaves unexamined.
            throw abendProgram("the TRANCATG read failed physically, and app/cbl/CBTRN03C.cbl:L505-L511 "
                    + "guards only INVALID KEY, so the source would have continued from a stale buffer",
                    INVALID_TRAN_CATG_KEY_TEXT.strip(), cause);
        }
        if (found.isEmpty()) {
            // app/cbl/CBTRN03C.cbl:L507 - the key renders as the six byte group it is.
            LOG.error("{}{}{}", INVALID_TRAN_CATG_KEY_TEXT, logSafe(typeCode),
                    unsignedDisplayDigits(categoryCode.longValue(), DETAIL_CATEGORY_CODE_WIDTH,
                            "TRAN-CAT-CD"));
            // app/cbl/CBTRN03C.cbl:L508-L509.
            LOG.error(this.fileStatusMapper.displayIoStatus(LOOKUP_FAILURE_IO_STATUS));
            // app/cbl/CBTRN03C.cbl:L510.
            throw abendProgram(String.format(Locale.ROOT,
                    "TRANCATG holds no row for the composite key type '%s' category %d",
                    logSafe(typeCode), categoryCode),
                    INVALID_TRAN_CATG_KEY_TEXT.strip());
        }
        return found.get();
    }

    /**
     * {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBTRN03C.cbl:L626-L630}.
     *
     * <p>The source displays {@code 'ABENDING PROGRAM'} at {@code :L627}, zeroes {@code TIMING} at
     * {@code :L628}, moves {@code 999} into {@code ABCODE} at {@code :L629} and calls the Language
     * Environment abend service {@code CEE3ABD} at {@code :L630}, which terminates the step.
     *
     * <p>The Java equivalent is {@link FatalProcessingException}, which already carries abend code
     * {@value FatalProcessingException#BATCH_ABEND_CODE} and return code
     * {@value FatalProcessingException#BATCH_RETURN_CODE} as its own constants - neither is redeclared
     * here. The exception is <em>returned</em> rather than thrown so a call site can write
     * {@code throw abendProgram(…)} and remain visibly terminal to both a reader and the compiler.
     *
     * <p>{@code TIMING} has no counterpart: it is a Language Environment feedback field, and there is no
     * abend service to feed it.
     *
     * @param reason the condition that forced the abend, becoming {@code ABEND-REASON}; it must name the
     * file or field involved and must never carry a card number.
     * @param abendMessage the operator facing text, becoming {@code ABEND-MSG}; the {@code DISPLAY}
     * literal of the paragraph that detected the condition.
     * @return the exception to throw, never {@code null}.
     */
    private static FatalProcessingException abendProgram(String reason, String abendMessage) {
        // app/cbl/CBTRN03C.cbl:L627.
        LOG.error("{} - {}", ABENDING_PROGRAM_TEXT, reason);
        // app/cbl/CBTRN03C.cbl:L629-L630 - abend code 999, return code 12, both owned by the exception.
        return new FatalProcessingException(abendCodeDisplay(), ABEND_CULPRIT, reason, abendMessage);
    }

    /**
     * {@code 9999-ABEND-PROGRAM} for an infrastructure failure, preserving the root cause.
     *
     * <p><strong>Finding, High severity - a source path that swallows a failure, deliberately NOT
     * reproduced.</strong> Every {@code SELECT} declares a status field -
     * {@code CARDXREF-STATUS} at {@code app/cbl/CBTRN03C.cbl:L37}, {@code TRANTYPE-STATUS} at
     * {@code :L43}, {@code TRANCATG-STATUS} at {@code :L49} - so a physical failure sets a status rather
     * than raising a Language Environment abend. But the three lookup paragraphs at {@code :L485-L491},
     * {@code :L495-L501} and {@code :L505-L511} guard <strong>only</strong> {@code INVALID KEY}, which
     * fires for the {@code '2x'} class and <em>not</em> for the {@code '9x'} physical error family nor for
     * {@code '35'}. Neither paragraph ever examines its status field, and no guard follows the
     * {@code END-READ}. The consequence in the source is that a physical read failure is
     * <strong>silently ignored</strong>, the record area still holds the <em>previous</em> iteration's
     * contents, and the report continues to be written from stale data with no diagnostic whatsoever.
     *
     * <p>That path is not reproduced, and the departure is deliberate rather than incidental. Clause B4
     * forbids swallowing an exception outright; AAP transformation rule 12 requires a typed exception on
     * <strong>every</strong> I/O path; and a report silently built from a stale buffer is precisely the
     * class of corruption the parity gates exist to detect rather than to enshrine. Abending is the safe
     * direction, and unlike the source it cannot produce a plausible-looking wrong report. Destined for
     * {@code DECISION_LOG.md} as a labelled deviation, not as parity.
     *
     * <p><strong>Why the fatal type and not a file-status translation.</strong> The authoritative status
     * map ends with "anything else - fatal, abend 999, return code 12", and a persistence failure here
     * has <em>no</em> COBOL file status at all, precisely because these paragraphs never read one. Naming
     * a {@code '9x'} subcode in order to feed {@link FileStatusMapper} would put a fabricated status into
     * the log, which clause F1 forbids. Remediation, if a future data layer ever surfaces a genuine
     * status: call {@code fileStatusMapper.requireSuccess(status, logicalFile, operation, cause)}, which
     * owns the {@code '9x'} translation, instead of calling this method.
     *
     * @param reason the condition that forced the abend, becoming {@code ABEND-REASON}.
     * @param abendMessage the operator facing text, becoming {@code ABEND-MSG}.
     * @param cause the persistence failure, retained so the stack trace reaches the real fault.
     * @return the exception to throw, never {@code null}.
     */
    private static FatalProcessingException abendProgram(String reason, String abendMessage,
            Throwable cause) {
        // app/cbl/CBTRN03C.cbl:L627.
        LOG.error("{} - {}", ABENDING_PROGRAM_TEXT, reason);
        return new FatalProcessingException(abendCodeDisplay(), ABEND_CULPRIT, reason, abendMessage,
                cause);
    }

    /**
     * Renders the abend code as the four display characters {@code ABEND-CODE PIC X(4)} holds.
     *
     * <p>{@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN03C.cbl:L629} supplies the value and
     * {@link FatalProcessingException#BATCH_ABEND_CODE} already declares it; the width comes from
     * {@link FileStatusMapper#ABEND_CODE_WIDTH}. Both are reused rather than restated, so the value
     * appears exactly once in the tree.
     *
     * @return the zero padded four character abend code.
     */
    private static String abendCodeDisplay() {
        return String.format(Locale.ROOT, "%0" + FileStatusMapper.ABEND_CODE_WIDTH + "d",
                FatalProcessingException.BATCH_ABEND_CODE);
    }

    /**
     * Builds {@code REPORT-NAME-HEADER}, {@code app/cpy/CVTRA07Y.cpy:L4-L13}.
     *
     * <p>Six fields totalling {@code 38 + 41 + 12 + 10 + 4 + 10 = 115} characters of content, then space
     * padded to the {@value #REPORT_LINE_LENGTH} character record by the
     * {@code MOVE … TO FD-REPTFILE-REC} of {@code app/cbl/CBTRN03C.cbl:L325}.
     *
     * <p>The two date fields carry whatever {@code app/cbl/CBTRN03C.cbl:L277-L278} moved into them, which
     * is why they are read from the header state rather than from the reporting period directly.
     *
     * @return the 133 character report name header record.
     */
    private String reportNameHeaderLine() {
        String content = fixedWidth(REPT_SHORT_NAME_TEXT, REPT_SHORT_NAME_WIDTH)
                + fixedWidth(REPT_LONG_NAME_TEXT, REPT_LONG_NAME_WIDTH)
                + fixedWidth(REPT_DATE_HEADER_TEXT, REPT_DATE_HEADER_WIDTH)
                + fixedWidth(this.reportStartDate, REPT_DATE_WIDTH)
                + fixedWidth(DATE_RANGE_SEPARATOR_TEXT, DATE_RANGE_SEPARATOR_WIDTH)
                + fixedWidth(this.reportEndDate, REPT_DATE_WIDTH);
        return fixedWidth(content, REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code WS-BLANK-LINE}, {@code app/cbl/CBTRN03C.cbl:L133}.
     *
     * <p>{@code PIC X(133) VALUE SPACES} - the separator line of the header block, already the full record
     * length, so no padding applies.
     *
     * @return {@value #REPORT_LINE_LENGTH} spaces.
     */
    private static String blankLine() {
        return " ".repeat(REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code TRANSACTION-HEADER-1}, {@code app/cpy/CVTRA07Y.cpy:L33-L46}.
     *
     * <p>Seven fields totalling {@code 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114} characters of content, then
     * space padded to the record length. Every literal is reproduced exactly as declared, including the
     * eight leading spaces inside {@code '        Amount'} at {@code app/cpy/CVTRA07Y.cpy:L46}, which right
     * align the heading over the fifteen character amount column.
     *
     * @return the 133 character column heading record.
     */
    private static String transactionHeader1Line() {
        String content = fixedWidth(HEADING_TRANSACTION_ID_TEXT, HEADING_TRANSACTION_ID_WIDTH)
                + fixedWidth(HEADING_ACCOUNT_ID_TEXT, HEADING_ACCOUNT_ID_WIDTH)
                + fixedWidth(HEADING_TRANSACTION_TYPE_TEXT, HEADING_TRANSACTION_TYPE_WIDTH)
                + fixedWidth(HEADING_TRAN_CATEGORY_TEXT, HEADING_TRAN_CATEGORY_WIDTH)
                + fixedWidth(HEADING_TRAN_SOURCE_TEXT, HEADING_TRAN_SOURCE_WIDTH)
                + " ".repeat(HEADING_GAP_WIDTH)
                + fixedWidth(HEADING_AMOUNT_TEXT, HEADING_AMOUNT_WIDTH);
        return fixedWidth(content, REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code TRANSACTION-HEADER-2}, {@code app/cpy/CVTRA07Y.cpy:L48}.
     *
     * <p>{@code PIC X(133) VALUE ALL '-'} - the only structure in the copybook that is already the full
     * record length, and therefore the only one needing no padding. It serves as the rule under the column
     * headings and under both total blocks, which is why it is emitted from three paragraphs.
     *
     * @return {@value #REPORT_LINE_LENGTH} hyphens.
     */
    private static String transactionHeader2Line() {
        return String.valueOf(RULE_CHARACTER).repeat(REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code REPORT-PAGE-TOTALS}, {@code app/cpy/CVTRA07Y.cpy:L50-L54}.
     *
     * <p>{@code 11 + 86 + 15 = 112} characters of content: the label {@code 'Page Total'} in an eleven
     * character field, an eighty six character leader of {@code ALL '.'}, and the amount under the
     * {@code +ZZZ,ZZZ,ZZZ.ZZ} mask.
     *
     * @param amount the page total to render, from {@code MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL} at
     * {@code app/cbl/CBTRN03C.cbl:L294}.
     * @return the 133 character page total record.
     */
    private static String reportPageTotalsLine(BigDecimal amount) {
        String content = fixedWidth(PAGE_TOTAL_LABEL_TEXT, PAGE_TOTAL_LABEL_WIDTH)
                + String.valueOf(LEADER_CHARACTER).repeat(PAGE_TOTAL_LEADER_WIDTH)
                + formatTotalAmount(amount);
        return fixedWidth(content, REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code REPORT-ACCOUNT-TOTALS}, {@code app/cpy/CVTRA07Y.cpy:L56-L60}.
     *
     * <p>{@code 13 + 84 + 15 = 112} characters of content. The label is two characters longer than the
     * other two and the leader two characters shorter, which is what keeps all three amount columns
     * aligned at 112. The literal reads {@code 'Account Total'} although the group being closed is a card
     * number group - see {@link #writeAccountTotals(List)} for that finding.
     *
     * @param amount the account total to render, from {@code MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL}
     * at {@code app/cbl/CBTRN03C.cbl:L307}.
     * @return the 133 character account total record.
     */
    private static String reportAccountTotalsLine(BigDecimal amount) {
        String content = fixedWidth(ACCOUNT_TOTAL_LABEL_TEXT, ACCOUNT_TOTAL_LABEL_WIDTH)
                + String.valueOf(LEADER_CHARACTER).repeat(ACCOUNT_TOTAL_LEADER_WIDTH)
                + formatTotalAmount(amount);
        return fixedWidth(content, REPORT_LINE_LENGTH);
    }

    /**
     * Builds {@code REPORT-GRAND-TOTALS}, {@code app/cpy/CVTRA07Y.cpy:L62-L66}.
     *
     * <p>{@code 11 + 86 + 15 = 112} characters of content, the same geometry as the page total line with a
     * different label.
     *
     * @param amount the grand total to render, from {@code MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL} at
     * {@code app/cbl/CBTRN03C.cbl:L319}.
     * @return the 133 character grand total record.
     */
    private static String reportGrandTotalsLine(BigDecimal amount) {
        String content = fixedWidth(GRAND_TOTAL_LABEL_TEXT, GRAND_TOTAL_LABEL_WIDTH)
                + String.valueOf(LEADER_CHARACTER).repeat(GRAND_TOTAL_LEADER_WIDTH)
                + formatTotalAmount(amount);
        return fixedWidth(content, REPORT_LINE_LENGTH);
    }

    /**
     * Renders an amount under the detail mask {@code PIC -ZZZ,ZZZ,ZZZ.ZZ},
     * {@code app/cpy/CVTRA07Y.cpy:L30}.
     *
     * <p>A <strong>single</strong> sign symbol is a fixed insertion character: it occupies position one and
     * does not float with the value. Because the symbol is {@code -}, it prints a hyphen for a negative
     * amount and a <strong>space</strong> for a non negative one.
     *
     * @param amount the value of {@code TRAN-AMT} for the record being reported.
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters.
     */
    private static String formatDetailAmount(BigDecimal amount) {
        return formatEditedAmount(amount, false);
    }

    /**
     * Renders an amount under the total mask {@code PIC +ZZZ,ZZZ,ZZZ.ZZ},
     * {@code app/cpy/CVTRA07Y.cpy:L54}, {@code :L60} and {@code :L66}.
     *
     * <p>The {@code +} symbol is likewise fixed in position one, but it is mandatory: it prints
     * {@code +} for a non negative value and {@code -} for a negative one, so a total always carries a
     * visible sign.
     *
     * @param amount the value of one of the three accumulators.
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters.
     */
    private static String formatTotalAmount(BigDecimal amount) {
        return formatEditedAmount(amount, true);
    }

    /**
     * Applies a COBOL numeric edit mask of {@value #EDITED_AMOUNT_WIDTH} characters: one fixed sign
     * followed by {@value #AMOUNT_MASK_BODY}.
     *
     * <p>The mask body is walked symbol by symbol, so the layout in
     * {@code app/cpy/CVTRA07Y.cpy:L30} is the algorithm rather than a set of hard coded positions:
     *
     * <ul>
     *   <li>{@code Z} consumes one digit. While still suppressing, a {@code 0} becomes a space; the first
     *       significant digit stops suppression and every digit after it prints.</li>
     *   <li>{@code ,} prints a space while suppressing and a comma once suppression has stopped, which is
     *       why {@code 194.00} carries no stray comma in its blanked region.</li>
     *   <li>{@code .} always prints, and stops suppression. That is the second half of the standard rule -
     *       replacement ends at the first significant digit <em>or at the decimal point, whichever comes
     *       first</em> - and it is what makes the two decimal digits of {@code 0.05} print while all nine
     *       integer positions blank.</li>
     * </ul>
     *
     * <p><strong>Zero.</strong> Every digit position in both masks is a {@code Z}, so a zero value leaves
     * nothing to print and the field blanks out entirely. The two masks then differ on the fixed sign: the
     * {@code -} form yields {@value #EDITED_AMOUNT_WIDTH} spaces, while the {@code +} form keeps its
     * mandatory sign and yields {@code +} followed by fourteen spaces. <em>Finding, Medium severity.</em>
     * A stricter reading of the IBM rule blanks the editing characters too, which would drop that
     * {@code +} as well. The reading implemented here treats the fixed insertion sign as outside the
     * suppression region, and it is flagged rather than assumed because the two readings differ in exactly
     * one observable character. Remediation, should the Gate 1 baseline show a blank: return
     * {@value #EDITED_AMOUNT_WIDTH} spaces for both masks, a one line change in the zero branch below.
     * Destined for {@code DECISION_LOG.md}.
     *
     * <p><strong>High order truncation.</strong> The mask holds {@value #AMOUNT_INTEGER_DIGITS} integer
     * digits, which is exactly the domain of {@code PIC S9(09)V99}, so a single transaction amount always
     * fits. A running total need not: this class accumulates in {@link BigDecimal} and so loses nothing,
     * whereas the source accumulates in {@code PIC S9(09)V99} and would have truncated on overflow. The
     * rendering keeps the low order nine integer digits, which is what a COBOL {@code MOVE} into the
     * narrower edited field does, so the printed column matches the legacy column even where the retained
     * value is more accurate than the legacy one.
     *
     * <p>No locale can influence the result. The grouping separator and decimal point are the mask's own
     * literals, the digits come from {@link BigDecimal#unscaledValue()}, and the only
     * {@link String#format} calls in this class pass {@link Locale#ROOT}. A locale sensitive formatter here
     * would silently corrupt every amount in the report on a machine whose default locale groups with a
     * point.
     *
     * @param amount the value to render; {@code null} is treated as zero, since a COBOL numeric field
     * cannot be absent.
     * @param mandatorySign {@code true} for the {@code +} mask, {@code false} for the {@code -} mask.
     * @return exactly {@value #EDITED_AMOUNT_WIDTH} characters.
     */
    private static String formatEditedAmount(BigDecimal amount, boolean mandatorySign) {
        BigDecimal scaled = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);

        // The digits of the absolute value, without sign or point: |value| x 100 for a scale of two.
        String allDigits = lowOrderDigits(scaled.abs().unscaledValue().toString(),
                AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE);

        if (isAllZeroDigits(allDigits)) {
            if (mandatorySign) {
                return SIGN_POSITIVE + " ".repeat(EDITED_AMOUNT_WIDTH - 1);
            }
            return " ".repeat(EDITED_AMOUNT_WIDTH);
        }

        StringBuilder edited = new StringBuilder(EDITED_AMOUNT_WIDTH);
        if (scaled.signum() < 0) {
            edited.append(SIGN_NEGATIVE);
        } else {
            edited.append(mandatorySign ? SIGN_POSITIVE : SIGN_BLANK);
        }

        boolean suppressing = true;
        int digitIndex = 0;
        for (int position = 0; position < AMOUNT_MASK_BODY.length(); position++) {
            char symbol = AMOUNT_MASK_BODY.charAt(position);
            if (symbol == ZERO_SUPPRESSION_SYMBOL) {
                char digit = allDigits.charAt(digitIndex);
                digitIndex++;
                if (suppressing && digit == '0') {
                    edited.append(SIGN_BLANK);
                } else {
                    suppressing = false;
                    edited.append(digit);
                }
            } else if (symbol == DECIMAL_POINT) {
                suppressing = false;
                edited.append(DECIMAL_POINT);
            } else {
                edited.append(suppressing ? SIGN_BLANK : GROUPING_SEPARATOR);
            }
        }
        return edited.toString();
    }

    /**
     * Validates one injected collaborator.
     *
     * <p>The source obtains its inputs by {@code OPEN} at {@code app/cbl/CBTRN03C.cbl:L161-L166}, each open
     * guarded so that an unavailable file abends before a single record is read. Constructor validation is
     * the equivalent guard: a missing collaborator fails the step at wiring time rather than on the first
     * record, which is both earlier and easier to diagnose.
     *
     * <p>Declared {@code static} deliberately. It is called from the constructor, and a constructor that
     * calls an overridable instance method leaks {@code this} before the object is initialised - a condition
     * {@code -Xlint:this-escape} reports and {@code -Werror} then fails the build on.
     *
     * @param <T> the collaborator type.
     * @param value the injected collaborator.
     * @param typeName the type, for the abend reason.
     * @param ddName the legacy DD name or field the collaborator stands in for, for the abend reason.
     * @return {@code value}, never {@code null}.
     * @throws FatalProcessingException if {@code value} is {@code null}.
     */
    private static <T> T requireCollaborator(T value, String typeName, String ddName) {
        if (value == null) {
            throw abendProgram(String.format(Locale.ROOT,
                    "%s was not injected, so %s cannot be resolved", typeName, ddName),
                    ABENDING_PROGRAM_TEXT);
        }
        return value;
    }

    /**
     * Validates one bound of the reporting period, the job parameter substitute for
     * {@code 0550-DATEPARM-READ}.
     *
     * <p>{@code WS-START-DATE} and {@code WS-END-DATE} are both {@code PIC X(10)} at
     * {@code app/cbl/CBTRN03C.cbl:L123} and {@code :L125}, and the filter at {@code :L173-L174} compares
     * them against a ten character substring. A value of any other length would compare against a different
     * number of characters and silently change which records the report carries, so a wrong length is
     * rejected rather than padded.
     *
     * <p>That rejection is the analogue of the {@code OTHER} branch at {@code :L237-L241}: the source cannot
     * inspect the content of the eighty byte record either, but it does abend when the record cannot be
     * obtained, and an unusable period is the same class of failure. The dates are never parsed - see
     * {@link #withinReportingPeriod(Transaction)} for why the comparison must stay lexicographic.
     *
     * @param value the job parameter.
     * @param fieldName the working storage field it stands in for, for the abend reason.
     * @return {@code value}, exactly {@value #REPT_DATE_WIDTH} characters.
     * @throws FatalProcessingException if the parameter is absent or not exactly
     * {@value #REPT_DATE_WIDTH} characters.
     */
    private static String requireReportingDate(String value, String fieldName) {
        if (value == null) {
            throw abendProgram(String.format(Locale.ROOT,
                    "%s was not supplied as a job parameter, so the reporting period is undefined",
                    fieldName),
                    ERROR_READING_DATEPARM_TEXT);
        }
        if (value.length() != REPT_DATE_WIDTH) {
            throw abendProgram(String.format(Locale.ROOT,
                    "%s is %d characters ('%s'), and the field is declared PIC X(%d)",
                    fieldName, value.length(), logSafe(value), REPT_DATE_WIDTH),
                    ERROR_READING_DATEPARM_TEXT);
        }
        return value;
    }

    /**
     * Rejects an inverted reporting period.
     *
     * <p>An added guard with no counterpart in the source, which validates neither bound. An inverted period
     * makes the inclusive test at {@code app/cbl/CBTRN03C.cbl:L173-L174} unsatisfiable, so every record is
     * filtered, the header block driven by the first record at {@code :L275-L280} never fires, and the step
     * writes an empty report while completing normally. Failing loudly is strictly more useful than a silent
     * empty file, and it cannot mask a legacy behaviour because the legacy behaviour is undefined here.
     *
     * <p>The comparison is lexicographic for the same reason the filter is - see
     * {@link #withinReportingPeriod(Transaction)}.
     *
     * @param start the validated start bound.
     * @param end the validated end bound.
     * @throws FatalProcessingException if {@code start} sorts after {@code end}.
     */
    private static void requireOrderedPeriod(String start, String end) {
        if (start.compareTo(end) > 0) {
            throw abendProgram(String.format(Locale.ROOT,
                    "the reporting period '%s' to '%s' is inverted, so no record can satisfy the inclusive "
                            + "filter and the report would be empty",
                    logSafe(start), logSafe(end)),
                    ERROR_READING_DATEPARM_TEXT);
        }
    }

    /**
     * Reads {@code TRAN-AMT} and normalises it to the scale its picture declares.
     *
     * <p>{@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L11} is a two decimal signed field.
     * A COBOL numeric field cannot be absent, so a {@code null} attribute means the row was not loaded as
     * the layout guarantees and the safe outcome is the abend rather than a silent zero that would corrupt
     * three accumulators and one detail line.
     *
     * <p>Scaling here rather than at each use keeps every arithmetic operand at scale two, so
     * {@code pageTotal}, {@code accountTotal} and {@code grandTotal} stay at scale two through every
     * {@link BigDecimal#add(BigDecimal)} and the rendered column never depends on the order additions
     * happened in. {@link RoundingMode#HALF_EVEN} matches the COBOL default for a truncating move into a
     * two decimal field on a value that already carries two decimals, where no rounding occurs at all; it
     * is stated explicitly so the behaviour does not depend on a default.
     *
     * <p>The magnitude is not re checked. {@code com.cardemo.model.entity.Transaction} already bounds the
     * attribute to the {@code S9(09)V99} domain, and restating that bound here would duplicate a validation
     * that has one owner.
     *
     * @param item the record being reported on.
     * @return the amount at scale {@value #AMOUNT_SCALE}, never {@code null}.
     * @throws FatalProcessingException if {@code TRAN-AMT} is absent.
     */
    private static BigDecimal requireAmount(Transaction item) {
        BigDecimal amount = item.getAmount();
        if (amount == null) {
            throw abendProgram(String.format(Locale.ROOT,
                    "TRAN-AMT is absent on transaction '%s', and PIC S9(09)V99 cannot be unset",
                    logSafe(item.getTransactionId())),
                    ABENDING_PROGRAM_TEXT);
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Reads {@code XREF-ACCT-ID} from the cross reference buffer for the detail line.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:L364} moves {@code XREF-ACCT-ID} into the detail line on
     * <strong>every</strong> record, while {@code :L187} refreshes the buffer only on a control break. The
     * intervening records therefore read a buffer filled by an earlier iteration, which is exactly what
     * {@code currentCrossReference} holds - and it is correct precisely because the break key and the
     * cross reference key are the same card number, so every record between two breaks shares the account.
     *
     * <p>An empty buffer at detail time would mean the break at {@code :L181} was bypassed, which cannot
     * happen through {@link #process(Transaction)}. The guard exists so that the impossible state abends
     * with a diagnosis instead of throwing an unqualified {@code NullPointerException}.
     *
     * @return the account identifier of the current control break group.
     * @throws FatalProcessingException if the buffer is empty or carries no account identifier.
     */
    private long requireAccountId() {
        if (this.currentCrossReference == null) {
            throw abendProgram("the CARDXREF buffer is empty at detail time, so the control break at "
                    + "app/cbl/CBTRN03C.cbl:L181 did not refresh it", INVALID_CARD_NUMBER_TEXT.strip());
        }
        Long accountId = this.currentCrossReference.getAccountId();
        if (accountId == null) {
            throw abendProgram("XREF-ACCT-ID is absent on the cross reference row of the current control "
                    + "break group, and PIC 9(11) cannot be unset", INVALID_CARD_NUMBER_TEXT.strip());
        }
        return accountId.longValue();
    }

    /**
     * Reads {@code TRAN-CAT-CD} for the detail line.
     *
     * <p>{@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7} is unsigned and four digits, and
     * {@code app/cbl/CBTRN03C.cbl:L367} moves it into a receiving field of the same picture. It has already
     * been dereferenced by {@link #lookupTranCatg(Transaction)} to build the composite key, so a
     * {@code null} here is unreachable through {@link #process(Transaction)}; the guard keeps the abend
     * diagnosable if the method is ever reached by another path.
     *
     * @param item the record being reported on.
     * @return the category code, widened for the display renderer.
     * @throws FatalProcessingException if {@code TRAN-CAT-CD} is absent.
     */
    private static long requireCategoryCode(Transaction item) {
        Integer categoryCode = item.getCategoryCode();
        if (categoryCode == null) {
            throw abendProgram(String.format(Locale.ROOT,
                    "TRAN-CAT-CD is absent on transaction '%s', and PIC 9(04) cannot be unset",
                    logSafe(item.getTransactionId())),
                    INVALID_TRAN_CATG_KEY_TEXT.strip());
        }
        return categoryCode.longValue();
    }

    /**
     * Applies COBOL alphanumeric {@code MOVE} semantics to a fixed width {@code PIC X(n)} field.
     *
     * <p>A {@code MOVE} into an alphanumeric field left justifies the sending item, pads the remainder with
     * spaces, and truncates on the <strong>right</strong> when the sending item is longer. Every
     * {@code MOVE} in {@code 1120-WRITE-DETAIL} at {@code app/cbl/CBTRN03C.cbl:L363-L370} and in the
     * header and total blocks behaves this way, including the two that truncate a fifty character
     * description - see {@link #writeDetail(List, Transaction, TransactionType, TransactionCategory)}.
     *
     * <p>It is also what pads a content group out to the {@value #REPORT_LINE_LENGTH} character record, so
     * one method covers both the field level moves and the record level move.
     *
     * <p>A {@code null} sending item is treated as spaces. COBOL has no null, so the case can only arise
     * from an unset JPA attribute, and a blank column is the representation the fixed width record would
     * have carried for an empty field.
     *
     * @param value the sending item, or {@code null} for spaces.
     * @param width the declared width of the receiving field.
     * @return exactly {@code width} characters.
     */
    private static String fixedWidth(String value, int width) {
        String source = value == null ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Renders an unsigned integer into a {@code PIC 9(n)} display field.
     *
     * <p>{@code TRAN-REPORT-ACCOUNT-ID} is declared {@code PIC X(11)} at {@code app/cpy/CVTRA07Y.cpy:L19}
     * and receives {@code XREF-ACCT-ID PIC 9(11)}, while {@code TRAN-REPORT-CAT-CD} is itself
     * {@code PIC 9(04)} at {@code :L25}. In both cases the value reaches the report as
     * <strong>zero padded digits</strong>, not as a trimmed number: account {@code 194} prints as
     * {@code 00000000194} and category {@code 1} prints as {@code 0001}. Rendering them any other way
     * shifts nothing but changes every affected column against the Gate 1 baseline.
     *
     * <p>A value too wide for the field keeps its low order digits, which is what a COBOL numeric
     * {@code MOVE} into a narrower field does - high order truncation, silently. A negative value is
     * rejected instead, because an unsigned picture has no position to carry a sign and would otherwise
     * render a hyphen inside the digit region.
     *
     * @param value the value to render.
     * @param width the declared digit count of the receiving field.
     * @param fieldName the field, for the abend reason.
     * @return exactly {@code width} digit characters.
     * @throws FatalProcessingException if {@code value} is negative.
     */
    private static String unsignedDisplayDigits(long value, int width, String fieldName) {
        if (value < MIN_UNSIGNED_DISPLAY_VALUE) {
            throw abendProgram(String.format(Locale.ROOT,
                    "%s is %d, and PIC 9(%d) is unsigned so it has no position for a sign",
                    fieldName, value, width),
                    ABENDING_PROGRAM_TEXT);
        }
        return lowOrderDigits(Long.toString(value), width);
    }

    /**
     * Right aligns a digit string into a fixed number of positions, zero filling or truncating high order
     * digits.
     *
     * <p>The single rule behind both numeric renderers in this class: a COBOL {@code MOVE} into a numeric
     * field aligns on the decimal point, pads unused high order positions with zeros, and drops high order
     * digits that do not fit. {@link #unsignedDisplayDigits(long, int, String)} uses it for a whole
     * {@code PIC 9(n)} field and {@link #formatEditedAmount(BigDecimal, boolean)} uses it for the eleven
     * digit region of the edit mask.
     *
     * @param digits the digit characters, without sign or decimal point.
     * @param width the number of digit positions available.
     * @return exactly {@code width} digit characters.
     */
    private static String lowOrderDigits(String digits, int width) {
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Reports whether a digit string is entirely zeros.
     *
     * <p>The zero test for {@link #formatEditedAmount(BigDecimal, boolean)}. It is applied to the digit
     * string rather than to the {@link BigDecimal} because the mask blanks on the <strong>rendered</strong>
     * digits: a value whose only significant digits fall outside the eleven positions the mask holds renders
     * as zeros and must blank accordingly, which a {@link BigDecimal#signum()} test would miss.
     *
     * @param digits the digit characters to test.
     * @return {@code true} when every character is {@code '0'}.
     */
    private static boolean isAllZeroDigits(String digits) {
        for (int index = 0; index < digits.length(); index++) {
            if (digits.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Neutralises control characters before a value reaches the log.
     *
     * <p>The source writes with {@code DISPLAY} to SYSOUT, where a record is a record and a data value
     * cannot forge one. A line oriented log has no such protection: a control character inside a persisted
     * field could close the current line and forge a second, apparently genuine, log record. Every
     * interpolated value therefore passes through here, satisfying the untrusted input clause of the
     * project standard on the one surface of this class that emits free text.
     *
     * <p>{@link Character#isISOControl(char)} covers both control ranges, so no character able to break a
     * line survives. {@code null} renders as {@value #ABSENT_VALUE} rather than as {@code "null"}, because a
     * COBOL field cannot be absent and the distinction is worth seeing in a diagnostic.
     *
     * @param value the value to interpolate, possibly {@code null}.
     * @return a value safe to place in a log line, never {@code null}.
     */
    private static String logSafe(String value) {
        if (value == null) {
            return ABSENT_VALUE;
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? LOG_SUBSTITUTE_CHARACTER : character);
        }
        return safe.toString();
    }

    /**
     * Masks a card number for the log, keeping only its last {@value #CARD_NUMBER_VISIBLE_DIGITS} digits.
     *
     * <p><em>Finding, Low severity.</em> {@code app/cbl/CBTRN03C.cbl:L180} displays the whole
     * {@code TRAN-RECORD} and {@code :L487} displays the whole card number, both to a SYSOUT held under
     * dataset level protection. The no secrets clause of the project standard forbids reproducing that on a
     * log stream that is aggregated and searchable, so the two log sites mask while the report body -
     * which is the legitimate, access controlled output - continues to carry card derived data in full. The
     * divergence is confined to log text and is invisible to the Gate 1 comparison, which reads report
     * records. Destined for {@code DECISION_LOG.md}.
     *
     * <p>The value is passed through {@link #logSafe(String)} first, so a masked value cannot smuggle a
     * control character either, and trailing spaces from the sixteen character fixed width key are stripped
     * so the mask length reflects the digits actually present.
     *
     * @param cardNumber the card number, possibly {@code null} and possibly space padded.
     * @return the masked value, never {@code null} and never carrying more than
     * {@value #CARD_NUMBER_VISIBLE_DIGITS} original digits.
     */
    private static String maskCardNumber(String cardNumber) {
        String digits = logSafe(cardNumber).strip();
        if (digits.isEmpty() || ABSENT_VALUE.equals(digits)) {
            return ABSENT_VALUE;
        }
        if (digits.length() <= CARD_NUMBER_VISIBLE_DIGITS) {
            return String.valueOf(CARD_MASK_CHARACTER).repeat(digits.length());
        }
        int masked = digits.length() - CARD_NUMBER_VISIBLE_DIGITS;
        return String.valueOf(CARD_MASK_CHARACTER).repeat(masked) + digits.substring(masked);
    }
}
