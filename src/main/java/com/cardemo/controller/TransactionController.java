/*
 * ******************************************************************
 * Program     : TransactionController.java
 * Application : CardDemo
 * Type        : Spring Boot REST Controller
 * Function    : Transaction list (10 rows per page), transaction detail and transaction add endpoints.
 * Source      : app/csd/CARDDEMO.CSD transactions CT00, CT01, CT02
 *               -> app/cbl/COTRN00C.cbl (699 lines), mapset COTRN00
 *               -> app/cbl/COTRN01C.cbl (330 lines), mapset COTRN01
 *               -> app/cbl/COTRN02C.cbl (783 lines), mapset COTRN02 @ 7756d89
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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.TransactionAddRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionListResponse;
import com.cardemo.model.dto.TransactionResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;

import jakarta.validation.Valid;

/**
 * The transaction surface: CICS transactions {@code CT00}, {@code CT01} and {@code CT02}, the three
 * programs they fronted and the three mapsets those programs painted.
 *
 * <p>The three CSD triples are the contract this class replaces, and each was read from the resource
 * definitions rather than inferred:</p>
 *
 * <table border="1">
 *   <caption>The three transactions this controller replaces, one operation each</caption>
 *   <tr><th>Transaction</th><th>Program</th><th>Mapset</th><th>Operation</th></tr>
 *   <tr>
 *     <td>{@code CT00} - {@code DEFINE TRANSACTION(CT00)} at {@code app/csd/CARDDEMO.CSD:L419}</td>
 *     <td>{@code PROGRAM(COTRN00C)} at {@code :L420}, registered by {@code DEFINE PROGRAM(COTRN00C)} at
 *         {@code :L257}; {@code app/cbl/COTRN00C.cbl}, 699 lines, 16 paragraph labels</td>
 *     <td>{@code COTRN00}</td>
 *     <td>{@link #listTransactions}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CT01} - {@code DEFINE TRANSACTION(CT01)} at {@code app/csd/CARDDEMO.CSD:L429}</td>
 *     <td>{@code PROGRAM(COTRN01C)} at {@code :L430}, registered by {@code DEFINE PROGRAM(COTRN01C)} at
 *         {@code :L264}; {@code app/cbl/COTRN01C.cbl}, 330 lines, 9 paragraph labels</td>
 *     <td>{@code COTRN01}</td>
 *     <td>{@link #getTransactionDetail}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CT02} - {@code DEFINE TRANSACTION(CT02)} at {@code app/csd/CARDDEMO.CSD:L439}</td>
 *     <td>{@code PROGRAM(COTRN02C)} at {@code :L440}, registered by {@code DEFINE PROGRAM(COTRN02C)} at
 *         {@code :L271}; {@code app/cbl/COTRN02C.cbl}, 783 lines, 18 paragraph labels</td>
 *     <td>{@code COTRN02}</td>
 *     <td>{@link #addTransaction}</td>
 *   </tr>
 * </table>
 *
 * <p>One transaction becomes one operation, so this class carries <strong>exactly three</strong>
 * request-mapped handlers and no fourth. The {@code @ExceptionHandler} methods below are status mappers,
 * not operations: they are reached only when a handler fails and are addressable by no URL.</p>
 *
 * <h2>What it does</h2>
 *
 * <p>It accepts three HTTP requests, hands each to its service exactly once, and turns the single outcome
 * of that call into a status code. It is a boundary adapter and nothing else. It holds no business rule,
 * runs no validation cascade, performs no arithmetic, parses no number, formats no amount, generates no
 * identifier, reads no repository, touches no {@code EntityManager} and opens no transaction. Every one of
 * those behaviours lives in the service tier, which is where the paragraph-for-paragraph translations of
 * {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and {@code app/cbl/COTRN02C.cbl} belong.</p>
 *
 * <p>All three transactions were backed by one CICS file, {@code DEFINE FILE(TRANSACT)} at
 * {@code app/csd/CARDDEMO.CSD:L76}, over the record layout {@code app/cpy/CVTRA05Y.cpy}. That is the
 * reason they are grouped here: they are three views of one resource, not three resources.</p>
 *
 * <h2>Statelessness, and what has no counterpart</h2>
 *
 * <p>The source was pseudo-conversational: each program ended with {@code RETURN TRANSID} and a
 * communication area, and the next turn resumed from it. That mechanism is replaced by stateless HTTP plus
 * token claims, so <strong>this class creates no session, touches no {@code HttpSession} and keeps no
 * per-request or static mutable state</strong>. The stateless session policy itself is declared centrally
 * in {@code SecurityConfig} and is not restated here.</p>
 *
 * <p>Seven communication-area fields from {@code app/cpy/COCOM01Y.cpy} therefore have <em>no</em>
 * counterpart and appear in no request and no response: {@code CDEMO-FROM-TRANID PIC X(04)} at
 * {@code :L21}, {@code CDEMO-FROM-PROGRAM PIC X(08)} at {@code :L22}, {@code CDEMO-TO-TRANID PIC X(04)} at
 * {@code :L23}, {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code :L24}, {@code CDEMO-PGM-CONTEXT PIC 9(01)} at
 * {@code :L29} with its condition names {@code CDEMO-PGM-ENTER VALUE 0} at {@code :L30} and
 * {@code CDEMO-PGM-REENTER VALUE 1} at {@code :L31}, {@code CDEMO-LAST-MAP PIC X(7)} at {@code :L43} and
 * {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code :L44} - both seven characters wide, not eight. Routing is
 * expressed by URL, and the enter-versus-re-enter flag collapses into the distinction between a request
 * that carries parameters and one that does not.</p>
 *
 * <p><strong>Pagination is not attributed to the shared copybook, because it is not in it.</strong>
 * {@code app/cpy/COCOM01Y.cpy} contains no page-number field and no next-page flag. The paging cursor is a
 * set of program-local communication-area <em>extension</em> fields declared inside {@code COTRN00C}
 * itself, under {@code 05 CDEMO-CT00-INFO} at {@code app/cbl/COTRN00C.cbl:L62}:
 * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)} at {@code :L63}, {@code CDEMO-CT00-TRNID-LAST PIC X(16)} at
 * {@code :L64}, {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at {@code :L65} and
 * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'} at {@code :L66} with
 * {@code 88 NEXT-PAGE-YES VALUE 'Y'} at {@code :L67} and {@code 88 NEXT-PAGE-NO VALUE 'N'} at
 * {@code :L68}. Those four fields become request parameters and response metadata, which is the whole of
 * the pagination design.</p>
 *
 * <p>Identity is read from the authenticated principal and from <strong>nothing else</strong> - never from
 * a request body, never from a query parameter and never from a session. The subject is the upper-cased
 * {@code CDEMO-USER-ID PIC X(08)} of {@code app/cpy/COCOM01Y.cpy:L25} and the role authority is
 * {@code CDEMO-USER-TYPE PIC X(01)} at {@code :L26} with its two condition names,
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at {@code :L27} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at
 * {@code :L28}. The remaining identifiers the communication area carried -
 * {@code CDEMO-CUST-ID PIC 9(09)} at {@code :L33}, {@code CDEMO-ACCT-ID PIC 9(11)} at {@code :L38} and
 * {@code CDEMO-CARD-NUM PIC 9(16)} at {@code :L41} - are data transfer object fields and are
 * <strong>never</strong> token claims, because a token is signed rather than encrypted and a claim is
 * readable by anyone holding it.</p>
 *
 * <p><strong>This controller reads the role authority but does not branch on it</strong>, and the absence is
 * a decision rather than an oversight. All three transactions were available to both user classes: none of
 * the three programs tests {@code CDEMO-USER-TYPE}, and correspondingly none of the three services accepts
 * an administrator flag. Authorisation is therefore declared centrally in {@code SecurityConfig} and is
 * neither repeated nor contradicted here. Introducing a local role test would add an access rule the source
 * does not have, and holding an unused authority constant would be dead code; the operations that genuinely
 * are administrator-only live behind {@code /api/admin} on a different controller.</p>
 *
 * <h2>Transaction timestamps are text, never a temporal type</h2>
 *
 * <p>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are declared {@code PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy:16} and {@code :17}, and the corpus holds three mutually incompatible
 * producers for them: a batch form, an online form, and pure pass-through of whatever arrived. They are
 * consequently {@code String} in Java and {@code CHAR(26)} in the schema, never a date-time, an instant or
 * a SQL timestamp. All 300 rows of {@code app/data/ASCII/dailytran.txt} corroborate this independently by
 * carrying a processing timestamp of 26 blanks, which no temporal type can represent.</p>
 *
 * <p>The consequence for this class is absolute and is enforced structurally rather than by convention:
 * <strong>no handler signature, no request parameter and no response field on this controller is a
 * temporal type</strong>, and this class neither parses, reformats, normalises nor validates any timestamp
 * or date. The dates the operations carry - {@code TORIGDTI PIC X(10)} at
 * {@code app/cpy-bms/COTRN02.CPY:102} and its processing counterpart - are relayed as the text the
 * symbolic map declared them to be. A temporal type here would silently reject the blank timestamps the
 * fixture proves are legal and would rewrite the two forms into one, breaking parity against the baseline
 * in a way no compiler reports.</p>
 *
 * <h2>Numeric contract</h2>
 *
 * <p>Every financial value is a fixed-scale decimal at the precision its field definition dictates.
 * {@code TRAN-AMT} is {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}, so it is
 * {@code NUMERIC(11,2)} with scale two and half-even rounding, compared with {@code compareTo} and never
 * with {@code equals}. <strong>No {@code float} and no {@code double} appears in any financial field</strong>
 * anywhere on this surface, in a request body or a response body. This class holds no numeric field of its
 * own: the amount crosses its boundary as the text of {@code TRNAMTI PIC X(12)}
 * ({@code app/cpy-bms/COTRN02.CPY:96}), exactly as the 3270 map carried it, and the decimal conversion
 * happens once, in the service.</p>
 *
 * <p>Serialisation is configured centrally under {@code src/main/resources} - no numeric timestamps,
 * unknown properties rejected, and decimals never written as floating point - and this class
 * <strong>does not override any of it per operation</strong>.</p>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile and unit-test the module with {@code ./mvnw -B -ntp test}. The full gate is
 * {@code ./mvnw -B -ntp clean verify}, which compiles under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} enabled, so a single raw type, unchecked cast or dangling documentation comment in
 * this file fails the build outright; an unused import does not, because {@code javac} 25 publishes no
 * {@code unused} lint key, and malformed Javadoc is covered by the separate explicit doclint command. Run the
 * application under the {@code local} profile against the Compose topology, which supplies PostgreSQL 16
 * and the LocalStack endpoint; the transaction surface itself needs only the database.</p>
 *
 * <p>Unit tests for this class belong in {@code src/test/java/com/cardemo/unit} and drive it through
 * {@code MockMvc} with the three services stubbed. The API-contract gate belongs in
 * {@code src/test/java/com/cardemo/e2e} and exercises these three operations as three of the seventeen
 * against a real application context. Both tiers can construct this class directly, because it holds no
 * static mutable state and receives all three collaborators through its constructor. No test belongs in
 * this package.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>This class reads no property of its own</strong> and holds no page-size literal. Its base path
 * is the compile-time constant {@value #BASE_PATH} and the detail path is {@value #DETAIL_PATH}.</p>
 *
 * <p>The one policy value the transaction list depends on is the screen depth,
 * {@code carddemo.pagination.transaction-list-page-size}, and it is bound <em>once</em>, by
 * {@code TransactionListService} through its constructor, and validated there against the physical slot
 * count of the {@code COTRN00} map. Its value is ten. That figure needs two citations rather than one,
 * because the obvious locator does not show it:</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl:L65-L68} supplies the <em>paging semantics</em> - the page counter
 *       {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} and the next-page flag with its two condition names - but
 *       <strong>not the row count</strong>. The program declares no {@code OCCURS 10} anywhere; its only
 *       {@code OCCURS} is the communication area at {@code :L89},
 *       {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}.</li>
 *   <li>{@code app/cpy-bms/COTRN00.CPY} supplies the <em>size</em>, by carrying exactly ten row slots:
 *       {@code TRNID01I} through {@code TRNID10I}, each {@code PIC X(16)}, and {@code TDESC01I} through
 *       {@code TDESC10I}, each {@code PIC X(26)}. Ten fields, therefore ten rows.</li>
 *   </ul>
 *
 * <p>Citing only the first would be citing a locator that does not contain the number, which Rule 1
 * Clause F forbids. The program's own loop bounds agree with the map -
 * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} at {@code :L290} and
 * {@code PERFORM UNTIL WS-IDX &gt;= 11} at {@code :L297} - which is the third, corroborating reading.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Status selection happens <strong>in this class</strong>, in the {@code @ExceptionHandler} methods
 * below. There is no {@code @ControllerAdvice} and no advice class anywhere in the repository, and the nine
 * exception types carry no status annotation of their own, so each controller owns the decision for its own
 * operations. The choice is genuinely contextual - the same exception type can mean "the caller can fix
 * this" on one boundary and "a dependency is down" on another - which is why it is not centralised.</p>
 *
 * <table border="1">
 *   <caption>Every status this controller can return, and the first thing to check</caption>
 *   <tr><th>Status</th><th>Condition</th><th>First thing to check</th></tr>
 *   <tr>
 *     <td>{@code 400}</td>
 *     <td>{@code ValidationException} - a non-numeric account identifier, a card number that is not
 *         sixteen digits, an amount the currency-aware conversion rejects, a date the date validator
 *         rejects, a blank required field, or a page number outside the browse domain</td>
 *     <td>The {@code detail} carries the legacy message literal verbatim, {@code field} names the
 *         symbolic-map field the 3270 cursor would have been parked on, and {@code failureKind} separates
 *         an omitted value from a supplied-and-wrong one</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 404}</td>
 *     <td>{@code RecordNotFoundException} - no such transaction, or no cross-reference for the supplied
 *         account or card</td>
 *     <td>For the detail operation the {@code detail} is the byte-exact legacy literal
 *         {@code Transaction ID NOT found...}, including its three trailing periods</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 409}</td>
 *     <td>{@code DuplicateRecordException} - file status {@code '22'}; two concurrent adds generated the
 *         same identifier</td>
 *     <td>Expected and retained, not a defect. Retry the request; the next descending browse sees the
 *         committed row and yields the following identifier</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 502}</td>
 *     <td>{@code FileAccessException} - the {@code '9x'} file-status family, a physical or logical I/O
 *         failure against {@code TRANSACT}</td>
 *     <td>The four-character expanded status the exception carries, and the logical file and operation
 *         names beside it in the log</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 503}</td>
 *     <td>{@code FileUnavailableException} - file status {@code '35'}; the resource could not be opened</td>
 *     <td>Whether the database is reachable. A retry may resolve this, which is why it is distinguished
 *         from a failed access against an open file</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 500}</td>
 *     <td>{@code FatalProcessingException}, or any remaining typed CardDemo failure</td>
 *     <td>The abend code {@code 999} and batch return code {@code 12} on the response, then the cause in
 *         the log. The cause is never returned</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 401}</td>
 *     <td>No usable identity reached the operation</td>
 *     <td>Present a bearer token. Only sign-on is unauthenticated</td>
 *   </tr>
 * </table>
 *
 * <p>File-status semantics behind that table, taken from the guard idiom the corpus applies on every I/O
 * path: {@code '00'} and {@code '04'} continue; {@code '10'} is end of data and is <strong>not</strong> an
 * error - it is what ends the list browse and produces a short or empty final page; {@code '23'} is
 * record-not-found; {@code '22'} is a duplicate key; {@code '35'} is an unavailable file; the {@code '9x'}
 * family is an access failure; and anything else is fatal.</p>
 *
 * <p>Two things deliberately <em>cannot</em> happen here, and their absence is a design guarantee rather
 * than an omission. A batch reject code is never thrown and never mapped to a status: reject codes are
 * business outcomes of the daily posting job that drive a batch exit status, and although that job posts
 * transactions, no operation on this controller may surface one. And no card number, account identifier,
 * amount, description, merchant detail, token or header value ever reaches a log line; the log records
 * presence, counts and enumerated actions only.</p>
 */
