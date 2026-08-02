/*
 * ******************************************************************
 * Program     : ReportController.java
 * Application : CardDemo
 * Type        : Spring Boot REST Controller
 * Function    : Transaction-report submission endpoint. Publishes to the report-jobs queue,
 *               replacing EXEC CICS WRITEQ TD QUEUE('JOBS').
 * Source      : app/csd/CARDDEMO.CSD transaction CR00 and DEFINE TDQUEUE(JOBS)
 *               -> app/cbl/CORPT00C.cbl (649 lines), mapset CORPT00
 *               -> app/cpy-bms/CORPT00.CPY (17 input fields) @ 7756d89
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
package com.cardemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;

import jakarta.validation.Valid;

/**
 * The transaction-report submission surface: CICS transaction {@code CR00}, its program
 * {@code app/cbl/CORPT00C.cbl} (649 lines, 10 paragraph labels) and the mapset {@code CORPT00} that
 * program painted.
 *
 * <p>The CSD triple is the contract this class replaces. {@code app/csd/CARDDEMO.CSD:L409} declares
 * {@code DEFINE TRANSACTION(CR00) GROUP(CARDDEMO)} and {@code :L410} names {@code PROGRAM(CORPT00C)}; the
 * program itself is registered by {@code DEFINE PROGRAM(CORPT00C)} at {@code :L242}; and the seventeen
 * screen fields the conversation exchanged are declared by the generated symbolic map
 * {@code app/cpy-bms/CORPT00.CPY}. One transaction becomes one operation, so this class carries
 * <strong>exactly one</strong> request-mapped handler.
 *
 * <h2>What it does</h2>
 *
 * <p>It accepts a report-submission form over HTTP, hands it to {@code ReportSubmissionService} once, and
 * translates that call's single outcome into a status code. It is a boundary adapter and nothing else: it
 * holds no business rule, resolves no reporting period, performs no date arithmetic, validates no date,
 * assembles no composite value, reads no database and touches no queue. Every one of those behaviours lives
 * in the service, which is where the paragraph-for-paragraph translation of
 * {@code app/cbl/CORPT00C.cbl} belongs.
 *
 * <p>The <strong>side effect</strong> of a successful call is one message published to the report-jobs FIFO
 * queue. The report itself is <em>not</em> produced here and is not produced synchronously anywhere: a
 * listener on the batch tier consumes that message and runs the report job. That is why the success status
 * is {@code 202 Accepted} rather than {@code 200 OK} - the work has been accepted for asynchronous
 * execution, and nothing about the report exists yet when the response is written.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and unit-test the module with {@code ./mvnw -B -ntp test}; the full gate is
 * {@code ./mvnw -B -ntp clean verify}, which compiles with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so a single unused import here fails the build. Run the application with the
 * {@code local} profile against the Compose topology, which supplies PostgreSQL and the LocalStack
 * endpoint the queue client is pointed at. Unit tests for this class belong in
 * {@code src/test/java/com/cardemo/unit} and drive it through {@code MockMvc} with a stubbed
 * service; the end-to-end contract check belongs in {@code src/test/java/com/cardemo/e2e} and exercises
 * this operation as one of the seventeen against a real application context. Because this class holds no
 * static mutable state and takes its one collaborator through its constructor, both tiers can construct it
 * directly without a context.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>This class reads no property of its own.</strong> Its base path is the compile-time constant
 * {@value #BASE_PATH} and there is nothing else to configure at this layer. The queue the operation
 * ultimately publishes to is configured under {@code carddemo.aws.sqs} in
 * {@code src/main/resources/application.yml}: {@code carddemo.aws.sqs.report-queue} carries the physical
 * queue name and is indirected to an environment variable with <strong>no literal default</strong>, so an
 * unset value fails startup rather than silently publishing into a queue nobody consumes;
 * {@code carddemo.aws.sqs.report-queue-logical-name} and
 * {@code carddemo.aws.sqs.report-message-group-id} are both fixed at {@code carddemo-report-jobs}, the
 * latter being a deterministic FIFO group rather than a generated one. All of it is bound by the service,
 * and every queue interaction targets LocalStack: <strong>there are zero live cloud credentials, and no
 * code path in this class can reach a live endpoint</strong>, which is guaranteed structurally because this
 * class references no cloud client, template or SDK type at all.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <table border="1">
 *   <caption>Every status this operation can return, and what to do about it</caption>
 *   <tr><th>Status</th><th>Condition</th><th>First thing to check</th></tr>
 *   <tr>
 *     <td>{@code 400}</td>
 *     <td>{@code ValidationException}: no period selector, more than one period selector resolved to a
 *         rejected period, a blank or out-of-range custom-range component, an assembled date the date
 *         validator rejects, or a confirmation that is blank or unrecognised</td>
 *     <td>The {@code detail} carries the legacy message literal verbatim and the {@code field} property
 *         names the symbolic-map field the 3270 cursor would have been parked on. {@code failureKind}
 *         separates a blank value from a wrong one</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 401}</td>
 *     <td>No usable authenticated identity reached the operation</td>
 *     <td>The bearer token is absent, expired or anonymous. Only sign-on {@code CC00} is unauthenticated;
 *         every other operation requires a token</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 202}</td>
 *     <td>Confirmed and published</td>
 *     <td>Nothing. If no report appears, the fault is downstream: check the queue depth and the batch
 *         listener, not this operation</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 200}</td>
 *     <td>The confirmation was declined, so nothing was published</td>
 *     <td>Expected. This is a distinct outcome from a rejection and from a success, and it is deliberately
 *         neither {@code 202} nor {@code 400}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 502}</td>
 *     <td>{@code FileAccessException}: the publish itself failed</td>
 *     <td>The {@code detail} is {@code Unable to Write TDQ (JOBS)...} byte for byte. Check that the queue
 *         exists and that the emulator is reachable; the underlying cause is attached to the exception and
 *         logged, never returned</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 503}</td>
 *     <td>{@code FileUnavailableException}: the queue could not be opened at all</td>
 *     <td>The queue-provisioning step has not run, or the emulator is down. Retry is meaningful here,
 *         which is why it is separated from {@code 502}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 500}</td>
 *     <td>{@code FatalProcessingException} or any other typed CardDemo failure</td>
 *     <td>The response body is deliberately uninformative. Diagnose from the correlation identifier in the
 *         structured log, where the cause is recorded at {@code ERROR}</td>
 *   </tr>
 * </table>
 *
 * <h2>Finding, High: the monthly period is the FULL current calendar month</h2>
 *
 * <p><strong>Severity: High. Recorded as BLOCKER 5.5. Locator: {@code app/cbl/CORPT00C.cbl:L212-L238}.
 * Resolution: the source governs; the specification prose is superseded.</strong>
 *
 * <p>The plan prose at section 0.7.5.2 describes the monthly period as ending on the current day. The
 * source does not support that reading. {@code :L213} opens the monthly arm, {@code :L217-L219} build the
 * start date from the current year, the current month and the literal {@code '01'}, and then
 * {@code :L223-L230} compute the end date: {@code MOVE 1 TO WS-CURDATE-DAY} discards today's day outright,
 * {@code ADD 1 TO WS-CURDATE-MONTH} advances the month, {@code IF WS-CURDATE-MONTH > 12} rolls the year,
 * and {@code COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)}
 * subtracts one day from the first of the next month. Because that computation writes through a
 * redefinition of the same year, month and day subfields, the moves at {@code :L232-L234} emit the
 * <strong>last day of the current month</strong>. The period is a full calendar month, first day through
 * last day.
 *
 * <p>The consequence of getting it wrong is not cosmetic. An end date of "today" would differ from the
 * source on every day of the month except the last, so every report the batch tier produced would diverge
 * from the parity baseline. The arithmetic is implemented in the service against an injected clock, so no
 * ambient time zone participates in it and the boundary is assertable under test on any date.
 *
 * <h2>Finding, Medium: the value-quoting confirmation message belongs to CR00, not CB00</h2>
 *
 * <p><strong>Severity: Medium. Locators: {@code app/cbl/CORPT00C.cbl:L483-L491} and
 * {@code app/cbl/COBIL00C.cbl:L187}.</strong>
 *
 * <p>The message that <em>quotes the offending value back to the caller</em> -
 * {@code "x" is not a valid value to confirm...} - is <strong>this</strong> transaction's, built by
 * {@code STRING} from a quotation mark, {@code CONFIRMI OF CORPT0AI DELIMITED BY SPACE} and the literal
 * suffix. Searching the whole corpus for that suffix returns {@code app/cbl/CORPT00C.cbl} and nothing
 * else. Bill payment, transaction {@code CB00}, uses the fixed
 * {@code Invalid value. Valid values are (Y/N)...} at {@code app/cbl/COBIL00C.cbl:L187} with no echo at
 * all. The two must not be swapped, and the echoing form must not be introduced into bill payment.
 *
 * <h2>Finding, Low: the embedded job deck is seventeen cards, not eighteen</h2>
 *
 * <p><strong>Severity: Low, documentation only, no code impact. Locator:
 * {@code app/cbl/CORPT00C.cbl:L81-L127}.</strong>
 *
 * <p>{@code 01 JOB-DATA.} declares <strong>seventeen</strong> eighty-byte card images, counted directly as
 * the {@code 05} entries of {@code 02 JOB-DATA-1}: fourteen plain {@code 05 FILLER PIC X(80) VALUE}
 * literals plus the three named multi-part groups {@code FILLER-1}, {@code FILLER-2} and {@code FILLER-3},
 * each of which sums to eighty bytes. The plan prose says eighteen and is superseded. The count carries no
 * consequence either way, because the whole deck collapses into one typed message; it is recorded because
 * Rule 1 Clause F requires findings to be evidence-based rather than inherited.
 *
 * <h2>Mechanism substitutions, each labelled</h2>
 *
 * <ul>
 *   <li><strong>The embedded job deck becomes one typed message.</strong> The seventeen eighty-byte card
 *       images at {@code app/cbl/CORPT00C.cbl:L81-L127}, redefined as an array of up to a thousand slots,
 *       collapse into a single typed queue message carrying the report name and the two dates. Recorded in
 *       {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}.</li>
 *   <li><strong>The transient data queue write becomes a queue publish.</strong>
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at {@code app/cbl/CORPT00C.cbl:L515-L537} becomes one
 *       FIFO publish, performed entirely inside the service.</li>
 *   <li><strong>The JES2 internal reader becomes a queue listener.</strong> The queue's
 *       {@code DDNAME(INREADER)} handed its cards to the internal reader; on the target side a listener
 *       consumes the message and maps it onto batch job parameters.</li>
 *   <li><strong>The eighty-byte fixed record becomes the fixed message shape.</strong>
 *       {@code DEFINE TDQUEUE(JOBS) ... RECORDSIZE(80) RECORDFORMAT(FIXED) ... DISPOSITION(MOD)} at
 *       {@code app/csd/CARDDEMO.CSD:L499-L505} fixes both the record width the deck was written in and,
 *       through {@code DISPOSITION(MOD)}, the strict append ordering that a single deterministic FIFO
 *       message group reproduces.</li>
 *   <li><strong>The misspelled paragraph name is preserved, not corrected.</strong>
 *       {@code WIRTE-JOBSUB-TDQ.} at {@code app/cbl/CORPT00C.cbl:L515} is misspelled in the source. It is
 *       cited verbatim wherever it is named, in the service's paragraph map and in the traceability
 *       matrix, and it is never silently repaired.</li>
 *   <li><strong>The pseudo-conversational screen exchange becomes one stateless call.</strong> Under
 *       Transformation Rule 7, {@code RETURN TRANSID ... COMMAREA} becomes stateless REST plus token
 *       claims with no server-side session state. The confirmation handshake therefore arrives as an
 *       explicit field on this single request rather than as a remembered conversation turn.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not carry</h2>
 *
 * <ul>
 *   <li><strong>No session and no conversational state.</strong> Nothing here creates a session or reads
 *       one. The stateless session policy is declared centrally in {@code SecurityConfig}, and this class
 *       adds nothing that could contradict it.</li>
 *   <li><strong>No navigation or re-entry fields.</strong> {@code app/cpy/COCOM01Y.cpy} declares
 *       {@code CDEMO-FROM-TRANID X(04)} at {@code :L21}, {@code CDEMO-FROM-PROGRAM X(08)} at {@code :L22},
 *       {@code CDEMO-TO-TRANID X(04)} at {@code :L23}, {@code CDEMO-TO-PROGRAM X(08)} at {@code :L24},
 *       {@code CDEMO-PGM-CONTEXT 9(01)} at {@code :L29} with {@code 88 CDEMO-PGM-ENTER VALUE 0} at
 *       {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at {@code :L31}, and
 *       {@code CDEMO-LAST-MAP X(7)} and {@code CDEMO-LAST-MAPSET X(7)} at {@code :L43-L44} - note the
 *       width of seven, not eight. <strong>None appears in any request or response.</strong> Routing is
 *       the URL, and the enter-versus-re-enter flag collapses into stateless request handling.</li>
 *   <li><strong>No pagination.</strong> {@code app/cpy/COCOM01Y.cpy} declares no page-number field and no
 *       next-page flag, so there is nothing to page. The list page sizes configured under
 *       {@code carddemo.pagination} belong to other operations and to the batch tier, and none of them is
 *       a concern of this class.</li>
 *   <li><strong>No cloud type.</strong> No queue, notification or object-storage client, template or SDK
 *       type is imported, injected or named in executable code here.</li>
 *   <li><strong>No temporal type and no parsing.</strong> The six custom-range components arrive as
 *       discrete text exactly as the six screen fields declare them, and this class does not combine,
 *       parse, reformat, reorder or validate any of them. Timestamp-shaped values are text throughout the
 *       migration because the corpus produces renderings a temporal type cannot reproduce.</li>
 *   <li><strong>No monetary value.</strong> This operation carries none. Where the migration does carry
 *       one it is a fixed-scale decimal compared with {@code compareTo}, and no binary approximate-numeric
 *       type appears anywhere.</li>
 *   <li><strong>No cursor semantics.</strong> {@code MOVE -1 TO CONFIRML} at
 *       {@code app/cbl/CORPT00C.cbl:L470} and {@code MOVE -1 TO MONTHLYL} at {@code :L534} parked the 3270
 *       cursor. <strong>Cursor repositioning has no Java counterpart and is not implemented.</strong> This
 *       class invents no cursor-hint field; where a symbolic-map field name appears in a response or a
 *       problem detail it is the service's own field marker, carried so that a rejection can be attributed
 *       to a field, and it comes with no attribute byte, no colour, no map name and no terminal
 *       behaviour.</li>
 *   <li><strong>No function-key dispatcher.</strong> {@code app/cpy/CVCRD01Y.cpy} action state and the
 *       procedural {@code YYYY-STORE-PFKEY.} of {@code app/cpy/CSSTRPFY.cpy:L17}, which evaluates
 *       {@code EIBAID}, map onto controller-level action mapping: one action, one URL. There is no generic
 *       action parameter and no re-implemented key cascade. {@code app/cpy/CSSTRPFY.cpy} is a
 *       procedure-division copybook and yields no type and no import, and the
 *       {@code app/cpy/CVCRD01Y.cpy} fields {@code CCARD-LAST-PROG}, {@code CCARD-RETURN-TO-PROG},
 *       {@code CCARD-RETURN-FLAG} and {@code CCARD-FUNCTION} are commented out in the source and have no
 *       counterpart at all. The CICS-supplied action, attribute and screen-attribute copybooks are absent
 *       from this repository and are not imported.</li>
 *   <li><strong>No advice class and no shared base.</strong> Status selection is contextual, so it is
 *       performed here rather than in a global handler; there is no such handler anywhere in the
 *       repository, and adding one would duplicate the decision this class already owns. There is likewise
 *       no base controller and no shared header helper, which Rule 1 Clause C's consistent-structure
 *       requirement is served by rather than harmed by, because the widths the header fields carry differ
 *       between maps.</li>
 *   <li><strong>No batch reject-code surface.</strong> The reject outcomes of the batch tier are business
 *       results that drive a job exit status. They are never thrown and never mapped to a status code, and
 *       that matters here precisely because the job this operation submits runs in the tier that owns
 *       them.</li>
 * </ul>
 *
 * <h2>Access control</h2>
 *
 * <p>This operation requires a valid bearer token; only sign-on {@code CC00} is unauthenticated. Identity
 * is taken <strong>only</strong> from the authenticated principal - the subject is the upper-cased
 * {@code CDEMO-USER-ID} of {@code app/cpy/COCOM01Y.cpy:L25} and the authority is that copybook's
 * {@code CDEMO-USER-TYPE} at {@code :L26}, whose sole values are declared by
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at {@code :L28}. Nothing is read from the request body, the query
 * string or a session.
 *
 * <p><strong>No user class is required beyond being authenticated, and that is an evidence-based
 * decision.</strong> A per-transaction authorisation attribute for {@code CR00} is
 * {@code Not available} from {@code app/csd/CARDDEMO.CSD:L409-L415}, which declares none. The only
 * authorisation evidence in the corpus is the main-menu option table: {@code app/cpy/COMEN02Y.cpy:L74-L78}
 * declares slot 9, {@code 'Transaction Reports'}, targeting {@code 'CORPT00C'} with the user-type gate
 * {@code 'U'}. Reporting is therefore open to a standard user, so imposing an administrator-only rule here
 * would be an invention. The authoritative rules are declared centrally in {@code SecurityConfig}; this
 * class states no method-level rule that could contradict them, and it introduces no operation outside the
 * seventeen the CSD sources.
 */
