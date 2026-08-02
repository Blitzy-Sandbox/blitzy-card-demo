/*
 * ******************************************************************
 * Program     : BillingController.java
 * Application : CardDemo
 * Type        : Spring @RestController (online bill payment endpoint)
 * Function    : Bill payment endpoint. Pays the FULL current account
 *               balance.
 * Source      : app/csd/CARDDEMO.CSD transaction CB00
 *               -> app/cbl/COBIL00C.cbl (572 lines), mapset COBIL00
 *               -> app/cpy-bms/COBIL00.CPY (10 input fields) @ 7756d89
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
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.service.billing.BillPaymentService;

import jakarta.validation.Valid;

/**
 * The bill-payment endpoint: the HTTP replacement for CICS transaction {@code CB00}, the program it fronts,
 * {@code app/cbl/COBIL00C.cbl} (572 lines, 16 paragraph labels), and the mapset that program painted,
 * {@code COBIL00}.
 *
 * <p>The definitions are {@code app/csd/CARDDEMO.CSD:L337} ({@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)})
 * with {@code PROGRAM(COBIL00C)} on {@code :L338}, {@code app/csd/CARDDEMO.CSD:L196}
 * ({@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO) LANGUAGE(COBOL)}) and
 * {@code app/csd/CARDDEMO.CSD:L114} ({@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)}). This class carries
 * <strong>exactly one</strong> request-mapped operation, which is the whole of the billing surface: the
 * transaction inventory of {@code app/csd/CARDDEMO.CSD} contains one billing transaction and no other.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It accepts the bound {@code COBIL00} symbolic map as a JSON body, hands it to
 * {@code com.cardemo.service.billing.BillPaymentService} once, and translates the pass that service reports
 * into an HTTP status and a response body. It contains no business logic: no arithmetic, no validation rule,
 * no repository access, no persistence context and no transaction management. The transaction boundary that
 * makes the transaction insert and the account update atomic is owned by the service, which declares
 * {@code @Transactional(rollbackFor = Exception.class)} on its entry point.
 *
 * <p>Two properties of the operation are counter-intuitive, are contractual, and are deliberately
 * <em>not</em> improved here.
 *
 * <ul>
 *   <li><strong>The payment is always the full balance, never partial.</strong>
 *       {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at {@code app/cbl/COBIL00C.cbl:224} derives the amount from
 *       the account that was just read, and
 *       {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at {@code :234} then drives the balance to
 *       exactly zero. There is no partial-payment path anywhere in the program's 572 lines. Accordingly
 *       <strong>this endpoint accepts no payment amount</strong>: the request record
 *       {@code com.cardemo.model.dto.BillPaymentRequest} declares no amount member, its
 *       {@code currentBalance} member is the display echo of {@code CURBALI PIC X(14)} at
 *       {@code app/cpy-bms/COBIL00.CPY:66} and is never treated as an input to the settlement, and the
 *       service re-reads the authoritative balance from {@code ACCTDAT} before paying it. Accepting a
 *       client-supplied amount would convert a full settlement into a partial one, which is a behaviour
 *       change and therefore forbidden.</li>
 *   <li><strong>The confirmation gate is five-way, never a two-valued flag.</strong> See section 5.</li>
 * </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Java 25 ({@code maven.compiler.release} 25, no preview features), Maven 3.9.11, parent
 * {@code spring-boot-starter-parent:3.5.11}, Spring Framework 6.2.16, Spring Security 6.5.8, Tomcat 10.1.52
 * (so {@code jakarta.servlet}, never the pre-Jakarta package). The compiler runs
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning fails the build; build with
 * {@code ./mvnw -B -ntp clean compile} and verify with {@code ./mvnw -B -ntp clean verify}, where
 * {@code jacoco-maven-plugin:0.8.12} enforces an 80 percent line-coverage floor.
 *
 * <p>Tests for this class belong in {@code src/test/java/com/cardemo/unit} and
 * {@code src/test/java/com/cardemo/e2e} and never beside it. The class holds no static mutable state and no
 * per-request state, so it is reachable from a standalone {@code MockMvc} setup with a stubbed service as
 * well as from a full application context; the API-contract gate exercises it over a real context.
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p><strong>This class reads no configuration property.</strong> Nothing in
 * {@code src/main/resources/application.yml} is specific to it, so no key is dereferenced and none is
 * guessed. What governs its observable behaviour is declared elsewhere and is listed here so that a reader
 * does not have to hunt for it:
 *
 * <ul>
 *   <li><strong>Paths.</strong> {@value #BASE_PATH} at class level and {@value #PAYMENT_PATH} on the
 *       operation, so the single endpoint is {@code POST /api/billing/payments}.</li>
 *   <li><strong>Authentication and authorisation.</strong> Declared centrally in {@code SecurityConfig},
 *       including {@code SessionCreationPolicy.STATELESS}. This class adds no method-level authorisation
 *       annotation, so it cannot contradict that declaration; it only refuses a request that reached it
 *       with no usable identity. See section 7.</li>
 *   <li><strong>Serialisation.</strong> Jackson is configured centrally - unknown properties rejected,
 *       timestamps never numeric, decimals written plain - and is not overridden per controller. The
 *       request record additionally rejects an unrecognised property of its own accord.</li>
 *   <li><strong>Numeric conversion.</strong> {@code WebConfig} owns the two converters: a strict
 *       digits-only one for identifiers and card numbers, and a currency-aware one for amounts only. The
 *       account identifier of this operation is textual and is carried in the JSON body, so no converter is
 *       involved and this class declares no parser, no formatter and no data binder of its own.</li>
 *   <li><strong>Error response shape.</strong> {@code server.error.include-message},
 *       {@code include-stacktrace} and {@code include-binding-errors} are all {@code never} and
 *       {@code include-exception} is {@code false}. The failure mappers below hold to that posture: a fixed
 *       detail for every server-side failure, and never a class name, a stack trace or a row value.</li>
 *   <li><strong>Observability.</strong> {@code CorrelationIdFilter} owns the diagnostic context keys
 *       {@code correlationId}, {@code traceId} and {@code spanId}; this class neither adds, renames,
 *       overwrites nor clears them. {@code MetricsConfig} owns the four counters; this class registers no
 *       instrument of its own.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <p>Every failure is surfaced by one of the seven mappers declared on this class, each producing an
 * {@code application/problem+json} body. There is no {@code @ControllerAdvice}, no
 * {@code ResponseEntityExceptionHandler} and no advice type anywhere in this repository, and none of the
 * nine {@code com.cardemo.exception} types carries a status annotation, so HTTP status selection is
 * contextual and belongs to the controller that knows the operation.
 *
 * <table border="1">
 *   <caption>Status selection for transaction {@code CB00}</caption>
 *   <tr><th>Condition</th><th>Status</th><th>Legacy locator</th></tr>
 *   <tr><td>Payment recorded and balance driven to zero</td><td>201</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:527}-{@code :531}</td></tr>
 *   <tr><td>Balance displayed, nothing paid</td><td>200</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:236}-{@code :239}</td></tr>
 *   <tr><td>No authenticated principal</td><td>401</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:107}-{@code :109}</td></tr>
 *   <tr><td>Field wider than the map declares</td><td>400</td>
 *       <td>{@code app/cpy-bms/COBIL00.CPY}</td></tr>
 *   <tr><td>{@code ValidationException}</td><td>400</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:161}, {@code :187}, {@code :201}, {@code :237}</td></tr>
 *   <tr><td>{@code RecordNotFoundException}</td><td>404</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:361}, {@code :392}, {@code :425}, {@code :456}</td></tr>
 *   <tr><td>{@code DuplicateRecordException}</td><td>409</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:533}-{@code :536}</td></tr>
 *   <tr><td>{@code FileUnavailableException}</td><td>503</td><td>file status {@code '35'}</td></tr>
 *   <tr><td>{@code FileAccessException}</td><td>502</td><td>file status {@code '9x'}</td></tr>
 *   <tr><td>{@code FatalProcessingException}</td><td>500</td>
 *       <td>abend code {@code 999}, return code {@code 12}</td></tr>
 *   <tr><td>Any other typed CardDemo failure</td><td>500</td><td>-</td></tr>
 * </table>
 *
 * <p>Diagnosis proceeds from the logs and the correlation identifier, not from the response body. The
 * response never carries an account identifier drawn from a failure, a colliding key, a cause chain or a
 * stack trace: a provider message can quote row values, and a record-not-found failure carries the
 * submitted identifier as its record key, so neither is relayed.
 *
 * <h2>5. The five-way confirmation gate</h2>
 *
 * <p>{@code EVALUATE CONFIRMI OF COBIL0AI} at {@code app/cbl/COBIL00C.cbl:173}-{@code :191} has five
 * {@code WHEN} arms over four behaviours, and collapsing it would lose information the 3270 screen
 * displayed:
 *
 * <ul>
 *   <li>{@code WHEN 'Y'} and {@code WHEN 'y'} at {@code :174}-{@code :177}:
 *       {@code SET CONF-PAY-YES TO TRUE} then {@code PERFORM READ-ACCTDAT-FILE} - the payment proceeds.
 *       Answered with {@code 201 Created}.</li>
 *   <li>{@code WHEN 'N'} and {@code WHEN 'n'} at {@code :178}-{@code :181}:
 *       {@code PERFORM CLEAR-CURRENT-SCREEN} then {@code MOVE 'Y' TO WS-ERR-FLG} - the payment is
 *       cancelled. Answered with {@code 400 Bad Request}.</li>
 *   <li>{@code WHEN SPACES} and {@code WHEN LOW-VALUES} at {@code :182}-{@code :184}:
 *       {@code PERFORM READ-ACCTDAT-FILE} <em>only</em> - the balance is read and echoed and nothing is
 *       paid. This <strong>display-only</strong> outcome is distinct from both the confirm and the cancel
 *       arms and is answered with {@code 200 OK} carrying the echoed balance and the literal
 *       {@code Confirm to make a bill payment...} of {@code :237}. It is the reason this endpoint needs no
 *       separate balance-enquiry operation, which would have been an eighteenth.</li>
 *   <li>{@code WHEN OTHER} at {@code :185}-{@code :190}: {@code MOVE 'Y' TO WS-ERR-FLG}, the literal
 *       {@code Invalid value. Valid values are (Y/N)...} and {@code MOVE -1 TO CONFIRML}. Answered with
 *       {@code 400 Bad Request}.</li>
 * </ul>
 *
 * <p><strong>{@code SPACES} and {@code LOW-VALUES} are distinct source states</strong> even though they
 * share an arm, so absent, blank and low-values remain three distinct states end to end: this class passes
 * the submitted confirmation through untouched, and never coerces {@code null} to the empty string or the
 * empty string to {@code null}. The service classifies them - {@code null} to low-values, blank to spaces, a
 * value containing a null character to low-values - and the three-state accepted, rejected and blank model
 * it applies comes from the {@code COPY ... REPLACING} procedure-division template
 * {@code app/cpy/CSSETATY.cpy}.
 *
 * <p><strong>Attribution correction, severity Medium.</strong> The invalid-confirmation message that quotes
 * the offending value back to the operator belongs to transaction {@code CR00} and
 * {@code app/cbl/CORPT00C.cbl:483}-{@code :491}, <strong>not</strong> to {@code CB00}. The literal here is
 * the fixed {@code Invalid value. Valid values are (Y/N)...} of {@code app/cbl/COBIL00C.cbl:187}, with no
 * echo of the submitted character, and the value-quoting variant appears nowhere in this class. The
 * correction is recorded in {@code DECISION_LOG.md}.
 *
 * <h2>6. Retained legacy behaviour</h2>
 *
 * <p><strong>The identifier-generation race is retained, not repaired.</strong>
 * {@code app/cbl/COBIL00C.cbl:212}-{@code :217} generates the payment transaction's identifier by moving
 * {@code HIGH-VALUES} into {@code TRAN-ID}, starting a browse, reading the previous record, ending the
 * browse and adding one; the end-of-file arm at {@code :487}-{@code :488} moves {@code ZEROS} into
 * {@code TRAN-ID}, so <strong>the first identifier generated against an empty transaction file is 1</strong>.
 * That algorithm is inherently racy, exactly as the browse was. It is kept rather than replaced with a
 * database sequence, because a sequence would change the generated values and break the parity comparison
 * against the legacy baseline; a collision therefore surfaces as
 * {@code com.cardemo.exception.DuplicateRecordException} and is answered with {@code 409 Conflict}, which is
 * the observable counterpart of the {@code Tran ID already exist...} literal at {@code :536}. The retention
 * is recorded in {@code DECISION_LOG.md}.
 *
 * <p><strong>Cursor repositioning has no counterpart.</strong> {@code MOVE -1 TO CONFIRML} at {@code :189}
 * and {@code :239} and {@code MOVE -1 TO ACTIDINL} at {@code :163} and {@code :203} park the 3270 cursor.
 * That is terminal mechanics, not data, and this class invents no cursor-hint field for it. The service's
 * screen projection does publish which field the cursor would have been parked on, because it is part of the
 * authored map projection; no cursor member originates here.
 *
 * <p><strong>Reject codes are not part of this surface.</strong>
 * {@code com.cardemo.model.enums.RejectCode} models batch business outcomes that drive an exit status; it is
 * never thrown and never mapped to a status here.
 *
 * <h2>7. Statelessness and identity</h2>
 *
 * <p>Transformation rule 7 is binding: {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(...)} at
 * {@code app/cbl/COBIL00C.cbl:146}-{@code :149} becomes stateless HTTP plus token claims with
 * <strong>no server-side session state</strong>. This class creates no session, touches no session, retains
 * no conversational state and stores nothing between requests. In particular <strong>the two-phase
 * confirmation gate is not modelled as conversational state</strong>: the confirmation arrives as an explicit
 * member of the request body on this single call, which is what makes one call sufficient.
 *
 * <p>Identity comes only from the authenticated principal - the subject is the upper-cased
 * {@code CDEMO-USER-ID PIC X(08)} of {@code app/cpy/COCOM01Y.cpy:L25} and the authority is the
 * {@code CDEMO-USER-TYPE PIC X(01)} of {@code :L26} with its two condition names {@code 'A'} at {@code :L27}
 * and {@code 'U'} at {@code :L28}. Nothing is read from the request body, the query string or a session for
 * that purpose.
 *
 * <p><strong>No user-class gate is applied.</strong> {@code app/cbl/COBIL00C.cbl} tests
 * {@code CDEMO-USER-TYPE} nowhere in its 572 lines, so both user classes reach {@code CB00}; adding a role
 * check here would invent a guard the source does not have and could contradict {@code SecurityConfig}.
 * A request that carries no usable identity is refused with {@code 401 Unauthorized}, which is the stateless
 * counterpart of {@code IF EIBCALEN = 0} at {@code :107}, whose arms move {@code COSGN00C} into
 * {@code CDEMO-TO-PROGRAM} at {@code :108} and transfer to the sign-on program at {@code :109}.
 *
 * <p><strong>The communication-area fields with no counterpart appear in neither the request nor the
 * response</strong>, which is why the response body is the map projection rather than the service's full
 * pass report: {@code CDEMO-FROM-TRANID X(04)} at {@code app/cpy/COCOM01Y.cpy:L21},
 * {@code CDEMO-FROM-PROGRAM X(08)} at {@code :L22}, {@code CDEMO-TO-TRANID X(04)} at {@code :L23},
 * {@code CDEMO-TO-PROGRAM X(08)} at {@code :L24}, {@code CDEMO-PGM-CONTEXT 9(01)} at {@code :L29} with
 * {@code 88 CDEMO-PGM-ENTER VALUE 0} at {@code :L30} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at
 * {@code :L31}, {@code CDEMO-LAST-MAP X(7)} at {@code :L43} and {@code CDEMO-LAST-MAPSET X(7)} at
 * {@code :L44} - the last two being seven characters wide, not eight. Equally,
 * {@code CDEMO-ACCT-ID 9(11)} at {@code :L38}, {@code CDEMO-ACCT-STATUS X(01)} at {@code :L39},
 * {@code CDEMO-CARD-NUM 9(16)} at {@code :L41} and {@code CDEMO-CUST-ID 9(09)} at {@code :L33} are payload
 * members and never token claims; the claim set is exactly the subject, the role, the issuer, the
 * issued-at instant and the expiry.
 *
 * <p>Action mapping follows the same principle. The attention-identifier state of
 * {@code app/cpy/CVCRD01Y.cpy} and the procedural {@code YYYY-STORE-PFKEY.} paragraph of
 * {@code app/cpy/CSSTRPFY.cpy:L17}, an {@code EVALUATE TRUE} over {@code EIBAID}, become
 * <strong>controller-level action mapping</strong>: a distinct URL per action. This endpoint is the payment
 * action, so it supplies the enter-key arm itself as a fixed value; it exposes no generic attention-key
 * parameter and reimplements no function-key dispatcher. {@code app/cpy/CSSTRPFY.cpy} is copied into a
 * procedure division and therefore has no entity, no payload type and no import, and the four
 * {@code app/cpy/CVCRD01Y.cpy} members that are commented out in the source at {@code :26}, {@code :28},
 * {@code :35} and {@code :41} have no counterpart at all. {@code DFHAID}, {@code DFHBMSCA} and
 * {@code DFHATTR} are supplied by the transaction monitor, are absent from this repository and have no
 * import.
 *
 * <h2>8. Operations deliberately absent</h2>
 *
 * <p>This class exposes one operation and no other. There is no separate confirmation endpoint, because the
 * confirmation is a member of the single request; no balance-enquiry endpoint, because the display-only arm
 * of section 5 returns the balance; no partial-payment capability of any kind, because the source has none;
 * no alias, bulk or search operation; and no health, information or metrics operation, because the Actuator
 * endpoints declared in {@code src/main/resources/application.yml} own those. Nor is there any developer or
 * diagnostic operation: {@code app/csd/CARDDEMO.CSD:L388}-{@code :391} defines transaction {@code CDV1},
 * described there as a developer transaction, over program {@code COCRDSEC}, and the source of
 * {@code COCRDSEC} is <strong>Not available</strong> anywhere in the repository - the name occurs only at
 * {@code app/csd/CARDDEMO.CSD:L211} and {@code :L390} - so nothing is invented for it.
 *
 * <p>No service-level objective for this operation is available in the legacy corpus:
 * <strong>Not available</strong>. None is therefore asserted here, and the performance gate records a
 * measured baseline rather than a target.
 */
