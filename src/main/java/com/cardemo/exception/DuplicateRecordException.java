/*
 * ******************************************************************
 * Program     : DuplicateRecordException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of FILE STATUS '22' / DFHRESP(DUPREC) / DFHRESP(DUPKEY) - duplicate key.
 * Source      : app/cbl/COUSR01C.cbl:L260-L261 @ 7756d89 - the canonical
 *               DFHRESP(DUPKEY) site, user add against the eight byte USRSEC key.
 * Source      : app/cbl/COTRN02C.cbl:L735-L736 @ 7756d89 - transaction add.
 * Source      : app/cbl/COBIL00C.cbl:L533-L534 @ 7756d89 - bill payment.
 * Source      : app/jcl/COMBTRAN.jcl:STEP10 @ 7756d89 - the IDCAMS REPRO bulk
 *               load where duplicate interest identifiers surface.
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
 * Signals that a keyed write collided with a record that already exists.
 *
 * <p><strong>What it does.</strong> It is the typed target for the duplicate key outcome of the frozen
 * legacy corpus: {@code FILE STATUS '22'} in the batch idiom, and {@code DFHRESP(DUPREC)} together with
 * {@code DFHRESP(DUPKEY)} in the online programs. It is thrown when an insert, a write or a bulk load
 * is rejected because the key is already present in the store.
 *
 * <p><strong>Why it matters more than a plain error type.</strong> This class is not defensive
 * decoration. It is the designed <em>surfacing mechanism</em> for two hazards that the migration
 * preserves on purpose rather than repairs: the maximum-plus-one identifier generation race of the two
 * online programs, and the duplicate identifier collision of the combine job's bulk load. Both are
 * described in full below, and both come with a prohibition that binds callers rather than this class.
 * Papering over either one changes generated identifier values and breaks the boundary parity
 * comparison that the migration is accepted against.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line or step in
 * the frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the four
 * {@code Source} lines of the file header record. The anchor is stated once here rather than repeated on
 * each citation. Filename case is reproduced as it appears on disk, which matters because the corpus is
 * not uniform: of the 28 members of {@code app/cbl} only two use an uppercase extension, of the 28
 * members of {@code app/cpy} only one does, and of the <strong>29</strong> members of {@code app/jcl}
 * exactly one does - so a glob over that directory must be case insensitive or it silently drops a
 * member. Every path cited here is lowercase because every file cited here is lowercase. Each figure
 * quoted is one this file's author measured on the anchor commit rather than inherited.
 *
 * <h2>Evidence: how often the corpus actually signals a duplicate</h2>
 *
 * <p>Measured across all 28 programs of {@code app/cbl}. The measurement method is stated so that each
 * figure is reproducible rather than merely asserted. Status literals were counted as comparison sites
 * with {@code grep -rhoE "STATUS[ ]+=[ ]+'nn'" app/cbl/}; response codes were counted as occurrences of
 * the literal {@code DFHRESP(name)}:
 *
 * <ul>
 *   <li>{@code FILE STATUS} comparison sites - {@code '00'} 73, {@code '10'} 7, {@code '23'} 1,
 *       <strong>{@code '22'} 0</strong>, {@code '35'} 0.</li>
 *   <li>{@code EXEC CICS} response codes - {@code DFHRESP(NORMAL)} 43, {@code DFHRESP(NOTFND)} 23,
 *       {@code DFHRESP(ENDFILE)} 8, <strong>{@code DFHRESP(DUPREC)} 7</strong>,
 *       <strong>{@code DFHRESP(DUPKEY)} 3</strong>, {@code DFHRESP(NOTOPEN)} 0.</li>
 * </ul>
 *
 * <p><strong>Finding, severity Medium. Not available: the corpus contains no literal
 * {@code FILE STATUS '22'} test.</strong> There are zero such sites. Widening the search from
 * {@code app/cbl} to the whole frozen tree does not find one either - {@code grep -rn "'22'" app/}
 * returns no match at all, in the programs, the copybooks, the job control, the mapsets or the resource
 * definitions. Duplicate key handling exists in this corpus <em>only</em> in the online programs, and
 * only through the two CICS response codes. The {@code '22'} row of the map below is therefore grounded
 * in the <strong>CICS response equivalence</strong> and in the batch guard's own structure, not in an
 * observed COBOL status comparison, and this documentation does not imply otherwise.
 *
 * <p><em>What would be needed to close the gap.</em> A batch execution trace, or an EBCDIC dataset
 * capture, in which a {@code WRITE} against one of the keyed clusters actually returns {@code '22'} - or
 * an authoritative statement that the batch stream never attempts a duplicate insert. Neither exists in
 * this repository. <em>Remediation.</em> Keep the type and keep the mapping, because a keyed store can
 * genuinely reject a duplicate and the batch guard of
 * {@code app/cbl/CBTRN02C.cbl:L512} treats any non {@code '00'} write status as fatal; but do not claim
 * that the message text or the status literal of this branch is provable against a legacy baseline, and
 * record the gap in {@code DECISION_LOG.md} rather than presenting it as a translated behaviour.
 *
 * <p>The three {@code DFHRESP(DUPKEY)} sites, by exact locator, are the whole observed basis for this
 * type:
 *
 * <ul>
 *   <li>{@code app/cbl/COUSR01C.cbl:L260} - user add. The canonical site. Its write is
 *       {@code WRITE-USER-SEC-FILE} at {@code app/cbl/COUSR01C.cbl:L238-L248}, whose {@code RIDFLD} is
 *       {@code SEC-USR-ID}, the eight byte {@code USRSEC} key.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl:L735} - transaction add. Its write is at
 *       {@code app/cbl/COTRN02C.cbl:L713-L721}, {@code RIDFLD (TRAN-ID)}.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L533} - bill payment, which generates a transaction the same way
 *       transaction add does.</li>
 * </ul>
 *
 * <p><strong>Why one type covers all ten sites, evidenced rather than assumed.</strong> At each of the
 * three locators above, {@code WHEN DFHRESP(DUPKEY)} and {@code WHEN DFHRESP(DUPREC)} are
 * <em>adjacent fall-through</em> {@code WHEN} clauses of a single {@code EVALUATE}, sharing one handler
 * body - {@code app/cbl/COUSR01C.cbl:L260-L261}, {@code app/cbl/COTRN02C.cbl:L735-L736} and
 * {@code app/cbl/COBIL00C.cbl:L533-L534}. The source itself declines to distinguish the two responses.
 * Splitting them into two Java types, or adding a per entity subtype, would invent a distinction the
 * system of record does not make. One type is therefore the faithful translation, and no
 * per entity convenience subclass of this class exists anywhere in the tree.
 *
 * <h2>The status to exception map</h2>
 *
 * <table>
 * <caption>Position of this class in the status to exception map</caption>
 * <tr><th scope="col">{@code FILE STATUS}</th><th scope="col">Meaning</th><th scope="col">Target</th></tr>
 * <tr><td>{@code '00'} / {@code '04'}</td><td>Success / secondary success</td><td>Continue</td></tr>
 * <tr><td>{@code '10'}</td><td>End of file</td>
 *     <td><strong>Loop termination, not an error.</strong> No exception is raised</td></tr>
 * <tr><td>{@code '23'}</td><td>Record not found</td>
 *     <td>{@code RecordNotFoundException}, except at the scoped accepted control path sites</td></tr>
 * <tr><td><strong>{@code '22'}</strong></td><td><strong>Duplicate key</strong></td>
 *     <td><strong>This class</strong></td></tr>
 * <tr><td>{@code '35'}</td><td>File unavailable</td><td>{@code FileUnavailableException}</td></tr>
 * <tr><td>{@code '9x'}</td><td>Physical or logical I/O error</td><td>{@code FileAccessException}</td></tr>
 * <tr><td>Anything else</td><td>Unexpected</td>
 *     <td>{@code FatalProcessingException}, abend code 999, return code 12</td></tr>
 * </table>
 *
 * <p>Two rows are routinely misread. {@code '10'} is <strong>not</strong> a failure: it is how every
 * sequential loop in the batch corpus learns that it has finished, and raising anything for it would
 * abort a normal run. And {@code '23'} is not always a failure either, which the next section develops,
 * because conflating its leniency with this class's condition is the single easiest way to break parity
 * here.
 *
 * <p>Choosing which row applies is <strong>not</strong> this class's job. A raw status is classified by
 * {@code com.cardemo.model.enums.FileStatus} - whose {@code DUPLICATE_KEY} constant carries the
 * {@code '22'} code and which also owns the four character diagnostic rendering - and the decision of
 * which failure a status represents belongs to {@code com.cardemo.service.shared.FileStatusMapper}. This
 * package supplies only the typed targets of that decision. Accordingly this class holds no status
 * field, performs no status parsing and repeats no diagnostic literal; duplicating any of those would
 * create a second source of truth for a value that must stay byte identical to the legacy output.
 *
 * <h2>Hazard 1: the maximum-plus-one identifier generation race, preserved deliberately</h2>
 *
 * <p>Two online programs generate a transaction identifier by browsing the keyed cluster backwards and
 * adding one to whatever they find. The idiom is identical in both, at
 * {@code app/cbl/COTRN02C.cbl:L444-L451} and {@code app/cbl/COBIL00C.cbl:L212-L219}:
 *
 * <pre>
 * MOVE HIGH-VALUES TO TRAN-ID
 * PERFORM STARTBR-TRANSACT-FILE
 * PERFORM READPREV-TRANSACT-FILE
 * PERFORM ENDBR-TRANSACT-FILE
 * MOVE TRAN-ID     TO WS-TRAN-ID-N
 * ADD 1 TO WS-TRAN-ID-N
 * </pre>
 *
 * <p>The boundary case is explicit in the source rather than incidental. {@code READPREV-TRANSACT-FILE}
 * at {@code app/cbl/COBIL00C.cbl:L472-L496} answers an end of file response with
 * {@code MOVE ZEROS TO TRAN-ID} at {@code app/cbl/COBIL00C.cbl:L488}, so on an empty cluster the
 * subsequent {@code ADD 1} yields <strong>1</strong>. The first generated identifier is one, not zero,
 * and a Java translation that seeds from an absent maximum must reproduce exactly that.
 *
 * <p><strong>The algorithm is inherently racy, and the race is retained.</strong> Note the third line:
 * {@code ENDBR} closes the browse <em>before</em> the successor is computed and long before the write is
 * attempted, so neither a position nor a lock is held across the read-compute-write window. Two
 * concurrent tasks read the same maximum and derive the same successor; the second write to reach the
 * cluster is the one that collides. The migration keeps this behaviour instead of correcting it, because
 * the generated identifier values themselves are compared against the legacy baseline: a scheme that
 * allocated different numbers would fail that comparison even though every individual value it produced
 * was unique. The primary key constraint is what detects the collision, and
 * <strong>this class is how that detection reaches a caller.</strong>
 *
 * <h2>Hazard 2: the combine job's duplicate identifier collision</h2>
 *
 * <p>The interest calculation program cannot detect a duplicate at all, and that is a property of its
 * file definition rather than an omission in its logic. Its transaction output is declared at
 * {@code app/cbl/CBACT04C.cbl:L53-L56} as
 *
 * <pre>
 * SELECT TRANSACT-FILE ASSIGN TO TRANSACT
 *        ORGANIZATION IS SEQUENTIAL
 *        ACCESS MODE  IS SEQUENTIAL
 *        FILE STATUS  IS TRANFILE-STATUS.
 * </pre>
 *
 * <p>There is <strong>no {@code RECORD KEY} clause</strong>, so there is no key to be duplicate against;
 * every run also allocates a brand new generation of a sequential generation data group. Duplicate
 * detection is structurally impossible at the point of writing.
 *
 * <p>The identifiers it writes are nonetheless fully deterministic, which is precisely what creates the
 * exposure. {@code 1300-B-WRITE-TX} at {@code app/cbl/CBACT04C.cbl:L473-L480} increments a suffix and
 * concatenates it onto the job's date parameter:
 *
 * <pre>
 * ADD 1 TO WS-TRANID-SUFFIX
 * STRING PARM-DATE,
 *        WS-TRANID-SUFFIX
 *   DELIMITED BY SIZE
 *   INTO TRAN-ID
 * </pre>
 *
 * <p>{@code PARM-DATE} is {@code PIC X(10)} at {@code app/cbl/CBACT04C.cbl:L178} and
 * {@code WS-TRANID-SUFFIX} is {@code PIC 9(06) VALUE 0} at {@code app/cbl/CBACT04C.cbl:L173} - declared
 * once, incremented only at {@code :L474} and <strong>never reset per account</strong>, so it is
 * run global and the sixteen character identifier is determined entirely by the date parameter plus an
 * ordinal. <strong>Re-running the job with the same date parameter therefore reproduces the identical
 * identifier sequence.</strong>
 *
 * <p>The collision surfaces one job later. {@code app/jcl/COMBTRAN.jcl:STEP05R} sorts the concatenated
 * transaction backup and interest generations by identifier, and {@code app/jcl/COMBTRAN.jcl:STEP10}
 * then runs {@code IDCAMS} with {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} to bulk load the sorted
 * result into the <em>keyed</em> transaction cluster. That load is the first moment a key constraint
 * exists, and a repeated date parameter collides there.
 *
 * <p>The required behaviour is unambiguous: the bulk load must raise this exception and the batch tier
 * must translate it into a <strong>failed exit status</strong>, aborting the job. The consequence of the
 * softer alternative is a silently corrupted transaction master, so the softer alternative is forbidden
 * outright by the next section.
 *
 * <h2>The caller contract: three prohibitions</h2>
 *
 * <p>These bind the code that catches or provokes this exception, not merely this class. They are
 * stated here because this is the one place every such caller is guaranteed to read.
 *
 * <ul>
 *   <li><strong>Blocker - no retry, no backoff, no regeneration loop.</strong> Catching this exception
 *       and re-deriving the identifier would hand out a number the legacy system would not have handed
 *       out, breaking the boundary parity comparison. No retry annotation, no retry template, no
 *       recovery loop and no jittered wait may wrap an identifier generating write. <em>Remediation:</em>
 *       let the exception propagate; report the collision to the caller exactly as the legacy screen
 *       reported it.</li>
 *   <li><strong>Blocker - no silent upsert.</strong> Converting a duplicate into an update, a merge, an
 *       insert-or-ignore or an insert-on-conflict-do-nothing at the combine load would overwrite or
 *       discard transaction rows and leave a corrupted master that no test asserts on.
 *       <em>Remediation:</em> fail the step and let the operator re-drive it with a correct date
 *       parameter.</li>
 *   <li><strong>Blocker - no database sequence, identity column or generator substitution.</strong> A
 *       sequence is better engineering and is still wrong here: it changes the generated values, so it
 *       fails the baseline comparison just as a retry does. The maximum-plus-one browse is retained on
 *       purpose. <em>Remediation:</em> keep the derived maximum; the tradeoff is justified in
 *       {@code DECISION_LOG.md}, not in this class.</li>
 * </ul>
 *
 * <p>Stated positively: the only correct handling of this exception is to let it reach a boundary that
 * reports it - an HTTP response for the online path, a failed exit status for the batch path.
 *
 * <h2>Not to be confused with the not found leniency</h2>
 *
 * <p>There is exactly one place in the batch corpus where a missing record is treated as success, and it
 * is emphatically not a place where a <em>duplicate</em> is treated as success. The distinction is worth
 * spelling out because the two conditions sit in the same paragraph.
 *
 * <p>{@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L467-L501} is an upsert. Its
 * {@code READ ... INVALID KEY} at {@code :L474-L479} displays a creating message and sets
 * {@code WS-CREATE-TRANCAT-REC} to {@code 'Y'}, and its guard at {@code :L481} then reads
 * {@code IF TCATBALF-STATUS = '00' OR '23'} before dispatching to
 * {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503} or {@code 2700-B-UPDATE-TCATBAL-REC} at
 * {@code :L526}.
 *
 * <p><strong>That leniency is for {@code '23'}, not for {@code '22'}, and it is scoped to the read guard
 * alone.</strong> The guards that follow the mutating verbs accept a single value: the {@code WRITE} on
 * the create branch is guarded at {@code app/cbl/CBTRN02C.cbl:L512} by
 * {@code IF TCATBALF-STATUS = '00'}, and the {@code REWRITE} on the update branch is guarded identically
 * at {@code :L530}. Anything else moves 12 into {@code APPL-RESULT} and falls through to
 * {@code 9910-DISPLAY-IO-STATUS} and {@code 9999-ABEND-PROGRAM}, which moves 999 into {@code ABCODE} and
 * issues {@code CALL 'CEE3ABD'} at {@code app/cbl/CBTRN02C.cbl:L707-L711}. So a duplicate arriving on the
 * create branch <strong>abends the run</strong>; it does not quietly become an update. The interest
 * program's parallel leniency at {@code app/cbl/CBACT04C.cbl:L422} and {@code :L436} is likewise a not
 * found fallback and likewise says nothing about duplicates.
 *
 * <p><strong>Never treat this exception as an upsert trigger.</strong> A missing record and a colliding
 * record are opposite conditions with opposite legacy outcomes: one is an accepted control path, the
 * other terminates the unit of work.
 *
 * <h2>What this class carries, and what it must never carry</h2>
 *
 * <p>It carries exactly enough to make a collision diagnosable: the logical file the write was aimed at,
 * and the key that collided. Both are optional and both are strings. That is the whole surface, and it is
 * deliberately minimal.
 *
 * <p><strong>The key only rule, and why it is not merely hygiene.</strong> Exception messages are
 * logged. The canonical duplicate site in this corpus is user add, and the record in flight there is
 * {@code SEC-USER-DATA} of {@code app/cpy/CSUSR01Y.cpy:L17-L23} - an eighty byte layout of
 * {@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 * <strong>{@code SEC-USR-PWD X(08)}</strong>, {@code SEC-USR-TYPE X(01)} and
 * {@code SEC-USR-FILLER X(23)}. The write at {@code app/cbl/COUSR01C.cbl:L238-L248} passes
 * {@code FROM (SEC-USER-DATA)}, the entire record including that password field, while its
 * {@code RIDFLD} is only {@code SEC-USR-ID}. <strong>A message or field that echoed the record being
 * written would therefore log a credential.</strong> Pass the key, never the record.
 *
 * <p>The same restriction covers every other sensitive value the corpus defines, since a colliding key
 * elsewhere in the tree may be a card number. Callers must keep out of the message and out of both
 * fields: passwords and their hashes, card numbers, social security numbers, telephone numbers,
 * government issued identifiers, dates of birth, electronic funds account identifiers, tokens and
 * signing keys. Where the natural key is itself sensitive, supply a masked or truncated form, or supply
 * the logical file alone and omit the key.
 *
 * <p>Also absent by design, each for a reason:
 *
 * <ul>
 *   <li><strong>No reject code and no reject outcome of any kind.</strong> A batch reject is a business
 *       outcome, not a failure: it is accumulated into a counter and written to a reject record, and it
 *       drives the job's exit status through {@code IF WS-REJECT-COUNT &gt; 0} at
 *       {@code app/cbl/CBTRN02C.cbl:L229-L231}. It is never thrown, and an exception based design could
 *       not reproduce it anyway, because the source's sequential unguarded validation checks let a later
 *       reason overwrite an earlier one whereas a throw would abandon the remaining checks. This class
 *       is the opposite kind of event - a genuine failure that must abort the unit of work - and the two
 *       must not be conflated when the batch exit status is computed.</li>
 *   <li><strong>No status field and no HTTP status.</strong> Distinguishability comes from the type.
 *       Binding a response status here would fix one answer for every call site, whereas the online and
 *       batch tiers legitimately need different ones; controllers choose in context. There is no
 *       exception handling advice anywhere in this tree.</li>
 *   <li><strong>No annotation, and no logging.</strong> An exception is thrown; the caller logs it. A
 *       logger here would double log every failure and would put a side effect in a constructor.</li>
 *   <li><strong>No custom serialization hook.</strong> {@link Throwable} already implements
 *       {@link java.io.Serializable}, so this type is unavoidably serializable, but it adds no object
 *       stream callback, no serialization proxy, no instance replacement method and no alternative
 *       externalization contract, and no instance of it is ever reconstructed from untrusted input. The
 *       only concession is the pinned {@code serialVersionUID}, which exists to satisfy the compiler
 *       lint rather than to enable a wire format.</li>
 * </ul>
 *
 * <h2>Null and blank handling</h2>
 *
 * <p>The policy is inherited unchanged from {@link CardDemoException} and is uniform across the
 * hierarchy: <strong>a null or blank message, a null cause, a null or blank logical file and a null or
 * blank key are all permitted and are all stored and returned exactly as supplied.</strong> Nothing is
 * rejected, nothing is trimmed, nothing is defaulted and nothing is normalised - and because no value is
 * case folded or formatted, behaviour carries no locale, charset, time zone or environment dependency.
 *
 * <p>Constructors do not validate their arguments, and that is a deliberate decision rather than an
 * oversight. Throwing {@link IllegalArgumentException} from the constructor of a failure type would
 * replace the real failure with a spurious argument failure at the exact moment the real one is being
 * reported, destroying the root cause this hierarchy exists to preserve. Validating the reporter is not
 * worth losing the report. Callers are nonetheless expected to supply a specific, actionable message and
 * to prefer a cause carrying constructor whenever they are inside a {@code catch} block.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>This exception appears sporadically under concurrent load on transaction add or bill
 *       payment.</em> That is Hazard 1 behaving as specified, not a defect. Two requests derived the same
 *       successor. <strong>Do not add a retry</strong>; report the collision and let the caller resubmit.
 *       <em>Severity High if it is instead silenced.</em></li>
 *   <li><em>This exception appears from the combine job's load step.</em> That is Hazard 2: the interest
 *       job has almost certainly been run twice with the same date parameter, so the second run
 *       regenerated identifiers the first run already loaded. Re-drive with the correct date parameter.
 *       Do not make the load idempotent.</li>
 *   <li><em>{@link Throwable#getCause()} returns null although the duplicate was detected by the
 *       driver.</em> The throwing site used a constructor without a cause. <strong>Severity High:</strong>
 *       the driver's own exception carries the constraint name and is usually the only evidence of
 *       <em>which</em> key collided. Switch to a cause carrying constructor; this class never drops a
 *       cause it was handed.</li>
 *   <li><em>{@link #getLogicalFile()} and {@link #getCollidingKey()} both return null.</em> A message
 *       only constructor was used. Permitted, but the collision is then far harder to attribute; prefer
 *       a field carrying constructor at any repository or batch writer site.</li>
 *   <li><em>A record that legitimately does not exist is now reported as a duplicate, or vice versa.</em>
 *       The central mapper has confused {@code '22'} with {@code '23'}. See the leniency section above;
 *       the fix belongs in the mapper, never here.</li>
 *   <li><em>Log review finds a credential or a card number in a duplicate message.</em> A caller echoed
 *       the record instead of the key. <strong>Severity Blocker.</strong> Fix the throwing site and treat
 *       the exposed value as compromised.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> The {@code serialVersionUID} declaration
 *       below was removed. The build compiles with {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning}, so that declaration is mandatory rather than advisory.</li>
 * </ul>
 *
 * <h2>Dependency direction and testing</h2>
 *
 * <p>This file declares no import at all. It depends only on {@link CardDemoException}, its sibling in
 * this package, and on {@code java.lang}. No framework, persistence or third party type appears in it -
 * in particular no Spring data access exception and no persistence provider exception - so the type
 * survives a change of persistence technology and can be constructed in a plain unit test. Where a type
 * outside this package is mentioned above it is named in prose rather than linked, because the reference
 * is documentary and must not become a compile time dependency. Dependencies may point from
 * {@code com.cardemo.exception} towards {@code com.cardemo.model} and never in the reverse direction.
 *
 * <p>Instances are immutable, carry no static state and perform no I/O, so behaviour is deterministic.
 * Tests live under {@code src/test/java/com/cardemo/unit} and not in this package; they assert cause
 * preservation, field retention and the null pass-through policy, while the batch integration tier
 * asserts that a repeated interest date parameter surfaces this exception with a failed exit status and
 * performs no upsert.
 *
 * @see CardDemoException
 */