@RestController
@RequestMapping(ReportController.BASE_PATH)
public class ReportController {

    /**
     * The base path every operation on this controller hangs from.
     *
     * <p>A compile-time constant so that the class-level {@code @RequestMapping} and the Javadoc that
     * documents it cannot drift apart, and so that a test can reference the path it exercises rather than
     * repeating a string literal.
     */
    static final String BASE_PATH = "/api/reports";

    /**
     * The CICS transaction this controller replaces, {@code app/csd/CARDDEMO.CSD:L409}. Logged rather than
     * returned, so that a log line names the legacy transaction an operator would recognise.
     */
    private static final String TRANSACTION_ID = "CR00";

    /**
     * The COBOL program the transaction fronted, {@code app/csd/CARDDEMO.CSD:L410} and
     * {@code app/cbl/CORPT00C.cbl}.
     */
    private static final String PROGRAM = "CORPT00C";

    /**
     * The four-character abend code of the legacy abend routine, rendered from the shared constant so that
     * the value is stated in exactly one place in the codebase.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /**
     * The abend culprit recorded when an untyped runtime failure escapes the service. It carries the
     * program name of {@code ABEND-CULPRIT PIC X(8)}, which is eight characters wide, and
     * {@value #PROGRAM} fits it exactly.
     */
    private static final String ABEND_CULPRIT = PROGRAM;

