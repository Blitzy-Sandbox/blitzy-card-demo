/*
 * ******************************************************************
 * Program     : FileAccessException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of the FILE STATUS '9x' family, carrying the four-character expanded status.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144 @ 7756d89 - IO-STATUS, TWO-BYTES-BINARY
 *               and its TWO-BYTES-ALPHA redefinition, IO-STATUS-04 and APPL-RESULT:
 *               the declarations that fix the rendered width at four characters.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89 - 9910-DISPLAY-IO-STATUS, the
 *               two branch four character renderer whose output this type carries.
 * Source      : app/cbl/CBTRN02C.cbl:L236-L252 @ 7756d89 - the universal I/O guard
 *               idiom, which renders the status and only then abends, and which is the
 *               site this type is raised from.
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
package com.cardemo.exception;

import com.cardemo.model.enums.FileStatus;

/**
 * Signals a physical or logical I/O failure - the COBOL {@code FILE STATUS} {@code '9x'} family - and
 * carries the four character expanded status that the legacy operator read off the job log.
 *
 * <p><strong>What it does.</strong> In the frozen corpus a failing I/O verb does not return a value to a
 * caller. It renders a diagnostic and terminates the run. This type is the Java form of the diagnostic
 * half of that pair: it names the failure, identifies the file and the operation that provoked it, and
 * transports the exact four characters the legacy renderer produced so that the logging layer can emit a
 * line which matches the legacy baseline byte for byte. It is the only member of the hierarchy that
 * carries a rendered status, because {@code '9x'} is the only family whose second byte is a binary
 * subcode rather than a displayable digit.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line or line range
 * in the frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the three
 * {@code Source} lines in the file header record. The anchor is stated once here rather than repeated on
 * every citation. Filename case is reproduced as it appears on disk: in {@code app/cbl} only
 * {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} carry an uppercase extension, so {@code CBTRN02C.cbl} is
 * cited in lowercase throughout. Every count quoted below was measured against the corpus rather than
 * inherited from prose.
 *
 * <h2>The declarations that fix the width at four characters</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L131-L144} declares the whole mechanism:
 *
 * <pre>
 * 01  IO-STATUS.                                     &lt;- L131
 *     05  IO-STAT1            PIC X.                 &lt;- L132
 *     05  IO-STAT2            PIC X.                 &lt;- L133
 * 01  TWO-BYTES-BINARY        PIC 9(4) BINARY.       &lt;- L134
 * 01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.
 *     05  TWO-BYTES-LEFT      PIC X.                 &lt;- L136
 *     05  TWO-BYTES-RIGHT     PIC X.                 &lt;- L137
 * 01  IO-STATUS-04.                                  &lt;- L138
 *     05  IO-STATUS-0401      PIC 9   VALUE 0.       &lt;- L139
 *     05  IO-STATUS-0403      PIC 999 VALUE 0.       &lt;- L140
 * 01  APPL-RESULT             PIC S9(9)   COMP.      &lt;- L142
 *     88  APPL-AOK            VALUE 0.               &lt;- L143
 *     88  APPL-EOF            VALUE 16.              &lt;- L144
 * </pre>
 *
 * <p>{@code IO-STATUS-04} is a group of {@code PIC 9} followed by {@code PIC 999}, so it is
 * <strong>exactly four characters wide</strong>. That width is the contract this type transports; it is
 * neither a formatting preference nor a rounded figure. Two further details of these declarations matter
 * and are easy to misread. {@code APPL-EOF} is {@code VALUE 16} - sixteen, not twelve - so end of file is
 * not signalled by the same value the guard moves on failure. And {@code TWO-BYTES-ALPHA} is a
 * <em>redefinition</em> of a binary halfword, which is the entire reason the second status byte can be
 * turned into a number at all.
 *
 * <h2>The two branch renderer</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L714-L727}, verbatim:
 *
 * <pre>
 * 9910-DISPLAY-IO-STATUS.                                    &lt;- L714
 *     IF  IO-STATUS NOT NUMERIC                              &lt;- L715
 *     OR  IO-STAT1 = '9'                                     &lt;- L716
 *         MOVE IO-STAT1 TO IO-STATUS-04(1:1)                 &lt;- L717
 *         MOVE 0        TO TWO-BYTES-BINARY                  &lt;- L718
 *         MOVE IO-STAT2 TO TWO-BYTES-RIGHT                   &lt;- L719
 *         MOVE TWO-BYTES-BINARY TO IO-STATUS-0403            &lt;- L720
 *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04        &lt;- L721
 *     ELSE                                                   &lt;- L722
 *         MOVE '0000' TO IO-STATUS-04                        &lt;- L723
 *         MOVE IO-STATUS TO IO-STATUS-04(3:2)                &lt;- L724
 *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04        &lt;- L725
 *     END-IF                                                 &lt;- L726
 *     EXIT.                                                  &lt;- L727
 * </pre>
 *
 * <p><strong>Branch A</strong>, the branch this family takes, is selected when the two character status
 * is not numeric <em>or</em> its first byte is {@code '9'}. The predicate is an inclusive or, so a wholly
 * numeric {@code '9x'} pair still takes it. Character 1 is the first status byte copied straight through.
 * Characters 2 to 4 are the second status byte expanded to three decimal digits: {@code TWO-BYTES-BINARY}
 * is zeroed, the raw byte is moved into {@code TWO-BYTES-RIGHT} - the <em>low order</em> byte of the
 * redefined halfword - and the resulting unsigned value, necessarily 0 to 255, is rendered by
 * {@code PIC 999} in exactly three digits.
 *
 * <p><strong>Branch B</strong> is selected otherwise. The field is set to {@code '0000'} and the two
 * status characters are overlaid at positions 3 and 4, so {@code '23'} renders as {@code 0023}.
 *
 * <p>Two implementation details of the Java form of branch A are load bearing, and both live in
 * {@code FileStatus} rather than here, as the ownership section below explains. The expansion masks the
 * character to its low order byte with {@code 0xFF}, because a Java {@code char} widened to {@code int}
 * without masking would sign extend and yield a negative number for any byte above {@code 0x7F}, which
 * would render wrongly. And the formatting call passes {@code Locale.ROOT} explicitly, because a default
 * locale can supply non ASCII digits for a three digit conversion, which would silently break the byte
 * comparison on a developer machine while passing on a build server.
 *
 * <h2>The preserved quirk - do not repair this</h2>
 *
 * <p>{@code 'FILE STATUS IS: NNNN'} is a fixed twenty character literal, and the {@code NNNN} inside it
 * is <strong>not a substitution placeholder</strong>. COBOL's {@code DISPLAY literal identifier}
 * concatenates its operands with no separator whatsoever, so at both {@code app/cbl/CBTRN02C.cbl:L721}
 * and {@code app/cbl/CBTRN02C.cbl:L725} the emitted line is the literal followed immediately by the four
 * rendered characters. The original author left the placeholder text in the message while also appending
 * the real value. Worked examples:
 *
 * <pre>
 * status '23'         branch B      FILE STATUS IS: NNNN0023
 * status '9' + X'01'  branch A      FILE STATUS IS: NNNN9001
 * status '10'         branch B      FILE STATUS IS: NNNN0010
 * </pre>
 *
 * <p>The line for status {@code '23'} is therefore {@code FILE STATUS IS: NNNN0023} and <em>not</em>
 * {@code FILE STATUS IS: 0023}. Do not substitute the digits into the placeholder, do not delete the
 * placeholder, do not insert a space, a colon or any other separator, and do not otherwise reformat the
 * line. The end to end parity gate compares log output byte for byte against the legacy baseline, so each
 * of those edits is a diff that will look like a Java defect and will not be one.
 * <strong>Severity of reformatting: Blocker.</strong>
 *
 * <p>The obligation extends past this class. {@code logback-spring.xml} must pass the assembled line
 * through unchanged: no pattern that trims or collapses whitespace, no structured logging field whose
 * name repeats the {@code FILE STATUS} wording, and no masking rule that matches the four rendered
 * characters. Masking exists to remove credentials and personally identifiable values, and a rendered
 * status is neither.
 *
 * <h2>Ownership - four concerns, no duplication</h2>
 *
 * <p>The mechanism this type belongs to is deliberately split, and the split is what keeps the byte exact
 * text in one place:
 *
 * <ul>
 *   <li><strong>The twenty character prefix literal is owned by {@code FileStatus}</strong>, as its
 *       {@code DISPLAY_MESSAGE_PREFIX} constant. This class does not redeclare it, and it appears above
 *       only as documentation prose. Two declarations of that literal would mean two places to get the
 *       parity gate wrong. <em>Severity of duplicating it: High.</em></li>
 *   <li><strong>The two branch rendering is owned by {@code FileStatus} too</strong>, as its
 *       {@code renderIoStatus04(String)} method, which is where the {@code 0xFF} mask and the
 *       {@code Locale.ROOT} formatting call described above actually live. This class calls that method
 *       and deliberately contains <em>no</em> second implementation of the two branches - which is why a
 *       reader will find no {@code String.format} here and should not add one.
 *       <em>Severity of implementing it twice: Medium.</em></li>
 *   <li><strong>The mapping decision is owned by {@code FileStatusMapper}</strong>. Choosing which member
 *       of the hierarchy a given status becomes is a policy question with named exceptions, and it is
 *       settled once, centrally, rather than at each of the call sites.</li>
 *   <li><strong>Carrying the rendered value is owned here</strong>, and <strong>emitting it is owned by
 *       the logging layer</strong>. This type performs no logging at all: the legacy renderer was a
 *       {@code DISPLAY}, and a logger invoked from a constructor would both duplicate whatever the
 *       eventual handler logs and put a side effect where there should be none.</li>
 * </ul>
 *
 * <p>Because the expansion is obtained from that single owning method, whose documented contract is that
 * it is total, never throws, and always returns exactly four characters, {@link #getExpandedStatus()} is
 * guaranteed non null and exactly four characters long on every construction path, including the paths
 * that supply no status at all. That guarantee is structural rather than asserted, which is why this
 * class contains no defensive length check: such a check could never fail, and an unreachable branch is
 * exactly the dead code the standards forbid. Malformed input is reshaped by the owning method using the
 * rule the receiving COBOL field itself applies - a {@code MOVE} into {@code PIC X(02)} left justifies,
 * pads right with spaces when the sender is short and truncates on the right when it is long - so a
 * {@code null}, a one character or a three character status is normalised rather than rejected.
 *
 * <h2>Where this type is raised, and where it stops</h2>
 *
 * <p>The corpus wraps every I/O verb in one idiom. {@code app/cbl/CBTRN02C.cbl:L236-L252}, verbatim:
 *
 * <pre>
 * 0000-DALYTRAN-OPEN.
 *     MOVE 8 TO APPL-RESULT.                         &lt;- L237
 *     OPEN INPUT DALYTRAN-FILE                       &lt;- L238
 *     IF  DALYTRAN-STATUS = '00'                     &lt;- L239
 *         MOVE 0 TO APPL-RESULT                      &lt;- L240
 *     ELSE
 *         MOVE 12 TO APPL-RESULT                     &lt;- L242
 *     END-IF
 *     IF  APPL-AOK                                   &lt;- L244
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR OPENING DALYTRAN'           &lt;- L247
 *         MOVE DALYTRAN-STATUS TO IO-STATUS          &lt;- L248
 *         PERFORM 9910-DISPLAY-IO-STATUS             &lt;- L249
 *         PERFORM 9999-ABEND-PROGRAM                 &lt;- L250
 *     END-IF
 *     EXIT.
 * </pre>
 *
 * <p>That idiom is replicated at <strong>eighteen</strong> guard sites in this one program. The
 * {@code IF APPL-AOK} tests stand at lines 244, 262, 281, 299, 317, 335, 357, 457, 486, 517, 535, 571,
 * 590, 608, 627, 645, 663 and 682, and the eighteen matching {@code PERFORM 9910-DISPLAY-IO-STATUS}
 * calls at lines 249, 267, 286, 304, 322, 340, 365, 462, 491, 522, 540, 576, 595, 613, 632, 650, 668 and
 * 687. Eighteen is a measured count; a figure of nineteen appears in project prose and is not what the
 * corpus contains.
 *
 * <p><strong>Note the ordering, because it defines the division of labour.</strong> The guard renders the
 * status first at L249 and only then abends at L250. This type corresponds to the render step: it is
 * raised where the source rendered a {@code '9x'} status, and it carries the rendering forward. The abend
 * step is a separate contract belonging to {@link FatalProcessingException}, which reproduces
 * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711} where {@code 999} is moved into
 * {@code ABCODE} before {@code CALL 'CEE3ABD'}, together with the process return code of 12 that
 * {@code MOVE 12 TO APPL-RESULT} at L242 anticipates. So the boundary is: a {@code '9x'} status becomes
 * <em>this</em> type; a status that is unexpected altogether becomes {@link FatalProcessingException}.
 *
 * <h2>The status to exception map</h2>
 *
 * <p>This type owns exactly one row of it:
 *
 * <ul>
 *   <li>{@code '00'} success, and {@code '04'} secondary success - <strong>continue</strong>, not a
 *       failure. The secondary value is accepted alongside {@code '00'} at nine call sites in
 *       {@code app/cbl/CBSTM03A.CBL}, namely lines 736, 748, 771, 789, 807, 862, 879, 895 and 911.</li>
 *   <li>{@code '10'} end of file - <strong>loop termination, never a throw.</strong> Nothing in this
 *       hierarchy is raised for it. A batch step that ends normally on exhausted input must not surface
 *       an exception, and mapping this value to one would abort every job at its natural end.</li>
 *   <li>{@code '22'} duplicate key - {@link DuplicateRecordException}.</li>
 *   <li>{@code '23'} record not found - {@link RecordNotFoundException}, except at the scoped sites where
 *       the corpus treats it as an accepted control path rather than an error.</li>
 *   <li>{@code '35'} file unavailable - {@link FileUnavailableException}.</li>
 *   <li><strong>{@code '9x'} physical or logical I/O error - this type, carrying the four character
 *       expanded status.</strong></li>
 *   <li>anything else - {@link FatalProcessingException}, abend 999, return code 12.</li>
 * </ul>
 *
 * <p><strong>On the evidence for the {@code '9x'} row.</strong> The guard quoted above never compares
 * against a literal {@code '9x'} value; it is binary, testing {@code = '00'} against everything else at
 * L239 to L242, and no literal {@code '9x'} pair occurs anywhere in the corpus. What <em>is</em> directly
 * attested is the renderer's own explicit {@code IO-STAT1 = '9'} test at
 * {@code app/cbl/CBTRN02C.cbl:L716} - the source itself singles out a leading {@code '9'} for special
 * rendering, which is precisely why this family earns a distinct type. That attestation is direct and
 * sufficient, and the absence of a literal {@code '9x'} comparison is recorded here rather than papered
 * over. <em>Severity: Low.</em>
 *
 * <h2>What this type must never carry</h2>
 *
 * <p>It carries the <strong>expanded status, the logical file name and the attempted operation</strong>.
 * It must never carry the record image or the key value that was being read or written when the failure
 * occurred. That prohibition is specific rather than decorative: the buffer in question is a 350 byte
 * transaction record or a 500 byte customer record, and the customer layout at
 * {@code app/cpy/CVCUS01Y.cpy:L15-L20} holds two telephone numbers, a social security number, a
 * government issued identifier, a date of birth and an electronic funds account identifier, while
 * {@code app/cpy/CSUSR01Y.cpy} holds a password field and card numbers are sixteen characters wide.
 * Exception messages are logged, so attaching the buffer would publish the entire personally identifiable
 * inventory into the log stream. <strong>Severity of attaching the buffer: Blocker.</strong> No secret,
 * credential, token, signing key, password, password hash or personally identifiable value belongs in the
 * message, in any field of this type, or in any example in this documentation. Identify the failing file
 * by its logical name; never by its content.
 *
 * <p><strong>A four digit collision to be aware of.</strong> The expanded status carried here is four
 * characters, and the batch reject reason is also a four digit field -
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181}. The two are unrelated
 * values from different domains and must never be conflated, stored in the same field, or compared. A
 * reject reason is a business outcome: the corpus moves it into that working storage field, writes it into
 * the eighty byte trailer of a 430 byte reject record declared at {@code app/cbl/CBTRN02C.cbl:L176-L178}
 * as {@code PIC X(350)} plus {@code PIC X(80)}, and lets it drive the exit status through
 * {@code IF WS-REJECT-COUNT &gt; 0} / {@code MOVE 4 TO RETURN-CODE} at
 * {@code app/cbl/CBTRN02C.cbl:L229-L231}. It is never raised as a failure, and consequently no reject
 * reason appears anywhere in this hierarchy. An expanded status, by contrast, is a diagnostic and is
 * exactly what this type exists to transport.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The emitted line reads {@code FILE STATUS IS: 9001} rather than
 *       {@code FILE STATUS IS: NNNN9001}.</em> Something substituted the rendered value into the
 *       placeholder instead of appending after it. Assemble the line as the owning prefix constant
 *       immediately followed by {@link #getExpandedStatus()}, with nothing between them.</li>
 *   <li><em>The three digit expansion is negative or absurdly large.</em> A second, local implementation
 *       of branch A omitted the {@code 0xFF} mask and sign extended a byte above {@code 0x7F}. Delete the
 *       local copy and call the owning method; there must be exactly one implementation in the tree.</li>
 *   <li><em>The three digits are not ASCII.</em> A formatting call omitted {@code Locale.ROOT} and picked
 *       up a default locale with its own digit shapes. Same remedy.</li>
 *   <li><em>{@link #getExpandedStatus()} returns {@code " 032"} unexpectedly.</em> The instance was built
 *       through a constructor that supplies no status, or with a {@code null} status. Two spaces are what
 *       an uninitialised {@code IO-STATUS} holds, and a space is 32, so that value is the faithful
 *       rendering of "no status was recorded". Supply the status if one is available.</li>
 *   <li><em>A batch job now aborts at the end of its input.</em> An end of file status was mapped to a
 *       throw. {@code '10'} is loop termination; see the map above.</li>
 *   <li><em>{@link Throwable#getCause()} is {@code null} after wrapping a driver failure.</em> A
 *       constructor without a cause parameter was used inside a {@code catch}. Switch to a cause carrying
 *       constructor; the underlying failure is the only actionable diagnostic and this type never drops
 *       one it was handed.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> {@code serialVersionUID} was removed.
 *       {@link Throwable} is already serializable, and the build compiles with {@code -Xlint:all},
 *       {@code -Werror} and {@code failOnWarning}, so the declaration is mandatory.</li>
 * </ul>
 *
 * <h2>Dependency direction and testing</h2>
 *
 * <p>This class depends on {@code java.lang} and on one CardDemo model type, {@code FileStatus}. That
 * direction - from {@code com.cardemo.exception} towards {@code com.cardemo.model} - is the sanctioned
 * one, and it is safe because the enum package references no CardDemo package at all, so no cycle is
 * possible. No framework type, no third party library and no JDK I/O type is involved: a genuine
 * {@code java.io} failure arrives only as a cause, typed as {@link Throwable}.
 *
 * <p>Instances are immutable, hold no static state, and depend on no clock, locale, charset or time zone,
 * so behaviour is deterministic and unit testable. Tests live under
 * {@code src/test/java/com/cardemo/unit} and not in this package.
 *
 * @see CardDemoException
 * @see FatalProcessingException
 */
