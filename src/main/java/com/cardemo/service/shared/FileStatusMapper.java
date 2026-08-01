/*
 * ******************************************************************
 * Component   : FileStatusMapper
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
import java.util.Optional;
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
 * </ul>
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
 *       exception by {@link #toException(String, String, String)} or by
 *       {@link #requireSuccessOrEndOfFile(String, String, String)}.</li>
 *   <li>{@code '22'} duplicate key - {@code com.cardemo.exception.DuplicateRecordException}.</li>
 *   <li>{@code '23'} record not found - {@code com.cardemo.exception.RecordNotFoundException}, except at
 *       the three scoped sites described below.</li>
 *   <li>{@code '35'} file unavailable - {@code com.cardemo.exception.FileUnavailableException}.</li>
 *   <li>{@code '9x'} physical or logical I/O error - {@code com.cardemo.exception.FileAccessException},
 *       carrying the four character expansion.</li>
 *   <li>anything else - {@code com.cardemo.exception.FatalProcessingException}, abend code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.</li>
 * </ul>
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
 *       {@link #requireSuccessOrEndOfFile(String, String, String)}.</li>
 * </ul>
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
 * </ol>
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
 * {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, so an unused import, a raw type or a
 * switch fall through is a build failure rather than a warning. Build with {@code ./mvnw -B clean compile},
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
 * </ul>
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
 * nothing. This is recorded here so that a later reviewer does not harden it by mistake.
 *
 * <h2>Findings carried by this translation, by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - mis-rendering the {@code FILE STATUS IS: NNNN} line. Remedy: never
 *       assemble it by hand; call {@link #displayIoStatus(String)}, which concatenates the two owned
 *       parts and substitutes nothing.</li>
 *   <li><strong>Blocker</strong> - omitting the low order byte mask in the three digit expansion, which
 *       sign extends any byte above {@code 0x7F} into a negative number and destroys the three digit
 *       width. Remedy: the mask is applied once, inside
 *       {@code FileStatus.renderIoStatus04(String)}; never re-derive the expansion.</li>
 *   <li><strong>Blocker</strong> - mapping a missing disclosure group default row to a not found. It
 *       abends. Remedy: use {@link #requireDefaultDisclosureGroupReadSuccess(String)}, which raises the
 *       fatal type for {@code '23'} exactly as {@code app/cbl/CBACT04C.cbl:L446-L458} does.</li>
 *   <li><strong>High</strong> - treating {@code '10'} as an error. Remedy: use
 *       {@link #requireSuccessOrEndOfFile(String, String, String)} at a sequential read.</li>
 *   <li><strong>High</strong> - admitting {@code '04'} to the general map. Remedy: it is reachable only
 *       through the two file service methods, and no general method accepts it.</li>
 *   <li><strong>High</strong> - implementing the rendering a second time in another class. Remedy: there
 *       is exactly one implementation, in {@code FileStatus}, and this class delegates to it.</li>
 *   <li><strong>Medium</strong> - citing the renderer paragraph as ending at line 731. Its body ends at
 *       {@code app/cbl/CBTRN02C.cbl:L727}; lines 728 onward are version stamp comments.</li>
 *   <li><strong>Medium</strong> - citing line 445 for the disclosure group retry guard. The verified line
 *       is {@code app/cbl/CBACT04C.cbl:L446}.</li>
 *   <li><strong>Low</strong> - the source writes {@code '00'} then two spaces then {@code OR '23'} at
 *       both lenient guards, so a naive single space search finds neither.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><strong>Not available:</strong> any service level objective for I/O latency or throughput. None
 *       exists anywhere in the frozen corpus - the legacy system publishes no service level agreement, and
 *       the performance gate records a measured baseline rather than a target. What would be needed is a
 *       published latency or throughput requirement in the source; there is none, and none is invented
 *       here.</li>
 *   <li><strong>Not available:</strong> a literal source site for {@code '22'} or for {@code '35'}. A
 *       census of {@code app/cbl} at commit {@code 7756d89} finds {@code '00'} 88 times in 9 files,
 *       {@code '04'} 9 times in 1 file, {@code '10'} 11 times in 9 files, {@code '23'} 3 times in 2
 *       files, and {@code '22'} and {@code '35'} <strong>zero times each</strong>. Both are reachable
 *       only through the else arm of the binary guard, so their mappings are retained as runtime
 *       reachable rather than deleted; see the notes on them in {@code failureFor}. What would be needed
 *       is a COBOL guard that tests those two literals; none exists. The CICS side has its own separate
 *       vocabulary, 23 sites of {@code DFHRESP(NOTFND)}, 7 of {@code DFHRESP(DUPREC)}, 3 of
 *       {@code DFHRESP(DUPKEY)} and none at all of {@code DFHRESP(NOTOPEN)}, and this class deliberately
 *       does not conflate the two.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A log line reading {@code FILE STATUS IS: 0023} rather than {@code FILE STATUS IS: NNNN0023}
 *       means something substituted the rendering into the placeholder instead of appending after it.
 *       Remedy: call {@link #displayIoStatus(String)} and log its result verbatim.</li>
 *   <li>An abend where an orderly end of loop was expected means a sequential read used the two way
 *       guard. Remedy: switch that call site to
 *       {@link #requireSuccessOrEndOfFile(String, String, String)}.</li>
 *   <li>A {@code RecordNotFoundException} escaping the interest calculation job means the default
 *       disclosure group retry used a general method. Remedy: switch it to
 *       {@link #requireDefaultDisclosureGroupReadSuccess(String)}.</li>
 *   <li>An abend on the first daily transaction whose category balance row does not yet exist means the
 *       upsert used a general method. Remedy: switch it to
 *       {@link #requireCategoryBalanceReadSuccess(String)} and branch on the returned flag.</li>
 *   <li>A corrupt read silently accepted anywhere outside the statement file service means {@code '04'}
 *       leaked into a general path. Remedy: only the two file service methods may accept it.</li>
 *   <li>A {@code FatalProcessingException} whose message reads {@code (absent)} where a status should be,
 *       and whose expansion reads {@code  032}, means the caller passed {@code null} or an empty status.
 *       That is reported rather than absorbed by design: the renderer is total and normalises the value
 *       exactly as a {@code MOVE} into {@code PIC X(02)} would, padding to two spaces, and a space is 32.
 *       Remedy: find the upstream code that lost the status, rather than defaulting it to {@code '00'}.</li>
 *   <li>A message that contains a record key, an account identifier or a card number means a caller passed
 *       one as the logical file name. Remedy: pass the DD or logical file name only; this class adds no
 *       other content and deliberately passes no key to the exception types that accept one.</li>
 * </ul>
 */
