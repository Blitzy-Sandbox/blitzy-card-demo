/*
 * ******************************************************************
 * Program     : CardDemoException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception base class
 * Function    : Root of the typed hierarchy replacing COBOL FILE STATUS and CICS response-code branching.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L236-L252 @ 7756d89 - the universal
 *               I/O guard idiom, and the abend exit it falls into, that this
 *               hierarchy replaces.
 * Source      : app/cpy/CSMSG02Y.cpy @ 7756d89 - the abend work areas carried by
 *               the fatal subtype of this hierarchy.
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

/**
 * Root of the CardDemo typed exception hierarchy.
 *
 * <p><strong>What it does.</strong> It replaces the two failure-signalling mechanisms of the frozen
 * legacy corpus with one Java construct: the {@code FILE STATUS} guard that every batch program wraps
 * around every I/O verb, and the {@code EXEC CICS} response-code branching that every online program
 * wraps around every file request. Neither legacy mechanism carries a failure value up a call chain;
 * each aborts the unit of work at the point of detection. This class, and the eight subtypes that
 * extend it, give that abort a type, a message and a preserved root cause.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line range in
 * the frozen corpus, and <em>all</em> of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the two
 * {@code Source} lines in the file header record. The anchor is stated once here rather than repeated
 * on each citation. Filename case is reproduced as it appears on disk, which matters because the
 * corpus is not uniform: in {@code app/cbl} only two members use an uppercase extension and in
 * {@code app/cpy} only one does, so every path cited here is lowercase because every file cited here
 * is lowercase. Each figure quoted is one this file's author measured rather than inherited.
 *
 * <p><strong>Why it is unchecked.</strong> The legacy guard does not propagate a checked failure to a
 * caller - it displays a diagnostic and terminates the run through
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} {@code 9999-ABEND-PROGRAM}, which moves {@code 999} into
 * {@code ABCODE} and issues {@code CALL 'CEE3ABD'}. Extending {@link RuntimeException} reproduces that
 * shape faithfully: a failure unwinds the current unit of work without a {@code throws} clause being
 * threaded through every repository, service and batch signature it passes. A parallel checked
 * hierarchy would add ceremony that has no counterpart in the source and would tempt callers into the
 * empty {@code catch} block that Rule 1 Clause B forbids.
 *
 * <h2>The one idiom this hierarchy replaces</h2>
 *
 * <p>The batch corpus does not contain hundreds of individual status checks. It contains <em>one</em>
 * idiom, stamped out mechanically. Its work areas are declared at
 * {@code app/cbl/CBTRN02C.cbl:L131-L144}: a two byte {@code IO-STATUS} split into {@code IO-STAT1} and
 * {@code IO-STAT2}, a {@code TWO-BYTES-BINARY} redefined as {@code TWO-BYTES-ALPHA} for the expansion
 * of a non numeric status, a four character {@code IO-STATUS-04} render target, and a signed binary
 * {@code APPL-RESULT} carrying the two condition names {@code APPL-AOK VALUE 0} (L143) and
 * {@code APPL-EOF VALUE 16} (L144). The end of file condition name is <em>sixteen</em>, not twelve;
 * the values {@code 8} and {@code 12} that the idiom moves into {@code APPL-RESULT} are working values
 * and are deliberately distinct from those two condition names.
 *
 * <p>The idiom itself, verbatim from {@code app/cbl/CBTRN02C.cbl:L236-L252}:
 *
 * <pre>
 * 0000-DALYTRAN-OPEN.
 *     MOVE 8 TO APPL-RESULT.
 *     OPEN INPUT DALYTRAN-FILE
 *     IF  DALYTRAN-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT
 *     END-IF
 *     IF  APPL-AOK
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR OPENING DALYTRAN'
 *         MOVE DALYTRAN-STATUS TO IO-STATUS
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM
 *     END-IF
 *     EXIT.
 * </pre>
 *
 * <p>That block is replicated with only the file name and the verb changed. Measured on the anchor
 * commit with {@code grep -c 'APPL-AOK' app/cbl/CBTRN02C.cbl}, discounting the L143 declaration, it is
 * evaluated at <strong>18 sites in this one program</strong> - L244, L262, L281, L299, L317, L335,
 * L357, L457, L486, L517, L535, L571, L590, L608, L627, L645, L663 and L682 - covering every open,
 * read, write, rewrite and close it performs.
 *
 * <p><strong>Recognising it as one idiom rather than 18 unrelated checks is the whole design
 * argument for this package.</strong> One idiom warrants one central translation, not a bespoke
 * failure path per call site.
 *
 * <h2>Architectural boundary - what belongs here and what does not</h2>
 *
 * <p>The translation is split across three collaborators, and the split is deliberate:
 *
 * <ul>
 *   <li>{@code com.cardemo.model.enums.FileStatus} <em>classifies</em> a raw two character status
 *       value and owns its four character rendering, including the
 *       {@code 'FILE STATUS IS: NNNN'} diagnostic literal of
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727} {@code 9910-DISPLAY-IO-STATUS}.</li>
 *   <li>{@code com.cardemo.service.shared.FileStatusMapper} <em>decides</em> which failure a given
 *       status represents, including the sites where a not found status is an accepted control path
 *       rather than an error.</li>
 *   <li><strong>This package supplies only the typed targets of that decision.</strong></li>
 * </ul>
 *
 * <p>No mapping logic, no status parsing, no rendering and no severity table may be added to this
 * class or to any of its subtypes. Duplicating the four character rendering or the diagnostic literal
 * here would create a second source of truth for a value that must be byte identical to the legacy
 * output, and Rule 1 Clause C forbids that duplication.
 *
 * <h2>The nine types</h2>
 *
 * <p>This class is the base; the eight subtypes are the vocabulary. Each subtype exists because it is
 * separately distinguishable at a call site, which is what makes a caller's structured log and HTTP
 * response meaningful:
 *
 * <ul>
 *   <li>{@code ValidationException} - a field or request level rejection, carrying the per field error
 *       markers that the {@code app/cpy/CSSETATY.cpy} template produced by
 *       {@code COPY ... REPLACING}.</li>
 *   <li>{@code RecordNotFoundException} - a keyed read found nothing, the error reading of
 *       {@code FILE STATUS '23'} and of {@code DFHRESP(NOTFND)}.</li>
 *   <li>{@code DuplicateRecordException} - a keyed write collided, the reading of
 *       {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)}.</li>
 *   <li>{@code FileUnavailableException} - the underlying store was not open or not reachable, the
 *       reading of {@code FILE STATUS '35'} and of {@code DFHRESP(NOTOPEN)}.</li>
 *   <li>{@code ConcurrentUpdateException} - the record changed between the read that populated the
 *       screen and the write that would have committed it.</li>
 *   <li>{@code DataIntegrityException} - a referential expectation across the related records did not
 *       hold.</li>
 *   <li>{@code FileAccessException} - a physical or logical I/O error, the {@code '9x'} family, which
 *       is the branch {@code 9910-DISPLAY-IO-STATUS} renders by expanding the second status byte.</li>
 *   <li>{@code FatalProcessingException} - the unrecoverable terminal case. It carries the four abend
 *       work areas declared in {@code app/cpy/CSMSG02Y.cpy} - internally titled {@code CABENDD.CPY}
 *       and described there as work areas for the abend routine - namely {@code ABEND-CODE PIC X(4)},
 *       {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and
 *       {@code ABEND-MSG PIC X(72)}.</li>
 * </ul>
 *
 * <p>Catching this base type therefore catches every CardDemo originated failure, while catching a
 * subtype selects exactly one. Callers should catch the narrowest type that they can actually act on.
 *
 * <h2>Evidence: the legacy status and response code census</h2>
 *
 * <p>Measured across {@code app/cbl} at the anchor commit. The scope of each count is stated so the
 * figure is reproducible rather than merely asserted. Status literals were counted as comparison sites
 * with {@code grep -oE "STATUS[ ]+=[ ]+'nn'" app/cbl/*.cbl}; response codes were counted as
 * occurrences of the literal {@code DFHRESP(name)}:
 *
 * <ul>
 *   <li>{@code FILE STATUS} comparison sites - {@code '00'} 73, {@code '10'} 7, {@code '23'} 1,
 *       {@code '22'} 0, {@code '35'} 0.</li>
 *   <li>{@code EXEC CICS} response codes - {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23,
 *       {@code DFHRESP(ENDFILE)} 8, {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3,
 *       {@code DFHRESP(NOTOPEN)} 0.</li>
 * </ul>
 *
 * <p>Two consequences of that census shape this package, and both are easy to get wrong:
 *
 * <ul>
 *   <li>The single {@code '23'} comparison site is <strong>not</strong> an error. It is
 *       {@code app/cbl/CBTRN02C.cbl:L481}, {@code IF TCATBALF-STATUS = '00' OR '23'}, the upsert that
 *       accepts a missing category balance row as an accepted control path. The interest program does
 *       the same at {@code app/cbl/CBACT04C.cbl:L422} and {@code :L436} for its default group
 *       fallback. A blanket rule that maps not found onto {@code RecordNotFoundException} would abend
 *       both of those working paths, which is why that decision belongs to the central mapper and not
 *       to a constructor here.</li>
 *   <li><strong>Finding, severity Medium.</strong> {@code '22'} and {@code '35'} do not occur anywhere
 *       in {@code app/cbl}, and {@code DFHRESP(NOTOPEN)} does not occur either. So
 *       {@code DuplicateRecordException} rests on {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)}
 *       only, and {@code FileUnavailableException} has no antecedent in the corpus at all: it is a
 *       defensive type for a condition the source never demonstrates. Remediation: keep the type,
 *       because the underlying store can genuinely be unavailable, but do not treat parity of its
 *       message text as provable against a legacy baseline, and record it in {@code DECISION_LOG.md}
 *       rather than presenting it as a translated behaviour.</li>
 * </ul>
 *
 * <p><strong>Finding, severity High.</strong> The account update program distinguishes four separate
 * write outcomes - account lock failure, customer lock failure, data changed before update, and locked
 * but update failed. Collapsing them into one generic conflict would discard information the legacy
 * screen displayed. Remediation: raise the distinguishable subtype per outcome and let the controller
 * choose the response; never widen a specific subtype to this base type on the way out.
 *
 * <h2>The cause preservation contract</h2>
 *
 * <p>Rule 1 Clause B requires error handling that wraps with context and preserves the root cause.
 * That contract is stated here once and binds every type in this package:
 *
 * <ul>
 *   <li>Every type in this hierarchy offers a cause carrying constructor.</li>
 *   <li>No type in this hierarchy ever drops a cause it was handed; the cause is always delegated to
 *       {@code super}, so {@link Throwable#getCause()} returns it unchanged.</li>
 *   <li>Every {@code catch} block anywhere in the tree either rethrows or throws a type from this
 *       package carrying the original throwable as its cause. A {@code catch} that discards its
 *       argument is the swallowing that Clause B forbids.</li>
 *   <li>Stack trace capture is left at the {@link Throwable} default. Neither
 *       {@link Throwable#fillInStackTrace()} suppression nor a writable stack trace flag is offered,
 *       because either would destroy the root cause evidence this contract exists to protect.</li>
 * </ul>
 *
 * <h2>Null and blank handling</h2>
 *
 * <p>Clause B requires that null and empty cases be handled explicitly, so the policy is stated
 * rather than left implicit, and it is uniform across all nine types: <strong>a null or blank message
 * and a null cause are permitted and passed through unchanged.</strong> A null cause means there is no
 * underlying failure to attribute. No argument is rejected and no argument is substituted.
 *
 * <p>The reason for permitting rather than rejecting is specific to exceptions. Throwing
 * {@link IllegalArgumentException} from the constructor of a failure type would replace the real
 * failure with a spurious argument failure at the exact moment the real one is being reported,
 * destroying the root cause the contract above exists to preserve. Validating the reporter is not
 * worth losing the report. Callers are nonetheless expected to supply a specific, actionable message,
 * and to prefer the cause carrying constructor whenever they are inside a {@code catch}.
 *
 * <h2>What this class deliberately does not carry</h2>
 *
 * <ul>
 *   <li><strong>No reject code.</strong> The batch reject codes are business outcomes that drive a
 *       batch exit status; they are never thrown. Preserving parity depends on it: the source's
 *       over limit and expiry checks are sequential and unguarded, so when both fail the later code
 *       overwrites the earlier one and a single reject record is written. An exception based design
 *       cannot reproduce that overwrite, because the first throw would abandon the second check.</li>
 *   <li><strong>No error code, no numeric identifier and no HTTP status field.</strong>
 *       Distinguishability comes from the type, which is precisely why there are nine of them. Binding
 *       a status here would fix one mapping for every call site; the controllers choose it in
 *       context.</li>
 *   <li><strong>No annotation of any kind, and no logging.</strong> An exception is thrown; the caller
 *       logs it. A logger here would double log every failure and would put a side effect in a
 *       constructor.</li>
 *   <li><strong>No custom serialization hook and no state beyond {@link Throwable}.</strong> Rule 1
 *       Clause D names insecure deserialization as a risky pattern to flag, so this hierarchy adds no
 *       object stream callback, no serialization proxy, no instance replacement method and no
 *       alternative externalization contract. It relies solely on the default mechanism inherited from
 *       {@link Throwable}, it holds no field of its own to reconstruct, and no instance of it is ever
 *       rebuilt from untrusted input. The only serialization concession is the pinned
 *       {@code serialVersionUID} below, which exists to satisfy the compiler lint rather than to
 *       enable a wire format.</li>
 *   <li><strong>No token, credential, key or privileged handle</strong>, and no authorisation decision.
 *       Exception messages are logged, so callers must keep secrets and personally identifiable
 *       values - passwords and their hashes, social security numbers, card numbers, telephone numbers,
 *       government identifiers, dates of birth and electronic funds account identifiers - out of the
 *       message they supply. Identify a failing record by its key, never by its content.</li>
 * </ul>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A caller sees this base type rather than a subtype.</em> Something threw the base
 *       directly. Prefer the narrowest subtype; the base exists to be extended and to be caught, not
 *       to be the default thing thrown.</li>
 *   <li><em>{@link Throwable#getCause()} returns null after a wrap.</em> The throwing site used the
 *       message only constructor inside a {@code catch}. Switch it to the cause carrying constructor;
 *       the cause is never dropped by this hierarchy itself.</li>
 *   <li><em>{@link Throwable#getMessage()} returns null.</em> A null message was supplied and passed
 *       through by design, as documented above. Fix the throwing site, not this class.</li>
 *   <li><em>A working legacy path now fails.</em> Suspect a not found status being mapped to an error
 *       at one of the accepted control path sites cited in the census above. The fix belongs in the
 *       central mapper.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> A subtype was added without declaring
 *       {@code serialVersionUID}. Every type here is a {@link java.io.Serializable} descendant through
 *       {@link Throwable}, and the build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so the declaration is mandatory rather than advisory.</li>
 * </ul>
 *
 * <h2>Dependency direction and testing</h2>
 *
 * <p>This package depends only on {@code java.lang}: no framework type, no third party library and no
 * other CardDemo package. Dependencies may point from {@code com.cardemo.exception} towards
 * {@code com.cardemo.model} where a subtype genuinely needs a model type, and never in the reverse
 * direction. In particular the enum package does not reference this package, which keeps the two
 * mutually independent and the cycle impossible.
 *
 * <p>Instances are immutable value carriers with no static state, no clock, no locale and no charset
 * dependency, so behaviour is deterministic and unit testable. Tests live under
 * {@code src/test/java/com/cardemo/unit} and not in this package.
 *
 * @see RuntimeException
 */
