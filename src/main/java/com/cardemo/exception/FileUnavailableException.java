/*
 * ******************************************************************
 * Program     : FileUnavailableException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of FILE STATUS '35' / DFHRESP(NOTOPEN) - dataset or
 *               resource unavailable.
 * Source      : app/cbl/CBTRN02C.cbl:L236-L252 @ 7756d89 - the universal OPEN guard whose
 *               failure branch this type serves.
 * Source      : app/jcl/OPENFIL.jcl @ 7756d89 and app/jcl/CLOSEFIL.jcl @ 7756d89 - the
 *               legacy online file-availability jobs.
 * Source      : app/csd/CARDDEMO.CSD @ 7756d89 - the eight-file online file control table.
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

import java.util.Optional;

/**
 * Reports that a backing store was not open, not defined or not reachable when a CardDemo
 * operation needed it.
 *
 * <p><strong>What it does.</strong> It is the typed target for {@code FILE STATUS '35'} and for
 * {@code DFHRESP(NOTOPEN)}: the store itself was unavailable, so the request never reached the
 * point of succeeding or failing on its own merits. In the Java target the same condition arises
 * from a datasource that cannot hand out a connection, a schema whose migrations have not been
 * applied, an object-storage bucket that does not exist, and a queue whose endpoint does not
 * answer. One type covers all four, because from a caller's point of view they are the same
 * fact - <em>the thing I depend on is not there</em> - and they call for the same operator
 * action.
 *
 * <p><strong>What it deliberately is not.</strong> This type <em>reports</em>. It never decides
 * and never repairs. It performs no retry, no reconnect, no probe and no circuit-breaking; it
 * implements no health contract and it does not log. Those responsibilities sit with the
 * observability tier, {@code com.cardemo.observability.HealthIndicators}, and letting them leak
 * in here would invert the dependency direction and give the readiness contract two homes. The
 * separation is three-way and each part has exactly one owner:
 * {@code com.cardemo.model.enums.FileStatus} <em>classifies</em> a raw status value,
 * {@code com.cardemo.service.shared.FileStatusMapper} <em>decides</em> which failure that value
 * represents, and this class is one of the typed targets that decision selects.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and, where a
 * line is meaningful, a line or line range. All of them are keyed to the traceability anchor
 * commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the
 * {@code Source} lines in the file header record; the anchor is stated once here rather than
 * repeated on each citation. Filename case is reproduced as it appears on disk, which is load
 * bearing: {@code app/csd/CARDDEMO.CSD} is uppercase, in {@code app/cbl} only
 * {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} use an uppercase extension, and {@code app/jcl}
 * holds 29 members of which {@code CREASTMT.JCL} alone uses an uppercase extension - so a
 * case-sensitive glob over that directory silently drops a whole feature. Every figure quoted
 * below was measured on the anchor commit by this file's author, and the measuring command is
 * given so that each figure is reproducible rather than merely asserted.
 *
 * <h2>Not available: this type has no literal antecedent in the frozen corpus</h2>
 *
 * <p><strong>Not available.</strong> The frozen COBOL corpus at {@code 7756d89} contains no
 * literal {@code FILE STATUS '35'} test - {@code grep -rn "'35'" app/cbl} matches
 * <strong>0 files</strong> - and no {@code DFHRESP(NOTOPEN)} reference anywhere under
 * {@code app/} - {@code grep -rn "NOTOPEN" app/} returns <strong>0 matches</strong>. This
 * class's mapping is therefore grounded architecturally rather than in a literal source test.
 *
 * <p>Both searches were run recursively over the whole directory, so both cover the uppercase
 * {@code .CBL} members as well as the lowercase ones, and the zero results are not an artefact
 * of the case-sensitivity trap described above. For contrast, the statuses that <em>are</em>
 * attested are attested plainly. Counted as comparison sites with
 * {@code grep -ohE "STATUS[ ]+=[ ]+'nn'" app/cbl/*.cbl} - a lowercase glob, quoted exactly as it
 * was run so that the figures reproduce, and the same one the base type's census used -
 * {@code '00'} occurs 73 times, {@code '10'} 7 times and {@code '23'} once, while {@code '22'}
 * and {@code '35'} occur 0 times. Counted as occurrences of the literal,
 * {@code DFHRESP(NORMAL)} occurs 43 times, {@code DFHRESP(NOTFND)} 23,
 * {@code DFHRESP(ENDFILE)} 8, {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and
 * {@code DFHRESP(NOTOPEN)} 0.
 *
 * <p>The {@code '35'} figure is the one that does not depend on the glob, which is what makes the
 * disclosure above safe to state so flatly: it is 0 under that lowercase glob and 0 under the
 * recursive search alike. The one status whose count <em>is</em> sensitive to the glob is
 * {@code '04'}, the secondary success of the statement file service, and it is sensitive precisely
 * because all nine of its test sites live in {@code app/cbl/CBSTM03A.CBL} - an uppercase member
 * the lowercase glob never reaches. That status is not this type's concern, but it is the concrete
 * demonstration that the trap is real rather than theoretical.
 *
 * <p><strong>Finding, severity Medium.</strong> The status value this type is named for is never
 * tested by name in the system of record, so the parity of its message text cannot be proved
 * against a legacy baseline the way a reject description or a screen message can. What would be
 * needed to ground it literally is any one of the following, none of which exists at the anchor
 * commit:
 *
 * <ul>
 *   <li>a {@code FILE STATUS '35'} comparison site in one of the 28 COBOL programs, which would
 *       fix the condition's own handling;</li>
 *   <li>a {@code DFHRESP(NOTOPEN)} handler in one of the 17 online programs, which would fix the
 *       screen message and the cursor behaviour that accompany it;</li>
 *   <li>an operational runbook documenting the closed-dataset condition and the expected
 *       response to it, which would fix the operator-facing wording.</li>
 * </ul>
 *
 * <p><strong>Remediation.</strong> Keep the type, for the three reasons set out next, but treat
 * its message text as newly authored rather than translated: assert its behaviour, not its
 * wording, and record it in {@code DECISION_LOG.md} as an architecturally grounded addition
 * rather than presenting it as a migrated behaviour. The base type
 * {@link CardDemoException} already carries this same finding at the same severity; the evidence
 * below narrows it but does not retire it, because the literal token is still absent.
 *
 * <h2>Why the type is nevertheless required</h2>
 *
 * <p>Three independent groundings, each verifiable at the anchor commit.
 *
 * <p><strong>One - the universal OPEN guard is binary, so the condition is reachable without
 * being named.</strong> Every I/O verb in the batch corpus is wrapped in one idiom, stamped out
 * mechanically. Verbatim from {@code app/cbl/CBTRN02C.cbl:L236-L252}:
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
 * <p>The test is <em>binary</em>. There is one comparison, against {@code '00'}, and a single
 * {@code ELSE} that everything else falls into. Measured with
 * {@code grep -c 'IF  APPL-AOK' app/cbl/CBTRN02C.cbl}, that guard is evaluated at
 * <strong>18 sites in this one program</strong> - L244, L262, L281, L299, L317, L335, L357,
 * L457, L486, L517, L535, L571, L590, L608, L627, L645, L663 and L682 - covering every open,
 * read, write, rewrite and close it performs. So an unavailable file is <em>reachable at
 * runtime</em> at all 18 of them even though no program names it: the source simply lumps it in
 * with everything that is not {@code '00'} and abends.
 *
 * <p>That is the honest reading, and it is precisely why a distinguishable type is warranted.
 * The legacy behaviour of routing every non-success status to one abend is not a statement that
 * the conditions are equivalent; it is the absence of any means to distinguish them. Rule 1
 * Clause A asks for meaningful errors, and an unavailable store is the one failure in this
 * taxonomy that an operator can actually fix - by starting a service, restoring a dataset or
 * applying a migration - so collapsing it into the fatal case would discard the only actionable
 * signal in the set.
 *
 * <p><strong>Two - the closed state is the designed initial state of every online file.</strong>
 * All eight file definitions in {@code app/csd/CARDDEMO.CSD} carry
 * {@code STATUS(ENABLED) OPENTIME(FIRSTREF)}, at L4, L16, L28, L41, L54, L67, L79 and L91
 * respectively. {@code OPENTIME(FIRSTREF)} means the file is <em>not</em> open when the region
 * starts; it is opened implicitly on first reference. Every one of those eight files therefore
 * begins the region's life closed, and the first request against it is the one that discovers
 * whether the underlying dataset can be opened at all. A deferred open that fails yields exactly
 * the condition this type names.
 *
 * <p><strong>Three - the legacy system had operator commands and dedicated jobs for closing
 * files, so the closed window is designed rather than hypothetical.</strong>
 * {@code app/jcl/OPENFIL.jcl:L26-L30} and {@code app/jcl/CLOSEFIL.jcl:L26-L30} exist for no
 * other purpose than to make datasets available to, and unavailable from, the online region,
 * each issuing five {@code CEMT SET FIL(...) OPE} or {@code CEMT SET FIL(...) CLO} commands
 * against {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and
 * {@code USRSEC}. They are not alone: measured with
 * {@code grep -rho "CEMT SET FIL(.*) CLO" app/jcl}, the corpus issues 10 close commands across
 * 4 members and, symmetrically, 10 open commands across 4 members. The other three members
 * bracket a cluster reload with a close and a reopen -
 * {@code app/jcl/CARDFILE.jcl:L26-L27} then {@code :L122-L123},
 * {@code app/jcl/TRANFILE.jcl:L26-L27} then {@code :L120-L121}, and
 * {@code app/jcl/CUSTFILE.jcl:L26} then {@code :L80}. In other words the legacy operating
 * procedure <em>deliberately</em> takes online files out of service while their datasets are
 * rebuilt, which makes a transaction arriving inside that window an anticipated event rather
 * than an unforeseen one.
 *
 * <p>The same file that supplies this grounding also fixes the scope of the online store. Those
 * eight definitions - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF},
 * {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - are the complete
 * online file control table, which is also the evidence that {@code TCATBALF}, {@code DISCGRP},
 * {@code TRANCATG} and {@code TRANTYPE} are batch-only datasets: each of those four occurs 0
 * times in {@code app/csd/CARDDEMO.CSD}. That asymmetry matters when this exception is being
 * diagnosed, because an unavailable batch-only store can never have been caused by an online
 * request.
 *
 * <p>Neither {@code app/jcl/OPENFIL.jcl} nor {@code app/jcl/CLOSEFIL.jcl} has a direct Java
 * analogue - there is no region whose files can be opened - and both are documented as such.
 * Their intent survives as the readiness and liveness checks of the observability tier, and this
 * type is the shared failure vocabulary those checks and the repositories express it in. One
 * incidental legacy defect is worth recording while citing them, severity Low, logged rather
 * than repaired because {@code app/} is frozen: the job name on {@code app/jcl/OPENFIL.jcl:L1}
 * is spelled {@code OEPNFIL}, two characters transposed, while the member itself is
 * {@code OPENFIL.jcl}.
 *
 * <h2>Position in the status-to-exception map</h2>
 *
 * <p>This class owns exactly one row. The map as a whole belongs to
 * {@code com.cardemo.service.shared.FileStatusMapper}; it is reproduced here only so that a
 * reader of this row can see what the neighbouring rows do, and no part of it is implemented by
 * this class.
 *
 * <table>
 *   <caption>COBOL FILE STATUS to Java exception mapping, with this class's row marked</caption>
 *   <thead>
 *     <tr><th>FILE STATUS</th><th>Meaning</th><th>Target</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@code '00'} / {@code '04'}</td>
 *       <td>Success, and the secondary success the statement file service also accepts</td>
 *       <td>Continue; nothing is thrown</td>
 *     </tr>
 *     <tr>
 *       <td>{@code '10'}</td>
 *       <td>End of file</td>
 *       <td><strong>Loop termination, not an error.</strong> Never thrown, by any type</td>
 *     </tr>
 *     <tr>
 *       <td>{@code '23'}</td>
 *       <td>Record not found</td>
 *       <td>{@link RecordNotFoundException}, except at the scoped sites where a missing record
 *           is an accepted control path</td>
 *     </tr>
 *     <tr>
 *       <td>{@code '22'}</td>
 *       <td>Duplicate key</td>
 *       <td>{@link DuplicateRecordException}</td>
 *     </tr>
 *     <tr>
 *       <td><strong>{@code '35'}</strong></td>
 *       <td><strong>File unavailable</strong></td>
 *       <td><strong>This class.</strong> A documented, architecturally grounded row rather than
 *           a literally attested one - see the disclosure above</td>
 *     </tr>
 *     <tr>
 *       <td>{@code '9x'}</td>
 *       <td>Physical or logical I/O error</td>
 *       <td>{@link FileAccessException}, carrying the four-character expanded status</td>
 *     </tr>
 *     <tr>
 *       <td>anything else</td>
 *       <td>Unexpected</td>
 *       <td>{@link FatalProcessingException}, abend code 999, return code 12</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>Two rows are easy to get wrong and both are called out deliberately. End of file is
 * <em>not</em> a failure: the corpus drives every sequential loop off it, so a type that threw on
 * it would abort every batch job at the last record. And the single {@code '23'} comparison site
 * in the whole corpus is an accepted control path rather than an error, which is why the decision
 * belongs to the central mapper and never to a constructor here.
 *
 * <p>The four-character {@code IO-STATUS-04} rendering and the diagnostic literal that
 * accompanies it are deliberately absent from this class. They belong to
 * {@code com.cardemo.model.enums.FileStatus}, whose {@code FILE_UNAVAILABLE} constant carries the
 * {@code '35'} value this type corresponds to, and to {@link FileAccessException} for the
 * {@code '9x'} family. Restating either here would create a second source of truth for output
 * that has to stay byte identical to the legacy diagnostic, and Rule 1 Clause C forbids that
 * duplication. This class holds no reference to the enum at all - the correspondence is by
 * documentation, so the dependency stays one-way and the two packages remain independent.
 *
 * <h2>What may be carried, and the security contract on it</h2>
 *
 * <p>An instance optionally carries one piece of state: the <em>logical</em> name of the
 * unavailable resource. On the legacy side that is a DD name or a CICS file name - one of
 * {@code DALYTRAN}, {@code ACCTDAT}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT},
 * {@code TRANSACT}, {@code USRSEC}, {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG},
 * {@code TRANTYPE}, {@code STMTFILE} or {@code HTMLFILE}. On the Java side it is a table name, a
 * bucket name or a queue name. Nothing else is carried: no client handle, no configuration
 * object, no credential and no privileged reference of any kind, which is Rule 1 Clause D's
 * least-privilege requirement applied to a failure report.
 *
 * <p><strong>Finding, severity Blocker, if violated.</strong> This is the one exception type
 * whose natural phrasing invites a leak. A message written as "could not connect to" followed by
 * a resolved target routinely embeds a database connection string or a service endpoint, and
 * those frequently carry user information, an access key or a password in the locator itself.
 * Rule 1 Clause D forbids secrets in code and in logs, and exception messages are logged.
 * Therefore, in both the message and the resource name:
 *
 * <ul>
 *   <li>carry the <em>logical</em> resource name only - {@code ACCTDAT}, or the bucket's own
 *       name - never a connection string, never a URI, never a host and port, never anything
 *       with user information embedded in it, and never an access key or token;</li>
 *   <li>identify a failing record by its key and never by its content, so that no password or
 *       password hash, no social security number, no card number, no telephone number, no
 *       government-issued identifier, no date of birth and no electronic funds account
 *       identifier can reach a log through this type;</li>
 *   <li>let the cause carry the transport detail. The underlying driver or client exception
 *       already contains whatever the operator needs, it is preserved unchanged, and it is
 *       subject to the log masking configured for the application rather than being re-emitted
 *       as fresh plain text in a CardDemo message.</li>
 * </ul>
 *
 * <p><strong>Finding, severity High, if violated.</strong> Dropping the cause. For this type the
 * cause is not decoration - a connection refusal, a missing bucket, an unreachable queue
 * endpoint or a failed datasource initialisation is the <em>only</em> actionable diagnostic, and
 * the resource name alone cannot tell an operator which of them happened. Remediation: inside a
 * {@code catch}, always use a constructor that takes a cause.
 *
 * <h2>Null, blank and normalisation behaviour</h2>
 *
 * <p>Stated explicitly rather than left implicit, as Rule 1 Clause B requires.
 *
 * <ul>
 *   <li><strong>Message and cause follow the hierarchy-wide policy of
 *       {@link CardDemoException} unchanged:</strong> a null or blank message and a null cause
 *       are permitted and passed straight through. No argument is rejected and none is
 *       substituted. Throwing from the constructor of a failure type would replace the real
 *       failure with a spurious argument failure at the exact moment the real one is being
 *       reported.</li>
 *   <li><strong>The resource name has exactly one absent representation.</strong> A null, empty
 *       or entirely whitespace name is stored as null, so {@link #resourceName()} answers
 *       {@link Optional#empty()} for all of them and a caller never has to test for blankness on
 *       top of presence.</li>
 *   <li><strong>A present resource name is stripped of leading and trailing whitespace, and its
 *       case is left exactly as supplied.</strong> Stripping is there because names lifted from
 *       the legacy side arrive space padded out of fixed-width fields:
 *       {@code app/jcl/OPENFIL.jcl:L26} reads {@code CEMT SET FIL(TRANSACT ) OPE}, trailing
 *       space and all. Case is left alone deliberately, because the two families of resource name
 *       disagree - legacy DD and CICS names are uppercase while object-storage bucket names are
 *       lowercase - so normalising in either direction would corrupt one of them. No case
 *       conversion means no locale is consulted, which is how this class satisfies Clause C's
 *       ban on environment-specific assumptions rather than by pinning one.</li>
 * </ul>
 *
 * <p>Nothing here reads a clock, a locale, a character set, a time zone, an environment variable
 * or a system property, and there is no default host, port or path anywhere in the class.
 * Construction is a field assignment and a whitespace test; it performs no probe and opens no
 * connection, so it is cheap enough to use on a failure path and deterministic enough to assert
 * on in a unit test.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <p>When this exception is seen in a log, the store named by {@link #resourceName()} - or, if
 * that is empty, the store implied by the message and the stack frame that threw - is the place
 * to look. In order of how often each is the answer:
 *
 * <ul>
 *   <li><em>The database is up but every request fails.</em> The schema is probably empty or
 *       partially migrated. Check that the migrations have been applied and that none is pending
 *       or failed; a table that does not exist yet presents exactly as an unavailable store.</li>
 *   <li><em>The database is not reachable at all.</em> Check that the database service is
 *       running, that the connection pool is not exhausted by leaked connections, and that the
 *       credentials the active profile resolves are the ones the database expects. Read the
 *       cause: the driver names which of these it was.</li>
 *   <li><em>An object-storage operation fails while the database is healthy.</em> The bucket is
 *       probably missing. Buckets are provisioned by an initialisation script that is expected to
 *       be idempotent and to be re-run on every start of the local stack, and the emulated
 *       environment does not retain state across restarts, so a restart without re-provisioning
 *       produces this precisely.</li>
 *   <li><em>A queue publish or receive fails.</em> Check that the queue exists and that its name
 *       matches the configured one exactly, including any suffix the queue type requires.</li>
 *   <li><em>Everything is reachable from a shell but not from the application.</em> Suspect
 *       configuration rather than infrastructure: the wrong profile is active, or a required
 *       environment variable is unset and the fail-fast on it is what surfaced here.</li>
 *   <li><em>{@link Throwable#getCause()} is null.</em> The throwing site used a constructor
 *       without a cause inside a {@code catch}, which is the swallowing Clause B forbids. Fix the
 *       throwing site; this class never drops a cause it was handed.</li>
 *   <li><em>{@link #resourceName()} is empty.</em> Permitted, and not a defect in this class -
 *       the throwing site used a constructor that does not take a resource name, or passed one
 *       that was blank. Prefer the three-argument form wherever the resource is known, because it
 *       is what makes the log actionable without parsing the message.</li>
 * </ul>
 *
 * <p>What this type never means: it does not mean a record was missing, which is
 * {@link RecordNotFoundException}; it does not mean the read or write itself failed against an
 * available store, which is {@link FileAccessException}; and it does not mean end of file, which
 * is not an exception at all.
 *
 * <h2>Dependency direction and testing</h2>
 *
 * <p>This class depends on {@code java.lang}, on {@link Optional} and on its own superclass.
 * There is no framework type, no third-party library, no annotation and no logger; the
 * observability tier that consumes this type is not referenced by it, and neither is the enum
 * package. Instances are immutable, hold no static state and are safe to publish across threads.
 *
 * <p>Tests live under {@code src/test/java/com/cardemo/unit} and not in this package. Because
 * this type has the thinnest literal grounding in the hierarchy it is the easiest to leave
 * under-exercised, and the coverage gate admits no exclusions, so every constructor and the
 * accessor are expected to be genuinely tested - including cause preservation, the single absent
 * representation of a blank resource name, and the fact that no transport locator can be
 * smuggled in through the resource name.
 *
 * @see CardDemoException
 * @see FileAccessException
 * @see RecordNotFoundException
 */