@Component
public class FileStatusMapper {

    /**
     * The value the guard moves into {@code APPL-RESULT} <em>before</em> performing the verb, from
     * {@code MOVE 8 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L237 0000-DALYTRAN-OPEN}.
     *
     * <p>It is a sentinel meaning "the verb has not reported yet", and it is deliberately not one of the
     * two declared condition names at {@code app/cbl/CBTRN02C.cbl:L143-L144}. No method on this class ever
     * returns it, because every method here runs strictly after the verb has reported. It is published so
     * that a batch step reproducing the full paragraph can seed its own result field with the same value
     * the corpus uses, rather than inventing one.
     */
    public static final int APPL_RESULT_INITIAL = 8;

    /**
     * The success value of {@code APPL-RESULT}, from the condition name {@code 88 APPL-AOK VALUE 0} at
     * {@code app/cbl/CBTRN02C.cbl:L143}, moved in by {@code MOVE 0 TO APPL-RESULT} at
     * {@code app/cbl/CBTRN02C.cbl:L240}.
     */
    public static final int APPL_AOK = 0;

    /**
     * The end of file value of {@code APPL-RESULT}, from the condition name
     * {@code 88 APPL-EOF VALUE 16} at {@code app/cbl/CBTRN02C.cbl:L144}, moved in by
     * {@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L352}.
     *
     * <p><strong>Sixteen, not twelve.</strong> Assuming twelve would make an orderly end of file
     * indistinguishable from a failure and would abend the last iteration of every sequential read loop in
     * the corpus.
     */
    public static final int APPL_EOF = 16;

    /**
     * The failure value of {@code APPL-RESULT}, from {@code MOVE 12 TO APPL-RESULT} at
     * {@code app/cbl/CBTRN02C.cbl:L242} and {@code app/cbl/CBTRN02C.cbl:L354}.
     *
     * <p>Unlike success and end of file it has no condition name in the source; it is only ever a working
     * value, which is why the guard tests it by exclusion rather than by name.
     *
     * <p>It happens to equal the process return code an abend yields, but the two are unrelated
     * quantities: this is an in-program work field, and the return code is owned by
     * {@code FatalProcessingException}. They are never substituted for one another.
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
     *
     * <p>Eight characters is also exactly the width of a COBOL program name, which is why the culprit
     * supplied at each scoped site below is the originating program identifier and fits without padding.
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
     *
     * <p>Recorded as the documented legacy width only. Messages are never padded or truncated to it,
     * because truncating a diagnostic to a fixed display width would destroy the context Rule 1 Clause B
     * requires an error to preserve, and nothing in the target renders this field to a 3270 screen.
     */
    public static final int ABEND_MESSAGE_WIDTH = 72;

    /**
     * The unset value of {@code ABEND-CODE}: {@value #ABEND_CODE_WIDTH} spaces, reproducing the
     * {@code VALUE SPACES} initialisation at {@code app/cpy/CSMSG02Y.cpy:L22-L23}.
     *
     * <p>Spaces rather than null, because that is what the copybook declares and what an operator reading
     * the abend block would have seen. The batch terminator carries no four character display code at all
     * - it moves the numeric
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} into a binary field at
     * {@code app/cbl/CBTRN02C.cbl:L710} instead - so this is the correct value for every abend this class
     * raises.
     */
    public static final String ABEND_CODE_UNSET = " ".repeat(ABEND_CODE_WIDTH);

    /**
     * The unset value of {@code ABEND-CULPRIT}: {@value #ABEND_CULPRIT_WIDTH} spaces, reproducing the
     * {@code VALUE SPACES} initialisation at {@code app/cpy/CSMSG02Y.cpy:L24-L25}.
     *
     * <p>Used on the general paths, where the culprit is genuinely unknown because this class serves every
     * program in the corpus. The scoped methods name their originating program instead, because at those
     * sites it is known exactly.
     */
    public static final String ABEND_CULPRIT_UNSET = " ".repeat(ABEND_CULPRIT_WIDTH);

    /**
     * The exact literal displayed before the transaction category balance read abends, from
     * {@code DISPLAY 'ERROR READING TRANSACTION BALANCE FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L489 2700-UPDATE-TCATBAL}.
     *
     * <p>Held as a constant because the parity gate compares this text byte for byte. Thirty eight
     * characters, so it fits {@code ABEND-REASON} without padding or truncation.
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
     *
     * <p>A distinct literal from {@link #DISCGRP_READ_FAILURE_TEXT}: the source distinguishes the initial
     * lenient lookup from the strict retry, and so does this class.
     */
    public static final String DEFAULT_DISCGRP_READ_FAILURE_TEXT = "ERROR READING DEFAULT DISCLOSURE GROUP";

    /**
     * The exact literal the statement generator displays before an abend to report the file service return
     * code, from {@code DISPLAY 'RETURN CODE: ' WS-M03B-RC} at {@code app/cbl/CBSTM03A.CBL:L740} and its
     * twelve siblings. The trailing space is part of the literal and is preserved.
     *
     * <p>Note what this literal implies: the file service sites report the <strong>raw</strong> two
     * character return code and never the four character expansion, because none of the thirteen performs
     * the renderer paragraph. See {@link #requireFileServiceSuccess(String, String, String)}.
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
     * {@code ABEND-CULPRIT} by the two file service methods. Exactly {@value #ABEND_CULPRIT_WIDTH}
     * characters.
     */
    private static final String CULPRIT_STATEMENT = "CBSTM03A";

    /**
     * The DD name of the transaction category balance file, from
     * {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF} at {@code app/cbl/CBTRN02C.cbl:L57}.
     */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * The DD name of the disclosure group file, from {@code SELECT DISCGRP-FILE ASSIGN TO DISCGRP} at
     * {@code app/cbl/CBACT04C.cbl:L47}.
     */
    private static final String DD_DISCGRP = "DISCGRP";

    /**
     * The verb recorded for every read this class guards. The corpus does not distinguish the verbs by
     * status - one guard shape covers all of them - so the verb travels as data.
     */
    private static final String OPERATION_READ = "READ";

    /**
     * The reason recorded on a general abend, where the corpus has no single literal to reproduce because
     * the displayed text differs at every one of the seventy eight guard sites - {@code 'ERROR OPENING
     * DALYTRAN'} at {@code app/cbl/CBTRN02C.cbl:L247} and {@code 'ERROR READING DALYTRAN FILE'} at
     * {@code app/cbl/CBTRN02C.cbl:L363} being two of them.
     *
     * <p><strong>This is target side text, not a reproduced legacy literal</strong>, and it is deliberately
     * excluded from any byte for byte parity comparison. The literals that <em>are</em> compared are the
     * four held as constants above. Forty six characters, so it fits {@code ABEND-REASON}.
     */
    private static final String UNRECOGNISED_STATUS_REASON = "UNRECOGNISED COBOL FILE STATUS AT I/O GUARD";