    /**
     * The abend reason recorded when an untyped runtime failure escapes the service, standing in for
     * {@code ABEND-REASON PIC X(50)}.
     */
    private static final String ABEND_REASON = "REPORT SUBMISSION FAILED UNEXPECTEDLY";

    /**
     * The abend message recorded when an untyped runtime failure escapes the service, standing in for
     * {@code ABEND-MSG PIC X(72)}.
     */
    private static final String ABEND_MESSAGE = "UNEXPECTED ERROR IN CR00 REPORT SUBMISSION.";

    /**
     * The problem title used for every rejection, which is a caller-correctable condition.
     */
    private static final String VALIDATION_PROBLEM_TITLE = "Report submission rejected";

    /**
     * The problem title used when the report-jobs queue itself could not be written or opened.
     */
    private static final String QUEUE_PROBLEM_TITLE = "Report job submission failed";

    /**
     * The problem title used for an abend and for any other typed failure.
     */
    private static final String FAILURE_PROBLEM_TITLE = "Report submission failed";

    /**
     * The fixed detail returned for an abend and for any other typed failure.
     *
     * <p>Deliberately uninformative. Rule 1 Clause D requires that nothing sensitive reach a response, and
     * the cheapest way to guarantee it for an unclassified failure is to return a constant. The cause stays
     * attached to the exception and is logged at {@code ERROR}, so diagnosis proceeds from the correlation
     * identifier rather than from the response body.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The report submission could not be completed. Retry the request or contact support with the "
                    + "correlation identifier.";

    /**
     * The problem-detail property naming the symbolic-map field a rejection is attributed to.
     */
    private static final String FIELD_PROPERTY = "field";

