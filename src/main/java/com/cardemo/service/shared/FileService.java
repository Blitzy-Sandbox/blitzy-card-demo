/*
 * ******************************************************************
 * Program     : FileService.java
 * Component   : FileService
 * Application : CardDemo
 * Type        : Spring @Service (shared, DD-name-keyed file access)
 * Function    : Replaces CALL 'CBSTM03B' USING WS-M03B-AREA static linkage
 * Source      : app/cbl/CBSTM03B.CBL (230 lines, 14 PROCEDURE DIVISION
 *               paragraphs) @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL (924 lines) - the sole caller, whose
 *               WS-M03B-AREA at L71-L83 declares the byte-identical shared
 *               area and whose guards fix the accepted return codes
 *               @ 7756d89
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

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.enums.FileStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

/**
 * The CardDemo statement file service: one Spring bean standing in for the whole of the frozen
 * {@code app/cbl/CBSTM03B.CBL} subprogram, reached in the source through a single static linkage point.
 *
 * <h2>What it does</h2>
 *
 * <p>This class is the <strong>third collapse rule</strong> of the migration. The source reaches all four of
 * its datasets through exactly one call site shape,
 * {@code CALL 'CBSTM03B' USING WS-M03B-AREA} ({@code app/cbl/CBSTM03A.CBL:L351}), passing a 1040 byte shared
 * area whose first field selects the dataset by DD name and whose second selects the operation by a single
 * character. That indirection has no home in any of the twenty program-derived services, so the entire call
 * contract becomes this one injected bean exposing a DD-name-keyed handler map. Collapsing it here is what
 * keeps the repository free of four near-identical access classes, which is the duplication clause of the
 * project rule.
 *
 * <p>The mechanism substitution is deliberate and narrow. Static linkage becomes constructor injection; the
 * {@code EVALUATE LK-M03B-DD} dispatch of {@code 0000-START.} becomes an immutable, order-deterministic
 * handler map; the four {@code FILE STATUS} work areas become one status register per DD; and the
 * {@code FILE-CONTROL.} paragraph becomes four pluggable dataset bindings. Nothing else changes: the return
 * codes, the record geometry, the payload width and both latent defects are reproduced exactly.
 *
 * <p><strong>Why the handler map belongs here and nowhere else.</strong> The DD-keyed
 * {@code EVALUATE} at {@code app/cbl/CBSTM03B.CBL:L118} is the only genuine runtime variability in the
 * statement subsystem: four datasets multiplied by six operation codes. By contrast the
 * {@code ALTER ... TO PROCEED TO} chain in {@code app/cbl/CBSTM03A.CBL} is a deterministic one-shot
 * initialisation pipeline that walks five datasets once and then leaves the state machine permanently, not a
 * runtime dispatch table, so no strategy map belongs there.
 *
 * <h2>How to build and test</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} 25 and no preview features, Maven 3.9.11, parent
 * {@code spring-boot-starter-parent} 3.5.11. The compiler runs {@code -Xlint:all -Werror}, so a raw type, an
 * unchecked cast or a dangling documentation comment fails the build outright; an unused import does not,
 * because {@code javac} 25 publishes no {@code unused} lint key, and malformed Javadoc does not either,
 * because no Javadoc plugin is bound in {@code pom.xml}. Build with {@code ./mvnw -B clean compile} and verify with
 * {@code ./mvnw -B verify}, which enforces an 80 percent JaCoCo line floor with no exclusions. Unit tests for
 * this class belong in {@code src/test/java/com/cardemo/unit/} only; they need no database and no container,
 * because a dataset binding is an interface a test can implement directly.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Four DD names</strong>, each exactly eight characters to match {@code LK-M03B-DD PIC X(08)}:
 *       {@code TRNXFILE}, {@code XREFFILE}, {@code CUSTFILE}, {@code ACCTFILE}
 *       ({@code app/cbl/CBSTM03B.CBL:L31-L53}).</li>
 *   <li><strong>Two access modes, and they are not interchangeable.</strong> {@code TRNXFILE} and
 *       {@code XREFFILE} are {@code ACCESS MODE IS SEQUENTIAL} (L33, L39) and therefore implement the plain
 *       read {@code 'R'}; {@code CUSTFILE} and {@code ACCTFILE} are {@code ACCESS MODE IS RANDOM} (L45, L51)
 *       and therefore implement the keyed read {@code 'K'}.</li>
 *   <li><strong>Six operation codes</strong>, all retained: {@code 'O'} open, {@code 'C'} close, {@code 'R'}
 *       read, {@code 'K'} keyed read, {@code 'W'} write, {@code 'Z'} rewrite
 *       ({@code app/cbl/CBSTM03B.CBL:L103-L108}).</li>
 *   <li><strong>Only 12 of the 24 cells are implemented.</strong> Each dataset implements exactly three
 *       operations. See the matrix below.</li>
 *   <li><strong>Record widths, preserved byte-exactly:</strong> {@code TRNXFILE} 350 = 32 key plus 318 data;
 *       {@code XREFFILE} 50 = 16 plus 34; {@code CUSTFILE} 500 = 9 plus 491; {@code ACCTFILE} 300 = 11 plus
 *       289 ({@code app/cbl/CBSTM03B.CBL:L58-L78}).</li>
 *   <li><strong>Payload width 1000</strong>, right padded with spaces and never trimmed, carrying
 *       {@code LK-M03B-FLDT PIC X(1000)} (L112). All four record widths fit inside it.</li>
 *   <li><strong>Key width 25</strong> carrying {@code LK-M03B-KEY PIC X(25)} (L110), with an effective length
 *       from {@code LK-M03B-KEY-LN PIC S9(4)} (L111) validated to 1 &lt;= length &lt;= 25.</li>
 *   <li><strong>Return code width 2</strong> carrying {@code LK-M03B-RC PIC X(02)} (L109). {@code '00'} and
 *       {@code '04'} are success at the nine open, close and initial-read sites; {@code '00'} alone is
 *       success at the four get-next sites, where {@code '10'} means end of file; everything else abends.
 *       Both guards are owned by {@code FileStatusMapper} and are never reimplemented here.</li>
 *   </ul>
 *
 * <h2>The implemented matrix, 12 of 24 cells</h2>
 *
 * <ul>
 *   <li>{@code TRNXFILE} - {@code 1000-TRNXFILE-PROC.} at L133 implements {@code 'O'} (L136), {@code 'R'}
 *       (L141) and {@code 'C'} (L147).</li>
 *   <li>{@code XREFFILE} - {@code 2000-XREFFILE-PROC.} at L157 implements {@code 'O'} (L160), {@code 'R'}
 *       (L165) and {@code 'C'} (L171).</li>
 *   <li>{@code CUSTFILE} - {@code 3000-CUSTFILE-PROC.} at L181 implements {@code 'O'} (L184), {@code 'K'}
 *       (L189-L190) and {@code 'C'} (L196).</li>
 *   <li>{@code ACCTFILE} - {@code 4000-ACCTFILE-PROC.} at L206 implements {@code 'O'} (L209), {@code 'K'}
 *       (L214-L215) and {@code 'C'} (L221).</li>
 *   </ul>
 *
 * <p>The eight remaining cells are unreachable in the source and are reproduced as unreachable here.
 * {@code 'W'} and {@code 'Z'} are declared at L107 and L108 and then referenced by no handler at all; the
 * root cause is that every dataset is opened {@code OPEN INPUT}, that is read only, so a write and a rewrite
 * are structurally impossible rather than merely absent. {@code 'R'} is additionally absent from
 * {@code CUSTFILE} and {@code ACCTFILE}, and {@code 'K'} from {@code TRNXFILE} and {@code XREFFILE}, which
 * follows directly from the access-mode split. All eight, the two write operation codes and the four pure
 * terminator paragraphs are retained rather than deleted, are marked as intentional no-ops at the point of
 * definition, so that they are not mistaken for abandoned dead code.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A stale return code with an untouched payload (defect A, High) - now refused.</strong> A
 *       valid DD combined with an operation that dataset does not implement, for example {@code TRNXFILE}
 *       with {@code 'K'}, performs no input or output at all: no {@code IF} in the handler fires, control
 *       falls through to the status epilogue, and the epilogue publishes the status register left behind by
 *       the previous call on that same DD, so the second call inherits the first call's verdict.
 *       {@link #execute(FileServiceRequest)} refuses the combination; call {@link #supports} first, or use a
 *       guarded method, which cannot select an unimplemented cell.</li>
 *   <li><strong>An unknown DD name reporting success (defect B, High) - now refused.</strong> {@code WHEN
 *       OTHER GO TO 9999-GOBACK.} at L127-L128 performs no input or output and never assigns the return code,
 *       so the caller observes the value it pre-set itself. Because the caller pre-sets {@code MOVE ZERO}
 *       into a two byte alphanumeric field at {@code app/cbl/CBSTM03A.CBL:L349}, that value is {@code '00'},
 *       and {@code MOVE SPACES} at L350 leaves an all-spaces payload - a blank record reported as a
 *       successful read. {@link #execute(FileServiceRequest)} refuses the name and names the four it
 *       accepts.</li>
 *   <li><strong>An out-of-range key length is rejected.</strong> The source applies COBOL reference
 *       modification with a runtime length, which is undefined behaviour outside 1 to 25. This class
 *       validates the bound explicitly and abends with context instead.</li>
 *   <li><strong>A non-numeric or wrong-length {@code ACCTFILE} key is rejected.</strong> {@code FD-ACCT-ID}
 *       is {@code PIC 9(11)} at L77, the only numeric key of the four, so the extracted key must be exactly
 *       eleven ASCII digits. {@code FD-CUST-ID} is {@code PIC X(09)} at L72 and must be exactly nine
 *       characters.</li>
 *   <li><strong>A dataset is not bound.</strong> Invoking a DD for which no binding was injected abends with
 *       the DD name in the message. Remedy: contribute a binding for that DD to the application context.
 *       Construction never fails for a missing binding, so a context that does not run the statement job
 *       still starts.</li>
 *   <li><strong>Reading the source with a lowercase-only glob loses it.</strong> The primary source
 *       is {@code app/cbl/CBSTM03B.CBL} with an uppercase extension, and its caller
 *       {@code app/cbl/CBSTM03A.CBL} likewise, so a {@code *.cbl} pattern silently drops both. Match
 *       {@code app/cbl} case-insensitively.</li>
 *   <li><strong>Counting lines without stripping the carriage return shifts every citation.</strong>
 *       Both source members are CRLF terminated on every line. Strip the carriage return before counting.</li>
 *   </ul>
 *
 * <h2>Ways this translation gets broken</h2>
 *
 * <ul>
 *   <li>Reading {@code app/cbl/CBSTM03B.CBL} with a lowercase-only glob, or
 *       counting its lines without stripping the carriage return. Match case-insensitively and strip
 *       the carriage return first.</li>
 *   <li>Removing either reproduced legacy defect - the stale-status fall-through or the unknown DD reporting
 *       success; admitting {@code '04'} on a general status path; exposing a write capability for {@code 'W'}
 *       or {@code 'Z'}; logging the payload or the key. Both defects are reproduced behind the
 *       guarded methods, all status decisions are delegated to {@code FileStatusMapper}, the surface stays
 *       read only, and nothing but DD name, operation and return code is logged.</li>
 *   <li>Collapsing a status epilogue into its terminator, which would drop four paragraphs from the map, or
 *       ignoring the {@code FD-ACCT-ID PIC 9(11)} numeric-key asymmetry against three alphanumeric keys. The
 *       epilogue and terminator stay separate and the numeric key is validated explicitly. The
 *       carriage-return-stripped source puts the dispatch at L114, L116, L118 and L128, and those are the
 *       lines cited throughout this file.</li>
 *   </ul>
 *
 * <h2>Two source curiosities, recorded and not corrected</h2>
 *
 * <ul>
 *   <li>{@code FD-ACCT-DATA} is declared twice, as {@code PIC X(318)} under
 *       {@code TRNX-FILE} at L63 and as {@code PIC X(289)} under {@code ACCT-FILE} at L78, a duplicate data
 *       name legal only under qualification; and the header comments at L25-L26 read "This program is to called
 *       by the statement create program" and "It does file handling". Neither is corrected, because
 *       {@code app/} is frozen.</li>
 *   <li>The PROCEDURE DIVISION has fourteen paragraphs, not fifteen: the fifteenth Area A paragraph is
 *       {@code FILE-CONTROL.} at L30, in the ENVIRONMENT DIVISION INPUT-OUTPUT SECTION, which carries no
 *       behaviour and maps to the four dataset bindings rather than to a method. No paragraph was
 *       consolidated to make a count fit.</li>
 *   <li>No throughput or latency objective for file access exists. The source
 *       publishes no service level, so none is invented here; the performance gate records a measured
 *       baseline rather than a target.</li>
 *   </ul>
 *
 * <h2>Concurrency and state</h2>
 *
 * <p>This bean is a singleton and is otherwise stateless, with exactly one deliberate exception: it holds one
 * status register per DD, because the source's {@code FILE STATUS} fields live in WORKING-STORAGE
 * ({@code app/cbl/CBSTM03B.CBL:L83-L97}) and therefore survive from one call to the next. That persistence is
 * not incidental - it is the observable mechanism of defect A, so removing it would remove the defect. The
 * registers are atomic, so publication between threads is safe. A sequential dataset nevertheless carries a
 * single read cursor exactly as the source does, so two threads must not drive the same DD concurrently; that
 * is a property of the file model being reproduced, not of this class.
 *
 * <p>No case folding and no locale-sensitive formatting is performed anywhere in this class. DD names and
 * operation codes are compared byte-exactly, as the source's {@code EVALUATE} does, so no locale can
 * influence a dispatch decision.
 *
 * <h2>Paragraph map</h2>
 *
 * <p>Each of the fourteen PROCEDURE DIVISION paragraphs of {@code app/cbl/CBSTM03B.CBL} maps to exactly one
 * private method below, in source order, and each carries its own citation: {@code 0000-START.} L116,
 * {@code 9999-GOBACK.} L130, {@code 1000-TRNXFILE-PROC.} L133, {@code 1900-EXIT.} L151, {@code 1999-EXIT.}
 * L154, {@code 2000-XREFFILE-PROC.} L157, {@code 2900-EXIT.} L175, {@code 2999-EXIT.} L178,
 * {@code 3000-CUSTFILE-PROC.} L181, {@code 3900-EXIT.} L200, {@code 3999-EXIT.} L203,
 * {@code 4000-ACCTFILE-PROC.} L206, {@code 4900-EXIT.} L225 and {@code 4999-EXIT.} L228.
 *
 * @see FileStatusMapper
 */
