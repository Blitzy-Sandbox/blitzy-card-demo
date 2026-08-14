/*
 * ******************************************************************
 * Program     : FileStatusMapper.java
 * Application : CardDemo
 * Type        : Spring @Component (shared service)
 * Function    : Central COBOL FILE STATUS to typed-exception translation
 * Source      : app/cbl/CBTRN02C.cbl (731 lines) universal FILE STATUS
 *               guard idiom + app/cpy/CSMSG02Y.cpy (CABENDD.CPY abend
 *               work areas) @ 7756d89
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
package com.cardemo.service.shared;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The one place in the codebase where a COBOL {@code FILE STATUS} value becomes a typed
 * {@code com.cardemo.exception} subtype.
 *
 * <h2>What it does</h2>
 *
 * <p>Every {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE} and {@code CLOSE} in the CardDemo
 * batch corpus is wrapped in one repeated shape: a status is inspected, a result field is set, and the
 * program either continues or displays a message, renders the status and abends. That shape appears at
 * roughly nineteen sites in {@code app/cbl/CBTRN02C.cbl} alone and is invoked seventy eight times across
 * the corpus. It is <strong>one idiom, not hundreds of independent checks</strong>, which is the entire
 * justification for translating it once, here, rather than at each call site.
 *
 * <p>This component owns exactly one concern: <strong>deciding</strong> which exception a status maps to.
 * It is deliberately the only place that decision is made. The neighbouring concerns belong elsewhere and
 * are not duplicated here:
 *
 * <ul>
 *   <li><strong>Classifying</strong> a raw two character value into a status family belongs to
 *       {@code com.cardemo.model.enums.FileStatus}. That type classifies; it does not decide.</li>
 *   <li>The twenty character display literal {@code FILE STATUS IS: NNNN} is declared exactly once, as
 *       {@code FileStatus.DISPLAY_MESSAGE_PREFIX}. <strong>It is referenced here and never
 *       redeclared.</strong></li>
 *   <li>The four character {@code IO-STATUS-04} rendering is computed exactly once, by
 *       {@code FileStatus.renderIoStatus04(String)}. <strong>This class delegates to it and contains no
 *       second implementation of it.</strong> That method already applies the mandatory low order byte
 *       mask and passes {@code java.util.Locale.ROOT} explicitly, so re-deriving either here would be
 *       duplication of the kind Rule 1 Clause C forbids.</li>
 *   <li>Emitting a log record belongs to the caller. This class never logs, never registers a metric,
 *       never touches a repository, reads no configuration and performs no input or output.</li>
 *   </ul>
 *
 * <p>What this class does add, and what {@code FileStatus} deliberately leaves undone, is the assembly of
 * the legacy diagnostic line from those two owned parts - see {@link #displayIoStatus(String)}. Assembly
 * is concatenation, not a second rendering.
 *
 * <p>Dependency direction is strictly {@code exception} then {@code model.enums}, never the reverse. This
 * class depends on both and nothing depends on this class in either direction.
 *
 * <h2>The status to exception map</h2>
 *
 * <ul>
 *   <li>{@code '00'} success - returns normally, throws nothing.</li>
 *   <li>{@code '04'} secondary success - accepted <strong>only</strong> through
 *       {@link #requireFileServiceSuccess(String, String, String)} and
 *       {@link #requireFileServiceSuccessOrEndOfFile(String, String, String)}. It is
 *       <strong>not</strong> in the general map and is fatal on every general path.</li>
 *   <li>{@code '10'} end of file - <strong>loop termination, not an error.</strong> Never mapped to an
 *       exception by {@link #toException(String, String, String)}, and classified as
 *       {@value #APPL_EOF} rather than as a failure by
 *       {@link #applResultForSequentialRead(String)}.</li>
 *   <li>{@code '22'} duplicate key - {@code com.cardemo.exception.DuplicateRecordException}.</li>
 *   <li>{@code '23'} record not found - {@code com.cardemo.exception.RecordNotFoundException}, except at
 *       the three scoped sites described below.</li>
 *   <li>{@code '35'} file unavailable - {@code com.cardemo.exception.FileUnavailableException}.</li>
 *   <li>{@code '9x'} physical or logical I/O error - {@code com.cardemo.exception.FileAccessException},
 *       carrying the four character expansion.</li>
 *   <li>anything else - {@code com.cardemo.exception.FatalProcessingException}, abend code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.</li>
 *   </ul>
 *
 * <h2>The store failure map, which is the other direction of the same decision</h2>
 *
 * <p>The map above answers "which exception does this {@code FILE STATUS} mean". A relational store does not
 * report a {@code FILE STATUS}, so the target needs the question asked the other way round as well: "which
 * condition of the guard did this store failure just raise". {@link #classifyStoreFailure(Throwable)} is that
 * decision and it lives here for the same reason the other one does - so it is made once rather than at each
 * of the four write sites, which is where it was previously made four times and wrongly.
 *
 * <ul>
 *   <li>{@code SQLSTATE} {@value #SQLSTATE_UNIQUE_VIOLATION}, or an explicit duplicate translation from the
 *       data access layer - {@link StoreFailureKind#DUPLICATE_KEY}, the only route to {@code FILE STATUS '22'}
 *       and to {@code DFHRESP(DUPREC)}.</li>
 *   <li>{@code SQLSTATE} class {@value #SQLSTATE_CLASS_INTEGRITY} other than
 *       {@value #SQLSTATE_UNIQUE_VIOLATION} - {@link StoreFailureKind#CONSTRAINT_REFUSED}, the guard's
 *       {@code WHEN OTHER} arm reached because the store enforced a foreign key, a check constraint or a
 *       {@code NOT NULL} column that the KSDS never had.</li>
 *   <li>{@code SQLSTATE} class {@value #SQLSTATE_CLASS_DATA_EXCEPTION} - {@link
 *       StoreFailureKind#INVALID_DATA}, a request error with <strong>no counterpart in the
 *       corpus</strong>, because a value that fitted its {@code PIC} clause was storable by construction.</li>
 *   <li>anything else, including a failure carrying no state at all -
 *       {@link StoreFailureKind#IO_ERROR}, the guard's {@code ELSE} arm and the only condition for which a
 *       retry is a rational response.</li>
 *   </ul>
 *
 * <h2>The two guard shapes, and why one class cannot serve both with one method</h2>
 *
 * <p>The corpus does not have a single guard. It has two, and they disagree about {@code '10'}:
 *
 * <ul>
 *   <li><strong>Two way</strong>, used by {@code OPEN}, {@code CLOSE}, {@code WRITE} and
 *       {@code REWRITE}: {@code app/cbl/CBTRN02C.cbl:L239-L243 0000-DALYTRAN-OPEN} accepts {@code '00'}
 *       and sends everything else, {@code '10'} included, to the abend path. End of file is not a
 *       reachable outcome of an open or a write, so a report of it is an unexpected condition.
 *       Reproduced by {@link #requireSuccess(String, String, String)}.</li>
 *   <li><strong>Three way</strong>, used by a sequential {@code READ}:
 *       {@code app/cbl/CBTRN02C.cbl:L347-L356 1000-DALYTRAN-GET-NEXT} accepts {@code '00'}, treats
 *       {@code '10'} as end of file by moving {@value #APPL_EOF} into the result field, and sends
 *       everything else to the abend path. The same fourteen lines recur at
 *       {@code app/cbl/CBACT01C.cbl:L94-L103}, {@code app/cbl/CBACT02C.cbl:L98},
 *       {@code app/cbl/CBACT03C.cbl:L98} and {@code app/cbl/CBCUS01C.cbl:L98}. Reproduced by
 *       {@link #applResultForSequentialRead(String)}, which the four sequential readers call and then
 *       branch on. It returns the result code rather than throwing, because each of those paragraphs has
 *       work to do <em>between</em> the classification and the branch - {@code CBACT01C} performs
 *       {@code 1100-DISPLAY-ACCT-RECORD} at {@code :L96}, before the {@code IF APPL-AOK} at {@code :L104}
 *       - and each abends with its own {@code DISPLAY} literal and its own culprit. A method that threw
 *       could carry neither.</li>
 *   </ul>
 *
 * <p>Collapsing the two into one method would either abend a legitimate end of file or silently accept an
 * end of file reported by an open. Both are behaviour changes, so both shapes are kept.
 *
 * <h2>The only three sites where a non normal status is success</h2>
 *
 * <p>Only three {@code '23'} literals exist in the whole 19,254 line corpus, in exactly two files, plus
 * the {@code '04'} acceptance confined to one file. Nothing outside them may treat a non {@code '00'}
 * status as success:
 *
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L481 2700-UPDATE-TCATBAL} - the transaction category balance upsert.
 *       Reproduced by {@link #requireCategoryBalanceReadSuccess(String)}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L422 1200-GET-INTEREST-RATE} - the disclosure group lookup, whose
 *       miss triggers a retry against the literal default group. Reproduced by
 *       {@link #requireDisclosureGroupReadSuccess(String)}. The retry itself at
 *       {@code app/cbl/CBACT04C.cbl:L446 1200-A-GET-DEFAULT-INT-RATE} is <em>strict</em> and is
 *       reproduced by {@link #requireDefaultDisclosureGroupReadSuccess(String)}.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L736} and eight siblings - the statement file service call sites,
 *       which accept {@code '04'} alongside {@code '00'}. Reproduced by
 *       {@link #requireFileServiceSuccess(String, String, String)} and
 *       {@link #requireFileServiceSuccessOrEndOfFile(String, String, String)}.</li>
 *   </ol>
 *
 * <h2>Reject codes are not exceptions and never pass through here</h2>
 *
 * <p>A batch reject is a business outcome carried as data. The five reject reasons are moved into
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} and are never raised.
 * They live in {@code com.cardemo.model.enums.RejectCode} and drive an exit status. <strong>This class
 * never throws, wraps, maps or even mentions a reject code in a value it returns.</strong>
 *
 * <p>The two exit code paths are likewise independent and must not be conflated. Return code 4 is set if
 * and only if the reject count exceeds zero, at {@code app/cbl/CBTRN02C.cbl:L227-L231}. Return code
 * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} arises from the abend at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}. Nothing this class produces can ever yield return code 4.
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 and Maven 3.9.11, against {@code spring-boot-starter-parent:3.5.11}. The compiler runs
 * {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, so a raw type, a switch fall through
 * or a deprecated call is a build failure rather than a warning. An unused import is not: {@code javac}
 * at release 25 publishes no lint key for one, so it must be spotted by hand.
 * Build with {@code ./mvnw -B clean compile},
 * run the unit suite with {@code ./mvnw -B clean test} and gate coverage with {@code ./mvnw -B verify},
 * which enforces an eighty percent line floor with no package excluded. This class adds no dependency,
 * needs no annotation processor and does not use Lombok.
 *
 * <p>Its unit tests belong in {@code src/test/java/com/cardemo/unit} and never in this package. They must
 * assert, at minimum: that {@code '23'} yields {@code FILE STATUS IS: NNNN0023}; that a first byte of
 * {@code '9'} with a second byte above {@code 0x7F} still renders three digits and never a negative
 * number; that {@code '00'} and {@code '10'} throw nothing; each of the four typed mappings; that an
 * unrecognised status yields the abend code; that the second disclosure group miss is fatal and
 * <em>not</em> a not found; and that {@code '04'} is rejected on every general path.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None are configurable. This class reads no property, no environment variable and no classpath
 * resource, holds no credential and has no injected collaborator. Its fixed values are:
 *
 * <ul>
 *   <li>The result field vocabulary: initial {@value #APPL_RESULT_INITIAL}, success
 *       {@value #APPL_AOK}, end of file {@value #APPL_EOF} and failure {@value #APPL_FAILURE}. Note that
 *       end of file is sixteen and not twelve.</li>
 *   <li>The abend contract: code {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}
 *       and process return code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}, both owned by and read
 *       from {@code FatalProcessingException} rather than restated here.</li>
 *   <li>The four {@code CABENDD.CPY} field widths, from {@code app/cpy/CSMSG02Y.cpy:L21-L29}:
 *       {@code ABEND-CODE} {@value #ABEND_CODE_WIDTH}, {@code ABEND-CULPRIT}
 *       {@value #ABEND_CULPRIT_WIDTH}, {@code ABEND-REASON} {@value #ABEND_REASON_WIDTH} and
 *       {@code ABEND-MSG} {@value #ABEND_MESSAGE_WIDTH}. <strong>All four are declared
 *       {@code VALUE SPACES}, not null</strong>, so an unknown code or culprit is passed as spaces padded
 *       to its width rather than as null.</li>
 *   <li>The exact legacy failure literals for the three scoped sites, held as constants so that the text
 *       the parity gate compares exists in one place.</li>
 *   </ul>
 *
 * <h2>Security</h2>
 *
 * <p>Every message this class builds is limited to the raw status, its four character expansion, the
 * logical file or DD name and the attempted operation. <strong>It never places a record key, a record
 * payload, an account identifier, a card number or any other personally identifiable value in a message
 * or in a structured field</strong>, which is why the not found and duplicate mappings deliberately pass
 * no key even though the exception types accept one. Callers must honour the same rule and must never
 * pass a key value as a logical file name.
 *
 * <p><strong>Deliberate, safe carve-out from the log masking policy.</strong> The line produced by
 * {@link #displayIoStatus(String)} contains no personally identifiable value: it is four literal
 * {@code N} characters followed by four digits, or by one status byte and three digits. It must therefore
 * pass through {@code logback-spring.xml} unmasked, unreformatted and byte for byte. Adding a masking or
 * reformatting rule that touches it would break the end to end parity comparison while protecting
 * nothing. This is recorded here so that a later reader does not harden it by mistake.
 *
 * <p><strong>The precise limit of that carve-out.</strong> The one status byte the parity line can carry is
 * copied through unaltered, so a malformed status - one that never came from the corpus, but from a store
 * or adapter layer this application does not control - can put a control character into that single line.
 * The carve-out is accepted there and only there, because the line's bytes are the parity contract and the
 * exposure is one unrepeatable character in a line whose remaining nineteen are fixed literals. It does
 * <strong>not</strong> extend to exception messages: every status this class puts into a message is encoded
 * first, through {@code FileStatus.escapeForDiagnostics(String)} and
 * {@code FileStatus.renderIoStatus04ForDiagnostics(String)}. Messages are assembled from several
 * caller-supplied parts, are logged at many sites and are wrapped by other messages, so the same character
 * that is tolerable once in a fixed line is not tolerable there.
 *
 * <h2>Ways this translation gets broken</h2>
 *
 * <ul>
 *   <li>Mis-rendering the {@code FILE STATUS IS: NNNN} line. Never
 *       assemble it by hand; call {@link #displayIoStatus(String)}, which concatenates the two owned
 *       parts and substitutes nothing.</li>
 *   <li>Omitting the low order byte mask in the three digit expansion, which
 *       sign extends any byte above {@code 0x7F} into a negative number and destroys the three digit
 *       width. The mask is applied once, inside
 *       {@code FileStatus.renderIoStatus04(String)}; never re-derive the expansion.</li>
 *   <li>Mapping a missing disclosure group default row to a not found. It
 *       abends. Use {@link #requireDefaultDisclosureGroupReadSuccess(String)}, which raises the
 *       fatal type for {@code '23'} exactly as {@code app/cbl/CBACT04C.cbl:L446-L458} does.</li>
 *   <li>Treating {@code '10'} as an error. Use {@link #applResultForSequentialRead(String)} at a sequential
 *       read, or {@link #requireFileServiceSuccessOrEndOfFile(String, String, String)} at a file service
 *       call.</li>
 *   <li>Admitting {@code '04'} to the general map. It is reachable only
 *       through the two file service methods, and no general method accepts it.</li>
 *   <li>Implementing the rendering a second time in another class. There
 *       is exactly one implementation, in {@code FileStatus}, and this class delegates to it.</li>
 *   <li>Searching the source for a single space between the two lenient literals: the source writes
 *       {@code '00'} then two spaces then {@code OR '23'} at both guards, so a naive search finds
 *       neither.</li>
 *   </ul>
 *
 * <h2>Two things the corpus does not supply</h2>
 *
 * <ul>
 *   <li>Any service level objective for I/O latency or throughput. None
 *       exists anywhere in the frozen corpus - the legacy system publishes no service level agreement, and
 *       the performance gate records a measured baseline rather than a target. No objective is invented
 *       here.</li>
 *   <li>A literal source site for {@code '22'} or for {@code '35'}. A
 *       census of {@code app/cbl} at commit {@code 7756d89} finds {@code '00'} 88 times in 9 files,
 *       {@code '04'} 9 times in 1 file, {@code '10'} 11 times in 9 files, {@code '23'} 3 times in 2
 *       files, and {@code '22'} and {@code '35'} <strong>zero times each</strong>. Both are reachable
 *       only through the else arm of the binary guard, so their mappings are retained as runtime
 *       reachable rather than deleted; see the notes on them in {@code failureFor}. Grounding them would
 *       need a COBOL guard that tests those two literals; none exists. The CICS side has its own separate
 *       vocabulary, 23 sites of {@code DFHRESP(NOTFND)}, 7 of {@code DFHRESP(DUPREC)}, 3 of
 *       {@code DFHRESP(DUPKEY)} and none at all of {@code DFHRESP(NOTOPEN)}, and this class deliberately
 *       does not conflate the two.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A log line reading {@code FILE STATUS IS: 0023} rather than {@code FILE STATUS IS: NNNN0023}
 *       means something substituted the rendering into the placeholder instead of appending after it.
 *       Remedy: call {@link #displayIoStatus(String)} and log its result verbatim.</li>
 *   <li>An exception message reading {@code ... FILE STATUS \\u0009A (IO-STATUS-04 \\u0009065)} is not a
 *       defect. The status genuinely began with a tab, and the encoding is what stopped that byte from
 *       splitting the log record. Diagnose the adapter that produced the status, not this class.</li>
 *   <li>A diagnostic record that ends earlier than the message that produced it, or a second record that
 *       nothing appears to have logged, means a raw status byte reached a log through some path other than
 *       this class. Remedy: that path used {@code FileStatus.renderIoStatus04(String)} where it needed
 *       {@code FileStatus.renderIoStatus04ForDiagnostics(String)}; {@link #displayIoStatus(String)} is the
 *       only site permitted to emit the unencoded form.</li>
 *   <li>An abend where an orderly end of loop was expected means a sequential read classified its status
 *       with {@link #applResultForGuard(String)}, which is the {@code OPEN} and {@code WRITE} shape.
 *       Remedy: switch that call site to {@link #applResultForSequentialRead(String)}.</li>
 *   <li>A {@code RecordNotFoundException} escaping the interest calculation job means the default
 *       disclosure group retry used a general method. Remedy: switch it to
 *       {@link #requireDefaultDisclosureGroupReadSuccess(String)}.</li>
 *   <li>An abend on the first daily transaction whose category balance row does not yet exist means the
 *       upsert used a general method. Remedy: switch it to
 *       {@link #requireCategoryBalanceReadSuccess(String)} and branch on the returned flag.</li>
 *   <li>A corrupt read silently accepted anywhere outside the statement file service means {@code '04'}
 *       leaked into a general path. Remedy: only the two file service methods may accept it.</li>
 *   <li>A {@code 409} naming an identifier the caller can prove is free means a write site reported a store
 *       failure as a duplicate without classifying it. Remedy: pass the caught throwable through
 *       {@link #classifyStoreFailure(Throwable)} and answer the duplicate arm only for
 *       {@link StoreFailureKind#DUPLICATE_KEY}.</li>
 *   <li>{@link #classifyStoreFailure(Throwable)} answering {@link StoreFailureKind#IO_ERROR} for a
 *       collision the store log clearly shows means the throwable it was given had already been re-wrapped
 *       and no longer carries the driver's exception. Remedy: classify at the {@code catch}, before
 *       wrapping.</li>
 *   <li>A {@code FatalProcessingException} whose message reads {@code (absent)} where a status should be,
 *       and whose expansion reads {@code  032}, means the caller passed {@code null} or an empty status.
 *       That is reported rather than absorbed by design: the renderer is total and normalises the value
 *       exactly as a {@code MOVE} into {@code PIC X(02)} would, padding to two spaces, and a space is 32.
 *       Remedy: find the upstream code that lost the status, rather than defaulting it to {@code '00'}.</li>
 *   <li>A message that contains a record key, an account identifier or a card number means a caller passed
 *       one as the logical file name. Remedy: pass the DD or logical file name only; this class adds no
 *       other content and deliberately passes no key to the exception types that accept one.</li>
 *   </ul>
 */
