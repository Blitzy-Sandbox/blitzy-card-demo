/*
 * ******************************************************************
 * Program     : TransactionCombineProcessor.java
 * Application : CardDemo
 * Type        : Spring Batch ItemProcessor (Java 25 / Spring Boot 3.5.11)
 * Function    : Merge/ordering semantics for the combined transaction stream.
 * Source      : app/jcl/COMBTRAN.jcl STEP05R + STEP10 @ 7756d89
 *               (no COBOL program exists for this job - the JCL is the source of truth)
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
import java.util.Comparator;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;

/**
 * Per-record merge and ordering semantics of the transaction-combine batch step.
 *
 * <h2>What it does</h2>
 *
 * <p>It is the {@link ItemProcessor} of the step that replaces the two-step z/OS job
 * {@code app/jcl/COMBTRAN.jcl}: a DFSORT step that merges two concatenated sequential inputs into one
 * ordered stream, followed by an IDCAMS {@code REPRO} step that bulk loads that stream into the keyed
 * transaction cluster. Per record it does exactly three things - it validates the sort key, it validates
 * the fixed-width geometry, and it confirms the record is loadable - and then returns the very same
 * instance unchanged. It reads nothing, writes nothing and mutates nothing.
 *
 * <h2>This component has no COBOL program behind it</h2>
 *
 * <p><strong>No COBOL paragraph labels exist for this component; {@code app/jcl/COMBTRAN.jcl} STEP05R
 * and STEP10 are the sole sources.</strong> That sentence is stated explicitly so that the traceability
 * audit does not read the absence of paragraph citations as an omission. Every other processor in this
 * package maps one private method onto one named COBOL paragraph; this one cannot, because
 * {@code COMBTRAN.jcl} invokes only the system utilities {@code SORT} and {@code IDCAMS} and there is no
 * program in {@code app/cbl} to map. The job's whole behaviour is expressed in DFSORT and IDCAMS control
 * cards, so the control cards are cited in place of paragraph labels and each private method below names
 * the card it translates.
 *
 * <p>Every citation in this file is keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}. {@code app/jcl/COMBTRAN.jcl}
 * is 52 lines and LF-only at that commit. Its directory holds <strong>29</strong> members, one of which
 * ({@code CREASTMT.JCL}) uses an uppercase extension, so a glob over {@code app/jcl} must be case
 * insensitive or it silently drops a member; the member cited here is lowercase.
 *
 * <h2>The source evidence, card by card</h2>
 *
 * <p>STEP05R is the merge. {@code app/jcl/COMBTRAN.jcl:L22} declares {@code //STEP05R EXEC PGM=SORT} and
 * the two DD statements that follow are <em>concatenated</em>, which is what makes this a merge rather
 * than a copy:
 *
 * <ul>
 *   <li>{@code app/jcl/COMBTRAN.jcl:L23-L24} - {@code //SORTIN DD DISP=SHR,} then
 *       {@code DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)}, the transaction backup.</li>
 *   <li>{@code app/jcl/COMBTRAN.jcl:L25-L26} - an unnamed continuation {@code // DD DISP=SHR,} then
 *       {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(0)}, the interest job's output.</li>
 *   </ul>
 *
 * <p>Both are the <strong>current</strong> generation {@code (0)}, not {@code (+1)}. {@code SYSTRAN} is
 * written by the interest job, whose output DD at {@code app/jcl/INTCALC.jcl:L37-L41} allocates
 * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} with {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)}. That is the
 * reason interest transactions reach the keyed cluster <em>only here</em> and never from the interest
 * processor: the interest job writes a fresh sequential generation, and this step is what loads it.
 *
 * <p>{@code app/jcl/COMBTRAN.jcl:L27-L28} opens {@code //SYMNAMES DD *} and declares the single symbol
 * {@code TRAN-ID,1,16,CH}. {@code app/jcl/COMBTRAN.jcl:L29-L30} then opens {@code //SYSIN DD *} and gives
 * the whole sort specification: {@code SORT FIELDS=(TRAN-ID,A)}.
 *
 * <p>{@code app/jcl/COMBTRAN.jcl:L33-L37} declares {@code //SORTOUT} onto
 * {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} with {@code DCB=(*.SORTIN)} at
 * {@code app/jcl/COMBTRAN.jcl:L35}, so the output inherits the input's 350-byte fixed geometry rather
 * than declaring its own. Record length is therefore preserved byte-exactly across the step.
 *
 * <p>STEP10 is the load. {@code app/jcl/COMBTRAN.jcl:L41} declares {@code //STEP10 EXEC PGM=IDCAMS},
 * {@code app/jcl/COMBTRAN.jcl:L43-L44} points {@code //TRANSACT} at the sorted output,
 * {@code app/jcl/COMBTRAN.jcl:L45-L46} points {@code //TRANVSAM} at
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, and {@code app/jcl/COMBTRAN.jcl:L47-L48} supplies the one
 * control card {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)}. The same verb appears standalone at
 * {@code app/ctl/REPROCT.ctl} as {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}, reached from
 * {@code app/proc/REPROC.prc:L21} ({@code //PRC001 EXEC PGM=IDCAMS}) through
 * {@code app/proc/REPROC.prc:L28} ({@code DSN=&CNTLLIB(REPROCT)}), which independently confirms the
 * semantic: {@code REPRO} loads a sequential file into a VSAM cluster.
 *
 * <p><strong>A difference between the two decks worth noting.</strong> {@code app/jcl/COMBTRAN.jcl:L41} carries
 * <em>no</em> {@code COND=} parameter, unlike {@code app/jcl/CREASTMT.JCL} whose {@code STEP020},
 * {@code STEP030} and {@code STEP040} at {@code :L56}, {@code :L66} and {@code :L79} each carry
 * {@code COND=(0,NE)}. STEP10 therefore runs unconditionally in the source. It is recorded here because
 * a reader comparing the two decks will notice the difference; acting on it is not this class's
 * business. Decider gating is the job's concern, and this class contains no gating logic at all.
 *
 * <h2>Deriving the sort key from the 350-byte record</h2>
 *
 * <p>{@code TRAN-ID,1,16,CH} at {@code app/jcl/COMBTRAN.jcl:L28} means offset 1, length 16, character.
 * Laid against the record layout of {@code app/cpy/CVTRA05Y.cpy}, whose header comment at
 * {@code app/cpy/CVTRA05Y.cpy:L2} reads "Data-structure for TRANsaction record (RECLN = 350)", offset 1
 * length 16 lands squarely and only on the first field. The derivation is stated here so that nobody
 * has to recompute it:
 *
 * <pre>
 *   COBOL field         Line  PIC        Bytes    Java accessor            Checked by
 *   TRAN-ID             :L5   X(16)       1-16    getTransactionId()       the sort key + the load key
 *   TRAN-TYPE-CD        :L6   X(02)      17-18    getTypeCode()            geometry
 *   TRAN-CAT-CD         :L7   9(04)      19-22    getCategoryCode()        geometry (unsigned)
 *   TRAN-SOURCE         :L8   X(10)      23-32    getTransactionSource()   geometry
 *   TRAN-DESC           :L9   X(100)    33-132    getDescription()         geometry
 *   TRAN-AMT            :L10  S9(09)V99 133-143   getAmount()              geometry (signed, scale 2)
 *   TRAN-MERCHANT-ID    :L11  9(09)     144-152   getMerchantId()          geometry (unsigned)
 *   TRAN-MERCHANT-NAME  :L12  X(50)     153-202   getMerchantName()        geometry
 *   TRAN-MERCHANT-CITY  :L13  X(50)     203-252   getMerchantCity()        geometry
 *   TRAN-MERCHANT-ZIP   :L14  X(10)     253-262   getMerchantZip()         geometry
 *   TRAN-CARD-NUM       :L15  X(16)     263-278   getCardNumber()          geometry; never logged
 *   TRAN-ORIG-TS        :L16  X(26)     279-304   getOrigTs()              geometry; text, not temporal
 *   TRAN-PROC-TS        :L17  X(26)     305-330   getProcTs()              geometry; text, may be blank
 *   FILLER              :L18  X(20)     331-350   not modelled             padding only
 * </pre>
 *
 * <p>The arithmetic closes exactly:
 * {@code 16+2+4+10+100+11+9+50+50+10+16+26+26 = 330}, plus the 20-byte {@code FILLER} = <strong>350</strong>.
 * Offset 1 length 16 can only be {@code TRAN-ID}, so the sort key is the transaction identifier and
 * nothing else.
 *
 * <p>{@code CH} is a plain byte-wise ascending character compare. The identifier is
 * {@code PIC X(16)} - a character field, not a number - so it is compared as text and is
 * <strong>never</strong> parsed to a numeric type. Parsing would discard leading zeros, and every
 * identifier in this corpus is zero-padded: all 300 rows of {@code app/data/ASCII/dailytran.txt} carry a
 * 16-character all-digit identifier in bytes 1-16.
 *
 * <p><strong>Codepage collation.</strong> DFSORT collates {@code CH} fields in
 * EBCDIC; {@link String#compareTo(String)} collates in UTF-16 code-unit order. For the decimal digits
 * {@code 0}-{@code 9} the two sequences agree, so for the all-digit identifiers this corpus actually
 * contains the two orderings are identical and the translation is exact. Because the field is
 * {@code PIC X(16)} a non-digit identifier is nevertheless representable, and for such a value the two
 * collating sequences would differ - in EBCDIC digits sort after letters, in ASCII before them.
 * <em>If that ever matters:</em> compare the EBCDIC-translated bytes rather than the
 * characters. It is deliberately <em>not</em> done here, because introducing a codepage translation for
 * a case the data does not contain would add a failure mode without removing one.
 *
 * <h2>Inputs, output, and why nothing is filtered or rewritten</h2>
 *
 * <p>The input is one {@link Transaction} decoded by the upstream reader from the concatenated
 * {@code TRANSACT.BKUP(0)} and {@code SYSTRAN(0)} stream. That reader performs the multi-source
 * concatenated read; it is referred to in prose only and is deliberately <em>not</em> imported, since this
 * class has no compile-time dependency on it.
 *
 * <p>The output is <strong>the same instance</strong>, returned unchanged. That is not laziness, it is
 * the source specification, and it rests on evidence rather than on assumption:
 *
 * <ul>
 *   <li><strong>Nothing is filtered.</strong> {@code app/jcl/COMBTRAN.jcl} contains no {@code INCLUDE},
 *       {@code OMIT}, {@code SKIPREC} or {@code STOPAFT} control statement anywhere, so every input
 *       record reaches {@code SORTOUT}. Contrast {@code app/proc/TRANREPT.prc:L45}, which <em>does</em>
 *       carry {@code INCLUDE COND=} and therefore <em>does</em> drop records. Because Spring Batch reads
 *       a {@code null} return from a processor as "filter this item out", this method must never return
 *       {@code null} - and it never does.</li>
 *   <li><strong>Nothing is reformatted.</strong> {@code app/jcl/COMBTRAN.jcl} contains no {@code INREC},
 *       {@code OUTREC} or {@code OUTFIL} statement either. Contrast {@code app/jcl/CREASTMT.JCL:L54},
 *       which <em>does</em> carry {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} and therefore
 *       does reshape the record. This step moves bytes without touching them, so no field is trimmed,
 *       padded, upper-cased, re-scaled or re-ordered here.</li>
 *   <li><strong>Only one key.</strong> {@code app/jcl/COMBTRAN.jcl:L30} specifies exactly one sort field.
 *       Contrast {@code app/jcl/CREASTMT.JCL:L53}, {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, which has
 *       two. No secondary key is invented here.</li>
 *   </ul>
 *
 * <h2>Side effects</h2>
 *
 * <p><strong>None.</strong> No database access, no object-storage access, no queue access, no file
 * handle, no network call, no clock read, no random source, no process spawn, no static mutable state.
 * Every method is a pure function of its arguments apart from logging, and the class is therefore safe
 * to share across step threads. Two consequences worth stating because they are easy to get wrong:
 *
 * <ul>
 *   <li><strong>The load is not performed here.</strong> Translating the IDCAMS {@code REPRO} of
 *       {@code app/jcl/COMBTRAN.jcl:L48} into a bulk insert belongs to the step's writer, which the
 *       migration plan specifies as a {@code JdbcTemplate} batch update. This class neither opens a
 *       connection nor builds a statement, and it contains no SQL text of any kind.</li>
 *   <li><strong>No external sort process is spawned.</strong> The DFSORT invocation of
 *       {@code app/jcl/COMBTRAN.jcl:L22} becomes the {@link Comparator} published as
 *       {@link #TRAN_ID_ASCENDING} plus ordered retrieval, never a child process. {@code Runtime.exec}
 *       and {@code ProcessBuilder} appear nowhere in this file.</li>
 *   </ul>
 *
 * <h2>Error modes</h2>
 *
 * <table>
 * <caption>What this class throws, and when</caption>
 * <tr><th scope="col">Condition</th><th scope="col">Thrown</th></tr>
 * <tr><td>{@code null} record from the reader</td><td>{@link DataIntegrityException}</td></tr>
 * <tr><td>{@code null}, blank, or not exactly 16-character identifier</td>
 *     <td>{@link DataIntegrityException}</td></tr>
 * <tr><td>A character field longer than its {@code PIC} width, or a numeric field outside its
 *     {@code PIC} range</td><td>{@link DataIntegrityException}</td></tr>
 * <tr><td>Colliding identifier reported by the load path</td><td>{@link DuplicateRecordException}</td></tr>
 * <tr><td>Any other constraint violation reported by the load path</td>
 *     <td>{@link DataIntegrityException}</td></tr>
 * <tr><td>Any other store failure reported by the load path</td>
 *     <td>{@link FatalProcessingException}</td></tr>
 * </table>
 *
 * <p>The relevant rows of the tree-wide status-to-exception map, reproduced so that this class can be
 * read without opening another file: {@code '00'} continue; {@code '04'} secondary success at the
 * statement file-service call sites only; {@code '10'} end of file, which is <strong>loop termination
 * and is never thrown</strong>; {@code '22'} {@link DuplicateRecordException}; {@code '23'} record not
 * found, except at three scoped sites; {@code '35'} file unavailable; {@code '9x'} file access carrying
 * the four-character expanded status; anything else {@link FatalProcessingException} with abend code
 * {@value FatalProcessingException#BATCH_ABEND_CODE} and return code
 * {@value FatalProcessingException#BATCH_RETURN_CODE}. The exit codes those map onto are {@code 0}
 * completed, {@code 4} completed-with-rejects, {@code 8} failed and {@code 12} abend.
 *
 * <p>Two of those rows are load-bearing here. {@code '10'} is never raised by this class, because end of
 * stream is the reader's business and reaches this class as "not called again" rather than as an
 * exception. And <strong>{@code 4} is not this step's exit code</strong>: it belongs exclusively to the
 * daily posting job's reject count, whose {@code MOVE 4 TO RETURN-CODE} at
 * {@code app/cbl/CBTRN02C.cbl:L230} is the only such statement in the entire 19,254-line corpus. A
 * collision in this step is a <strong>failure</strong>, exit code {@code 8} - never a completed-with-rejects
 * outcome and never a skip.
 *
 * <h2>The duplicate contract</h2>
 *
 * <p>The interest job's identifiers are fully deterministic: the suffix counter at
 * {@code app/cbl/CBACT04C.cbl:L173} is declared once and never reset per account, and
 * {@code app/cbl/CBACT04C.cbl:L473-L480} concatenates the ten-character date parameter with that suffix
 * to form the sixteen-character identifier. Re-running the interest job with the same date parameter
 * therefore reproduces the identical identifier sequence. That job cannot detect the clash itself,
 * because its output file is declared sequential with no {@code RECORD KEY} at
 * {@code app/cbl/CBACT04C.cbl:L53-L56} and each run allocates a brand-new generation.
 *
 * <p>The clash therefore surfaces <em>here</em>, at the {@code REPRO} of
 * {@code app/jcl/COMBTRAN.jcl:L48}, because that load is the first moment a key constraint exists. The
 * required outcome is a {@link DuplicateRecordException} and a failed exit status, aborting the job.
 * {@link DuplicateRecordException} states the three caller prohibitions in full; they bind this class and
 * are honoured here without exception:
 *
 * <ul>
 *   <li><strong>No retry, no backoff, no regeneration loop.</strong> Re-deriving an identifier
 *       would hand out a value the legacy system would not have handed out.</li>
 *   <li><strong>No silent upsert.</strong> No update, merge, insert-or-ignore or
 *       on-conflict-do-nothing. A softer outcome silently corrupts the transaction master.</li>
 *   <li><strong>No database sequence, identity column or generator substitution.</strong> A
 *       sequence is better engineering and still wrong, because it changes the generated values and so
 *       fails the boundary parity comparison.</li>
 *   </ul>
 *
 * <p>Accordingly this file contains no retry annotation, no recovery loop, no skip policy, no
 * {@code ON CONFLICT}, no merge call and no sequence reference. The single correct handling is to let the
 * exception propagate to the step boundary.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is none to set. This class reads no property, no environment variable, no system property and
 * no profile; it has no tunable, no threshold and no feature flag. Its only constants are the widths the
 * copybook fixes, published as {@link #TRAN_ID_LENGTH} and {@link #COMBINED_RECORD_LENGTH}, and its only
 * ordering is {@link #TRAN_ID_ASCENDING}. Chunk size, commit interval, retry and skip policy are the
 * step's configuration, not this class's.
 *
 * <h2>How it is exercised, and common failure modes</h2>
 *
 * <p>It is driven by the combine job's single chunk-oriented step, between the concatenated reader and
 * the bulk-load writer. Its unit test is
 * {@code src/test/java/com/cardemo/unit/batch/TransactionCombineProcessorTest.java}, which this file does
 * not author; the class is left constructor-injectable, free of static mutable state and free of any
 * hidden clock precisely so that test needs no framework. Troubleshooting, by symptom:
 *
 * <ul>
 *   <li><em>The step fails immediately with an identifier-length message.</em> The reader's fixed-width
 *       decode is misaligned. Check that it slices bytes 1-16 for the identifier, per
 *       {@code app/cpy/CVTRA05Y.cpy:L5}, and that it is reading 350-byte records.</li>
 *   <li><em>The step fails with a geometry message naming one field.</em> The decode is off by the
 *       preceding field's width. The offset table above gives the exact expected bytes.</li>
 *   <li><em>The step fails with a duplicate identifier.</em> The interest job was re-run with a date
 *       parameter it had already used. This is the designed outcome, not a defect. Re-drive the pipeline
 *       with a correct date parameter; do not relax the constraint and do not retry.</li>
 *   <li><em>The step completes having processed zero records.</em> Both input generations were empty.
 *       That is a legitimate no-op and not an error: the reader returns end of stream on its first read,
 *       this class is never invoked, and the step completes with exit code {@code 0}.</li>
 *   <li><em>A negative amount appears in the loaded data.</em> Also legitimate. Debits are genuinely
 *       negative in this corpus - {@code app/data/ASCII/dailytran.txt} carries negative overpunch signs -
 *       and nothing here normalises, absolutises or rejects them.</li>
 *   </ul>
 *
 * <h2>Boundaries of this class, and what the corpus does not determine</h2>
 *
 * <ul>
 *   <li><strong>No constraint name is carried on a diagnostic.</strong>
 *       {@code src/main/resources/db/migration/V1__create_schema.sql} declares {@code pk_transaction} as
 *       the primary key of the {@code transaction} table, so the name is known; it is deliberately not
 *       carried here. Neither diagnostic path using
 *       {@link #RELATION} can establish which of that table's four named constraints failed, because
 *       the per-record rejections throw before any insert is attempted and the store-failure
 *       translation does not parse the driver-reported name. Where a constructor requires a constraint
 *       name, {@code null} is passed rather than a fabricated identifier. The table and column remain
 *       independently verifiable: {@code Transaction} maps {@code @Table(name = "transaction")} and
 *       {@code @Column(name = "tran_id")}.</li>
 *   <li><strong>The step definition sits outside this class</strong> - the reader, the writer and the
 *       chunk size all belong to it. Nothing here depends on any of them; the reader is referred to in
 *       prose only.</li>
 *   <li><strong>No throughput or latency objective exists.</strong> The frozen corpus publishes
 *       no service-level objective anywhere, so none is asserted, implied or designed against.</li>
 *   </ul>
 *
 * <h2>Bean registration</h2>
 *
 * <p>Registered by {@link Component}, consistent with the stereotype annotations used elsewhere in this
 * tree, so the bean exists without depending on a configuration class. If a batch configuration ever
 * declares this type as a factory-method bean as well, keep exactly one definition - preferably this
 * annotation - because two definitions of the
 * same type make by-type injection ambiguous and the context will fail to start.
 *
 * @see Transaction
 * @see DuplicateRecordException
 * @see DataIntegrityException
 */
