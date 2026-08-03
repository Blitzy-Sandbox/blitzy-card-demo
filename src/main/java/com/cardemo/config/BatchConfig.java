/*
 * ******************************************************************
 * Program     : BatchConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (batch wiring)
 * Function    : Registers the batch collaborators that cannot register
 *               themselves - the four repository backed dataset bindings
 *               that stand in for the CBSTM03B file access subprogram's
 *               four DD names. The step scoped report processor is NOT
 *               registered here: it carries @Component @StepScope itself,
 *               and a factory of the same derived bean name could not
 *               coexist with it.
 * Source      : app/cbl/CBTRN03C.cbl:L127-L137 (WS-REPORT-VARS, the six
 *                 per-run state items that forbid a singleton)
 *               + app/proc/TRANREPT.prc:L60-L78 (the report step and its
 *                 two SYMNAMES driven reporting dates)
 *               + app/cbl/CBSTM03B.CBL:L58-L97 (the four DD names, their
 *                 access modes, key widths and FILE STATUS groups)
 *               + app/cbl/CBSTM03A.CBL:L71-L83 (the CALL contract whose
 *                 WS-M03B-FLDT carries the record image)
 *               + app/jcl/CREASTMT.JCL:L47-L60 (the sort that fixes the
 *                 TRNXFILE order and the KEYS(32 0) work cluster)
 *               + app/cpy/CVACT01Y.cpy + app/cpy/CVCUS01Y.cpy
 *                 + app/cpy/CVACT03Y.cpy + app/cpy/COSTM01.CPY (the four
 *                 record layouts the bindings render)
 *               @ 7756d89
 * Replaces    : the JCL EXEC PGM step definitions of the batch stream and
 *               the static CALL 'CBSTM03B' linkage
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
package com.cardemo.config;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileService;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

/**
 * Wiring for the batch tier: the four dataset bindings that give the {@code CBSTM03B} translation something
 * to read.
 *
 * <h2>What it does</h2>
 *
 * <p>One group of beans, which exists because the collaborators concerned cannot correctly register
 * themselves.
 *
 * <ol>
 *   <li><strong>{@code TransactionReportProcessor} is deliberately NOT declared here.</strong> It carries
 *       the six {@code WS-REPORT-VARS} items of {@code app/cbl/CBTRN03C.cbl:L127-L137} - the line counter,
 *       three running totals, the control-break card number and the first-time flag - so a singleton
 *       instance would carry one report's pagination and totals into the next, and it needs the two
 *       reporting dates that {@code app/proc/TRANREPT.prc:L60-L70} supplies through {@code SYMNAMES}. Both
 *       requirements are met on the class itself: it is annotated {@code @Component @StepScope} and binds
 *       the dates with {@code @Value("#{jobParameters[...]}")}. A {@code @Bean} factory here would derive
 *       the same bean name, {@code transactionReportProcessor}, and
 *       {@code spring.main.allow-bean-definition-overriding} is {@code false}, so the pair would abort
 *       startup rather than be a harmless redundancy. The processor stays directly constructible from a
 *       unit test because its constructor takes plain strings whatever binds them.</li>
 *   <li><strong>The four {@link FileService.Dataset} bindings.</strong> {@code CBSTM03A} reaches all of its
 *       input through {@code CALL 'CBSTM03B' USING WS-M03B-AREA} with a DD-name selector
 *       ({@code app/cbl/CBSTM03A.CBL:L71-L83}), and {@code CBSTM03B} declares exactly four files
 *       ({@code app/cbl/CBSTM03B.CBL:L58-L78}). {@code FileService} is the translation of the subprogram
 *       and owns the dispatch, the status registers and the guards; it deliberately owns no data access.
 *       These four beans are the data access, one per DD, each backed by the repository that replaced the
 *       corresponding VSAM cluster. Without them the service dispatches into nothing and the statement
 *       processor fails on its first operation.</li>
 *   </ol>
 *
 * <h2>The record-image contract, and why these bindings render fixed-width text</h2>
 *
 * <p>{@code CBSTM03B} returns a record by moving it into {@code WS-M03B-FLDT PIC X(1000)}; the caller then
 * moves that area into a copybook-shaped group and reads fields by position. The relational substrate has
 * columns rather than positions, so each binding renders its entity back into the legacy record image and
 * {@code StatementProcessor} parses it exactly as {@code CBSTM03A} did. That round trip is not ceremony: it
 * is the contract, and it is what keeps the field widths, the zoned-decimal signs and the 32-byte composite
 * key of {@code app/cpy/COSTM01.CPY} observable rather than implicit. The widths are taken from the
 * copybooks and asserted by {@link FileService.Dd#recordWidth()}, so a drift is a startup failure rather
 * than a silently short record.
 *
 * <p>The signed money fields of {@code app/cpy/CVACT01Y.cpy} are encoded through
 * {@link StatementProcessor#encodeZonedDecimal(BigDecimal, int)}, the published inverse of the decoder that
 * consumes them. The table is therefore stated once, in the class that owns both directions, rather than
 * copied here.
 *
 * <h2>Ordering is a precondition, not a preference</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L417-L419} abandons its scan as soon as a stored card number exceeds the
 * one it is looking for. That early exit is only correct while both sequences ascend by card number, which
 * {@code app/jcl/CREASTMT.JCL:L53} guarantees for the transaction stream and the {@code XREF-CARD-NUM}
 * record key guarantees for the cross-reference stream. Both sequential bindings therefore read in that
 * order and do so through <strong>keyset</strong> windows rather than page-number offsets, so a full-run
 * scan costs one index seek per window instead of re-reading every preceding row.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile} - {@code -Xlint:all -Werror} with {@code failOnWarning}, so
 * a warning fails the build. Test with {@code ./mvnw -B clean test}; the full gate, including the JaCoCo
 * line floor and the Failsafe integration tier, is {@code ./mvnw -B clean verify}. The integration tier
 * needs a reachable Docker socket because it starts PostgreSQL and the AWS emulator through Testcontainers.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <table border="1">
 *   <caption>Configuration this class reads</caption>
 *   <tr><th>Key</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code jobParameters['startDate']}</td><td>none</td>
 *       <td>{@code WS-START-DATE}, the inclusive lower bound of the report period</td></tr>
 *   <tr><td>{@code jobParameters['endDate']}</td><td>none</td>
 *       <td>{@code WS-END-DATE}, the inclusive upper bound of the report period</td></tr>
 *   <tr><td>{@code carddemo.batch.dataset-window-size}</td><td>{@value #DEFAULT_WINDOW_SIZE}</td>
 *       <td>Rows fetched per keyset window by the two sequential bindings. Affects memory and round trips
 *           only; it cannot affect the emitted output, because the order is fixed independently of it</td></tr>
 * </table>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails with "no dataset binding is registered for DD ..."</dt>
 *   <dd>{@code FileService} verified its bindings at wiring time and one of the four was missing. Either
 *       this class was not scanned - a sliced test that imported only some configuration - or a bean method
 *       below was removed. All four are mandatory; the service refuses to start half-bound rather than
 *       abend in the middle of a statement run.</dd>
 *   <dt>Startup fails with "two dataset bindings claim DD ..."</dt>
 *   <dd>A second binding for the same DD was declared elsewhere. Remove that one: dispatch would otherwise
 *       depend on bean ordering.</dd>
 *   <dt>The report processor bean cannot be created outside a step</dt>
 *   <dd>Expected, and not a defect in this class. {@code TransactionReportProcessor} is
 *       {@code @Component @StepScope} and its two dates come from job parameters, so it resolves only
 *       inside a running step. A unit test constructs it directly instead, which is why its constructor
 *       takes plain strings.</dd>
 *   <dt>A statement run reports a card group as missing although rows exist</dt>
 *   <dd>The two sequential streams disagreed on order. Check that nothing reordered the statement sort:
 *       the transaction stream must be card-then-identifier ascending and the cross-reference stream must
 *       be card ascending, or the source's early exit ends the scan too soon.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>This class holds one immutable {@code int} field, the window size, assigned once by the constructor
 * from configuration. The two random-access bindings are stateless. The two sequential bindings hold a
 * cursor, which is what a sequential read is; every method that touches it is {@code synchronized} and
 * {@code openInput} resets it, so a second run starts from the beginning exactly as a second
 * {@code OPEN INPUT} does. That mirrors the source faithfully: {@code CBSTM03B} is a single load module
 * whose {@code WORKING-STORAGE} survives between calls, so the legacy program supported one statement run
 * at a time and so does this wiring. Two concurrent statement runs are outside the source's contract and
 * are not supported.
 */