@RestController
@RequestMapping(BillingController.BASE_PATH)
public class BillingController {

    /**
     * The base path the billing surface is mounted under. Chosen to match the resource-per-transaction-group
     * convention the tree already follows - {@code /api/menu} for the two menu transactions, {@code /api/auth}
     * for sign-on - and deliberately distinct from every other group so that no path can be shared.
     */
    static final String BASE_PATH = "/api/billing";

    /**
     * The payment path, relative to {@value #BASE_PATH}, giving the single endpoint
     * {@code POST /api/billing/payments}. A distinct URL per action is the replacement for the attention-key
     * dispatch of {@code app/cpy/CSSTRPFY.cpy}, so the path itself names the action and no attention-key
     * parameter is exposed.
     */
    static final String PAYMENT_PATH = "/payments";

    /**
     * The CICS transaction this operation replaces, from
     * {@code app/csd/CARDDEMO.CSD:L337} ({@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}).
     */
    static final String TRANSACTION_ID = "CB00";

    /**
     * The COBOL program this operation replaces, from
     * {@code app/csd/CARDDEMO.CSD:L196} ({@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO)}). It is 572 lines
     * long and declares 16 procedure-division paragraphs.
     */
    static final String PROGRAM_NAME = "COBIL00C";

    /**
     * The BMS mapset that program painted, from
     * {@code app/csd/CARDDEMO.CSD:L114} ({@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)}). Its generated
     * symbolic map {@code app/cpy-bms/COBIL00.CPY} declares the ten input fields the request record carries.
     */
    static final String MAPSET_NAME = "COBIL00";