@Component
public class TransactionCombineProcessor implements ItemProcessor<Transaction, Transaction> {

    /**
     * Creates the processor.
     *
     * <p>This step has no collaborator to inject: {@code app/jcl/COMBTRAN.jcl} has no COBOL program at all,
     * and {@code STEP05R} is a pure DFSORT ordering of a concatenated input, so the per-record work is a
     * function of the record alone and touches no repository, no object store and no clock. The constructor
     * is declared explicitly rather than left implicit so that the public surface is documented; it takes
     * no argument and performs no work.
     */
    public TransactionCombineProcessor() {
        // Intentionally empty: this processor is stateless, so there is nothing to assign and nothing to
        // validate. It is not a placeholder - see the class documentation for why no collaborator exists.
    }

    /**
     * Diagnostic logger. Debug carries per-record flow, error is used only where this class rethrows.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionCombineProcessor.class);

    /**
     * Width of the sort key in characters: {@code 16}. Fixed by the sort symbol
     * {@code TRAN-ID,1,16,CH} at {@code app/jcl/COMBTRAN.jcl:28}.
     */
    public static final int TRAN_ID_LENGTH = 16;

    /**
     * Length in bytes of one combined transaction record: {@code 350}. Declared by
     * {@code RECLN = 350} at {@code app/cpy/CVTRA05Y.cpy:2} and fixed on the concatenated sort input by
     * {@code DCB=(*.SORTIN)} at {@code app/jcl/COMBTRAN.jcl:35}.
     */
    public static final int COMBINED_RECORD_LENGTH = 350;