public class FileUnavailableException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements
     * {@link java.io.Serializable}, so every type in this hierarchy is unavoidably serializable
     * and must pin this value explicitly: the build runs {@code -Xlint:all} with {@code -Werror}
     * and {@code failOnWarning}, which turns the {@code serial} lint into a compilation failure.
     * A fixed literal is used rather than a computed default so that the identity does not shift
     * when the class is edited. No custom object-stream hook, serialization proxy, instance
     * replacement method or alternative externalization contract is declared anywhere in this
     * class, and no instance of it is ever reconstructed from untrusted input - Rule 1 Clause D
     * names insecure deserialization as a pattern to flag, and the only concession made to
     * serialization here is this constant.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logical name of the unavailable resource, or null when the throwing site did not identify
     * one.
     *
     * <p>Normalised on construction so that null, empty and whitespace-only inputs all collapse
     * to null, giving {@link #resourceName()} a single representation of absence. A present value
     * is stripped of surrounding whitespace with its case untouched. It is a {@link String}
     * rather than the classifying enum because the same field has to name a legacy DD name and a
     * Java-side table, bucket or queue, and no closed set spans both.
     *
     * <p>By contract this holds a logical name only - never a connection string, a URI, a host
     * and port, a locator carrying user information, a credential or a token.
     */
    private final String resourceName;

    /**
     * Reports an unavailable resource without naming it and without an underlying throwable.
     *
     * <p>The weakest of the three forms and the one to reach for last. Use it only when the
     * unavailability is established by CardDemo's own logic rather than by a failure from a
     * driver or client - a precondition check that found a required target absent, for instance -
     * so that there is genuinely no cause to preserve. Inside a {@code catch} block use
     * {@link #FileUnavailableException(String, String, Throwable)} instead; discarding the caught
     * throwable is the swallowing Rule 1 Clause B forbids, and for this type the caught throwable
     * is the only thing that says <em>why</em> the resource was unavailable.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is
     * recorded, no connection is attempted, no configuration is read and no state outside this
     * instance is touched.
     *
     * <p>Error modes: none. This constructor cannot fail on its arguments; see the parameter note
     * for the null and blank policy.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It
     *                is permitted to be null or blank and is passed through unchanged, exactly as
     *                the hierarchy-wide policy of {@link CardDemoException} specifies. It must
     *                name the resource logically and must not contain a connection string, a URI,
     *                a credential or any personally identifiable value, because exception
     *                messages are logged
     */
    public FileUnavailableException(String message) {
        super(message);
        this.resourceName = null;
    }

    /**
     * Reports an unavailable resource, preserving the throwable that revealed it but without
     * naming the resource.
     *
     * <p>Correct inside a {@code catch} when the resource's logical name is not available at the
     * catch site. Where it is available, prefer
     * {@link #FileUnavailableException(String, String, Throwable)}: naming the resource is what
     * lets an operator act on the log line without parsing prose out of the message.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected,
     * unwrapped, re-thrown nor logged; it is delegated to {@link CardDemoException} exactly as
     * supplied, so {@link Throwable#getCause()} returns it unchanged.
     *
     * <p>Error modes: none. This constructor cannot fail on its arguments.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It
     *                is permitted to be null or blank and is passed through unchanged. It must
     *                not contain a connection string, a URI, a credential or any personally
     *                identifiable value
     * @param cause   the underlying throwable - typically a connection failure, a missing bucket,
     *                an unreachable queue endpoint or a failed datasource initialisation -
     *                retrievable through {@link Throwable#getCause()}. It is permitted to be
     *                null, which records that there is no underlying failure to attribute; when
     *                non-null it is always retained, because this hierarchy never drops a cause
     *                it was handed
     */
    public FileUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.resourceName = null;
    }

    /**
     * Reports an unavailable resource, naming it and preserving the throwable that revealed it.
     *
     * <p>The preferred form. It produces the one log line an operator can act on directly: which
     * store was unavailable, and what the store itself said about why.
     *
     * <p>There is deliberately no two-argument {@code (String, String)} companion to this
     * constructor. It would be indistinguishable from
     * {@link #FileUnavailableException(String, Throwable)} whenever a caller passed a bare
     * {@code null} as the second argument, since neither {@link String} nor {@link Throwable} is
     * more specific than the other, and the call would simply fail to compile as ambiguous. To
     * name a resource when there is no cause to attribute, call this constructor with an explicit
     * null third argument. Requiring that is the explicit behaviour Rule 1 Clause A asks for, in
     * preference to an overload set that reads well until the first null reaches it.
     *
     * <p>One consequence of the {@link Throwable} contract is worth stating, because it is
     * invisible at the call site. Passing a null cause here is not identical to using
     * {@link #FileUnavailableException(String)}: this constructor initialises the cause to null
     * definitively, so a later {@link Throwable#initCause(Throwable)} on the instance throws
     * {@link IllegalStateException}, whereas the message-only constructor leaves the cause
     * uninitialised and a later {@code initCause} succeeds. Both report null from
     * {@link Throwable#getCause()}. The difference only matters to code that attaches a cause
     * after construction, which CardDemo never does, but it is why the message-only constructor
     * is not implemented by delegating here.
     *
     * <p>Side effects: none beyond throwable construction. No connection is attempted, no health
     * check is run, nothing is logged and nothing is retried - this type reports and never
     * repairs.
     *
     * <p>Error modes: none. Every argument is accepted; the resource name is normalised rather
     * than validated, as described below.
     *
     * @param message      the detail message, retrievable through {@link Throwable#getMessage()}.
     *                     It is permitted to be null or blank and is passed through unchanged. It
     *                     must not contain a connection string, a URI, a credential or any
     *                     personally identifiable value
     * @param resourceName the logical name of the unavailable resource - a legacy DD or CICS file
     *                     name such as {@code ACCTDAT}, or a Java-side table, bucket or queue
     *                     name. It is permitted to be null, empty or entirely whitespace, and all
     *                     three are stored as absent so that {@link #resourceName()} answers
     *                     {@link Optional#empty()}. A present value is stripped of surrounding
     *                     whitespace, because legacy names arrive space padded out of fixed-width
     *                     fields, and its case is preserved exactly as supplied, because DD names
     *                     are uppercase while bucket names are lowercase and normalising either
     *                     way would corrupt the other. It must be a logical name only - never a
     *                     connection string, a URI, a host and port, a locator carrying user
     *                     information, a credential or a token
     * @param cause        the underlying throwable, retrievable through
     *                     {@link Throwable#getCause()}. It is permitted to be null, which records
     *                     that there is no underlying failure to attribute; when non-null it is
     *                     always retained
     */
    public FileUnavailableException(String message, String resourceName, Throwable cause) {
        super(message, cause);
        this.resourceName = normaliseResourceName(resourceName);
    }

    /**
     * Returns the logical name of the unavailable resource, if the throwing site supplied one.
     *
     * <p>This is the whole state this type adds to {@link CardDemoException}, and it exists so
     * that a caller can route, count or report on the failure without parsing the message. An
     * empty result is a normal outcome and never an error: it means the throwing site used a
     * constructor that does not take a resource name.
     *
     * <p>Side effects: none. The accessor is a pure read - it consults no configuration, contacts
     * nothing and never triggers a health check or a reconnection attempt.
     *
     * <p>Error modes: none. It never throws and never returns null.
     *
     * @return the stripped, case-preserved logical resource name, or {@link Optional#empty()} if
     *         none was supplied or the supplied value was blank. By contract the value is a
     *         logical name only, so it is safe to log as-is; it is never a connection string, a
     *         URI, a credential or a token
     */
    public Optional<String> resourceName() {
        return Optional.ofNullable(this.resourceName);
    }

    /**
     * Collapses a supplied resource name onto a single canonical representation.
     *
     * <p>Null, empty and whitespace-only inputs all become null, which is what gives
     * {@link #resourceName()} exactly one way to say "absent" instead of three. Any other input
     * is stripped of leading and trailing whitespace and otherwise returned untouched -
     * specifically, its case is not changed.
     *
     * <p>Both operations used here are defined over Unicode code points rather than over a
     * locale, so the result is identical on every platform and under every default locale,
     * charset and time zone. That is deliberate: Rule 1 Clause C forbids environment-specific
     * assumptions, and the way this class satisfies that is by having no locale-sensitive
     * operation to configure in the first place.
     *
     * @param resourceName the value supplied by the throwing site; may be null, empty or blank
     * @return the stripped value, or null if the input was null or contained no non-whitespace
     *         character
     */
    private static String normaliseResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        return resourceName.strip();
    }
}