public class CardDemoException extends RuntimeException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements
     * {@link java.io.Serializable}, so every type in this hierarchy is unavoidably serializable and
     * must pin this value explicitly: the build runs {@code -Xlint:all} with {@code -Werror} and
     * {@code failOnWarning}, which turns the {@code serial} lint into a compilation failure. A fixed
     * literal is used rather than a computed default so that the identity does not shift when the
     * class is edited.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception that reports a failure with no underlying throwable to attribute it to.
     *
     * <p>Use this form when the failure originates in CardDemo's own logic - a guard that did not
     * hold, a lookup that returned nothing, a state that cannot be reconciled - so that there is
     * genuinely no cause to preserve. Inside a {@code catch} block, use
     * {@link #CardDemoException(String, Throwable)} instead; discarding the caught throwable is the
     * swallowing that Rule 1 Clause B forbids.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded
     * and no state outside this instance is read or written.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It is
     *                permitted to be null or blank and is passed through unchanged, in which case
     *                {@link Throwable#getMessage()} returns exactly what was supplied. A specific,
     *                actionable message is expected; it must not contain a secret, a credential or a
     *                personally identifiable value, because exception messages are logged
     */
    public CardDemoException(String message) {
        super(message);
    }

    /**
     * Creates an exception that reports a failure and preserves the throwable that caused it.
     *
     * <p>This is the form required at every wrapping site. It is the direct implementation of Rule 1
     * Clause B's requirement to wrap with context and preserve the root cause: the message supplies
     * the CardDemo level context and the cause retains the original failure, unmodified and fully
     * navigable through {@link Throwable#getCause()}.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * re-thrown nor logged; it is delegated to {@link RuntimeException} as supplied.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It is
     *                permitted to be null or blank and is passed through unchanged. A specific,
     *                actionable message is expected; it must not contain a secret, a credential or a
     *                personally identifiable value, because exception messages are logged
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}. It is
     *                permitted to be null, which records that there is no underlying failure to
     *                attribute. When non null it is always retained; this hierarchy never drops a
     *                cause it was handed
     */
    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
    }
}