@Component
public class FileStatusMapper {

    /**
     * The value the guard moves into {@code APPL-RESULT} <em>before</em> performing the verb, from
     * {@code MOVE 8 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L237 0000-DALYTRAN-OPEN}.
     */
    public static final int APPL_RESULT_INITIAL = 8;

    /**
     * The success value of {@code APPL-RESULT}, from the condition name {@code 88 APPL-AOK VALUE 0} at
     * {@code app/cbl/CBTRN02C.cbl:L143}, moved in by {@code MOVE 0 TO APPL-RESULT} at
     * {@code app/cbl/CBTRN02C.cbl:L240}.
     */
    public static final int APPL_AOK = 0;

    /**
     * The end of file value of {@code APPL-RESULT}, from the condition name {@code 88 APPL-EOF VALUE 16} at
     * {@code app/cbl/CBTRN02C.cbl:L144}, moved in by {@code MOVE 16 TO APPL-RESULT} at
     * {@code app/cbl/CBTRN02C.cbl:L352}.
     */
    public static final int APPL_EOF = 16;

    /**
     * The failure value of {@code APPL-RESULT}, from {@code MOVE 12 TO APPL-RESULT} at
     * {@code app/cbl/CBTRN02C.cbl:L242} and {@code app/cbl/CBTRN02C.cbl:L354}.
     */
    public static final int APPL_FAILURE = 12;