    /**
     * The problem-detail property distinguishing a blank value from a value that was supplied and wrong.
     */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * The problem-detail property carrying the legacy abend code.
     */
    private static final String ABEND_CODE_PROPERTY = "abendCode";

    /**
     * The problem-detail property carrying the legacy batch return code.
     */
    private static final String RETURN_CODE_PROPERTY = "returnCode";

    /**
     * The logger. Static and final, so it is not mutable state; the correlation, trace and span identifiers
     * every line carries are placed in the diagnostic context by the correlation filter, and this class
     * neither adds, renames, overwrites, removes nor clears any of them.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReportController.class);

    /**
     * The one collaborator: the service that replaces {@code app/cbl/CORPT00C.cbl} and owns the queue
     * publish.
     */
    private final ReportSubmissionService reportSubmissionService;

    /**
     * Assembles the controller.
     *
     * <p>Constructor injection is the only injection form used here: no field injection, no setter
     * injection and no annotation-driven lookup. The collaborator is therefore final and non-null for the
     * lifetime of the bean, the class holds no global mutable state, and a unit test can construct the
     * controller with a single stub and no application context. The argument is validated rather
     * than trusted, because a null collaborator would otherwise surface as a failure on the first request
     * instead of at context refresh.
     *
     * @param reportSubmissionService the report-submission service replacing
     *                                {@code app/cbl/CORPT00C.cbl}; must not be null.
     * @throws IllegalArgumentException if the service is null, which is a bean-wiring defect rather than a
     *                                 request-time condition
     */
    public ReportController(final ReportSubmissionService reportSubmissionService) {

        if (reportSubmissionService == null) {
            throw new IllegalArgumentException(
                    "reportSubmissionService must not be null; it is the replacement for "
                            + "app/cbl/CORPT00C.cbl");
        }

        this.reportSubmissionService = reportSubmissionService;
    }