    /**
     * The attention identifier this endpoint supplies to the service, fixed to the enter-key arm of
     * {@code EVALUATE EIBAID} at {@code app/cbl/COBIL00C.cbl:125}-{@code :126}, which is the only arm that
     * reaches {@code PROCESS-ENTER-KEY} and therefore the only one that can settle a balance.
     *
     * <p>It is a constant rather than a parameter on purpose. The URL is the action, so a caller cannot ask
     * for the attention-key-three transfer arm of {@code :128} or the attention-key-four clear arm of
     * {@code :136} through this endpoint; neither is a payment. Derived from the service's own enumeration
     * rather than written as a literal, so a rename cannot silently desynchronise the two.
     */
    private static final String ENTER_ATTENTION_KEY = BillPaymentService.AidKey.ENTER.name();

    /**
     * {@code ABEND-CODE} as rendered for a response property: the {@code 999} that
     * {@code app/cbl/CBTRN02C.cbl:707}-{@code :710} moves into the abend work area before calling the
     * language-environment abend service. Sourced from the exception type rather than restated as a literal.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /**
     * {@code ABEND-REASON} for the case where the service fails in a way its own typed hierarchy does not
     * describe. Upper case, because the abend work-area fields of {@code app/cpy/CSMSG02Y.cpy} are
     * display fields written in upper case throughout the corpus.
     */
    private static final String ABEND_REASON = "BILL PAYMENT FAILED UNEXPECTEDLY";