@RestController
@RequestMapping(TransactionController.BASE_PATH)
public class TransactionController {

    /**
     * The base path of the transaction resource. Package private so a test can reference it without
     * duplicating the literal, and a compile-time constant so it can be used by {@code @RequestMapping}.
     */
    static final String BASE_PATH = "/api/transactions";

    /**
     * The path of the detail operation, relative to {@link #BASE_PATH}. A distinct sub-path rather than a
     * path variable, so that the identifier travels as the {@code TRNIDINI PIC X(16)} text field the
     * symbolic map declares at {@code app/cpy-bms/COTRN01.CPY:60} rather than as a coerced number.
     */
    static final String DETAIL_PATH = "/detail";

    /** CICS transaction identifier of the list operation, {@code app/csd/CARDDEMO.CSD:L419}. */
    private static final String LIST_TRANSACTION_ID = "CT00";

    /** CICS transaction identifier of the detail operation, {@code app/csd/CARDDEMO.CSD:L429}. */
    private static final String DETAIL_TRANSACTION_ID = "CT01";

    /** CICS transaction identifier of the add operation, {@code app/csd/CARDDEMO.CSD:L439}. */
    private static final String ADD_TRANSACTION_ID = "CT02";

    /** Program behind the list operation, {@code app/csd/CARDDEMO.CSD:L420}; 699 lines. */
    private static final String LIST_PROGRAM = "COTRN00C";

    /** Program behind the detail operation, {@code app/csd/CARDDEMO.CSD:L430}; 330 lines. */
    private static final String DETAIL_PROGRAM = "COTRN01C";

    /** Program behind the add operation, {@code app/csd/CARDDEMO.CSD:L440}; 783 lines. */
    private static final String ADD_PROGRAM = "COTRN02C";

    /**
     * Lowest accepted page number. Zero, because {@code TransactionListState} reports an unstarted browse
     * as page zero and a client echoing that value back must not be refused for it.
     */
    private static final int LOWEST_PAGE_NUMBER = 0;

    /**
     * Highest accepted page number: 99999999, the full domain of
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COTRN00C.cbl:L65}. Eight digits, so eight
     * nines. The bound is asserted rather than assumed because the counter is unsigned and unclamped in the
     * source, and a value outside its domain is a client error rather than an empty page.
     */
    private static final int HIGHEST_PAGE_NUMBER = 99999999;

    /** Name of the page request parameter, used when a refusal must name the field it refers to. */
    private static final String PAGE_FIELD = "page";

    /** Request-parameter name of the navigation action, matched exactly against the three declared tokens. */
    private static final String ACTION_FIELD = "action";

    /** Request-parameter name of the page-state flag, matched exactly against two tokens and no alias. */
    private static final String NEXT_PAGE_FIELD = "nextPageAvailable";

    /** The only accepted affirmative spelling of {@link #NEXT_PAGE_FIELD}. */
    private static final String TRUE_TOKEN = "true";

    /** The only accepted negative spelling of {@link #NEXT_PAGE_FIELD}. */
    private static final String FALSE_TOKEN = "false";

    /**
     * The widest page token accepted before the value is parsed at all.
     *
     * <p>Eight digits, the width of {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}. The <em>domain</em> check remains
     * {@link #requirePageWithinBrowseDomain(int)}, which is where that rule is stated and cited; this bound
     * exists only so the parse can never see an arbitrarily long digit run and so a rejected value is
     * rejected as out of range rather than as an overflow.</p>
     */
    private static final int MAXIMUM_PAGE_TOKEN_DIGITS = 8;

    /**
     * Name of the transaction identifier request parameter. The symbolic-map counterpart is
     * {@code TRNIDINI}, {@code app/cpy-bms/COTRN01.CPY:60} for the detail screen and
     * {@code app/cpy-bms/COTRN00.CPY:66} for the list screen.
     */
    private static final String TRANSACTION_ID_FIELD = "transactionId";

    /**
     * Refusal text for an omitted detail identifier. The source refuses a blank
     * {@code TRNIDINI} before it reads anything, so an absent identifier is a client error and never a
     * lookup that returns nothing.
     */
    private static final String TRANSACTION_ID_REQUIRED_MESSAGE =
            "transactionId must be supplied; the detail operation refuses a blank identifier before it "
                    + "reads, exactly as app/cbl/COTRN01C.cbl does";

    /** Abend code the fatal exception carries when this controller raises one; {@code 999}. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Abend reason recorded when the list operation fails unexpectedly. */
    private static final String LIST_ABEND_REASON = "TRANSACTION LIST FAILED UNEXPECTEDLY";

    /** Abend reason recorded when the detail operation fails unexpectedly. */
    private static final String DETAIL_ABEND_REASON = "TRANSACTION VIEW FAILED UNEXPECTEDLY";

    /** Abend reason recorded when the add operation fails unexpectedly. */
    private static final String ADD_ABEND_REASON = "TRANSACTION ADD FAILED UNEXPECTEDLY";

    /** Abend message recorded when the list operation fails unexpectedly. */
    private static final String LIST_ABEND_MESSAGE = "UNEXPECTED ERROR IN CT00 TRANSACTION LIST.";

    /** Abend message recorded when the detail operation fails unexpectedly. */
    private static final String DETAIL_ABEND_MESSAGE = "UNEXPECTED ERROR IN CT01 TRANSACTION VIEW.";

    /** Abend message recorded when the add operation fails unexpectedly. */
    private static final String ADD_ABEND_MESSAGE = "UNEXPECTED ERROR IN CT02 TRANSACTION ADD.";

    /** Problem title used for every refusal this controller reports. */
    private static final String VALIDATION_PROBLEM_TITLE = "Transaction request rejected";

    /** Problem title used when a requested record does not exist. */
    private static final String NOT_FOUND_PROBLEM_TITLE = "Transaction not found";

    /** Problem title used when a generated identifier collided. */
    private static final String DUPLICATE_PROBLEM_TITLE = "Transaction identifier already taken";

    /** Problem title used when the transaction store is unreachable or an access against it failed. */
    private static final String STORE_PROBLEM_TITLE = "Transaction store unavailable";

    /** Problem title used for a fatal failure. */
    private static final String FAILURE_PROBLEM_TITLE = "Transaction processing failed";

    /**
     * Fixed detail for a fatal failure. Deliberately uninformative: the cause is logged, never returned,
     * which is the counterpart of the source displaying response and reason codes to the operator rather
     * than to the terminal.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The transaction request could not be completed. The failure has been logged.";

    /** Problem property naming the rejected input. */
    private static final String FIELD_PROPERTY = "field";