    /**
     * Operation 1 of 1 on this controller, and operation 13 of the seventeen the REST surface exposes.
     * Submits a transaction report for printing.
     *
     * <h2>Provenance</h2>
     *
     * <p>Replaces CICS transaction {@code CR00} - {@code DEFINE TRANSACTION(CR00)} at
     * {@code app/csd/CARDDEMO.CSD:L409} naming {@code PROGRAM(CORPT00C)} at {@code :L410} - and the program
     * it fronted, {@code app/cbl/CORPT00C.cbl}, 649 lines with 10 paragraph labels, which painted mapset
     * {@code CORPT00}. Specifically it is the Java form of the enter-key path: {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/CORPT00C.cbl:L208} resolving the period, then {@code SUBMIT-JOB-TO-INTRDR} at
     * {@code :L462} running the confirmation gate, then {@code WIRTE-JOBSUB-TDQ.} at {@code :L515} writing
     * to the queue. The seventeen fields the request body carries are the seventeen input fields of the
     * generated symbolic map {@code app/cpy-bms/CORPT00.CPY}, one for one.
     *
     * <h2>Purpose</h2>
     *
     * <p>To turn a report request into exactly one instruction for the batch tier. The legacy program did
     * this by writing a whole job deck to an extrapartition transient data queue whose
     * {@code DDNAME(INREADER)} handed it to the JES2 internal reader; the target does it by publishing one
     * typed message to a FIFO queue that a batch listener consumes.
     *
     * <h2>Inputs</h2>
     *
     * <p>The authenticated principal, and a {@code ReportRequest} body carrying the seventeen map fields:
     * three independent one-character period selectors, six discrete custom-range date components declared
     * month, day, year for the start date and then the end date, a one-character confirmation gate, the six
     * screen header fields and the message field. Bean validation enforces the field widths the symbolic
     * map declares; a body that violates one is rejected by the framework as {@code 400} before this method
     * is entered.
     *
     * <p><strong>Nothing is combined, parsed, reordered or validated here.</strong> The six components stay
     * discrete text across the boundary because the service validates each one individually and reports a
     * different message for a component that is blank than for a component that is present and wrong.
     * Assembling them into a single value at the boundary would destroy that distinction, and coercing a
     * blank component to a zero would submit an invalid date where the source reported a blank one.
     *
     * <h2>Outputs and side effects</h2>
     *
     * <p>The <strong>side effect</strong> of the confirmed path is one message published to the report-jobs
     * FIFO queue, carrying the report name and the two resolved dates. <strong>The report is produced
     * asynchronously by the batch tier</strong>, so a {@code 202 Accepted} response means the instruction
     * was accepted and nothing more; no report exists yet. The declined path publishes nothing at all.
     *
     * <p>The body is the service's own screen result, which carries the cleared form, the error and success
     * indicators and the field marker. Its message component carries the legacy message literal: on the
     * confirmed path the report name followed by {@code  report submitted for printing ...}, and on the
     * declined path blanks, exactly as {@code INITIALIZE-ALL-FIELDS} at {@code app/cbl/CORPT00C.cbl:L633}
     * left it.
     *
     * <h2>The three report periods</h2>
     *
     * <p>The service resolves the period from the three selectors, which
     * {@code app/cbl/CORPT00C.cbl:L212} evaluates in a single first-match-wins {@code EVALUATE TRUE}, so
     * monthly beats yearly beats custom when more than one arrives. A single enumeration could not express
     * more than one selector at once, which is why the request carries three independent fields.
     *
     * <ul>
     *   <li><strong>Monthly</strong> ({@code app/cbl/CORPT00C.cbl:L213-L238}), report name {@code Monthly}
     *       - the <strong>FULL current calendar month</strong>, from the first day through the
     *       <strong>last</strong> day. The start date is the current year and month with the literal
     *       {@code '01'}; the end date comes from {@code MOVE 1 TO WS-CURDATE-DAY},
     *       {@code ADD 1 TO WS-CURDATE-MONTH}, {@code IF WS-CURDATE-MONTH > 12} rolling the year, and then
     *       one day subtracted from the resulting packed value, which lands on the month end. <strong>The
     *       plan prose at section 0.7.5.2 states this incorrectly as ending on the current day; that is
     *       recorded as BLOCKER 5.5, severity High, and the source governs.</strong></li>
     *   <li><strong>Yearly</strong> ({@code app/cbl/CORPT00C.cbl:L239-L255}), report name {@code Yearly} -
     *       the first through the last day of the current year, built from the literals {@code '01'} and
     *       {@code '01'} for the start and {@code '12'} and {@code '31'} for the end, so the period runs
     *       from {@code yyyy-01-01} to {@code yyyy-12-31}.</li>
     *   <li><strong>Custom</strong> ({@code app/cbl/CORPT00C.cbl:L256} and {@code :L381-L410}), report name
     *       {@code Custom} - the six discrete components, validated in <strong>month, day, year</strong>
     *       order, start date before end date. The first check is the start month, whose message is
     *       {@code Start Date - Month can NOT be empty...}; the declaration order of the six map fields is
     *       therefore the observable message order. Each assembled composite is then checked against the
     *       explicit format literal {@code YYYY-MM-DD} at {@code :L72} through the date service, whose
     *       result shape mirrors the legacy parameter block at {@code :L129-L135} - a date, a format, and a
     *       result group of a severity code, a filler and a message number. <strong>Validation outcomes,
     *       not merely parsing, are what must match</strong>, which is why no bare date parse stands in for
     *       it and why nothing the source rejects is quietly widened.</li>
     * </ul>
     *
     * <h2>The confirmation gate is four-way, and every arm is distinguishable</h2>
     *
     * <p>{@code SUBMIT-JOB-TO-INTRDR} at {@code app/cbl/CORPT00C.cbl:L462} has four arms, not two, so the
     * gate is never modelled as a two-state flag. Absent, blank and a supplied value are distinct states
     * and are never coerced into one another.
     *
     * <table border="1">
     *   <caption>The four arms and the status each produces</caption>
     *   <tr><th>Arm</th><th>Source</th><th>Result</th></tr>
     *   <tr>
     *     <td>blank</td>
     *     <td>{@code :L464-L472}</td>
     *     <td>{@code 400}, detail {@code Please confirm to print the } then the report name then
     *         {@code  report...}, {@code failureKind} of a blank value</td>
     *   </tr>
     *   <tr>
     *     <td>{@code 'Y'} or {@code 'y'}</td>
     *     <td>{@code :L478}</td>
     *     <td>{@code 202}, published</td>
     *   </tr>
     *   <tr>
     *     <td>{@code 'N'} or {@code 'n'}</td>
     *     <td>{@code :L480-L482}</td>
     *     <td>{@code 200}, the form cleared and nothing published</td>
     *   </tr>
     *   <tr>
     *     <td>anything else</td>
     *     <td>{@code :L483-L491}</td>
     *     <td>{@code 400}, detail {@code "x" is not a valid value to confirm...} with the submitted
     *         character echoed back, {@code failureKind} of a supplied but wrong value</td>
     *   </tr>
     * </table>
     *
     * <p>The two letter cases are matched as separate literals rather than by case folding, so no locale
     * participates in the comparison. The value-quoting message is <strong>this</strong> transaction's and
     * not bill payment's; see the class-level Medium finding.
     *
     * <p>The declined arm is the reason the confirmed path is {@code 202} and the declined path is
     * {@code 200}: the source distinguishes them - one reaches the queue write and the other returns from
     * the gate - and collapsing both into one status would lose the distinction a caller most needs, namely
     * whether a job was actually submitted.
     *
     * <h2>Configuration and defaults</h2>
     *
     * <p>This method reads no property. The queue it ultimately publishes to is configured under
     * {@code carddemo.aws.sqs} in {@code src/main/resources/application.yml}:
     * {@code carddemo.aws.sqs.report-queue} for the physical name, environment-indirected with no literal
     * default so that a missing value fails startup, and
     * {@code carddemo.aws.sqs.report-queue-logical-name} together with
     * {@code carddemo.aws.sqs.report-message-group-id} both fixed at {@code carddemo-report-jobs}. Every
     * queue interaction targets LocalStack and <strong>no live cloud credential exists on any path</strong>.
     *
     * <h2>Failure modes and troubleshooting</h2>
     *
     * <ul>
     *   <li>{@code 401} - no usable authenticated identity. The stateless counterpart of
     *       {@code IF EIBCALEN = 0} at {@code app/cbl/CORPT00C.cbl:L172-L174}, where a transaction reached
     *       without a communication area was routed to sign-on instead of being painted.</li>
     *   <li>{@code 400} - a rejection. The detail is the source's own literal and the {@code field}
     *       property names the symbolic-map field it belongs to: no period selector yields
     *       {@code Select a report type to print report...}; a blank or out-of-range custom component
     *       yields its own {@code can NOT be empty} or {@code Not a valid} literal; a rejected composite
     *       yields the corresponding date literal; and the two invalid confirmation arms yield the two
     *       literals tabulated above.</li>
     *   <li>{@code 502} - the publish failed. The detail is {@code Unable to Write TDQ (JOBS)...} byte for
     *       byte, the literal moved at {@code app/cbl/CORPT00C.cbl:L531}. That string is part of the
     *       observable contract and is never reworded. Check that the queue exists and the emulator is
     *       reachable.</li>
     *   <li>{@code 503} - the queue could not be opened. Separated from {@code 502} because a retry is
     *       meaningful.</li>
     *   <li>{@code 500} - an abend or another typed failure. The body is fixed; diagnose from the
     *       correlation identifier.</li>
     * </ul>
     *
     * <h2>Boundary conditions handled explicitly</h2>
     *
     * <p>An absent confirmation field, a blank one and a low-values one are three distinct states and none
     * is coerced to another; a blank month component is reported differently from a month outside one to
     * twelve; a day invalid for the month it belongs to is rejected by the date service and not by a range
     * test; a start date after the end date is a date-service outcome rather than a parse failure; and a
     * period selector that is blank, low-values or populated is three states per selector, not a flag.
     * Every one of those decisions is the service's, and this method neither pre-empts nor second-guesses
     * any of them - which is precisely why it can be relied on to reproduce them.
     *
     * <h2>What has no counterpart</h2>
     *
     * <p>{@code MOVE -1 TO CONFIRML} at {@code app/cbl/CORPT00C.cbl:L470} and
     * {@code MOVE -1 TO MONTHLYL} at {@code :L534} repositioned the 3270 cursor. <strong>Cursor
     * repositioning has no Java counterpart and is deliberately not implemented</strong>, and no
     * cursor-hint field is invented for it. The attention-identifier cascade likewise collapses: this
     * operation <em>is</em> the enter-key action, so the action is the URL and the method rather than a
     * parameter, and the return-to-menu key is ordinary client-side navigation.
     *
     * @param authentication the principal the security filter chain resolved, which is null when the
     *                       request reached the operation with no identity.
     * @param request        the report-submission form, bean-validated against the field widths the
     *                       symbolic map declares; never null once the framework has bound a body.
     * @return {@code 202 Accepted} when the report job was published, {@code 200 OK} when the confirmation
     *         was declined and nothing was published, or {@code 401 Unauthorized} when the request carried
     *         no usable identity
     * @throws ValidationException      when a period selector is absent, a custom-range component is blank
     *                                  or out of range, an assembled date is rejected, or the confirmation
     *                                  is blank or unrecognised; mapped to {@code 400} by
     *                                  {@link #handleRejection}
     * @throws FileUnavailableException when the report-jobs queue could not be opened; mapped to
     *                                  {@code 503} by
     *                                  {@link #handleQueueUnavailable}
     * @throws FileAccessException      when the publish failed; mapped to {@code 502} by
     *                                  {@link #handleQueueFailure}
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     *                                  failure; mapped to {@code 500} by
     *                                  {@link #handleAbend}
     */
    @PostMapping
    public ResponseEntity<ReportSubmissionService.ReportSubmissionScreen> submitReport(
            final Authentication authentication, @Valid @RequestBody final ReportRequest request) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of IF EIBCALEN = 0 at "
                    + "app/cbl/CORPT00C.cbl:L172-L174", TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final ReportSubmissionService.ReportSubmissionScreen screen = publishReportJob(request);