@Service
@JobScope
public class FileService implements InitializingBean {

    // SCOPE AND WIRING VALIDATION, and why BOTH are present. Finding, severity High, RESOLVED. The four status
    // registers below are per-instance, but a singleton has exactly one instance, so two overlapping job
    // executions would still have shared them - and a status register is read immediately after the call that
    // set it, which makes a cross-execution overwrite a wrong-status decision rather than a lost log line.
    // @JobScope gives one instance per job execution, which is the isolation CBSTM03B had by construction: the
    // subprogram's WORKING-STORAGE belonged to the invoking job step and to nothing else. InitializingBean is
    // orthogonal and is kept: afterPropertiesSet refuses to complete wiring unless all four Dd values are
    // bound, so a missing binding fails when the instance is created rather than at the first read. With the
    // job scope that is the first job execution rather than context refresh, which is the only observable
    // consequence of pairing the two.

    /**
     * Logger for the per-call diagnostic line.
     *
     * <p>Deliberately narrow. Only the DD name, the operation code and the two character return code are ever
     * logged. The payload buffer and the key are never logged, never serialised and never placed in an
     * exception message, because a single payload can carry a whole customer record - the 491 data bytes of
     * {@code FD-CUST-DATA} at {@code app/cbl/CBSTM03B.CBL:L73} span the social security number, the
     * government issued identifier, the date of birth, the electronic funds account identifier and two
     * telephone numbers - or a whole cross-reference record including a sixteen digit card number.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileService.class);

    /**
     * Width of the DD name field, carrying {@code LK-M03B-DD PIC X(08)}
     * ({@code app/cbl/CBSTM03B.CBL:L101}).
     */
    public static final int DD_NAME_WIDTH = 8;

    /**
     * Width of the operation field, carrying {@code LK-M03B-OPER PIC X(01)}
     * ({@code app/cbl/CBSTM03B.CBL:L102}).
     */
    public static final int OPERATION_WIDTH = 1;

    /**
     * Width of the key field, carrying {@code LK-M03B-KEY PIC X(25)} ({@code app/cbl/CBSTM03B.CBL:L110}).
     * This is also the inclusive upper bound on a supplied key length.
     */
    public static final int KEY_WIDTH = 25;

    /**
     * Inclusive lower bound on a supplied key length. COBOL reference modification
     * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} ({@code app/cbl/CBSTM03B.CBL:L189}) is undefined below one, so
     * the bound is enforced rather than inherited.
     */
    public static final int MINIMUM_KEY_LENGTH = 1;

    /**
     * Width of the payload buffer, carrying {@code LK-M03B-FLDT PIC X(1000)}
     * ({@code app/cbl/CBSTM03B.CBL:L112}). Every payload this class returns is exactly this long, right
     * padded with spaces.
     */
    public static final int PAYLOAD_WIDTH = 1000;

    /**
     * Total width of the shared area, carrying {@code LK-M03B-AREA}
     * ({@code app/cbl/CBSTM03B.CBL:L100-L112}): 8 plus 1 plus 2 plus 25 plus 4 plus 1000.
     */
    public static final int SHARED_AREA_WIDTH = 1040;

    /**
     * Width of the signed key-length field, carrying {@code LK-M03B-KEY-LN PIC S9(4)}
     * ({@code app/cbl/CBSTM03B.CBL:L111}).
     */
    public static final int KEY_LENGTH_FIELD_DIGITS = 4;

    /**
     * The payload a caller supplies before the call, carrying {@code MOVE SPACES TO WS-M03B-FLDT}
     * ({@code app/cbl/CBSTM03A.CBL:L350}). It is also exactly what a caller observes on both no-input-output
     * paths, defect A and defect B.
     */
    public static final String PRESET_PAYLOAD = " ".repeat(PAYLOAD_WIDTH);

    /**
     * The return code a caller supplies before the call, carrying {@code MOVE ZERO TO WS-M03B-RC}
     * ({@code app/cbl/CBSTM03A.CBL:L349}).
     *
     * <p>The figurative constant {@code ZERO} fills the whole two byte alphanumeric field with the digit
     * zero, so the pre-set value is {@code "00"} - the very code that means success. That is the entire
     * mechanism of defect B: an unknown DD name leaves this value in place and the caller reads it as a
     * successful call.
     */
    public static final String PRESET_RETURN_CODE = "0".repeat(FileStatus.STATUS_CODE_LENGTH);

    /**
     * The initial content of a status register, modelling a WORKING-STORAGE group of two
     * {@code PIC X} sub-fields with no {@code VALUE} clause ({@code app/cbl/CBSTM03B.CBL:L83-L97}).
     *
     * <p>This is what defect A publishes on the very first call against a DD, before any input or output has
     * ever set a real status on it.
     */
    public static final String UNSET_STATUS = " ".repeat(FileStatus.STATUS_CODE_LENGTH);

    /**
     * The abending component recorded in {@code ABEND-CULPRIT PIC X(8)} for every abend this class raises.
     * The culprit is the subprogram whose contract this bean carries, not its caller.
     */
    private static final String ABEND_CULPRIT = "CBSTM03B";

    /**
     * {@code ABEND-REASON} for a shared-area field that violates its own picture clause.
     */
    private static final String INVALID_SHARED_AREA_REASON = "INVALID CBSTM03B SHARED AREA FIELD";

    /**
     * {@code ABEND-REASON} for a DD invoked with no dataset binding contributed to the context.
     */
    private static final String UNBOUND_DATASET_REASON = "NO DATASET BINDING FOR CBSTM03B DD NAME";

    /**
     * {@code ABEND-REASON} for a dataset binding that breaks its own contract, for instance by returning a
     * null status.
     */
    private static final String BROKEN_BINDING_REASON = "DATASET BINDING BREACHED THE CBSTM03B CONTRACT";

    /**
     * Abend reason for defect B, refused at the public adapter: a DD name no handler recognises.
     *
     * <p>Worded as the corpus words its own abend reasons - upper case, terse, naming the subprogram - so a
     * log line from this class is indistinguishable in shape from one the frozen batch programs produce.
     */
    private static final String UNKNOWN_DD_REASON = "UNRECOGNISED CBSTM03B DD NAME";

    /**
     * Abend reason for defect A, refused at the public adapter: an operation the resolved dataset does not
     * implement, which in the source performs no input or output and republishes a stale status.
     */
    private static final String UNIMPLEMENTED_OPERATION_REASON =
            "UNIMPLEMENTED CBSTM03B DD AND OPERATION COMBINATION";

    /**
     * The dispatch table: one handler per DD name, keyed by the resolved DD and built exactly once.
     *
     * <p>This is the Java form of {@code EVALUATE LK-M03B-DD} at {@code app/cbl/CBSTM03B.CBL:L118}. It is an
     * unmodifiable view over an {@code EnumMap}, so it is immutable, contains no mutable state, and iterates
     * in enum declaration order rather than hash order - determinism the project rule requires. Each handler
     * composes the three paragraphs that {@code PERFORM nnnn-PROC THRU nnnn-EXIT} would execute in sequence:
     * the operation body, then the status epilogue, then the terminator.
     *
     * <p>The handlers take the service as a parameter rather than capturing it, so this field can be static
     * and the constructor never publishes a partially initialised instance.
     */
    private static final Map<Dd, DatasetHandler> HANDLERS = buildHandlers();

    /**
     * The 12 of 24 implemented cells of the four-dataset by six-operation matrix, built exactly once.
     *
     * <p>Derived from the handler bodies of {@code app/cbl/CBSTM03B.CBL}: every dataset implements
     * {@code 'O'} and {@code 'C'}, plus exactly one read form chosen by its {@code ACCESS MODE}. The eight
     * absent cells are absent here for the same reason they are absent there.
     */
    private static final Map<Dd, Set<Operation>> IMPLEMENTED_OPERATIONS = buildImplementedOperations();

    /**
     * The single owner of every return-code decision in the codebase. Injected, never instantiated here, so
     * that the four character status rendering and the {@code '04'} carve-out exist in exactly one place.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * The dataset bindings, one per DD, carrying {@code FILE-CONTROL.}
     * ({@code app/cbl/CBSTM03B.CBL:L30-L53}).
     *
     * <p>Unmodifiable and possibly incomplete: a context that does not run the statement job contributes no
     * bindings at all, and this bean still starts. A missing binding is reported only when the DD it belongs
     * to is actually invoked.
     */
    private final Map<Dd, Dataset> datasets;

    /**
     * One status register per DD, carrying the four WORKING-STORAGE {@code FILE STATUS} groups
     * ({@code app/cbl/CBSTM03B.CBL:L83-L97}).
     *
     * <p>The map itself is unmodifiable and fully populated for all four DDs; the registers inside it are
     * mutable by design. This is the only state this bean retains between calls, and it is retained
     * deliberately, because a COBOL subprogram's WORKING-STORAGE survives from one call to the next and that
     * survival is precisely what defect A exposes.
     *
     * <h4>The scope of that survival, proved from the source rather than assumed</h4>
     *
     * <p>Cross-call survival is required; process-wide sharing is not, and the difference is what the
     * {@code @JobScope} on this class establishes. {@code CBSTM03B} is a separately compiled subprogram
     * reached by {@code CALL 'CBSTM03B' USING WS-M03B-AREA} from {@code app/cbl/CBSTM03A.CBL:L347-L351} and
     * its sibling call sites, and it declares no {@code INITIAL} attribute on its {@code PROGRAM-ID}, so its
     * WORKING-STORAGE persists for the life of the <em>run unit</em> - one enclosing job step - and is
     * released with it. There is exactly one such run unit per execution of {@code app/jcl/CREASTMT.JCL}, and
     * the legacy system ran one at a time. A process-wide singleton would therefore be <em>more</em> shared
     * than the source, not equally shared: two concurrent statement jobs would read each other's residual
     * statuses, so the very defect this state reproduces would fire across job boundaries where the source
     * cannot produce it. Scoping the bean to the job reproduces the source's own lifetime exactly - survival
     * within one run, isolation between runs.
     *
     * <p>Isolating the registers per <em>invocation</em> is deliberately NOT done, and that is the one place
     * where an obvious hardening would break parity: defect A is observable only because a call that performs
     * no input or output leaves the previous call's status in place, and a per-invocation reset would silently
     * repair it. The acceptance is therefore an explicit exception to Rule 1 Clause B's preference against
     * retained mutable state, justified by the parity mandate, and it is <strong>owed an entry in the planned
     * {@code DECISION_LOG.md}</strong>; measured at this commit that file does not exist, so the register of
     * record is this Javadoc together with the analysis in {@code docs/technical-specifications.md}. Nothing
     * here may be described as already recorded or tracked until the entry and its stable identifier exist.
     */
    private final Map<Dd, AtomicReference<String>> statusRegisters;