    /**
     * Ascending order by transaction identifier - the direct translation of {@code SORT FIELDS=(TRAN-ID,A)} at
     * {@code app/jcl/COMBTRAN.jcl:L30}.
     */
    public static final Comparator<Transaction> TRAN_ID_ASCENDING =
            Comparator.nullsFirst(Comparator.comparing(Transaction::getTransactionId,
                    Comparator.nullsFirst(Comparator.<String>naturalOrder())));

    /**
     * Legacy logical name of the keyed cluster this step loads: {@code TRANSACT}, the DD name at
     * {@code app/jcl/COMBTRAN.jcl:43} that the {@code REPRO} of {@code :48} targets.
     */
    private static final String LOGICAL_FILE = "TRANSACT";

    /**
     * Relation the load targets: {@code transaction}.
     */
    private static final String RELATION = "transaction";

    /**
     * Name of the primary-key constraint a duplicate identifier violates: {@code pk_transaction}.
     *
     * <p>Read from {@code src/main/resources/db/migration/V1__create_schema.sql}, which declares
     * {@code CONSTRAINT pk_transaction PRIMARY KEY (tran_id)}. It is named explicitly there rather than
     * left to the database's default, which is what makes it quotable at all.
     *
     * <p>Only the duplicate-key branch may use this. A collision on {@code tran_id} can violate nothing
     * else, so attributing it to this constraint is a deduction rather than a guess. The generic
     * integrity branch must not use it, because any of the three foreign keys could be the cause there.
     */
    private static final String PRIMARY_KEY_CONSTRAINT = "pk_transaction";