    /**
     * The reason recorded when the statement file service reports a return code its call site does not
     * accept. Target side text, for the same reason as {@link #UNRECOGNISED_STATUS_REASON}.
     */
    private static final String FILE_SERVICE_REASON = "UNACCEPTED RETURN CODE FROM STATEMENT FILE SERVICE";

    /**
     * The placeholder rendered in place of a logical file or DD name the caller did not identify, so that
     * the four characters {@code null} never appear in a log line.
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
     * Creates the stateless mapper.
     *
     * <p>Declared explicitly so that it carries documentation, and left empty deliberately: this class has
     * <strong>no collaborator to inject</strong>. Its only dependencies are a static enumeration and a set
     * of exception types, neither of which is a bean. Constructor injection is therefore satisfied
     * vacuously rather than avoided, and there is no field injection, no setter injection and no service
     * locator anywhere in this class.
     *
     * <p>Every instance is immutable and holds no state at all, so the single bean Spring creates is
     * inherently thread safe and every method on it is a pure function of its arguments. Instance methods
     * rather than static ones are used so that a collaborator can inject and, where useful, substitute this
     * component in a test.
     *
     * <p>Side effects: none.
     */
    public FileStatusMapper() {
        // No collaborator, no configuration and no state to initialise. See the constructor Javadoc.
    }

    /**
     * Reproduces {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} by assembling
     * the exact line that paragraph writes to SYSOUT, and returns it for the caller to log.
     *
     * <p>The paragraph body, verbatim, ends at {@code :L727}; lines 728 onward are version stamp comments
     * and are not code. Both of its branches end in the same statement,
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}, at {@code :L721} and {@code :L725}
     * respectively.
     *
     * <p><strong>Blocker severity: {@code NNNN} is a literal, not a placeholder.</strong> COBOL
     * {@code DISPLAY a b} concatenates its operands with no separator, so the twenty character literal is
     * emitted whole and the four rendered characters follow it. The original author left the placeholder in
     * the text while also appending the real value, and the resulting quirk is part of the observable
     * contract:
     *
     * <ul>
     *   <li>status {@code '23'} produces {@code FILE STATUS IS: NNNN0023}</li>
     *   <li>status {@code '10'} produces {@code FILE STATUS IS: NNNN0010}</li>
     *   <li>status {@code '00'} produces {@code FILE STATUS IS: NNNN0000}</li>
     *   <li>a first byte of {@code '9'} with a second byte of {@code 0x01} produces
     *       {@code FILE STATUS IS: NNNN9001}</li>
     * </ul>
     *
     * <p>Removing the placeholder, substituting the value into it, inserting a separator or reformatting
     * the line each produce a diff against the legacy baseline. The line must also reach the log
     * <strong>unmasked</strong>: it carries no personally identifiable value, so no masking rule in
     * {@code logback-spring.xml} may touch it.
     *
     * <p><strong>Neither of the two owned parts is recomputed here.</strong> The prefix is the constant
     * declared once by {@code FileStatus}, and the four character expansion is produced once by
     * {@code FileStatus.renderIoStatus04(String)} - including the mandatory low order byte mask that stops
     * a second status byte above {@code 0x7F} from sign extending into a negative number. This method only
     * concatenates them, which is the one part {@code FileStatus} deliberately leaves undone because it
     * does not log.
     *
     * <p>Side effects: none. <strong>This method does not log.</strong> It returns the text and the caller
     * decides whether, when and at what level to emit it, which is what keeps this class free of a logging
     * concern.
     *
     * <p>Error modes: none. The method is total and never throws, because it runs on a path where
     * something has already failed and throwing from a diagnostic would destroy the very context the
     * diagnostic exists to report. A {@code null}, empty, short or over long status is normalised exactly
     * as a COBOL {@code MOVE} into {@code PIC X(02)} normalises it.
     *
     * @param ioStatus the raw file status, ordinarily two characters, and tolerated when {@code null},
     *                 shorter or longer
     * @return the complete legacy line, never {@code null}, always the twenty character prefix followed
     *         immediately by exactly four rendered characters, and therefore always twenty four characters
     *         long
     */
    public String displayIoStatus(String ioStatus) {
        return FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(ioStatus);
    }

    /**
     * Reproduces the two way guard's arithmetic from
     * {@code app/cbl/CBTRN02C.cbl:L239-L243 0000-DALYTRAN-OPEN}:
     *
     * <pre>
     * IF  DALYTRAN-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     MOVE 12 TO APPL-RESULT
     * END-IF
     * </pre>
     *
     * <p>The test is binary - {@code '00'} against everything else - and this is the shape every
     * {@code OPEN}, {@code CLOSE}, {@code WRITE} and {@code REWRITE} guard in the corpus uses. End of file
     * is not among its outcomes, which is why a status of {@code '10'} yields {@value #APPL_FAILURE} here.
     *
     * <p>{@value #APPL_RESULT_INITIAL} is never returned: the corpus moves it in before the verb, and this
     * method is only ever evaluated after the verb has reported.
     *
     * <p>Side effects: none. Pure function of its argument; no locale, charset or clock is consulted.
     *
     * <p>Error modes: none. A {@code null} or malformed status is not {@code '00'} and therefore yields
     * {@value #APPL_FAILURE}, which is the safe direction: an unusable status is treated as a failure
     * rather than as a success.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@value #APPL_AOK} when the status is exactly {@code '00'}, otherwise
     *         {@value #APPL_FAILURE}
     */
    public int applResultForGuard(String ioStatus) {
        return FileStatus.SUCCESS.matches(ioStatus) ? APPL_AOK : APPL_FAILURE;
    }