    /**
     * Creates the file service over the injected status mapper and whatever dataset bindings the context
     * contributes.
     *
     * @param fileStatusMapper the sole owner of return-code interpretation and four character status
     * rendering. Must not be {@code null}
     * @param datasetBindings every dataset binding present in the context, in any order. A caller that
     * constructs this service directly may supply any subset, which is what lets a unit test exercise one DD
     * in isolation; a <strong>Spring managed</strong> instance must be given all four, and
     * {@link #afterPropertiesSet()} refuses to complete wiring otherwise. Must not be {@code null}, and no
     * element may be {@code null} or report a {@code null} DD
     * @throws NullPointerException if {@code fileStatusMapper} or {@code datasetBindings} is {@code null}, or
     * if any binding is {@code null} or reports a {@code null} DD
     * @throws IllegalArgumentException if two bindings claim the same DD, which would make dispatch
     * order-dependent
     */
    public FileService(final FileStatusMapper fileStatusMapper, final List<Dataset> datasetBindings) {
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.datasets = indexBindings(datasetBindings);
        this.statusRegisters = buildStatusRegisters();
        LOG.info("CBSTM03B file service ready: {} of {} dataset bindings contributed",
                this.datasets.size(), Dd.values().length);
    }

    /**
     * Fails wiring unless every DD the subprogram declares has exactly one binding.
     *
     * <p>{@code app/cbl/CBSTM03B.CBL:L58-L78} declares four files and {@code app/cbl/CBSTM03A.CBL} reaches
     * all of its input through them, so a context that offers three bindings is not a reduced-function
     * context - it is a context in which the statement run abends part way through, after it has already
     * opened files and emitted records. Refusing to finish the refresh converts that into a startup failure
     * naming the missing DD, which is the difference between a diagnosable configuration defect and a
     * mid-run abend.
     *
     * <p>Deliberately here and not in the constructor. The container calls this method; a direct constructor
     * call does not, which is what lets a unit test build the service with a single binding and exercise one
     * DD in isolation. The completeness requirement is a <em>wiring</em> requirement, so it belongs to the
     * wiring callback.
     *
     * @throws IllegalStateException if any DD has no binding, naming every missing DD and the configuration
     * class that is expected to declare them
     */
    @Override
    public void afterPropertiesSet() {
        final Set<Dd> missing = EnumSet.allOf(Dd.class);
        missing.removeAll(this.datasets.keySet());
        if (!missing.isEmpty()) {
            final StringBuilder ddNames = new StringBuilder();
            for (final Dd dd : missing) {
                if (ddNames.length() > 0) {
                    ddNames.append(", ");
                }
                ddNames.append(dd.ddName());
            }
            throw new IllegalStateException("no dataset binding is registered for DD " + ddNames
                    + "; app/cbl/CBSTM03B.CBL:L58-L78 declares all " + Dd.values().length
                    + " files and every one of them must have exactly one repository backed binding. "
                    + "com.cardemo.config.BatchConfig is the class that declares them; a sliced test that "
                    + "imports only part of the configuration must import it too.");
        }
        LOG.info("CBSTM03B dataset bindings verified: all {} DDs are bound", Dd.values().length);
    }

