/*
 * ******************************************************************
 * Program     : FatalProcessingException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Typed abend carrying the CABENDD.CPY work areas; batch abend code 999, return code 12.
 * Source      : app/cpy/CSMSG02Y.cpy (CABENDD.CPY, abend work areas) + app/cbl/CBTRN02C.cbl:L707-710 @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L4203-L4228 @ 7756d89 - the online ABEND-ROUTINE and its
 *               default-message substitution.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89 - 9910-DISPLAY-IO-STATUS, the four character
 *               status render that always precedes the abend.
 * Source      : app/cbl/CBACT04C.cbl:L415-L460 @ 7756d89 - the disclosure group retry whose missing
 *               DEFAULT row abends instead of reporting a not found record.
 * Source      : app/cbl/CBSTM03A.CBL:L71-L80,L736-L911 @ 7756d89 - the file service call contract whose
 *               secondary success status must never reach this type.
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
 * The typed abend: the terminal failure of the CardDemo exception hierarchy.
 *
 * <p><strong>What it does.</strong> It carries the four abend work area fields of the frozen legacy
 * corpus into Java, and it marks a failure the legacy system did not recover from. Where the seven
 * sibling subtypes each name a <em>recognised</em> condition, this type names the residual one: a
 * status, response or state that the source code had no branch for and answered by terminating the
 * unit of work. It is deliberately the last resort of the hierarchy.
 *
 * <p><strong>What it deliberately does not do.</strong> It does not terminate anything. The legacy
 * batch routine ends the task with {@code CALL 'CEE3ABD'}
 * ({@code app/cbl/CBTRN02C.cbl:L711 @ 7756d89}) and the legacy online routine ends it with
 * {@code EXEC CICS ABEND} ({@code app/cbl/COACTUPC.cbl:L4222-L4224 @ 7756d89}). Neither has a Java
 * counterpart inside this class. Being thrown <em>is</em> the whole of its behaviour; the batch tier
 * translates the throw into a failed exit status and a process return code, and the logging tier
 * emits the diagnostic. Three collaborators, three concerns - see the boundary section below.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line or line
 * range in the frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the
 * {@code Source} lines in the file header record. Filename case is reproduced as it appears on disk
 * because the corpus is not uniform: in {@code app/cpy} only {@code COSTM01.CPY} uses an uppercase
 * extension, and in {@code app/cbl} only {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} do. That is
 * why {@code app/cpy/CSMSG02Y.cpy}, {@code app/cbl/CBTRN02C.cbl}, {@code app/cbl/COACTUPC.cbl} and
 * {@code app/cbl/CBACT04C.cbl} are cited in lowercase while {@code app/cbl/CBSTM03A.CBL} is cited in
 * uppercase. Every figure quoted here was measured against the corpus rather than inherited.
 *
 * <h2>Provenance: CSMSG02Y.cpy is CABENDD.CPY, and that correction is load bearing</h2>
 *
 * <p><strong>Finding, severity High.</strong> {@code app/cpy/CSMSG02Y.cpy} was previously classified
 * as a message copybook, purely on the strength of its {@code CSMSG} member-name prefix and its
 * neighbour {@code app/cpy/CSMSG01Y.cpy}, which genuinely is one. It is not a message copybook. Its
 * own header block, at {@code app/cpy/CSMSG02Y.cpy:L2 @ 7756d89}, titles it {@code CABENDD.CPY}, and
 * {@code :L4} states its purpose as work areas for the abend routine. Carrying the misclassification
 * forward would have discarded the entire field set below and left this type with nothing to carry -
 * hence High rather than Low. <strong>Remediation, already applied:</strong> the four fields are
 * modelled here as the constructor payload, and the classification is recorded in this Javadoc so
 * that the member name cannot mislead a future reader the same way.
 *
 * <p>The declaration, verbatim from {@code app/cpy/CSMSG02Y.cpy:L21-L29 @ 7756d89}, with the source
 * sequence numbers the copybook carries in columns 1 to 6:
 *
 * <pre>
 * 001200 01  ABEND-DATA.
 * 001300   05  ABEND-CODE     PIC X(4)  VALUE SPACES.
 * 001500   05  ABEND-CULPRIT  PIC X(8)  VALUE SPACES.
 * 001700   05  ABEND-REASON   PIC X(50) VALUE SPACES.
 * 001900   05  ABEND-MSG      PIC X(72) VALUE SPACES.
 * </pre>
 *
 * <table>
 *   <caption>The four CABENDD.CPY work areas and the accessor that carries each one</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Width</th><th>Accessor</th></tr>
 *   <tr><td>{@code ABEND-CODE}</td><td>{@code X(4)}</td><td>4</td><td>{@link #getAbendCode()}</td></tr>
 *   <tr><td>{@code ABEND-CULPRIT}</td><td>{@code X(8)}</td><td>8</td><td>{@link #getAbendCulprit()}</td></tr>
 *   <tr><td>{@code ABEND-REASON}</td><td>{@code X(50)}</td><td>50</td><td>{@link #getAbendReason()}</td></tr>
 *   <tr><td>{@code ABEND-MSG}</td><td>{@code X(72)}</td><td>72</td><td>{@link #getAbendMessage()}</td></tr>
 * </table>
 *
 * <p>The group totals 134 bytes, which is the length the online routine sends to the terminal in one
 * operation. <strong>The widths above are provenance and contract documentation, not runtime
 * limits.</strong> The Java fields are plain {@link String} and are never measured, padded or
 * truncated. That is a deliberate choice: an abend diagnostic is the only artefact an operator has
 * once the run is over, and silently clipping it at 50 or 72 characters would destroy information at
 * exactly the moment it is most needed. A caller that must render the legacy fixed-width block is
 * responsible for its own padding, because that is a presentation concern.
 *
 * <h2>The batch abend contract: code 999, return code 12</h2>
 *
 * <p>Verbatim from {@code app/cbl/CBTRN02C.cbl:L707-L711 @ 7756d89}:
 *
 * <pre>
 * 9999-ABEND-PROGRAM.
 *     DISPLAY 'ABENDING PROGRAM'
 *     MOVE 0 TO TIMING
 *     MOVE 999 TO ABCODE
 *     CALL 'CEE3ABD'.
 * </pre>
 *
 * <p>{@link #BATCH_ABEND_CODE} and {@link #BATCH_RETURN_CODE} expose the two numbers that paragraph
 * establishes. This class only publishes them; it never acts on them. The literal displayed at
 * {@code :L708} is part of the observable log contract and belongs to the logging tier, so it is
 * described here and deliberately not reproduced as a string literal in this file - duplicating it
 * would create a second source of truth for a value the parity comparison reads byte for byte.
 *
 * <p>Every one of the 18 guard sites in that program reaches this paragraph the same way. Verbatim
 * from {@code app/cbl/CBTRN02C.cbl:L247-L250 @ 7756d89}:
 *
 * <pre>
 * DISPLAY 'ERROR OPENING DALYTRAN'
 * MOVE DALYTRAN-STATUS TO IO-STATUS
 * PERFORM 9910-DISPLAY-IO-STATUS
 * PERFORM 9999-ABEND-PROGRAM
 * </pre>
 *
 * <p><strong>The ordering matters and explains the split between two types.</strong> The four
 * character status is rendered by {@code 9910-DISPLAY-IO-STATUS}
 * ({@code app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89}) <em>before</em> control reaches the abend. So the
 * rendered status is carried by {@code FileAccessException} and its rendering is owned by
 * {@code com.cardemo.model.enums.FileStatus}, while this type carries only the abend itself. When an
 * operator sees an abend, the status line immediately preceding it in the log is the diagnostic that
 * caused it.
 *
 * <h2>Two abend mechanisms, three code values</h2>
 *
 * <p>The corpus abends in two different ways and three distinct numbers are in play. Conflating them
 * is the single easiest mistake to make here, so all three are tabulated with their locators.
 *
 * <table>
 *   <caption>The two abend mechanisms and the three code values, with locators</caption>
 *   <tr><th>Path</th><th>Code field</th><th>Value</th><th>Terminator</th><th>Return code</th></tr>
 *   <tr>
 *     <td><strong>Batch - mandated for this class</strong><br>
 *         {@code app/cbl/CBTRN02C.cbl:L707-L711}</td>
 *     <td>{@code ABCODE PIC S9(9) BINARY} ({@code :L147})</td>
 *     <td><strong>999</strong></td>
 *     <td>{@code CALL 'CEE3ABD'}</td>
 *     <td><strong>12</strong></td>
 *   </tr>
 *   <tr>
 *     <td>Online<br>{@code app/cbl/COACTUPC.cbl:L4222-L4224}</td>
 *     <td>{@code EXEC CICS ABEND ABCODE(...)}</td>
 *     <td>{@code '9999'} - four characters</td>
 *     <td>{@code EXEC CICS ABEND}</td>
 *     <td>not applicable</td>
 *   </tr>
 *   <tr>
 *     <td>Online payload<br>{@code app/cbl/COACTUPC.cbl:L2635}</td>
 *     <td>{@code ABEND-CODE PIC X(4)}</td>
 *     <td>{@code '0001'}</td>
 *     <td>not a terminator</td>
 *     <td>not applicable</td>
 *   </tr>
 * </table>
 *
 * <p><strong>The mandated contract for this class is the batch row: 999 and return code 12.</strong>
 * The other two rows are documented so that no reader "corrects" 999 to 9999 or to 0001.
 * <strong>Finding, severity Low:</strong> conflating the three values in prose is a documentation
 * defect only. <strong>Finding, severity Blocker:</strong> setting {@link #BATCH_ABEND_CODE} to 9999
 * would break the batch abend contract the validation gates assert.
 *
 * <p>Note the type difference the table records. {@code ABCODE} is a signed binary field
 * ({@code app/cbl/CBTRN02C.cbl:L147 @ 7756d89}) and is therefore numeric, which is why
 * {@link #BATCH_ABEND_CODE} is an {@code int}. {@code ABEND-CODE} is a four character display field
 * ({@code app/cpy/CSMSG02Y.cpy:L22 @ 7756d89}) and is therefore text, which is why
 * {@link #getAbendCode()} returns a {@link String}. They are two different fields that happen to hold
 * similar looking numbers; they are not interchangeable.
 *
 * <h2>Three corrections to the online abend routine</h2>
 *
 * <p>Verbatim from {@code app/cbl/COACTUPC.cbl:L4203-L4228 @ 7756d89}, elided only where an
 * {@code EXEC CICS} block spans several lines:
 *
 * <pre>
 * ABEND-ROUTINE.
 *     IF ABEND-MSG EQUAL LOW-VALUES
 *        MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG
 *     END-IF
 *     MOVE LIT-THISPGM       TO ABEND-CULPRIT
 *     EXEC CICS SEND FROM (ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) NOHANDLE ERASE END-EXEC
 *     EXEC CICS HANDLE ABEND CANCEL END-EXEC
 *     EXEC CICS ABEND ABCODE('9999') END-EXEC
 * ABEND-ROUTINE-EXIT.
 *     EXIT
 * </pre>
 *
 * <p>Its caller at {@code app/cbl/COACTUPC.cbl:L2633-L2640 @ 7756d89} sets all four fields first -
 * the culprit, the code {@code '0001'}, a blank reason and the message - and the routine is also
 * registered as a handler by {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at
 * {@code app/cbl/COACTUPC.cbl:L862-L864 @ 7756d89}.
 *
 * <p><strong>Correction 1, severity High - the substitution trigger is LOW-VALUES, not blank.</strong>
 * The test at {@code :L4205} is {@code IF ABEND-MSG EQUAL LOW-VALUES}. It is not a test for spaces and
 * it is not a test for an empty field. This matters because the copybook initialises {@code ABEND-MSG}
 * to {@code VALUE SPACES} ({@code app/cpy/CSMSG02Y.cpy:L28-L29 @ 7756d89}), and spaces are not low
 * values, so <em>a freshly initialised field does not trigger the substitution</em>. Only a field
 * explicitly set to low values does. Java has no low-values concept, so the faithful analogue of
 * "absent" is {@code null}, and the analogue of a space filled field is a blank string. Therefore:
 *
 * <ul>
 *   <li>a {@code null} message <strong>is</strong> replaced by {@link #DEFAULT_ABEND_MESSAGE};</li>
 *   <li>a blank or whitespace-only message <strong>is not</strong> replaced, and is passed through
 *       exactly as supplied.</li>
 * </ul>
 *
 * <p>Substituting on a blank message would fire where the source does not, and the substituted text is
 * compared byte for byte by the parity gates - which is why this is High and not cosmetic. The mapping
 * from low values to {@code null} is a mechanism substitution and is recorded as such in
 * {@code DECISION_LOG.md}. <strong>Remediation if a divergence is ever observed:</strong> check the
 * throwing site, not this class; a caller that passes {@code ""} is asking for {@code ""}.
 *
 * <p><strong>Correction 2, severity Medium - the culprit is overwritten inside the routine.</strong>
 * The caller moves the program name into {@code ABEND-CULPRIT} at {@code :L2634}, and then the routine
 * moves it again at {@code :L4209}, overwriting whatever the caller set. In the source both moves
 * happen to write the same {@code LIT-THISPGM} value, so the overwrite is invisible there; but the
 * authoritative value is unambiguously the one the routine itself supplies. The Java analogue is that
 * <em>the component which raises the exception is the culprit</em>. A culprit supplied to a
 * constructor here is honoured and never overwritten, because a caller in Java always knows its own
 * identity whereas the COBOL routine could not; the divergence is documented rather than reproduced,
 * since reproducing an overwrite would mean discarding the only value available.
 *
 * <p><strong>Correction 3, severity Low - the online abend code is four characters.</strong> The online
 * terminator carries {@code '9999'} at {@code :L4222-L4224}, and the online payload field carries
 * {@code '0001'} at {@code :L2635}. Neither is the batch value. See the three-code table above.
 *
 * <h2>Where this type sits in the status map: it is the catch-all</h2>
 *
 * <p>Classification of a raw status is owned by {@code com.cardemo.model.enums.FileStatus} and the
 * decision of which failure a status represents is owned by
 * {@code com.cardemo.service.shared.FileStatusMapper}. The table below is the map those two implement,
 * reproduced here only to fix this type's place in it.
 *
 * <table>
 *   <caption>Status to exception map, with the row this type occupies marked</caption>
 *   <tr><th>File status</th><th>Meaning</th><th>Target</th></tr>
 *   <tr><td>{@code '00'} or {@code '04'}</td><td>Success, and secondary success</td>
 *       <td>Continue - not a failure</td></tr>
 *   <tr><td>{@code '10'}</td><td>End of file</td><td>Loop termination - <em>not</em> a failure</td></tr>
 *   <tr><td>{@code '23'}</td><td>Record not found</td>
 *       <td>{@code RecordNotFoundException}, except at the scoped sites below</td></tr>
 *   <tr><td>{@code '22'}</td><td>Duplicate key</td><td>{@code DuplicateRecordException}</td></tr>
 *   <tr><td>{@code '35'}</td><td>File unavailable</td><td>{@code FileUnavailableException}</td></tr>
 *   <tr><td>{@code '9x'}</td><td>Physical or logical I/O error</td>
 *       <td>{@code FileAccessException}, carrying the four character rendered status</td></tr>
 *   <tr><td><strong>anything else</strong></td><td><strong>Unexpected</strong></td>
 *       <td><strong>this type - abend 999, return code 12</strong></td></tr>
 * </table>
 *
 * <p><strong>Reach for this type last.</strong> A caller must exhaust all six specific rows first. An
 * over-applied catch-all is worse than no catch-all, because it converts a diagnosable condition into
 * an undiagnosable one.
 *
 * <h3>The one inversion: a not-found that belongs here</h3>
 *
 * <p>There is exactly one place where a record-not-found status must produce <em>this</em> type rather
 * than {@code RecordNotFoundException}, and it is the place a naive implementation gets backwards. In
 * {@code app/cbl/CBACT04C.cbl @ 7756d89}, paragraph {@code 1200-GET-INTEREST-RATE} at {@code :L415}
 * reads the disclosure group file, accepts {@code '00' OR '23'} at {@code :L422}, and on {@code '23'}
 * at {@code :L436} substitutes the literal default group identifier at {@code :L437} and performs
 * {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code :L438}. That retry paragraph, at {@code :L443}, reads
 * again at {@code :L444} with <strong>no INVALID KEY clause at all</strong>, and its guard at
 * <strong>{@code :L446} accepts only {@code '00'}</strong>. Anything else displays a diagnostic at
 * {@code :L455} and falls through to the abend at {@code :L458}.
 *
 * <p><strong>So a missing default disclosure group row abends the job.</strong> It is fatal, never a
 * reportable not-found. The first read tolerates absence; the retry does not.
 *
 * <h3>The scoped-success sites that must not come here</h3>
 *
 * <p>The mirror-image error is over-applying this type where the source deliberately tolerates a
 * status. Three such sites exist and all three are scoped narrowly:
 *
 * <ul>
 *   <li><strong>The transaction-category-balance upsert.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L481 @ 7756d89} tests
 *       {@code IF TCATBALF-STATUS = '00' OR '23'} inside {@code 2700-UPDATE-TCATBAL} ({@code :L467}),
 *       so a not-found on the <em>read</em> is an accepted create path. The leniency stops there: the
 *       {@code WRITE} guard at {@code :L512} and the {@code REWRITE} guard at {@code :L530} both accept
 *       {@code '00'} only, and anything else abends.</li>
 *   <li><strong>The first disclosure group read</strong> at {@code app/cbl/CBACT04C.cbl:L422 @ 7756d89},
 *       described in the inversion above - tolerant, unlike its retry.</li>
 *   <li><strong>The file service secondary success.</strong> {@code app/cbl/CBSTM03A.CBL @ 7756d89}
 *       calls its file access subprogram through a shared area whose {@code WS-M03B-RC} is
 *       {@code PIC X(02)} at {@code :L80}, and accepts {@code '00' OR '04'} at nine sites: {@code :L736},
 *       {@code :L748}, {@code :L771}, {@code :L789}, {@code :L807}, {@code :L862}, {@code :L879},
 *       {@code :L895} and {@code :L911}. Its four {@code EVALUATE WS-M03B-RC} sites, at {@code :L353},
 *       {@code :L379}, {@code :L403} and {@code :L837}, accept {@code '00'} only, treat {@code '10'} as
 *       end of file, and abend on anything else - which is to say, they arrive here.</li>
 * </ul>
 *
 * <p>A measured note, because the count is easy to get wrong: the batch corpus contains
 * <strong>three</strong> literal {@code '23'} tests, not one. Two are the tolerant acceptances above
 * ({@code app/cbl/CBTRN02C.cbl:L481} and {@code app/cbl/CBACT04C.cbl:L422}); the third,
 * {@code app/cbl/CBACT04C.cbl:L436}, is the retry trigger rather than an acceptance. Measured with
 * {@code grep -rn "OR '23'\|= '23'" app/cbl/} at the anchor commit.
 *
 * <h2>Return code 4 and return code 12 are independent</h2>
 *
 * <p>Two different mechanisms in the same program set a process return code, and they never meet.
 *
 * <ul>
 *   <li><strong>Return code 4 is a business outcome.</strong> Reject codes are moved into
 *       {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} ({@code app/cbl/CBTRN02C.cbl:L181 @ 7756d89}) at
 *       {@code :L385}, {@code :L397}, {@code :L410}, {@code :L417} and {@code :L556}; they are written
 *       into a 430 byte reject record composed of {@code REJECT-TRAN-DATA PIC X(350)} plus
 *       {@code VALIDATION-TRAILER PIC X(80)} ({@code :L176-L178}), the trailer being a
 *       {@code PIC 9(04)} reason and a {@code PIC X(76)} description ({@code :L180-L182}); and they are
 *       cleared on each iteration at {@code :L208-L209}. The exit contract at {@code :L227-L231} then
 *       reads {@code IF WS-REJECT-COUNT &gt; 0  MOVE 4 TO RETURN-CODE  END-IF}. Whether the reject count
 *       exceeds zero is the <em>only</em> determinant of return code 4.</li>
 *   <li><strong>Return code 12 is an abend.</strong> It is this type, and it has no relationship to the
 *       reject count.</li>
 * </ul>
 *
 * <p><strong>Consequently a reject code is never thrown and never becomes an exception.</strong>
 * {@code com.cardemo.model.enums.RejectCode} models those outcomes as data that drives an exit status;
 * this class has no reference to it, produces no reject value and can never yield return code 4.
 * Reject code 109 is a case in point: it is assigned at {@code app/cbl/CBTRN02C.cbl:L556 @ 7756d89}
 * inside the {@code INVALID KEY} clause of the account rewrite in {@code 2800-UPDATE-ACCOUNT-REC}
 * ({@code :L545}), which runs only on the already validated path, so it writes no reject record, does
 * not increment the reject count, and is cleared on the next iteration. It is retained for parity in
 * the enum package, and deliberately has no counterpart here.
 *
 * <h2>Security contract: the reason and the message describe the condition, never the data</h2>
 *
 * <p>{@code ABEND-REASON} and {@code ABEND-MSG} are free-text operator diagnostics, and the legacy
 * online routine sends the <em>entire</em> 134 byte block to the terminal in one operation at
 * {@code app/cbl/COACTUPC.cbl:L4211-L4216 @ 7756d89}. Their Java counterparts are logged. So a caller
 * that packs a record image, a key value or a field value into either one leaks it, twice over.
 *
 * <p><strong>The contract is therefore absolute: describe the condition, never the data.</strong>
 * "Unexpected status on the disclosure group retry" is a reason; a copy of the record that produced it
 * is not. <strong>Finding, severity Blocker:</strong> allowing a record image into the reason or the
 * message. <strong>Remediation:</strong> identify a failing record by its key alone, and only where the
 * key is not itself sensitive.
 *
 * <p>Rule 1 Clause D requires no secrets in code, logs, tests or configuration, and the corpus is
 * explicit about what qualifies. {@code app/cpy/CVCUS01Y.cpy:L15-L20 @ 7756d89} declares
 * {@code CUST-SSN}, {@code CUST-GOVT-ISSUED-ID}, {@code CUST-DOB-YYYY-MM-DD},
 * {@code CUST-EFT-ACCOUNT-ID} and two telephone fields; {@code app/cpy/CSUSR01Y.cpy @ 7756d89}
 * declares {@code SEC-USR-PWD}; and card numbers are sixteen character fields throughout. None of
 * these values, nor any password hash, token or signing key, may appear in any field of this
 * exception. This class holds no credential, reads no configuration and consults no environment, so it
 * has no privilege to misuse.
 *
 * <h2>Architectural boundary: what belongs here and what does not</h2>
 *
 * <p>The abend translation is split across three collaborators and the split is deliberate:
 *
 * <ul>
 *   <li><strong>This class carries</strong> the abend payload and the two published numbers. Nothing
 *       else.</li>
 *   <li><strong>The batch tier terminates.</strong> It maps a throw of this type onto a failed exit
 *       status and process return code {@link #BATCH_RETURN_CODE}. No Spring Batch type is imported
 *       here, and no exit status is constructed here, precisely so that this class stays framework
 *       independent and unit testable without a job context.</li>
 *   <li><strong>The logging tier emits.</strong> It writes the diagnostic, including the literal at
 *       {@code app/cbl/CBTRN02C.cbl:L708 @ 7756d89}, and the four character status line that the guard
 *       renders before the abend.</li>
 * </ul>
 *
 * <p>Accordingly this class contains no status parsing, no status rendering, no mapping table, no
 * severity lookup, no metric, no logger, no annotation, no retry, no recovery and no fallback. An
 * abend is terminal by definition, so a fallback would contradict the type. Adding any of these would
 * duplicate a responsibility that already has an owner, which Rule 1 Clause C forbids.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <p>When an operator sees this exception, the procedure is: read the culprit to locate the component,
 * read the reason and the message for the condition, walk {@link Throwable#getCause()} to the root
 * cause, then look at the log line immediately <em>preceding</em> the abend for the four character
 * status that triggered it.
 *
 * <ul>
 *   <li><em>The message is the default text and nothing else is known.</em> The throwing site supplied
 *       {@code null}, so the substitution of {@link #DEFAULT_ABEND_MESSAGE} fired as designed. This is
 *       the legacy behaviour, not a defect - but a specific message is always better. Fix the throwing
 *       site.</li>
 *   <li><em>The message is blank rather than the default.</em> Also correct, and deliberately so: a
 *       blank message is the analogue of a space filled field, which the source does not substitute.
 *       See Correction 1.</li>
 *   <li><em>{@link Throwable#getCause()} is null after a wrap.</em> The throwing site used a
 *       constructor without a cause parameter inside a {@code catch}. Switch to a cause carrying
 *       constructor; this class never drops a cause it was handed.</li>
 *   <li><em>An accessor returns null.</em> That field was not supplied. The two message-only
 *       constructors leave the code, the culprit and the reason unset by design, because the batch path
 *       has no four character display code to supply - it carries the binary code 999 instead. Use a
 *       payload constructor when the fields are known.</li>
 *   <li><em>A record that used to be reported as missing now abends.</em> Expected on the disclosure
 *       group retry, and only there. See the inversion section.</li>
 *   <li><em>A run that used to succeed now abends on a not-found.</em> Suspect the opposite mistake: a
 *       tolerant site being mapped strictly. Check the three scoped-success sites. The fix belongs in
 *       the central mapper, never here.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> {@link #serialVersionUID} was removed.
 *       {@link Throwable} implements {@link java.io.Serializable}, the build runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so the declaration is mandatory.</li>
 *   <li><em>The JVM exits when this is thrown.</em> Not caused by this class. It performs no
 *       termination of any kind - no process exit, no halt, no shutdown hook, no thread interruption.
 *       Look at the batch tier or the runner. <strong>Finding, severity Blocker:</strong> adding any
 *       JVM termination here, which would also make the test suite unrunnable.</li>
 * </ul>
 *
 * <h2>Dependency direction, determinism and testing</h2>
 *
 * <p>This file declares no import at all. It depends only on {@code java.lang} and on
 * {@link CardDemoException} in its own package, so it introduces no cycle and no framework coupling.
 *
 * <p>Instances are immutable: every field is {@code final}, there is no setter, and there is no static
 * mutable state - the only static members are three constants of immutable type. Construction performs
 * one null comparison and no formatting, no case conversion, no clock read, no locale lookup and no
 * charset decision, so {@link #DEFAULT_ABEND_MESSAGE} is byte identical on every platform and every
 * run. That determinism is what makes the parity comparison meaningful.
 *
 * <p>Unit tests for this type belong under {@code src/test/java/com/cardemo/unit} and not in this
 * package. Their required coverage is both substitution paths - {@code null} substitutes, blank does
 * not - every constructor, every accessor, cause preservation through {@link Throwable#getCause()},
 * the two published numbers being 999 and 12, and the absence of any JVM termination. Nothing in this
 * class requires a Spring context, a database, a clock or a locale to exercise.
 *
 * @see CardDemoException
 */