    /**
     * Reproduces the three way sequential read guard's arithmetic from
     * {@code app/cbl/CBTRN02C.cbl:L347-L356 1000-DALYTRAN-GET-NEXT}:
     *
     * <pre>
     * IF  DALYTRAN-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     IF  DALYTRAN-STATUS = '10'
     *         MOVE 16 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     * END-IF
     * </pre>
     *
     * <p>The same nested test recurs at {@code app/cbl/CBACT01C.cbl:L94-L103},
     * {@code app/cbl/CBACT02C.cbl:L98}, {@code app/cbl/CBACT03C.cbl:L98},
     * {@code app/cbl/CBCUS01C.cbl:L98}, {@code app/cbl/CBACT04C.cbl:L330} and
     * {@code app/cbl/CBTRN01C.cbl:L207}. Downstream, the corpus distinguishes the two non success outcomes
     * by condition name: {@code IF APPL-EOF MOVE 'Y' TO END-OF-FILE} at
     * {@code app/cbl/CBTRN02C.cbl:L360-L361} terminates the loop, and only the remaining case reaches the
     * renderer and the abend at {@code :L365-L366}.
     *
     * <p>Side effects: none. Pure function of its argument.
     *
     * <p>Error modes: none. A {@code null} or malformed status matches neither literal and therefore
     * yields {@value #APPL_FAILURE}.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@value #APPL_AOK} for {@code '00'}, {@value #APPL_EOF} for {@code '10'}, otherwise
     *         {@value #APPL_FAILURE}
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
     * <p>This is the mapping decision itself, expressed as a value rather than as control flow, so that it
     * can be asserted directly by a test and composed by a caller that wants to inspect the outcome before
     * acting on it. It applies the map documented on this class in full: {@code '22'} to a duplicate record,
     * {@code '23'} to a record not found, {@code '35'} to a file unavailable, the {@code '9x'} family to a
     * file access failure carrying the four character expansion, and anything else - {@code '04'} included
     * - to a fatal abend.
     *
     * <p><strong>An empty result means no exception is warranted, and it covers two different
     * situations.</strong> {@code '00'} is success and {@code '10'} is <em>end of file, which is loop
     * termination and not an error</em>. The two are distinguished by
     * {@link #applResultForSequentialRead(String)}, whose result this method consumes, so a caller that
     * needs to tell them apart should use that method or
     * {@link #requireSuccessOrEndOfFile(String, String, String)} rather than inferring from an empty
     * result.
     *
     * <p><strong>High severity:</strong> because this method treats {@code '10'} as benign, it must not be
     * used to implement an {@code OPEN}, {@code CLOSE}, {@code WRITE} or {@code REWRITE} guard, where the
     * corpus sends {@code '10'} to the abend path. Use {@link #requireSuccess(String, String, String)}
     * there; it applies the two way arithmetic instead.
     *
     * <p>Side effects: none beyond constructing the returned throwable when one is warranted. Nothing is
     * thrown, logged or counted, and the returned exception is not filled in with a stack trace until it is
     * constructed here - so a caller that discards the result has still paid for that construction, which is
     * the one reason to prefer the {@code require} methods on a hot path.
     *
     * <p>Error modes: none. This method cannot fail.
     *
     * @param ioStatus        the raw two character file status, which may be {@code null}, shorter or
     *                        longer, and which is reported rather than coerced into a success
     * @param logicalFileName the logical file or DD name whose I/O reported the status, for example
     *                        {@code "DALYTRAN"} or {@code "ACCTDAT"}. Permitted to be {@code null}. Pass an
     *                        identity only - <strong>never a record key, a record image, an account
     *                        identifier or a card number</strong>
     * @param operation       the attempted operation, for example {@code "OPEN"} or {@code "READ"}.
     *                        Permitted to be {@code null}
     * @return the exception the status maps to, or an empty {@link Optional} for {@code '00'} and
     *         {@code '10'}. Never {@code null}
     */
    public Optional<CardDemoException> toException(String ioStatus, String logicalFileName, String operation) {
        return toException(ioStatus, logicalFileName, operation, null);
    }