@Configuration
public class BatchConfig {

    /**
     * Default number of rows a sequential binding fetches per keyset window.
     *
     * <p>Chosen to match the chunk size the batch profile already uses for the interest job, so that a
     * dataset window and a chunk are the same order of magnitude and neither dominates the other.
     */
    private static final int DEFAULT_WINDOW_SIZE = 100;

    /** Configuration key for the window size, so the spelling exists exactly once. */
    private static final String KEY_WINDOW_SIZE = "carddemo.batch.dataset-window-size";

    /**
     * The character-comparison origin for a keyset scan. The empty string precedes every non-empty key, so
     * a first window requested with it starts at the beginning of the sequence.
     */
    private static final String SCAN_ORIGIN = "";

    /**
     * The {@code '00'} status every successful operation returns, taken from the enum that owns the
     * vocabulary rather than written as a literal.
     */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.code().orElseThrow();

    /** The {@code '10'} status a sequential read returns at end of file. */
    private static final String STATUS_END_OF_FILE = FileStatus.END_OF_FILE.code().orElseThrow();

    /** The {@code '23'} status a keyed read returns when the key is absent. */
    private static final String STATUS_RECORD_NOT_FOUND = FileStatus.RECORD_NOT_FOUND.code().orElseThrow();

    /** Rows fetched per keyset window by the two sequential bindings. Always at least one. */
    private final int windowSize;