    /**
     * Indexes the injected bindings by DD, rejecting duplicates.
     *
     * <p>Static so that the constructor cannot publish a partially initialised instance.
     *
     * @param datasetBindings the bindings to index. Must not be {@code null}
     * @return an unmodifiable, order-deterministic map from DD to binding, possibly empty
     * @throws NullPointerException if the list or any element is {@code null}, or an element reports a
     * {@code null} DD
     * @throws IllegalArgumentException if two bindings claim the same DD
     */
    private static Map<Dd, Dataset> indexBindings(final List<Dataset> datasetBindings) {
        Objects.requireNonNull(datasetBindings, "datasetBindings must not be null");
        final EnumMap<Dd, Dataset> indexed = new EnumMap<>(Dd.class);
        for (final Dataset binding : datasetBindings) {
            Objects.requireNonNull(binding, "a dataset binding must not be null");
            final Dd dd = Objects.requireNonNull(binding.dd(), "a dataset binding must report a non-null DD");
            final Dataset previous = indexed.put(dd, binding);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "two dataset bindings claim DD " + dd.ddName() + "; exactly one is required");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * Creates one status register per DD, each holding the unset value.
     *
     * <p>Static so that the constructor cannot publish a partially initialised instance.
     *
     * @return an unmodifiable map, populated for every DD, whose registers are mutable by design
     */
    private static Map<Dd, AtomicReference<String>> buildStatusRegisters() {
        final EnumMap<Dd, AtomicReference<String>> registers = new EnumMap<>(Dd.class);
        for (final Dd dd : Dd.values()) {
            registers.put(dd, new AtomicReference<>(UNSET_STATUS));
        }
        return Collections.unmodifiableMap(registers);
    }

    /**
     * Builds the DD-keyed dispatch table, one entry per {@code WHEN} of
     * {@code EVALUATE LK-M03B-DD} ({@code app/cbl/CBSTM03B.CBL:L118-L126}).
     *
     * <p>Each entry reproduces one {@code PERFORM nnnn-PROC THRU nnnn-EXIT}: the operation body runs first,
     * then the status epilogue - which every {@code GO TO nnnn-EXIT} inside the body also lands on - then the
     * terminator.
     *
     * @return an unmodifiable, order-deterministic map covering all four DDs
     */
    private static Map<Dd, DatasetHandler> buildHandlers() {
        final EnumMap<Dd, DatasetHandler> handlers = new EnumMap<>(Dd.class);
        handlers.put(Dd.TRNXFILE, (service, area) -> {
            service.trnxfileProc(area);
            service.trnxfileStatusEpilogue(area);
            service.trnxfileTerminator();
        });
        handlers.put(Dd.XREFFILE, (service, area) -> {
            service.xreffileProc(area);
            service.xreffileStatusEpilogue(area);
            service.xreffileTerminator();
        });
        handlers.put(Dd.CUSTFILE, (service, area) -> {
            service.custfileProc(area);
            service.custfileStatusEpilogue(area);
            service.custfileTerminator();
        });
        handlers.put(Dd.ACCTFILE, (service, area) -> {
            service.acctfileProc(area);
            service.acctfileStatusEpilogue(area);
            service.acctfileTerminator();
        });
        return Collections.unmodifiableMap(handlers);
    }

    /**
     * Builds the 12 of 24 implemented-cell matrix from the handler bodies of
     * {@code app/cbl/CBSTM03B.CBL}.
     *
     * @return an unmodifiable, order-deterministic map from DD to its three implemented operations
     */
    private static Map<Dd, Set<Operation>> buildImplementedOperations() {
        final EnumMap<Dd, Set<Operation>> implemented = new EnumMap<>(Dd.class);
        for (final Dd dd : Dd.values()) {
            implemented.put(dd, Collections.unmodifiableSet(
                    EnumSet.of(Operation.OPEN, Operation.CLOSE, dd.accessMode().readOperation())));
        }
        return Collections.unmodifiableMap(implemented);
    }

    /**
     * Performs one call against the shared area: the exact Java equivalent of
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA} ({@code app/cbl/CBSTM03A.CBL:L351}).
     *
     * <p>This is the entry point every other public method is built on. For a <em>dispatchable</em> cell - a
     * recognised DD paired with an operation that dataset implements - it behaves exactly as the subprogram
     * does: it reports the outcome as a return code and <strong>never throws for an input or output
     * condition</strong>, because {@code CBSTM03B} has no abend path of its own. It moves a
     * {@code FILE STATUS} into the return code and returns, leaving every accept-or-abend decision to its
     * caller. Interpreting the code is therefore the caller's job, exactly as it is at the thirteen guard
     * sites in {@code app/cbl/CBSTM03A.CBL}, and {@code FileStatusMapper} is where that interpretation lives.
     *
     * <p><strong>It fails closed on the two cells the source leaves unimplemented.</strong> An unrecognised
     * DD name (defect B) and a recognised DD paired with an unimplemented operation (defect A) are refused
     * with an abend rather than answered with a pre-set or stale success - see
     * {@link #requireDispatchableCell(FileServiceRequest)} for the two conditions, and the class
     * documentation for why refusing them is the faithful choice rather than a deviation. The byte-exact
     * legacy behaviour remains available, deliberately under its own name, through
     * {@link #executeInLegacyParityMode(FileServiceRequest)}.
     *
     * @param request the shared area to act on, carrying the DD name, the operation, the key, the key length
     * and the caller's pre-set return code and payload. Must not be {@code null}
     * @return the outcome, always carrying a two character return code and a payload of exactly
     * {@link #PAYLOAD_WIDTH} characters. Never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the DD name is unrecognised, if the resolved
     * dataset does not implement the requested operation, if a validated shared-area field is out of range
     * for the resolved dataset, if the resolved dataset has no binding in this context, or if a binding
     * breaches its contract. Carries abend culprit {@code CBSTM03B}
     */
    public FileServiceResult execute(final FileServiceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireDispatchableCell(request);
        return executeInLegacyParityMode(request);
    }

    /**
     * Performs one call with the source's fail-open behaviour intact: the parity mode.
     *
     * <p><strong>This is the only entry point through which defects A and B are reachable, and it exists so
     * that they remain <em>provable</em> without being <em>reachable by accident</em>.</strong> An earlier
     * revision made this the behaviour of {@link #execute(FileServiceRequest)} itself, on the reasoning that
     * {@code CBSTM03B} has no abend path of its own and leaves every accept-or-abend decision to its caller.
     * That reasoning is right about the subprogram and wrong about the adapter, and the distinction is the
     * point of this method's existence:
     *
     * <ul>
     *   <li>In the source, the fail-open paths are <strong>unreachable</strong>. The single caller,
     *       {@code app/cbl/CBSTM03A.CBL}, only ever sets a DD name from its own literals and only ever pairs
     *       it with an operation that dataset implements, so no live execution can select an unknown name or
     *       an unimplemented cell. The defects are latent, not active.</li>
     *   <li>In Java, {@link FileServiceRequest} accepts an arbitrary DD-name string, so the same paths are
     *       <strong>reachable</strong>. Reproducing them on the default adapter therefore does not preserve
     *       the source's behaviour - it manufactures a fail-open path the source never had, and it
     *       contradicts the migration's own invariant that a file status becomes a typed exception on every
     *       I/O path and is never swallowed.</li>
     * </ul>
     *
     * <p>So {@link #execute(FileServiceRequest)} now fails closed - see
     * {@link #requireDispatchableCell(FileServiceRequest)} - and this method carries the byte-exact legacy
     * behaviour for the tests that assert both defects and for any future parity comparison that needs it. It
     * is named for what it is rather than being hidden behind a flag, because a boolean parameter on
     * {@code execute} would put the dangerous behaviour one typo away from a production call site.
     *
     * <p>Two consequences of calling this method, restated because they are the whole of the defects. An
     * unrecognised DD name yields the request's own pre-set return code and payload untouched, which for the
     * pre-sets a caller normally supplies means {@code '00'} and a blank record - defect B. A recognised DD
     * combined with an operation that dataset does not implement performs no input or output and yields the
     * status register left behind by the previous call on that DD - defect A.
     *
     * @param request the shared area to act on. Must not be {@code null}
     * @return the outcome, always carrying a two character return code and a payload of exactly
     * {@link #PAYLOAD_WIDTH} characters. Never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if a validated shared-area field is out of range
     * for the resolved dataset, if the resolved dataset has no binding in this context, or if a binding
     * breaches its contract. Carries abend culprit {@code CBSTM03B}
     */
    public FileServiceResult executeInLegacyParityMode(final FileServiceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final SharedArea area = new SharedArea(request);
        dispatch(area);
        final FileServiceResult result = new FileServiceResult(area.returnCode(), area.payload());
        if (LOG.isDebugEnabled()) {
            LOG.debug("CBSTM03B call: dd={} operation={} returnCode={}",
                    request.ddName(), request.operation().code(), result.returnCode());
        }
        return result;
    }

    /**
     * Refuses a request that would select a cell the source leaves unimplemented, closing defects A and B at
     * the public adapter.
     *
     * <p>Two conditions are refused, and each is refused because the alternative is a caller that believes an
     * operation succeeded when nothing happened:
     *
     * <ul>
     *   <li><strong>An unrecognised DD name</strong> - defect B. The dispatch would assign no return code at
     *       all, so the caller would read back its own pre-set {@code '00'} and an all-spaces payload and
     *       conclude it had read a blank record.</li>
     *   <li><strong>A recognised DD paired with an operation that dataset does not implement</strong> -
     *       defect A. No handler branch fires, no input or output occurs, and the status epilogue republishes
     *       whatever the previous call on that DD left in the register - so the second call inherits the
     *       first call's verdict.</li>
     * </ul>
     *
     * <p>Both are refused with {@code FatalProcessingException} carrying abend culprit {@code CBSTM03B} and
     * abend code 999, which is the same terminal outcome the corpus produces for any status its guards do not
     * accept ({@code app/cbl/CBTRN02C.cbl:L707-L711}). Nothing is swallowed and the message names the DD and
     * the operation, so the diagnosis does not require reading this class.
     *
     * @param request the request about to be dispatched; must not be {@code null}
     * @throws com.cardemo.exception.FatalProcessingException if the DD name is unrecognised, or if the
     * resolved dataset does not implement the requested operation
     */
    private static void requireDispatchableCell(final FileServiceRequest request) {
        final Optional<Dd> resolved = Dd.fromDdName(request.ddName());
        if (resolved.isEmpty()) {
            throw new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                    UNKNOWN_DD_REASON,
                    String.format(Locale.ROOT,
                            "DD name '%s' is not one of %s, so no handler exists for it. The source reaches "
                                    + "GOBACK without assigning LK-M03B-RC on this path "
                                    + "(app/cbl/CBSTM03B.CBL:L127-L131), which would report the caller's own "
                                    + "pre-set '00' as a successful read of a blank record. That fail-open "
                                    + "path is unreachable in the corpus, where the only caller supplies DD "
                                    + "names from its own literals, and it is refused here rather than "
                                    + "reproduced because a Java caller can reach it. Use "
                                    + "executeInLegacyParityMode if the legacy behaviour is what you need.",
                            FileStatus.escapeForDiagnostics(request.ddName()),
                            Arrays.stream(Dd.values()).map(Dd::ddName).toList()));
        }

        final Dd dd = resolved.get();
        if (!IMPLEMENTED_OPERATIONS.get(dd).contains(request.operation())) {
            throw new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                    UNIMPLEMENTED_OPERATION_REASON,
                    String.format(Locale.ROOT,
                            "Dataset %s does not implement operation '%s'; it implements %s. No handler "
                                    + "branch would fire, so the source falls through to its status epilogue "
                                    + "and republishes the register left by the previous call on this DD "
                                    + "(app/cbl/CBSTM03B.CBL:L151-L152), which makes the second call inherit "
                                    + "the first call's verdict. That fail-open path is unreachable in the "
                                    + "corpus and is refused here rather than reproduced. Call supports(...) "
                                    + "first, use a guarded method, or use executeInLegacyParityMode if the "
                                    + "legacy behaviour is what you need.",
                            dd.ddName(),
                            request.operation().code(),
                            IMPLEMENTED_OPERATIONS.get(dd).stream().map(Operation::code).toList()));
        }
    }

    /**
     * Opens a dataset for input, applying the guard of {@code 8100-TRNXFILE-OPEN}
     * ({@code app/cbl/CBSTM03A.CBL:L736}) and its siblings, which accept {@code '00'} or {@code '04'}.
     *
     * <p>The source opens every one of the four datasets {@code OPEN INPUT}
     * ({@code app/cbl/CBSTM03B.CBL:L136}, L160, L184, L209), so this method - and this class - offers read
     * access only. There is deliberately no write or rewrite counterpart.
     *
     * @param dd the dataset to open. Must not be {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException for any return code other than {@code '00'} or
     * {@code '04'}, including {@code '10'}; also when the dataset has no binding in this context
     */
    public void open(final Dd dd) {
        Objects.requireNonNull(dd, "dd must not be null");
        final FileServiceResult result = execute(FileServiceRequest.of(dd, Operation.OPEN));
        fileStatusMapper.requireFileServiceSuccess(result.returnCode(), dd.ddName(), Operation.OPEN.name());
    }

    /**
     * Closes a dataset, applying the guard of {@code 9100-TRNXFILE-CLOSE}
     * ({@code app/cbl/CBSTM03A.CBL:L862}) and its siblings, which accept {@code '00'} or {@code '04'}.
     *
     * @param dd the dataset to close. Must not be {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws com.cardemo.exception.FatalProcessingException for any return code other than {@code '00'} or
     * {@code '04'}; also when the dataset has no binding in this context
     */
    public void close(final Dd dd) {
        Objects.requireNonNull(dd, "dd must not be null");
        final FileServiceResult result = execute(FileServiceRequest.of(dd, Operation.CLOSE));
        fileStatusMapper.requireFileServiceSuccess(result.returnCode(), dd.ddName(), Operation.CLOSE.name());
    }

    /**
     * Reads the next record of a sequential dataset under the <strong>lenient</strong> guard of the initial
     * read in {@code 8100-TRNXFILE-OPEN} ({@code app/cbl/CBSTM03A.CBL:L748}), which accepts {@code '00'} or
     * {@code '04'} and treats neither as end of file.
     *
     * <p>The source uses this shape once per dataset, for the priming read that immediately follows the open,
     * where an empty dataset is not an anticipated outcome. Use {@link #readNext(Dd)} for the repeated
     * get-next loop, whose guard is different in a way that matters.
     *
     * @param dd the sequential dataset to read. Must not be {@code null} and must be
     * {@link AccessMode#SEQUENTIAL}
     * @return the payload, exactly {@link #PAYLOAD_WIDTH} characters and right padded with spaces. Never
     * {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws IllegalArgumentException if {@code dd} is a random-access dataset, which implements the keyed
     * read instead - one of the eight cells the source leaves unimplemented
     * @throws com.cardemo.exception.FatalProcessingException for any return code other than {@code '00'} or
     * {@code '04'}, including {@code '10'}; also when the dataset has no binding in this context
     */
    public String readAcceptingSecondaryStatus(final Dd dd) {
        requireSequential(dd, Operation.READ);
        final FileServiceResult result = execute(FileServiceRequest.of(dd, Operation.READ));
        fileStatusMapper.requireFileServiceSuccess(result.returnCode(), dd.ddName(), Operation.READ.name());
        return result.payload();
    }

    /**
     * Reads the next record of a sequential dataset under the get-next guard of
     * {@code 1000-XREFFILE-GET-NEXT} ({@code app/cbl/CBSTM03A.CBL:L353}) and
     * {@code 8500-READTRNX-READ} ({@code app/cbl/CBSTM03A.CBL:L837}), which accept {@code '00'} alone and
     * treat {@code '10'} as end of file.
     *
     * <p>Note the deliberate asymmetry against {@link #readAcceptingSecondaryStatus(Dd)}: {@code '04'} is
     * <strong>not</strong> accepted here. The two sequential get-next sites are the only read sites in the
     * source that recognise end of file, and they are also the only read sites that reject {@code '04'}.
     * Preserving that split is what keeps a short dataset from being mistaken for a failed one, and a
     * malformed status from being mistaken for the end of the data.
     *
     * @param dd the sequential dataset to read. Must not be {@code null} and must be
     * {@link AccessMode#SEQUENTIAL}
     * @return the payload when a record was returned, or an empty optional at end of file. Never
     * {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws IllegalArgumentException if {@code dd} is a random-access dataset
     * @throws com.cardemo.exception.FatalProcessingException for any return code other than {@code '00'} or
     * {@code '10'}, {@code '04'} included; also when the dataset has no binding in this context
     */
    public Optional<String> readNext(final Dd dd) {
        requireSequential(dd, Operation.READ);
        final FileServiceResult result = execute(FileServiceRequest.of(dd, Operation.READ));
        final boolean endOfFile = fileStatusMapper.requireFileServiceSuccessOrEndOfFile(
                result.returnCode(), dd.ddName(), Operation.READ.name());
        return endOfFile ? Optional.empty() : Optional.of(result.payload());
    }

    /**
     * Reads one record of a random-access dataset by key, under the <strong>strictest</strong> guard in the
     * caller: {@code 2000-CUSTFILE-GET} ({@code app/cbl/CBSTM03A.CBL:L379}) and
     * {@code 3000-ACCTFILE-GET} ({@code app/cbl/CBSTM03A.CBL:L403}) accept {@code '00'} and nothing else.
     *
     * <p>Those two blocks carry no {@code WHEN '10'} branch at all, unlike their two sequential counterparts,
     * so at a keyed site end of file is an abend rather than an absence and a missing customer or account
     * stops the run. This method therefore returns the payload directly instead of an optional: there is no
     * successful outcome in which no record was returned. Treating a keyed read like a sequential one would
     * silently skip a missing record where the source stops, which is why the two are separate methods.
     *
     * <p>The key follows {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN)} ({@code app/cbl/CBSTM03B.CBL:L189} for
     * {@code CUSTFILE}, L214 for {@code ACCTFILE}): the first {@code keyLength} characters of the 25 character
     * key field are taken and assigned to the record key. The caller computes that length from the field it
     * is searching on - {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID} yields 9 and
     * {@code LENGTH OF XREF-ACCT-ID} yields 11 - which is exactly what each dataset's record key requires.
     *
     * @param dd the random-access dataset to read. Must not be {@code null} and must be
     * {@link AccessMode#RANDOM}
     * @param key the key field, carrying {@code LK-M03B-KEY PIC X(25)}. Must not be {@code null}. A shorter
     * value is right padded with spaces and a longer one truncated, exactly as a COBOL move into that field
     * would be
     * @param keyLength the effective key length, carrying {@code LK-M03B-KEY-LN PIC S9(4)}. Must be between
     * {@link #MINIMUM_KEY_LENGTH} and {@link #KEY_WIDTH} inclusive, and the extracted key must match the
     * dataset's record key exactly - 9 characters for {@code CUSTFILE}, 11 ASCII digits for {@code ACCTFILE}
     * @return the payload, exactly {@link #PAYLOAD_WIDTH} characters and right padded with spaces. Never
     * {@code null}
     * @throws NullPointerException if {@code dd} or {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code dd} is a sequential dataset, which implements the plain read
     * instead
     * @throws com.cardemo.exception.FatalProcessingException if {@code keyLength} is out of range, if the
     * extracted key does not match the dataset's record key, for any return code other than {@code '00'}, or
     * when the dataset has no binding in this context
     */
    public String readByKey(final Dd dd, final String key, final int keyLength) {
        requireRandom(dd, Operation.READ_K);
        final FileServiceResult result = execute(FileServiceRequest.keyed(dd, key, keyLength));
        final boolean endOfFile = fileStatusMapper.requireFileServiceSuccessOrEndOfFile(
                result.returnCode(), dd.ddName(), Operation.READ_K.name());
        if (endOfFile) {
            throw keyedEndOfFileAbend(dd, result.returnCode());
        }
        return result.payload();
    }

    /**
     * Reports whether a dataset implements an operation, exposing the 12 of 24 cell matrix programmatically.
     *
     * <p>Call this before {@link #execute(FileServiceRequest)} to avoid defect A. It is the only way to learn,
     * without reading the source, that {@code TRNXFILE} has no keyed read and that no dataset has a write.
     *
     * @param dd the dataset to interrogate. Must not be {@code null}
     * @param operation the operation to interrogate. Must not be {@code null}
     * @return {@code true} only for the twelve pairs the source implements
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean supports(final Dd dd, final Operation operation) {
        Objects.requireNonNull(dd, "dd must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        return IMPLEMENTED_OPERATIONS.get(dd).contains(operation);
    }

    /**
     * Reports whether a dataset binding for this DD was contributed to the application context.
     *
     * <p>A context that does not run the statement job binds nothing, and this bean still starts, so a caller
     * or a health check that needs to know can ask rather than provoke an abend.
     *
     * @param dd the dataset to interrogate. Must not be {@code null}
     * @return {@code true} when a binding is present and the DD can therefore be driven
     * @throws NullPointerException if {@code dd} is {@code null}
     */
    public boolean isBound(final Dd dd) {
        Objects.requireNonNull(dd, "dd must not be null");
        return datasets.containsKey(dd);
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraph 1 of 14 - the dispatch.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 0000-START.} ({@code app/cbl/CBSTM03B.CBL:L116}), the entry paragraph, whose whole body
     * is the {@code EVALUATE LK-M03B-DD} at L118.
     *
     * <p>The four recognised DD names each perform their handler through its terminator, L119 to L126, and
     * {@code WHEN OTHER} at L127 branches straight to the return paragraph at L128. Both exits from the
     * evaluate are preserved: the unrecognised name reaches the return through that explicit branch, and a
     * recognised name reaches it by natural fall-through once its handler has finished.
     *
     * <p>The carriage-return-stripped source puts this paragraph at L116 and its evaluate at L118, and those
     * are the lines used here.
     *
     * @param area the shared area, mutated in place exactly as {@code LK-M03B-AREA} is. Must not be
     * {@code null}
     */
    private void dispatch(final SharedArea area) {
        final Optional<Dd> resolved = Dd.fromDdName(area.ddName());
        if (resolved.isEmpty()) {
            // WHEN OTHER (L127) - GO TO 9999-GOBACK (L128). No input or output is performed and, decisively,
            // the return code is never assigned, so the caller's own pre-set value survives. This is defect B.
            goback();
            return;
        }
        HANDLERS.get(resolved.get()).handle(this, area);
        // Natural fall-through past END-EVALUATE into the return paragraph.
        goback();
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraph 2 of 14 - the return.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 9999-GOBACK.} ({@code app/cbl/CBSTM03B.CBL:L130}), whose body is the single statement
     * {@code GOBACK.} at L131.
     *
     * <p>Returning control to the caller is implicit in Java, so this paragraph has no executable
     * counterpart. It is retained rather than elided because it is a genuine paragraph with two distinct
     * entries - the {@code WHEN OTHER} branch and the fall-through - and because eliding it would drop a
     * label from the paragraph map the scope-coverage gate reads. Intentional no-op.
     *
     * <p>Its emptiness is also the whole of defect B: reaching {@code GOBACK} without having assigned
     * {@code LK-M03B-RC} is what lets an unknown DD name report success. It takes no shared area precisely
     * because it touches none - there is no field it may write.
     */
    private void goback() {
        // GOBACK. (L131) - returning control is implicit in Java. Intentionally empty; see the Javadoc above.
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraphs 3, 4 and 5 of 14 - TRNXFILE, the sequential statement work file.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 1000-TRNXFILE-PROC.} ({@code app/cbl/CBSTM03B.CBL:L133}), the handler for the sequential
     * statement work file whose record is 350 bytes: a 32 byte key of card number plus transaction identifier,
     * then 318 bytes of data (L61 to L63).
     *
     * <p>Three of the six operations are implemented, each as an independent {@code IF} that branches to the
     * status epilogue on a match: {@code OPEN INPUT} at L136, sequential {@code READ ... INTO LK-M03B-FLDT} at
     * L141, and {@code CLOSE} at L147. The keyed read, the write and the rewrite are not implemented for this
     * dataset - the first because its access mode is sequential, the last two because the dataset is opened
     * for input only and so cannot be written at all.
     *
     * <p>When none of the three matches, control falls out of the paragraph without performing any input or
     * output and lands on the status epilogue regardless. That is defect A, and it is reproduced rather than
     * corrected.
     *
     * @param area the shared area, mutated in place. Must not be {@code null}
     */
    private void trnxfileProc(final SharedArea area) {
        if (area.operation() == Operation.OPEN) {
            openInput(Dd.TRNXFILE);
            return;
        }
        if (area.operation() == Operation.READ) {
            sequentialRead(Dd.TRNXFILE, area);
            return;
        }
        if (area.operation() == Operation.CLOSE) {
            closeDataset(Dd.TRNXFILE);
            return;
        }
        // No IF fired: the keyed read, the write and the rewrite are unimplemented for this dataset. No input
        // or output occurs and the status epilogue publishes the register unchanged - defect A.
    }

    /**
     * Carries {@code 1900-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L151}), whose body is
     * {@code MOVE TRNXFILE-STATUS TO LK-M03B-RC.} at L152.
     *
     * <p>This is a status-propagation epilogue, <strong>not</strong> a terminator: every
     * {@code GO TO 1900-EXIT} inside the handler lands here, and so does the fall-through when no operation
     * matched. It is deliberately kept separate from {@code 1999-EXIT.}; collapsing the pair would drop four
     * paragraphs from the map across the four datasets.
     *
     * <p>It publishes whatever the status register currently holds. After a real operation that is the status
     * that operation reported; after an unimplemented one it is the status left by the previous call on this
     * DD, or the unset value on the very first call. Publishing unconditionally is exactly what the source
     * does, and it is the mechanism of defect A.
     *
     * @param area the shared area whose return code is assigned. Must not be {@code null}
     */
    private void trnxfileStatusEpilogue(final SharedArea area) {
        area.publishReturnCode(statusRegisters.get(Dd.TRNXFILE).get());
    }

    /**
     * Carries {@code 1999-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L154}), whose body is the single statement
     * {@code EXIT.} at L155.
     *
     * <p>{@code EXIT} is COBOL's no-operation, present solely so that
     * {@code PERFORM 1000-TRNXFILE-PROC THRU 1999-EXIT} (L120) has a paragraph to stop at. It has no
     * executable counterpart in Java and is retained as an explicit, intentional no-op so that the paragraph
     * map stays complete and one-to-one.
     */
    private void trnxfileTerminator() {
        // EXIT. (L155) - COBOL's no-operation. Intentionally empty; see the Javadoc above.
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraphs 6, 7 and 8 of 14 - XREFFILE, the sequential card cross-reference.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 2000-XREFFILE-PROC.} ({@code app/cbl/CBSTM03B.CBL:L157}), the handler for the sequential
     * card cross-reference whose record is 50 bytes: a 16 byte card number key then 34 bytes of data (L67,
     * L68).
     *
     * <p>Structurally identical to the {@code TRNXFILE} handler and implementing the same three operations:
     * {@code OPEN INPUT} at L160, sequential {@code READ ... INTO LK-M03B-FLDT} at L165, {@code CLOSE} at
     * L171. The keyed read, the write and the rewrite are unimplemented for the same two reasons.
     *
     * <p>The 34 data bytes carry the customer and account identifiers this dataset exists to resolve, and the
     * 16 key bytes are a card number, so neither the payload nor the key may ever be logged.
     *
     * @param area the shared area, mutated in place. Must not be {@code null}
     */
    private void xreffileProc(final SharedArea area) {
        if (area.operation() == Operation.OPEN) {
            openInput(Dd.XREFFILE);
            return;
        }
        if (area.operation() == Operation.READ) {
            sequentialRead(Dd.XREFFILE, area);
            return;
        }
        if (area.operation() == Operation.CLOSE) {
            closeDataset(Dd.XREFFILE);
            return;
        }
        // No IF fired - defect A, as in the TRNXFILE handler.
    }

    /**
     * Carries {@code 2900-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L175}), whose body is
     * {@code MOVE XREFFILE-STATUS TO LK-M03B-RC.} at L176. The {@code TRNXFILE} epilogue's contract applies
     * verbatim, over this dataset's own status register.
     *
     * @param area the shared area whose return code is assigned. Must not be {@code null}
     */
    private void xreffileStatusEpilogue(final SharedArea area) {
        area.publishReturnCode(statusRegisters.get(Dd.XREFFILE).get());
    }

    /**
     * Carries {@code 2999-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L178}), whose body is {@code EXIT.} at L179.
     * The terminator for {@code PERFORM 2000-XREFFILE-PROC THRU 2999-EXIT} (L122). Intentional no-op.
     */
    private void xreffileTerminator() {
        // EXIT. (L179) - COBOL's no-operation. Intentionally empty.
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraphs 9, 10 and 11 of 14 - CUSTFILE, the random-access customer master.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 3000-CUSTFILE-PROC.} ({@code app/cbl/CBSTM03B.CBL:L181}), the handler for the
     * random-access customer master whose record is 500 bytes: a 9 character key then 491 bytes of data (L72,
     * L73).
     *
     * <p>Here the access mode changes the shape of the handler. {@code OPEN INPUT} at L184 and {@code CLOSE}
     * at L196 are unchanged, but the middle operation is the <strong>keyed</strong> read at L188 to L190,
     * which first assigns the reference-modified key with
     * {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID} and then reads. The plain sequential read is
     * unimplemented for this dataset, because its access mode is random; the write and the rewrite are
     * unimplemented because it is opened for input only.
     *
     * <p>Those 491 data bytes are the single largest concentration of personal data in the corpus - social
     * security number, government issued identifier, date of birth, electronic funds account identifier and
     * two telephone numbers among them - so the payload is never logged, never serialised and never placed in
     * an exception message.
     *
     * @param area the shared area, mutated in place. Must not be {@code null}
     */
    private void custfileProc(final SharedArea area) {
        if (area.operation() == Operation.OPEN) {
            openInput(Dd.CUSTFILE);
            return;
        }
        if (area.operation() == Operation.READ_K) {
            keyedRead(Dd.CUSTFILE, area);
            return;
        }
        if (area.operation() == Operation.CLOSE) {
            closeDataset(Dd.CUSTFILE);
            return;
        }
        // No IF fired: the plain read, the write and the rewrite are unimplemented here - defect A.
    }

    /**
     * Carries {@code 3900-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L200}), whose body is
     * {@code MOVE CUSTFILE-STATUS TO LK-M03B-RC.} at L201.
     *
     * @param area the shared area whose return code is assigned. Must not be {@code null}
     */
    private void custfileStatusEpilogue(final SharedArea area) {
        area.publishReturnCode(statusRegisters.get(Dd.CUSTFILE).get());
    }

    /**
     * Carries {@code 3999-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L203}), whose body is {@code EXIT.} at L204.
     * The terminator for {@code PERFORM 3000-CUSTFILE-PROC THRU 3999-EXIT} (L124). Intentional no-op.
     */
    private void custfileTerminator() {
        // EXIT. (L204) - COBOL's no-operation. Intentionally empty.
    }

    // ------------------------------------------------------------------------------------------------------
    // Paragraphs 12, 13 and 14 of 14 - ACCTFILE, the random-access account master.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Carries {@code 4000-ACCTFILE-PROC.} ({@code app/cbl/CBSTM03B.CBL:L206}), the handler for the
     * random-access account master whose record is 300 bytes: an 11 digit key then 289 bytes of data (L77,
     * L78).
     *
     * <p>Shaped like the {@code CUSTFILE} handler: {@code OPEN INPUT} at L209, keyed read at L213 to L215
     * with {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID}, {@code CLOSE} at L221.
     *
     * <p>One detail sets this dataset apart from the other three and it is load bearing:
     * {@code FD-ACCT-ID} is declared {@code PIC 9(11)} at L77, that is <strong>numeric</strong>, where the
     * other three record keys are alphanumeric {@code PIC X(n)}. A move of non-numeric characters into a
     * numeric field is undefined in COBOL and would abend under numeric checking, so the extracted key is
     * validated to be exactly eleven ASCII digits before it is used. The caller supplies precisely that,
     * computing the length from an eleven digit field ({@code app/cbl/CBSTM03A.CBL:L397}).
     *
     * @param area the shared area, mutated in place. Must not be {@code null}
     */
    private void acctfileProc(final SharedArea area) {
        if (area.operation() == Operation.OPEN) {
            openInput(Dd.ACCTFILE);
            return;
        }
        if (area.operation() == Operation.READ_K) {
            keyedRead(Dd.ACCTFILE, area);
            return;
        }
        if (area.operation() == Operation.CLOSE) {
            closeDataset(Dd.ACCTFILE);
            return;
        }
        // No IF fired: the plain read, the write and the rewrite are unimplemented here - defect A.
    }

    /**
     * Carries {@code 4900-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L225}), whose body is
     * {@code MOVE ACCTFILE-STATUS TO LK-M03B-RC.} at L226.
     *
     * @param area the shared area whose return code is assigned. Must not be {@code null}
     */
    private void acctfileStatusEpilogue(final SharedArea area) {
        area.publishReturnCode(statusRegisters.get(Dd.ACCTFILE).get());
    }

    /**
     * Carries {@code 4999-EXIT.} ({@code app/cbl/CBSTM03B.CBL:L228}), whose body is {@code EXIT.} at L229.
     * The terminator for {@code PERFORM 4000-ACCTFILE-PROC THRU 4999-EXIT} (L126) and the last paragraph of
     * the program. Intentional no-op.
     */
    private void acctfileTerminator() {
        // EXIT. (L229) - COBOL's no-operation. Intentionally empty.
    }

    // ------------------------------------------------------------------------------------------------------
    // The four dataset verbs. These carry FILE-CONTROL. (app/cbl/CBSTM03B.CBL:L30-L53), which is an Area A
    // paragraph of the ENVIRONMENT DIVISION rather than of the PROCEDURE DIVISION, so it maps to the dataset
    // bindings and their verbs rather than to a paragraph method of its own. It is the fifteenth Area A label
    // in the file and the reconciliation of the fifteen-paragraph count.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Performs {@code OPEN INPUT} against a dataset and records the resulting status in that dataset's
     * register ({@code app/cbl/CBSTM03B.CBL:L136}, L160, L184, L209).
     *
     * <p>Input only, on all four datasets, without exception. This single fact is why the write and rewrite
     * operation codes are unreachable and why this class exposes no write capability - least privilege that
     * the source already imposed.
     *
     * <p>The payload is deliberately untouched: a COBOL {@code OPEN} does not move a record.
     *
     * @param dd the dataset to open. Never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException when the dataset has no binding, or its binding
     * returns a malformed status
     */
    private void openInput(final Dd dd) {
        final Dataset dataset = resolveDataset(dd, Operation.OPEN);
        statusRegisters.get(dd).set(requireStatus(dd, Operation.OPEN, dataset.openInput()));
    }

    /**
     * Performs {@code CLOSE} against a dataset and records the resulting status in that dataset's register
     * ({@code app/cbl/CBSTM03B.CBL:L147}, L171, L196, L221). The payload is deliberately untouched.
     *
     * @param dd the dataset to close. Never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException when the dataset has no binding, or its binding
     * returns a malformed status
     */
    private void closeDataset(final Dd dd) {
        final Dataset dataset = resolveDataset(dd, Operation.CLOSE);
        statusRegisters.get(dd).set(requireStatus(dd, Operation.CLOSE, dataset.close()));
    }

    /**
     * Performs {@code READ ... INTO LK-M03B-FLDT} against a sequential dataset
     * ({@code app/cbl/CBSTM03B.CBL:L141}, L165).
     *
     * <p>The payload is replaced only when a record was actually returned. At end of file COBOL moves nothing,
     * so the buffer keeps the value the caller pre-set - which is why a caller writes
     * {@code MOVE SPACES TO WS-M03B-FLDT} before every read ({@code app/cbl/CBSTM03A.CBL:L350}) and why this
     * class leaves the pre-set value in place rather than blanking or nulling it.
     *
     * @param dd the sequential dataset to read. Never {@code null}
     * @param area the shared area whose payload is assigned on a successful read. Never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException when the dataset has no binding, or its binding
     * returns a malformed result
     */
    private void sequentialRead(final Dd dd, final SharedArea area) {
        final Dataset dataset = resolveDataset(dd, Operation.READ);
        final DatasetRead read = requireRead(dd, Operation.READ, dataset.readNext());
        statusRegisters.get(dd).set(read.status());
        if (read.hasRecord()) {
            area.publishPayload(padOrTruncate(read.record(), PAYLOAD_WIDTH));
        }
    }

    /**
     * Performs the two statement pair of a keyed read against a random-access dataset: the reference-modified
     * key assignment and then {@code READ ... INTO LK-M03B-FLDT}
     * ({@code app/cbl/CBSTM03B.CBL:L189-L190} for {@code CUSTFILE}, L214 to L215 for {@code ACCTFILE}).
     *
     * <p>As with the sequential read, the payload is replaced only when a record was returned.
     *
     * @param dd the random-access dataset to read. Never {@code null}
     * @param area the shared area supplying the key and receiving the payload. Never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException when the key length is out of range, the
     * extracted key does not match the dataset's record key, the dataset has no binding, or its binding
     * returns a malformed result
     */
    private void keyedRead(final Dd dd, final SharedArea area) {
        final Dataset dataset = resolveDataset(dd, Operation.READ_K);
        final String recordKey = extractRecordKey(dd, area);
        final DatasetRead read = requireRead(dd, Operation.READ_K, dataset.readByKey(recordKey));
        statusRegisters.get(dd).set(read.status());
        if (read.hasRecord()) {
            area.publishPayload(padOrTruncate(read.record(), PAYLOAD_WIDTH));
        }
    }

    /**
     * Applies {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-xxx-ID}
     * ({@code app/cbl/CBSTM03B.CBL:L189}, L214) and validates the result against the dataset's record key.
     *
     * <p>COBOL reference modification with a runtime length is undefined when the length falls outside the
     * field, and a move of non-numeric characters into a numeric key is undefined too. Both are validated
     * explicitly here rather than left to chance, which is a deliberate, labelled hardening of the source's
     * silent behaviour: the source would truncate or pad a mismatched key without complaint and read the wrong
     * record. Failing loudly with the dataset, the operation and the expected width is the only outcome that
     * cannot corrupt a statement.
     *
     * <p>The key value itself never reaches the message, the log or the exception. A customer or account
     * identifier is personal data, so only its length and the expectation it violated are reported.
     *
     * @param dd the random-access dataset whose record key applies. Never {@code null}
     * @param area the shared area carrying the key and its length. Never {@code null}
     * @return the extracted key, exactly {@code dd.keyWidth()} characters
     * @throws com.cardemo.exception.FatalProcessingException if the key length is outside
     * {@link #MINIMUM_KEY_LENGTH} to {@link #KEY_WIDTH}, if the extracted key is not exactly the record key
     * width, or if a numeric record key would receive a non-digit
     */
    private String extractRecordKey(final Dd dd, final SharedArea area) {
        final int keyLength = area.keyLength();
        if (keyLength < MINIMUM_KEY_LENGTH || keyLength > KEY_WIDTH) {
            throw invalidSharedAreaAbend("key length " + keyLength + " for " + dd.ddName()
                    + " is outside the " + MINIMUM_KEY_LENGTH + " to " + KEY_WIDTH
                    + " bound of LK-M03B-KEY PIC X(25)");
        }
        final String extracted = area.key().substring(0, keyLength);
        if (extracted.length() != dd.keyWidth()) {
            throw invalidSharedAreaAbend("key length " + keyLength + " for " + dd.ddName()
                    + " does not match its record key width of " + dd.keyWidth());
        }
        if (dd.numericKey() && !isAllAsciiDigits(extracted)) {
            throw invalidSharedAreaAbend("the key supplied for " + dd.ddName()
                    + " is not " + dd.keyWidth() + " ASCII digits, which its numeric record key requires");
        }
        return extracted;
    }

    /**
     * Reports whether every character is an ASCII digit.
     *
     * <p>Deliberately not {@code Character.isDigit}, which also accepts the decimal digits of other scripts
     * and would let a non-ASCII digit reach a {@code PIC 9(11)} field that cannot represent it.
     *
     * @param value the text to test. Never {@code null}
     * @return {@code true} when the text is non-empty and every character is one of {@code '0'} to {@code '9'}
     */
    private static boolean isAllAsciiDigits(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a dataset binding, or abends naming the DD that has none.
     *
     * @param dd the dataset wanted. Never {@code null}
     * @param operation the operation being attempted, for the diagnostic. Never {@code null}
     * @return the binding, never {@code null}
     * @throws com.cardemo.exception.FatalProcessingException when no binding for this DD was contributed to
     * the application context
     */
    private Dataset resolveDataset(final Dd dd, final Operation operation) {
        final Dataset dataset = datasets.get(dd);
        if (dataset == null) {
            throw new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                    UNBOUND_DATASET_REASON,
                    operation.name() + " of " + dd.ddName()
                            + " cannot proceed: no dataset binding for that DD is present in this context");
        }
        return dataset;
    }

    /**
     * Validates a status a binding reported, so that a contract breach fails here rather than surfacing later
     * as an unexplained abend from the status guard.
     *
     * @param dd the dataset that reported it. Never {@code null}
     * @param operation the operation that produced it. Never {@code null}
     * @param status the reported status, which a faulty binding may leave {@code null} or misshapen
     * @return the status, guaranteed non-null and exactly {@link FileStatus#STATUS_CODE_LENGTH} characters
     * @throws com.cardemo.exception.FatalProcessingException when the status is {@code null} or not exactly
     * two characters
     */
    private String requireStatus(final Dd dd, final Operation operation, final String status) {
        if (status == null || status.length() != FileStatus.STATUS_CODE_LENGTH) {
            throw new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                    BROKEN_BINDING_REASON,
                    operation.name() + " of " + dd.ddName() + " returned the status '"
                            + FileStatus.escapeForDiagnostics(status) + "', which is not the required "
                            + FileStatus.STATUS_CODE_LENGTH + " characters");
        }
        return status;
    }

    /**
     * Validates a read result a binding reported.
     *
     * @param dd the dataset that reported it. Never {@code null}
     * @param operation the read form that produced it. Never {@code null}
     * @param read the reported result, which a faulty binding may leave {@code null}
     * @return the result, with its status validated
     * @throws com.cardemo.exception.FatalProcessingException when the result or its status is {@code null}, or
     * the status is not exactly two characters
     */
    private DatasetRead requireRead(final Dd dd, final Operation operation, final DatasetRead read) {
        if (read == null) {
            throw new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                    BROKEN_BINDING_REASON,
                    operation.name() + " of " + dd.ddName() + " returned no result at all");
        }
        requireStatus(dd, operation, read.status());
        return read;
    }

    /**
     * Rejects a sequential operation aimed at a random-access dataset.
     *
     * @param dd the dataset requested. Must not be {@code null}
     * @param operation the read form requested, for the diagnostic. Never {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws IllegalArgumentException when the dataset's access mode does not implement this read form, which
     * is one of the eight cells the source leaves unimplemented
     */
    private static void requireSequential(final Dd dd, final Operation operation) {
        Objects.requireNonNull(dd, "dd must not be null");
        if (dd.accessMode() != AccessMode.SEQUENTIAL) {
            throw new IllegalArgumentException(dd.ddName() + " is " + dd.accessMode()
                    + " and does not implement " + operation.name()
                    + "; use readByKey for a random-access dataset");
        }
    }

    /**
     * Rejects a keyed operation aimed at a sequential dataset.
     *
     * @param dd the dataset requested. Must not be {@code null}
     * @param operation the read form requested, for the diagnostic. Never {@code null}
     * @throws NullPointerException if {@code dd} is {@code null}
     * @throws IllegalArgumentException when the dataset's access mode does not implement this read form
     */
    private static void requireRandom(final Dd dd, final Operation operation) {
        Objects.requireNonNull(dd, "dd must not be null");
        if (dd.accessMode() != AccessMode.RANDOM) {
            throw new IllegalArgumentException(dd.ddName() + " is " + dd.accessMode()
                    + " and does not implement " + operation.name()
                    + "; use readNext for a sequential dataset");
        }
    }

    /**
     * Builds the abend for an end-of-file code at a keyed read site, where the caller accepts {@code '00'}
     * alone ({@code app/cbl/CBSTM03A.CBL:L379}, L403).
     *
     * @param dd the dataset that reported it. Never {@code null}
     * @param returnCode the reported code, {@code '10'} at this point
     * @return the abend, never {@code null}
     */
    private static FatalProcessingException keyedEndOfFileAbend(final Dd dd, final String returnCode) {
        return new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                "UNACCEPTED RETURN CODE FROM CBSTM03B KEYED READ",
                Operation.READ_K.name() + " of " + dd.ddName() + " reported "
                        + FileStatusMapper.FILE_SERVICE_RETURN_CODE_TEXT
                        + FileStatus.escapeForDiagnostics(returnCode)
                        + ", which a keyed read site does not accept");
    }

    /**
     * Builds the abend for a shared-area field that violates its own picture clause, reproducing the runtime
     * failure COBOL would suffer on an undefined reference modification or numeric move.
     *
     * @param detail what was wrong, never containing a key value or any record content
     * @return the abend, never {@code null}
     */
    private static FatalProcessingException invalidSharedAreaAbend(final String detail) {
        return new FatalProcessingException(FileStatusMapper.ABEND_CODE_UNSET, ABEND_CULPRIT,
                INVALID_SHARED_AREA_REASON, detail);
    }

    /**
     * Pads with spaces on the right, or truncates on the right, to reach exactly the wanted width.
     *
     * <p>This is a COBOL alphanumeric move: {@code MOVE} into {@code PIC X(n)} left justifies, pads short and
     * truncates long, silently in both directions. Used for the 1000 character payload and the 25 character
     * key. The payload is never trimmed, because its trailing spaces are part of the record geometry the
     * parity comparison depends on.
     *
     * @param value the text to fit. Never {@code null}
     * @param width the wanted width, always positive
     * @return text of exactly {@code width} characters
     */
    private static String padOrTruncate(final String value, final int width) {
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    // ------------------------------------------------------------------------------------------------------
    // The shared-area contract, as types.
    // ------------------------------------------------------------------------------------------------------

    /**
     * The two VSAM access modes {@code FILE-CONTROL.} declares, and the read form each one implements.
     *
     * <p>The split is not cosmetic. {@code ACCESS MODE IS SEQUENTIAL} appears at
     * {@code app/cbl/CBSTM03B.CBL:L33} and L39, {@code ACCESS MODE IS RANDOM} at L45 and L51, and it is
     * exactly this declaration that decides which of the two read operation codes each dataset's handler
     * implements. Four datasets are therefore not interchangeable, and eight of the twenty-four cells of the
     * operation matrix are unimplemented as a direct consequence.
     */
    public enum AccessMode {

        /**
         * {@code ACCESS MODE IS SEQUENTIAL} ({@code app/cbl/CBSTM03B.CBL:L33}, L39). Implements the plain read
         * {@code 'R'} and never the keyed read.
         */
        SEQUENTIAL(Operation.READ),

        /**
         * {@code ACCESS MODE IS RANDOM} ({@code app/cbl/CBSTM03B.CBL:L45}, L51). Implements the keyed read
         * {@code 'K'} and never the plain read.
         */
        RANDOM(Operation.READ_K);

        /**
         * The read operation this access mode implements.
         */
        private final Operation readOperation;

        /**
         * Binds an access mode to its read form.
         *
         * @param readOperation the read operation this mode implements. Never {@code null}
         */
        AccessMode(final Operation readOperation) {
            this.readOperation = readOperation;
        }

        /**
         * Returns the one read operation this access mode implements.
         *
         * @return {@link Operation#READ} for sequential access, {@link Operation#READ_K} for random access.
         * Never {@code null}
         */
        public Operation readOperation() {
            return readOperation;
        }
    }

    /**
     * The four DD names {@code EVALUATE LK-M03B-DD} recognises, each with the physical geometry its
     * {@code FD} declares.
     *
     * <p>Every name is exactly {@link #DD_NAME_WIDTH} characters, matching {@code LK-M03B-DD PIC X(08)}, and
     * every width is taken from {@code app/cbl/CBSTM03B.CBL:L58-L78} rather than assumed. The record widths
     * corroborate the VSAM catalogue independently: 350 matches the statement work cluster defined
     * {@code KEYS(32 0) RECORDSIZE(350 350)} in {@code app/jcl/CREASTMT.JCL}, whose 32 byte key is the card
     * number plus transaction identifier of {@code app/cpy/COSTM01.CPY}; 50, 500 and 300 match the
     * cross-reference, customer and account clusters.
     */
    public enum Dd {

        /**
         * The sequential statement work file. Record 350 = 32 key plus 318 data
         * ({@code app/cbl/CBSTM03B.CBL:L61-L63}); the key is itself {@code FD-TRNX-CARD PIC X(16)} followed by
         * {@code FD-TRNX-ID PIC X(16)}.
         */
        TRNXFILE("TRNXFILE", AccessMode.SEQUENTIAL, 32, 318, false),

        /**
         * The sequential card cross-reference. Record 50 = 16 key plus 34 data
         * ({@code app/cbl/CBSTM03B.CBL:L67-L68}); the key is a card number.
         */
        XREFFILE("XREFFILE", AccessMode.SEQUENTIAL, 16, 34, false),

        /**
         * The random-access customer master. Record 500 = 9 key plus 491 data
         * ({@code app/cbl/CBSTM03B.CBL:L72-L73}). Its 491 data bytes carry the corpus's densest concentration
         * of personal data.
         */
        CUSTFILE("CUSTFILE", AccessMode.RANDOM, 9, 491, false),

        /**
         * The random-access account master. Record 300 = 11 key plus 289 data
         * ({@code app/cbl/CBSTM03B.CBL:L77-L78}).
         *
         * <p>The only dataset of the four whose record key is numeric: {@code FD-ACCT-ID PIC 9(11)} at L77,
         * against {@code PIC X(n)} for the other three. A key offered to this dataset must therefore be
         * eleven ASCII digits.
         */
        ACCTFILE("ACCTFILE", AccessMode.RANDOM, 11, 289, true);

        /**
         * The eight character DD name, exactly as the source's {@code WHEN} literals spell it.
         */
        private final String ddName;

        /**
         * The declared access mode, which fixes the read form.
         */
        private final AccessMode accessMode;

        /**
         * Width of the record key in characters.
         */
        private final int keyWidth;

        /**
         * Width of the data portion in characters.
         */
        private final int dataWidth;

        /**
         * Whether the record key is numeric rather than alphanumeric.
         */
        private final boolean numericKey;

        /**
         * Binds a DD name to its declared geometry.
         *
         * @param ddName the eight character DD name. Never {@code null}
         * @param accessMode the declared access mode. Never {@code null}
         * @param keyWidth width of the record key
         * @param dataWidth width of the data portion
         * @param numericKey whether the record key is numeric
         */
        Dd(final String ddName, final AccessMode accessMode, final int keyWidth, final int dataWidth,
                final boolean numericKey) {
            this.ddName = ddName;
            this.accessMode = accessMode;
            this.keyWidth = keyWidth;
            this.dataWidth = dataWidth;
            this.numericKey = numericKey;
        }

        /**
         * Returns the eight character DD name.
         *
         * @return the DD name, always exactly {@link #DD_NAME_WIDTH} characters. Never {@code null}
         */
        public String ddName() {
            return ddName;
        }

        /**
         * Returns the declared access mode.
         *
         * @return the access mode, which fixes which read form this dataset implements. Never {@code null}
         */
        public AccessMode accessMode() {
            return accessMode;
        }

        /**
         * Returns the width of the record key.
         *
         * @return 32, 16, 9 or 11 characters
         */
        public int keyWidth() {
            return keyWidth;
        }

        /**
         * Returns the width of the data portion that follows the key.
         *
         * @return 318, 34, 491 or 289 characters
         */
        public int dataWidth() {
            return dataWidth;
        }

        /**
         * Returns the total record width, which must be preserved byte-exactly.
         *
         * @return 350, 50, 500 or 300 characters, the sum of the key and data widths
         */
        public int recordWidth() {
            return keyWidth + dataWidth;
        }

        /**
         * Reports whether the record key is numeric.
         *
         * @return {@code true} only for {@link #ACCTFILE}, whose key is {@code PIC 9(11)}
         */
        public boolean numericKey() {
            return numericKey;
        }

        /**
         * Resolves a raw DD name, reproducing {@code EVALUATE LK-M03B-DD}
         * ({@code app/cbl/CBSTM03B.CBL:L118}).
         *
         * <p>The comparison is byte-exact and case-sensitive, as the source's {@code WHEN} literals are, and
         * the candidate is first fitted to {@link #DD_NAME_WIDTH} characters, as a move into
         * {@code PIC X(08)} would fit it. So {@code "TRNXFILE "} resolves - the ninth character is truncated
         * away - while {@code "trnxfile"} does not. No case folding is applied, so no locale can change the
         * answer.
         *
         * <p>An unresolved name is the {@code WHEN OTHER} path of L127, which performs no input or output and
         * leaves the return code untouched. That is defect B, and returning an empty optional rather than
         * throwing is what preserves it.
         *
         * @param candidate the raw DD name to resolve, which may be any length. Must not be {@code null}
         * @return the matching DD, or an empty optional for every unrecognised name including a blank one
         * @throws NullPointerException if {@code candidate} is {@code null}
         */
        public static Optional<Dd> fromDdName(final String candidate) {
            Objects.requireNonNull(candidate, "candidate must not be null");
            final String fitted = padOrTruncate(candidate, DD_NAME_WIDTH);
            for (final Dd dd : values()) {
                if (dd.ddName.equals(fitted)) {
                    return Optional.of(dd);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The six operation codes declared as condition names on {@code LK-M03B-OPER PIC X(01)}
     * ({@code app/cbl/CBSTM03B.CBL:L102-L108}), and identically on the caller's side at
     * {@code app/cbl/CBSTM03A.CBL:L73-L79}.
     *
     * <p>All six are retained even though two are unreachable, because both are declared in the source on a
     * field the source really does inspect, and deleting them would misrepresent the contract this bean
     * carries. Retained deliberately; use {@link #supports(Dd, Operation)} to
     * discover which pairs are actually implemented.
     */
    public enum Operation {

        /**
         * {@code M03B-OPEN VALUE 'O'} ({@code app/cbl/CBSTM03B.CBL:L103}). Implemented by all four datasets,
         * always as {@code OPEN INPUT}.
         */
        OPEN('O'),

        /**
         * {@code M03B-CLOSE VALUE 'C'} ({@code app/cbl/CBSTM03B.CBL:L104}). Implemented by all four datasets.
         */
        CLOSE('C'),

        /**
         * {@code M03B-READ VALUE 'R'} ({@code app/cbl/CBSTM03B.CBL:L105}). Implemented by the two sequential
         * datasets only; a request for it against a random-access dataset performs no input or output.
         */
        READ('R'),

        /**
         * {@code M03B-READ-K VALUE 'K'} ({@code app/cbl/CBSTM03B.CBL:L106}). Implemented by the two
         * random-access datasets only.
         */
        READ_K('K'),

        /**
         * {@code M03B-WRITE VALUE 'W'} ({@code app/cbl/CBSTM03B.CBL:L107}).
         *
         * <p><strong>Implemented by no dataset.</strong> The condition name is declared and then referenced
         * nowhere in the program, because every dataset is opened {@code OPEN INPUT} and so cannot be written.
         * Retained as an intentional parity artefact and never given behaviour.
         */
        WRITE('W'),

        /**
         * {@code M03B-REWRITE VALUE 'Z'} ({@code app/cbl/CBSTM03B.CBL:L108}).
         *
         * <p><strong>Implemented by no dataset</strong>, for exactly the same reason as {@link #WRITE}.
         * Retained as an intentional parity artefact.
         */
        REWRITE('Z');

        /**
         * The single character the condition name tests for.
         */
        private final char code;

        /**
         * Binds an operation to its character code.
         *
         * @param code the single character from the {@code VALUE} clause
         */
        Operation(final char code) {
            this.code = code;
        }

        /**
         * Returns the single character code, the value of {@code LK-M03B-OPER} that selects this operation.
         *
         * @return one of {@code 'O'}, {@code 'C'}, {@code 'R'}, {@code 'K'}, {@code 'W'} or {@code 'Z'}
         */
        public char code() {
            return code;
        }
    }

    /**
     * A binding between one DD name and the records behind it: the Java form of {@code FILE-CONTROL.}
     * ({@code app/cbl/CBSTM03B.CBL:L30-L53}).
     *
     * <p>Contribute one implementation per DD to the application context and this service will dispatch to it.
     * The four verbs correspond exactly to the four I/O statements the source issues -
     * {@code OPEN INPUT}, {@code CLOSE}, sequential {@code READ} and keyed {@code READ} - and each reports the
     * outcome as a raw two character COBOL {@code FILE STATUS} rather than by throwing, because that is what
     * the four {@code FILE STATUS} clauses of {@code FILE-CONTROL.} deliver and what this bean must publish.
     *
     * <p>An implementation is required to honour its DD's access mode and is never asked to do otherwise: a
     * sequential binding receives {@link #openInput()}, {@link #readNext()} and {@link #close()} and never
     * {@link #readByKey(String)}, while a random-access binding receives {@link #readByKey(String)} and never
     * {@link #readNext()}. That guarantee is enforced by the 12 of 24 dispatch matrix, so the unused verb may
     * simply report an unsupported status.
     *
     * <p>Implementations must not log or serialise the record they return. It is unmasked personal data.
     */
    public interface Dataset {

        /**
         * Returns the DD this binding serves.
         *
         * @return the DD name this binding answers for. Must not be {@code null}
         */
        Dd dd();

        /**
         * Opens the dataset for input, carrying {@code OPEN INPUT}
         * ({@code app/cbl/CBSTM03B.CBL:L136}, L160, L184, L209). There is deliberately no output form.
         *
         * @return the resulting two character {@code FILE STATUS}, typically {@code "00"}. Must not be
         * {@code null} and must be exactly {@link FileStatus#STATUS_CODE_LENGTH} characters
         */
        String openInput();

        /**
         * Closes the dataset, carrying {@code CLOSE} ({@code app/cbl/CBSTM03B.CBL:L147}, L171, L196, L221).
         *
         * @return the resulting two character {@code FILE STATUS}. Must not be {@code null} and must be
         * exactly {@link FileStatus#STATUS_CODE_LENGTH} characters
         */
        String close();

        /**
         * Reads the next record in key order, carrying sequential {@code READ ... INTO LK-M03B-FLDT}
         * ({@code app/cbl/CBSTM03B.CBL:L141}, L165). Called only on a {@link AccessMode#SEQUENTIAL} binding.
         *
         * @return the status together with the record when one was returned, or
         * {@link DatasetRead#withoutRecord(String)} carrying {@code "10"} at end of file. Must not be
         * {@code null}
         */
        DatasetRead readNext();

        /**
         * Reads one record by key, carrying keyed {@code READ ... INTO LK-M03B-FLDT}
         * ({@code app/cbl/CBSTM03B.CBL:L190}, L215). Called only on a {@link AccessMode#RANDOM} binding.
         *
         * @param recordKey the record key, already extracted by reference modification and already validated
         * to be exactly the DD's key width, and all digits where the key is numeric. Never {@code null}
         * @return the status together with the record when one was found, or
         * {@link DatasetRead#withoutRecord(String)} carrying {@code "23"} when the key is absent. Must not be
         * {@code null}
         */
        DatasetRead readByKey(String recordKey);
    }

    /**
     * The outcome of one read against a dataset binding: a two character {@code FILE STATUS} and, when the
     * read returned one, the record.
     *
     * <p>The record is absent whenever no record was moved into the buffer - at end of file, or when a keyed
     * read finds no such key - because COBOL's {@code READ ... INTO} leaves the receiving field untouched in
     * those cases. Modelling the absence rather than substituting spaces is what lets this service leave the
     * caller's pre-set payload in place, exactly as the source does.
     *
     * @param status the two character {@code FILE STATUS}. Must not be {@code null}
     * @param record the record as fixed width text, or {@code null} when the read returned none. When present
     * it should be exactly the DD's {@link Dd#recordWidth()}; it is fitted to
     * {@link FileService#PAYLOAD_WIDTH} before publication and never trimmed
     */
    public record DatasetRead(String status, String record) {

        /**
         * Creates a result that carries a record.
         *
         * @param status the two character {@code FILE STATUS}, typically {@code "00"}. Must not be
         * {@code null}
         * @param record the record that was read. Must not be {@code null}
         * @return the result, never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public static DatasetRead of(final String status, final String record) {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(record, "record must not be null");
            return new DatasetRead(status, record);
        }

        /**
         * Creates a result that carries no record, for end of file or an absent key.
         *
         * @param status the two character {@code FILE STATUS}, typically {@code "10"} or {@code "23"}. Must
         * not be {@code null}
         * @return the result, never {@code null}
         * @throws NullPointerException if {@code status} is {@code null}
         */
        public static DatasetRead withoutRecord(final String status) {
            Objects.requireNonNull(status, "status must not be null");
            return new DatasetRead(status, null);
        }

        /**
         * Reports whether a record was returned and should therefore replace the payload.
         *
         * @return {@code true} when a record is present
         */
        public boolean hasRecord() {
            return record != null;
        }

        /**
         * Returns a representation that names the status but <strong>never</strong> the record.
         *
         * <p>A record's automatically generated {@code toString} would print every component, and this record
         * carries whole customer, account and cross-reference records. The override exists solely to make that
         * impossible; only the presence and the length of the record are disclosed.
         *
         * @return a redacted representation, never {@code null}
         */
        @Override
        public String toString() {
            return "DatasetRead[status=" + status
                    + ", record=" + (record == null ? "(none)" : "(redacted " + record.length() + " chars)")
                    + "]";
        }
    }

    /**
     * One populated shared area on its way in: the Java form of {@code LK-M03B-AREA}
     * ({@code app/cbl/CBSTM03B.CBL:L100-L112}) as a caller fills it before
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}.
     *
     * <p>Every text component is fitted to its picture clause on construction, so a request always describes a
     * shared area that could really have existed: the DD name is exactly 8 characters, the key exactly 25, the
     * pre-set return code exactly 2 and the pre-set payload exactly 1000.
     *
     * <p>The two pre-set components are what make defect B observable rather than merely described. The source
     * assigns the return code before every call - {@code MOVE ZERO TO WS-M03B-RC}
     * ({@code app/cbl/CBSTM03A.CBL:L349}) - and the subprogram leaves that value untouched when it does not
     * recognise the DD name. Carrying the pre-set explicitly is the only way a stateless Java call can
     * reproduce what the caller then observes.
     *
     * <p>The key length is deliberately unvalidated here. The source routinely leaves it at zero or stale for
     * an open, a close or a sequential read - {@code MOVE ZERO TO WS-M03B-KEY-LN}
     * ({@code app/cbl/CBSTM03A.CBL:L372}) precedes the {@code COMPUTE} that sets it - so rejecting a
     * non-positive length at construction would reject calls the source makes constantly. It is validated
     * where it is actually consumed, on the keyed read path.
     *
     * @param ddName the DD name, carrying {@code LK-M03B-DD PIC X(08)}. Must not be {@code null}; any
     * unrecognised value, blank included, takes the {@code WHEN OTHER} path
     * @param operation the operation, carrying {@code LK-M03B-OPER PIC X(01)}. Must not be {@code null}
     * @param key the key field, carrying {@code LK-M03B-KEY PIC X(25)}. Must not be {@code null}
     * @param keyLength the effective key length, carrying {@code LK-M03B-KEY-LN PIC S9(4)}. Consumed only by
     * the keyed read, where it must be between {@link FileService#MINIMUM_KEY_LENGTH} and
     * {@link FileService#KEY_WIDTH}
     * @param presetReturnCode the return code the caller assigned before the call, carrying
     * {@code LK-M03B-RC PIC X(02)}. Must not be {@code null}
     * @param presetPayload the payload the caller assigned before the call, carrying
     * {@code LK-M03B-FLDT PIC X(1000)}. Must not be {@code null}
     */
    public record FileServiceRequest(String ddName, Operation operation, String key, int keyLength,
            String presetReturnCode, String presetPayload) {

        /**
         * Fits every text component to its picture clause and rejects the nulls.
         */
        public FileServiceRequest {
            Objects.requireNonNull(ddName, "ddName must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(presetReturnCode, "presetReturnCode must not be null");
            Objects.requireNonNull(presetPayload, "presetPayload must not be null");
            ddName = padOrTruncate(ddName, DD_NAME_WIDTH);
            key = padOrTruncate(key, KEY_WIDTH);
            presetReturnCode = padOrTruncate(presetReturnCode, FileStatus.STATUS_CODE_LENGTH);
            presetPayload = padOrTruncate(presetPayload, PAYLOAD_WIDTH);
        }

        /**
         * Builds a keyless request against a known dataset, with the pre-sets the source always applies.
         *
         * <p>Suitable for an open, a close and a sequential read. The key is blank and its length zero, which
         * is exactly the state the source leaves them in at those sites.
         *
         * @param dd the dataset to act on. Must not be {@code null}
         * @param operation the operation to perform. Must not be {@code null}
         * @return the request, never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public static FileServiceRequest of(final Dd dd, final Operation operation) {
            Objects.requireNonNull(dd, "dd must not be null");
            return new FileServiceRequest(dd.ddName(), operation, "", 0, PRESET_RETURN_CODE, PRESET_PAYLOAD);
        }

        /**
         * Builds a keyed read request against a known dataset, with the pre-sets the source always applies.
         *
         * <p>Mirrors {@code 2000-CUSTFILE-GET} ({@code app/cbl/CBSTM03A.CBL:L369-L376}), which moves the
         * search field into the key, computes the length from that field, then zeroes the return code and
         * blanks the payload.
         *
         * @param dd the random-access dataset to read. Must not be {@code null}
         * @param key the key field. Must not be {@code null}
         * @param keyLength the effective key length
         * @return the request, never {@code null}
         * @throws NullPointerException if {@code dd} or {@code key} is {@code null}
         */
        public static FileServiceRequest keyed(final Dd dd, final String key, final int keyLength) {
            Objects.requireNonNull(dd, "dd must not be null");
            return new FileServiceRequest(dd.ddName(), Operation.READ_K, key, keyLength, PRESET_RETURN_CODE,
                    PRESET_PAYLOAD);
        }

        /**
         * Builds a request against a raw DD name, with the pre-sets the source always applies.
         *
         * <p>This is the only way to reach the {@code WHEN OTHER} path of
         * {@code app/cbl/CBSTM03B.CBL:L127}, and therefore the only way to observe defect B, since every other
         * factory takes a resolved dataset.
         *
         * @param ddName the raw DD name, recognised or not. Must not be {@code null}
         * @param operation the operation to perform. Must not be {@code null}
         * @param key the key field. Must not be {@code null}
         * @param keyLength the effective key length
         * @return the request, never {@code null}
         * @throws NullPointerException if {@code ddName}, {@code operation} or {@code key} is {@code null}
         */
        public static FileServiceRequest raw(final String ddName, final Operation operation, final String key,
                final int keyLength) {
            return new FileServiceRequest(ddName, operation, key, keyLength, PRESET_RETURN_CODE,
                    PRESET_PAYLOAD);
        }

        /**
         * Returns a representation that names the DD, the operation and the key <strong>length</strong>, but
         * never the key itself and never the payload.
         *
         * <p>The generated {@code toString} would print the key, which for a customer or account search is
         * personal data, and the whole pre-set payload. The override makes both impossible.
         *
         * @return a redacted representation, never {@code null}
         */
        @Override
        public String toString() {
            return "FileServiceRequest[ddName=" + ddName
                    + ", operation=" + operation
                    + ", keyLength=" + keyLength
                    + ", key=(redacted " + key.length() + " chars)"
                    + ", presetReturnCode=" + presetReturnCode
                    + ", presetPayload=(redacted " + presetPayload.length() + " chars)]";
        }
    }

    /**
     * One shared area on its way back out: the two fields {@code CBSTM03B} can have changed.
     *
     * <p>The return code carries {@code LK-M03B-RC PIC X(02)} and the payload carries
     * {@code LK-M03B-FLDT PIC X(1000)}. The payload is always exactly {@link FileService#PAYLOAD_WIDTH}
     * characters and is never trimmed, because its trailing spaces are part of the record geometry the parity
     * comparison depends on.
     *
     * <p>Interpreting the return code is the caller's job, and {@code FileStatusMapper} is where that job is
     * done. Do not compare it against {@code "04"} on a general path: that code is accepted at nine specific
     * sites in {@code app/cbl/CBSTM03A.CBL} and nowhere else in the corpus.
     *
     * @param returnCode the two character return code. Never {@code null}
     * @param payload the payload buffer, exactly {@link FileService#PAYLOAD_WIDTH} characters. Never
     * {@code null}
     */
    public record FileServiceResult(String returnCode, String payload) {

        /**
         * Rejects the nulls and fixes the widths.
         */
        public FileServiceResult {
            Objects.requireNonNull(returnCode, "returnCode must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
            returnCode = padOrTruncate(returnCode, FileStatus.STATUS_CODE_LENGTH);
            payload = padOrTruncate(payload, PAYLOAD_WIDTH);
        }

        /**
         * Returns the record portion of the payload for a dataset, that is the leading
         * {@link Dd#recordWidth()} characters, with the payload's trailing filler removed but the record's own
         * fixed-width padding intact.
         *
         * <p>The payload is a 1000 character buffer into which a record of 350, 50, 500 or 300 characters was
         * moved, so the bytes beyond the record width are buffer filler rather than data. This method draws
         * that boundary in one place, using the width the {@code FD} declares, so a caller never has to
         * hard-code it. Nothing is trimmed within the record itself.
         *
         * @param dd the dataset the payload was read from, whose {@code FD} fixes the record width. Must not
         * be {@code null}
         * @return the record, exactly {@link Dd#recordWidth()} characters. Never {@code null}
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public String record(final Dd dd) {
            Objects.requireNonNull(dd, "dd must not be null");
            return payload.substring(0, dd.recordWidth());
        }

        /**
         * Returns a representation that names the return code but <strong>never</strong> the payload.
         *
         * <p>The generated {@code toString} would print the whole payload, which may be an entire customer
         * record. The override makes that impossible; only the payload's length is disclosed.
         *
         * @return a redacted representation, never {@code null}
         */
        @Override
        public String toString() {
            return "FileServiceResult[returnCode=" + returnCode
                    + ", payload=(redacted " + payload.length() + " chars)]";
        }
    }

    /**
     * One entry of the DD-keyed dispatch table: everything
     * {@code PERFORM nnnn-PROC THRU nnnn-EXIT} executes for one dataset.
     *
     * <p>Takes the service as a parameter rather than capturing it, so the table can be built once into a
     * static field without the constructor ever publishing a partially initialised instance.
     */
    @FunctionalInterface
    private interface DatasetHandler {

        /**
         * Runs one dataset's operation body, then its status epilogue, then its terminator.
         *
         * @param service the service whose paragraph methods and status registers to use. Never {@code null}
         * @param area the shared area, mutated in place. Never {@code null}
         */
        void handle(FileService service, SharedArea area);
    }

    /**
     * The mutable shared area for the duration of exactly one call: the Java form of {@code LK-M03B-AREA} as
     * the subprogram sees it, a block the caller owns and the subprogram writes into.
     *
     * <p>Created per call and never retained, so this class holds no cross-call state. That is what keeps the
     * service free of global mutable state while still letting the fourteen paragraph methods communicate the
     * way the paragraphs they carry do - by writing into a shared block rather than by returning values.
     *
     * <p>It deliberately has no {@code toString}: the inherited one discloses nothing, whereas any field-wise
     * implementation would disclose the key and the payload.
     */
    private static final class SharedArea {

        /**
         * The raw DD name, exactly {@link FileService#DD_NAME_WIDTH} characters.
         */
        private final String ddName;

        /**
         * The requested operation.
         */
        private final Operation operation;

        /**
         * The key field, exactly {@link FileService#KEY_WIDTH} characters.
         */
        private final String key;

        /**
         * The effective key length, consumed only by the keyed read.
         */
        private final int keyLength;

        /**
         * The return code, initialised to the caller's pre-set and assigned only by a status epilogue. Left at
         * the pre-set on the {@code WHEN OTHER} path, which is defect B.
         */
        private String returnCode;

        /**
         * The payload, initialised to the caller's pre-set and replaced only when a read returns a record.
         */
        private String payload;

        /**
         * Populates the area from an incoming request, whose components are already fitted to their picture
         * clauses.
         *
         * @param request the request to copy. Never {@code null}
         */
        SharedArea(final FileServiceRequest request) {
            this.ddName = request.ddName();
            this.operation = request.operation();
            this.key = request.key();
            this.keyLength = request.keyLength();
            this.returnCode = request.presetReturnCode();
            this.payload = request.presetPayload();
        }

        /**
         * Returns the raw DD name for the dispatch to resolve.
         *
         * @return the DD name, never {@code null}
         */
        String ddName() {
            return ddName;
        }

        /**
         * Returns the requested operation.
         *
         * @return the operation, never {@code null}
         */
        Operation operation() {
            return operation;
        }

        /**
         * Returns the 25 character key field for reference modification.
         *
         * @return the key, never {@code null}
         */
        String key() {
            return key;
        }

        /**
         * Returns the effective key length.
         *
         * @return the length as supplied, unvalidated until the keyed read consumes it
         */
        int keyLength() {
            return keyLength;
        }

        /**
         * Returns the current return code.
         *
         * @return the return code, never {@code null}
         */
        String returnCode() {
            return returnCode;
        }

        /**
         * Returns the current payload.
         *
         * @return the payload, never {@code null}
         */
        String payload() {
            return payload;
        }

        /**
         * Assigns the return code, carrying {@code MOVE <file>-STATUS TO LK-M03B-RC}
         * ({@code app/cbl/CBSTM03B.CBL:L152}, L176, L201, L226).
         *
         * @param status the status to publish. Never {@code null}
         */
        void publishReturnCode(final String status) {
            this.returnCode = status;
        }

        /**
         * Assigns the payload, carrying the {@code INTO LK-M03B-FLDT} phrase of a successful read.
         *
         * @param record the record, already fitted to {@link FileService#PAYLOAD_WIDTH}. Never {@code null}
         */
        void publishPayload(final String record) {
            this.payload = record;
        }
    }
}