    /**
     * Decides which exception a raw file status maps to, without throwing it, preserving an underlying
     * throwable as the cause.
     *
     * <p>Identical in every respect to {@link #toException(String, String, String)} except that the supplied
     * throwable is attached to the returned exception. <strong>This is the form to use inside a
     * {@code catch} block</strong>: the target has no {@code FILE STATUS} register, so a status reaching
     * this class has usually been derived from a driver or JDK failure, and dropping that failure is exactly
     * the swallowing of context Rule 1 Clause B forbids. The cause is delegated to the exception
     * constructor unchanged and is never inspected, unwrapped, rethrown or logged.
     *
     * <p>Side effects: none beyond constructing the returned throwable.
     *
     * <p>Error modes: none. This method cannot fail.
     *
     * @param ioStatus        the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation       the attempted operation, permitted to be {@code null}
     * @param cause           the underlying throwable to preserve, permitted to be {@code null} when the
     *                        status was reported without one, as it is in the corpus itself
     * @return the exception the status maps to, or an empty {@link Optional} for {@code '00'} and
     *         {@code '10'}. Never {@code null}
     */
    public Optional<CardDemoException> toException(String ioStatus, String logicalFileName, String operation,
            Throwable cause) {
        if (applResultForSequentialRead(ioStatus) != APPL_FAILURE) {
            return Optional.empty();
        }
        return Optional.of(failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, cause));
    }

    /**
     * Applies the two way guard of {@code app/cbl/CBTRN02C.cbl:L236-L252 0000-DALYTRAN-OPEN}: returns when
     * the status is {@code '00'} and throws otherwise.
     *
     * <p>This is the guard to use for an {@code OPEN}, a {@code CLOSE}, a {@code WRITE} or a
     * {@code REWRITE}. It accepts one value and one value only, so {@code '04'} and {@code '10'} are both
     * rejected here - correctly, because neither is a reachable outcome of those verbs in the corpus and
     * the source's own {@code ELSE} arm sends both to the abend path.
     *
     * <p>It also reproduces the source's ordering. The corpus renders the status and only then abends -
     * {@code PERFORM 9910-DISPLAY-IO-STATUS} at {@code :L249} precedes
     * {@code PERFORM 9999-ABEND-PROGRAM} at {@code :L250} - so the four character expansion is carried on
     * the thrown exception and the abend outcome is carried by the fatal type. Callers that want the
     * legacy SYSOUT line as well should log {@link #displayIoStatus(String)} before calling, exactly where
     * the corpus performs the renderer.
     *
     * <p>Side effects: none on the success path. On the failure path the only effect is the construction and
     * throwing of the exception; nothing is logged or counted.
     *
     * @param ioStatus        the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation       the attempted operation, permitted to be {@code null}
     * @throws com.cardemo.exception.DuplicateRecordException  when the status is {@code '22'}
     * @throws com.cardemo.exception.RecordNotFoundException   when the status is {@code '23'}
     * @throws com.cardemo.exception.FileUnavailableException  when the status is {@code '35'}
     * @throws com.cardemo.exception.FileAccessException       when the status is in the {@code '9x'} family
     * @throws com.cardemo.exception.FatalProcessingException  for every other status, including
     *                                                         {@code '04'}, {@code '10'} and any
     *                                                         unrecognised, {@code null} or malformed value
     */
    public void requireSuccess(String ioStatus, String logicalFileName, String operation) {
        requireSuccess(ioStatus, logicalFileName, operation, null);
    }

    /**
     * Applies the two way guard of {@code app/cbl/CBTRN02C.cbl:L236-L252 0000-DALYTRAN-OPEN}, preserving an
     * underlying throwable as the cause of anything it throws.
     *
     * <p>Identical to {@link #requireSuccess(String, String, String)} except that the supplied throwable
     * becomes the cause. This is the form required inside a {@code catch} block.
     *
     * <p>Side effects: none on the success path; on the failure path, construction and throwing only.
     *
     * @param ioStatus        the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation       the attempted operation, permitted to be {@code null}
     * @param cause           the underlying throwable to preserve, permitted to be {@code null}
     * @throws com.cardemo.exception.CardDemoException whenever the status is not exactly {@code '00'}; the
     *                                                 concrete subtype is chosen by the map documented on
     *                                                 {@link #requireSuccess(String, String, String)} and
     *                                                 always carries {@code cause}
     */
    public void requireSuccess(String ioStatus, String logicalFileName, String operation, Throwable cause) {
        if (applResultForGuard(ioStatus) == APPL_AOK) {
            return;
        }
        throw failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, cause);
    }

    /**
     * Applies the three way sequential read guard of
     * {@code app/cbl/CBTRN02C.cbl:L345-L369 1000-DALYTRAN-GET-NEXT}: reports end of file for {@code '10'},
     * returns for {@code '00'} and throws otherwise.
     *
     * <p>This is the guard to use for a sequential {@code READ}. The returned flag is the Java counterpart
     * of {@code MOVE 'Y' TO END-OF-FILE} at {@code :L361}, which is the loop's termination condition and
     * emphatically <strong>not</strong> an error - the corpus reaches the renderer and the abend at
     * {@code :L365-L366} only when the result field is neither {@value #APPL_AOK} nor {@value #APPL_EOF}.
     *
     * <p><strong>High severity:</strong> mapping {@code '10'} to an exception here would abend the final
     * iteration of every sequential read loop in the corpus, so this method never does.
     *
     * <p>No overload taking a cause is offered, and that is deliberate: this guard reads a status reported
     * by a completed read rather than adapting a thrown throwable, which is the shape the source has. Where
     * a throwable does exist, use {@link #toException(String, String, String, Throwable)} and act on the
     * result.
     *
     * <p>Side effects: none on either accepting path; on the failure path, construction and throwing only.
     *
     * @param ioStatus        the raw two character file status, which may be {@code null}, shorter or longer
     * @param logicalFileName the logical file or DD name, permitted to be {@code null}, and an identity only
     * @param operation       the attempted operation, permitted to be {@code null}
     * @return {@code true} when the status is {@code '10'} and the caller must stop reading, {@code false}
     *         when the status is {@code '00'} and a record was returned
     * @throws com.cardemo.exception.CardDemoException for every other status; the concrete subtype is chosen
     *                                                 by the map documented on
     *                                                 {@link #requireSuccess(String, String, String)}
     */
    public boolean requireSuccessOrEndOfFile(String ioStatus, String logicalFileName, String operation) {
        int applResult = applResultForSequentialRead(ioStatus);
        if (applResult == APPL_AOK) {
            return false;
        }
        if (applResult == APPL_EOF) {
            return true;
        }
        throw failureFor(ioStatus, logicalFileName, operation, null, ABEND_CULPRIT_UNSET, null);
    }

    /**
     * Scoped carve-out 1 of 3. Applies the lenient read guard of
     * {@code app/cbl/CBTRN02C.cbl:L467-L499 2700-UPDATE-TCATBAL}, where a record not found is an accepted
     * control path rather than an error because the paragraph is an upsert.
     *
     * <p>The source, with the guard at {@code :L481}:
     *
     * <pre>
     * READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *    INVALID KEY
     *      DISPLAY 'TCATBAL record not found for key : '
     *         FD-TRAN-CAT-KEY '.. Creating.'
     *      MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     * END-READ.
     *
     * IF  TCATBALF-STATUS = '00'  OR '23'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     MOVE 12 TO APPL-RESULT
     * END-IF
     * </pre>
     *
     * <p>The paragraph then dispatches on the flag, to {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L496}
     * or to {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L498}, and both branches add the transaction
     * amount to the category balance. The returned flag is precisely that dispatch decision.
     *
     * <p><strong>Low severity note on citing this line:</strong> the source writes {@code '00'} followed by
     * <em>two</em> spaces before {@code OR '23'}, so a search for a single space finds nothing at
     * {@code :L481}.
     *
     * <p><strong>The leniency is scoped to this read and stops here.</strong> The write verification inside
     * {@code 2700-A} and the rewrite verification inside {@code 2700-B} accept {@code '00'} and nothing
     * else, so those must use {@link #requireSuccess(String, String, String)}. Extending the tolerance to
     * them would silently accept a failed write.
     *
     * <p>On failure this method reproduces the source's own literal,
     * {@value #TCATBAL_READ_FAILURE_TEXT} from {@code :L489}, and names {@code CBTRN02C} as the abend
     * culprit.
     *
     * <p>Side effects: none on either accepting path; on the failure path, construction and throwing only.
     * Nothing is logged, so the caller remains responsible for emitting the legacy not found and creating
     * messages at {@code :L476-L477} if it wants them.
     *
     * @param ioStatus the raw two character status reported by the category balance read, which may be
     *                 {@code null}, shorter or longer
     * @return {@code true} when the status is {@code '23'} and the caller must take the create branch,
     *         {@code false} when the status is {@code '00'} and the caller must take the update branch
     * @throws com.cardemo.exception.CardDemoException for every other status; the concrete subtype is chosen
     *                                                 by the map documented on
     *                                                 {@link #requireSuccess(String, String, String)}, so
     *                                                 {@code '22'}, {@code '35'} and the {@code '9x'}
     *                                                 family remain typed and everything else is fatal
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
     * <p>The source, with the guard at {@code :L422} and the retry trigger at {@code :L436}:
     *
     * <pre>
     * READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     *      INVALID KEY
     *         DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
     *         DISPLAY 'TRY WITH DEFAULT GROUP CODE'
     * END-READ.
     *
     * IF  DISCGRP-STATUS  = '00'  OR '23'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     MOVE 12 TO APPL-RESULT
     * END-IF
     * ...
     * IF  DISCGRP-STATUS  = '23'
     *     MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     *     PERFORM 1200-A-GET-DEFAULT-INT-RATE
     * END-IF
     * </pre>
     *
     * <p>The returned flag is the {@code :L436} test. A caller acting on {@code true} must substitute the
     * literal default group identifier and read again, verifying that second read with
     * {@link #requireDefaultDisclosureGroupReadSuccess(String)} and <strong>not</strong> with this method.
     *
     * <p>Two further behaviours of the source belong to the caller rather than to this class, and are noted
     * so they are not lost: a zero rate produces no interest transaction and no accumulation
     * ({@code app/cbl/CBACT04C.cbl:L214}), and on an invalid key the read leaves the previous iteration's
     * record contents in place until the default read overwrites them, so a caller must not carry stale
     * rate state across iterations.
     *
     * <p><strong>Low severity note on citing this line:</strong> as at the other lenient guard, the source
     * writes {@code '00'} followed by two spaces before {@code OR '23'}.
     *
     * <p>On failure this method reproduces the source's own literal,
     * {@value #DISCGRP_READ_FAILURE_TEXT} from {@code :L431}, and names {@code CBACT04C} as the abend
     * culprit.
     *
     * <p>Side effects: none on either accepting path; on the failure path, construction and throwing only.
     *
     * @param ioStatus the raw two character status reported by the disclosure group read, which may be
     *                 {@code null}, shorter or longer
     * @return {@code true} when the status is {@code '23'} and the caller must retry against the default
     *         group, {@code false} when the status is {@code '00'} and a rate was returned
     * @throws com.cardemo.exception.CardDemoException for every other status; the concrete subtype is chosen
     *                                                 by the map documented on
     *                                                 {@link #requireSuccess(String, String, String)}
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
     * <p>The source, with the guard at {@code :L446}:
     *
     * <pre>
     * 1200-A-GET-DEFAULT-INT-RATE.
     *     READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     *
     *     IF  DISCGRP-STATUS  = '00'
     *         MOVE 0 TO APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     * </pre>
     *
     * <p>Two details make this paragraph different from the lookup that calls it, and both are load bearing.
     * The read at {@code :L444} carries <strong>no {@code INVALID KEY} clause at all</strong>, and the guard
     * accepts {@code '00'} <strong>alone</strong> - so a missing default row falls to the {@code ELSE},
     * reaches {@code DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'} at {@code :L455}, is rendered at
     * {@code :L457} and abends at {@code :L458}. The job stops.
     *
     * <p><strong>Blocker severity - the inversion this method exists to prevent.</strong> A status of
     * {@code '23'} here maps to {@code com.cardemo.exception.FatalProcessingException} and
     * <strong>never</strong> to {@code com.cardemo.exception.RecordNotFoundException}. Reaching this
     * paragraph already means the specific group was missing; the default row missing too means the
     * reference data itself is broken and the interest run cannot produce a correct result. Treating it as
     * an ordinary lookup miss would let the job continue and silently under-accrue interest, which is the
     * single most common error at this site. It is why this method exists separately from
     * {@link #requireDisclosureGroupReadSuccess(String)} rather than being a flag on it.
     *
     * <p><strong>Medium severity note on citing this line:</strong> the verified guard is at {@code :L446}.
     * An earlier draft of the specification cited {@code :L445}, which is the blank line the source leaves
     * between the read and the guard.
     *
     * <p>On failure this method reproduces the source's own literal,
     * {@value #DEFAULT_DISCGRP_READ_FAILURE_TEXT} from {@code :L455} - a distinct string from the one the
     * lenient lookup displays - and names {@code CBACT04C} as the abend culprit.
     *
     * <p>Side effects: none on the success path; on the failure path, construction and throwing only.
     *
     * @param ioStatus the raw two character status reported by the default group read, which may be
     *                 {@code null}, shorter or longer
     * @throws com.cardemo.exception.FatalProcessingException when the status is {@code '23'}, and for every
     *                                                        unrecognised, {@code null} or malformed value
     * @throws com.cardemo.exception.CardDemoException        when the status is {@code '22'}, {@code '35'} or
     *                                                        in the {@code '9x'} family, each keeping its
     *                                                        typed subtype
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
     * {@code app/cbl/CBSTM03A.CBL:L736 8100-TRNXFILE-OPEN} and its eight siblings, the only sites in the
     * corpus where {@code '04'} is accepted as success.
     *
     * <p>The source:
     *
     * <pre>
     * IF WS-M03B-RC = '00' OR '04'
     *     CONTINUE
     * ELSE
     *     DISPLAY 'ERROR OPENING TRNXFILE'
     *     DISPLAY 'RETURN CODE: ' WS-M03B-RC
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF.
     * </pre>
     *
     * <p>There are exactly nine such sites, at {@code app/cbl/CBSTM03A.CBL} lines 736, 748, 771, 789, 807,
     * 862, 879, 895 and 911, and they are the open, close and initial read of the datasets the statement
     * generator reaches through {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. The value inspected is
     * {@code WS-M03B-RC PIC X(02)}, declared at {@code app/cbl/CBSTM03A.CBL:L80}, and the corpus census
     * confirms all nine {@code '04'} literals in the whole of {@code app/cbl} live in this one file.
     *
     * <p><strong>High severity: {@code '04'} is confined to this method and its sibling.</strong> It is
     * absent from the general map, so no general path can accept it. Admitting it generally would silently
     * accept a read reported with a secondary condition anywhere in the corpus, which no other guard does.
     * That is why the acceptance is exposed as its own method rather than as a flag on a general one -
     * there is no argument a caller can pass to a general method that would reach this behaviour.
     *
     * <p><strong>Two fidelity points that distinguish this guard from every other one in this class.</strong>
     * First, {@code '10'} is <em>not</em> accepted here: these nine sites have no end of file arm, so an end
     * of file reported by them falls to the {@code ELSE} and abends, exactly as any other unexpected code
     * does. Use {@link #requireFileServiceSuccessOrEndOfFile(String, String, String)} at the read sites that
     * do have one. Second, none of the thirteen file service sites performs
     * {@code 9910-DISPLAY-IO-STATUS} - they display {@value #FILE_SERVICE_RETURN_CODE_TEXT} followed by the
     * <strong>raw</strong> two character code and go straight to {@code 9999-ABEND-PROGRAM}. The file
     * service return code therefore has no typed status vocabulary and no four character expansion, and
     * every rejection here is fatal rather than mapped through the {@code FILE STATUS} families. Conflating
     * the two vocabularies would attribute a file access or not found meaning to a code that never had one.
     *
     * <p>Side effects: none on the success path; on the failure path, construction and throwing only.
     *
     * @param returnCode the raw two character return code reported by the file service, which may be
     *                   {@code null}, shorter or longer
     * @param ddName     the DD name the call selected, for example {@code "TRNXFILE"} or {@code "XREFFILE"}.
     *                   Permitted to be {@code null}. Pass an identity only
     * @param operation  the attempted operation, for example {@code "OPEN"} or {@code "READ"}. Permitted to
     *                   be {@code null}
     * @throws com.cardemo.exception.FatalProcessingException for every code other than {@code '00'} and
     *                                                        {@code '04'}, including {@code '10'} and any
     *                                                        {@code null} or malformed value
     */
    public void requireFileServiceSuccess(String returnCode, String ddName, String operation) {
        if (FileStatus.SUCCESS.matches(returnCode) || FileStatus.SUCCESS_SECONDARY.matches(returnCode)) {
            return;
        }
        throw fileServiceAbend(returnCode, ddName, operation);
    }

    /**
     * Scoped carve-out 3 of 3, second form. Applies the stricter statement file service guard of
     * {@code app/cbl/CBSTM03A.CBL:L353 EVALUATE WS-M03B-RC} and its three siblings, which accept
     * {@code '00'} alone, treat {@code '10'} as end of file and abend on everything else.
     *
     * <p>The source:
     *
     * <pre>
     * EVALUATE WS-M03B-RC
     *   WHEN '00'
     *     CONTINUE
     *   WHEN '10'
     *     MOVE 'Y' TO END-OF-FILE
     *   WHEN OTHER
     *     DISPLAY 'ERROR READING XREFFILE'
     *     DISPLAY 'RETURN CODE: ' WS-M03B-RC
     *     PERFORM 9999-ABEND-PROGRAM
     * END-EVALUATE.
     * </pre>
     *
     * <p>There are exactly four such sites, at {@code app/cbl/CBSTM03A.CBL} lines 353, 379, 403 and 837.
     * They are the driving reads, which is why they need an end of file arm that the nine acceptance sites
     * do not have.
     *
     * <p><strong>{@code '04'} is deliberately rejected by this form.</strong> The four {@code EVALUATE}
     * arms name {@code '00'} and {@code '10'} only, so a secondary condition falls to {@code WHEN OTHER}.
     * This is the one place in this class where the same underlying return code is accepted by one method
     * and rejected by another, and it is faithful: the source really does apply two different tolerances to
     * the same field, a looser one at the open and initial read sites and a stricter one at the driving
     * reads.
     *
     * <p>As with {@link #requireFileServiceSuccess(String, String, String)}, a rejection is fatal and is not
     * mapped through the {@code FILE STATUS} families, because {@code WHEN OTHER} performs
     * {@code 9999-ABEND-PROGRAM} directly and never the renderer.
     *
     * <p>Side effects: none on either accepting path; on the failure path, construction and throwing only.
     *
     * @param returnCode the raw two character return code reported by the file service, which may be
     *                   {@code null}, shorter or longer
     * @param ddName     the DD name the call selected. Permitted to be {@code null}. Pass an identity only
     * @param operation  the attempted operation. Permitted to be {@code null}
     * @return {@code true} when the code is {@code '10'} and the caller must stop reading, {@code false}
     *         when the code is {@code '00'} and a record was returned
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
     * <p>Reproduces the {@code ELSE} arm shared by every guard in the corpus - for example
     * {@code app/cbl/CBTRN02C.cbl:L246-L251} and {@code app/cbl/CBTRN02C.cbl:L362-L367} - which the source
     * answers by displaying a message, rendering the status through
     * {@code app/cbl/CBTRN02C.cbl:L714 9910-DISPLAY-IO-STATUS} and abending through
     * {@code app/cbl/CBTRN02C.cbl:L707 9999-ABEND-PROGRAM}. The target refines that single outcome into the
     * typed families the migration mandates, so the status still ends a unit of work but now carries what
     * kind of failure it was.
     *
     * <p>The classification is delegated to {@code FileStatus.tryClassify(String)}, which never throws and
     * reports an unclassifiable value as an empty result. The decision is then an exhaustive
     * {@code switch} over the seven constants - exhaustive rather than defaulted so that adding an eighth
     * status family becomes a compile error here rather than a silent fall-through to the fatal arm.
     *
     * <p>Notes on the individual arms:
     *
     * <ul>
     *   <li><strong>{@code '22'} and {@code '35'} have no literal source site anywhere in the corpus.</strong>
     *       A census of {@code app/cbl} at commit {@code 7756d89} finds zero occurrences of each. They are
     *       reachable only through this {@code ELSE} arm at run time, so they look like dead code and are
     *       <strong>deliberately retained</strong>: deleting them would push a genuine duplicate key or a
     *       genuine unavailable file into the fatal arm and discard the information. Both arms are tracked
     *       in {@code DECISION_LOG.md} under the runtime-reachable-only status mapping entry, which is what
     *       makes them tracked rather than untracked under Rule 1 Clause B.</li>
     *   <li><strong>{@code '23'} maps to a not found here, which is correct for the general path only.</strong>
     *       The strict disclosure group retry inverts it to fatal; see
     *       {@link #requireDefaultDisclosureGroupReadSuccess(String)}, which is why that method does not
     *       route {@code '23'} through this one.</li>
     *   <li><strong>{@code '9x'} is a family, not a value.</strong> The raw status rather than the rendering
     *       is handed to the exception, because that type expands it once at construction through the single
     *       owning renderer - so the four character expansion is produced exactly once and is reachable from
     *       the throwable, reproducing the source's render-then-abend ordering.</li>
     *   <li><strong>{@code '00'}, {@code '04'} and {@code '10'} share the fatal arm.</strong> They are not
     *       unreachable filler. A caller only ever reaches this method having already rejected the status, so
     *       arriving here with {@code '04'} means a general path was handed a secondary condition it must not
     *       accept, and arriving with {@code '10'} means a two way guard was handed an end of file. Both are
     *       genuinely unexpected conditions and the corpus abends on both. {@code '00'} cannot be reached
     *       through any public method on this class and is grouped with them because exhaustiveness requires
     *       the label, not because success is a failure.</li>
     * </ul>
     *
     * <p>Side effects: none. Nothing is thrown from here - the exception is returned and the caller decides
     * whether to throw it - and nothing is logged or counted. Pure function of its arguments.
     *
     * @param ioStatus          the raw status, which may be {@code null}, shorter or longer
     * @param logicalFileName   the logical file or DD name, or {@code null}; an identity, never a key
     * @param operation         the attempted operation, or {@code null}
     * @param legacyFailureText the exact literal the source displays at this site, or {@code null} on a
     *                          general path where the displayed text differs per site
     * @param abendCulprit      the originating program for {@code ABEND-CULPRIT}, or
     *                          {@link #ABEND_CULPRIT_UNSET} where it is unknown
     * @param cause             the underlying throwable to preserve, or {@code null}
     * @return the exception the status maps to, never {@code null}
     */
    private CardDemoException failureFor(String ioStatus, String logicalFileName, String operation,
            String legacyFailureText, String abendCulprit, Throwable cause) {
        String message = failureMessage(ioStatus, logicalFileName, operation, legacyFailureText);
        Optional<FileStatus> classified = FileStatus.tryClassify(ioStatus);
        if (classified.isEmpty()) {
            return fatalFor(ioStatus, logicalFileName, operation, legacyFailureText, abendCulprit, cause);
        }
        return switch (classified.get()) {
            // FILE STATUS '22'. No literal source site in app/cbl (census: 0 occurrences); retained as a
            // runtime-reachable-only mapping, tracked in DECISION_LOG.md. No colliding key is passed: this
            // class never places a key value in an exception.
            case DUPLICATE_KEY -> new DuplicateRecordException(message, logicalFileName, null, cause);
            // FILE STATUS '23'. Cited at app/cbl/CBTRN02C.cbl:L481 and app/cbl/CBACT04C.cbl:L422 and :L436.
            // No record key is passed, for the same reason.
            case RECORD_NOT_FOUND -> new RecordNotFoundException(message, logicalFileName, null, cause);
            // FILE STATUS '35'. No literal source site in app/cbl (census: 0 occurrences); retained as a
            // runtime-reachable-only mapping, tracked in DECISION_LOG.md.
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
     * Builds the abend for a status the guard cannot classify, or classifies as one the call site had
     * already rejected.
     *
     * <p>Reproduces {@code app/cbl/CBTRN02C.cbl:L707-L711 9999-ABEND-PROGRAM}:
     *
     * <pre>
     * 9999-ABEND-PROGRAM.
     *     DISPLAY 'ABENDING PROGRAM'
     *     MOVE 0 TO TIMING
     *     MOVE 999 TO ABCODE
     *     CALL 'CEE3ABD'.
     * </pre>
     *
     * <p>The abend code {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and the
     * process return code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} are
     * <strong>read from {@code FatalProcessingException} and never restated here</strong>, and neither is
     * acted upon: no process is terminated, no exit status is set and nothing is logged. In particular the
     * literal {@code 'ABENDING PROGRAM'} from {@code :L708} is not placed in the returned value, because it
     * is something the source <em>displays</em> and therefore belongs to the logging layer.
     *
     * <p>The payload is the four {@code CABENDD.CPY} fields from {@code app/cpy/CSMSG02Y.cpy:L21-L29}, in
     * the copybook's own field order. All four are declared {@code VALUE SPACES} there, so the code is
     * passed as {@value #ABEND_CODE_WIDTH} spaces rather than as null: the batch terminator has no four
     * character display code to supply, carrying the numeric code in a binary field instead. The culprit is
     * the originating program where it is known and {@value #ABEND_CULPRIT_WIDTH} spaces where it is not.
     * No field is padded or truncated to its legacy width beyond those two defaults, because truncating a
     * diagnostic would discard the context an error must preserve.
     *
     * <p>Side effects: none. The exception is returned, not thrown, and the cause is delegated unchanged.
     *
     * @param ioStatus          the raw status, which may be {@code null}, shorter or longer
     * @param logicalFileName   the logical file or DD name, or {@code null}
     * @param operation         the attempted operation, or {@code null}
     * @param legacyFailureText the exact literal the source displays at this site, or {@code null}
     * @param abendCulprit      the originating program, or {@link #ABEND_CULPRIT_UNSET}
     * @param cause             the underlying throwable to preserve, or {@code null}
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
     * <p>Reproduces the {@code ELSE} and {@code WHEN OTHER} arms shared by the thirteen file service sites
     * in {@code app/cbl/CBSTM03A.CBL}, for example {@code :L738-L741}:
     *
     * <pre>
     * ELSE
     *     DISPLAY 'ERROR OPENING TRNXFILE'
     *     DISPLAY 'RETURN CODE: ' WS-M03B-RC
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF.
     * </pre>
     *
     * <p>The message reproduces {@value #FILE_SERVICE_RETURN_CODE_TEXT} followed by the <strong>raw</strong>
     * two character code, because that is what the source displays. It deliberately carries <strong>no four
     * character expansion</strong>: not one of the thirteen sites performs
     * {@code 9910-DISPLAY-IO-STATUS}, so the file service return code has no expansion in the source and
     * inventing one here would fabricate output the legacy system never produced. For the same reason the
     * code is never routed through the {@code FILE STATUS} families - it is a subprogram return code
     * declared {@code WS-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03A.CBL:L80}, a different vocabulary that
     * happens to share a width.
     *
     * <p>Side effects: none. The exception is returned, not thrown.
     *
     * @param returnCode the raw two character return code, which may be {@code null}, shorter or longer
     * @param ddName     the DD name the call selected, or {@code null}
     * @param operation  the attempted operation, or {@code null}
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
     * <p>The four character form is obtained from the single owning renderer,
     * {@code FileStatus.renderIoStatus04(String)}, and is labelled with the field it reproduces,
     * {@code IO-STATUS-04} from {@code app/cbl/CBTRN02C.cbl:L138-L140}. The twenty character
     * {@code FILE STATUS IS: NNNN} prefix is deliberately <strong>not</strong> included: that exact line is
     * produced only by {@link #displayIoStatus(String)}, so no caller can mistake a fragment of a message
     * for the line the parity gate compares, and no reformatting of a message can disturb it.
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
                .append(orPlaceholder(ioStatus, ABSENT_VALUE))
                .append(" (IO-STATUS-04 ")
                .append(FileStatus.renderIoStatus04(ioStatus))
                .append(')');
        return message.toString();
    }

    /**
     * Substitutes a fixed placeholder for a value the caller did not supply, so that the four characters
     * {@code null} can never reach a log line through a message this class builds.
     *
     * <p>Blank is treated exactly as absent. That is deliberate and matches the source's own world, where a
     * fixed width alphanumeric field that was never populated holds spaces rather than a distinguishable
     * empty value - the four {@code CABENDD.CPY} fields at {@code app/cpy/CSMSG02Y.cpy:L21-L29} are all
     * declared {@code VALUE SPACES}. The blank test uses {@code String.isBlank()}, which is defined over
     * {@code Character.isWhitespace} and consults no locale, so this method is deterministic.
     *
     * <p>The value is otherwise returned untouched: not trimmed, not case folded and not validated. No case
     * conversion occurs anywhere in this class, so no Turkish locale hazard exists.
     *
     * <p>Side effects: none. Pure function.
     *
     * @param value       the caller supplied value, which may be {@code null}, empty or whitespace only
     * @param placeholder the fixed text to substitute, never {@code null}
     * @return the value exactly as supplied when it contains a non whitespace character, otherwise the
     *         placeholder
     */
    private static String orPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }
}