    /**
     * The width of {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22-L23}, declared
     * {@code VALUE SPACES}.
     */
    public static final int ABEND_CODE_WIDTH = 4;

    /**
     * The width of {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24-L25}, declared
     * {@code VALUE SPACES}.
     */
    public static final int ABEND_CULPRIT_WIDTH = 8;

    /**
     * The width of {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:L26-L27}, declared
     * {@code VALUE SPACES}. Every legacy failure literal this class carries as a reason fits inside it.
     */
    public static final int ABEND_REASON_WIDTH = 50;

    /**
     * The width of {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28-L29}, declared
     * {@code VALUE SPACES}.
     */
    public static final int ABEND_MESSAGE_WIDTH = 72;

    /**
     * The unset value of {@code ABEND-CODE}: {@value #ABEND_CODE_WIDTH} spaces, reproducing the
     * {@code VALUE SPACES} initialisation at {@code app/cpy/CSMSG02Y.cpy:L22-L23}.
     */
    public static final String ABEND_CODE_UNSET = " ".repeat(ABEND_CODE_WIDTH);

    /**
     * The unset value of {@code ABEND-CULPRIT}: {@value #ABEND_CULPRIT_WIDTH} spaces, reproducing the
     * {@code VALUE SPACES} initialisation at {@code app/cpy/CSMSG02Y.cpy:L24-L25}.
     */
    public static final String ABEND_CULPRIT_UNSET = " ".repeat(ABEND_CULPRIT_WIDTH);

    /**
     * The exact literal displayed before the transaction category balance read abends, from
     * {@code DISPLAY 'ERROR READING TRANSACTION BALANCE FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L489 2700-UPDATE-TCATBAL}.
     */
    public static final String TCATBAL_READ_FAILURE_TEXT = "ERROR READING TRANSACTION BALANCE FILE";

    /**
     * The exact literal displayed before the disclosure group read abends, from
     * {@code DISPLAY 'ERROR READING DISCLOSURE GROUP FILE'} at
     * {@code app/cbl/CBACT04C.cbl:L431 1200-GET-INTEREST-RATE}.
     */
    public static final String DISCGRP_READ_FAILURE_TEXT = "ERROR READING DISCLOSURE GROUP FILE";

    /**
     * The exact literal displayed before the <em>default</em> disclosure group read abends, from
     * {@code DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'} at
     * {@code app/cbl/CBACT04C.cbl:L455 1200-A-GET-DEFAULT-INT-RATE}.
     */
    public static final String DEFAULT_DISCGRP_READ_FAILURE_TEXT = "ERROR READING DEFAULT DISCLOSURE GROUP";