public class FileAccessException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} is already serializable, so every type in this
     * hierarchy is unavoidably serializable and must pin this value explicitly: the build runs
     * {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, which turns the {@code serial}
     * lint into a compilation failure. A fixed literal is used rather than a computed default so that the
     * identity does not shift when the class is edited. No custom serialization hook is declared here and
     * none may be added; the default mechanism inherited from {@link Throwable} is sufficient, and no
     * instance of this type is ever reconstructed from untrusted input.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The four character expanded status, obtained once at construction from the single owning renderer.
     *
     * <p>Never {@code null} and always exactly four characters, on every construction path, because that
     * is the documented contract of the method which produces it. Rendered once here rather than on each
     * accessor call, so reading it is free.
     */
    private final String expandedStatus;

    /**
     * The logical file or DD name whose I/O failed, for example {@code DALYTRAN}, {@code ACCTDAT},
     * {@code TRANSACT} or {@code STMTFILE}. May be {@code null} when the caller did not identify it.
     *
     * <p>This is an identity, not content. It is safe to log, which is exactly why it is the thing
     * carried in place of the record buffer.
     */
    private final String logicalFileName;

    /**
     * The attempted operation, for example {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE},
     * {@code DELETE} or {@code CLOSE}. May be {@code null} when the caller did not identify it.
     *
     * <p>The corpus does not distinguish the verbs by status - one guard shape covers all of them - so the
     * verb is recorded as data on a single type rather than encoded in a family of per verb types.
     */
    private final String operation;

    /**
     * Creates an I/O failure with a message only, recording no status, file name, operation or cause.
     *
     * <p>Use the richest constructor the call site can populate; this form exists for the paths that
     * genuinely have nothing more to report. Inside a {@code catch} block use
     * {@link #FileAccessException(String, Throwable)} or
     * {@link #FileAccessException(String, String, String, String, Throwable)} instead, because discarding
     * a caught throwable is the swallowing of context the standards forbid.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged and no metric is recorded.
     *
     * <p>Error modes: none. {@link #getExpandedStatus()} still returns a valid four character value,
     * {@code " 032"}, being the faithful rendering of an uninitialised two byte status field.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted to
     *                be {@code null} or blank and passed through unchanged. It must not contain a secret,
     *                a credential, a personally identifiable value or a record image, because exception
     *                messages are logged
     */
    public FileAccessException(String message) {
        this(message, null, null, null, null);
    }

    /**
     * Creates an I/O failure with a message and the underlying throwable that caused it.
     *
     * <p>This is the minimum acceptable form inside a {@code catch} block. The cause is delegated to
     * {@link CardDemoException} and therefore to {@link RuntimeException} unchanged, so the root failure -
     * ordinarily a driver or JDK I/O exception, and the only genuinely actionable diagnostic - remains
     * fully navigable through {@link Throwable#getCause()}.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * rethrown nor logged.
     *
     * <p>Error modes: none. A {@code null} cause is accepted and records that there is no underlying
     * failure to attribute.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted to
     *                be {@code null} or blank and passed through unchanged, subject to the same
     *                prohibition on secrets, personally identifiable values and record images
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                Permitted to be {@code null}. When non {@code null} it is always retained
     */
    public FileAccessException(String message, Throwable cause) {
        this(message, null, null, null, cause);
    }

    /**
     * Creates an I/O failure that records the raw status, the file and the operation, with no cause.
     *
     * <p>Use this form where the failure is reported by a status value rather than by a thrown throwable,
     * which is the shape the corpus itself has: the guard inspects a status and no exception object
     * exists. Where a throwable <em>is</em> available, prefer
     * {@link #FileAccessException(String, String, String, String, Throwable)}.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * <p>Error modes: none - this constructor cannot fail. The status is expanded by the single owning
     * renderer, whose contract is total: a {@code null}, empty, short or over long value is normalised
     * exactly as a COBOL {@code MOVE} into a two character alphanumeric field would normalise it, rather
     * than rejected. Nothing is validated by throwing, because a diagnostic path must not itself fail and
     * destroy the context it exists to report.
     *
     * @param message         the detail message, subject to the prohibition on secrets, personally
     *                        identifiable values and record images. Permitted to be {@code null} or blank
     * @param ioStatus        the raw two character file status, for example {@code "90"}. Permitted to be
     *                        {@code null}, shorter or longer, and normalised rather than rejected
     * @param logicalFileName the logical file or DD name that failed, for example {@code "DALYTRAN"}.
     *                        Permitted to be {@code null}. Pass an identity, never a record image or key
     *                        value
     * @param operation       the attempted operation, for example {@code "OPEN"}. Permitted to be
     *                        {@code null}
     */
    public FileAccessException(String message, String ioStatus, String logicalFileName, String operation) {
        this(message, ioStatus, logicalFileName, operation, null);
    }

    /**
     * Creates an I/O failure that records the raw status, the file, the operation and the cause.
     *
     * <p>This is the canonical constructor; every other constructor on this class delegates to it, so the
     * fields are assigned in exactly one place. It is the preferred form at any site that has both a
     * status and a throwable, because it loses neither.
     *
     * <p>The raw status is expanded exactly once, here, by the single owning renderer, and the result is
     * stored. Concatenating the owning twenty character prefix constant with the stored value reproduces
     * the legacy line byte for byte - see the class documentation for the worked examples and for the
     * placeholder quirk that makes the line for status {@code '23'} read {@code FILE STATUS IS: NNNN0023}.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded, no
     * file handle, channel or path is retained, and no state outside this instance is read or written.
     *
     * <p>Error modes: none - this constructor cannot fail, for the reasons given on
     * {@link #FileAccessException(String, String, String, String)}.
     *
     * @param message         the detail message, subject to the prohibition on secrets, personally
     *                        identifiable values and record images. Permitted to be {@code null} or blank
     * @param ioStatus        the raw two character file status, for example {@code "90"}. Permitted to be
     *                        {@code null}, shorter or longer, and normalised rather than rejected
     * @param logicalFileName the logical file or DD name that failed, for example {@code "DALYTRAN"}.
     *                        Permitted to be {@code null}. Pass an identity, never a record image or key
     *                        value
     * @param operation       the attempted operation, for example {@code "OPEN"}. Permitted to be
     *                        {@code null}
     * @param cause           the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                        Permitted to be {@code null}. When non {@code null} it is always retained
     */
    public FileAccessException(String message, String ioStatus, String logicalFileName, String operation,
            Throwable cause) {
        super(message, cause);
        this.expandedStatus = FileStatus.renderIoStatus04(ioStatus);
        this.logicalFileName = logicalFileName;
        this.operation = operation;
    }

    /**
     * Returns the four character expanded status this failure carries.
     *
     * <p>To reproduce the legacy diagnostic line, concatenate the twenty character prefix constant owned
     * by {@code FileStatus} with this value and place nothing whatsoever between them. Do not substitute
     * this value into the {@code NNNN} text inside that prefix; the placeholder stays and the value is
     * appended after it, which is what the corpus does at {@code app/cbl/CBTRN02C.cbl:L721} and
     * {@code app/cbl/CBTRN02C.cbl:L725}.
     *
     * <p>Side effects: none. The value was rendered once at construction and is returned as stored, so no
     * formatting occurs here and repeated calls are free and identical.
     *
     * @return the expanded status, never {@code null} and always exactly four characters long. For an
     *         instance built without a status this is {@code " 032"}, the faithful rendering of an
     *         uninitialised two byte status field
     */
    public String getExpandedStatus() {
        return expandedStatus;
    }

    /**
     * Returns the logical file or DD name whose I/O failed.
     *
     * <p>Side effects: none.
     *
     * @return the logical file name, for example {@code "DALYTRAN"} or {@code "ACCTDAT"}, or {@code null}
     *         when the throwing site did not identify one. Callers that render this into a message must
     *         handle {@code null} explicitly rather than relying on a default rendering
     */
    public String getLogicalFileName() {
        return logicalFileName;
    }

    /**
     * Returns the operation that was attempted when the I/O failed.
     *
     * <p>Side effects: none.
     *
     * @return the operation, for example {@code "OPEN"} or {@code "READ"}, or {@code null} when the
     *         throwing site did not identify one. Callers must handle {@code null} explicitly
     */
    public String getOperation() {
        return operation;
    }
}