    /**
     * Creates the configuration class.
     *
     * @param windowSize rows per keyset window, from {@value #KEY_WINDOW_SIZE}, defaulting to
     *     {@value #DEFAULT_WINDOW_SIZE}
     * @throws IllegalArgumentException if the window size is not positive, because a non-positive window
     *     would either fetch nothing and report a premature end of file or spin forever
     */
    public BatchConfig(
            @Value("${" + KEY_WINDOW_SIZE + ":" + DEFAULT_WINDOW_SIZE + "}") final int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException(KEY_WINDOW_SIZE + " must be positive but was " + windowSize);
        }
        this.windowSize = windowSize;
    }

    // =============================================================================================
    // THERE IS DELIBERATELY NO PROCESSOR, READER OR WRITER FACTORY IN THIS CLASS.
    //
    // A @Bean @StepScope transactionReportProcessor factory stood here and has been removed. It could not
    // coexist with the component it built: com.cardemo.batch.processors.TransactionReportProcessor is
    // annotated @Component @StepScope, whose default bean name is "transactionReportProcessor" - the same
    // name this factory method carried. spring.main.allow-bean-definition-overriding is false in the base
    // profile, so the pair was a startup failure rather than a harmless redundancy, and the arity of the
    // processor's constructor had moved on from the call this factory made.
    //
    // The component annotation is what survives, for two reasons that outlast this one class. Every reader,
    // processor and writer under batch/** is registered by @Component on the class - twelve of them - so a
    // factory here would be the single exception a reader has to notice. And the scope is a property of the
    // component rather than of whoever wires it: a step-scoped bean cannot be injected into a singleton
    // without a proxy, so the container enforces the isolation contract that a factory in a configuration
    // class merely asserts. What this class owns is the FileService.Dataset bindings below, which have no
    // component to annotate because they are one binding per DD name rather than one class per role.
    // =============================================================================================


    /**
     * The {@code TRNXFILE} binding: the sorted statement transaction stream of
     * {@code app/jcl/CREASTMT.JCL:STEP010} and {@code STEP020}.
     *
     * @param transactionRepository the {@code TRANSACT} cluster
     * @return the sequential binding for {@link FileService.Dd#TRNXFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset trnxFileDataset(final TransactionRepository transactionRepository) {
        return new TrnxFileDataset(transactionRepository, windowSize);
    }

    /**
     * The {@code XREFFILE} binding: the cross-reference cluster read in record-key order.
     *
     * @param cardCrossReferenceRepository the {@code CARDXREF} cluster
     * @return the sequential binding for {@link FileService.Dd#XREFFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset xrefFileDataset(
            final CardCrossReferenceRepository cardCrossReferenceRepository) {
        return new XrefFileDataset(cardCrossReferenceRepository, windowSize);
    }

    /**
     * The {@code CUSTFILE} binding: keyed reads of the customer cluster.
     *
     * @param customerRepository the {@code CUSTDATA} cluster
     * @return the random-access binding for {@link FileService.Dd#CUSTFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset custFileDataset(final CustomerRepository customerRepository) {
        return new CustFileDataset(customerRepository);
    }

    /**
     * The {@code ACCTFILE} binding: keyed reads of the account cluster.
     *
     * @param accountRepository the {@code ACCTDATA} cluster
     * @return the random-access binding for {@link FileService.Dd#ACCTFILE}, never {@code null}
     */
    @Bean
    public FileService.Dataset acctFileDataset(final AccountRepository accountRepository) {
        return new AcctFileDataset(accountRepository);
    }

    // =============================================================================================
    // Record rendering. Field widths come from the copybooks named on each method, never from a
    // measurement of the data, so a short or wide value is a startup or read failure rather than a
    // silently misaligned record.
    // =============================================================================================

    /** {@code XREF-CUST-ID PIC 9(09)}, {@code app/cpy/CVACT03Y.cpy:L6}. */
    private static final int CUSTOMER_ID_DIGITS = 9;

    /** {@code XREF-ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** {@code XREF-CARD-NUM PIC X(16)}, {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME}, {@code CUST-LAST-NAME}, each {@code PIC X(25)}. */
    private static final int NAME_PART_WIDTH = 25;

    /** {@code CUST-ADDR-LINE-1} through {@code -3}, each {@code PIC X(50)}. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private static final int STATE_CODE_WIDTH = 2;

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private static final int COUNTRY_CODE_WIDTH = 3;

    /** {@code CUST-ADDR-ZIP} and {@code ACCT-ADDR-ZIP}, both {@code PIC X(10)}. */
    private static final int ZIP_WIDTH = 10;

    /** {@code CUST-PHONE-NUM-1} and {@code -2}, both {@code PIC X(15)}. */
    private static final int PHONE_WIDTH = 15;

    /** {@code CUST-SSN PIC 9(09)}. */
    private static final int SSN_WIDTH = 9;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final int GOVT_ID_WIDTH = 20;

    /** {@code CUST-DOB-YYYY-MM-DD} and the three {@code ACCT-*-DATE} fields, all {@code PIC X(10)}. */
    private static final int DATE_WIDTH = 10;

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private static final int EFT_ACCOUNT_WIDTH = 10;

    /** {@code CUST-PRI-CARD-HOLDER-IND} and {@code ACCT-ACTIVE-STATUS}, both {@code PIC X(01)}. */
    private static final int INDICATOR_WIDTH = 1;

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    private static final int FICO_SCORE_DIGITS = 3;

    /** {@code ACCT-CURR-BAL} and its four siblings, all {@code PIC S9(10)V99} - twelve characters. */
    private static final int MONEY_FIELD_WIDTH = 12;

    /** {@code ACCT-GROUP-ID PIC X(10)}. */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * Fits a value to an exact character width, right-padding with spaces and truncating on the right.
     *
     * <p>This is what a COBOL {@code MOVE} of an alphanumeric item into a shorter or longer one does, and it
     * is applied uniformly so that no field can silently shift the ones after it.
     *
     * @param value the value, {@code null} rendering as all spaces
     * @param width the exact width required
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String fit(final String value, final int width) {
        final String source = value == null ? "" : value;
        if (source.length() == width) {
            return source;
        }
        if (source.length() > width) {
            return source.substring(0, width);
        }
        return source + " ".repeat(width - source.length());
    }

    /**
     * Renders an unsigned integral value as zero-padded digits, which is what {@code PIC 9(n)} holds.
     *
     * @param value the value, {@code null} rendering as zero
     * @param width the exact number of digits
     * @return exactly {@code width} digits, never {@code null}
     */
    private static String digits(final Number value, final int width) {
        final long magnitude = value == null ? 0L : Math.abs(value.longValue());
        final String rendered = Long.toString(magnitude);
        if (rendered.length() >= width) {
            return rendered.substring(rendered.length() - width);
        }
        return "0".repeat(width - rendered.length()) + rendered;
    }

    /**
     * Renders a value already known to be digits as zero-padded digits of an exact width.
     *
     * <p>Used for {@code CUST-SSN} and {@code CUST-FICO-CREDIT-SCORE}, both of which the entity holds as
     * text because their leading zeros are significant.
     *
     * @param value the digit string, {@code null} or blank rendering as all zeros
     * @param width the exact number of digits
     * @return exactly {@code width} characters, never {@code null}
     */
    private static String digitText(final String value, final int width) {
        final String source = value == null ? "" : value.strip();
        if (source.isEmpty()) {
            return "0".repeat(width);
        }
        if (source.length() >= width) {
            return source.substring(source.length() - width);
        }
        return "0".repeat(width - source.length()) + source;
    }

    /**
     * Asserts that a rendered record is exactly the width its DD declares.
     *
     * <p>A record of the wrong width is the one failure that would look like working code: the caller
     * slices by position, so every field after the drift would be read from the wrong offset.
     *
     * @param record the rendered record
     * @param dd the DD the record belongs to
     * @return the record, unchanged
     * @throws IllegalStateException if the width is wrong, which is a defect in the renderer above rather
     *     than a data condition
     */
    private static String requireRecordWidth(final String record, final FileService.Dd dd) {
        if (record.length() != dd.recordWidth()) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "the rendered %s record is %d characters but the DD declares exactly %d",
                    dd.ddName(), Integer.valueOf(record.length()), Integer.valueOf(dd.recordWidth())));
        }
        return record;
    }

    /**
     * The sorted transaction stream that {@code CBSTM03A} reads through its {@code TRNXFILE} DD.
     *
     * <p>Sequential, ascending by card number then transaction identifier, rendered through the same
     * {@code OUTREC} projection the sort applies - which is why the record is produced by
     * {@link StatementProcessor#projectBaseRecord(Transaction)} rather than assembled here. That method
     * renders the 350-byte {@code app/cpy/CVTRA05Y.cpy} image and then applies the projection of
     * {@code app/jcl/CREASTMT.JCL:L57}, which truncates two bytes of the processing timestamp and drops the
     * trailing filler; reproducing that shape in a second place would invite the two copies to diverge.
     */
    private static final class TrnxFileDataset implements FileService.Dataset {

        /** The {@code TRANSACT} cluster. */
        private final TransactionRepository transactionRepository;

        /** Rows fetched per keyset window. */
        private final int windowSize;

        /** The rows of the current window that have not been returned yet. */
        private final Deque<Transaction> window = new ArrayDeque<>();

        /** Card number of the last row returned, the first half of the keyset position. */
        private String lastCardNumber = SCAN_ORIGIN;

        /** Identifier of the last row returned, the second half of the keyset position. */
        private String lastTransactionId = SCAN_ORIGIN;

        /** Whether the underlying sequence has been exhausted, so no further query is issued. */
        private boolean exhausted;

        /**
         * Creates the binding.
         *
         * @param transactionRepository the {@code TRANSACT} cluster; must not be {@code null}
         * @param windowSize rows per keyset window; already validated positive by the enclosing class
         */
        private TrnxFileDataset(final TransactionRepository transactionRepository, final int windowSize) {
            this.transactionRepository = Objects.requireNonNull(transactionRepository,
                    "transactionRepository must not be null");
            this.windowSize = windowSize;
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.TRNXFILE;
        }

        @Override
        public synchronized String openInput() {
            window.clear();
            lastCardNumber = SCAN_ORIGIN;
            lastTransactionId = SCAN_ORIGIN;
            exhausted = false;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized String close() {
            window.clear();
            exhausted = true;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized FileService.DatasetRead readNext() {
            if (window.isEmpty() && !exhausted) {
                final List<Transaction> next = transactionRepository.findStatementOrderAfter(
                        lastCardNumber, lastTransactionId, PageRequest.ofSize(windowSize));
                if (next.isEmpty()) {
                    exhausted = true;
                } else {
                    window.addAll(next);
                }
            }
            final Transaction row = window.pollFirst();
            if (row == null) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            lastCardNumber = fit(row.getCardNumber(), CARD_NUMBER_WIDTH);
            lastTransactionId = row.getTransactionId();
            final String projected = StatementProcessor.projectBaseRecord(row);
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(projected, FileService.Dd.TRNXFILE));
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            throw new UnsupportedOperationException("TRNXFILE is opened for sequential access only; "
                    + "app/cbl/CBSTM03B.CBL:L61-L63 declares ORGANIZATION SEQUENTIAL for it, so a keyed "
                    + "read has no counterpart. The service refuses the operation before reaching here.");
        }
    }

    /**
     * The cross-reference cluster that drives the whole statement run, read through {@code XREFFILE}.
     *
     * <p>Sequential in record-key order, which for {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} is card
     * number ascending. The record is the 36 populated bytes of {@code app/cpy/CVACT03Y.cpy} followed by
     * the 14-byte filler that brings it to the catalogued 50.
     */
    private static final class XrefFileDataset implements FileService.Dataset {

        /** The {@code CARDXREF} cluster. */
        private final CardCrossReferenceRepository cardCrossReferenceRepository;

        /** Rows fetched per keyset window. */
        private final int windowSize;

        /** The rows of the current window that have not been returned yet. */
        private final Deque<CardCrossReference> window = new ArrayDeque<>();

        /** Card number of the last row returned, the keyset position. */
        private String lastCardNumber = SCAN_ORIGIN;

        /** Whether the underlying sequence has been exhausted. */
        private boolean exhausted;

        /**
         * Creates the binding.
         *
         * @param cardCrossReferenceRepository the {@code CARDXREF} cluster; must not be {@code null}
         * @param windowSize rows per keyset window; already validated positive by the enclosing class
         */
        private XrefFileDataset(final CardCrossReferenceRepository cardCrossReferenceRepository,
                final int windowSize) {
            this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                    "cardCrossReferenceRepository must not be null");
            this.windowSize = windowSize;
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.XREFFILE;
        }

        @Override
        public synchronized String openInput() {
            window.clear();
            lastCardNumber = SCAN_ORIGIN;
            exhausted = false;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized String close() {
            window.clear();
            exhausted = true;
            return STATUS_SUCCESS;
        }

        @Override
        public synchronized FileService.DatasetRead readNext() {
            if (window.isEmpty() && !exhausted) {
                final List<CardCrossReference> next = cardCrossReferenceRepository
                        .findByCardNumberGreaterThanOrderByCardNumberAsc(lastCardNumber,
                                PageRequest.ofSize(windowSize));
                if (next.isEmpty()) {
                    exhausted = true;
                } else {
                    window.addAll(next);
                }
            }
            final CardCrossReference row = window.pollFirst();
            if (row == null) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            lastCardNumber = row.getCardNumber();
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderCrossReference(row), FileService.Dd.XREFFILE));
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            throw new UnsupportedOperationException("XREFFILE is opened for sequential access only; "
                    + "app/cbl/CBSTM03B.CBL:L67-L68 declares ORGANIZATION SEQUENTIAL for it, so a keyed "
                    + "read has no counterpart. The service refuses the operation before reaching here.");
        }

        /**
         * Renders one cross-reference row in the {@code app/cpy/CVACT03Y.cpy} layout.
         *
         * @param row the row just read, never {@code null}
         * @return exactly 50 characters: 16 + 9 + 11 populated, then the 14-byte filler
         */
        private static String renderCrossReference(final CardCrossReference row) {
            final String populated = fit(row.getCardNumber(), CARD_NUMBER_WIDTH)
                    + digits(row.getCustomerId(), CUSTOMER_ID_DIGITS)
                    + digits(row.getAccountId(), ACCOUNT_ID_DIGITS);
            return fit(populated, FileService.Dd.XREFFILE.recordWidth());
        }
    }

    /**
     * The customer cluster, read by key through {@code CUSTFILE}.
     *
     * <p>Random access: {@code app/cbl/CBSTM03B.CBL:L71-L74} declares {@code ACCESS MODE IS RANDOM} with
     * {@code FD-CUST-ID} as the record key, and {@code app/cbl/CBSTM03A.CBL:L369-L392} reads it once per
     * cross-reference row. Stateless, because a keyed read carries no position.
     */
    private static final class CustFileDataset implements FileService.Dataset {

        /** The {@code CUSTDATA} cluster. */
        private final CustomerRepository customerRepository;

        /**
         * Creates the binding.
         *
         * @param customerRepository the {@code CUSTDATA} cluster; must not be {@code null}
         */
        private CustFileDataset(final CustomerRepository customerRepository) {
            this.customerRepository = Objects.requireNonNull(customerRepository,
                    "customerRepository must not be null");
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.CUSTFILE;
        }

        @Override
        public String openInput() {
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            throw new UnsupportedOperationException("CUSTFILE is opened for random access only; "
                    + "app/cbl/CBSTM03B.CBL:L71-L74 declares ACCESS MODE IS RANDOM for it, so a "
                    + "sequential read has no counterpart. The service refuses the operation before "
                    + "reaching here.");
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            Objects.requireNonNull(recordKey, "recordKey must not be null");
            final Optional<Customer> found = customerRepository.findById(Long.valueOf(recordKey.strip()));
            if (found.isEmpty()) {
                return FileService.DatasetRead.withoutRecord(STATUS_RECORD_NOT_FOUND);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderCustomer(found.get()), FileService.Dd.CUSTFILE));
        }

        /**
         * Renders one customer row in the {@code app/cpy/CVCUS01Y.cpy} layout.
         *
         * <p>The eighteen named items total 332 characters and the copybook's {@code FILLER PIC X(168)}
         * brings the record to the catalogued 500.
         *
         * @param customer the row just read, never {@code null}
         * @return exactly 500 characters
         */
        private static String renderCustomer(final Customer customer) {
            final StringBuilder record = new StringBuilder(FileService.Dd.CUSTFILE.recordWidth());
            record.append(digits(customer.getCustomerId(), CUSTOMER_ID_DIGITS));
            record.append(fit(customer.getFirstName(), NAME_PART_WIDTH));
            record.append(fit(customer.getMiddleName(), NAME_PART_WIDTH));
            record.append(fit(customer.getLastName(), NAME_PART_WIDTH));
            record.append(fit(customer.getAddressLine1(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressLine2(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressLine3(), ADDRESS_LINE_WIDTH));
            record.append(fit(customer.getAddressStateCode(), STATE_CODE_WIDTH));
            record.append(fit(customer.getAddressCountryCode(), COUNTRY_CODE_WIDTH));
            record.append(fit(customer.getAddressZip(), ZIP_WIDTH));
            record.append(fit(customer.getPhoneNumber1(), PHONE_WIDTH));
            record.append(fit(customer.getPhoneNumber2(), PHONE_WIDTH));
            record.append(digitText(customer.getSsn(), SSN_WIDTH));
            record.append(fit(customer.getGovernmentIssuedId(), GOVT_ID_WIDTH));
            record.append(fit(customer.getDateOfBirth(), DATE_WIDTH));
            record.append(fit(customer.getEftAccountId(), EFT_ACCOUNT_WIDTH));
            record.append(fit(customer.getPrimaryCardHolderIndicator(), INDICATOR_WIDTH));
            record.append(digitText(customer.getFicoCreditScore(), FICO_SCORE_DIGITS));
            return fit(record.toString(), FileService.Dd.CUSTFILE.recordWidth());
        }
    }

    /**
     * The account cluster, read by key through {@code ACCTFILE}.
     *
     * <p>Random access with a <strong>numeric</strong> record key: {@code app/cbl/CBSTM03B.CBL:L77}
     * declares {@code FD-ACCT-ID PIC 9(11)}, which is why {@link FileService.Dd#ACCTFILE} reports
     * {@code numericKey()} and the service validates the key as eleven digits before it arrives here.
     * Stateless, because a keyed read carries no position.
     */
    private static final class AcctFileDataset implements FileService.Dataset {

        /** The {@code ACCTDATA} cluster. */
        private final AccountRepository accountRepository;

        /**
         * Creates the binding.
         *
         * @param accountRepository the {@code ACCTDATA} cluster; must not be {@code null}
         */
        private AcctFileDataset(final AccountRepository accountRepository) {
            this.accountRepository = Objects.requireNonNull(accountRepository,
                    "accountRepository must not be null");
        }

        @Override
        public FileService.Dd dd() {
            return FileService.Dd.ACCTFILE;
        }

        @Override
        public String openInput() {
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            throw new UnsupportedOperationException("ACCTFILE is opened for random access only; "
                    + "app/cbl/CBSTM03B.CBL:L77-L78 declares ACCESS MODE IS RANDOM for it, so a "
                    + "sequential read has no counterpart. The service refuses the operation before "
                    + "reaching here.");
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            Objects.requireNonNull(recordKey, "recordKey must not be null");
            final Optional<Account> found = accountRepository.findById(Long.valueOf(recordKey.strip()));
            if (found.isEmpty()) {
                return FileService.DatasetRead.withoutRecord(STATUS_RECORD_NOT_FOUND);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS,
                    requireRecordWidth(renderAccount(found.get()), FileService.Dd.ACCTFILE));
        }

        /**
         * Renders one account row in the {@code app/cpy/CVACT01Y.cpy} layout.
         *
         * <p>The twelve named items total 122 characters and the copybook's {@code FILLER PIC X(178)}
         * brings the record to the catalogued 300. The five {@code PIC S9(10)V99} money fields are zoned
         * decimal with a trailing overpunch sign, encoded through the published inverse of the decoder that
         * reads them back, so no sign convention is restated here.
         *
         * @param account the row just read, never {@code null}
         * @return exactly 300 characters
         */
        private static String renderAccount(final Account account) {
            final StringBuilder record = new StringBuilder(FileService.Dd.ACCTFILE.recordWidth());
            record.append(digits(account.getAccountId(), ACCOUNT_ID_DIGITS));
            record.append(fit(account.getActiveStatus(), INDICATOR_WIDTH));
            record.append(money(account.getCurrentBalance()));
            record.append(money(account.getCreditLimit()));
            record.append(money(account.getCashCreditLimit()));
            record.append(fit(account.getOpenDate(), DATE_WIDTH));
            record.append(fit(account.getExpiraionDate(), DATE_WIDTH));
            record.append(fit(account.getReissueDate(), DATE_WIDTH));
            record.append(money(account.getCurrentCycleCredit()));
            record.append(money(account.getCurrentCycleDebit()));
            record.append(fit(account.getAddressZip(), ZIP_WIDTH));
            record.append(fit(account.getGroupId(), GROUP_ID_WIDTH));
            return fit(record.toString(), FileService.Dd.ACCTFILE.recordWidth());
        }

        /**
         * Encodes one {@code PIC S9(10)V99} money field.
         *
         * @param value the amount, {@code null} encoding as positive zero
         * @return exactly {@value #MONEY_FIELD_WIDTH} characters
         */
        private static String money(final BigDecimal value) {
            return StatementProcessor.encodeZonedDecimal(value, MONEY_FIELD_WIDTH);
        }
    }
}