    /**
     * {@code ABEND-MSG} for that same case.
     */
    private static final String ABEND_MESSAGE = "UNEXPECTED ERROR IN CB00 BILL PAYMENT.";

    /**
     * {@code ABEND-REASON} for a pass that reports neither a payment nor a retained failure. That combination
     * is unreachable through this endpoint - the enter-key arm always ends in one or the other - so it can
     * only mean the service contract changed underneath this class, which is an abend rather than a request
     * fault.
     */
    private static final String NO_OUTCOME_ABEND_REASON = "BILL PAYMENT REPORTED NO PAYMENT AND NO FAILURE";

    /**
     * The {@code ABEND-MSG} prefix for that same case. The outcome the service did report is appended, which
     * is safe because every outcome is a constant of the service's own enumeration and can carry no submitted
     * character.
     */
    private static final String NO_OUTCOME_ABEND_MESSAGE_PREFIX = "UNEXPECTED CB00 OUTCOME WITHOUT FAILURE: ";

    /**
     * {@code ABEND-REASON} for a pass that reports a payment or a prompt but no screen. The source sends the
     * map on every path through {@code PROCESS-ENTER-KEY}, the last of them at
     * {@code app/cbl/COBIL00C.cbl:242}, so a missing screen is likewise a broken contract rather than a
     * request fault.
     */
    private static final String NO_SCREEN_ABEND_REASON = "BILL PAYMENT SENT NO SCREEN";

    /**
     * {@code ABEND-MSG} for that same case.
     */
    private static final String NO_SCREEN_ABEND_MESSAGE = "CB00 COMPLETED WITHOUT SENDING A MAP.";

    /**
     * The problem-detail title for a rejected request. Deliberately generic: the byte-exact legacy literal
     * travels in the detail, not in the title.
     */
    private static final String VALIDATION_PROBLEM_TITLE = "Bill payment rejected";

    /**
     * The problem-detail title for an absent record.
     */
    private static final String NOT_FOUND_PROBLEM_TITLE = "Bill payment record not found";

    /**
     * The problem-detail title for a generated-identifier collision.
     */
    private static final String DUPLICATE_PROBLEM_TITLE = "Bill payment transaction identifier collided";

    /**
     * The problem-detail title for an unavailable dataset.
     */
    private static final String UNAVAILABLE_PROBLEM_TITLE = "Bill payment dataset unavailable";

    /**
     * The problem-detail title for a dataset access failure.
     */
    private static final String ACCESS_PROBLEM_TITLE = "Bill payment dataset access failed";

    /**
     * The problem-detail title for every remaining failure, including an abend.
     */
    private static final String FAILURE_PROBLEM_TITLE = "Bill payment failed";

    /**
     * The fixed detail emitted for a generated-identifier collision. It names no key, because the colliding
     * value is a generated transaction identifier and a client has no use for it; retrying is the remedy,
     * since the next descending browse observes the row that won the race.
     */
    private static final String DUPLICATE_PROBLEM_DETAIL =
            "The generated payment transaction identifier was already taken. Retry the request.";

    /**
     * The fixed detail emitted when a dataset could not be opened at all, the counterpart of file status
     * {@code '35'}. Distinguished from an access failure because the remedy differs: a dataset that is not
     * available becomes available again without any change to the request.
     */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "A dataset required by bill payment is unavailable. Retry once it has been restored, and quote "
                    + "the correlation identifier if the failure persists.";

    /**
     * The fixed detail emitted for a physical or logical input-output failure, the counterpart of the
     * {@code '9x'} file-status family. It names no cause: a provider message can echo column values.
     */
    private static final String ACCESS_PROBLEM_DETAIL =
            "A dataset required by bill payment could not be read or written. Retry the request, and quote "
                    + "the correlation identifier if the failure persists.";

    /**
     * The fixed detail emitted for every server-side failure. It names no class, no message, no column and no
     * value, which is the same posture as {@code server.error.include-message} being {@code never}. Diagnosis
     * proceeds from the correlation identifier in the logs.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The bill payment could not be completed. Retry the request, and quote the correlation identifier "
                    + "if the failure persists.";

    /**
     * The problem-detail property carrying the rejected field name, present only when the validation failure
     * named one. The name is a closed-domain identifier chosen by the service, never a submitted value.
     */
    private static final String FIELD_PROPERTY = "field";

    /**
     * The problem-detail property distinguishing a wrong value from an absent one, transcribed from the outer
     * and inner conditions of {@code app/cpy/CSSETATY.cpy:L18}-{@code :L26}. It is what lets a client tell the
     * empty-identifier rejection of {@code app/cbl/COBIL00C.cbl:161} from the invalid-confirmation rejection
     * of {@code :187} without parsing the detail text.
     */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * The problem-detail property naming which kind of record was absent. The record <em>type</em> is carried
     * and the record <em>key</em> is not: the key is the submitted account identifier, and an identifier
     * echoed back on a 404 is exactly what an account-enumeration attempt needs.
     */
    private static final String RECORD_TYPE_PROPERTY = "recordType";

    /**
     * The problem-detail property naming the dataset a failure attaches to - a logical file name such as
     * {@code ACCTDAT}, drawn from the eight {@code DEFINE FILE} entries of {@code app/csd/CARDDEMO.CSD}, and
     * therefore a closed domain that discloses nothing.
     */
    private static final String LOGICAL_FILE_PROPERTY = "logicalFile";

    /**
     * The problem-detail property carrying the four-character rendering of the file status, produced by
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:714}-{@code :731} and preserved by the
     * shared status mapper. Four characters drawn from a closed domain, so it is safe to publish and is the
     * single most useful field when correlating a failure with the legacy baseline.
     */
    private static final String IO_STATUS_PROPERTY = "ioStatus";

    /**
     * The problem-detail property naming the input-output operation that failed - a read for update, a
     * rewrite, a browse or a write - so that a 502 can be attributed without consulting the logs.
     */
    private static final String OPERATION_PROPERTY = "operation";

    /**
     * The problem-detail property carrying {@code ABEND-CODE}, so that the {@code 999} of the legacy abend
     * routine stays observable to a client without disclosing the cause.
     */
    private static final String ABEND_CODE_PROPERTY = "abendCode";

    /**
     * The problem-detail property carrying the batch return code, {@code 12}.
     */
    private static final String RETURN_CODE_PROPERTY = "returnCode";