    /** Problem property distinguishing an omitted value from a supplied-and-wrong one. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /**
     * The problem-detail property carrying the stable, machine-readable code for the failure class.
     * <p>
     * Every error body this controller returns carries exactly one of the {@code ERROR_CODE_*} constants
     * below. A client branches on that code, never on the wording of {@code detail} and never on a property
     * naming an internal resource: the code is the supported contract, so the internal detail that used to
     * travel beside it could be withdrawn without breaking any caller.
     */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * The problem-detail property carrying the correlation identifier of the failing request.
     * <p>
     * This is the hinge of the {@code CWE-209} fix. The relation, constraint, logical file, operation and
     * file-status values that used to be returned to the client are now written only to the log, and this
     * identifier is what lets a caller reporting a failure be joined to those log records: it is the same
     * value {@code CorrelationIdFilter} placed in the diagnostic context and echoed on the
     * {@code X-Correlation-Id} response header, so support can retrieve the internal detail while an
     * attacker holding the response body cannot.
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value substituted when no correlation identifier is in the diagnostic context.
     * <p>
     * {@code CorrelationIdFilter} runs at {@code HIGHEST_PRECEDENCE} and every request that reaches a
     * handler here has passed through it, so this is unreachable in the server. It exists because a
     * standalone unit test may invoke a handler directly, and because a null property would serialise as a
     * {@code null} member and make the body's shape depend on how it was produced.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * Fixed detail for an unreachable transaction store.
     * <p>
     * Fixed rather than relayed. This type is composed in one place, {@code FileStatusMapper}, whose message
     * names the operation, the logical file and the {@code COBOL FILE STATUS}; relaying it published all
     * three. The operator text is logged at {@code ERROR} and is reachable by correlation identifier.
     */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "The transaction store is not currently available. Retry the request.";

    /**
     * Fixed detail for an input-output failure against the transaction store.
     * <p>
     * Fixed for the same reason as {@value #UNAVAILABLE_PROBLEM_DETAIL}: on this resource group a
     * {@code FileAccessException} is raised by {@code TransactionListService} through
     * {@code FileStatusMapper}, so its message is the composed internal diagnostic rather than a screen
     * caption.
     */
    private static final String IO_PROBLEM_DETAIL =
            "The transaction store reported a failure. Quote the correlation identifier when reporting this.";

    /**
     * Stable error code meaning that the request was refused by a field-level validation rule.
     */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /**
     * Stable error code meaning that a record the operation needed does not exist.
     */
    private static final String ERROR_CODE_NOT_FOUND = "CARDDEMO-RECORD-NOT-FOUND";

    /**
     * The fixed detail returned when the requested transaction does not exist.
     *
     * <p><strong>Fixed rather than relayed, on a type rule rather than a path argument.</strong> This
     * exception type is one of the five {@code FileStatusMapper} composes, and the message it composes names
     * the operation, the logical file and the {@code COBOL FILE STATUS}. It happens to be true today that no
     * collaborator on this resource group reaches that mapper - but that is a whole-program property, not one
     * a reader of this file can check, and one line added to a service three files away would reinstate the
     * disclosure with no test failing. The rule is therefore applied by type: a detail is never taken from an
     * exception the mapper can construct. Only {@code ValidationException} and
     * {@code ConcurrentUpdateException}, which appear nowhere in that mapper, keep their relayed literal.
     */
    private static final String NOT_FOUND_PROBLEM_DETAIL =
            "No transaction was found for the identifier supplied.";

    /**
     * The fixed detail returned when the generated transaction identifier was already taken.
     *
     * <p>Fixed for the same type-based reason as {@value #NOT_FOUND_PROBLEM_DETAIL}, and it states the one
     * thing a caller can act on: the retained descending-browse race of
     * {@code app/cbl/COTRN02C.cbl:L444-L449} makes the request retryable.
     */
    private static final String DUPLICATE_PROBLEM_DETAIL =
            "The transaction identifier generated for this request was already taken. Retry the request.";

    /**
     * Stable error code meaning that the key the operation generated was already taken.
     */
    private static final String ERROR_CODE_DUPLICATE = "CARDDEMO-DUPLICATE-RECORD";

    /**
     * Stable error code meaning that a required data store or queue could not be reached; the request is retryable.
     */
    private static final String ERROR_CODE_UNAVAILABLE = "CARDDEMO-RESOURCE-UNAVAILABLE";

    /**
     * Stable error code meaning that the store behind this service reported an input-output failure.
     */
    private static final String ERROR_CODE_IO_FAILURE = "CARDDEMO-IO-FAILURE";

    /**
     * Stable error code meaning that processing terminated abnormally.
     */
    private static final String ERROR_CODE_ABEND = "CARDDEMO-PROCESSING-ABEND";

    /**
     * Stable error code meaning that an unexpected typed failure occurred.
     */
    private static final String ERROR_CODE_INTERNAL = "CARDDEMO-INTERNAL-FAILURE";

    /** Response header naming the location of a newly added transaction. */
    private static final String LOCATION_HEADER = "Location";

    /**
     * The logger. Static and final, so it is not mutable state. The {@code correlationId}, {@code traceId}
     * and {@code spanId} every line carries are placed in the diagnostic context by the correlation filter;
     * this class neither adds, renames, overwrites, removes nor clears any of them, and it registers no
     * metric of its own.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionController.class);

    /** The service replacing {@code app/cbl/COTRN00C.cbl}; owns the paged browse and the page size. */
    private final TransactionListService transactionListService;

    /** The service replacing {@code app/cbl/COTRN01C.cbl}; owns the single-record read. */
    private final TransactionDetailService transactionDetailService;

    /** The service replacing {@code app/cbl/COTRN02C.cbl}; owns validation, identifier generation and the write. */
    private final TransactionAddService transactionAddService;