public class FatalProcessingException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements
     * {@link java.io.Serializable}, so this type is unavoidably serializable and must pin the value
     * explicitly: the build runs {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning},
     * which turns the {@code serial} lint into a compilation failure rather than a warning. A fixed
     * literal is used rather than a computed default so that the identity does not shift when the
     * class is edited.
     *
     * <p>No custom serialization hook accompanies it. Rule 1 Clause D names insecure deserialization
     * as a risky pattern to flag, so this type adds no object stream callback, no serialization proxy,
     * no instance replacement method and no alternative externalization contract, and no instance of
     * it is ever reconstructed from untrusted input.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The batch abend code, {@code 999}.
     *
     * <p>From {@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710 @ 7756d89}, immediately
     * before {@code CALL 'CEE3ABD'} at {@code :L711}. {@code ABCODE} is declared
     * {@code PIC S9(9) BINARY} at {@code :L147}, which is why this constant is an {@code int} and not
     * a four character string.
     *
     * <p>This is <strong>not</strong> the online value. The online terminator carries the four
     * character literal {@code '9999'} at {@code app/cbl/COACTUPC.cbl:L4222-L4224 @ 7756d89} and the
     * online payload field carries {@code '0001'} at {@code :L2635}. Setting this constant to either
     * of those would break the batch abend contract the validation gates assert - severity Blocker.
     *
     * <p>The constant is published for the batch tier to read. This class never acts on it: it starts
     * no process, sets no exit status and terminates nothing.
     */
    public static final int BATCH_ABEND_CODE = 999;

    /**
     * The process return code an abend yields, {@code 12}.
     *
     * <p>The abend at {@code app/cbl/CBTRN02C.cbl:L707-L711 @ 7756d89} ends the task rather than
     * returning normally, so the step completes with a failure return code of 12 - the value the
     * legacy job stream tests for with its condition-code gating.
     *
     * <p>It is <strong>independent of return code 4</strong>, which the same program sets at
     * {@code app/cbl/CBTRN02C.cbl:L227-L231 @ 7756d89} if and only if the reject count exceeds zero.
     * Rejects are business outcomes carried as data; an abend is a failure carried as this exception.
     * The two paths never meet, and this class can never produce return code 4.
     *
     * <p>Published for the batch tier to map onto a failed exit status. This class does not act on it.
     */
    public static final int BATCH_RETURN_CODE = 12;

    /**
     * The substituted default abend message, exactly {@code UNEXPECTED ABEND OCCURRED.} - including the
     * trailing full stop, which is part of the literal.
     *
     * <p>From {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} at
     * {@code app/cbl/COACTUPC.cbl:L4206 @ 7756d89}, guarded by
     * {@code IF ABEND-MSG EQUAL LOW-VALUES} at {@code :L4205}.
     *
     * <p><strong>The trigger is low values, not blank.</strong> The copybook initialises
     * {@code ABEND-MSG} to {@code VALUE SPACES} at {@code app/cpy/CSMSG02Y.cpy:L28-L29 @ 7756d89}, and
     * spaces are not low values, so a freshly initialised field is not substituted. The faithful Java
     * analogue of low values is {@code null}; a blank string is the analogue of spaces and is therefore
     * passed through untouched. Substituting on a blank message would fire where the source does not,
     * and this text is compared byte for byte by the parity gates - severity High.
     *
     * <p>The value is a compile-time constant with no locale, charset or clock dependency, so it is
     * byte identical on every platform and every run.
     */
    public static final String DEFAULT_ABEND_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /**
     * The four character abend code, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22 @ 7756d89}.
     *
     * <p>Text, not a number: it is the online display code, distinct from the numeric
     * {@link #BATCH_ABEND_CODE}. Null when not supplied, which is the normal case on the batch path.
     * Never measured, padded or truncated to its four character legacy width.
     */
    private final String abendCode;

    /**
     * The culprit component, from {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24 @ 7756d89}.
     *
     * <p>The legacy routine overwrites whatever its caller set, at
     * {@code app/cbl/COACTUPC.cbl:L4209 @ 7756d89}, so the authoritative value there is the routine's
     * own program name. The Java analogue is the component that raises the exception. A supplied value
     * is honoured and never overwritten. Null when not supplied. Never truncated to eight characters.
     */
    private final String abendCulprit;

    /**
     * The reason for the abend, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26 @ 7756d89}.
     *
     * <p>A free-text operator diagnostic that <strong>describes the condition and never the data</strong>
     * - the legacy routine sends the whole block to a terminal at
     * {@code app/cbl/COACTUPC.cbl:L4211-L4216 @ 7756d89} and this value is logged. Null when not
     * supplied; blank when supplied blank, mirroring the {@code VALUE SPACES} initialisation. Never
     * truncated to fifty characters.
     */
    private final String abendReason;

    /**
     * The abend message, from {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28 @ 7756d89}.
     *
     * <p>The only one of the four fields subject to substitution: {@code null} becomes
     * {@link #DEFAULT_ABEND_MESSAGE}, a blank value does not. It therefore never holds {@code null},
     * and it always equals {@link Throwable#getMessage()}. Subject to the same condition-never-data
     * contract as the reason. Never truncated to seventy-two characters.
     */
    private final String abendMessage;

    /**
     * Creates a fatal abend reporting a condition, with no underlying throwable and no abend payload.
     *
     * <p>The narrowest form, for use where CardDemo's own logic detects a state it has no branch for -
     * the residual case of the status map. Inside a {@code catch} block use
     * {@link #FatalProcessingException(String, Throwable)} instead; discarding the caught throwable is
     * the swallowing Rule 1 Clause B forbids.
     *
     * <p>The abend code, the culprit and the reason are left unset, which is the faithful
     * representation of the batch path: it has no four character display code to supply, carrying the
     * numeric {@link #BATCH_ABEND_CODE} instead. Use
     * {@link #FatalProcessingException(String, String, String, String)} when those fields are known.
     *
     * <p>Side effects: none beyond throwable construction. <strong>No JVM termination occurs</strong> -
     * no process exit, no halt, no shutdown hook, no thread interruption. Nothing is logged, no metric
     * is recorded, and no state outside this instance is read or written.
     *
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG}.
     *                     {@code null} is replaced by {@link #DEFAULT_ABEND_MESSAGE}, reproducing the
     *                     low-values substitution at {@code app/cbl/COACTUPC.cbl:L4205-L4207 @ 7756d89};
     *                     a blank value is <strong>not</strong> replaced and is passed through
     *                     unchanged. It must describe the condition and must never contain a record
     *                     image, a credential or a personally identifiable value, because it is logged
     */
    public FatalProcessingException(String abendMessage) {
        super(substituteAbendMessage(abendMessage));
        this.abendCode = null;
        this.abendCulprit = null;
        this.abendReason = null;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend reporting a condition and preserving the throwable that caused it.
     *
     * <p>The form required at every wrapping site, and the one that makes an abend diagnosable. This
     * type is the terminal case of the hierarchy: if it dropped the cause, the root cause of the abend
     * would be lost with nothing downstream to recover it. It never does - the cause is delegated to
     * {@link CardDemoException} exactly as supplied, in direct satisfaction of Rule 1 Clause B's
     * requirement to wrap with context and preserve the root cause. <strong>Finding, severity
     * High:</strong> dropping the cause at a throwing site.
     *
     * <p>The abend code, the culprit and the reason are left unset, as documented on
     * {@link #FatalProcessingException(String)}.
     *
     * <p>Side effects: none beyond throwable construction. <strong>No JVM termination occurs.</strong>
     * The cause is neither inspected, unwrapped, re-thrown nor logged.
     *
     * @param abendMessage the abend message and the detail message, carrying {@code ABEND-MSG}.
     *                     {@code null} is replaced by {@link #DEFAULT_ABEND_MESSAGE}; a blank value is
     *                     not. It must describe the condition, never the data
     * @param cause        the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                     {@code null} is permitted and records that there is no underlying failure to
     *                     attribute; a non-null value is always retained
     */
    public FatalProcessingException(String abendMessage, Throwable cause) {
        super(substituteAbendMessage(abendMessage), cause);
        this.abendCode = null;
        this.abendCulprit = null;
        this.abendReason = null;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend carrying the complete {@code CABENDD.CPY} payload.
     *
     * <p>The full form, reproducing what the legacy caller at
     * {@code app/cbl/COACTUPC.cbl:L2633-L2640 @ 7756d89} sets before entering the abend routine: a
     * code, a culprit, a reason and a message. The parameter order follows the copybook's field order
     * at {@code app/cpy/CSMSG02Y.cpy:L21-L29 @ 7756d89} so that the two can be read side by side.
     *
     * <p>Only the message is substituted. The other three are passed through exactly as supplied,
     * {@code null} included, because the source substitutes {@code ABEND-MSG} alone at
     * {@code app/cbl/COACTUPC.cbl:L4205-L4207 @ 7756d89}. None of the four is measured, padded or
     * truncated to its legacy width.
     *
     * <p>Side effects: none beyond throwable construction. <strong>No JVM termination occurs.</strong>
     *
     * @param abendCode    the four character display code, carrying {@code ABEND-CODE PIC X(4)}.
     *                     {@code null} and blank are both permitted and are stored unchanged. This is
     *                     the online display code and is unrelated to {@link #BATCH_ABEND_CODE}
     * @param abendCulprit the component that raised the abend, carrying
     *                     {@code ABEND-CULPRIT PIC X(8)}. {@code null} and blank are both permitted and
     *                     are stored unchanged; a supplied value is never overwritten
     * @param abendReason  the reason, carrying {@code ABEND-REASON PIC X(50)}. {@code null} and blank
     *                     are both permitted and are stored unchanged. It must describe the condition
     *                     and must never contain a record image, a credential or a personally
     *                     identifiable value, because it is logged
     * @param abendMessage the abend message and the detail message, carrying
     *                     {@code ABEND-MSG PIC X(72)}. {@code null} is replaced by
     *                     {@link #DEFAULT_ABEND_MESSAGE}; a blank value is <strong>not</strong>
     *                     replaced. It must describe the condition, never the data
     */
    public FatalProcessingException(String abendCode, String abendCulprit, String abendReason,
            String abendMessage) {
        super(substituteAbendMessage(abendMessage));
        this.abendCode = abendCode;
        this.abendCulprit = abendCulprit;
        this.abendReason = abendReason;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Creates a fatal abend carrying the complete {@code CABENDD.CPY} payload and preserving the
     * throwable that caused it.
     *
     * <p>The most complete form, and the one to prefer at an I/O guard: it records everything the
     * legacy operator had and everything the Java stack knows. Field semantics are exactly as
     * documented on {@link #FatalProcessingException(String, String, String, String)}, and the cause is
     * delegated to {@link CardDemoException} exactly as supplied and never dropped.
     *
     * <p>Side effects: none beyond throwable construction. <strong>No JVM termination occurs.</strong>
     *
     * @param abendCode    the four character display code, carrying {@code ABEND-CODE PIC X(4)}.
     *                     {@code null} and blank are both permitted and are stored unchanged
     * @param abendCulprit the component that raised the abend, carrying
     *                     {@code ABEND-CULPRIT PIC X(8)}. {@code null} and blank are both permitted and
     *                     are stored unchanged
     * @param abendReason  the reason, carrying {@code ABEND-REASON PIC X(50)}. {@code null} and blank
     *                     are both permitted and are stored unchanged. It must describe the condition,
     *                     never the data
     * @param abendMessage the abend message and the detail message, carrying
     *                     {@code ABEND-MSG PIC X(72)}. {@code null} is replaced by
     *                     {@link #DEFAULT_ABEND_MESSAGE}; a blank value is not
     * @param cause        the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                     {@code null} is permitted; a non-null value is always retained
     */
    public FatalProcessingException(String abendCode, String abendCulprit, String abendReason,
            String abendMessage, Throwable cause) {
        super(substituteAbendMessage(abendMessage), cause);
        this.abendCode = abendCode;
        this.abendCulprit = abendCulprit;
        this.abendReason = abendReason;
        this.abendMessage = substituteAbendMessage(abendMessage);
    }

    /**
     * Applies the legacy default-message substitution.
     *
     * <p>Reproduces {@code IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.' TO
     * ABEND-MSG} at {@code app/cbl/COACTUPC.cbl:L4205-L4207 @ 7756d89}. The trigger is a
     * {@code null} reference and nothing else: a blank or whitespace-only value is the analogue of the
     * copybook's {@code VALUE SPACES} initialisation
     * ({@code app/cpy/CSMSG02Y.cpy:L28-L29 @ 7756d89}), which the source does not substitute, so it is
     * returned untouched. Testing {@code isBlank()} here would fire where the source does not -
     * severity High.
     *
     * <p>It is {@code static} so that it can be evaluated in a {@code super} argument, and it is a pure
     * function: one reference comparison, no formatting, no case conversion, no locale, no charset and
     * no clock. Being deterministic is what lets the parity gates compare the substituted text byte
     * for byte.
     *
     * @param abendMessage the candidate message, which may be {@code null}, blank or populated
     * @return {@link #DEFAULT_ABEND_MESSAGE} when {@code abendMessage} is {@code null}; otherwise
     *         {@code abendMessage} exactly as supplied, blank values included
     */
    private static String substituteAbendMessage(String abendMessage) {
        return abendMessage == null ? DEFAULT_ABEND_MESSAGE : abendMessage;
    }

    /**
     * Returns the four character abend code carried by {@code ABEND-CODE PIC X(4)}
     * ({@code app/cpy/CSMSG02Y.cpy:L22 @ 7756d89}).
     *
     * <p>Text rather than a number, and unrelated to the numeric {@link #BATCH_ABEND_CODE}: the
     * copybook field is a display code used by the online path, whereas the batch terminator carries a
     * signed binary value. Side effects: none.
     *
     * @return the abend code exactly as supplied, or {@code null} when it was not supplied - the
     *         normal case for the batch path and for both message-only constructors
     */
    public String getAbendCode() {
        return abendCode;
    }

    /**
     * Returns the culprit component carried by {@code ABEND-CULPRIT PIC X(8)}
     * ({@code app/cpy/CSMSG02Y.cpy:L24 @ 7756d89}).
     *
     * <p>The Java analogue of the legacy routine's own program name, which it moves into the field at
     * {@code app/cbl/COACTUPC.cbl:L4209 @ 7756d89}, overwriting its caller. A value supplied here is
     * honoured instead of overwritten. Side effects: none.
     *
     * @return the culprit exactly as supplied, or {@code null} when it was not supplied
     */
    public String getAbendCulprit() {
        return abendCulprit;
    }

    /**
     * Returns the reason carried by {@code ABEND-REASON PIC X(50)}
     * ({@code app/cpy/CSMSG02Y.cpy:L26 @ 7756d89}).
     *
     * <p>A free-text operator diagnostic bound by the condition-never-data contract, because the legacy
     * routine sends the whole block to a terminal and this value is logged. Side effects: none.
     *
     * @return the reason exactly as supplied, or {@code null} when it was not supplied. A blank value
     *         is returned blank, mirroring the copybook's {@code VALUE SPACES} initialisation
     */
    public String getAbendReason() {
        return abendReason;
    }

    /**
     * Returns the abend message carried by {@code ABEND-MSG PIC X(72)}
     * ({@code app/cpy/CSMSG02Y.cpy:L28 @ 7756d89}), after the low-values substitution.
     *
     * <p>The only field subject to substitution, so it is <strong>never {@code null}</strong> and
     * always equals {@link Throwable#getMessage()}. Side effects: none.
     *
     * @return the supplied message exactly as supplied when it was non-null, blank values included;
     *         otherwise {@link #DEFAULT_ABEND_MESSAGE}
     */
    public String getAbendMessage() {
        return abendMessage;
    }
}
