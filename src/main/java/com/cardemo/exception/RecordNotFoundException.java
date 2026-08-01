/*
 * ******************************************************************
 * Program     : RecordNotFoundException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed translation of FILE STATUS '23' / DFHRESP(NOTFND) - record not found.
 * Source      : app/cbl/CBTRN02C.cbl:L481 @ 7756d89 - the TCATBAL upsert read guard
 *               IF TCATBALF-STATUS = '00' OR '23', where a missing row is an
 *               accepted control path and NOT this exception.
 * Source      : app/cbl/CBACT04C.cbl:L422,L436 @ 7756d89 - the disclosure group
 *               default rate fallback, whose first lookup accepts a missing row
 *               and whose retry does not.
 * Source      : app/cbl/COACTUPC.cbl:L3668,L3716,L3766 @ 7756d89 - three
 *               WHEN DFHRESP(NOTFND) arms, representative of the 23 online sites
 *               that are the real antecedent of this type.
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
 * Signals that a keyed read found no record where the caller required one.
 *
 * <p><strong>What it does.</strong> It is the <em>error</em> reading of two legacy conditions: the
 * COBOL {@code FILE STATUS} value {@code '23'} on a keyed read, and the CICS response
 * {@code DFHRESP(NOTFND)} on an {@code EXEC CICS READ}. Neither legacy mechanism propagates a value
 * to a caller; the batch corpus displays a diagnostic and abends, and the online corpus sets an
 * input-error flag and repaints the screen. This type gives that outcome a name, so a caller can
 * distinguish "the row you asked for does not exist" from every other way a read can fail.
 *
 * <p><strong>What it is not.</strong> Not-found is <em>conditionally</em> an error, and this is the
 * most nuanced translation in the package for exactly that reason. There are three verified sites in
 * the frozen corpus where a non-{@code '00'} status is <em>success</em>, and a blanket
 * "not found implies throw" rule would abend all three working paths. Those sites are enumerated
 * below and this exception must not be raised at any of them.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line in the
 * frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the
 * {@code Source} lines in the file header record. The anchor is stated once here rather than
 * repeated on each citation. Filename case is reproduced as it appears on disk, which is not uniform:
 * of the 28 members of {@code app/cbl} only {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} carry an
 * uppercase extension and the other 26 are lowercase, and in {@code app/cpy} only
 * {@code COSTM01.CPY} is uppercase. Every figure quoted below was measured against the anchor rather
 * than inherited, and where a measurement contradicted an inherited figure the measurement is
 * published together with its method so that it is reproducible.
 *
 * <h2>The one idiom behind the decision</h2>
 *
 * <p>The batch corpus does not contain hundreds of bespoke status checks. It contains <em>one</em>
 * idiom, stamped out mechanically, whose work areas are declared at
 * {@code app/cbl/CBTRN02C.cbl:L131-L144}: a two byte {@code IO-STATUS} split into {@code IO-STAT1}
 * and {@code IO-STAT2}, a {@code TWO-BYTES-BINARY} redefined as {@code TWO-BYTES-ALPHA}, a four
 * character {@code IO-STATUS-04} render target, and a signed binary {@code APPL-RESULT} carrying the
 * condition names {@code APPL-AOK VALUE 0} (L143) and {@code APPL-EOF VALUE 16} (L144). The end of
 * file condition name is <em>sixteen</em>, not twelve; the {@code 8} and {@code 12} the idiom moves
 * into {@code APPL-RESULT} are working values distinct from those condition names.
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
 * <p>That block recurs with only the file name and the verb changed. Measured with
 * {@code grep -n 'APPL-AOK' app/cbl/CBTRN02C.cbl}, which returns 19 lines, one of which is the L143
 * declaration itself, the condition is <strong>evaluated at 18 sites in this one program</strong> -
 * L244, L262, L281, L299, L317, L335, L357, L457, L486, L517, L535, L571, L590, L608, L627, L645,
 * L663 and L682 - covering every open, read, write, rewrite and close it performs. Recognising it as
 * one idiom rather than 18 unrelated checks is the whole argument for a single central translation,
 * and for this type being one of its typed targets rather than a per call site invention.
 *
 * <h2>Where this type sits in the status map</h2>
 *
 * <p>This type owns exactly one row. The rest of the map is listed so that the boundaries are
 * visible, because the rows immediately around it are the ones most often confused with it:
 *
 * <table>
 *   <caption>COBOL {@code FILE STATUS} to typed target, as translated by this package</caption>
 *   <thead>
 *     <tr><th>{@code FILE STATUS}</th><th>Meaning</th><th>Target</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code '00'}</td><td>Success</td><td>Continue - nothing is thrown</td></tr>
 *     <tr><td>{@code '04'}</td>
 *         <td>Secondary success, file service only</td>
 *         <td>Continue - nothing is thrown</td></tr>
 *     <tr><td>{@code '10'}</td><td>End of file</td>
 *         <td><strong>Loop termination, never an error and never a throw</strong></td></tr>
 *     <tr><td><strong>{@code '23'}</strong></td><td><strong>Record not found</strong></td>
 *         <td><strong>This type</strong> - except at the three scoped sites below</td></tr>
 *     <tr><td>{@code '22'}</td><td>Duplicate key</td><td>{@code DuplicateRecordException}</td></tr>
 *     <tr><td>{@code '35'}</td><td>File unavailable</td><td>{@code FileUnavailableException}</td></tr>
 *     <tr><td>{@code '9x'}</td><td>Physical or logical I/O error</td>
 *         <td>{@code FileAccessException}, carrying the four character expanded status</td></tr>
 *     <tr><td>anything else</td><td>Unexpected</td>
 *         <td>{@code FatalProcessingException}, abend code 999, return code 12</td></tr>
 *   </tbody>
 * </table>
 *
 * <p><strong>The {@code '10'} row is the mistranslation that does the most damage.</strong> End of
 * file is how every sequential loop in the corpus terminates normally - the corpus reads until
 * {@code '10'} and then closes - so mapping it onto a not-found failure turns every successful batch
 * run into an abend. It is not a near miss of {@code '23'} and it is not this type. Nothing is
 * thrown for it at all.
 *
 * <h2>Evidence: what actually justifies this type</h2>
 *
 * <p>Measured across {@code app/cbl} at the anchor. Comparison sites were counted with
 * {@code grep -ohE "STATUS[A-Z-]* +=? *'nn'" app/cbl/*}, which matches a status field compared
 * directly against a literal; response codes were counted as occurrences of the literal
 * {@code DFHRESP(name)}:
 *
 * <ul>
 *   <li>{@code FILE STATUS} comparison sites - {@code '00'} 73, {@code '10'} 7, {@code '23'} 1,
 *       {@code '22'} 0, {@code '35'} 0.</li>
 *   <li>{@code EXEC CICS} response codes - {@code DFHRESP(NORMAL)} 43,
 *       <strong>{@code DFHRESP(NOTFND)} 23</strong>, {@code DFHRESP(ENDFILE)} 8,
 *       {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3, {@code DFHRESP(NOTOPEN)} 0.</li>
 * </ul>
 *
 * <p>The 23 {@code DFHRESP(NOTFND)} sites are spread across 11 of the 17 online programs -
 * {@code COACTUPC.cbl} 3, {@code COACTVWC.cbl} 3, {@code COBIL00C.cbl} 4, {@code COCRDSLC.cbl} 2,
 * {@code COCRDUPC.cbl} 1, {@code COTRN00C.cbl} 1, {@code COTRN01C.cbl} 1, {@code COTRN02C.cbl} 3,
 * {@code COUSR00C.cbl} 1, {@code COUSR02C.cbl} 2 and {@code COUSR03C.cbl} 2. That makes not found
 * the most frequently handled failure in the whole corpus, and this the busiest type in the package.
 *
 * <p><strong>Finding, severity Medium - the literal {@code '23'} tests are not the antecedent of
 * this type.</strong> The comparison-site count of 1 above is an artefact of the counting pattern,
 * which only matches a literal in the first comparand position. Counting occurrences of the literal
 * instead, with {@code grep -c "'23'" app/cbl/*}, returns <strong>three</strong> sites:
 * {@code app/cbl/CBTRN02C.cbl:L481}, {@code app/cbl/CBACT04C.cbl:L422} and
 * {@code app/cbl/CBACT04C.cbl:L436}. Every one of the three is an accepted control path, detailed
 * below; not one of them is an error. <strong>The batch corpus therefore never treats a literal
 * {@code '23'} as a failure, and the entire error-reading antecedent of this type is the 23 online
 * {@code DFHRESP(NOTFND)} sites.</strong> Remediation: keep the {@code '23'} row in the map above,
 * because a keyed read against the migrated store genuinely can find nothing on paths the source
 * reaches by other means, but do not claim batch parity for it against a legacy baseline, and record
 * the asymmetry in {@code DECISION_LOG.md} rather than presenting it as a translated batch
 * behaviour.
 *
 * <h2>The three scoped sites where not found is success</h2>
 *
 * <p><strong>Finding, severity Blocker.</strong> Mapping not found onto this exception
 * unconditionally abends three working legacy paths. Each is transcribed here with its locators so
 * that the boundary is checkable rather than remembered. The leniency is owned by
 * {@code com.cardemo.service.shared.FileStatusMapper}, which is the only component permitted to
 * decide; this type is only ever the target of that decision.
 *
 * <p><strong>Site 1 - the category balance upsert.</strong>
 * {@code app/cbl/CBTRN02C.cbl:L467-L501}, paragraph {@code 2700-UPDATE-TCATBAL}. It presets
 * {@code MOVE 'N' TO WS-CREATE-TRANCAT-REC} (L473), reads with
 * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} (L474) under an {@code INVALID KEY} arm (L475)
 * that displays {@code 'TCATBAL record not found for key : '} with the key and the trailing
 * {@code '.. Creating.'} (L476-L477) and flips the flag to {@code 'Y'} (L478). The guard then
 * accepts {@code IF TCATBALF-STATUS = '00' OR '23'} (L481) before dispatching to
 * {@code 2700-A-CREATE-TCATBAL-REC} (L503) or {@code 2700-B-UPDATE-TCATBAL-REC} (L526). Both
 * branches add the transaction amount to the category balance, at L508 and L527 respectively, so a
 * missing row is not merely tolerated - it is the create half of an upsert.
 *
 * <p><strong>The leniency at site 1 is scoped to the read guard alone.</strong> The subsequent
 * {@code WRITE} (L510) is guarded at L512 and the {@code REWRITE} (L528) at L530, and both accept
 * {@code '00'} and nothing else, falling into the abend on anything else. Widening the read's
 * leniency to either write verb is a parity violation: it would swallow a genuine write failure that
 * the source terminates on.
 *
 * <p><strong>Site 2 - the disclosure group default rate fallback, and its inversion.</strong>
 * {@code app/cbl/CBACT04C.cbl:L415-L460}. {@code 1200-GET-INTEREST-RATE} (L415) reads the group
 * (L416) under an {@code INVALID KEY} arm (L417) that displays
 * {@code 'DISCLOSURE GROUP RECORD MISSING'} (L418) and {@code 'TRY WITH DEFAULT GROUP CODE'} (L419).
 * The guard accepts {@code IF DISCGRP-STATUS = '00' OR '23'} (L422); anything else displays
 * {@code 'ERROR READING DISCLOSURE GROUP FILE'} (L431) and abends (L434). Then
 * {@code IF DISCGRP-STATUS = '23'} (L436) substitutes the literal group identifier
 * {@code 'DEFAULT'} (L437) and performs the retry (L438).
 *
 * <p>The retry, {@code 1200-A-GET-DEFAULT-INT-RATE} (L443), <strong>has no {@code INVALID KEY} arm
 * at all</strong> - its read stands alone at L444 - and its guard at L446 accepts {@code '00'} and
 * nothing else, displaying {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} (L455) and abending
 * (L458) otherwise.
 *
 * <p><strong>Finding, severity High - the inversion.</strong> A missing default row therefore
 * <em>terminates the job</em>. The same status, on the same file, two reads apart, means opposite
 * things: not found on the first lookup is a control path that must not throw, and not found on the
 * retry is fatal and must raise {@code FatalProcessingException}, never this type. Getting the two
 * the wrong way round is the specific error a naive implementation makes, and it is invisible until
 * the seed data is missing a default row. Remediation: implement the fallback as two distinct
 * lookups with two distinct outcomes rather than one retried lookup with one shared handler.
 *
 * <p><strong>Site 3 - the file service secondary success.</strong> {@code app/cbl/CBSTM03A.CBL},
 * which with {@code CBSTM03B.CBL} is one of the only two uppercase members of {@code app/cbl}. Its
 * shared call area declares {@code WS-M03B-RC PIC X(02)} at L80. Nine sites accept
 * {@code IF WS-M03B-RC = '00' OR '04'} - L736, L748, L771, L789, L807, L862, L879, L895 and L911 -
 * covering the opens, the closes and the initial reads, so {@code '04'} is a second success value
 * there and not a failure. Four further sites, {@code EVALUATE WS-M03B-RC} at L353, L379, L403 and
 * L837, accept {@code '00'} only, treat {@code '10'} as end of file and abend on anything else. This
 * type is not raised for {@code '04'} anywhere.
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <ul>
 *   <li><strong>No reject code, and this is the type most likely to be given one by mistake.</strong>
 *       Two of the five batch reject codes are literally described as not found: 101 and 109 both
 *       carry the description {@code 'ACCOUNT RECORD NOT FOUND'}. Neither is ever raised. Both are
 *       moved into a <em>data</em> field, {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} declared at
 *       {@code app/cbl/CBTRN02C.cbl:L181} - 101 at L397 under the {@code INVALID KEY} arm of the
 *       account read in {@code 1500-B-LOOKUP-ACCT}, and 109 at L556 under the {@code INVALID KEY}
 *       arm of the account rewrite. The field is cleared per iteration at L208-L209, and it drives
 *       an exit status rather than control flow: {@code IF WS-REJECT-COUNT &gt; 0} then
 *       {@code MOVE 4 TO RETURN-CODE} at L229-L231, which is the sole determinant of return code 4.
 *       Reject codes are business outcomes written to a 430 byte reject record -
 *       {@code REJECT-TRAN-DATA PIC X(350)} at L177 plus {@code VALIDATION-TRAILER PIC X(80)} at
 *       L178, the trailer decomposing into the {@code PIC 9(04)} reason at L181 and
 *       {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at L182. Throwing on 101 would abandon the
 *       loop that must continue to the next record; nothing here may import, accept, store, expose
 *       or name the reject code enum.</li>
 *   <li><strong>No status value and no status enum field.</strong> Adding one would be dishonest as
 *       well as redundant: 23 of the 24 antecedent sites are CICS response codes that have no
 *       {@code FILE STATUS} at all, so the field would be absent or invented at almost every
 *       throwing site. {@code com.cardemo.model.enums.FileStatus} classifies a raw status and owns
 *       both its four character rendering and the diagnostic display literal that
 *       {@code 9910-DISPLAY-IO-STATUS} emits at {@code app/cbl/CBTRN02C.cbl:L714-L727}. That literal
 *       is deliberately not reproduced anywhere in this file, not even as documentation, because it
 *       is compared byte for byte against the legacy baseline and a second copy of it would be a
 *       second source of truth - exactly the duplication Rule 1 Clause C forbids. The dependency
 *       direction is strictly from this package towards {@code com.cardemo.model.enums} and never
 *       the reverse.</li>
 *   <li><strong>No annotation of any kind and no logging.</strong> In particular no HTTP status
 *       annotation: the right response differs by caller, since the same missing row is a not-found
 *       response when a client asked for it directly and an integrity failure when a batch step
 *       required it, so controllers choose in context. An exception is thrown; the caller logs it.
 *       A logger here would double log every failure and put a side effect in a constructor.</li>
 *   <li><strong>No per entity subtype.</strong> One type covers all 23 online sites and every
 *       migrated table. The record type is carried as data, not encoded in the class name, which is
 *       why there is no account, card or customer flavoured variant of this class.</li>
 *   <li><strong>No custom serialization hook.</strong> No object stream callback, no serialization
 *       proxy, no instance replacement method and no alternative externalization contract. The
 *       default mechanism inherited from {@link Throwable} is used as is, the two fields below are
 *       plain immutable strings, and no instance is ever rebuilt from untrusted input. The only
 *       concession is the pinned identity below, which exists to satisfy the compiler lint.</li>
 * </ul>
 *
 * <h2>The masking contract for the carried key</h2>
 *
 * <p><strong>Finding, severity Blocker if breached.</strong> This type is naturally keyed by "the
 * record we could not find", and in this domain that key is very often a card number, which the
 * legacy layouts declare as {@code PIC X(16)}. Exception messages are logged. Callers must therefore
 * never pass an unmasked card number, and never pass any of the personally identifiable values the
 * customer layout carries: {@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2 PIC X(15)}
 * ({@code app/cpy/CVCUS01Y.cpy:L15-L16}), {@code CUST-SSN PIC 9(09)} (L17),
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} (L18), {@code CUST-DOB-YYYY-MM-DD PIC X(10)} (L19),
 * {@code CUST-EFT-ACCOUNT-ID PIC X(10)} (L20), nor the password field of the user security layout.
 * No credential, token, signing key or password hash belongs in a message or a key either.
 *
 * <p>The contract is a caller obligation and is deliberately not enforced here. This class cannot
 * tell a masked identifier from an unmasked one, and a constructor that guessed would either mangle
 * legitimate keys or give false assurance. What it does instead is keep the surface small enough to
 * comply with easily: pass the record type, which is never sensitive, and either omit the key or
 * pass a non sensitive descriptor of it - a masked suffix, a surrogate identifier, or the field name
 * that was searched on. Identify a record by its key, never by its content.
 *
 * <h2>Null and blank handling</h2>
 *
 * <p>Stated explicitly, because Rule 1 Clause B requires boundary conditions to be handled rather
 * than implied, and because the policy is deliberately not uniform across the four inputs:
 *
 * <ul>
 *   <li><strong>Message and cause pass through unchanged.</strong> A null or blank message and a
 *       null cause are permitted, are not substituted and are not rejected, exactly as
 *       {@link CardDemoException} specifies for the whole hierarchy. Rejecting them would replace a
 *       real failure with a spurious argument failure at the moment the real one is being reported.
 *       A null cause records that there is no underlying throwable to attribute.</li>
 *   <li><strong>Each constructor form reaches the matching superclass form, deliberately.</strong>
 *       The two constructors that take no cause call the message-only superclass constructor, and
 *       the two that take a cause call the message-and-cause one. They are not funnelled through a
 *       single widest constructor, because {@link Throwable} distinguishes a cause that was never
 *       initialised from one that was explicitly initialised to null: only the former permits a
 *       later {@link Throwable#initCause(Throwable)}. Funnelling would have silently made every
 *       instance reject that call and would have made this type behave differently from
 *       {@link CardDemoException} for the same constructor shape. {@link Throwable#getCause()}
 *       returns null either way, so the distinction is invisible until something tries to set a
 *       cause after the fact. Two assignments are repeated to keep it correct; the normalisation
 *       rule itself is not duplicated.</li>
 *   <li><strong>Record type and key collapse blank to absent.</strong> A null, empty or
 *       whitespace-only value for either of this class's own two fields is normalised to absent, so
 *       that "I have no record type" has one representation instead of four and every caller is
 *       spared repeating the same emptiness check when building a log field. Nothing else is
 *       changed: the value is not trimmed, not case folded and not validated against any list of
 *       known file names, so no locale, charset or clock is consulted anywhere in this class and its
 *       behaviour is fully deterministic.</li>
 * </ul>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A batch job that used to complete now abends on a missing row.</em> The most likely
 *       cause is a blanket not-found mapping reaching one of the three scoped sites above. Check the
 *       category balance upsert and the disclosure group first lookup; neither may raise this type.
 *       The fix belongs in the central mapper, not here.</li>
 *   <li><em>The interest job fails only when reference data is incomplete.</em> Expected, and it must
 *       not be softened. A missing default disclosure group row is fatal by design; if it is
 *       surfacing as this type, the retry lookup has been wired to the first lookup's handler and the
 *       inversion has been inverted again.</li>
 *   <li><em>Every sequential batch step throws at the end of its input.</em> End of file has been
 *       mapped onto not found. {@code '10'} terminates a loop and is never thrown.</li>
 *   <li><em>{@link Throwable#getCause()} returns null after a wrap.</em> The throwing site used a
 *       constructor without a cause inside a {@code catch}. Switch to one of the cause carrying
 *       constructors; this class never drops a cause it was handed.</li>
 *   <li><em>{@link #recordType()} or {@link #recordKey()} is empty when a value was supplied.</em>
 *       The supplied value was blank or whitespace only and was collapsed to absent by the documented
 *       policy above. Supply a real value at the throwing site.</li>
 *   <li><em>A card number appears in a log.</em> Treat as a Blocker. The masking contract was
 *       breached at the throwing site, which is the only place that can fix it.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> The pinned identity below was
 *       removed. Every type here is a {@link java.io.Serializable} descendant through
 *       {@link Throwable}, and the build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so the declaration is mandatory rather than advisory.</li>
 * </ul>
 *
 * <p>Instances are immutable, carry no static state and consult no clock, locale or charset, so
 * behaviour is deterministic and unit testable. Tests live under
 * {@code src/test/java/com/cardemo/unit} and not in this package.
 *
 * @see CardDemoException
 */