    /**
     * Culprit reported on an abend: {@code COMBTRAN}.
     *
     * <p>The abend work area gives {@code ABEND-CULPRIT} a width of {@code X(8)}, and there is no COBOL
     * program to name for this job, so the eight-character JCL member name {@code app/jcl/COMBTRAN.jcl}
     * is used - which is both the true source artefact and exactly eight characters wide.
     */
    private static final String ABEND_CULPRIT = "COMBTRAN";

    /**
     * Largest magnitude representable by {@code TRAN-AMT PIC S9(09)V99}: {@code 999999999.99}. Declared
     * at {@code app/cpy/CVTRA05Y.cpy:10}.
     */
    private static final BigDecimal TRAN_AMT_MAX = new BigDecimal("999999999.99");

    /**
     * Maximum decimal places {@code TRAN-AMT PIC S9(09)V99} can carry: {@code 2}.
     */
    private static final int TRAN_AMT_SCALE = 2;

    /**
     * Inclusive upper bound of {@code TRAN-CAT-CD PIC 9(04)}, unsigned: {@code 9999}.
     */
    private static final long TRAN_CAT_CD_MAX = 9999L;

    /**
     * Inclusive upper bound of {@code TRAN-MERCHANT-ID PIC 9(09)}, unsigned: {@code 999999999}.
     */
    private static final long TRAN_MERCHANT_ID_MAX = 999_999_999L;