    /**
     * Diagnostic log destination. Static and final: a logger is neither mutable state nor per-request state,
     * and holding it once avoids a lookup on every request.
     */
    private static final Logger LOG = LoggerFactory.getLogger(BillingController.class);

    /**
     * The bill-payment service, replacing {@code app/cbl/COBIL00C.cbl}. Final and never reassigned, and the
     * only collaborator this class has.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Creates the controller over the bill-payment service.
     *
     * <p>Constructor injection is the only injection form used: there is no field or setter injection, no
     * injection annotation, and no static mutable state, so the collaborator is non-null and final for the
     * lifetime of the bean. The argument is validated rather than trusted, because a null collaborator would
     * otherwise surface as a failure on the first request instead of at context refresh.
     *
     * @param billPaymentService the bill-payment service replacing {@code app/cbl/COBIL00C.cbl}; must not be
     * null.
     * @throws IllegalArgumentException if the service is null, which is a bean-wiring defect rather than a
     * request-time condition
     */
    public BillingController(final BillPaymentService billPaymentService) {

        if (billPaymentService == null) {
            throw new IllegalArgumentException(
                    "billPaymentService must not be null; it is the replacement for app/cbl/COBIL00C.cbl");
        }

        this.billPaymentService = billPaymentService;
    }

    /**
     * The only operation of this class - settle an account balance in full. Replaces CICS transaction
     * {@code CB00} and the program it fronts, {@code app/cbl/COBIL00C.cbl} (572 lines, 16 paragraph labels),
     * which painted mapset {@code COBIL00} from {@code app/cpy-bms/COBIL00.CPY}.
     *
     * <p><strong>Purpose.</strong> One pass of {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COBIL00C.cbl:154}-{@code :244}: validate the account identifier, evaluate the
     * confirmation gate, read and echo the balance, refuse a balance at or below zero, and - only when the
     * operator confirmed - generate a transaction identifier, record the payment and drive the balance to
     * zero.
     *
     * <p><strong>Inputs.</strong> The authenticated principal, and the bound {@code COBIL00} symbolic map as
     * a JSON body. The ten members of {@code com.cardemo.model.dto.BillPaymentRequest} are the ten input
     * fields the generated map declares, each constrained to the width the map declares - the account
     * identifier is {@code ACTIDINI PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:60}, the balance echo is
     * {@code CURBALI PIC X(14)} at {@code :66} and the confirmation is {@code CONFIRMI PIC X(1)} at
     * {@code :72}. A member wider than its declared width is rejected with {@code 400 Bad Request} by the
     * constraint validator before this method runs, and an unrecognised property is rejected by the record
     * itself. The body is required, so an absent one is likewise rejected with {@code 400} before entry; no
     * null check for it appears below, because it would be unreachable.
     *
     * <p><strong>The request carries no payment amount, and none may ever be added.</strong> The settlement
     * is always the entire balance, derived by the service from the account it re-reads - see
     * {@code app/cbl/COBIL00C.cbl:224} and {@code :234}. The {@code currentBalance} member is the display
     * echo of the map field and is never read as an input to the settlement; this method neither accepts,
     * computes, validates nor echoes an amount of its own.
     *
     * <p><strong>Outputs.</strong> The projection of the {@code COBIL0AO} output map declared at
     * {@code app/cpy-bms/COBIL00.CPY:79}, carrying the byte-exact screen literal the pass produced:
     *
     * <ul>
     *   <li>{@code 201 Created} when the payment was recorded. The balance is now exactly zero, and the
     *       account and balance fields of the body are blank because {@code INITIALIZE-ALL-FIELDS} at
     *       {@code app/cbl/COBIL00C.cbl:560}-{@code :566} runs before the success message is composed at
     *       {@code :527}-{@code :531}; the generated identifier travels in that message, exactly as the
     *       operator saw it. No {@code Location} header is sent: this class exposes no transaction resource
     *       to point at, and inventing one would add an eighteenth operation.</li>
     *   <li>{@code 200 OK} when the confirmation was blank or low values - the display-only arm. The balance
     *       was read and echoed and <strong>nothing was paid</strong>; the message is the
     *       {@code Confirm to make a bill payment...} literal of {@code :237}.</li>
     *   <li>{@code 401 Unauthorized} when no usable identity reached the operation.</li>
     *   <li>Every other outcome raises the failure the service retained, which one of the seven mappers on
     *       this class turns into a status and a problem detail.</li>
     * </ul>
     *
     * <p><strong>Side effects.</strong> On the confirmed path exactly two rows change: one row is inserted
     * into the transaction dataset and one account row is updated to a zero balance -
     * {@code app/cbl/COBIL00C.cbl:233} then {@code :235}. <strong>Both happen inside a single atomic unit
     * owned by the service</strong>, which declares {@code @Transactional(rollbackFor = Exception.class)} on
     * its entry point, so they commit together or not at all; this method manages no transaction and holds
     * no persistence context. Every other path is read-only. Three datasets participate and no others:
     * {@code ACCTDAT}, the {@code CXACAIX} alternate-index path and {@code TRANSACT}, all three among the
     * eight {@code DEFINE FILE} entries of {@code app/csd/CARDDEMO.CSD}; the batch-only datasets have no
     * definition there and are unreachable from here.
     *
     * <p><strong>Configuration and defaults.</strong> None is read here; see section 3 of the class
     * documentation. Two arguments the service accepts are supplied as fixed values rather than exposed:
     * the attention identifier is pinned to the enter-key arm, because the URL is the action, and the
     * deep-link selector - the {@code CDEMO-CB00-TRN-SELECTED} field of
     * {@code app/cbl/COBIL00C.cbl:116}-{@code :121} - is always absent, because it is communication-area
     * state and no communication area survives a stateless request.
     *
     * <p><strong>The five-way confirmation gate is preserved.</strong>
     * {@code EVALUATE CONFIRMI OF COBIL0AI} at {@code app/cbl/COBIL00C.cbl:173}-{@code :191} has five
     * {@code WHEN} arms over four behaviours, and all four stay distinguishable here: confirm answers
     * {@code 201}, the display-only arm answers {@code 200}, and cancel and invalid each answer {@code 400}
     * with their own byte-exact detail - {@code Invalid value. Valid values are (Y/N)...} from {@code :187}
     * for an unrecognised character. The confirmation is never reduced to a two-valued flag, and absent,
     * blank and low-values stay three distinct states because the submitted value is passed through
     * untouched. Section 5 of the class documentation enumerates the arms with their locators.
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} for the empty-identifier rejection
     * of {@code :161}, the invalid confirmation of {@code :187}, the cancelled confirmation of {@code :180}
     * and the {@code You have nothing to pay...} rejection of {@code :201}, which covers a zero balance as
     * well as a negative one because the guard at {@code :198} reads
     * {@code IF ACCT-CURR-BAL &lt;= ZEROS}. An identifier that is blank, not all digits or wider than eleven
     * characters cannot address a row, so it yields {@code 404} rather than {@code 400}, exactly as the
     * source falls through to the not-found arm of {@code READ-ACCTDAT-FILE} at {@code :359}. {@code 404}
     * also covers an absent cross-reference at {@code :425} and an absent browse target at {@code :456};
     * {@code 409} covers the retained identifier race; {@code 503} and {@code 502} cover the file statuses
     * {@code '35'} and {@code '9x'}; {@code 500} covers an abend. The response body never carries an
     * identifier, a key, a cause chain or a stack trace - diagnosis proceeds from the correlation identifier
     * in the logs.
     *
     * <p><strong>The identifier-generation race is retained rather than repaired</strong>
     * ({@code :211}-{@code :218}, with the first identifier being 1 against an empty file per
     * {@code :472}-{@code :496}), so a collision surfaces as
     * {@code com.cardemo.exception.DuplicateRecordException} and {@code 409 Conflict} instead of being
     * masked by a database sequence; see section 6 of the class documentation and
     * {@code DECISION_LOG.md}. Cursor repositioning has no counterpart and no field is invented for it.
     *
     * @param authentication the principal Spring Security resolved, which is null when the request reached
     * the operation unauthenticated.
     * @param request the bound {@code COBIL00} symbolic map; never null, because the body is required.
     * @return {@code 201 Created} with the map projection when the balance was settled in full,
     * {@code 200 OK} with the echoed balance when the confirmation was blank or low values, or
     * {@code 401 Unauthorized} when no usable identity reached the operation
     * @throws FatalProcessingException when the service reports neither a payment nor a failure, when it
     * reports a payment or a prompt without a screen, or when it fails in a way its own typed hierarchy does
     * not describe
     */
    @PostMapping(PAYMENT_PATH)
    public ResponseEntity<BillPaymentService.BillPaymentScreen> payBill(
            final Authentication authentication,
            @Valid @RequestBody final BillPaymentRequest request) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of IF EIBCALEN = 0 at app/cbl/COBIL00C.cbl:107, "
                    + "whose arms transfer to COSGN00C", TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final BillPaymentService.BillPaymentResult result = submitPayment(request);
        final BillPaymentService.PaymentOutcome outcome = result.outcome();