public class RecordNotFoundException extends CardDemoException {

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
     * The logical file or entity that was searched, or null when the caller did not supply one.
     *
     * <p>Normalised at construction so that a null, empty or whitespace-only argument is stored as
     * null, giving absence a single representation. Never sensitive: the legacy names are file names
     * such as {@code ACCTDAT}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code TRANSACT},
     * {@code USRSEC}, {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE}.
     */
    private final String recordType;

    /**
     * A non sensitive descriptor of the key that found nothing, or null when the caller did not
     * supply one.
     *
     * <p>Normalised at construction on the same rule as {@link #recordType}. Subject to the masking
     * contract in the class documentation: it must never hold an unmasked card number, a social
     * security number, a government identifier, a telephone number, a date of birth, an electronic
     * funds account identifier, a credential or a password hash.
     */
    private final String recordKey;

    /**
     * Creates an exception reporting that a keyed read found nothing, with no record identity and no
     * underlying throwable.
     *
     * <p>Use this form only when the message already identifies the record adequately and there is
     * genuinely no cause to preserve. Prefer {@link #RecordNotFoundException(String, String, String)}
     * so that the record identity is available as structured data to a caller building a log entry,
     * and prefer a cause carrying constructor inside any {@code catch} block, because discarding a
     * caught throwable is the swallowing that Rule 1 Clause B forbids.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded
     * and no state outside this instance is read or written.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Null and
     *                blank are permitted and are passed through unchanged. It must not contain a
     *                secret, a credential or a personally identifiable value, because exception
     *                messages are logged
     */
    public RecordNotFoundException(String message) {
        super(message);
        this.recordType = null;
        this.recordKey = null;
    }

    /**
     * Creates an exception reporting that a keyed read found nothing and preserves the throwable that
     * caused it.
     *
     * <p>This is the form required when adapting a persistence or framework level not-found signal:
     * the message supplies the CardDemo context and the original throwable is retained unchanged.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * rethrown nor logged; it is delegated to {@link CardDemoException} as supplied.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Null and
     *                blank are permitted and are passed through unchanged, subject to the same
     *                prohibition on secrets and personally identifiable values
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}. Null
     *                is permitted and records that there is no underlying failure to attribute; a non
     *                null value is always retained
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.recordType = null;
        this.recordKey = null;
    }

    /**
     * Creates an exception reporting that a keyed read found nothing, carrying the identity of the
     * record that was sought.
     *
     * <p>This is the preferred form for a CardDemo originated lookup miss. The record identity is
     * carried as structured data rather than only interpolated into the message, so that a caller can
     * emit it as its own field in a JSON log line and query on it, which is the observability that
     * Rule 1 Clause A asks for. This class itself neither logs nor formats it.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * @param message    the detail message, retrievable through {@link Throwable#getMessage()}. Null
     *                   and blank are permitted and are passed through unchanged
     * @param recordType the logical file or entity searched, such as {@code ACCTDAT} or
     *                   {@code TRANSACT}. Null, empty and whitespace-only are permitted and are all
     *                   normalised to absent, reported by {@link #recordType()}
     * @param recordKey  a non sensitive descriptor of the key that found nothing. Null, empty and
     *                   whitespace-only are permitted and are all normalised to absent, reported by
     *                   {@link #recordKey()}. It must satisfy the masking contract documented on this
     *                   class: never an unmasked card number, social security number, government
     *                   identifier, telephone number, date of birth, electronic funds account
     *                   identifier, credential or password hash
     */
    public RecordNotFoundException(String message, String recordType, String recordKey) {
        super(message);
        this.recordType = blankToNull(recordType);
        this.recordKey = blankToNull(recordKey);
    }

    /**
     * Creates an exception reporting that a keyed read found nothing, carrying both the identity of
     * the record that was sought and the throwable that caused the failure.
     *
     * <p>This is the widest of the four forms and the one to use when adapting an underlying
     * not-found signal at a point where the record identity is also known. It satisfies Rule 1
     * Clause B on both counts at once: the message and the record identity supply the context, and
     * the cause preserves the root failure.
     *
     * <p>Side effects: none beyond throwable construction. The cause is delegated to
     * {@link CardDemoException} exactly as supplied and is never dropped.
     *
     * @param message    the detail message, retrievable through {@link Throwable#getMessage()}. Null
     *                   and blank are permitted and are passed through unchanged
     * @param recordType the logical file or entity searched. Null, empty and whitespace-only are
     *                   permitted and are all normalised to absent
     * @param recordKey  a non sensitive descriptor of the key that found nothing, subject to the
     *                   masking contract documented on this class. Null, empty and whitespace-only
     *                   are permitted and are all normalised to absent
     * @param cause      the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                   Null is permitted and records that there is no underlying failure to
     *                   attribute; a non null value is always retained
     */
    public RecordNotFoundException(String message, String recordType, String recordKey,
            Throwable cause) {
        super(message, cause);
        this.recordType = blankToNull(recordType);
        this.recordKey = blankToNull(recordKey);
    }

    /**
     * Returns the logical file or entity that was searched, if the throwing site supplied one.
     *
     * <p>Intended for a caller assembling a structured log entry or choosing a response, so that the
     * record identity does not have to be parsed back out of the message. This accessor performs no
     * formatting, no case folding and no lookup, and consults no locale.
     *
     * <p>Side effects: none. The value is immutable and is the value normalised at construction.
     *
     * @return the record type, or {@link Optional#empty()} when the throwing site supplied null, an
     *         empty string or a whitespace-only string. Never null
     */
    public Optional<String> recordType() {
        return Optional.ofNullable(recordType);
    }

    /**
     * Returns the non sensitive descriptor of the key that found nothing, if the throwing site
     * supplied one.
     *
     * <p>Whether this value is safe to log is the throwing site's responsibility under the masking
     * contract documented on this class; this accessor cannot and does not verify it, and returns
     * exactly what was supplied.
     *
     * <p>Side effects: none. The value is immutable and is the value normalised at construction.
     *
     * @return the record key descriptor, or {@link Optional#empty()} when the throwing site supplied
     *         null, an empty string or a whitespace-only string. Never null
     */
    public Optional<String> recordKey() {
        return Optional.ofNullable(recordKey);
    }

    /**
     * Normalises an absent-or-blank record identity component to null.
     *
     * <p>Collapsing null, the empty string and a whitespace-only string onto one representation is
     * what lets {@link #recordType()} and {@link #recordKey()} report absence honestly through
     * {@link Optional} rather than handing a caller a blank string to re-check.
     * {@link String#isBlank()} tests Unicode whitespace through
     * {@link Character#isWhitespace(char)} and so is locale independent, which keeps this class
     * deterministic as Rule 1 Clause C requires. The value is otherwise returned untouched: not
     * trimmed, not case folded and not validated.
     *
     * @param value the caller supplied component, which may be null, empty or whitespace only
     * @return null when the value is null or blank, otherwise the value exactly as supplied
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