    /**
     * Assembles the controller.
     *
     * <p>Constructor injection is the only injection form used: no field injection, no setter injection and
     * no annotation-driven lookup. All three collaborators are therefore final and non-null for the
     * lifetime of the bean, the class holds no global mutable state, and a unit test can construct it with
     * three stubs and no application context. Each argument is validated rather than trusted, because a
     * null collaborator would otherwise surface as a failure on the first request instead of at context
     * refresh - which is a slower and much less obvious diagnosis.</p>
     *
     * @param transactionListService the paged-browse service replacing {@code app/cbl/COTRN00C.cbl}; must
     *                               not be null.
     * @param transactionDetailService the single-read service replacing {@code app/cbl/COTRN01C.cbl}; must
     *                                 not be null.
     * @param transactionAddService the write service replacing {@code app/cbl/COTRN02C.cbl}; must not be
     *                              null.
     * @throws IllegalArgumentException if any service is null, which is a bean-wiring defect rather than a
     *                                 request-time condition
     */
    public TransactionController(final TransactionListService transactionListService,
            final TransactionDetailService transactionDetailService,
            final TransactionAddService transactionAddService) {

        if (transactionListService == null) {
            throw new IllegalArgumentException(
                    "transactionListService must not be null; it is the replacement for "
                            + "app/cbl/COTRN00C.cbl");
        }
        if (transactionDetailService == null) {
            throw new IllegalArgumentException(
                    "transactionDetailService must not be null; it is the replacement for "
                            + "app/cbl/COTRN01C.cbl");
        }
        if (transactionAddService == null) {
            throw new IllegalArgumentException(
                    "transactionAddService must not be null; it is the replacement for "
                            + "app/cbl/COTRN02C.cbl");
        }

        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Operation 1 of 3 on this controller, and operation 9 of the seventeen the REST surface exposes. Lists
     * transactions one page at a time.
     *
     * <h4>Provenance</h4>
     *
     * <p>Replaces CICS transaction {@code CT00} - {@code DEFINE TRANSACTION(CT00)} at
     * {@code app/csd/CARDDEMO.CSD:L419} naming {@code PROGRAM(COTRN00C)} at {@code :L420} - and the program
     * it fronted, {@code app/cbl/COTRN00C.cbl}, 699 lines with 16 paragraph labels, which painted mapset
     * {@code COTRN00}. Concretely it is the Java form of the {@code EVALUATE EIBAID} dispatch at
     * {@code :L119-L134}, whose three data-bearing arms are {@code WHEN DFHENTER} at {@code :L120},
     * {@code WHEN DFHPF7} at {@code :L125} and {@code WHEN DFHPF8} at {@code :L127}.</p>
     *
     * <h4>Purpose</h4>
     *
     * <p>Returns one page of the transaction browse. All work is done by
     * {@code TransactionListService}: this method rebuilds the browse position from the request, calls the
     * service <strong>once</strong>, and returns the page the service produced. It performs no browse, no
     * comparison, no counting and no ordering of its own.</p>
     *
     * <h4>Inputs, and why pagination looks like this</h4>
     *
     * <p>Transformation rule 7 moves conversational state onto the wire: the paging cursor becomes request
     * parameters and the paging outcome becomes response metadata. The four parameters that carry it are the
     * four program-local extension fields of {@code 05 CDEMO-CT00-INFO} at {@code app/cbl/COTRN00C.cbl:L62}
     * - {@code firstKey} for {@code CDEMO-CT00-TRNID-FIRST PIC X(16)} at {@code :L63}, {@code lastKey} for
     * {@code CDEMO-CT00-TRNID-LAST PIC X(16)} at {@code :L64}, {@code page} for
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at {@code :L65} and {@code nextPageAvailable} for
     * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)} at {@code :L66}. A client echoes back what the previous
     * response reported; supplying none of them starts a fresh browse, which is the initial state the
     * source establishes with {@code SET NEXT-PAGE-NO TO TRUE} at {@code :L99}.</p>
     *
     * <p><strong>The legacy sentinel byte is not transported.</strong> {@code COTRN00C} spells its flag
     * {@code 'Y'} and {@code 'N'} through the condition names at {@code :L67} and {@code :L68}, while the
     * card-list program expresses the same concept with low values instead. The two are incompatible, so
     * neither is placed on the wire: {@code nextPageAvailable} is a boolean, and the sentinel stays a
     * terminal representation belonging to the program that used it.</p>
     *
     * <p><strong>Forward and backward paging are both preserved, through one operation.</strong> The
     * direction travels as the {@code action} parameter, whose three values are the business meanings of the
     * three dispatch arms: {@code SUBMIT} for the enter key at {@code :L120}, {@code PAGE_BACKWARD} for
     * {@code DFHPF7} at {@code :L125} and {@code PAGE_FORWARD} for {@code DFHPF8} at {@code :L127}. This is
     * controller-level action mapping and it is deliberately <em>not</em> two further endpoints - which
     * would create operations eighteen and nineteen - and deliberately not a generic attention-identifier
     * string, which would re-expose the {@code EVALUATE EIBAID} cascade as public API. The enumeration is
     * the mapping; there is no function-key dispatcher here. The two arms with no data outcome are
     * unrepresentable by construction: {@code WHEN DFHPF3} at {@code :L122} was navigation, now expressed by
     * the client choosing a different URL, and {@code WHEN OTHER} at {@code :L129} was an invalid-key
     * message, which cannot arise when the only inputs are three named constants.</p>
     *
     * <p>{@code transactionId} is the search key, {@code TRNIDINI PIC X(16)} at
     * {@code app/cpy-bms/COTRN00.CPY:66}, relayed verbatim. Absent, blank and populated are three distinct
     * states and are kept distinct: an omitted parameter arrives as null and a supplied empty one arrives as
     * the empty string, and <strong>neither is coerced into the other</strong>. Only the service is entitled
     * to interpret the difference, which it does exactly where the source does.</p>
     *
     * <p>Row selection has no wire form. The source offered {@code SEL0001I} through {@code SEL0010I}, each
     * {@code PIC X(1)}, so that a 3270 user could mark a row and be transferred to the detail or add screen.
     * In REST that transfer <em>is</em> a different URL, so the selection vector is passed empty and a
     * client navigates to {@link #getTransactionDetail} instead. Nothing is lost: the identifier needed to
     * navigate is on every row of the page.</p>
     *
     * <h4>Outputs</h4>
     *
     * <p>{@code 200} with a {@code TransactionListResponse} of transaction list rows: up to ten rows, each
     * carrying the identifier ({@code TRNIDnnI PIC X(16)}), the date ({@code TDATEnnI PIC X(8)}), the
     * description ({@code TDESCnnI PIC X(26)}) and the amount ({@code TAMTnnI PIC X(12)}), plus the page
     * number, the page size applied, the boolean next-page indicator and the two keyset cursors. Ordering is
     * deterministic - ascending by transaction identifier, the browse order of the {@code TRANSACT} cluster -
     * so the same request returns the same page.</p>
     *
     * <p><strong>The envelope also carries the turn's own status text, and that is the point of it.</strong>
     * {@code ERRMSGO} at {@code app/cbl/COTRN00C.cbl} is how the source reported "you are already at the top
     * of the page" at {@code :L258-L262} and "you have reached the bottom" at {@code :L267-L272} - conditions
     * that are answers rather than faults, so they cannot be expressed as a status code and would otherwise
     * be lost entirely. It is relayed byte for byte as {@code statusMessage}. The per-row selection flag is
     * <em>not</em> carried: it is a 3270 input marker, and in REST the navigation it enabled is a different
     * URL, reachable from the identifier every row already carries.</p>
     *
     * <p><strong>The description here is 26 characters wide, and that is not the same field as anywhere
     * else.</strong> {@code TDESC01I} through {@code TDESC10I} are {@code PIC X(26)} in
     * {@code app/cpy-bms/COTRN00.CPY}, the detail screen's {@code TDESCI} is {@code PIC X(60)} at
     * {@code app/cpy-bms/COTRN01.CPY:96}, and the persisted {@code TRAN-DESC} is {@code PIC X(100)} at
     * {@code app/cpy/CVTRA05Y.cpy:9}. Three widths for one datum. All three are carried faithfully; none is
     * widened, narrowed or unified, because each is the contract of the surface it belongs to.</p>
     *
     * <p><strong>There is no total-element count and no total-page count: {@code Not available}.</strong>
     * This is not an omission. The source never computes one - it discovers whether a further page exists by
     * reading one record beyond the page and testing the result, at {@code app/cbl/COTRN00C.cbl:L305-L315} -
     * so a total would have to be invented, and inventing one would also cost a count query the source never
     * issues. What would be needed to supply one: a decision to diverge from the source, plus a counting
     * query, plus a decision-log entry recording both. None of those is in scope.</p>
     *
     * <h4>Side effects</h4>
     *
     * <p>None. This operation reads.</p>
     *
     * <h4>Configuration and defaults</h4>
     *
     * <p>This method reads no property. The page size is ten and is bound once by the service from
     * {@code carddemo.pagination.transaction-list-page-size}; <strong>no page-size literal appears in this
     * class</strong>. The two locators that establish the figure - the paging fields at
     * {@code app/cbl/COTRN00C.cbl:L65-L68} for the semantics, and the ten row slots {@code TRNID01I} through
     * {@code TRNID10I} with {@code TDESC01I} through {@code TDESC10I} in {@code app/cpy-bms/COTRN00.CPY} for
     * the count - are set out in this class's documentation, along with why one alone would not do. Defaults
     * on this operation: an absent {@code action} means {@code SUBMIT} and an absent {@code page} means zero,
     * which together mean "the first page of a fresh browse"; an absent {@code nextPageAvailable} means
     * false; and the three text parameters default to absent. Every one of those defaults applies to an
     * <em>absent</em> parameter only - a parameter that is present is matched exactly, and an empty one is a
     * blank refusal rather than a default.</p>
     *
     * <h4>Failure modes and troubleshooting</h4>
     *
     * <p>{@code 400} when {@code page} falls outside the domain of the eight-digit source counter or is not
     * a run of ASCII digits, when {@code action} is not exactly one of the three declared tokens, when
     * {@code nextPageAvailable} is neither {@code true} nor {@code false}, or when the service refuses the
     * search key. No control token is trimmed, case folded or read through an alias: a signed page, a padded
     * action and {@code yes} in place of {@code true} are all refusals. {@code 401} when no identity reached
     * the operation. {@code 502} and {@code 503} when the store cannot be read or opened. {@code 500}
     * otherwise.</p>
     *
     * <p>Boundary conditions worth knowing, because each is answered rather than avoided. Page zero is
     * accepted and means an unstarted browse - it is the value {@code TransactionListState} itself reports
     * initially, so refusing it would refuse a client's own echo. A page past the end returns
     * {@code 200} with an empty row list and {@code nextPageAvailable} false, because file status
     * {@code '10'} is end of data and not an error; that is exactly what ends the source's browse loop. An
     * empty result set returns {@code 200} with zero rows for the same reason - never {@code 404}, because
     * a collection that happens to be empty was still found. A final partial page returns fewer than ten
     * rows. And a paging request that has run out of records returns the same page again, which is the
     * source's own behaviour at {@code :L267-L272} where it reports being at the bottom of the list and
     * redisplays; that message is informational rather than an error, so it does not become a failure
     * status.</p>
     *
     * <p>If a request that looks correct returns an unexpectedly empty page, check {@code firstKey} and
     * {@code lastKey} first: they are the keyset anchors, and a stale pair addresses a position that no
     * longer holds records.</p>
     *
     * @param transactionId the search key as typed, {@code TRNIDINI PIC X(16)}, or null when the parameter
     *                      was absent. Relayed verbatim; absent and blank are kept distinct.
     * @param actionToken which of the three data-bearing dispatch arms to run, as typed and matched exactly
     *                    against the three constant names; null means {@code SUBMIT}, empty is a blank
     *                    refusal and anything else is an invalid one.
     * @param pageToken the page number the previous response reported,
     *                  {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}; null means zero, the value of a fresh browse,
     *                  and a present value must be a run of at most eight ASCII digits with no sign and no
     *                  padding.
     * @param firstKey the identifier of the first row of the page being left,
     *                 {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}, or null on a fresh browse.
     * @param lastKey the identifier of the last row of the page being left,
     *                {@code CDEMO-CT00-TRNID-LAST PIC X(16)}, or null on a fresh browse.
     * @param nextPageToken whether the previous response reported a further page; the boolean form of
     *                      {@code CDEMO-CT00-NEXT-PAGE-FLG}, never the sentinel byte, and accepted only as
     *                      {@code true} or {@code false}.
     * @param authentication the principal the security filter chain resolved, or null when none did.
     * @return {@code 200 OK} with one page of transaction rows, its paging metadata and the turn's status
     *         text, or {@code 401 Unauthorized} when no identity reached the operation; never null
     * @throws ValidationException if a control token is not exactly one of its declared spellings, if
     *                             {@code page} is outside the eight-digit browse domain, or the service
     *                             refuses the search key; mapped to {@code 400} by
     *                             {@link #handleValidationFailure}
     * @throws FileUnavailableException if the transaction store could not be opened; mapped to {@code 503}
     *                                  by {@link #handleFileUnavailable}
     * @throws FileAccessException if the browse failed; mapped to {@code 502} by
     *                             {@link #handleFileAccessFailure}
     * @throws FatalProcessingException if the browse fails for any reason other than a typed CardDemo
     *                                  failure; mapped to {@code 500} by {@link #handleAbend}
     */
    @GetMapping
    public ResponseEntity<TransactionListResponse> listTransactions(
            @RequestParam(name = "transactionId", required = false) final String transactionId,
            @RequestParam(name = ACTION_FIELD, required = false) final String actionToken,
            @RequestParam(name = PAGE_FIELD, required = false) final String pageToken,
            @RequestParam(name = "firstKey", required = false) final String firstKey,
            @RequestParam(name = "lastKey", required = false) final String lastKey,
            @RequestParam(name = NEXT_PAGE_FIELD, required = false) final String nextPageToken,
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of the IF EIBCALEN = 0 arm at "
                    + "app/cbl/COTRN00C.cbl:L107-L109", LIST_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Every control token is matched here, exactly, rather than converted. The framework's default
        // converters normalise - they trim an enum token, accept a signed integer and treat six spellings as
        // a boolean - and a normalising converter on a control token silently accepts an instruction the
        // operation never declared. See resolveAction, requirePageToken and requireBooleanToken.
        final TransactionListAction action = resolveAction(actionToken);
        final int page = requirePageToken(pageToken);
        final boolean nextPageAvailable = requireBooleanToken(nextPageToken);

        requirePageWithinBrowseDomain(page);

        // The four extension fields of 05 CDEMO-CT00-INFO (app/cbl/COTRN00C.cbl:L62-L68), rebuilt from the
        // wire rather than from a session. They are placed exactly as received: only the service is
        // entitled to interpret them, and it clamps and resets the page number where the source does.
        final TransactionListService.TransactionListState state =
                new TransactionListService.TransactionListState(firstKey, lastKey, page, nextPageAvailable);

        final TransactionListService.TransactionListScreen screen =
                retrieveTransactionPage(action, transactionId, state);

        final PageResponse<TransactionDto.TransactionListRow> listing = screen.page();
        final TransactionListResponse response = TransactionListResponse.of(listing,
                listing.getFirstKey(), listing.getLastKey(), screen.list().errorMessage());

        // Presence and counts, never content. A row carries a card-derived identifier, a description and an
        // amount, and none of those may reach a log line; the keyset anchors are identifiers too.
        LOG.debug("Served transaction {} program {} action {}: page={}, size={}, rows={}, nextPage={}, "
                + "searchKeyPresent={}, statusMessagePresent={}", LIST_TRANSACTION_ID, LIST_PROGRAM, action,
                response.pageNumber(), response.pageSize(), response.rows().size(),
                response.nextPageAvailable(), transactionId != null, response.statusMessage() != null);

        return ResponseEntity.ok(response);
    }

    /**
     * Operation 2 of 3 on this controller, and operation 10 of the seventeen the REST surface exposes.
     * Returns one transaction's details.
     *
     * <h4>Provenance</h4>
     *
     * <p>Replaces CICS transaction {@code CT01} - {@code DEFINE TRANSACTION(CT01)} at
     * {@code app/csd/CARDDEMO.CSD:L429} naming {@code PROGRAM(COTRN01C)} at {@code :L430} - and the program
     * it fronted, {@code app/cbl/COTRN01C.cbl}, 330 lines with 9 paragraph labels, which painted mapset
     * {@code COTRN01}. Specifically it is the Java form of the enter-key path: the lookup of one record in
     * {@code READ-TRANSACT-FILE.} at {@code :L267}, whose twenty-one screen fields are the twenty-one input
     * fields of the generated symbolic map {@code app/cpy-bms/COTRN01.CPY}.</p>
     *
     * <h4>Purpose</h4>
     *
     * <p>Retrieves a single transaction by identifier and returns its detail projection. The read, the
     * status handling and the field-by-field projection all belong to {@code TransactionDetailService};
     * this method calls it once.</p>
     *
     * <h4>A preserved quirk: the read-only path issues an update-intent read</h4>
     *
     * <p>{@code app/cbl/COTRN01C.cbl:L275} carries the operand {@code UPDATE} inside the
     * {@code EXEC CICS READ} that begins at {@code :L269} - a read that takes an exclusive lock on the
     * record - even though this transaction only displays the record and never rewrites it. Nothing in the
     * program writes: the lock is acquired and then released at task end, and its only effect is to block
     * concurrent updaters for the duration of a read.</p>
     *
     * <p><strong>It is preserved, not repaired.</strong> Behavioural parity is the contract of this
     * migration, so the update-intent read is reproduced in the <em>service</em> layer, where the paragraph
     * map lives. It is documented at this operation but not reproduced at it, because a controller has no
     * paragraph map and holds no transaction boundary. Removing the operand would be a behaviour
     * change - it would alter the locking observable to a concurrent updater - and is therefore
     * out of scope regardless of how much it looks like a defect.</p>
     *
     * <h4>Inputs</h4>
     *
     * <p>One parameter: the transaction identifier, {@code TRNIDINI PIC X(16)} at
     * {@code app/cpy-bms/COTRN01.CPY:60}, relayed verbatim as the text the map declared it to be.
     * <strong>Absent, blank and populated are three distinct states.</strong> An omitted parameter and a
     * supplied empty one are not coerced into each other: both are refused, but they are refused with
     * different failure kinds, so a client can tell "you sent nothing" from "you sent nothing useful". That
     * three-state model - satisfied, unsatisfied and blank - is the shape
     * {@code app/cpy/CSSETATY.cpy} imposes through its parameterised {@code COPY ... REPLACING} template,
     * and it is why the refusal reports a kind alongside a message. The source itself refuses a blank
     * identifier before it reads anything, so an absent identifier is a client error and never a lookup that
     * returns nothing.</p>
     *
     * <h4>Outputs</h4>
     *
     * <p>{@code 200} with a {@code TransactionResponse}: the identifier, the <strong>tail-masked</strong>
     * card number, the type and category codes, the source, the description, the amount, the originating and
     * processing dates, the four merchant fields and the status message. Every field is at the width its
     * symbolic map declares.</p>
     *
     * <p><strong>The card number is masked and the six header fields are not carried.</strong> A primary
     * account number may not appear in an HTTP response, and {@code app/cpy-bms/COTRN01.CPY}'s
     * {@code CARDNI PIC X(16)} is one; the masking rule is stated once, in {@code ApiMasking}, and applied
     * here through the response type rather than restated. The header fields - the transaction name, the two
     * titles, the date and the time - painted a 3270 screen and carry no transaction data, so they are
     * omitted rather than transported.</p>
     *
     * <p><strong>Field-contract note - this description is 60 characters, not 26 and not 100.</strong>
     * {@code TDESCI} is {@code PIC X(60)} at {@code app/cpy-bms/COTRN01.CPY:96}. The list operation's
     * {@code TDESCnnI} is {@code PIC X(26)} and the persisted {@code TRAN-DESC} is {@code PIC X(100)} at
     * {@code app/cpy/CVTRA05Y.cpy:9}. The correct width for this response is 60, and the three are never
     * unified.</p>
     *
     * <p>The amount is rendered on the legacy edited display mask by the service and its projection, not
     * here. The two timestamps are relayed as the 26-character text they are declared to be at
     * {@code app/cpy/CVTRA05Y.cpy:16} and {@code :17} - see this class's documentation for why a temporal
     * type would break parity - and this method neither parses nor reformats them.</p>
     *
     * <h4>Side effects</h4>
     *
     * <p>None that change data. One record is read, under the update intent described above.</p>
     *
     * <h4>Configuration and defaults</h4>
     *
     * <p>None. This operation reads no property, and its single parameter has no default: its absence is a
     * refusal rather than a default.</p>
     *
     * <h4>Failure modes and troubleshooting</h4>
     *
     * <p>{@code 400} when the identifier is absent or blank, naming the field and the failure kind.
     * {@code 404} when no transaction carries that identifier, carrying the byte-exact legacy literal
     * {@code Transaction ID NOT found...} - three trailing periods, exactly as
     * {@code app/cbl/COTRN01C.cbl:L285} spells it - which is the translation of the
     * {@code WHEN DFHRESP(NOTFND)} arm at {@code :L283}. {@code 401} without a token. {@code 502} or
     * {@code 503} when the store cannot be read or opened. {@code 500} otherwise, carrying abend code
     * {@code 999} and return code {@code 12}.</p>
     *
     * <p>If a lookup returns {@code 404} for an identifier that a list page just displayed, check whether
     * the identifier was left-padded: the source's key is a fixed sixteen-character field, so a shorter
     * value addresses a different key.</p>
     *
     * @param transactionId the identifier to look up, {@code TRNIDINI PIC X(16)}; required, and relayed
     *                      verbatim with absent and blank kept distinct.
     * @param authentication the principal the security filter chain resolved, or null when none did.
     * @return {@code 200 OK} with the transaction detail projection, or {@code 401 Unauthorized} when no
     *         identity reached the operation; never null
     * @throws ValidationException if the identifier is absent or blank; mapped to {@code 400} by
     *                             {@link #handleValidationFailure}
     * @throws RecordNotFoundException if no transaction carries that identifier; mapped to {@code 404} by
     *                                 {@link #handleRecordNotFound}
     * @throws FileUnavailableException if the transaction store could not be opened; mapped to {@code 503}
     *                                  by {@link #handleFileUnavailable}
     * @throws FileAccessException if the read failed; mapped to {@code 502} by
     *                             {@link #handleFileAccessFailure}
     * @throws FatalProcessingException if the read fails for any reason other than a typed CardDemo
     *                                  failure; mapped to {@code 500} by {@link #handleAbend}
     */
    @GetMapping(DETAIL_PATH)
    public ResponseEntity<TransactionResponse> getTransactionDetail(
            @RequestParam(name = "transactionId", required = false) final String transactionId,
            final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of the IF EIBCALEN = 0 arm of MAIN-PARA at "
                    + "app/cbl/COTRN01C.cbl", DETAIL_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        requireTransactionIdSupplied(transactionId);

        final TransactionDetailService.TransactionDetailScreen screen =
                retrieveTransactionDetail(transactionId);

        // The identifier is not logged: it is the key of a financial record and it appears on the request
        // line already. Only the outcome shape is recorded.
        LOG.debug("Served transaction {} program {}: detail returned, errorLine={}", DETAIL_TRANSACTION_ID,
                DETAIL_PROGRAM, screen.errorFlagOn());

        return ResponseEntity.ok(TransactionResponse.of(screen.detail()));
    }

    /**
     * Operation 3 of 3 on this controller, and operation 11 of the seventeen the REST surface exposes. Adds
     * a transaction.
     *
     * <h4>Provenance</h4>
     *
     * <p>Replaces CICS transaction {@code CT02} - {@code DEFINE TRANSACTION(CT02)} at
     * {@code app/csd/CARDDEMO.CSD:L439} naming {@code PROGRAM(COTRN02C)} at {@code :L440} - and the program
     * it fronted, {@code app/cbl/COTRN02C.cbl}, 783 lines with 18 paragraph labels, which painted mapset
     * {@code COTRN02}. The request body's twenty-one fields are the twenty-one input fields of the generated
     * symbolic map {@code app/cpy-bms/COTRN02.CPY}, one for one, at the widths that map declares.</p>
     *
     * <h4>Purpose</h4>
     *
     * <p>Validates and writes one transaction. Every part of that - the validation cascade, the two numeric
     * conversions, the identifier generation, the record assembly and the write - belongs to
     * {@code TransactionAddService}, which this method calls once. It performs no validation of its own
     * beyond the declarative field-width constraints the request record carries, and it generates nothing.</p>
     *
     * <h4>Two distinct numeric parsers, used deliberately</h4>
     *
     * <p>The source uses <strong>two different numeric intrinsics on the same screen</strong>, and the
     * asymmetry is deliberate rather than incidental. Using one for both is a behaviour change: it either
     * accepts input the legacy system rejects, or rejects input it accepts.</p>
     *
     * <ul>
     *   <li><strong>Plain {@code FUNCTION NUMVAL} - strict, digits only - for identifiers.</strong> The
     *       account identifier at {@code app/cbl/COTRN02C.cbl:L204-L205} and the card number at
     *       {@code :L218-L219}. Each is guarded by an {@code IS NOT NUMERIC} class test first, and the card
     *       number's refusal carries the literal {@code Card Number must be Numeric...}. A currency symbol
     *       or a thousands separator in either of these is rejected.</li>
     *   <li><strong>Currency-aware {@code FUNCTION NUMVAL-C} - tolerant of currency symbols and thousands
     *       separators - for the amount, and only the amount.</strong> At
     *       {@code app/cbl/COTRN02C.cbl:L383-L384} on the edit path and again at {@code :L456-L457} when the
     *       record is assembled.</li>
     * </ul>
     *
     * <p>Both conversions are performed by the service, and the two parsing behaviours are also registered
     * as the two converters {@code WebConfig} contributes, so a value arriving as a request parameter
     * elsewhere in the application is bound on the same terms. <strong>This method adds no parser, no
     * formatter and no {@code @InitBinder}</strong>, and it could not usefully do so: every one of the
     * twenty-one request fields is text, exactly as the 3270 map carried it, so nothing is converted at this
     * boundary at all. That is what keeps the asymmetry intact - a controller that pre-parsed the body would
     * have to choose one parser and would silently destroy the distinction.</p>
     *
     * <p>The echo contract is equally specific and equally not this method's work. The amount is a
     * nine-integer-digit, two-decimal value at {@code app/cbl/COTRN02C.cbl:L58}
     * ({@code WS-TRAN-AMT-N PIC S9(9)V99}) but is echoed through an edited field at {@code :L59}
     * ({@code WS-TRAN-AMT-E}) whose mask is {@code +99999999.99} - a mandatory sign, exactly
     * <strong>eight</strong> integer digits and two decimals, one integer digit fewer than the value it
     * renders. The round trip is {@code MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E} at {@code :L385} then
     * {@code MOVE WS-TRAN-AMT-E TO TRNAMTI} at {@code :L386}, and it is reproduced by the service and its
     * projection. Nothing on this method formats an amount.</p>
     *
     * <h4>Inputs</h4>
     *
     * <p>One JSON body: the twenty-one fields of {@code app/cpy-bms/COTRN02.CPY}, including
     * {@code TRNSRCI PIC X(10)} at {@code :84}, {@code TDESCI PIC X(60)} at {@code :90},
     * {@code TRNAMTI PIC X(12)} at {@code :96} and {@code TORIGDTI PIC X(10)} at {@code :102}. Declarative
     * width constraints on the record are checked before this method runs.</p>
     *
     * <p><strong>The request must not, and cannot, supply an identifier.</strong> The record carries no
     * identifier field, so the API makes it unexpressible rather than merely forbidden.</p>
     *
     * <p><strong>No validation is added for the transaction source.</strong>
     * {@code app/cbl/COTRN02C.cbl:L454} moves {@code TRNSRCI} straight into {@code TRAN-SOURCE} with no
     * class test, no lookup and no length check beyond the field width. Adding one would reject records the
     * legacy system accepts, so none is added - here or in the service.</p>
     *
     * <h4>The generated identifier, and the race that is kept on purpose</h4>
     *
     * <p>The identifier is produced by the service using the source's own descending-browse maximum-key
     * idiom at {@code app/cbl/COTRN02C.cbl:L444-L449}: move high values into the key at {@code :L444},
     * start a browse at {@code :L445}, read the previous record at {@code :L446}, end the browse at
     * {@code :L447}, take the identifier at {@code :L448} and add one at {@code :L449}. On an empty file the
     * browse finds nothing, the identifier is zero, and <strong>the first generated identifier is
     * therefore 1</strong>.</p>
     *
     * <p>That algorithm is inherently racy under concurrency - precisely as the browse was, because reading
     * the maximum and inserting past it are two steps. The <strong>parity-preserving choice is to keep
     * it</strong> and let the primary key surface a collision as a duplicate-record failure, rather than
     * substituting a database sequence. A sequence would generate different values, would not reproduce the
     * first-identifier-is-one behaviour, and would break comparison against the baseline. This is a
     * deliberate, labelled decision rather than an oversight, and it is why {@code 409} is a normal
     * documented outcome of this operation rather than an internal error.</p>
     *
     * <h4>Outputs</h4>
     *
     * <p>{@code 201} with a {@code Location} header addressing the new transaction through
     * {@link #getTransactionDetail}, and a {@code TransactionResponse} as the body, when the write happened.
     * {@code 200} with the same response shape and no {@code Location} header when the interaction terminated
     * without a write - which the source does on four distinct paths, none of them an error: the confirmation
     * prompt of its two-phase handshake, a screen display, a screen clear, and an unsupported key. The
     * request was understood and answered in all four cases, so a failure status would misreport them.</p>
     *
     * <h4>Side effects</h4>
     *
     * <p>On the {@code 201} path exactly one transaction row is inserted, inside the service's transaction
     * boundary. On every other path there is no side effect at all.</p>
     *
     * <h4>Configuration and defaults</h4>
     *
     * <p>None. This operation reads no property and applies no default to any body field: an absent field
     * arrives as null and stays null, which is how the source's blank map field is represented.</p>
     *
     * <h4>Failure modes and troubleshooting</h4>
     *
     * <p>{@code 400} on the first cascade refusal - validation is fail-fast, so exactly one field is
     * reported and no later check runs. The refusals include a non-numeric account identifier, a card number
     * that is not sixteen digits (a fifteen- or seventeen-digit value is refused as invalid, not as
     * missing), an amount the currency-aware conversion rejects, and a date the date validator rejects
     * against the format {@code YYYY-MM-DD} declared at {@code app/cbl/COTRN02C.cbl:L60} and handed over at
     * {@code :L389-L390}. {@code 404} when neither cross-reference lookup finds a record for the supplied
     * account or card. {@code 409} on the retained identifier race - retry, and the next browse yields the
     * following identifier. {@code 401} without a token. {@code 502} or {@code 503} when the store cannot be
     * written or opened. {@code 500} otherwise.</p>
     *
     * <p>If a body that looks correct returns {@code 400}, check the account identifier before the card
     * number: the source validates the account first and stops at the first refusal, so the reported field
     * is the earlier one. If it returns {@code 200} rather than {@code 201}, check the confirmation field -
     * the source's handshake returns a prompt on the first pass and writes only on the second.</p>
     *
     * @param request the twenty-one symbolic-map input fields, validated for width before this method runs;
     *                must not be null, and carries no identifier because the service generates it.
     * @param authentication the principal the security filter chain resolved, or null when none did.
     * @return {@code 201 Created} with a {@code Location} header when the transaction was written,
     *         {@code 200 OK} with the screen projection when the interaction ended without a write, or
     *         {@code 401 Unauthorized} when no identity reached the operation; never null
     * @throws ValidationException on the first cascade refusal, carrying the offending field and the
     *                             byte-exact legacy message; mapped to {@code 400} by
     *                             {@link #handleValidationFailure}
     * @throws RecordNotFoundException when neither cross-reference lookup finds a record; mapped to
     *                                 {@code 404} by {@link #handleRecordNotFound}
     * @throws DuplicateRecordException when the generated identifier collides, which is the retained race;
     *                                  mapped to {@code 409} by {@link #handleDuplicateRecord}
     * @throws FileUnavailableException if the transaction store could not be opened; mapped to {@code 503}
     *                                  by {@link #handleFileUnavailable}
     * @throws FileAccessException when a data access operation fails; mapped to {@code 502} by
     *                             {@link #handleFileAccessFailure}
     * @throws FatalProcessingException when the assembled record violates the persisted layout, or the write
     *                                  fails for any reason other than a typed CardDemo failure; mapped to
     *                                  {@code 500} by {@link #handleAbend}
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> addTransaction(
            @Valid @RequestBody final TransactionAddRequest request, final Authentication authentication) {

        if (isUnauthenticated(authentication)) {
            LOG.warn("Refused transaction {} with 401: no authenticated principal reached the operation. "
                    + "This is the stateless counterpart of the EIBCALEN = 0 arm at "
                    + "app/cbl/COTRN02C.cbl:L115-L117", ADD_TRANSACTION_ID);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final TransactionAddService.TransactionAddResult result = createTransaction(request);

        if (result.outcome() == TransactionAddService.Outcome.ADDED) {
            // app/cbl/COTRN02C.cbl:L724-L734, the written arm. A new resource exists, so it is addressed
            // rather than merely described. The identifier is placed in the header because that is what a
            // Location header is for; it is not written to the log.
            LOG.info("Transaction {} program {} added one transaction", ADD_TRANSACTION_ID, ADD_PROGRAM);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(LOCATION_HEADER, detailLocationOf(result.transactionId()))
                    .body(TransactionResponse.of(result.screen()));
        }

        // The four no-write terminations: the confirmation prompt of the two-phase handshake, a screen
        // display, a screen clear and an unsupported key. Each was understood and answered, so none is a
        // failure; and none of them wrote, so none of them may claim 201.
        LOG.debug("Transaction {} program {} ended with outcome {} and wrote nothing", ADD_TRANSACTION_ID,
                ADD_PROGRAM, result.outcome());
        return ResponseEntity.ok(TransactionResponse.of(result.screen()));
    }

    /**
     * Maps a refusal onto {@code 400 Bad Request}, carrying the legacy message literal verbatim.
     *
     * <p>Status selection is performed here rather than in a global advice class, because the choice is
     * contextual: the same exception type means "the caller can fix this" on an input boundary and can mean
     * something else elsewhere. There is no {@code @ControllerAdvice} anywhere in the repository and the nine
     * exception types carry no status annotation of their own, so each controller owns the decision for its
     * own operations.</p>
     *
     * <p>The detail is the exception's message, which is the source's own literal - {@code Card Number must
     * be Numeric...} from {@code app/cbl/COTRN02C.cbl:L213}, one of the sibling refusals from the same
     * cascade, a date refusal, or one of this class's own boundary refusals. Those strings are the observable
     * contract of {@code CT00}, {@code CT01} and {@code CT02} and are surfaced unmodified.</p>
     *
     * <p>The {@code field} property names the symbolic-map field the 3270 cursor would have been parked on,
     * which is what lets a client attribute a refusal without any cursor semantics being implemented. The
     * {@code failureKind} property separates an omitted value from one that was supplied and wrong,
     * reproducing the distinction {@code app/cpy/CSSETATY.cpy} draws between its not-OK and blank states.</p>
     *
     * <p>The two properties are set under different conditions, and the asymmetry is deliberate. The field
     * name is guarded, because {@code ValidationException.getFieldName()} is documented as null for a
     * request-level refusal and the type publishes {@code hasFieldName()} precisely so a caller can ask. The
     * failure kind is <strong>not</strong> guarded, because {@code getFailureKind()} is documented as never
     * null and every constructor substitutes a default; a null check there would be a branch no input can
     * reach, which Rule 1 Clause B forbids. The message is guarded again, because
     * {@code Throwable.getMessage()} is genuinely nullable.</p>
     *
     * <p>The message is not logged. It can restate a caller-supplied value, and a value on this surface may
     * be a card number; the field name and the failure kind are logged instead, which is what actually
     * diagnoses the fault.</p>
     *
     * @param rejection the refusal raised by a handler or a service; never null when the framework
     *                  dispatches here.
     * @return {@code 400 Bad Request} carrying a problem detail and the failure kind, plus the rejected field
     *         when the exception named one
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
        problem.setProperty(FAILURE_KIND_PROPERTY, rejection.getFailureKind());

        LOG.debug("Refused a transaction request with 400: field {} kind {}", rejection.getFieldName(),
                rejection.getFailureKind());

        return ResponseEntity.badRequest().body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps an absent record onto {@code 404 Not Found}.
     *
     * <p>The detail is the exception's message, which on the detail operation is the byte-exact legacy
     * literal {@code Transaction ID NOT found...} from {@code app/cbl/COTRN01C.cbl:L285}, three trailing
     * periods included. On the add operation it is the corresponding cross-reference refusal. Both are
     * surfaced unmodified because both are part of the observable contract.</p>
     *
     * <p>{@code 404} rather than {@code 400}: the request was well formed and the resource simply does not
     * exist. The record type is carried as a property when the throwing site named one, so a client can tell
     * a missing transaction from a missing cross-reference. <strong>The record key is deliberately not
     * surfaced and not logged</strong>, because on the add path that key can be a card number.</p>
     *
     * @param absent the not-found failure; never null when the framework dispatches here.
     * @return {@code 404 Not Found} carrying the fixed detail, the stable error code and the correlation
     *         identifier; the record type and the exception's message are logged rather than returned
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absent) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);

        problem.setDetail(NOT_FOUND_PROBLEM_DETAIL);

        // The record type is logged, not returned: it holds a logical file name such as TRANSACT.
        LOG.debug("Answered a transaction request with 404: recordType {}", absent.recordType().orElse(null));

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(withPublicEnvelope(problem, ERROR_CODE_NOT_FOUND));
    }

    /**
     * Maps a colliding generated identifier onto {@code 409 Conflict}.
     *
     * <p>This is the retained race of the add operation, not a defect. The source generates an identifier by
     * reading the maximum key and adding one ({@code app/cbl/COTRN02C.cbl:L444-L449}), which is not atomic;
     * the parity-preserving translation keeps that algorithm and lets the primary key detect a collision,
     * which is legacy file status {@code '22'}. Keeping that algorithm rather than substituting a sequence
     * is what preserves the generated values, including the first-identifier-is-one case.</p>
     *
     * <p>{@code 409} rather than {@code 500}, and the distinction matters operationally: the request is
     * <em>retryable</em> and will normally succeed on the next attempt, because by then the committed row is
     * visible to the descending browse and the following identifier is generated. A {@code 500} would tell a
     * client to stop; a {@code 409} tells it to try again.</p>
     *
     * <p>The logical file is carried as the detail context when the throwing site named one. <strong>The
     * colliding key is not surfaced and not logged</strong>: it is the primary key of a financial record.</p>
     *
     * @param collision the duplicate-key failure; never null when the framework dispatches here.
     * @return {@code 409 Conflict} carrying a problem detail, the stable error code and the correlation
     *         identifier
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(final DuplicateRecordException collision) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle(DUPLICATE_PROBLEM_TITLE);

        problem.setDetail(DUPLICATE_PROBLEM_DETAIL);

        LOG.warn("Answered transaction {} with 409: the generated identifier collided on logical file {}. "
                + "This is the retained descending-browse race of app/cbl/COTRN02C.cbl:L444-L449 and the "
                + "request is retryable", ADD_TRANSACTION_ID, collision.getLogicalFile());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withPublicEnvelope(problem, ERROR_CODE_DUPLICATE));
    }

    /**
     * Maps an unopenable resource onto {@code 503 Service Unavailable}.
     *
     * <p>The translation of legacy file status {@code '35'}. Distinct from a failed access against an open
     * resource, and the distinction is worth a status of its own: a store that cannot be opened is an
     * environment condition a retry may resolve, whereas an access that failed against an open store is not.
     * The two corresponded to different file statuses in the source and the migration keeps them as different
     * exception types precisely so they need not be collapsed here.</p>
     *
     * <p>The detail is the exception's message and the named resource is carried as context when the throwing
     * site identified one, so an operator learns what to look at without the cause reaching the response.</p>
     *
     * @param unavailable the unavailable-resource failure; never null when the framework dispatches here.
     * @return {@code 503 Service Unavailable} carrying the fixed detail, the stable error code and the
     *         correlation identifier; the resource name is logged rather than returned
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(STORE_PROBLEM_TITLE);

        // The resource name is logged, not returned.
        LOG.error("Answered a transaction request with 503: resource {} could not be opened, the translation "
                + "of file status '35'", unavailable.resourceName().orElse(null), unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNAVAILABLE));
    }

    /**
     * Maps a failed access onto {@code 502 Bad Gateway}.
     *
     * <p>The translation of the legacy {@code '9x'} file-status family, a physical or logical I/O failure
     * against the {@code TRANSACT} store. {@code 502} rather than {@code 500} because the failure is in a
     * downstream dependency this operation is a gateway to, not in the operation itself.</p>
     *
     * <p>The four-character expanded status is carried as a property, which is the response-side counterpart
     * of the source's own four-character status rendering: when a status is non-numeric or begins with
     * {@code '9'} the first byte is copied through and the remainder expanded to three digits, and otherwise
     * the field is four zeros with the two status characters in the last two positions. The exception
     * produces that rendering; this method relays it rather than reformatting it. The cause is logged at
     * {@code ERROR} and never returned, which is the counterpart of the source displaying response and reason
     * codes to the operator's log rather than to the terminal.</p>
     *
     * @param failure the access failure; never null when the framework dispatches here.
     * @return {@code 502 Bad Gateway} carrying the fixed detail, the stable error code and the correlation
     *         identifier; the expanded file status, the logical file and the operation are logged rather than
     *         returned
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, IO_PROBLEM_DETAIL);
        problem.setTitle(STORE_PROBLEM_TITLE);

        // The expanded status, the logical file and the operation are logged, not returned.
        LOG.error("Answered a transaction request with 502: status {} logical file {} operation {}",
                failure.getExpandedStatus(), failure.getLogicalFileName(), failure.getOperation(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(withPublicEnvelope(problem, ERROR_CODE_IO_FAILURE));
    }

    /**
     * Maps a fatal failure onto {@code 500 Internal Server Error}.
     *
     * <p>The translation of the source's abend path. The abend code, which is {@code 999} exactly as the
     * corpus's abend routine moves it, and the batch return code {@code 12} are <strong>logged rather than
     * returned</strong>. Neither is invented and both remain the identifiers an operator correlates a failure
     * by - but an operator reads them on the log, and what the caller correlates by is the correlation
     * identifier in the body, which is what joins the two without publishing an internal termination code.</p>
     *
     * <p>The detail is a fixed string rather than the exception's message, and the cause is logged rather
     * than returned. That is deliberate: an abend message can restate internal state, and this is the one
     * status whose cause is by definition not something the caller can act on.</p>
     *
     * @param abend the fatal failure; never null when the framework dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and the
     *         correlation identifier; the abend code and the batch return code are logged rather than
     *         returned
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        // Logged, not returned: 999 and 12 are internals of the terminating path.
        LOG.error("A transaction request abended: code {} returnCode {} culprit {} reason {}",
                abend.getAbendCode() == null ? ABEND_CODE : abend.getAbendCode(),
                FatalProcessingException.BATCH_RETURN_CODE, abend.getAbendCulprit(), abend.getAbendReason(),
                abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps every remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>The framework resolves the most specific handler, so a refusal, an absent record, a collision, an
     * unopenable store, a failed access and an abend all reach their own method above and never arrive here.
     * This method covers the rest of the nine-type hierarchy - the concurrency and integrity members - each
     * of which stands for a legacy file status or response code that these three operations have no more
     * specific answer for. The detail is the same fixed string used for an abend, for the same reason, and
     * the cause is logged rather than returned.</p>
     *
     * <p>Its existence is what guarantees no typed failure escapes untranslated, which is the boundary
     * counterpart of the source's universal I/O guard: every status is either recognised or abends, and
     * nothing is silently ignored.</p>
     *
     * @param failure the remaining typed failure; never null when the framework dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and the
     *         correlation identifier
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("A transaction request failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Delegates to the transaction-list service exactly once and returns its screen result.
     *
     * <p>Extracted from {@link #listTransactions} so that the handler reads as a sequence of decisions and
     * the abend translation is stated once. The selection vector and the displayed-identifier vector are
     * passed empty rather than taken from the caller: row selection has no wire form on a stateless surface,
     * because the transfer a marked row triggered is now a different URL, and there is no previously
     * displayed screen for the server to reason about.</p>
     *
     * <p>Both catch clauses are load bearing and neither swallows anything. {@code CardDemoException} is
     * rethrown unchanged so that the declared status mapping applies to it, and because that type extends
     * {@code RuntimeException} the first clause is what stops the second from re-wrapping an already-typed
     * failure. Every other runtime failure becomes an abend carrying the four legacy abend fields
     * (code, culprit, reason and message), <strong>with the original throwable preserved as the
     * cause</strong>, so no root cause is ever lost.</p>
     *
     * @param action the paging action the caller selected; never null here.
     * @param searchTransactionId the search key as received, which may be null or blank; relayed verbatim.
     * @param state the browse position rebuilt from the request; never null here.
     * @return the service's screen result, never null
     * @throws FatalProcessingException when the browse fails for any reason other than a typed CardDemo
     *                                  failure
     */
    private TransactionListService.TransactionListScreen retrieveTransactionPage(
            final TransactionListAction action, final String searchTransactionId,
            final TransactionListService.TransactionListState state) {

        try {
            return transactionListService.submitScreen(action.attentionIdentifier(), searchTransactionId,
                    List.of(), List.of(), state);
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, LIST_PROGRAM, LIST_ABEND_REASON,
                    LIST_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Delegates to the transaction-detail service exactly once and returns its screen result.
     *
     * <p>The {@code viewTransaction} entry point is chosen deliberately over the key-press entry point: this
     * operation <em>is</em> the enter-key lookup of {@code CT01}, so the action is expressed by the method and
     * the URL. Accepting an attention identifier here would re-implement the source's key dispatch as public
     * API, and there is no generic action parameter on this operation.</p>
     *
     * <p>The two catch clauses behave exactly as described on {@link #retrieveTransactionPage}: a typed
     * failure is rethrown unchanged so its status mapping applies, and anything else becomes an abend with the
     * original throwable preserved as the cause.</p>
     *
     * @param transactionId the identifier to look up; non-null and non-blank here, because the handler
     *                      refuses otherwise before calling.
     * @return the service's screen result, never null
     * @throws FatalProcessingException when the read fails for any reason other than a typed CardDemo failure
     */
    private TransactionDetailService.TransactionDetailScreen retrieveTransactionDetail(
            final String transactionId) {

        try {
            return transactionDetailService.viewTransaction(transactionId);
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, DETAIL_PROGRAM, DETAIL_ABEND_REASON,
                    DETAIL_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Delegates to the transaction-add service exactly once and returns its result.
     *
     * <p>The {@code addTransaction} entry point is chosen for the same reason the detail operation chooses
     * its enter-key entry point: this operation is the enter-key path of {@code CT02}, expressed by the HTTP
     * method and the URL rather than by a parameter. No origin program is supplied, because the enter path
     * never reaches the branch that reads one.</p>
     *
     * <p>The two catch clauses behave exactly as described on {@link #retrieveTransactionPage}. Note what the
     * first clause protects in particular here: a duplicate-record failure from the retained identifier race
     * is a typed failure and must reach {@link #handleDuplicateRecord} as a {@code 409}, so re-wrapping it as
     * an abend would both lose the retryability signal and misreport a normal outcome as {@code 500}.</p>
     *
     * @param request the twenty-one symbolic-map input fields to hand to the service; never null here.
     * @return the service's result, never null
     * @throws FatalProcessingException when the write fails for any reason other than a typed CardDemo
     *                                  failure
     */
    private TransactionAddService.TransactionAddResult createTransaction(
            final TransactionAddRequest request) {

        try {
            return transactionAddService.addTransaction(request);
        } catch (final CardDemoException typed) {
            throw typed;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, ADD_PROGRAM, ADD_ABEND_REASON, ADD_ABEND_MESSAGE,
                    unexpected);
        }
    }

    /**
     * Builds the {@code Location} header value addressing a newly added transaction.
     *
     * <p>Static and side-effect free. The identifier is placed as a query parameter because the detail
     * operation takes it as one, which is itself a consequence of the symbolic map declaring
     * {@code TRNIDINI} as a sixteen-character text field rather than a number.</p>
     *
     * <p>A null identifier is handled explicitly rather than allowed to render as the four characters
     * {@code null}: the service documents the identifier as populated only on the written outcome, and this
     * method is called only on that outcome, but the guard costs nothing and turns a would-be malformed header
     * into an honest one that addresses the collection.</p>
     *
     * @param transactionId the generated identifier, or null if the service reported none.
     * @return the location of the new transaction, or of the collection when no identifier was reported;
     *         never null
     */
    private static String detailLocationOf(final String transactionId) {

        if (transactionId == null) {
            return BASE_PATH;
        }
        return BASE_PATH + DETAIL_PATH + "?" + TRANSACTION_ID_FIELD + "=" + transactionId;
    }

    /**
     * Refuses a page number outside the domain of the source's own page counter.
     *
     * <p>{@code CDEMO-CT00-PAGE-NUM} is declared {@code PIC 9(08)} at {@code app/cbl/COTRN00C.cbl:L65} -
     * unsigned, eight digits - so its domain is zero through 99999999 and nothing else is expressible in it.
     * A value outside that range is a client error rather than an empty page, and saying so at the boundary
     * is what stops it being mistaken for one.</p>
     *
     * <p>Zero is accepted, and that is not laxity: {@code TransactionListState} reports an unstarted browse as
     * page zero, so a client echoing back the metadata it was given must not be refused for it.</p>
     *
     * <p>Static and side-effect free.</p>
     *
     * @param page the page number as bound from the request.
     * @throws ValidationException if the page number lies outside the eight-digit domain of the source
     *                             counter
     */
    private static void requirePageWithinBrowseDomain(final int page) {

        if (page < LOWEST_PAGE_NUMBER || page > HIGHEST_PAGE_NUMBER) {
            throw ValidationException.invalidField(PAGE_FIELD,
                    "page must be between " + LOWEST_PAGE_NUMBER + " and " + HIGHEST_PAGE_NUMBER
                            + " inclusive, the domain of the eight-digit page counter the transaction list "
                            + "browse maintains");
        }
    }

    /**
     * Resolves the enumerated navigation action from its raw token, accepting nothing but an exact match.
     *
     * <p>The framework's default enum binding trims its input, so {@code " SUBMIT "} would bind as
     * {@code SUBMIT}. That is coercion of a control token: this value selects which of the three
     * data-bearing dispatch arms of {@code app/cbl/COTRN00C.cbl} runs, so accepting a spelling the operation
     * never declared is accepting an instruction it never declared. Comparison is by
     * {@link String#equals(Object)} against the constant names, with no trim and no case fold.</p>
     *
     * @param token the raw request-parameter value, or null when the parameter was absent - which defaults to
     * {@link TransactionListAction#SUBMIT}, so a bare request returns the first page exactly as before.
     * @return the resolved action, never null
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is not one of the three declared tokens. Neither message repeats the
     * rejected value
     */
    private static TransactionListAction resolveAction(final String token) {

        if (token == null) {
            return TransactionListAction.SUBMIT;
        }
        if (token.isEmpty()) {
            throw ValidationException.missingField(ACTION_FIELD,
                    "action must name one of the three declared navigation tokens when the parameter is"
                            + " present at all");
        }
        for (final TransactionListAction candidate : TransactionListAction.values()) {
            if (candidate.name().equals(token)) {
                return candidate;
            }
        }
        throw ValidationException.invalidField(ACTION_FIELD,
                "action must be exactly one of the three declared navigation tokens; no alias, no padding"
                        + " and no other case is accepted");
    }

    /**
     * Resolves the page counter from its raw token, accepting ASCII digits and nothing else.
     *
     * <p>The framework's default {@code int} binding accepts a leading sign and surrounding whitespace, so
     * {@code " +3 "} would bind as three. {@code CDEMO-CT00-PAGE-NUM} is {@code PIC 9(08)} - unsigned - and
     * the COBOL {@code IS NUMERIC} class test that guards every such field admits only the characters zero
     * through nine. This reproduces that test rather than the framework's. The digit range is written out
     * rather than delegated to {@link Character#isDigit(char)} on purpose: that method is true for every
     * decimal digit in Unicode, so a guard written with it would admit an Arabic-Indic digit string that no
     * 3270 terminal could have sent.</p>
     *
     * @param token the raw request-parameter value, or null when the parameter was absent - which defaults to
     * zero, the counter's own value on a first entry.
     * @return the page counter
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is not a run of at most eight ASCII digits
     */
    private static int requirePageToken(final String token) {

        if (token == null) {
            return LOWEST_PAGE_NUMBER;
        }
        if (token.isEmpty()) {
            throw ValidationException.missingField(PAGE_FIELD,
                    "page must be supplied as digits when the parameter is present at all");
        }
        for (int index = 0; index < token.length(); index++) {
            final char character = token.charAt(index);
            if (character < '0' || character > '9') {
                throw ValidationException.invalidField(PAGE_FIELD,
                        "page must consist of ASCII digits only, the domain of the COBOL IS NUMERIC class"
                                + " test that guards the eight-digit page counter");
            }
        }
        if (token.length() > MAXIMUM_PAGE_TOKEN_DIGITS) {
            throw ValidationException.invalidField(PAGE_FIELD,
                    "page must be at most " + MAXIMUM_PAGE_TOKEN_DIGITS + " digits; a longer value was never"
                            + " representable in the page counter the transaction list browse maintains");
        }
        return Integer.parseInt(token);
    }

    /**
     * Resolves the page-state flag from its raw token, accepting only {@code true} and {@code false}.
     *
     * <p>The framework's default {@code boolean} binding additionally accepts {@code on}, {@code off},
     * {@code yes}, {@code no}, {@code 1} and {@code 0}. This flag decides whether a page-forward is
     * attempted at all, so admitting six aliases for two values admits five spellings of an instruction the
     * operation never declared.</p>
     *
     * @param token the raw request-parameter value, or null when the parameter was absent - which defaults to
     * false, which is what a first request means.
     * @return the flag
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is neither exact token
     */
    private static boolean requireBooleanToken(final String token) {

        if (token == null) {
            return false;
        }
        if (token.isEmpty()) {
            throw ValidationException.missingField(NEXT_PAGE_FIELD,
                    "nextPageAvailable must be supplied as " + TRUE_TOKEN + " or " + FALSE_TOKEN
                            + " when the parameter is present at all");
        }
        if (TRUE_TOKEN.equals(token)) {
            return true;
        }
        if (FALSE_TOKEN.equals(token)) {
            return false;
        }
        throw ValidationException.invalidField(NEXT_PAGE_FIELD,
                "nextPageAvailable accepts exactly " + TRUE_TOKEN + " and " + FALSE_TOKEN
                        + "; no alias and no other spelling is accepted");
    }

    /**
     * Refuses an absent or blank transaction identifier on the detail operation.
     *
     * <p>The source refuses a blank {@code TRNIDINI} before it reads anything, so an unusable identifier is a
     * refusal and never a lookup that returns nothing. Reproducing that ordering here is what keeps
     * {@code 400} and {@code 404} meaning different things on this operation.</p>
     *
     * <p><strong>Absent and blank are answered separately, and neither is coerced into the other.</strong> A
     * missing parameter is reported with failure kind {@code BLANK} through {@code missingField}, a supplied
     * but whitespace-only value with {@code INVALID} through {@code invalidField}. That is the three-state
     * model - satisfied, unsatisfied and blank - that {@code app/cpy/CSSETATY.cpy} imposes through its
     * parameterised template, and collapsing the two would tell a client "you sent nothing" when it had in
     * fact sent something wrong.</p>
     *
     * <p>{@code isBlank} is used rather than {@code trim().isEmpty()} so that every Unicode whitespace form is
     * treated alike, and no locale is consulted, so the outcome cannot vary with the default locale. Static
     * and side-effect free.</p>
     *
     * @param transactionId the identifier as bound from the request, which may be null.
     * @throws ValidationException if the identifier is absent or blank
     */
    private static void requireTransactionIdSupplied(final String transactionId) {

        if (transactionId == null) {
            throw ValidationException.missingField(TRANSACTION_ID_FIELD, TRANSACTION_ID_REQUIRED_MESSAGE);
        }
        if (transactionId.isBlank()) {
            throw ValidationException.invalidField(TRANSACTION_ID_FIELD, TRANSACTION_ID_REQUIRED_MESSAGE);
        }
    }

    /**
     * Reports whether a request carries no usable identity.
     *
     * <p>Three conditions are treated alike: a null principal, which is what a permissive filter chain leaves
     * behind; a principal reporting itself unauthenticated; and an anonymous token, which reports itself
     * <em>authenticated</em> and would therefore slip past a bare authentication test. Keeping the three
     * together is what makes {@code 401} mean "bring a token" rather than something vaguer.</p>
     *
     * <p>Static and side-effect free, so it is trivially testable and cannot participate in any state. It
     * reads nothing but the principal - never a request body, never a query parameter and never a session -
     * which is the stateless replacement for the identity the communication area used to carry. All three
     * operations require a token; only sign-on is unauthenticated. Authentication is enforced centrally in
     * {@code SecurityConfig}, so this method neither repeats nor contradicts it: it is the local answer to
     * "what if a chain let an anonymous request through", and it fails closed.</p>
     *
     * @param authentication the principal the security filter chain resolved, which may be null.
     * @return true when the request carries no usable identity
     */
    private static boolean isUnauthenticated(final Authentication authentication) {
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * The paging actions the transaction list accepts, and the whole of this controller's action mapping.
     *
     * <p>Each constant is one data-bearing arm of the {@code EVALUATE EIBAID} dispatch at
     * {@code app/cbl/COTRN00C.cbl:L119-L134}, named for what it does to the data rather than for the 3270 key
     * that used to trigger it. That naming is the point: {@code app/cpy/CSSTRPFY.cpy} is procedural - it is
     * copied into a {@code PROCEDURE DIVISION} and maps {@code EIBAID} onto condition names - so it has no
     * data counterpart to translate, and its Java equivalent is controller-level action mapping rather than a
     * reimplemented function-key dispatcher. There is no generic attention-identifier parameter anywhere on
     * this controller.</p>
     *
     * <p>Three constants rather than five, and the two absences are structural. {@code WHEN DFHPF3} at
     * {@code :L122} transferred control to the main menu, which a client now expresses by requesting a
     * different URL; representing it here would make navigation a property of a data request.
     * {@code WHEN OTHER} at {@code :L129} raised the invalid-key message, and it is
     * <strong>unreachable by construction</strong> once the only accepted inputs are three named constants -
     * an unrecognised value is refused during binding and never reaches a handler. Neither is a retained no-op
     * and neither needs one: no dead artefact lives in this file.</p>
     *
     * <p>Bound by name from the {@code action} request parameter through the framework's built-in string to
     * enumeration conversion, so no converter is registered for it and none is needed.</p>
     *
     * <p>Declared nested because it has no meaning outside this controller's contract, and because the file
     * budget for this migration unit is one file.</p>
     */
    public enum TransactionListAction {

        /**
         * Show the page addressed by the supplied cursor. The transcription of {@code WHEN DFHENTER} at
         * {@code app/cbl/COTRN00C.cbl:L120}, which performs the enter-key paragraph and re-evaluates the
         * search key. This is the default, so a client that supplies no action gets the first page of a fresh
         * browse.
         */
        SUBMIT(TransactionListService.AttentionIdentifier.ENTER),

        /**
         * Page backward. The transcription of {@code WHEN DFHPF7} at {@code app/cbl/COTRN00C.cbl:L125}, which
         * restarts the browse from the saved first key and reads backwards. On the first page the source
         * reports being at the top of the list and redisplays the same page, and so does this action - that
         * message is informational, so it does not become a failure status.
         */
        PAGE_BACKWARD(TransactionListService.AttentionIdentifier.PF7),

        /**
         * Page forward. The transcription of {@code WHEN DFHPF8} at {@code app/cbl/COTRN00C.cbl:L127}, which
         * restarts the browse from the saved last key and reads forwards. With no further record the source
         * reports being at the bottom of the list at {@code :L267-L272} and returns the same page, and so does
         * this action.
         */
        PAGE_FORWARD(TransactionListService.AttentionIdentifier.PF8);

        /** The attention identifier this action presents to the transaction-list service. */
        private final TransactionListService.AttentionIdentifier attentionIdentifier;

        /**
         * Binds a constant to the attention identifier the service dispatches on.
         *
         * @param dispatchedIdentifier the service's own typed attention identifier for this action; never
         *                             null.
         */
        TransactionListAction(final TransactionListService.AttentionIdentifier dispatchedIdentifier) {
            this.attentionIdentifier = dispatchedIdentifier;
        }

        /**
         * Returns the attention identifier the transaction-list service dispatches on for this action.
         *
         * <p>Typed rather than a string token, so an action that does not correspond to a real dispatch arm
         * cannot be constructed and no token spelling can be got wrong.</p>
         *
         * @return the service's attention identifier; never null
         */
        public TransactionListService.AttentionIdentifier attentionIdentifier() {
            return this.attentionIdentifier;
        }
    }

    /**
     * Stamps the two properties every error body carries, and returns the same instance for chaining.
     *
     * <p>Called by each {@code @ExceptionHandler} above as the last thing it does to the body, so a future
     * handler cannot omit the envelope by accident: the {@code return} statement reads
     * {@code body(withPublicEnvelope(problem, ...))}, and a handler written without it does not compile
     * into that shape.
     *
     * <p><strong>This is the whole of the {@code CWE-209} posture.</strong> What the body carries is the
     * status, the title, a detail that is either a legacy screen literal or a fixed sentence, the error code
     * and the correlation identifier. What it no longer carries is the relation, the constraint name, the
     * logical file or dataset name, the input-output operation, the expanded file status, the record type,
     * the abend code and the batch return code. Every one of those is still emitted - at {@code WARN} or
     * {@code ERROR}, on a log stream the caller cannot read - so no diagnostic capability is lost and
     * nothing is swallowed.
     *
     * @param problem the body under construction; must not be null.
     * @param errorCode one of the {@code ERROR_CODE_*} constants.
     * @return {@code problem}, so the call can be inlined into the {@code body(...)} argument
     */
    private static ProblemDetail withPublicEnvelope(final ProblemDetail problem, final String errorCode) {

        problem.setProperty(ERROR_CODE_PROPERTY, errorCode);

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
        problem.setProperty(CORRELATION_ID_PROPERTY,
                correlationId == null || correlationId.isEmpty() ? CORRELATION_ID_UNAVAILABLE : correlationId);

        return problem;
    }
}