        if (outcome == BillPaymentService.PaymentOutcome.PAYMENT_SUCCESSFUL) {
            LOG.info("Settled transaction {} program {} mapset {} in full: confirmation branch {}, {} send(s)",
                    TRANSACTION_ID, PROGRAM_NAME, MAPSET_NAME, result.confirmationBranch(),
                    result.sendCount());
            return ResponseEntity.status(HttpStatus.CREATED).body(screenOf(result));
        }

        if (outcome == BillPaymentService.PaymentOutcome.CONFIRMATION_REQUIRED) {
            LOG.debug("Prompted transaction {} program {} for confirmation: branch {}, nothing was paid",
                    TRANSACTION_ID, PROGRAM_NAME, result.confirmationBranch());
            return ResponseEntity.ok(screenOf(result));
        }

        throw retainedFailureOf(result);
    }

    /**
     * Reports whether the supplied authentication carries no usable identity.
     *
     * <p>This is the stateless counterpart of {@code IF EIBCALEN = 0} at
     * {@code app/cbl/COBIL00C.cbl:107}, where an absent communication area proves the terminal reached
     * {@code COBIL00C} without first passing sign on. Both arms of that test move {@code 'COSGN00C'} into the
     * outbound program field and transfer, so the legacy remedy is "go and sign on". The HTTP remedy is the
     * same instruction expressed as a status code.
     *
     * <p>Three distinct states are refused, and they are genuinely distinct rather than three spellings of one
     * thing. A null authentication means no filter populated the context at all. An authentication that
     * reports itself not authenticated means a token was presented and rejected. An anonymous token means the
     * chain deliberately admitted an unidentified caller. None of them yields a subject claim, so none of them
     * can name the operator, and Rule 1 Clause B requires each to be handled explicitly rather than collapsed.
     *
     * <p>Side effects: none. The method reads the argument and returns; it never mutates the security context
     * and never touches the request.
     *
     * @param authentication the authentication resolved by the security chain, or null when nothing populated
     * the context.
     * @return true when the operation must be refused for want of an identity, false when a usable identity is
     * present
     */
    private static boolean isUnauthenticated(final Authentication authentication) {

        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * Performs the one and only service call this controller makes.
     *
     * <p>The call is {@code processRequest} rather than {@code payBill} for a reason that matters to parity.
     * {@code payBill} raises the confirmation prompt as an exception, which would erase the difference between
     * the display only arm of {@code app/cbl/COBIL00C.cbl:173-191} and the arms that fail; {@code
     * processRequest} retains every outcome on the result so all four arms stay distinguishable. The attention
     * key is fixed to the enter key by this method, never taken from the caller: the legacy dispatcher at
     * {@code app/cbl/COBIL00C.cbl:125-142} selected the paragraph from {@code EIBAID}, and in the target the
     * distinct URL is the action, so a client supplied key would reintroduce a function key dispatcher that
     * the migration deliberately removed. The entry mode is fixed to re-enter because a request that carries a
     * confirmation is by definition a subsequent pass over the map, and the selected transaction identifier is
     * null because this operation performs no deep link.
     *
     * <p>Side effects: the service owns them all. Behind this call sits a single unit of work bounded by
     * {@code @Transactional(rollbackFor = Exception.class)}, inside which the account balance is driven to
     * zero and one transaction row is inserted. This controller opens no transaction, holds no connection and
     * compensates for nothing.
     *
     * <p>Error handling follows Rule 1 Clause B: nothing is swallowed and nothing loses its root cause. A
     * failure the typed hierarchy already describes is rethrown exactly as raised, so the mapper that matches
     * it sees the original instance with its own diagnostic members intact. Any other runtime failure is
     * wrapped in {@code FatalProcessingException} with the original as the cause, which reproduces the abend
     * contract of the corpus: abend code {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}
     * and return code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.
     *
     * @param request the bound {@code COBIL00} symbolic map; never null, because the body is required.
     * @return the retained outcome of the operation, never null
     */
    private BillPaymentService.BillPaymentResult submitPayment(final BillPaymentRequest request) {

        try {
            return this.billPaymentService.processRequest(request, ENTER_ATTENTION_KEY,
                    BillPaymentService.EntryMode.REENTER, null);
        } catch (final CardDemoException alreadyTyped) {
            throw alreadyTyped;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, PROGRAM_NAME, ABEND_REASON, ABEND_MESSAGE,
                    unexpected);
        }
    }

    /**
     * Extracts the map projection that the operation sent, which is the whole of the response body.
     *
     * <p>The projection is the authored {@code COBIL0AO} shape and nothing wider. The retained outcome also
     * carries navigation, entry mode and return transaction members, which are the communication area fields
     * {@code CDEMO-FROM-TRANID} at {@code app/cpy/COCOM01Y.cpy:21} through {@code CDEMO-PGM-CONTEXT} at
     * {@code app/cpy/COCOM01Y.cpy:29}; those have no counterpart in a stateless interface and must not reach a
     * response. It also carries the retained failure itself, whose serialisation would emit a cause chain and
     * a stack trace and so contradict the disclosure posture the application configures centrally. Returning
     * the projection alone keeps both hazards out of the body while still delivering the balance, the byte
     * exact caption and the confirmation echo.
     *
     * <p>Side effects: none. The method reads one member of the argument and returns it.
     *
     * @param result the retained outcome of the operation; never null.
     * @return the map projection the operation sent, never null
     * @throws FatalProcessingException when the outcome reports a payment or a prompt yet sent no map, which
     * is unreachable through the authored service and is treated as an abend rather than as an empty body
     */
    private static BillPaymentService.BillPaymentScreen screenOf(
            final BillPaymentService.BillPaymentResult result) {

        final BillPaymentService.BillPaymentScreen screen = result.screen();
        if (screen == null) {
            throw new FatalProcessingException(ABEND_CODE, PROGRAM_NAME, NO_SCREEN_ABEND_REASON,
                    NO_SCREEN_ABEND_MESSAGE);
        }
        return screen;
    }

    /**
     * Extracts the typed failure that the operation retained, so the handler can rethrow it for mapping.
     *
     * <p>Every outcome other than a settled payment and a confirmation prompt is a failure, and the service
     * retains the exception that describes it rather than throwing at the point of discovery. That is what
     * keeps the four arms of the confirmation gate distinguishable, and it is why this controller rethrows
     * instead of translating: the mapper that matches the retained type sees the original instance with its
     * field name, record type, logical file, expanded status or abend members intact.
     *
     * <p>Side effects: none. The method reads two members of the argument and returns or constructs.
     *
     * @param result the retained outcome of the operation; never null.
     * @return the typed failure to rethrow, never null
     */
    private static CardDemoException retainedFailureOf(final BillPaymentService.BillPaymentResult result) {

        final CardDemoException retained = result.retainedFailure();
        if (retained != null) {
            return retained;
        }
        return new FatalProcessingException(ABEND_CODE, PROGRAM_NAME, NO_OUTCOME_ABEND_REASON,
                NO_OUTCOME_ABEND_MESSAGE_PREFIX + result.outcome());
    }

    /**
     * Maps a rejected input onto {@code 400 Bad Request}.
     *
     * <p>Three legacy rejections arrive here, and the byte exact caption the legacy map displayed is relayed
     * verbatim as the problem detail so the wire text and the 3270 text agree character for character. They
     * are: an empty account identifier, captioned {@code 'Acct ID can NOT be empty...'} at
     * {@code app/cbl/COBIL00C.cbl:159-164}; a confirmation value outside the accepted set, captioned
     * {@code 'Invalid value. Valid values are (Y/N)...'} at {@code app/cbl/COBIL00C.cbl:173-191}; and a
     * balance at or below zero, captioned {@code 'You have nothing to pay...'} at
     * {@code app/cbl/COBIL00C.cbl:197-205}, whose guard is {@code IF ACCT-CURR-BAL &lt;= ZEROS} and is
     * evaluated as a scaled decimal comparison, never as a binary floating comparison and never by equality.
     * The declined confirmation, the {@code 'N'} and {@code 'n'} arm that clears the map and raises the input
     * error flag, arrives here too.
     *
     * <p>The field name and the failure kind are both published as problem members. That pair is what keeps
     * the confirmation gate five ways distinguishable over HTTP: a blank confirmation and a low values
     * confirmation are reported as an absent input, whereas a value outside the accepted set is reported as a
     * wrong one, and the two are never coerced together. Both members are closed domain values chosen by the
     * service, so neither can carry a submitted account identifier, a card number or any other row value.
     *
     * <p>No message is fabricated. When the rejection carries none, the response carries no detail member.
     *
     * @param rejection the rejected input, carrying the legacy caption, the field it names and which of the
     * two failure conditions applies.
     * @return {@code 400 Bad Request} with a problem body naming the field and the failure kind
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure(final ValidationException rejection) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        final String rejectionMessage = rejection.getMessage();
        if (rejectionMessage != null) {
            problem.setDetail(rejectionMessage);
        }
        if (rejection.hasFieldName()) {
            problem.setProperty(FIELD_PROPERTY, rejection.getFieldName());
        }
        problem.setProperty(FAILURE_KIND_PROPERTY, rejection.getFailureKind().name());

        LOG.warn("Refused transaction {} with 400 for field {} and failure kind {}", TRANSACTION_ID,
                rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Maps an absent record onto {@code 404 Not Found}.
     *
     * <p>File status {@code '23'}, the legacy not found condition, reaches this mapper from three sites: the
     * account read at {@code app/cbl/COBIL00C.cbl:361} and its update counterpart at
     * {@code app/cbl/COBIL00C.cbl:392}, both captioned {@code 'Account ID NOT found...'}, and the
     * cross reference read through the alternate index path at {@code app/cbl/COBIL00C.cbl:425}. A malformed
     * account identifier also lands here rather than on the previous mapper, and that is faithful rather than
     * lax: the legacy move of a non numeric or over long value left the key unusable and fell through to the
     * not found arm, so the target reproduces the same destination.
     *
     * <p>The record type is published because it is a closed domain label. The record key is deliberately
     * withheld: it is the submitted account identifier, and echoing whether a given identifier exists would
     * turn a failure response into an enumeration oracle.
     *
     * <p>End of file, status {@code '10'}, is not a failure and never reaches here. It is the empty
     * transaction file path at {@code app/cbl/COBIL00C.cbl:487-488}, which moves zeros into the identifier so
     * the first generated identifier becomes one, and it completes as a settled payment.
     *
     * @param absence the absent record, carrying the legacy caption and the closed domain record type.
     * @return {@code 404 Not Found} with a problem body naming the record type but never the key
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absence) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);

        final String absenceMessage = absence.getMessage();
        if (absenceMessage != null) {
            problem.setDetail(absenceMessage);
        }
        absence.recordType().ifPresent(recordType -> problem.setProperty(RECORD_TYPE_PROPERTY, recordType));

        LOG.warn("Refused transaction {} with 404 for record type {}", TRANSACTION_ID,
                absence.recordType().orElse(null));

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Maps a colliding transaction identifier onto {@code 409 Conflict}.
     *
     * <p>This is the retained identifier generation race surfacing, and surfacing is the point. The corpus
     * derives the next identifier at {@code app/cbl/COBIL00C.cbl:211-218} by moving high values into the key,
     * browsing backwards to the greatest existing identifier and adding one, which is inherently racy between
     * two concurrent operators. The migration keeps the algorithm rather than substituting a database
     * sequence, because a sequence would generate different identifiers and break the parity comparison, so a
     * collision must be reported rather than masked. The legacy caption for the condition is
     * {@code 'Tran ID already exist...'} at {@code app/cbl/COBIL00C.cbl:536}; it is relayed when present, and
     * a retry instruction stands in when it is not.
     *
     * <p>The logical file is published because it is a closed domain dataset label. The colliding key is
     * withheld, on the same reasoning that withholds the record key on the previous mapper.
     *
     * <p>Troubleshooting: a repeat of this status under load is the race, not a defect, and the remedy is to
     * retry the operation. Nothing was written, because the insert and the balance update share one unit of
     * work and the constraint violation rolled both back.
     *
     * @param collision the colliding insert, carrying the dataset whose primary key rejected the write.
     * @return {@code 409 Conflict} with a problem body naming the dataset but never the colliding key
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(final DuplicateRecordException collision) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle(DUPLICATE_PROBLEM_TITLE);

        final String collisionMessage = collision.getMessage();
        problem.setDetail(collisionMessage == null ? DUPLICATE_PROBLEM_DETAIL : collisionMessage);
        problem.setProperty(LOGICAL_FILE_PROPERTY, collision.getLogicalFile());

        LOG.warn("Refused transaction {} with 409 on dataset {}: the retained max plus one identifier "
                + "generation of app/cbl/COBIL00C.cbl:211-218 collided and the unit of work rolled back",
                TRANSACTION_ID, collision.getLogicalFile());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Maps an unavailable dataset onto {@code 503 Service Unavailable}.
     *
     * <p>This is file status {@code '35'}, the legacy condition for a dataset that could not be opened. It is
     * a dependency outage rather than a fault in the request, so the status advertises that retrying later may
     * succeed while the caller changes nothing.
     *
     * <p>The detail is fixed rather than relayed, because a server side failure must not narrate infrastructure
     * back to a caller. The logical resource name is published when the raising site identified one, since a
     * dataset label is a closed domain value and is exactly what an operator needs in order to act.
     *
     * <p>Troubleshooting: confirm the datastore that replaced the account, cross reference and transaction
     * clusters is reachable and that the schema the migrations own has been applied. The composite health
     * endpoint reports the same dependency, and the correlation identifier on this response ties the refusal
     * to the log line that recorded it.
     *
     * <p>Reachability, stated plainly for Rule 1 Clause F: the authored service does not currently raise this
     * condition, because its I/O paths surface only the success, not found and physical error statuses. The
     * mapper is nevertheless the declared contract for status {@code '35'} at this endpoint, so a future
     * raising site is mapped deterministically instead of collapsing onto the catch all. It is documented and
     * cited rather than untracked, which is what Rule 1 Clause B requires of anything retained.
     *
     * @param unavailable the unavailable dataset, optionally carrying the logical resource name.
     * @return {@code 503 Service Unavailable} with a fixed problem body naming only the dataset
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);
        unavailable.resourceName().ifPresent(resource -> problem.setProperty(LOGICAL_FILE_PROPERTY, resource));

        LOG.error("Transaction {} could not open dataset {}: file status 35", TRANSACTION_ID,
                unavailable.resourceName().orElse(null), unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    /**
     * Maps a failed dataset access onto {@code 502 Bad Gateway}.
     *
     * <p>This is the {@code '9x'} family, the legacy physical and logical I/O error group. The corpus reacted
     * by rendering the status and abending, and the captions for the three sites that can raise it here are
     * {@code 'Unable to lookup Account...'} at {@code app/cbl/COBIL00C.cbl:368},
     * {@code 'Unable to Update Account...'} at {@code app/cbl/COBIL00C.cbl:399} and
     * {@code 'Unable to lookup XREF AIX file...'} at {@code app/cbl/COBIL00C.cbl:432}.
     *
     * <p>The status is a gateway failure rather than a generic server failure on purpose. The application is
     * acting as an intermediary to the datastore that replaced the VSAM clusters, and the failure originated
     * beyond that boundary rather than inside this application, so a distinct status keeps it separable from
     * both the unavailable dependency above and the abend below. Rule 1 Clause B requires the failure modes to
     * be distinguishable, and three different statuses for three different causes is how that is honoured.
     *
     * <p>The four character expanded status is published verbatim as it was rendered at construction. That
     * rendering is the contract of {@code 9910-DISPLAY-IO-STATUS}, reproduced so a diagnosis made against the
     * legacy log can be made against this response unchanged. The dataset and the attempted operation are
     * published for the same reason, and all three are closed domain values that carry no row content.
     *
     * <p>Troubleshooting: read the expanded status first, then the operation and the dataset. Nothing was
     * committed, because the insert and the balance update share one unit of work.
     *
     * @param failure the failed access, carrying the expanded status and, when the raising site identified
     * them, the dataset and the attempted operation.
     * @return {@code 502 Bad Gateway} with a fixed problem body naming the expanded status, the dataset and
     * the operation
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ACCESS_PROBLEM_DETAIL);
        problem.setTitle(ACCESS_PROBLEM_TITLE);
        problem.setProperty(IO_STATUS_PROPERTY, failure.getExpandedStatus());

        final String logicalFileName = failure.getLogicalFileName();
        if (logicalFileName != null) {
            problem.setProperty(LOGICAL_FILE_PROPERTY, logicalFileName);
        }
        final String operation = failure.getOperation();
        if (operation != null) {
            problem.setProperty(OPERATION_PROPERTY, operation);
        }

        LOG.error("Transaction {} failed I/O on dataset {} during {}: expanded status {}", TRANSACTION_ID,
                logicalFileName, operation, failure.getExpandedStatus(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}.
     *
     * <p>This reproduces the terminal path of the corpus, where an unexpected file status rendered a
     * diagnostic and called the language environment abend service. The abend code is published as
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and the return code as
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}, which are the values the batch
     * corpus set, so an online abend and a batch abend are recognisable as the same class of event.
     *
     * <p>The detail is fixed. The culprit program and the abend reason are logged rather than published,
     * because a caller has no use for an internal program name and Rule 1 Clause D keeps internals out of
     * responses. The root cause travels with the log record, never with the body, which is consistent with the
     * central error configuration that suppresses messages, stack traces and exception names on error
     * responses.
     *
     * <p>Troubleshooting: correlate on the identifier this response carries, read the logged culprit and
     * reason, then read the cause chain. Nothing was committed.
     *
     * @param abend the abend, carrying its code, culprit program, reason and message.
     * @return {@code 500 Internal Server Error} with a fixed problem body naming only the abend and return
     * codes
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
     * Maps any remaining typed failure onto {@code 500 Internal Server Error}.
     *
     * <p>This is the base of the hierarchy and therefore the last resort. Spring selects the closest matching
     * mapper by inheritance distance, so every mapper above continues to win for its own type and only a
     * subtype none of them names arrives here. It exists so that a failure can never escape unmapped into the
     * container's default error path, where the response shape would differ from every other response this
     * endpoint produces.
     *
     * <p>The detail is fixed and the cause is logged in full, so nothing is swallowed and nothing is
     * disclosed. Rule 1 Clause B is satisfied on both halves of the same sentence.
     *
     * <p>Troubleshooting: reaching this mapper means the failure carried a type the endpoint does not describe
     * specifically. Read the logged exception, then decide whether the condition warrants its own mapper.
     *
     * @param failure the typed failure that no more specific mapper names.
     * @return {@code 500 Internal Server Error} with a fixed problem body
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("Transaction {} failed with a typed CardDemo exception", TRANSACTION_ID, failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