public class DuplicateRecordException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements {@link java.io.Serializable},
     * so this type is unavoidably serializable and must pin this value explicitly: the build runs
     * {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, which turns the {@code serial}
     * lint into a compilation failure. A fixed literal is used rather than a computed default so that
     * the identity does not shift when the class is edited.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The logical file the colliding write was aimed at, or null when the caller did not identify one.
     *
     * <p>Holds the legacy logical name rather than a physical table name, so that a collision report
     * reads the way the corpus reads - {@code USRSEC}, {@code TRANSACT}, {@code ACCTDAT},
     * {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT} or {@code TCATBALF}. Stored exactly as supplied
     * and never case folded, so no locale is involved.
     */
    private final String logicalFile;

    /**
     * The key value that already existed, or null when the caller did not supply it.
     *
     * <p>This is the key alone, never the record. See the key only rule in the class documentation: the
     * record in flight at the canonical duplicate site contains a password field, so echoing a record
     * here would log a credential. Where the natural key is itself sensitive, a masked or truncated form
     * is supplied instead. Stored exactly as supplied.
     */
    private final String collidingKey;

    /**
     * Creates a duplicate key failure that identifies neither the logical file nor the colliding key.
     *
     * <p>The least informative form. It is offered because a caller that has only a message must still
     * be able to report the collision, but a repository or batch writer should prefer
     * {@link #DuplicateRecordException(String, String, String)} so that the collision can be attributed
     * without parsing prose. Inside a {@code catch} block use
     * {@link #DuplicateRecordException(String, Throwable)} instead, so the driver's own exception is
     * preserved.
     *
     * <p>Delegates to {@link #DuplicateRecordException(String, String, String)} with both fields null.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded and
     * no state outside this instance is read or written.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged. It must not contain a secret, a
     *                credential or a personally identifiable value, because exception messages are
     *                logged
     */
    public DuplicateRecordException(String message) {
        this(message, null, null);
    }

    /**
     * Creates a duplicate key failure that preserves the throwable that reported the collision.
     *
     * <p>This is the form required at every wrapping site, and it matters more for this type than for
     * most: a duplicate is almost always detected first by the database driver or the persistence
     * provider, and that exception is normally the only thing that names the violated constraint.
     * Discarding it is the swallowing that Rule 1 Clause B forbids.
     *
     * <p>Delegates to {@link #DuplicateRecordException(String, String, String, Throwable)} with both
     * fields null, which passes the cause to {@code super}, so {@link Throwable#getCause()} returns it
     * unchanged.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * rethrown nor logged.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged. It must not contain a secret, a
     *                credential or a personally identifiable value
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                Permitted to be null, which records that there is no underlying failure to
     *                attribute. When non null it is always retained
     */
    public DuplicateRecordException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * Creates a duplicate key failure that identifies the logical file and the colliding key.
     *
     * <p>The preferred form when CardDemo's own logic detected the collision and there is genuinely no
     * throwable to attribute it to - for example a pre-write existence check. When the collision was
     * reported by a driver or a persistence provider, use
     * {@link #DuplicateRecordException(String, String, String, Throwable)} so that the root cause is
     * preserved.
     *
     * <p>This constructor invokes {@code super(message)} rather than passing a null cause, which leaves
     * the cause uninitialised and so keeps {@link Throwable#initCause(Throwable)} available to a caller
     * that learns the cause later. That distinction is the reason this form is not folded into the four
     * argument one.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * @param message      the detail message, retrievable through {@link Throwable#getMessage()}.
     *                     Permitted to be null or blank and passed through unchanged. It must not
     *                     contain a secret, a credential or a personally identifiable value
     * @param logicalFile  the logical file the write was aimed at, such as {@code USRSEC} or
     *                     {@code TRANSACT}. Permitted to be null or blank and stored unchanged;
     *                     retrievable through {@link #getLogicalFile()}
     * @param collidingKey the key value that already existed. Permitted to be null or blank and stored
     *                     unchanged; retrievable through {@link #getCollidingKey()}. Supply the key
     *                     alone and never the record, and supply a masked form where the key is itself
     *                     sensitive
     */
    public DuplicateRecordException(String message, String logicalFile, String collidingKey) {
        super(message);
        this.logicalFile = logicalFile;
        this.collidingKey = collidingKey;
    }

    /**
     * Creates a fully described duplicate key failure that also preserves its root cause.
     *
     * <p>The most informative form and the one a repository or batch writer should use, because it
     * answers all three questions a collision raises at once: which file, which key, and what the store
     * actually reported. It is the direct implementation of Rule 1 Clause B's requirement to wrap with
     * context while preserving the root cause - the message and the two fields supply the CardDemo level
     * context, and the cause retains the original failure, unmodified and fully navigable.
     *
     * <p>Passes the cause to {@code super}, so {@link Throwable#getCause()} returns it unchanged. This
     * is the canonical cause carrying constructor of this class; the two argument form delegates here.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * rethrown nor logged.
     *
     * @param message      the detail message, retrievable through {@link Throwable#getMessage()}.
     *                     Permitted to be null or blank and passed through unchanged. It must not
     *                     contain a secret, a credential or a personally identifiable value
     * @param logicalFile  the logical file the write was aimed at, such as {@code USRSEC} or
     *                     {@code TRANSACT}. Permitted to be null or blank and stored unchanged;
     *                     retrievable through {@link #getLogicalFile()}
     * @param collidingKey the key value that already existed. Permitted to be null or blank and stored
     *                     unchanged; retrievable through {@link #getCollidingKey()}. Supply the key
     *                     alone and never the record, and supply a masked form where the key is itself
     *                     sensitive
     * @param cause        the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                     Permitted to be null, which records that there is no underlying failure to
     *                     attribute. When non null it is always retained
     */
    public DuplicateRecordException(String message, String logicalFile, String collidingKey,
            Throwable cause) {
        super(message, cause);
        this.logicalFile = logicalFile;
        this.collidingKey = collidingKey;
    }

    /**
     * Returns the logical file the colliding write was aimed at.
     *
     * <p>Side effects: none. The value is returned exactly as it was supplied to the constructor, with
     * no trimming, defaulting or case folding, so no locale is involved and repeated calls are
     * identical.
     *
     * @return the legacy logical file name, such as {@code USRSEC} or {@code TRANSACT}, or null when the
     *         throwing site did not identify one. A blank string is possible and means the caller
     *         supplied one; it is not normalised to null
     */
    public String getLogicalFile() {
        return logicalFile;
    }

    /**
     * Returns the key value that already existed.
     *
     * <p>Side effects: none. The value is returned exactly as it was supplied to the constructor.
     *
     * @return the colliding key, never the colliding record, or null when the throwing site did not
     *         supply it. A blank string is possible and is not normalised to null. Callers that log this
     *         value should be aware that a key can itself be sensitive, in which case the throwing site
     *         is required to have supplied a masked form
     */
    public String getCollidingKey() {
        return collidingKey;
    }
}