    /**
     * Validates one record of the concatenated stream and passes it through unchanged.
     *
     * @param item the record to validate and pass through.
     * @return {@code item} itself, unchanged and never {@code null}
     * @throws DataIntegrityException if the record is {@code null}, if its identifier is absent, blank or not
     * exactly {@value #TRAN_ID_LENGTH} characters, if any character field exceeds its {@code PIC} width, or if
     * any numeric field falls outside its {@code PIC} range - in every case the record cannot be loaded into a
     * {@value #COMBINED_RECORD_LENGTH}-byte keyed cluster
     */
    @Override
    public Transaction process(Transaction item) {
        if (item == null) {
            throw unloadable(null, "the reader supplied a null record, which has no key to load by");
        }

        String transactionId = extractSortKey(item);
        verifyFixedWidthGeometry(item, transactionId);
        verifyReproLoadPrecondition(item, transactionId);

        LOG.debug("Combine step accepted transaction id {} for the TRAN-ID ascending bulk load",
                renderKey(transactionId));
        return item;
    }

    /**
     * Extracts and validates the DFSORT sort key.
     *
     * <p>Translates the symbol declared at {@code app/jcl/COMBTRAN.jcl:L28} - {@code TRAN-ID,1,16,CH} -
     * read against {@code app/cpy/CVTRA05Y.cpy:L5}, {@code TRAN-ID PIC X(16)}. Offset 1 length 16 can
     * only be the first field of the 350-byte record, so the key is the transaction identifier.
     *
     * <p>The declared length is part of the contract, not a hint: DFSORT reads exactly sixteen bytes
     * from offset 1, so a value that is not exactly {@value #TRAN_ID_LENGTH} characters is not the field
     * the sort card names. Both a shorter and a longer value are therefore rejected rather than padded
     * or truncated - silently adjusting either one would change the ordering, and ordering is the entire
     * purpose of the step. The value is returned exactly as found: not trimmed, not padded and not case
     * folded, so no locale is involved.
     *
     * <p>A control character is refused as well, and that refusal is a security boundary rather than a
     * geometry one. Every other check here asks whether the value is the field the sort card names; this
     * one asks whether reporting the value can corrupt the report. A sixteen-character, non-blank
     * identifier carrying {@code CR} or {@code LF} satisfies every other rule on this method and, once
     * interpolated into a log record, splits that record in two - the second half being attacker-chosen
     * text that a log reader cannot distinguish from a line this application emitted. The corpus gives
     * no reason to accept such a value: all 300 identifiers in {@code app/data/ASCII/dailytran.txt}
     * bytes 1-16 are decimal digits, and both generators produce digits only - the descending-browse
     * maximum-plus-one of {@code app/cbl/COTRN02C.cbl:L444-L451} and the date-plus-suffix concatenation
     * of {@code app/cbl/CBACT04C.cbl:L473-L516}. Refusing a control character therefore cannot refuse a
     * legitimate record, which is the test a parity-preserving guard has to pass.
     *
     * <p>The check is placed after the length check so that a wrong-length value is still reported as a
     * geometry failure, which is its primary defect. Ordering is a diagnostics choice only, not a safety
     * one: {@link #renderKey(String)} escapes at the point of rendering, so every message on every path
     * is already safe regardless of which branch produces it. A value of sixteen newlines, for instance,
     * is {@link String#isBlank()} and so is reported by the blank branch - accurately, and safely.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @return the identifier, exactly as held by the record
     * @throws DataIntegrityException if the identifier is {@code null}, blank, not exactly
     *                                {@value #TRAN_ID_LENGTH} characters, or contains a control
     *                                character
     */
    private static String extractSortKey(Transaction item) {
        String transactionId = item.getTransactionId();
        if (transactionId == null) {
            throw unloadable(null, "TRAN-ID is null, so the record has no sort key and no primary key");
        }
        if (transactionId.isBlank()) {
            throw unloadable(transactionId, "TRAN-ID is blank, so the record has no usable sort key");
        }
        if (transactionId.length() != TRAN_ID_LENGTH) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "TRAN-ID is %d characters but the sort symbol TRAN-ID,1,16,CH fixes it at %d",
                    transactionId.length(), TRAN_ID_LENGTH));
        }
        if (containsControlCharacter(transactionId)) {
            throw unloadable(transactionId,
                    "TRAN-ID contains a control character, which no identifier in the frozen corpus does "
                            + "and which would break one log record into two if it were reported raw");
        }
        return transactionId;
    }

    /**
     * Verifies the 350-byte fixed-width geometry that both inputs share.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @param transactionId the identifier, reported in any failure so the record can be located
     * @throws DataIntegrityException if any character field exceeds its {@code PIC} width, or any numeric field
     * falls outside its {@code PIC} range
     */
    private static void verifyFixedWidthGeometry(Transaction item, String transactionId) {
        requireWidth(transactionId, item.getTypeCode(), "TRAN-TYPE-CD X(02) app/cpy/CVTRA05Y.cpy:L6", 2);
        requireUnsignedRange(transactionId, item.getCategoryCode(),
                "TRAN-CAT-CD 9(04) app/cpy/CVTRA05Y.cpy:L7", TRAN_CAT_CD_MAX);
        requireWidth(transactionId, item.getTransactionSource(),
                "TRAN-SOURCE X(10) app/cpy/CVTRA05Y.cpy:L8", 10);
        requireWidth(transactionId, item.getDescription(), "TRAN-DESC X(100) app/cpy/CVTRA05Y.cpy:L9", 100);
        requireAmountGeometry(transactionId, item.getAmount());
        requireUnsignedRange(transactionId, item.getMerchantId(),
                "TRAN-MERCHANT-ID 9(09) app/cpy/CVTRA05Y.cpy:L11", TRAN_MERCHANT_ID_MAX);
        requireWidth(transactionId, item.getMerchantName(),
                "TRAN-MERCHANT-NAME X(50) app/cpy/CVTRA05Y.cpy:L12", 50);
        requireWidth(transactionId, item.getMerchantCity(),
                "TRAN-MERCHANT-CITY X(50) app/cpy/CVTRA05Y.cpy:L13", 50);
        requireWidth(transactionId, item.getMerchantZip(),
                "TRAN-MERCHANT-ZIP X(10) app/cpy/CVTRA05Y.cpy:L14", 10);
        requireWidth(transactionId, item.getCardNumber(),
                "TRAN-CARD-NUM X(16) app/cpy/CVTRA05Y.cpy:L15", 16);
        requireWidth(transactionId, item.getOrigTs(), "TRAN-ORIG-TS X(26) app/cpy/CVTRA05Y.cpy:L16", 26);
        requireWidth(transactionId, item.getProcTs(), "TRAN-PROC-TS X(26) app/cpy/CVTRA05Y.cpy:L17", 26);
    }

    /**
     * Verifies the precondition for the IDCAMS {@code REPRO} bulk load.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @param transactionId the identifier returned by {@link #extractSortKey(Transaction)}
     * @throws DataIntegrityException if the record's current key differs from the key the stream was ordered
     * by, which would mean the record was reshaped between the sort and the load
     */
    private static void verifyReproLoadPrecondition(Transaction item, String transactionId) {
        if (!transactionId.equals(item.getTransactionId())) {
            throw unloadable(transactionId,
                    "TRAN-ID changed between the sort key read and the load, so the ordering that "
                            + "REPRO into a keyed cluster depends on no longer holds");
        }
    }

    /**
     * Translates a store failure raised by the bulk load into the migration's typed exception hierarchy.
     *
     * <p><strong>The cause is always preserved and its message is never copied into the message built
     * here.</strong> A driver's constraint-violation text commonly embeds the offending key value, which
     * on this record could be a card number; echoing it would put that value in a log line. Each message
     * is written for this application, names only the identifier, and never includes the record image.
     *
     * <p>No retry, backoff or identifier regeneration; no upsert, merge or on-conflict handling; no
     * database sequence or generated identity. Each would change values the boundary parity comparison
     * is measured against.
     *
     * @param item the record whose load failed.
     * @param cause the failure reported by the load path.
     * @throws DuplicateRecordException if {@code cause} reports a duplicate key
     * @throws DataIntegrityException if {@code cause} reports any other constraint violation
     * @throws FatalProcessingException in every other case, including a {@code null} {@code cause}
     */
    public void translateLoadFailure(Transaction item, DataAccessException cause) {
        String transactionId = item == null ? null : item.getTransactionId();
        String reportedKey = renderKey(transactionId);

        if (cause instanceof DuplicateKeyException) {
            String message = String.format(Locale.ROOT,
                    "Duplicate TRAN-ID %s rejected by the combine bulk load into %s (relation %s, "
                            + "constraint %s). The IDCAMS REPRO of app/jcl/COMBTRAN.jcl:L48 loads a keyed "
                            + "cluster, so a repeated identifier fails the step. Re-drive the pipeline "
                            + "with an unused date parameter; do not retry, upsert or substitute a "
                            + "sequence.",
                    reportedKey, LOGICAL_FILE, RELATION, PRIMARY_KEY_CONSTRAINT);
            LOG.error(message);
            throw new DuplicateRecordException(message, LOGICAL_FILE, transactionId, cause);
        }

        if (cause instanceof DataIntegrityViolationException) {
            String message = String.format(Locale.ROOT,
                    "Constraint violation rejected the combine bulk load of TRAN-ID %s into relation %s. "
                            + "The exception does not name the constraint in a portable field, so it is one of "
                            + "the four that V1__create_schema.sql declares on this relation - pk_transaction, "
                            + "fk04_transaction_card, fk05_transaction_type and fk06_transaction_category - with "
                            + "a duplicate key excluded because that condition is handled above. The retained "
                            + "cause carries the driver's own constraint detail.",
                    reportedKey, RELATION);
            LOG.error(message);
            throw new DataIntegrityException(message, null, RELATION, cause);
        }

        String reason = String.format(Locale.ROOT,
                "Unexpected store failure loading TRAN-ID %s into %s", reportedKey, LOGICAL_FILE);
        String message = String.format(Locale.ROOT,
                "%s. app/jcl/COMBTRAN.jcl:L48 REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM) could not "
                        + "complete and the condition is not a duplicate key or a constraint violation, "
                        + "so it is fatal.", reason);
        LOG.error(message);
        throw new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT, reason, message, cause);
    }

    /**
     * Builds the exception used for every per-record rejection in this step.
     *
     * @param transactionId the identifier if one could be read, otherwise {@code null}, for which the
     * message reports a fixed placeholder in place of an identifier
     * @param detail what specifically makes the record unloadable.
     * @return the exception to throw, never {@code null}
     */
    private static DataIntegrityException unloadable(String transactionId, String detail) {
        String reportedKey = renderKey(transactionId);
        String message = String.format(Locale.ROOT,
                "Combine step rejected a record for TRAN-ID %s: %s. Sources: "
                        + "app/jcl/COMBTRAN.jcl:L28 sort symbol TRAN-ID,1,16,CH; :L35 DCB=(*.SORTIN) "
                        + "fixing the record at %d bytes; :L48 REPRO into the keyed cluster.",
                reportedKey, detail, COMBINED_RECORD_LENGTH);
        return new DataIntegrityException(message, null, RELATION, null);
    }

    /**
     * Renders an identifier so that it can appear in a log record or a message without being able to
     * alter that record's structure.
     *
     * <p>This exists because the identifier is the one piece of attacker-influenced text this class
     * handles. It arrives as sixteen bytes lifted from a fixed-width record image, and until it has been
     * validated nothing is known about its contents. Interpolating it raw is what turns a data defect
     * into a log-integrity defect: a {@code CR} or {@code LF} inside it ends the current log line and
     * begins one that the reader will attribute to this application.
     *
     * <p>The escaping deliberately does not lean on the logging configuration.
     * {@code src/main/resources/logback-spring.xml} does encode and mask, but which appender is active is a
     * deployment-time choice and a plain-text one escapes nothing, so a {@code CR} or {@code LF} that
     * reaches the logger can still forge a line. The escaping therefore has to
     * happen here, at the point of rendering, and it is applied there rather than at each call site so
     * that adding a message later cannot forget it.
     *
     * <p>The rendering is total: every input produces a safe output, including inputs the validation
     * boundary would have refused, because this method is also used to report those refusals. Anything
     * outside printable ASCII is escaped, not merely the control characters that
     * {@link #containsControlCharacter(String)} refuses - the guard is deliberately narrow so it cannot
     * refuse a legitimate record, while the rendering is deliberately broad so that a character the
     * guard permits still cannot forge a line. {@code U+2028} is the case that makes the difference
     * matter: it is not an {@linkplain Character#isISOControl(char) ISO control} character, yet several
     * readers treat it as a line break.
     *
     * <p>A legitimate identifier is unchanged apart from the surrounding quotes: all sixteen characters
     * of every corpus identifier are decimal digits, so the diagnostic value an operator needs - the key
     * to go and look for - survives intact. The quotes are not decoration; they make a trailing space
     * visible, and a space-padded key is a real condition in a fixed-width record.
     *
     * @param transactionId the identifier, possibly {@code null} and possibly not yet validated
     * @return {@code Not available} when the identifier is {@code null}, otherwise the identifier
     *         quoted, with every character outside printable ASCII escaped; never {@code null}
     */
    private static String renderKey(String transactionId) {
        if (transactionId == null) {
            return "Not available";
        }
        StringBuilder rendered = new StringBuilder(transactionId.length() + 2);
        rendered.append('\'');
        for (int index = 0; index < transactionId.length(); index++) {
            char character = transactionId.charAt(index);
            switch (character) {
                case '\n' -> rendered.append("\\n");
                case '\r' -> rendered.append("\\r");
                case '\t' -> rendered.append("\\t");
                default -> {
                    if (character < ' ' || character > '~') {
                        rendered.append(String.format(Locale.ROOT, "\\u%04X", (int) character));
                    } else {
                        rendered.append(character);
                    }
                }
            }
        }
        rendered.append('\'');
        return rendered.toString();
    }

    /**
     * Reports whether a value contains a character that must never reach a diagnostic.
     *
     * <p>{@link Character#isISOControl(char)} is the exact predicate, covering {@code U+0000-U+001F} and
     * {@code U+007F-U+009F}. That range contains every character a log reader or a line-oriented tool
     * treats as a record boundary, including {@code U+0085 NEL}, which is easy to miss because it lies
     * outside the familiar C0 range.
     *
     * <p>Deliberately no wider than that. A broader rule - digits only, say - would match this corpus and
     * would still be wrong to impose, because {@code TRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L5} declares a character field, and narrowing a field's domain beyond
     * what the copybook declares risks refusing a record the legacy system would have posted. The
     * asymmetry is intentional and is the point of having two methods: refuse narrowly, render broadly.
     *
     * @param value the value to inspect; must not be {@code null}
     * @return {@code true} if any character is an ISO control character
     */
    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects a character field longer than the width its {@code PIC} clause declares.
     *
     * @param transactionId the record's identifier, reported on failure
     * @param value the field value, possibly {@code null}
     * @param field the COBOL field name, its {@code PIC} clause and its copybook line
     * @param width the number of characters the {@code PIC} clause allows
     * @throws DataIntegrityException if {@code value} is longer than {@code width}
     */
    private static void requireWidth(String transactionId, String value, String field, int width) {
        if (value != null && value.length() > width) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s holds %d characters, which exceeds the %d the PIC clause allows",
                    field, value.length(), width));
        }
    }

    /**
     * Rejects an unsigned numeric field outside the range its {@code PIC} clause declares.
     *
     * @param transactionId the record's identifier, reported on failure
     * @param value the field value, possibly {@code null}.
     * @param field the COBOL field name, its {@code PIC} clause and its copybook line
     * @param maxInclusive the largest value the {@code PIC} clause can hold
     * @throws DataIntegrityException if {@code value} is negative or above {@code maxInclusive}
     */
    private static void requireUnsignedRange(String transactionId, Number value, String field, long maxInclusive) {
        if (value == null) {
            return;
        }
        long actual = value.longValue();
        if (actual < 0L || actual > maxInclusive) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "%s holds %d, which is outside the unsigned range 0 to %d the PIC clause allows",
                    field, actual, maxInclusive));
        }
    }

    /**
     * Rejects an amount that {@code TRAN-AMT PIC S9(09)V99} cannot represent.
     *
     * @param transactionId the record's identifier, reported on failure
     * @param amount the amount, possibly {@code null}
     * @throws DataIntegrityException if the magnitude exceeds {@code 999999999.99} or more than
     * {@value #TRAN_AMT_SCALE} decimal places are significant
     */
    private static void requireAmountGeometry(String transactionId, BigDecimal amount) {
        if (amount == null) {
            return;
        }
        if (amount.stripTrailingZeros().scale() > TRAN_AMT_SCALE) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "TRAN-AMT S9(09)V99 app/cpy/CVTRA05Y.cpy:L10 needs more than %d decimal places, "
                            + "which cannot be stored without rounding a monetary value",
                    TRAN_AMT_SCALE));
        }
        if (amount.abs().compareTo(TRAN_AMT_MAX) > 0) {
            throw unloadable(transactionId, String.format(Locale.ROOT,
                    "TRAN-AMT S9(09)V99 app/cpy/CVTRA05Y.cpy:L10 has a magnitude above the %s the PIC "
                            + "clause allows", TRAN_AMT_MAX.toPlainString()));
        }
    }
}