        if (screen.errorFlagOn()) {
            // app/cbl/CORPT00C.cbl:L480-L482, the declined arm: PERFORM INITIALIZE-ALL-FIELDS, raise the
            // error flag, send. The queue write at :L498-L508 sits inside IF NOT ERR-FLG-ON, so nothing was
            // published. The request was understood and answered, so it is 200 rather than 202 or 400.
            LOG.debug("Transaction {} program {} declined at the confirmation gate; nothing was published",
                    TRANSACTION_ID, PROGRAM);
            return ResponseEntity.ok(screen);
        }

        // app/cbl/CORPT00C.cbl:L447-L454, the confirmed arm: the deck was written, the fields were cleared
        // and the success notice was composed. The batch tier produces the report asynchronously, so the
        // instruction is accepted rather than completed.
        LOG.info("Transaction {} program {} submitted a report job for asynchronous processing",
                TRANSACTION_ID, PROGRAM);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(screen);
    }

    /**
     * Maps a rejection onto {@code 400 Bad Request}, carrying the legacy message literal verbatim.
     *
     * <p>Status selection is performed here rather than in a global advice class, because the choice is
     * contextual: the same exception type means "the caller can fix this" on a submission boundary and can
     * mean something else elsewhere. There is no such advice class anywhere in the repository and the nine
     * exception types carry no status annotation of their own, so each controller owns the decision for its
     * own operations.
     *
     * <p>The detail is the exception's message, which is the source's own literal - one of the six
     * {@code can NOT be empty} messages, one of the six {@code Not a valid} messages, one of the two date
     * messages, {@code Select a report type to print report...}, the confirmation prompt built from
     * {@code Please confirm to print the } and {@code  report...}, or
     * {@code "x" is not a valid value to confirm...} with the submitted character echoed. Those strings are
     * the observable contract of {@code CR00} and are surfaced unmodified.
     *
     * <p>Echoing the confirmation character back is safe <em>in this one place</em> because a confirmation
     * gate value is neither personal data nor a credential, it is at most one character wide, and it is
     * JSON-encoded on the way out. It is not a licence to echo input generally.
     *
     * <p>The {@code field} property names the symbolic-map field the 3270 cursor would have been parked on -
     * the confirmation field or the monthly selector - which is what lets a client attribute the rejection
     * without any cursor semantics being implemented. The {@code failureKind} property separates a blank
     * value from one that was supplied and wrong, reproducing the distinction that
     * {@code app/cpy/CSSETATY.cpy} draws between its not-OK and blank states.
     *
     * <p>The two properties are set under different conditions, and the asymmetry is deliberate rather
     * than an oversight. The field name is guarded, because {@code ValidationException.getFieldName()}
     * documents its result as null for a request-level failure and the type publishes
     * {@code hasFieldName()} precisely so a caller can ask. The failure kind is <strong>not</strong>
     * guarded, because {@code ValidationException.getFailureKind()} documents its result as never null
     * and every one of that type's four constructors substitutes {@code INVALID} for an absent value; a
     * null check there would be a branch no input can reach, which Rule 1 Clause B forbids. The message
     * is guarded for the third reason again: {@code Throwable.getMessage()} is genuinely nullable.
     *
     * <p>The message is deliberately <strong>not</strong> logged, because on one arm it contains a
     * caller-supplied character; the field and the failure kind are logged instead, which is what actually
     * diagnoses the fault.
     *
     * @param rejection the validation failure raised by the service; never null when the framework
     *                  dispatches here.
     * @return {@code 400 Bad Request} carrying a problem detail and the failure kind, plus the rejected
     *         field when the exception named one
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleRejection(final ValidationException rejection) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        final String rejectionMessage = rejection.getMessage();
        if (rejectionMessage != null) {
            problem.setDetail(rejectionMessage);
        }
        if (rejection.hasFieldName()) {
            problem.setProperty(FIELD_PROPERTY, rejection.getFieldName());
        }
        final ValidationException.FailureKind failureKind = rejection.getFailureKind();
        problem.setProperty(FAILURE_KIND_PROPERTY, failureKind.name());

        LOG.warn("Refused transaction {} with 400 for field {} and failure kind {}", TRANSACTION_ID,
                rejection.getFieldName(), failureKind);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Maps an unavailable report-jobs queue onto {@code 503 Service Unavailable}.
     *
     * <p>Distinct from a failed write, and the distinction is worth a status of its own: a queue that cannot
     * be opened is an environment condition a retry may resolve, whereas a write that failed against an
     * open queue is not. On the legacy side the two corresponded to different file statuses, and the
     * migration keeps them as different exception types precisely so that they need not be collapsed here.
     *
     * <p>The detail is the exception's message and the named resource is carried as the queue field when the
     * throwing site identified one, so an operator learns which queue to look at without the cause reaching
     * the response.
     *
     * @param unavailable the unavailable-resource failure; never null when the framework dispatches here.
     * @return {@code 503 Service Unavailable} carrying a problem detail
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleQueueUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle(QUEUE_PROBLEM_TITLE);

        final String unavailableMessage = unavailable.getMessage();
        if (unavailableMessage != null) {
            problem.setDetail(unavailableMessage);
        }

        LOG.error("Transaction {} could not reach the report-jobs queue; resource {}", TRANSACTION_ID,
                unavailable.resourceName().orElse("unidentified"), unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    /**
     * Maps a failed publish onto {@code 502 Bad Gateway}, surfacing the legacy literal byte for byte.
     *
     * <p>This is the Java form of the failure arm of {@code WIRTE-JOBSUB-TDQ.} at
     * {@code app/cbl/CORPT00C.cbl:L515-L537}: on any response other than normal the source displayed the
     * response and reason codes, raised the error flag, moved
     * {@code Unable to Write TDQ (JOBS)...} into the message at {@code :L531} and repainted the screen with
     * the cursor on the monthly selector. <strong>That literal is part of the observable contract, so it is
     * returned verbatim as the problem detail - three trailing periods and all - and is never reworded,
     * shortened or wrapped.</strong> The service raises the exception carrying it; this method surfaces it.
     *
     * <p>{@code 502} rather than {@code 500} because the failure is in a downstream dependency this
     * operation is a gateway to, not in the operation itself. The cause is attached to the exception and
     * logged at {@code ERROR}; it is never returned, which is the counterpart of the source displaying the
     * response and reason codes to the operator's log rather than to the terminal.
     *
     * @param failure the publish failure; never null when the framework dispatches here.
     * @return {@code 502 Bad Gateway} carrying the legacy queue-failure literal as its detail
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleQueueFailure(final FileAccessException failure) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle(QUEUE_PROBLEM_TITLE);

        final String failureMessage = failure.getMessage();
        if (failureMessage != null) {
            problem.setDetail(failureMessage);
        }

        LOG.error("Transaction {} failed to publish the report job; status {} resource {} operation {}",
                TRANSACTION_ID, failure.getExpandedStatus(), failure.getLogicalFileName(),
                failure.getOperation(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract as response
     * metadata.
     *
     * <p>The legacy abend routine populated a code, a culprit, a reason and a message and then called the
     * language-environment abend service, and the batch stream reported return code twelve. Both values are
     * carried as problem-detail properties so that the contract stays observable, while the detail itself is
     * fixed and reveals nothing about the cause. The cause remains attached to the exception and is logged
     * at {@code ERROR}, so diagnosis proceeds from the correlation identifier rather than from the response
     * body.
     *
     * @param abend the fatal failure; never null when the framework dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail plus the abend code and the
     *         batch return code
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        final String carriedAbendCode = abend.getAbendCode();
        problem.setProperty(ABEND_CODE_PROPERTY, carriedAbendCode == null ? ABEND_CODE : carriedAbendCode);
        problem.setProperty(RETURN_CODE_PROPERTY, FatalProcessingException.BATCH_RETURN_CODE);

        LOG.error("Transaction {} abended: code {} culprit {} reason {}", TRANSACTION_ID, carriedAbendCode,
                abend.getAbendCulprit(), abend.getAbendReason(), abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Maps every remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>The framework resolves the most specific handler, so a rejection, an unavailable queue, a failed
     * publish and an abend all reach their own method above and never arrive here. This method covers the
     * rest of the nine-type hierarchy, each member of which stands for a legacy file status or response
     * code that this operation has no more specific answer for. The detail is the same fixed string used for
     * an abend, for the same reason, and the cause is logged rather than returned.
     *
     * @param failure the typed failure; never null when the framework dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("Transaction {} failed with a typed CardDemo exception", TRANSACTION_ID, failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Delegates to the report-submission service exactly once and returns its screen result.
     *
     * <p>Extracted from {@link #submitReport} so that the handler reads as a sequence of decisions and the
     * abend translation is stated once. The attention identifier is supplied as the enter-key constant
     * rather than taken from the caller: this operation <em>is</em> the enter-key action of {@code CR00}, so
     * the action is expressed by the method and the URL. Accepting it as a request parameter would
     * re-implement the {@code EVALUATE EIBAID} cascade as an API surface, and there is deliberately no
     * generic action parameter anywhere on this controller.
     *
     * <p>Both catch clauses are load bearing and neither swallows anything. {@code CardDemoException} is
     * rethrown unchanged so that the declared status mapping applies to it, and because that type extends
     * {@code RuntimeException} the first clause is what stops the second from re-wrapping an
     * already-typed failure. Every other runtime failure becomes an abend carrying the four legacy abend
     * fields, <strong>with the original throwable preserved as the cause</strong>, so no root cause is ever
     * lost.
     *
     * @param request the report-submission form to hand to the service; never null here.
     * @return the service's screen result, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     *                                 failure
     */
    private ReportSubmissionService.ReportSubmissionScreen publishReportJob(final ReportRequest request) {

        try {
            return reportSubmissionService.submitScreen(
                    ReportSubmissionService.AttentionIdentifier.ENTER, request);
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, ABEND_CULPRIT, ABEND_REASON, ABEND_MESSAGE,
                    unexpected);
        }
    }

    /**
     * Reports whether a request carries no usable identity.
     *
     * <p>Three conditions are treated alike: a null principal, which is what a permissive filter chain
     * leaves behind; a principal reporting itself unauthenticated; and an anonymous token, which reports
     * itself <em>authenticated</em> and would therefore slip past a bare authentication test. Keeping the
     * three together is what makes {@code 401} mean "bring a token" rather than something vaguer.
     *
     * <p>Static and side-effect free, so it is trivially testable and cannot participate in any state. It
     * reads nothing but the principal - never a request body, never a query parameter and never a session -
     * which is the stateless replacement for the identity the communication area used to carry.
     *
     * @param authentication the principal the security filter chain resolved, which may be null.
     * @return true when the request carries no usable identity
     */
    private static boolean isUnauthenticated(final Authentication authentication) {
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }
}