    /**
     * The exact literal the statement generator displays before an abend to report the file service return
     * code, from {@code DISPLAY 'RETURN CODE: ' WS-M03B-RC} at {@code app/cbl/CBSTM03A.CBL:L740} and its twelve
     * siblings. The trailing space is part of the literal and is preserved.
     */
    public static final String FILE_SERVICE_RETURN_CODE_TEXT = "RETURN CODE: ";

    /**
     * The originating program of the transaction category balance upsert, {@code CBTRN02C}, carried as
     * {@code ABEND-CULPRIT} by {@link #requireCategoryBalanceReadSuccess(String)}. Exactly
     * {@value #ABEND_CULPRIT_WIDTH} characters.
     */
    private static final String CULPRIT_POSTING = "CBTRN02C";

    /**
     * The originating program of both disclosure group lookups, {@code CBACT04C}, carried as
     * {@code ABEND-CULPRIT} by {@link #requireDisclosureGroupReadSuccess(String)} and
     * {@link #requireDefaultDisclosureGroupReadSuccess(String)}. Exactly {@value #ABEND_CULPRIT_WIDTH}
     * characters.
     */
    private static final String CULPRIT_INTEREST = "CBACT04C";

    /**
     * The originating program of the statement file service call sites, {@code CBSTM03A}, carried as
     * {@code ABEND-CULPRIT} by the two file service methods. Exactly {@value #ABEND_CULPRIT_WIDTH} characters.
     */
    private static final String CULPRIT_STATEMENT = "CBSTM03A";

    /**
     * The DD name of the transaction category balance file, from {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF}
     * at {@code app/cbl/CBTRN02C.cbl:L57}.
     */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * The DD name of the disclosure group file, from {@code SELECT DISCGRP-FILE ASSIGN TO DISCGRP} at
     * {@code app/cbl/CBACT04C.cbl:L47}.
     */
    private static final String DD_DISCGRP = "DISCGRP";

    /**
     * The verb recorded for every read this class guards. The corpus does not distinguish the verbs by status -
     * one guard shape covers all of them - so the verb travels as data.
     */
    private static final String OPERATION_READ = "READ";

    /**
     * The reason recorded on a general abend, where the corpus has no single literal to reproduce because the
     * displayed text differs at every one of the seventy eight guard sites - {@code 'ERROR OPENING DALYTRAN'}
     * at {@code app/cbl/CBTRN02C.cbl:L247} and {@code 'ERROR READING DALYTRAN FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L363} being two of them.
     */
    private static final String UNRECOGNISED_STATUS_REASON = "UNRECOGNISED COBOL FILE STATUS AT I/O GUARD";

    /**
     * The reason recorded when the statement file service reports a return code its call site does not accept.
     * Target side text, for the same reason as {@link #UNRECOGNISED_STATUS_REASON}.
     */
    private static final String FILE_SERVICE_REASON = "UNACCEPTED RETURN CODE FROM STATEMENT FILE SERVICE";

    /**
     * The placeholder rendered in place of a logical file or DD name the caller did not identify, so that the
     * four characters {@code null} never appear in a log line.
     */
    private static final String UNIDENTIFIED_FILE = "an unidentified logical file";

    /**
     * The placeholder rendered in place of an operation the caller did not identify.
     */
    private static final String UNIDENTIFIED_OPERATION = "An unidentified operation";

    /**
     * The placeholder rendered in place of a status or return code the caller did not supply at all.
     */
    private static final String ABSENT_VALUE = "(absent)";

    /**
     * The {@code SQLSTATE} of a unique or primary key constraint violation, {@value}.
     *
     * <p>This is the <em>only</em> store condition that means what {@code DFHRESP(DUPREC)} and
     * {@code FILE STATUS '22'} mean. It is defined by SQL/Foundation as {@code unique_violation} within class
     * {@code 23}, integrity constraint violation, and PostgreSQL reports it verbatim.
     */
    public static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * The {@code SQLSTATE} class of a data exception, {@value}.
     *
     * <p>Class {@code 22} covers every way a <em>value</em> is unusable rather than a <em>constraint</em> being
     * broken: {@code 22021} character not in repertoire, which is what PostgreSQL reports for a
     * {@code U+0000} in a character column; {@code 22001} string data right truncation; {@code 22P02} invalid
     * text representation. Every member of the class means the caller supplied field content the column cannot
     * hold, which is a request error and not a store failure.
     */
    public static final String SQLSTATE_CLASS_DATA_EXCEPTION = "22";

    /**
     * The number of links {@link #classifyStoreFailure(Throwable)} will follow along each of the two chains
     * it walks - {@link Throwable#getCause()} and {@link SQLException#getNextException()} - {@value}.
     *
     * <p>Bounded rather than unbounded because both chains are untrusted input like any other: a
     * self-referential or cyclic chain would otherwise loop forever inside a failure handler, which is the
     * worst possible place to hang. The self-reference guard catches the one-element cycle and this cap
     * catches every longer one. Thirty two is far beyond the four links the deepest real chain has -
     * {@code DataIntegrityViolationException} to Hibernate's {@code JDBCException} to the driver's
     * {@code SQLException} to its linked exception.
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    /**
     * Creates the stateless mapper.
     */
    public FileStatusMapper() {
        // No collaborator, no configuration and no state to initialise. See the constructor Javadoc.
    }

    /**
     * Reproduces {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} by assembling the
     * exact line that paragraph writes to SYSOUT, and returns it for the caller to log.
     *
     * @param ioStatus the raw file status, ordinarily two characters, and tolerated when {@code null}, shorter
     * or longer
     * @return the complete legacy line, never {@code null}, always the twenty character prefix followed
     * immediately by exactly four rendered characters, and therefore always twenty four characters long
     */
    public String displayIoStatus(String ioStatus) {
        return FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(ioStatus);
    }

    /**
     * The test is binary - {@code '00'} against everything else - and this is the shape every {@code OPEN},
     * {@code CLOSE}, {@code WRITE} and {@code REWRITE} guard in the corpus uses. End of file is not among its
     * outcomes, which is why a status of {@code '10'} yields {@value #APPL_FAILURE} here.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@value #APPL_AOK} when the status is exactly {@code '00'}, otherwise {@value #APPL_FAILURE}
     */
    public int applResultForGuard(String ioStatus) {
        return FileStatus.SUCCESS.matches(ioStatus) ? APPL_AOK : APPL_FAILURE;
    }

    /**
     * The same nested test recurs at {@code app/cbl/CBACT01C.cbl:L94-L103}, {@code app/cbl/CBACT02C.cbl:L98},
     * {@code app/cbl/CBACT03C.cbl:L98}, {@code app/cbl/CBCUS01C.cbl:L98}, {@code app/cbl/CBACT04C.cbl:L330} and
     * {@code app/cbl/CBTRN01C.cbl:L207}. Downstream, the corpus distinguishes the two non success outcomes by
     * condition name: {@code IF APPL-EOF MOVE 'Y' TO END-OF-FILE} at {@code app/cbl/CBTRN02C.cbl:L360-L361}
     * terminates the loop, and only the remaining case reaches the renderer and the abend at
     * {@code :L365-L366}.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@value #APPL_AOK} for {@code '00'}, {@value #APPL_EOF} for {@code '10'}, otherwise
     * {@value #APPL_FAILURE}
     */
    public int applResultForSequentialRead(String ioStatus) {
        if (FileStatus.SUCCESS.matches(ioStatus)) {
            return APPL_AOK;
        }
        if (FileStatus.END_OF_FILE.matches(ioStatus)) {
            return APPL_EOF;
        }
        return APPL_FAILURE;
    }



    /**
     * Decides which exception a raw file status maps to, without throwing it.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}, shorter or longer, and
     * which is reported rather than coerced into a success
     * @param logicalFileName the logical file or DD name whose I/O reported the status, for example
     * {@code "DALYTRAN"} or {@code "ACCTDAT"}.
     * @param operation the attempted operation, for example {@code "OPEN"} or {@code "READ"}.
     * @return the exception the status maps to, or an empty {@link Optional} for {@code '00'} and {@code '10'}.
     */
    public Optional<CardDemoException> toException(String ioStatus, String logicalFileName, String operation) {
        return toException(ioStatus, logicalFileName, operation, null);
    }

    /**
     * Decides which exception a raw file status maps to, without throwing it, preserving an underlying
     * throwable as the cause.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation the attempted operation, permitted to be {@code null}
     * @param cause the underlying throwable to preserve, permitted to be {@code null} when the status was
     * reported without one, as it is in the corpus itself
     * @return the exception the status maps to, or an empty {@link Optional} for {@code '00'} and {@code '10'}.
     */
    public Optional<CardDemoException> toException(String ioStatus, String logicalFileName, String operation,
            Throwable cause) {
        if (applResultForSequentialRead(ioStatus) != APPL_FAILURE) {
            return Optional.empty();
        }
        return Optional.of(failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, cause));
    }

    /**
     * Translates a status into its typed failure exactly as {@link #toException(String, String, String,
     * Throwable)} does, but carrying a caller-supplied legacy caption as the message instead of a composed
     * diagnostic one.
     *
     * <p><strong>What this is for.</strong> The online programs latch a fixed caption on their failure arms and
     * paint it on the screen - {@code 'Unable to Update User...'} at {@code app/cbl/COUSR03C.cbl}:332,
     * {@code 'Unable to lookup User...'} at {@code :296}, {@code 'Unable to Add User...'} on the add path - and
     * those strings are observable contract, compared byte for byte rather than by substring. A caller that
     * needs the caption published cannot get it from {@link #toException}, whose message names the operation,
     * the file and the expanded status; nor from prefixing, which still yields a different string. This method
     * gives the caller the caption and the correct subtype at once.
     *
     * <p><strong>Nothing is lost by using it.</strong> The status, the logical file and the operation are still
     * carried in the returned exception's own accessors, which is where a diagnostic consumer should read them
     * from rather than from prose. The status is still what selects the subtype, and the expanded
     * {@code IO-STATUS-04} rendering is still owned solely by {@code com.cardemo.model.enums.FileStatus}.
     *
     * <p>An empty return means the same thing it means on {@link #toException}: this status is not a failure,
     * so there is nothing to translate. The caller decides what that means at its own site.
     *
     * @param ioStatus        the two-character file status the operation recorded
     * @param logicalFileName the logical file or DD name the operation names
     * @param operation       the operation attempted, for the exception's context
     * @param cause           the underlying throwable, or {@code null} when there is none
     * @param legacyMessage   the source caption to carry as the message; a {@code null} composes as usual
     * @return the typed failure carrying that caption, or empty when the status is not a failure
     */
    public Optional<CardDemoException> toExceptionWithLegacyMessage(String ioStatus, String logicalFileName,
            String operation, Throwable cause, String legacyMessage) {
        if (applResultForSequentialRead(ioStatus) != APPL_FAILURE) {
            return Optional.empty();
        }
        return Optional.of(failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, cause,
                legacyMessage));
    }

    /**
     * Applies the two way guard of {@code app/cbl/CBTRN02C.cbl:L236-L252 0000-DALYTRAN-OPEN}: returns when the
     * status is {@code '00'} and throws otherwise.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation the attempted operation, permitted to be {@code null}
     * @throws com.cardemo.exception.DuplicateRecordException when the status is {@code '22'}
     * @throws com.cardemo.exception.RecordNotFoundException when the status is {@code '23'}
     * @throws com.cardemo.exception.FileUnavailableException when the status is {@code '35'}
     * @throws com.cardemo.exception.FileAccessException when the status is in the {@code '9x'} family
     * @throws com.cardemo.exception.FatalProcessingException for every other status, including {@code '04'},
     * {@code '10'} and any unrecognised, {@code null} or malformed value
     */
    public void requireSuccess(String ioStatus, String logicalFileName, String operation) {
        requireSuccess(ioStatus, logicalFileName, operation, null);
    }

    /**
     * Applies the two way guard of {@code app/cbl/CBTRN02C.cbl:L236-L252 0000-DALYTRAN-OPEN}, preserving an
     * underlying throwable as the cause of anything it throws.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation the attempted operation, permitted to be {@code null}
     * @param cause the underlying throwable to preserve, permitted to be {@code null}
     * @throws com.cardemo.exception.CardDemoException whenever the status is not exactly {@code '00'}.
     */
    public void requireSuccess(String ioStatus, String logicalFileName, String operation, Throwable cause) {
        if (applResultForGuard(ioStatus) == APPL_AOK) {
            return;
        }
        throw failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, cause);
    }

    /**
     * Scoped carve-out 1 of 3. Applies the lenient read guard of
     * {@code app/cbl/CBTRN02C.cbl:L467-L499 2700-UPDATE-TCATBAL}, where a record not found is an accepted
     * control path rather than an error because the paragraph is an upsert.
     *
     * @param ioStatus the raw two character status reported by the category balance read, which may be
     * {@code null}, shorter or longer
     * @return {@code true} when the status is {@code '23'} and the caller must take the create branch,
     * {@code false} when the status is {@code '00'} and the caller must take the update branch
     * @throws com.cardemo.exception.CardDemoException for every other status; the concrete subtype is chosen by
     * the map documented on {@link #requireSuccess(String, String, String)}, so {@code '22'}, {@code '35'} and
     * the {@code '9x'} family remain typed and everything else is fatal
     */
    public boolean requireCategoryBalanceReadSuccess(String ioStatus) {
        if (FileStatus.SUCCESS.matches(ioStatus)) {
            return false;
        }
        if (FileStatus.RECORD_NOT_FOUND.matches(ioStatus)) {
            return true;
        }
        throw failureFor(ioStatus, DD_TCATBALF, OPERATION_READ, TCATBAL_READ_FAILURE_TEXT, CULPRIT_POSTING,
                null);
    }

    /**
     * Scoped carve-out 2 of 3, first half. Applies the lenient read guard of
     * {@code app/cbl/CBACT04C.cbl:L415-L440 1200-GET-INTEREST-RATE}, where a record not found is an accepted
     * control path because it triggers a retry against the literal default group.
     *
     * @param ioStatus the raw two character status reported by the disclosure group read, which may be
     * {@code null}, shorter or longer
     * @return {@code true} when the status is {@code '23'} and the caller must retry against the default group,
     * {@code false} when the status is {@code '00'} and a rate was returned
     * @throws com.cardemo.exception.CardDemoException for every other status; the concrete subtype is chosen by
     * the map documented on {@link #requireSuccess(String, String, String)}
     */
    public boolean requireDisclosureGroupReadSuccess(String ioStatus) {
        if (FileStatus.SUCCESS.matches(ioStatus)) {
            return false;
        }
        if (FileStatus.RECORD_NOT_FOUND.matches(ioStatus)) {
            return true;
        }
        throw failureFor(ioStatus, DD_DISCGRP, OPERATION_READ, DISCGRP_READ_FAILURE_TEXT, CULPRIT_INTEREST,
                null);
    }

    /**
     * Scoped carve-out 2 of 3, second half. Applies the <strong>strict</strong> retry guard of
     * {@code app/cbl/CBACT04C.cbl:L443-L460 1200-A-GET-DEFAULT-INT-RATE}, where a record not found is
     * <strong>fatal</strong>.
     *
     * @param ioStatus the raw two character status reported by the default group read, which may be
     * {@code null}, shorter or longer
     * @throws com.cardemo.exception.FatalProcessingException when the status is {@code '23'}, and for every
     * unrecognised, {@code null} or malformed value
     * @throws com.cardemo.exception.CardDemoException when the status is {@code '22'}, {@code '35'} or in the
     * {@code '9x'} family, each keeping its typed subtype
     */
    public void requireDefaultDisclosureGroupReadSuccess(String ioStatus) {
        if (FileStatus.SUCCESS.matches(ioStatus)) {
            return;
        }
        if (FileStatus.RECORD_NOT_FOUND.matches(ioStatus)) {
            throw fatalFor(ioStatus, DD_DISCGRP, OPERATION_READ, DEFAULT_DISCGRP_READ_FAILURE_TEXT,
                    CULPRIT_INTEREST, null);
        }
        throw failureFor(ioStatus, DD_DISCGRP, OPERATION_READ, DEFAULT_DISCGRP_READ_FAILURE_TEXT,
                CULPRIT_INTEREST, null);
    }

    /**
     * Scoped carve-out 3 of 3, first form. Applies the statement file service guard of
     * {@code app/cbl/CBSTM03A.CBL:L736 8100-TRNXFILE-OPEN} and its eight siblings, the only sites in the corpus
     * where {@code '04'} is accepted as success.
     *
     * @param returnCode the raw two character return code reported by the file service, which may be
     * {@code null}, shorter or longer
     * @param ddName the DD name the call selected, for example {@code "TRNXFILE"} or {@code "XREFFILE"}.
     * @param operation the attempted operation, for example {@code "OPEN"} or {@code "READ"}.
     * @throws com.cardemo.exception.FatalProcessingException for every code other than {@code '00'} and
     * {@code '04'}, including {@code '10'} and any {@code null} or malformed value
     */
    public void requireFileServiceSuccess(String returnCode, String ddName, String operation) {
        if (FileStatus.SUCCESS.matches(returnCode) || FileStatus.SUCCESS_SECONDARY.matches(returnCode)) {
            return;
        }
        throw fileServiceAbend(returnCode, ddName, operation);
    }

    /**
     * Scoped carve-out 3 of 3, second form. Applies the stricter statement file service guard of
     * {@code app/cbl/CBSTM03A.CBL:L353 EVALUATE WS-M03B-RC} and its three siblings, which accept {@code '00'}
     * alone, treat {@code '10'} as end of file and abend on everything else.
     *
     * @param returnCode the raw two character return code reported by the file service, which may be
     * {@code null}, shorter or longer
     * @param ddName the DD name the call selected.
     * @param operation the attempted operation. Permitted to be {@code null}
     * @return {@code true} when the code is {@code '10'} and the caller must stop reading, {@code false} when
     * the code is {@code '00'} and a record was returned
     * @throws com.cardemo.exception.FatalProcessingException for every other code, {@code '04'} included
     */
    public boolean requireFileServiceSuccessOrEndOfFile(String returnCode, String ddName, String operation) {
        if (FileStatus.SUCCESS.matches(returnCode)) {
            return false;
        }
        if (FileStatus.END_OF_FILE.matches(returnCode)) {
            return true;
        }
        throw fileServiceAbend(returnCode, ddName, operation);
    }

    /**
     * The mapping decision itself, and the only place in the codebase where it is made.
     *
     * @param ioStatus the raw status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, or {@code null}.
     * @param operation the attempted operation, or {@code null}
     * @param legacyFailureText the exact literal the source displays at this site, or {@code null} on a general
     * path where the displayed text differs per site
     * @param abendCulprit the originating program for {@code ABEND-CULPRIT}, or {@link #ABEND_CULPRIT_UNSET}
     * where it is unknown
     * @param cause the underlying throwable to preserve, or {@code null}
     * @return the exception the status maps to, never {@code null}
     */
    private CardDemoException failureFor(String ioStatus, String logicalFileName, String operation,
            String legacyFailureText, String abendCulprit, Throwable cause) {
        return failureFor(ioStatus, logicalFileName, operation, legacyFailureText, abendCulprit, cause, null);
    }

    /**
     * As above, with the composed diagnostic message optionally replaced by a caller-supplied one.
     *
     * <p><strong>Why an override exists at all.</strong> The composed message names the operation, the logical
     * file and the expanded status, which is what a diagnostic reader needs. It is not what an online screen
     * displayed. Several online programs latch a fixed caption on their {@code WHEN OTHER} arm - for instance
     * {@code 'Unable to Update User...'} at {@code app/cbl/COUSR03C.cbl}:332 - and that caption is part of the
     * observable contract, compared byte for byte. Composing over it replaced a published literal with
     * generated prose; prefixing it, as {@code legacyFailureText} does, still changes the string and so still
     * fails an equality check against the source. The override lets the caption be the message while every
     * piece of the diagnostic context survives in the exception's own structured fields, which is where a
     * consumer that wants the status should read it from.
     *
     * <p>The override changes only the message. The <strong>subtype</strong> is still chosen here from the
     * status, which is the decision this class owns and the reason callers delegate to it rather than
     * constructing failures themselves.
     *
     * @param ioStatus          the two-character file status
     * @param logicalFileName   the logical file or DD name the operation names
     * @param operation         the operation attempted
     * @param legacyFailureText the source failure text to prefix onto a composed message, or {@code null}
     * @param abendCulprit      the program named on an abend
     * @param cause             the underlying throwable, or {@code null}
     * @param messageOverride   the exact message to carry, or {@code null} to compose one as usual
     * @return the typed failure for that status, never {@code null}
     */
    private CardDemoException failureFor(String ioStatus, String logicalFileName, String operation,
            String legacyFailureText, String abendCulprit, Throwable cause, String messageOverride) {
        String message = messageOverride != null
                ? messageOverride
                : failureMessage(ioStatus, logicalFileName, operation, legacyFailureText);
        Optional<FileStatus> classified = FileStatus.tryClassify(ioStatus);
        if (classified.isEmpty()) {
            return fatalFor(ioStatus, logicalFileName, operation, legacyFailureText, abendCulprit, cause);
        }
        return switch (classified.get()) {
            // FILE STATUS '22'. No literal source site in app/cbl (census: 0 occurrences); retained as a
            // runtime-reachable-only mapping. No colliding key is passed: this
            // class never places a key value in an exception.
            case DUPLICATE_KEY -> new DuplicateRecordException(message, logicalFileName, null, cause);
            // FILE STATUS '23'. Cited at app/cbl/CBTRN02C.cbl:L481 and app/cbl/CBACT04C.cbl:L422 and :L436.
            // No record key is passed, for the same reason.
            case RECORD_NOT_FOUND -> new RecordNotFoundException(message, logicalFileName, null, cause);
            // FILE STATUS '35'. No literal source site in app/cbl (census: 0 occurrences); retained as a
            // runtime-reachable-only mapping.
            case FILE_UNAVAILABLE -> new FileUnavailableException(message, logicalFileName, cause);
            // The '9x' family, from the guard IO-STAT1 = '9' at app/cbl/CBTRN02C.cbl:L716. The RAW status is
            // handed over; that type expands it once through the single owning renderer.
            case IO_ERROR -> new FileAccessException(message, ioStatus, logicalFileName, operation, cause);
            // '00', '04' and '10' reaching this method are unexpected conditions at the call site that
            // rejected them, and the corpus abends on an unexpected condition.
            case SUCCESS, SUCCESS_SECONDARY, END_OF_FILE ->
                    fatalFor(ioStatus, logicalFileName, operation, legacyFailureText, abendCulprit, cause);
        };
    }

    /**
     * Builds the abend for a status the guard cannot classify, or classifies as one the call site had already
     * rejected.
     *
     * @param ioStatus the raw status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, or {@code null}
     * @param operation the attempted operation, or {@code null}
     * @param legacyFailureText the exact literal the source displays at this site, or {@code null}
     * @param abendCulprit the originating program, or {@link #ABEND_CULPRIT_UNSET}
     * @param cause the underlying throwable to preserve, or {@code null}
     * @return the fatal exception, never {@code null}
     */
    private FatalProcessingException fatalFor(String ioStatus, String logicalFileName, String operation,
            String legacyFailureText, String abendCulprit, Throwable cause) {
        String reason = legacyFailureText == null ? UNRECOGNISED_STATUS_REASON : legacyFailureText;
        String message = failureMessage(ioStatus, logicalFileName, operation, legacyFailureText);
        return new FatalProcessingException(ABEND_CODE_UNSET, abendCulprit, reason, message, cause);
    }

    /**
     * Builds the abend for a statement file service return code the call site does not accept.
     *
     * @param returnCode the raw two character return code, which may be {@code null}, shorter or longer
     * @param ddName the DD name the call selected, or {@code null}
     * @param operation the attempted operation, or {@code null}
     * @return the fatal exception, never {@code null}
     */
    private FatalProcessingException fileServiceAbend(String returnCode, String ddName, String operation) {
        String message = orPlaceholder(operation, UNIDENTIFIED_OPERATION)
                + " of " + orPlaceholder(ddName, UNIDENTIFIED_FILE)
                + " through the statement file service reported " + FILE_SERVICE_RETURN_CODE_TEXT
                + orPlaceholder(returnCode, ABSENT_VALUE);
        return new FatalProcessingException(ABEND_CODE_UNSET, CULPRIT_STATEMENT, FILE_SERVICE_REASON, message,
                null);
    }

    /**
     * Assembles the detail message every exception this class builds carries.
     *
     * <p>The content is deliberately limited to four things: the exact literal the source displays at this
     * site where there is one, the attempted operation, the logical file or DD name, and the status in both
     * its raw and its four character forms. <strong>Nothing else may be added.</strong> A record key, a
     * record image, an account identifier, a card number or any other personally identifiable value is
     * forbidden here, because exception messages are logged - which is why the typed mappings in
     * {@code failureFor} pass no key even though their exception types accept one.
     *
     * <p>The four character form is obtained from the single owning renderer, and is labelled with the
     * field it reproduces, {@code IO-STATUS-04} from {@code app/cbl/CBTRN02C.cbl:L138-L140}. The twenty
     * character {@code FILE STATUS IS: NNNN} prefix is deliberately <strong>not</strong> included: that
     * exact line is produced only by {@link #displayIoStatus(String)}, so no caller can mistake a fragment
     * of a message for the line the parity gate compares, and no reformatting of a message can disturb it.
     *
     * <p><strong>Both status forms in this message are encoded, and that is the whole point of the split
     * from {@link #displayIoStatus(String)}.</strong> This text becomes an exception detail message and is
     * therefore logged. A status is not always well formed - it can arrive from a store or adapter layer
     * this corpus does not control - and a raw control byte in a log record can terminate the record early
     * or forge a second one, which would obscure the very failure this message exists to report. The raw
     * echo passes through {@code FileStatus.escapeForDiagnostics(String)} and the expansion through
     * {@code FileStatus.renderIoStatus04ForDiagnostics(String)}, so nothing but printable ASCII can reach
     * the message. For every status the corpus actually produces the encoding is a no operation and the
     * text is unchanged; only a malformed status renders differently, as {@code \\u0009} rather than as a
     * literal tab. The encoding is applied <em>after</em> {@link #orPlaceholder(String, String)}, so the
     * absent and blank cases keep their existing placeholder exactly as before.
     *
     * <p>{@link #displayIoStatus(String)} is deliberately <strong>not</strong> changed to match. It
     * reproduces the legacy {@code DISPLAY} line that the parity gate compares byte for byte, so it must
     * keep copying malformed bytes through unaltered. The two methods answer different questions and are
     * compared against different things.
     *
     * <p>Side effects: none. Pure function; no locale, charset or clock is consulted, so the text is byte
     * identical on every platform and every run.
     *
     * @param ioStatus          the raw status, which may be {@code null}, shorter or longer
     * @param logicalFileName   the logical file or DD name, or {@code null}
     * @param operation         the attempted operation, or {@code null}
     * @param legacyFailureText the exact literal the source displays at this site, or {@code null}
     * @return the assembled message, never {@code null} and never containing the text {@code null}
     */
    private static String failureMessage(String ioStatus, String logicalFileName, String operation,
            String legacyFailureText) {
        StringBuilder message = new StringBuilder(192);
        if (legacyFailureText != null) {
            message.append(legacyFailureText).append(" - ");
        }
        message.append(orPlaceholder(operation, UNIDENTIFIED_OPERATION))
                .append(" of ")
                .append(orPlaceholder(logicalFileName, UNIDENTIFIED_FILE))
                .append(" reported COBOL FILE STATUS ")
                .append(FileStatus.escapeForDiagnostics(orPlaceholder(ioStatus, ABSENT_VALUE)))
                .append(" (IO-STATUS-04 ")
                .append(FileStatus.renderIoStatus04ForDiagnostics(ioStatus))
                .append(')');
        return message.toString();
    }

    /**
     * Substitutes a fixed placeholder for a value the caller did not supply, so that the four characters
     * {@code null} can never reach a log line through a message this class builds.
     *
     * @param value the caller supplied value, which may be {@code null}, empty or whitespace only
     * @param placeholder the fixed text to substitute, never {@code null}
     * @return the value exactly as supplied when it contains a non whitespace character, otherwise the
     * placeholder
     */
    private static String orPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }

    // Store condition discrimination.
    //
    // Not a paragraph of any program. This is the mechanism that stands in for the RESP value CICS
    // handed back from EXEC CICS WRITE and EXEC CICS READ against a VSAM KSDS. A KSDS could fail a
    // keyed write for exactly one reason a program cared to name - the key was already present, which
    // CICS reported as DFHRESP(DUPKEY) or DFHRESP(DUPREC) - and everything else fell into the
    // programs' WHEN OTHER arm. A relational store fails the same statement for several reasons that
    // are genuinely different from one another and from a duplicate key, and it names which one in the
    // SQLSTATE. Collapsing them all onto the duplicate arm reports a referential refusal, a check
    // refusal and an unrepresentable value as "the key already exists", which is not merely imprecise
    // but actively misleading: it invites a retry that can never succeed.
    //
    // The classification below is therefore the target side of the source's WHEN OTHER arm rather than
    // a replacement for it. Each caller keeps its own source literal, its own cursor field and its own
    // screen assembly exactly as the source writes them; what this method decides is only WHICH typed
    // exception - and therefore which HTTP status - carries that unchanged outcome outwards.

    /**
     * The conditions a keyed write or a keyed read can raise in the relational store, in the vocabulary
     * the COBOL response arms distinguish.
     *
     * <p>The four constants are the complete partition of the space, and each maps onto exactly one arm of
     * the source's {@code EVALUATE WS-RESP-CD}:
     *
     * <ul>
     *   <li>{@link #DUPLICATE_KEY} is {@code DFHRESP(DUPKEY)} at {@code app/cbl/COUSR01C.cbl:L260} and
     *       {@code DFHRESP(DUPREC)} at {@code :L261}, and the shared branch of
     *       {@code app/cbl/COTRN02C.cbl:735-741}. It is the one condition a KSDS could raise on a write
     *       that the source names, so it is the one condition that keeps the duplicate arm.</li>
     *   <li>{@link #CONSTRAINT_REFUSED} is the source's {@code WHEN OTHER} arm reached because the store
     *       enforced a rule the KSDS never had. {@code V1__create_schema.sql} declares ten foreign keys
     *       and five check constraints that VSAM did not, so this condition is target-only by
     *       construction and cannot be traced to a response code the source ever saw.</li>
     *   <li>{@link #INVALID_DATA} is the same {@code WHEN OTHER} arm reached because a supplied value
     *       cannot be represented at all - SQLSTATE class {@code 22}, of which
     *       {@code 22021 invalid byte sequence for encoding "UTF8": 0x00} is the reachable member, raised
     *       by a {@code NUL} inside a value bound to a text column. A fixed-width COBOL field held that
     *       byte happily, which is why the source has no arm for it.</li>
     *   <li>{@link #IO_ERROR} is the {@code WHEN OTHER} arm reached because the store could not be
     *       interrogated - a connection, timeout or resource condition. This is the only member for which
     *       a retry is a rational response, and it is the only member that keeps a {@code 5xx} status.</li>
     * </ul>
     */
    public enum StoreFailureKind {

        /**
         * The key presented to an insert is already present: SQLSTATE
         * {@value FileStatusMapper#SQLSTATE_UNIQUE_VIOLATION}, or a unique or primary-key constraint named by
         * the driver.
         *
         * <p>The only condition that may be reported as {@code FILE STATUS '22'} and answered {@code 409}.
         * AAP section 0.7.4.1 requires exactly this for the retained identifier generation race: the racy
         * descending browse is kept and the primary key constraint surfaces a collision as a duplicate record
         * condition. {@code app/cbl/COUSR01C.cbl:L260-L261}, {@code app/cbl/COTRN02C.cbl:L735} and
         * {@code app/cbl/COBIL00C.cbl:L533} are the three {@code DUPKEY} sites in the corpus.
         */
        DUPLICATE_KEY,

        /**
         * A constraint other than a unique key refused the statement: a foreign key, a check constraint or
         * a {@code NOT NULL} column, which is SQLSTATE class {@code 23} excluding {@code 23505}.
         */
        CONSTRAINT_REFUSED,

        /**
         * A supplied value cannot be represented in the column it was bound to: SQLSTATE class
         * {@value FileStatusMapper#SQLSTATE_CLASS_DATA_EXCEPTION}.
         *
         * <p>A request error, and a <strong>labelled addition</strong> rather than parity: a census of
         * {@code app/cbl} at {@code 7756d89} finds no guard for it, because no such condition existed. The
         * terminal delivered a fixed width field and a value that fitted its {@code PIC} clause was storable
         * by construction. Reporting it as a duplicate tells a caller its identifier is taken when it is
         * free; reporting it as an I/O condition tells a caller the store is broken when the request was.
         */
        INVALID_DATA,

        /**
         * The store could not be interrogated, or reported a condition none of the above describes.
         */
        IO_ERROR
    }

    /**
     * SQLSTATE class {@code 23}, {@code integrity_constraint_violation}, {@value}: {@code 23502} not null,
     * {@code 23503} foreign key, {@code 23514} check, and the generic {@code 23000}.
     *
     * <p>Published alongside {@link #SQLSTATE_UNIQUE_VIOLATION} because the two together are the whole of the
     * distinction the classification exists to draw: {@value #SQLSTATE_UNIQUE_VIOLATION} is the one member of
     * this class that is a duplicate key, and every other member is a constraint the KSDS never had.
     */
    public static final String SQLSTATE_CLASS_INTEGRITY = "23";

    /** The width of the SQLSTATE class, which is the first two of its five characters, {@value}. */
    private static final int SQLSTATE_CLASS_LENGTH = 2;

    /**
     * Decides which store condition a data-access failure represents, by reading the SQLSTATE the driver
     * reported rather than by inspecting the exception type the translator chose.
     *
     * <p><strong>Findings F-2 and F-8, severity High, and finding 4, severity High - this method is the
     * remediation of all three.</strong> Four write sites caught
     * {@code org.springframework.dao.DataIntegrityViolationException} and mapped it <em>unconditionally</em>
     * to {@code DFHRESP(DUPREC)}. So a value the column could not hold was reported as
     * {@code 409 CARDDEMO-DUPLICATE-RECORD} naming an identifier that was in fact free, a referential refusal
     * was reported as a collision with a retry hint that could never succeed, and - from the other side - the
     * duplicate answer AAP section 0.7.4.1 requires for the retained identifier race depended on a subtype
     * choice that is not a property of the persistence layer. Deciding on the state fixes every direction at
     * once.
     *
     * <p><strong>Why the SQLSTATE and not the exception type.</strong> Spring's translation maps SQLSTATE
     * class {@code 22} and class {@code 23} alike onto {@code DataIntegrityViolationException}, so the
     * exception type cannot separate a duplicate key from a foreign-key refusal from an unrepresentable
     * value. The SQLSTATE can, it is standardised in its first two characters, and PostgreSQL publishes the
     * full five. Reading it is therefore the only classification that is both correct and portable.
     *
     * <p><strong>Why both chains are walked.</strong> The SQLSTATE is not on the exception the caller
     * catches. A batched statement fails as a Spring {@code DataIntegrityViolationException} wrapping a
     * Hibernate {@code ConstraintViolationException} wrapping a {@link java.sql.BatchUpdateException}, and
     * the driver puts the failing statement's own state on a further exception reachable only through
     * {@link SQLException#getNextException()}. So the walk descends the cause chain, and at every
     * {@link SQLException} it finds it also walks that exception's next-exception chain. The first
     * recognised state wins; both walks are depth-capped and both guard against a self-referencing link.
     *
     * <p><strong>A state that is present but unrecognised is an I/O condition, not a fallback.</strong> If the
     * driver reported a SQLSTATE and it is in neither class {@code 22} nor class {@code 23}, then the driver has
     * already answered the question: the condition is neither a constraint nor an unrepresentable value, so it
     * is {@link StoreFailureKind#IO_ERROR} and the type-based fallback is not consulted. This distinction
     * matters, because the type-based fallback would otherwise convict a connection or cancellation failure of
     * being a constraint refusal purely because of how it happened to be wrapped.
     *
     * <p><strong>Why there is a fallback at all.</strong> If no SQLSTATE is reachable anywhere - a translator
     * that discarded it, or a failure raised above the driver - the decision falls back on the translated type,
     * which is strictly less precise but never less safe: {@code DuplicateKeyException} is a duplicate key
     * by definition, a {@code DataIntegrityViolationException} that is not one is a constraint refusal by
     * elimination, and anything else is an I/O condition. The fallback deliberately does not answer
     * {@link StoreFailureKind#DUPLICATE_KEY} for a bare integrity violation, because that is precisely the
     * conflation this method exists to end.
     *
     * <p>Side effects: none. A pure function of the throwable it is given, performing no I/O, holding no
     * state and logging nothing - the call site owns the diagnostic, as it owns the {@code DFHRESP}
     * vocabulary.
     *
     * <p><b>Failure modes.</b> Severity <b>High</b>: passing the exception the repository call site
     * <em>rethrew</em> rather than the one it caught, which discards the driver's exception and yields
     * {@link StoreFailureKind#IO_ERROR} for every condition. Remedy: classify the caught throwable, before
     * wrapping. Severity <b>Low</b>: a store other than PostgreSQL that reports a unique violation with a
     * vendor specific state; the type-based fallback below covers it.
     *
     * @param failure the failure a data-access attempt raised, which may be {@code null}
     * @return the condition it represents; {@link StoreFailureKind#IO_ERROR} when nothing more specific can
     * be established, and never {@code null}
     */
    public static StoreFailureKind classifyStoreFailure(Throwable failure) {
        boolean sawSqlState = false;
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof SQLException sqlFailure) {
                StoreFailureKind fromDriver = classifySqlExceptionChain(sqlFailure);
                if (fromDriver != null) {
                    return fromDriver;
                }
                sawSqlState |= carriesAnySqlState(sqlFailure);
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
            depth++;
        }
        if (sawSqlState) {
            return StoreFailureKind.IO_ERROR;
        }
        if (failure instanceof DuplicateKeyException) {
            return StoreFailureKind.DUPLICATE_KEY;
        }
        if (failure instanceof DataIntegrityViolationException) {
            return StoreFailureKind.CONSTRAINT_REFUSED;
        }
        return StoreFailureKind.IO_ERROR;
    }

    /**
     * Walks one {@link SQLException} and every exception linked to it through
     * {@link SQLException#getNextException()}, returning the condition the first recognised SQLSTATE names.
     *
     * @param head the first exception of the chain, never {@code null}
     * @return the condition the chain names, or {@code null} when no exception in it carries a SQLSTATE this
     * class recognises
     */
    private static StoreFailureKind classifySqlExceptionChain(SQLException head) {
        SQLException current = head;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            StoreFailureKind recognised = classifySqlState(current.getSQLState());
            if (recognised != null) {
                return recognised;
            }
            SQLException next = current.getNextException();
            current = next == current ? null : next;
            depth++;
        }
        return null;
    }

    /**
     * Reports whether any exception in one next-exception chain carries a SQLSTATE at all, so that a state the
     * driver supplied but this class does not distinguish suppresses the type-based fallback.
     *
     * @param head the first exception of the chain, never {@code null}
     * @return {@code true} when at least one link carries a non-blank SQLSTATE
     */
    private static boolean carriesAnySqlState(SQLException head) {
        SQLException current = head;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            String sqlState = current.getSQLState();
            if (sqlState != null && !sqlState.isBlank()) {
                return true;
            }
            SQLException next = current.getNextException();
            current = next == current ? null : next;
            depth++;
        }
        return false;
    }

    /**
     * Maps one SQLSTATE onto the condition it names.
     *
     * @param sqlState the five character SQLSTATE, which may be {@code null} or shorter than its class
     * @return the condition it names, or {@code null} when it is absent, too short, or in a class this
     * classification does not distinguish
     */
    private static StoreFailureKind classifySqlState(String sqlState) {
        if (sqlState == null || sqlState.length() < SQLSTATE_CLASS_LENGTH) {
            return null;
        }
        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return StoreFailureKind.DUPLICATE_KEY;
        }
        String stateClass = sqlState.substring(0, SQLSTATE_CLASS_LENGTH);
        if (SQLSTATE_CLASS_INTEGRITY.equals(stateClass)) {
            return StoreFailureKind.CONSTRAINT_REFUSED;
        }
        if (SQLSTATE_CLASS_DATA_EXCEPTION.equals(stateClass)) {
            return StoreFailureKind.INVALID_DATA;
        }
        return null;
    }
}
