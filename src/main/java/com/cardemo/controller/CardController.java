/*
 * ******************************************************************
 * Program     : CardController.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 REST Controller
 * Function    : Card list (7 rows per page), card detail and card update endpoints.
 * Source      : app/csd/CARDDEMO.CSD transactions CCLI, CCDL, CCUP
 *               -> app/cbl/COCRDLIC.cbl (1,459 lines), mapset COCRDLI
 *               -> app/cbl/COCRDSLC.cbl (887 lines), mapset COCRDSL
 *               -> app/cbl/COCRDUPC.cbl (1,560 lines), mapset COCRDUP @ 7756d89
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

import java.util.Collection;
import java.util.List;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.config.WebConfig;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardListResponse;
import com.cardemo.model.dto.CardResponse;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;

/**
 * The card resource group: the three CICS card transactions reached over HTTP as three operations.
 *
 * <h2>What it does</h2>
 *
 * <p>Replaces three CICS transactions defined in {@code app/csd/CARDDEMO.CSD}, each with a program and a
 * mapset, and adds nothing beyond them:</p>
 *
 * <ul>
 *   <li>{@code CCLI} at {@code app/csd/CARDDEMO.CSD:L357} fronting {@code PROGRAM(COCRDLIC)} at
 *       {@code :L358} - {@code app/cbl/COCRDLIC.cbl}, 1,459 lines and 39 own paragraph labels, painting
 *       mapset {@code COCRDLI}. Reached by {@link #listCards}.</li>
 *   <li>{@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347} fronting {@code PROGRAM(COCRDSLC)} at
 *       {@code :L348} - {@code app/cbl/COCRDSLC.cbl}, 887 lines and 34 own paragraph labels, painting mapset
 *       {@code COCRDSL}. Reached by {@link #getCardDetail}.</li>
 *   <li>{@code CCUP} at {@code app/csd/CARDDEMO.CSD:L367}, described
 *       {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} at {@code :L368}, fronting
 *       {@code PROGRAM(COCRDUPC)} at {@code :L369} - {@code app/cbl/COCRDUPC.cbl}, 1,560 lines and 48
 *       paragraph labels, painting mapset {@code COCRDUP}. Reached by {@link #updateCard}.</li>
 *   </ul>
 *
 * <p><strong>{@code CCDL} fronts {@code COCRDSLC}, the card <em>select</em> program, and nothing else.</strong>
 * The name is one letter away from {@code COCRDSEC}, and the two are trivially confused, so the
 * distinction is recorded here rather than left to inference. {@code COCRDSEC} occurs at exactly two
 * places repository-wide - {@code app/csd/CARDDEMO.CSD:L211} and {@code :L390} - and both are the CSD
 * definition itself; {@code DEFINE TRANSACTION(CDV1)} spans {@code :L388-L391} and is described
 * "DEVELOPER TRANSACTION - 1". Its source member is <b>Not available</b>: there is no
 * {@code COCRDSEC} source file anywhere in the repository, and what would be needed to close that gap is
 * the source member itself, which is absent at every commit in this checkout. No endpoint is therefore
 * invented for {@code CDV1} and none appears below.</p>
 *
 * <p>Both card operations read the {@code CARDDAT} cluster declared at {@code app/csd/CARDDEMO.CSD:L25};
 * its alternate index {@code CARDAIX} at {@code :L13} backs the by-account finder. This class performs no
 * business logic of any kind. Each operation validates what HTTP itself cannot express, delegates once to
 * the service that owns the transcribed paragraph map, and translates the typed failure hierarchy into a
 * status code. There is no arithmetic here, no repository, no {@code EntityManager} and no
 * {@code JdbcTemplate}.</p>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and unit-test with {@code ./mvnw -B -ntp test}; the full gate is
 * {@code ./mvnw -B -ntp clean verify}, which compiles under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} set, so a single warning in this file fails the build. Run the application with
 * {@code java -jar target/*.jar}, or bring the whole topology up with {@code docker compose up -d} first
 * so that PostgreSQL is reachable. Tests for these operations live under
 * {@code src/test/java/com/cardemo/unit} and {@code src/test/java/com/cardemo/e2e} and never in this
 * package; the contract gate drives all three operations through {@code MockMvc} against a real
 * application context, which this class supports because it holds no static or per-request state.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This class reads no property and declares none. The one configuration value the card list depends on
 * is the screen depth, {@code carddemo.pagination.card-list-page-size}, and it is bound by
 * {@code CardListService} rather than here - that service rejects at startup any value disagreeing with
 * the physical row count of mapset {@code COCRDLI}. No page-size literal appears anywhere in this file
 * and no client may supply one; the value in force is reported back on every page through
 * {@code CardListResponse.pageSize()}. Authentication and authorisation are configured centrally in
 * {@code SecurityConfig}, request-parameter conversion in {@code WebConfig}, JSON in the Jackson
 * configuration under {@code src/main/resources}, and log masking in {@code logback-spring.xml}; this
 * class overrides none of them.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every failure below is produced by a service and mapped to a status by an
 * {@code @ExceptionHandler} declared on this class. There is no {@code @ControllerAdvice} and no advice
 * type anywhere in this repository, and the nine exception types carry no annotations at all - in
 * particular no {@code @ResponseStatus} - because status selection is contextual and belongs to the
 * endpoint rather than to the exception.</p>
 *
 * <table border="1">
 *   <caption>Failure to status mapping for this resource group</caption>
 *   <tr><th>Condition</th><th>Type</th><th>Status</th></tr>
 *   <tr><td>A filter or field was rejected</td><td>{@code ValidationException}</td><td>400</td></tr>
 *   <tr><td>No such card or account</td><td>{@code RecordNotFoundException}</td><td>404</td></tr>
 *   <tr><td>Update lost a race</td><td>{@code ConcurrentUpdateException}</td><td>409</td></tr>
 *   <tr><td>As-displayed snapshot absent</td><td>{@code ConcurrentUpdateException}</td><td>428</td></tr>
 *   <tr><td>As-displayed snapshot unverifiable or stale</td><td>{@code ConcurrentUpdateException}</td>
 *       <td>412</td></tr>
 *   <tr><td>File not open, status {@code '35'}</td><td>{@code FileUnavailableException}</td><td>503</td></tr>
 *   <tr><td>I/O failure, the {@code '9x'} family</td><td>{@code FileAccessException}</td><td>502</td></tr>
 *   <tr><td>Unexpected status, abend 999</td><td>{@code FatalProcessingException}</td><td>500</td></tr>
 * </table>
 *
 * <p>To troubleshoot, start from the {@code correlationId} on the response and the matching log line: the
 * filter that supplies it also supplies {@code traceId} and {@code spanId}, and this class neither adds
 * to nor removes from that context. A 400 carries the rejected field name and the two-state failure kind,
 * which is what distinguishes a blank filter from an invalid one; a 409 carries the concurrency outcome;
 * a 502 from an I/O failure carries the four-character expanded file status. No response and no log line
 * ever carries a card number, a cardholder name or any other protected field - the diagnostics below name
 * fields, operations and files only, never their values, and every card number a response does carry is
 * tail-masked before it is written.</p>
 *
 * <p>Two boundary conditions are worth stating because they are answers rather than faults. A page beyond
 * the last one, and an empty result set, both return {@code 200} with an empty row list and
 * {@code nextPageAvailable} false; the source behaves the same way, reporting "no more pages" as a screen
 * message rather than as an error. And a total element count and a total page count are <b>Not
 * available</b> by design: {@code CardListResponse} declares neither, because the source never computes
 * either - its browse learns only whether one further record exists. What would be needed to supply them
 * is a counting query the legacy program never issues, which would be new behaviour rather than parity.</p>
 *
 * <h2>Findings and deviations, with severities</h2>
 *
 * <p>Every finding this class carries is classified and tracked; each is also held in the
 * {@code DECISION_LOG.md} under the entry named at the end of its item. Nothing in this list is a silent
 * substitution, and nothing in it is a defect left unstated.</p>
 *
 * <ul>
 *   <li><strong>Blocker - {@code CCDL} fronts {@code COCRDSLC}, and binding it to {@code COCRDSEC}
 *       instead would attach this endpoint to a program that does not exist.</strong> The two names are
 *       one letter apart. {@code COCRDSEC} occurs at exactly two places repository-wide,
 *       {@code app/csd/CARDDEMO.CSD:L211} and {@code :L390}, both of them the definition itself, and its
 *       source member is <b>Not available</b>. Remediation, applied: the detail operation binds to
 *       {@code CardDetailService}, the transcription of {@code app/cbl/COCRDSLC.cbl}, and no endpoint is
 *       invented for {@code CDV1}. Decision log entry: <em>CDV1 has no target</em>.</li>
 *   <li><strong>High - the affected-record reference on a concurrency failure holds a masked card
 *       number rather than the dataset name it is documented for.</strong> Its accessor is documented as
 *       returning a dataset name, "typically {@code ACCTDAT} or {@code CUSTDAT}", but the card-update
 *       service supplies a tail-masked card number there instead. Remediation, applied on this side:
 *       this class never reads that reference, so it reaches neither a response body nor a log line;
 *       the concurrency outcome is the discriminator instead. The service itself is outside this file's
 *       scope, so the divergence is recorded rather than edited. Decision log entry:
 *       <em>affected record not surfaced</em>.</li>
 *   <li><strong>Medium - the next-page sentinel is not transportable, because the corpus has two
 *       incompatible ones.</strong> {@code app/cbl/COCRDLIC.cbl:L243} tests {@code LOW-VALUES} for
 *       "no further page" while {@code app/cbl/COTRN00C.cbl} uses the character {@code 'N'} for the same
 *       concept. Remediation, applied: neither byte travels. {@code PageResponse} carries a
 *       boolean-equivalent metadata member, and the client echoes that back rather than a sentinel.
 *       Decision log entry: <em>next page indicator normalised</em>.</li>
 *   <li><strong>Medium - the last-page latch does not round-trip.</strong>
 *       {@code WS-CA-LAST-PAGE-DISPLAYED} at {@code app/cbl/COCRDLIC.cbl:L239-L241} chooses between two
 *       screen messages at {@code :L905-L916}, and {@code PageResponse} declares no member able to carry
 *       it, so its value across turns is <b>Not available</b>. What would be needed is a metadata member
 *       for terminal-message state, which does not belong on a JSON contract. Remediation, applied: the
 *       neutral "not yet shown" value is sent every turn, so a client that pages past the end receives
 *       the informational message rather than the terminal one - the only observable consequence, and it
 *       changes no row and no key. Decision log entry: <em>last page latch not transported</em>.</li>
 *   <li><strong>Low - no total element count and no total page count.</strong> The browse learns only
 *       whether one further record exists; it never counts. Both are therefore <b>Not available</b>
 *       rather than invented, and supplying them would need a counting query the legacy program never
 *       issues. Decision log entry: <em>page metadata without totals</em>.</li>
 *   <li><strong>Low - the administrator flag is carried into the card list and never tested by it.</strong>
 *       {@code app/cbl/COCRDLIC.cbl} stores {@code CDEMO-USRTYP-ADMIN} into its commarea, at
 *       {@code :L1013} and {@code :L1040}, and no paragraph reads it for a decision. Remediation, applied:
 *       it is still derived and still passed, from the role authority alone, because dropping it would
 *       diverge from the commarea the source maintains - but it changes no row, no attribute and no
 *       message on this transaction, and it appears in no response. Decision log entry:
 *       <em>user type carried not tested</em>.</li>
 *   </ul>
 *
 * <h2>State and thread safety</h2>
 *
 * <p>Three final collaborators, assigned once by the constructor, and no other field: no static mutable
 * state, no instance mutable state, no per-request field and no session. That is what transformation rule
 * 7 requires - {@code EXEC CICS RETURN TRANSID ... COMMAREA} becomes stateless REST plus token claims -
 * and it is why every operation below is safe on any number of concurrent request threads. The paging
 * cursor the source held in {@code WS-COMMAREA} travels as request parameters and response metadata
 * instead. Identity is taken only from the authenticated principal, never from a request body and never
 * from a session.</p>
 */
@RestController
@RequestMapping(CardController.BASE_PATH)
public class CardController {

    /**
     * Diagnostic logger. The three legacy programs have no instrumentation of any kind - their only
     * output is the 3270 screen - so every use of this logger is new capability rather than a
     * transcription. Card numbers, cardholder names and every other protected field are excluded from
     * all of them, which matters more here than anywhere else in the surface because the card number is
     * this resource group's central datum.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardController.class);

    /**
     * The resource-group path. One controller per resource group, so the three card transactions share
     * this prefix and no other controller uses it.
     */
    static final String BASE_PATH = "/api/cards";

    /**
     * The card-detail path, relative to {@value #BASE_PATH}. Deliberately distinct from the collection
     * path so that the list and the detail are separate operations rather than one overloaded operation
     * discriminated by a flag, which is how {@code CCLI} and {@code CCDL} are separate transactions.
     */
    static final String DETAIL_PATH = "/detail";

    /**
     * The authority standing in for {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at
     * {@code app/cpy/COCOM01Y.cpy:L27}. Declared here rather than imported so that this controller
     * depends on no other controller and on nothing outside its own contract.
     */
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** CSD transaction identifier for the card list, {@code app/csd/CARDDEMO.CSD:L357}. */
    private static final String CARD_LIST_TRANSACTION_ID = "CCLI";

    /** CSD transaction identifier for the card detail, {@code app/csd/CARDDEMO.CSD:L347}. */
    private static final String CARD_DETAIL_TRANSACTION_ID = "CCDL";

    /** CSD transaction identifier for the card update, {@code app/csd/CARDDEMO.CSD:L367}. */
    private static final String CARD_UPDATE_TRANSACTION_ID = "CCUP";

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'}, {@code app/cbl/COCRDLIC.cbl:L179-L180}. */
    private static final String CARD_LIST_PROGRAM = "COCRDLIC";

    /** The card-detail program. {@code PROGRAM(COCRDSLC)} at {@code app/csd/CARDDEMO.CSD:L348}. */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** The card-update program. {@code PROGRAM(COCRDUPC)} at {@code app/csd/CARDDEMO.CSD:L369}. */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'}, {@code app/cbl/COCRDLIC.cbl:L183-L184}. */
    private static final String CARD_LIST_MAPSET = "COCRDLI";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'}, {@code app/cbl/COCRDLIC.cbl:L185-L186}. */
    private static final String CARD_LIST_MAP = "CCRDLIA";

    /**
     * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9}, {@code app/cbl/COCRDLIC.cbl:L241}, the companion of
     * {@code 88 CA-LAST-PAGE-SHOWN VALUE 0} at {@code :L240}.
     *
     * <p>The latch exists only to choose between two screen messages on a page-forward that has run out
     * of records, at {@code :L905-L916}. It changes no row and no key, and {@code PageResponse} declares
     * no member able to carry it, so its value across turns is <b>Not available</b> on the wire; what
     * would be needed to round-trip it is a metadata member the response type does not have, and adding
     * one would put terminal-message state on a JSON contract. The neutral "not yet shown" value is sent
     * on every turn, which is exactly what a first entry sends, so the client sees the informational
     * message rather than the terminal one - the only observable consequence.</p>
     */
    private static final int LAST_PAGE_NOT_SHOWN = 9;

    /**
     * The smallest value {@code WS-CA-SCREEN-NUM} can hold, {@code app/cbl/COCRDLIC.cbl:L237}.
     *
     * <p>The field is {@code PIC 9(1)}: unsigned, so zero is its floor. Zero is also its initial value on a
     * first entry, before {@code 9000-READ-FORWARD.} has advanced it, which is why the page parameter
     * defaults to zero rather than to one.</p>
     */
    private static final int LOWEST_SCREEN_NUMBER = 0;

    /**
     * The screen number of the first page, {@code CA-FIRST-PAGE} at {@code app/cbl/COCRDLIC.cbl:L237}.
     *
     * <p>Distinct from {@link #LOWEST_SCREEN_NUMBER}, and the distinction is load bearing: the field's
     * <em>domain</em> starts at zero because {@code WS-CA-SCREEN-NUM} is {@code PIC 9(1)} and wraps, but the
     * first page is one. A request that omits the page parameter therefore resolves to zero, which is a
     * legitimate wrapped page number and is <b>not</b> the first page - which is precisely why a backward
     * request must state the page it is moving back from rather than defaulting.
     */
    private static final int FIRST_SCREEN_NUMBER = 1;

    /** The request-parameter name of the previous-page cursor. */
    private static final String FIRST_KEY_FIELD = "firstKey";

    /** The request-parameter name of the next-page cursor. */
    private static final String LAST_KEY_FIELD = "lastKey";

    /** Separates the browse name from the page number in a page-bound cursor kind. */
    private static final String CURSOR_KIND_PAGE_SEPARATOR = "-page-";

    /** The request-parameter name of the opaque row reference. */
    private static final String CARD_KEY_FIELD = "cardKey";

    /**
     * The seal kind of an opaque row reference.
     *
     * <p>Distinct from the page-cursor kinds, because the kind is authenticated additional data: a reference
     * cannot be presented as a cursor and a cursor cannot be presented as a reference.
     */
    private static final String CARD_HANDLE_KIND = "card-row-reference";

    /**
     * The record key every row reference is sealed under.
     *
     * <p>A constant rather than the row's own identifiers, and deliberately: the reference exists precisely
     * because the caller does <em>not</em> hold those identifiers, so binding the seal to them would demand
     * the value the reference is standing in for. Confidentiality comes from the encryption, integrity from
     * the authentication tag, and scope from the kind above.
     */
    private static final String CARD_HANDLE_RECORD_KEY = "card-row";

    /**
     * The width of the account identifier inside a sealed row reference, {@code ACCTNO PIC X(11)}.
     *
     * <p>The payload is the account identifier followed by the card number, both at their declared widths,
     * so the split offset is a field contract rather than a parsing convention and no separator is needed.
     */
    private static final int HANDLE_ACCOUNT_ID_WIDTH = 11;

    /** The width of the card number inside a sealed row reference, {@code CARDSID PIC X(16)}. */
    private static final int HANDLE_CARD_NUMBER_WIDTH = 16;

    /**
     * The longest page token accepted before the value is parsed at all.
     *
     * <p>Two digits rather than one on purpose: the single-digit domain is enforced by
     * {@code requirePageWithinScreenNumberDomain}, which is where that rule is stated and cited, so
     * refusing a two-digit token here as well would state the same rule twice and leave the range check
     * unreachable for the values it exists to describe. What this bound does is keep the parse from ever
     * seeing an arbitrarily long digit run, so a rejected value is rejected as out of range rather than as
     * an overflow.</p>
     */
    private static final int MAXIMUM_PAGE_TOKEN_DIGITS = 2;

    /**
     * The largest value {@code WS-CA-SCREEN-NUM} can hold, {@code app/cbl/COCRDLIC.cbl:L237}.
     *
     * <p>A single unsigned digit, so nine is its ceiling. The source's own increment wraps at that ceiling
     * rather than overflowing, and the wrap is preserved rather than repaired; what is refused here is a
     * value that was never representable in the field at all.</p>
     */
    private static final int HIGHEST_SCREEN_NUMBER = 9;

    /** The name reported for a page cursor outside the domain the source declares for it. */
    private static final String PAGE_FIELD = "page";

    /** The request-parameter and field name of the page cursor a client echoes back. */
    private static final String NEXT_PAGE_FIELD = "nextPageAvailable";

    /** The two tokens the boolean page-state parameter accepts, and the only two. */
    private static final String TRUE_TOKEN = "true";

    /** The second of them. */
    private static final String FALSE_TOKEN = "false";

    /**
     * The browse this operation's page cursors are sealed for.
     *
     * <p>Sealing is bound to a browse, so a cursor issued by the card list cannot be presented to the
     * transaction list or to any other operation.</p>
     */
    private static final String CARD_LIST_CURSOR_KIND = "card-list-cursor";

    /** The field name reported when a sealed page cursor cannot be opened. */
    private static final String CURSOR_FIELD = "cursor";

    /** Problem-detail title for a rejected card request. */
    private static final String VALIDATION_PROBLEM_TITLE = "Card request rejected";

    /** Problem-detail title for a card record that does not exist. */
    private static final String NOT_FOUND_PROBLEM_TITLE = "Card record not found";

    /** Problem-detail title for a card update that lost a race or was not confirmed. */
    private static final String CONFLICT_PROBLEM_TITLE = "Card update not applied";

    /** Problem-detail title for a card file that is not available. */
    private static final String UNAVAILABLE_PROBLEM_TITLE = "Card file unavailable";

    /** Problem-detail title for any card operation that failed outright. */
    private static final String FAILURE_PROBLEM_TITLE = "Card operation failed";

    /**
     * The fixed detail returned for every 500. It names no cause, which is the same posture as
     * {@code server.error.include-message} set to {@code never}: the cause stays attached to the
     * exception and is logged, so diagnosis proceeds from the correlation identifier.
     */
    private static final String FAILURE_PROBLEM_DETAIL =
            "The request could not be completed. Quote the correlation identifier when reporting this.";

    /** The detail returned when a card file is not open, file status {@code '35'}. */
    private static final String UNAVAILABLE_PROBLEM_DETAIL =
            "The card file is not currently available. Retry once the datastore is reachable.";

    /** Problem-detail title for the {@code FILE STATUS '9x'} family, answered with {@code 502}. */
    private static final String IO_PROBLEM_TITLE = "Card data store input-output failure";

    /**
     * The detail returned for an input-output failure. It attributes the fault to the store rather than to
     * the request, which is what {@code 502} states, and it names no cause: the cause stays attached to the
     * exception and is logged.
     */
    private static final String IO_PROBLEM_DETAIL =
            "The card data store reported an input-output failure. The request was not completed.";

    /** Problem-detail member carrying the rejected field name. */
    private static final String FIELD_PROPERTY = "field";

    /** Problem-detail member carrying {@code INVALID} or {@code BLANK}, which keeps the two distinct. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /** Problem-detail member carrying the concurrency outcome, which keeps the five outcomes distinct. */
    private static final String OUTCOME_PROPERTY = "outcome";

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
     *
     * <p>It is the hinge of the posture the package documentation states: the same value
     * {@code CorrelationIdFilter} placed in the diagnostic context and echoed on the
     * {@code X-Correlation-Id} response header, so support can retrieve the withheld detail from the log
     * while a caller holding only the response body cannot.
     */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /**
     * The value substituted when no correlation identifier is in the diagnostic context.
     *
     * <p>Unreachable in the server, because {@code CorrelationIdFilter} runs at
     * {@code HIGHEST_PRECEDENCE} ahead of every handler here. It exists so that a handler invoked
     * directly by a unit test still produces a body of the same shape.
     */
    private static final String CORRELATION_ID_UNAVAILABLE = "unavailable";

    /**
     * Stable error code meaning that the request was refused by a field-level validation rule.
     */
    private static final String ERROR_CODE_VALIDATION = "CARDDEMO-VALIDATION-REJECTED";

    /**
     * Stable error code meaning that a record the operation needed does not exist.
     */
    private static final String ERROR_CODE_NOT_FOUND = "CARDDEMO-RECORD-NOT-FOUND";

    /**
     * The fixed detail returned when a card or account record does not exist.
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
            "No record was found for the identifier supplied.";

    /**
     * The single detail published when the framework's bean validation refuses the request body.
     *
     * <p>Fixed, and deliberately naming no field. The framework's binding result carries a field error per
     * violation, and each of those retains the <em>submitted value</em> alongside its message - which on the
     * update body includes the embossed name and the card number itself. Withholding the field name as well
     * costs a caller nothing, because the schema that declares the constraint is what the caller wrote the
     * body against, and it buys determinism: Hibernate Validator reports violations from an unordered set, so
     * any single-field or first-field projection would vary between two identical requests. The field names do
     * reach the log line, where they are diagnosable without being disclosed.
     */
    private static final String BIND_FAILURE_PROBLEM_DETAIL =
            "One or more fields of the request body failed validation. Correct the body and resubmit.";

    /**
     * The single detail published when the request body could not be read at all.
     *
     * <p>Covers the three conditions the framework folds into one exception before the update operation is
     * entered: a body that is not well-formed JSON, a body carrying a property outside the schema, and a
     * request with no body where one is required. The parser's own message is not published, because it names
     * the deserialiser's internal stream class, the byte offset it stopped at and, for an unrecognised
     * property, the full list of properties the type accepts - a schema dump handed to a caller.
     */
    private static final String UNREADABLE_BODY_PROBLEM_DETAIL =
            "The request body could not be read as JSON matching this operation's schema.";

    /**
     * Stable error code meaning that the update was not applied because the stored state moved or was not confirmed.
     */
    private static final String ERROR_CODE_UPDATE_CONFLICT = "CARDDEMO-UPDATE-CONFLICT";

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

    /**
     * Stable error code meaning that the request body could not be read as JSON matching the schema.
     *
     * <p>Distinct from {@link #ERROR_CODE_VALIDATION} because the two conditions are distinct: a body that
     * parsed and then failed a rule is not the same as a body that never parsed, and a client that retries
     * automatically needs to tell them apart. The framework-level bean-validation refusal, by contrast,
     * publishes {@link #ERROR_CODE_VALIDATION} - the same code the service-tier refusal publishes - so a
     * caller sees one code per condition rather than one code per layer that happened to catch it.
     */
    private static final String ERROR_CODE_UNREADABLE_BODY = "CARDDEMO-REQUEST-BODY-UNREADABLE";

    /** The abend code as text, for the abend that carries none of its own. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Abend reason for an unexpected card-list failure. */
    private static final String CARD_LIST_ABEND_REASON = "CARD LIST RETRIEVAL FAILED UNEXPECTEDLY";

    /** Abend reason for an unexpected card-detail failure. */
    private static final String CARD_DETAIL_ABEND_REASON = "CARD DETAIL RETRIEVAL FAILED UNEXPECTEDLY";

    /**
     * {@code ABEND-REASON} for a request that reached a snapshot-bearing operation with no usable principal.
     *
     * <p>Both routes are declared authenticated in {@code SecurityConfig}, so this is a broken chain rather
     * than a client mistake, which is why it abends rather than answering {@code 401}: a {@code 401} would
     * tell the caller to authenticate, when in fact they already had.</p>
     */
    private static final String SUBJECT_ABEND_REASON = "NO PRINCIPAL FOR SNAPSHOT BINDING";

    /**
     * {@code ABEND-MSG} for that condition. Names no principal, no route and no token.
     */
    private static final String SUBJECT_ABEND_MESSAGE =
            "A card operation was reached with no authenticated principal, so the as-displayed snapshot could"
                    + " be bound to no subject. The security filter chain declares both card routes"
                    + " authenticated; check that it is installed.";

    /** Abend reason for an unexpected card-update failure. */
    private static final String CARD_UPDATE_ABEND_REASON = "CARD UPDATE FAILED UNEXPECTEDLY";

    /** Abend message for an unexpected card-list failure. */
    private static final String CARD_LIST_ABEND_MESSAGE = "UNEXPECTED ERROR IN CCLI CARD LIST RETRIEVAL.";

    /** Abend message for an unexpected card-detail failure. */
    private static final String CARD_DETAIL_ABEND_MESSAGE =
            "UNEXPECTED ERROR IN CCDL CARD DETAIL RETRIEVAL.";

    /** Abend message for an unexpected card-update failure. */
    private static final String CARD_UPDATE_ABEND_MESSAGE = "UNEXPECTED ERROR IN CCUP CARD UPDATE.";

    /**
     * The card-list service, the transcription of {@code app/cbl/COCRDLIC.cbl}.
     */
    private final CardListService cardListService;

    /**
     * The card-detail service, the transcription of {@code app/cbl/COCRDSLC.cbl}.
     */
    private final CardDetailService cardDetailService;

    /**
     * The card-update service, the transcription of {@code app/cbl/COCRDUPC.cbl}.
     */
    private final CardUpdateService cardUpdateService;

    /**
     * Seals and opens the two page cursors.
     *
     * <p>Needed because on this resource the browse's saved keys <em>are</em> card numbers:
     * {@code app/cbl/COCRDLIC.cbl:L1197-L1205} records the first and last card number of the displayed page
     * so the next turn can reposition from them. Emitting them would publish two primary account numbers per
     * page in the one place a client is most likely to log, cache or bookmark, so the sealed forms travel
     * instead and the browse behaves identically.</p>
     */
    private final SnapshotTokenService snapshotTokenService;

    /**
     * Creates the controller over the three card services.
     *
     * <p>Constructor injection is the only injection form used: there is no field injection, no setter
     * injection and no {@code @Autowired}, so all three collaborators are non-null and final for the
     * lifetime of the bean and the class holds no global mutable state. Each argument is validated rather
     * than trusted, because a null collaborator would otherwise surface as a failure on the first request
     * instead of at context refresh.</p>
     *
     * @param cardListService the card-list service replacing {@code app/cbl/COCRDLIC.cbl}; must not be
     * null.
     * @param cardDetailService the card-detail service replacing {@code app/cbl/COCRDSLC.cbl} - the card
     * select program, not {@code COCRDSEC}; must not be null.
     * @param cardUpdateService the card-update service replacing {@code app/cbl/COCRDUPC.cbl}; must not be
     * null.
     * @param snapshotTokenService the sealer of page cursors, whose plain values are card numbers on this
     * resource; must not be null.
     * @throws IllegalArgumentException if any collaborator is null, which is a bean-wiring defect rather
     * than a request-time condition
     */
    public CardController(final CardListService cardListService,
            final CardDetailService cardDetailService,
            final CardUpdateService cardUpdateService,
            final SnapshotTokenService snapshotTokenService) {

        if (snapshotTokenService == null) {
            throw new IllegalArgumentException("snapshotTokenService must not be null; it is the"
                    + " replacement for the CCUP-OLD-DETAILS snapshot comparison of app/cbl/COCRDUPC.cbl"
                    + " and the WS-CA-SCREEN-NUM browse position of app/cbl/COCRDLIC.cbl, and it is what"
                    + " keeps a card number out of a page cursor and an as-displayed snapshot out of a"
                    + " caller's control");
        }
        if (cardListService == null) {
            throw new IllegalArgumentException(
                    "cardListService must not be null; it is the replacement for app/cbl/COCRDLIC.cbl");
        }
        if (cardDetailService == null) {
            throw new IllegalArgumentException(
                    "cardDetailService must not be null; it is the replacement for app/cbl/COCRDSLC.cbl");
        }
        if (cardUpdateService == null) {
            throw new IllegalArgumentException(
                    "cardUpdateService must not be null; it is the replacement for app/cbl/COCRDUPC.cbl");
        }

        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
        this.snapshotTokenService = snapshotTokenService;
    }

    /**
     * Operation 1 of 3 - one page of cards. Replaces CICS transaction {@code CCLI}
     * ({@code app/csd/CARDDEMO.CSD:L357}) and the program it fronts, {@code app/cbl/COCRDLIC.cbl} (1,459
     * lines, 39 own paragraph labels), which painted mapset {@code COCRDLI}.
     *
     * <p><strong>Purpose.</strong> Returns one screen of card rows, optionally narrowed by an account
     * filter and a card filter, together with the cursor needed to ask for the adjacent screen. It is
     * {@code 0000-MAIN.} at {@code app/cbl/COCRDLIC.cbl:L298-L602} reached as a single stateless request:
     * the browse paragraphs {@code 9000-READ-FORWARD.} at {@code :L1123} and
     * {@code 9100-READ-BACKWARDS.} at {@code :L1264}, the record filter
     * {@code 9500-FILTER-RECORDS.} at {@code :L1382}, and the return at {@code COMMON-RETURN.} at
     * {@code :L604}.</p>
     *
     * <p><strong>Inputs.</strong> All optional, and every one of them a cursor or a filter - never a page
     * size, which no client may choose.</p>
     *
     * <ul>
     *   <li>{@code accountFilter} and {@code cardFilter} - {@code ACCTSIDI PIC X(11)} at
     *       {@code app/cpy-bms/COCRDLI.CPY:66} and {@code CARDSIDI PIC X(16)} at {@code :72}. Relayed to
     *       the service <em>exactly as received</em>: not trimmed, not padded, not case-converted and
     *       never coerced between absent and blank, because those are two different states and the third
     *       is a value. See the note on the three-state filter model below.</li>
     *   <li>{@code action} - the enumerated navigation intent, {@link CardListAction}. Defaults to
     *       {@link CardListAction#SUBMIT}, so a bare request returns the first page.</li>
     *   <li>{@code page}, {@code firstKey}, {@code lastKey} and {@code nextPageAvailable} - the cursor the
     *       source held in {@code WS-COMMAREA}: {@code WS-CA-SCREEN-NUM} at {@code :L237},
     *       {@code WS-CA-FIRST-CARD-NUM} at {@code :L234}, {@code WS-CA-LAST-CARD-NUM} at {@code :L231}
     *       and {@code WS-CA-NEXT-PAGE-IND} at {@code :L242}. Under transformation rule 7 they travel as
     *       request parameters and response metadata, because the target keeps no server-side session and
     *       no server-side cursor. A client echoes back what the previous {@code PageResponse} gave it.
     *       Note that {@code app/cpy/COCOM01Y.cpy} declares no page-number and no next-page field at all:
     *       this state lived in each program's own {@code WORKING-STORAGE}, so it is not commarea state
     *       and is not described as such.</li>
     * </ul>
     *
     * <p><strong>The two filters are three-state, and stay three-state.</strong>
     * {@code app/cbl/COCRDLIC.cbl} gives each filter three condition names, not two:
     * {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} at {@code :L62},
     * {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} at {@code :L63} and
     * {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} at {@code :L64}, with the card triple at {@code :L66-L68}.
     * The combined gate at {@code :L431-L432} is
     * {@code IF NOT FLG-ACCTFILTER-NOT-OK AND NOT FLG-CARDFILTER-NOT-OK}, which is satisfied by
     * <em>both</em> the valid and the blank state - so a blank filter is emphatically not an invalid
     * filter, and collapsing the three onto a boolean would browse where the source refuses to and refuse
     * where the source browses. The three states reach a client distinguishably:</p>
     *
     * <ul>
     *   <li><em>blank</em> - the parameter is absent or empty. The browse runs unfiltered and the response
     *       is {@code 200}.</li>
     *   <li><em>valid</em> - the parameter is numeric. The browse runs filtered and the response is
     *       {@code 200}.</li>
     *   <li><em>not ok</em> - the parameter is present and not numeric. {@code 2210-EDIT-ACCOUNT.} at
     *       {@code :L1003} or {@code 2220-EDIT-CARD.} at {@code :L1036} refuses it, the browse is
     *       suppressed entirely, and the response is {@code 400} carrying the field name and the failure
     *       kind {@code INVALID}. The blank case that other transactions refuse arrives as failure kind
     *       {@code BLANK}, so the two remain separable on the wire and are never merged.</li>
     * </ul>
     *
     * <p><strong>Outputs.</strong> {@code 200} with a {@code CardListResponse} of masked card rows
     * carrying the rows in browse order - ascending card number, the key order of the {@code CARDDAT}
     * cluster, so the ordering is deterministic and repeatable for a given cursor. The page also carries
     * the page number, the page size in force, whether a further record exists, and the first and last
     * keys to page from. The next-page indicator is reported as a boolean: the corpus has two mutually
     * incompatible sentinels for it - {@code app/cbl/COCRDLIC.cbl:L243} tests {@code LOW-VALUES} while
     * {@code app/cbl/COTRN00C.cbl} uses {@code 'N'} - and neither byte is transported, because a JSON
     * contract cannot carry both and need carry neither. A total element count and a total page count are
     * <b>Not available</b>: {@code CardListResponse} declares neither member, because the browse never
     * counts - it looks ahead exactly one record. Supplying them would need a counting query the source
     * never issues.</p>
     *
     * <p><strong>Two things the response deliberately does not carry, and one it now does.</strong> No row
     * carries a card number in the clear: {@code CardListResponse} tail-masks every one of them, because a
     * page of this browse is a page of primary account numbers and a list response is the single place a
     * client is most likely to log, cache or bookmark. Neither page cursor carries one either -
     * {@code WS-CA-FIRST-CARD-NUM} at {@code app/cbl/COCRDLIC.cbl:L234} and
     * {@code WS-CA-LAST-CARD-NUM} at {@code :L231} <em>are</em> card numbers, so they travel sealed by
     * {@code SnapshotTokenService} and are opened again on the next turn, which leaves the browse behaving
     * identically while making the wire value meaningless to anyone but this server. What the response does
     * now carry is the source's own screen text: the information and error messages
     * {@code 1000-SEND-MAP} painted, plus whether row selection was open, none of which HTTP can express
     * and all of which the source reported on every turn.</p>
     *
     * <p><strong>Side effects.</strong> None. The operation reads and returns; it writes no row, publishes
     * no message and mutates no field of this class.</p>
     *
     * <p><strong>Configuration and defaults.</strong> The page depth is
     * {@code carddemo.pagination.card-list-page-size}, the single resolution of
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L177-L178} -
     * corroborated three further times in the same program by {@code OCCURS 7 TIMES} at {@code :L76},
     * {@code :L86} and {@code :L255}. It is bound and range-checked by {@code CardListService}, never by
     * this class and never by a client, so no page-size literal appears in this file. The value in force
     * is echoed on every response.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when a filter is present and not
     * numeric, or when the page cursor is outside the single digit the source declares; the response names
     * the field and the failure kind - and the three control tokens are matched exactly, so
     * {@code action} accepts only the three declared spellings, {@code page} only ASCII digits and
     * {@code nextPageAvailable} only {@code true} and {@code false}, with no alias, no trim and no case
     * fold. {@code 412} when a page cursor was presented that this server did not seal, or whose seal has
     * expired. {@code 502} when the browse itself fails, as {@code FileAccessException} carrying the
     * expanded file status, and {@code 500} for an abend carrying code
     * {@code 999} and return code {@code 12} with the original throwable preserved as the cause. A page
     * past the end and an empty result are not failures: both return {@code 200} with no rows and
     * {@code nextPageAvailable} false, which is the source reporting the same condition as a screen
     * message. Paging backward from the first page and forward from the last both return the same page
     * unchanged, exactly as {@code :L901-L916} does.</p>
     *
     * @param accountFilter the account filter as typed, or null when the parameter was absent; relayed
     * verbatim. Absent, empty and populated are three distinct states and none is converted into another.
     * @param cardFilter the card filter as typed, or null when the parameter was absent; relayed verbatim
     * on the same terms. Protected data: never logged and never echoed into a diagnostic.
     * @param actionToken the navigation intent as typed, matched exactly against the three declared
     * tokens; null defaults to {@link CardListAction#SUBMIT}, which is what a bare request means.
     * @param pageToken the page number being echoed back, the transcription of
     * {@code WS-CA-SCREEN-NUM PIC 9(1)} at {@code app/cbl/COCRDLIC.cbl:L237}; null resolves to zero, which
     * is what a first entry sends. Accepted only as ASCII digits.
     * @param firstKey the sealed first cursor of the page displayed, used to page backward and to
     * redisplay; null on a first request. Its plain value is a card number, which is why it arrives sealed
     * and is never logged.
     * @param lastKey the sealed last cursor of the page displayed, used to page forward; null on a first
     * request, and sealed on the same terms.
     * @param nextPageToken whether the previous response reported a further record; null resolves to false,
     * which is what a first request means, and only {@code true} and {@code false} are accepted. The
     * page-forward action needs it, exactly as {@code :L945} needs {@code CA-NEXT-PAGE-EXISTS}.
     * @param authentication the authenticated principal Spring Security resolved for this request, from
     * which alone the administrator flag is derived; may be null, and then resolves to non-administrator.
     * @return {@code 200 OK} with one page of card rows and the cursor for the adjacent page; never null
     * @throws ValidationException if the page cursor is outside the declared single-digit domain, or if a
     * filter is present and not numeric
     * @throws FatalProcessingException if the browse fails for any reason other than a typed CardDemo
     * failure
     */
    @GetMapping
    public ResponseEntity<CardListResponse> listCards(
            @RequestParam(name = "accountFilter", required = false) final String accountFilter,
            @RequestParam(name = "cardFilter", required = false) final String cardFilter,
            @RequestParam(name = WebConfig.NAVIGATION_ACTION_PARAMETER, required = false)
            final String actionToken,
            @RequestParam(name = PAGE_FIELD, required = false) final String pageToken,
            @RequestParam(name = "firstKey", required = false) final String firstKey,
            @RequestParam(name = "lastKey", required = false) final String lastKey,
            @RequestParam(name = NEXT_PAGE_FIELD, required = false) final String nextPageToken,
            final Authentication authentication) {

        // Every control token is matched here, exactly, rather than converted. See resolveAction,
        // requirePageToken and requireBooleanToken for why: the framework's default converters normalise -
        // they trim an enum token, accept a signed integer and treat six spellings as a boolean - and a
        // normalising converter on a control token silently accepts instructions the operation never
        // declared.
        final CardListAction action = resolveAction(actionToken);
        final int page = requirePageToken(pageToken);
        final boolean nextPageAvailable = requireBooleanToken(nextPageToken);

        requirePageWithinScreenNumberDomain(page);
        requireBackwardPagingState(action, pageToken, page, firstKey);
        requirePositioningCursor(action, page, firstKey, lastKey);

        // The commarea halves of :L327-L331, rebuilt from the wire rather than from a session. The two
        // filters are placed exactly as received, because 2210-EDIT-ACCOUNT and 2220-EDIT-CARD are the
        // only paragraphs entitled to interpret them and they live in the service.
        final CardListService.CardListRequest request = new CardListService.CardListRequest(
                true,
                action.aidToken(),
                accountFilter,
                cardFilter,
                List.of(),
                CARD_LIST_TRANSACTION_ID,
                CARD_LIST_PROGRAM,
                isAdministrator(authentication),
                true,
                CARD_LIST_MAP,
                CARD_LIST_MAPSET,
                null,
                null,
                page,
                LAST_PAGE_NOT_SHOWN,
                nextPageAvailable,
                openPageCursor(page, FIRST_KEY_FIELD, firstKey),
                openPageCursor(page, LAST_KEY_FIELD, lastKey),
                List.of(),
                null,
                null);

        final CardListResponse listing = retrieveCardList(request);

        // Presence, never content: a filter value is an account identifier or a card number, and neither
        // may reach a log line. The keys and the rows are omitted for the same reason.
        LOG.debug("Served transaction {} program {} action {}: page={}, size={}, rows={}, nextPage={}, "
                + "accountFilterPresent={}, cardFilterPresent={}",
                CARD_LIST_TRANSACTION_ID, CARD_LIST_PROGRAM, action, listing.pageNumber(),
                listing.pageSize(), listing.rows().size(), listing.nextPageAvailable(),
                accountFilter != null, cardFilter != null);

        return ResponseEntity.ok(listing);
    }

    /**
     * Operation 2 of 3 - one card's details. Replaces CICS transaction {@code CCDL}
     * ({@code app/csd/CARDDEMO.CSD:L347}) and the program it fronts,
     * <strong>{@code app/cbl/COCRDSLC.cbl}</strong> (887 lines, 34 own paragraph labels), which painted mapset
     * {@code COCRDSL}.
     *
     * <p>The program is {@code COCRDSLC}, the card select program, and <strong>not</strong>
     * {@code COCRDSEC}. {@code COCRDSEC} is the target of the developer transaction {@code CDV1} and its
     * source is <b>Not available</b> anywhere in the repository; the class documentation records what
     * would be needed to close that gap. No part of this operation derives from it.</p>
     *
     * <p><strong>Purpose.</strong> Retrieves a single card by account and card number. This is
     * {@code 0000-MAIN.} at {@code app/cbl/COCRDSLC.cbl:L248} taken along its re-entry path, and it
     * preserves the source's order of work exactly: {@code 2200-EDIT-MAP-INPUTS.} at {@code :L608} runs
     * first and performs {@code 2210-EDIT-ACCOUNT.} at {@code :L647} before
     * {@code 2220-EDIT-CARD.} at {@code :L685}; only if those passed does
     * {@code 9000-READ-DATA.} at {@code :L726} run, and it performs exactly one thing -
     * {@code 9100-GETCARD-BYACCTCARD.} at {@code :L736}, the read of the {@code CARDDAT} base cluster by
     * card number. Validate first, read second, and never the other way round.</p>
     *
     * <p><strong>The unreachable pair is preserved, in the service, and not here.</strong>
     * {@code 9150-GETCARD-BYACCT.} at {@code app/cbl/COCRDSLC.cbl:L779} and its
     * {@code 9150-GETCARD-BYACCT-EXIT.} at {@code :L810} read the card file through the account alternate
     * index, and <em>nothing performs them</em>: {@code 9000-READ-DATA.} at {@code :L726} performs only the
     * {@code 9100} range, and the label {@code 9150} occurs nowhere else in the program. The pair is
     * genuinely unreachable and is retained for parity in the <em>service</em> layer, where the paragraph
     * map must stay complete and mechanically checkable; it is covered by {@code DL-CR-01} in the
     * {@code DECISION_LOG.md} and has its rows in
     * {@code TRACEABILITY_MATRIX.md} as tracked rather than abandoned code, which is what keeps it
     * compatible with the no-dead-code standard. Two consequences bind here. It is documented at this
     * operation but <em>not reproduced</em> at it, because a controller has no paragraph map. And
     * <strong>no coverage exclusion may be added anywhere to compensate for it</strong> - the coverage
     * floor is met by testing real behaviour, not by excluding code from measurement.</p>
     *
     * <p><strong>Inputs.</strong> The account filter and the card filter, {@code ACCTSIDI PIC X(11)} at
     * {@code app/cpy-bms/COCRDSL.CPY:60} and {@code CARDSIDI PIC X(16)} at {@code :66}, relayed verbatim.
     * Both are required by the source, which refuses an absent one; null, blank and {@code "*"} all mean
     * "not supplied" and all reject, and the three-state model of the list operation applies here too -
     * the refusal for a blank filter carries failure kind {@code BLANK} while the refusal for a
     * non-numeric one carries {@code INVALID}, and the two are never merged.</p>
     *
     * <p><strong>Field-contract note.</strong> The detail contract has <em>no</em> expiry-day field.
     * {@code app/cpy-bms/COCRDSL.CPY} holds zero {@code EXPDAY} tokens, whereas
     * {@code app/cpy-bms/COCRDUP.CPY} declares {@code EXPDAYI PIC X(2)}. That divergence is real and is
     * left alone: no expiry day is accepted here, none is returned, and the two maps are not unified.
     * Detail carries fifteen input fields and update seventeen, and those counts are the contract.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200} with the populated card projection: the header fields, the
     * account identifier, the tail-masked card number, the cardholder name, the active status and the
     * expiry month and year, each at the width its symbolic map declares. Expiry components are relayed as
     * text and never as a date type, because the map declares them as characters and parsing them would
     * invent a validation the map does not express.</p>
     *
     * <p><strong>The response is also the precondition for the update.</strong> It carries the
     * {@code CCUP-OLD-DETAILS} group sealed into its {@code snapshot} member, and the matching {@code PUT}
     * requires that value in its request body. It is what {@code 9300-CHECK-CHANGE-IN-REC} at
     * {@code app/cbl/COCRDUPC.cbl} compares against, so it must be the values <em>this read displayed</em>
     * rather than values derived from the live row at write time - deriving them there makes the comparison
     * tautologically true and destroys the guard, and so does letting the caller rewrite them, which is why
     * the value is sealed rather than published. It is produced by the update service, which owns the group,
     * and it carries the whole group rather than flat fields because
     * {@code app/cpy-bms/COCRDSL.CPY} declares fifteen fields and none of them is the expiry <em>day</em>
     * that {@code :L1507} compares. The card verification value of {@code :L294} is not a member: it is
     * stored but has no read path at any layer, and the concurrency question its predicate asked is answered
     * by the {@code @Version} column instead.</p>
     *
     * <p><strong>Side effects.</strong> None; two reads and no write. No state is retained: the group travels
     * to the caller and back, and the server holds nothing between the two requests.</p>
     *
     * <p><strong>Configuration and defaults.</strong> None. This operation reads no property; it takes no
     * default beyond the absence of both parameters, which is itself a refusal rather than a default.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when either filter is absent,
     * blank or not the required number of digits - the response names the field and the failure kind, so a
     * fifteen or seventeen digit card number is distinguishable from an omitted one. {@code 404} when no
     * card carries that number, the translation of the not-found arm at {@code :L756}. {@code 502} when
     * the read fails, carrying the expanded file status, and {@code 500} for an abend carrying code
     * {@code 999} and return code {@code 12}. If a request that looks correct returns {@code 400}, check the account
     * filter first: the source validates it before the card filter and stops at the first refusal, so the
     * reported field is the earlier one.</p>
     *
     * @param accountFilter the account filter as typed, or null when the parameter was absent; relayed
     * verbatim, with absent, blank and populated kept distinct.
     * @param cardFilter the card filter as typed, or null when the parameter was absent; relayed verbatim
     * on the same terms. Protected data: never logged and never echoed into a diagnostic.
     * @param cardKey an opaque row reference taken from a card-list row or from an earlier detail
     * response, or null when the caller supplied the two filters directly. When present it supplies BOTH
     * filters and neither may accompany it, because a client that has one of these has no need to compose
     * the other and a request carrying both is ambiguous about which the caller meant
     * @param authentication the principal Spring Security resolved, whose name is the subject the sealed
     * snapshot is issued to; a snapshot issued here will not open for another operator
     * @return {@code 200 OK} with the masked card detail projection and the sealed as-displayed snapshot a
     * subsequent update echoes back as {@code snapshot}; never null
     * @throws ValidationException if either filter is absent, blank or not exactly the required number of
     * digits
     * @throws RecordNotFoundException if no card carries that number
     * @throws FatalProcessingException if the read fails for any reason other than a typed CardDemo
     * failure
     */
    @GetMapping(DETAIL_PATH)
    public ResponseEntity<CardResponse> getCardDetail(
            @RequestParam(name = "accountFilter", required = false) final String accountFilter,
            @RequestParam(name = "cardFilter", required = false) final String cardFilter,
            @RequestParam(name = CARD_KEY_FIELD, required = false) final String cardKey,
            final Authentication authentication) {

        // Resolved FIRST, before any read is issued. An absent principal on this route is a wiring defect
        // rather than a request outcome, and a defect is reported without having touched a dataset.
        final String subject = subjectOf(authentication);

        // An opaque row reference stands in for the two filters, and the filters are relayed unchanged when
        // it is absent. Resolving it here rather than in the service is deliberate: the service reproduces
        // 2210-EDIT-ACCOUNT and 2220-EDIT-CARD and must go on receiving exactly what the map field would
        // have carried, so the reference is turned back into those two values before it sees them - and the
        // two edits then run on them, in the source's order, exactly as for a hand-composed request.
        final CardHandle handle = openCardHandle(cardKey, accountFilter, cardFilter);
        final String resolvedAccountFilter = handle == null ? accountFilter : handle.accountId();
        final String resolvedCardFilter = handle == null ? cardFilter : handle.cardNumber();

        final CardDto detail = retrieveCardDetail(resolvedAccountFilter, resolvedCardFilter);

        // The sealed as-displayed snapshot the matching update must echo back. It is produced by the UPDATE
        // service, because that service owns CCUP-OLD-DETAILS, and it carries the group rather than flat
        // fields because app/cpy-bms/COCRDSL.CPY declares fifteen fields and none of them is the expiry day
        // that :1507 compares - so this is the only route by which a client obtains it. It is sealed because a
        // group the caller could rewrite would make the :1503-1508 comparison unconditionally true; the value
        // is bound to this operation, these two identifiers, this principal and an expiry.
        final String snapshot = acquireUpdateSnapshot(resolvedAccountFilter, resolvedCardFilter, subject);
        // The response carries a reference of its own, so a client that arrived here from a list row can
        // reach the update without ever holding the card number. It is sealed under a different operation
        // kind from the snapshot, so neither value opens in the other's place.
        final CardResponse response = CardResponse.readOf(detail, snapshot,
                sealCardHandle(detail.getAccountId(), detail.getCardNumber()));

        LOG.debug("Served transaction {} program {}: detail returned for one card with its sealed"
                + " as-displayed snapshot", CARD_DETAIL_TRANSACTION_ID, CARD_DETAIL_PROGRAM);

        return ResponseEntity.ok(response);
    }

    /**
     * Operation 3 of 3 - apply a card update. Replaces CICS transaction {@code CCUP}
     * ({@code app/csd/CARDDEMO.CSD:L367}, described {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} at
     * {@code :L368}) and the program it fronts, {@code app/cbl/COCRDUPC.cbl} (1,560 lines, 45 own /
     * 47 mapped paragraph labels), which painted mapset {@code COCRDUP}.
     *
     * <p><strong>Purpose.</strong> Applies an edited card record and returns the refreshed detail. The
     * whole of the work - the field edits, the change detection against the as-displayed snapshot, and the
     * write - belongs to {@code CardUpdateService}. This method contributes no edit, no comparison and no
     * normalisation: <strong>the request body is passed through verbatim</strong>, with no trim, no case
     * conversion and no reformatting, because every one of those would change what the change detection
     * compares and so change which updates are accepted.</p>
     *
     * <p><strong>Inputs.</strong> A {@code CardUpdateRequest} body carrying the seventeen input fields of
     * {@code app/cpy-bms/COCRDUP.CPY} - including {@code EXPDAYI PIC X(2)}, which the detail map does not
     * have - and the as-edited group {@code CCUP-NEW-DETAILS} of
     * {@code app/cbl/COCRDUPC.cbl:L303-L313}. {@code @Valid} is applied so that the width constraints the
     * record declares, and those of its nested groups, are enforced before the service is entered.</p>
     *
     * <p><strong>The as-displayed snapshot is a sealed body member.</strong> The other half of
     * {@code app/cbl/COCRDUPC.cbl:L291-L301}, {@code CCUP-OLD-DETAILS}, arrives as the body's
     * {@code snapshot} member: one opaque authenticated string, echoed back unaltered from the preceding
     * detail read. Transformation Rule 7 puts it there - the program's own half of the COMMAREA held it
     * between the two turns of the pseudo-conversation and a stateless server has nowhere to put it - and it
     * is sealed rather than readable because a group the caller can rewrite would make the comparison
     * unconditionally true. The write is guarded twice over and
     * neither guard substitutes for the other - the field-by-field comparison of {@code :L1498-L1521}, and
     * the {@code @Version} column that detects any concurrent write to the row. The one member of the legacy
     * group that does not travel is the card verification value of {@code :L294}: it is stored but has no
     * read path at any layer and no symbolic map declares a field for it, so no client could supply one, and
     * the concurrency question its {@code :L1503} predicate asked is answered by the version column.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200} with the refreshed card detail projection, masked, so a
     * client sees what was actually stored rather than what it sent. The write response carries no
     * as-displayed group: a further update needs a fresh read, which is what the source required too - the
     * screen was repainted before the next turn.</p>
     *
     * <p><strong>Side effects.</strong> Writes one card row on success, inside the service's transaction.
     * On any failure nothing is written.</p>
     *
     * <p><strong>Configuration and defaults.</strong> None; the operation reads no property and applies no
     * default to any field of the body.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when an edit paragraph refuses a
     * field. The refusal messages are the program's own {@code 88}-level literals on
     * {@code WS-RETURN-MSG PIC X(75)} and reach a client byte for byte, so they read exactly as the 3270
     * screen displayed them: {@code 'Card number not provided'} at {@code app/cbl/COCRDUPC.cbl:L180},
     * {@code 'Card name not provided'} at {@code :L182},
     * {@code 'Card name can only contain alphabets and spaces'} at {@code :L184},
     * {@code 'Card number if supplied must be a 16 digit number'} at {@code :L194},
     * {@code 'Card Active Status must be Y or N'} at {@code :L196} and
     * {@code 'Card expiry month must be between 1 and 12'} at {@code :L198}. Those literals are screen
     * captions - they disclose no credential and no protected field value - so relaying them unchanged is
     * consistent with the secret-hygiene standard. A status other than {@code Y} or {@code N} and an
     * expiry month outside one to twelve are refused by the fifth and sixth of them respectively.
     * {@code 404} when the card row is absent. {@code 409} when the update lost a race - the response
     * carries the concurrency outcome, so a record changed by someone else stays distinguishable from a
     * lock that could not be taken and from a write that failed after locking. {@code 428} when the body
     * carried no {@code snapshot} member, or one that opened onto nothing, so the lost-update guard had
     * nothing to compare against and the write was never attempted: read the record and resend the value that
     * read returned. {@code 412} when the presented snapshot failed to open - altered, issued for another
     * card, issued to another operator, or expired - or when the group it carries no longer matches the
     * stored row, which is the guard doing its work: read again, re-apply the edits over the values that read
     * returned, and do not retry blindly.
     * {@code 502} for an I/O failure and {@code 500} for an abend.</p>
     *
     * @param request the received map, the edited group and the sealed {@code snapshot} member; must not be
     * null and is relayed to the service exactly as bound - the sealed member especially, being an
     * authentication tag over specific bytes. That member is the value the preceding detail read returned; an
     * absent one, or one that opens onto nothing, is reported as unconfirmed rather than compared against
     * nothing.
     * @return {@code 200 OK} with the refreshed and masked card detail projection; never null
     * @throws ValidationException if an edit paragraph refuses a field
     * @throws RecordNotFoundException if the card row is absent
     * @param cardKey an opaque row reference taken from a card-list row or a detail response, or null when
     * the body carries the account identifier and the card number itself. When present it supplies both, and
     * the body must not also carry either. It is sealed under its own operation kind, distinct from the
     * snapshot's, so the two are not interchangeable
     * @param authentication the principal Spring Security resolved, whose name is the subject the sealed
     * snapshot must have been issued to
     * @throws ConcurrentUpdateException if the update was abandoned for any of the recorded outcomes
     * @throws FatalProcessingException if the update fails for any reason other than a typed CardDemo
     * failure
     */
    @PutMapping
    public ResponseEntity<CardResponse> updateCard(
            @Valid @RequestBody final CardUpdateRequest request,
            @RequestParam(name = CARD_KEY_FIELD, required = false) final String cardKey,
            final Authentication authentication) {

        // Resolved FIRST, before the reference is opened or the service is entered, for the same reason as on
        // the read: an absent principal here is a wiring defect and is reported without touching a dataset.
        final String subject = subjectOf(authentication);

        // The row reference arrives as a request parameter and NOT as a body member, and that is a contract
        // constraint rather than a style choice: app/cpy-bms/COCRDUP.CPY declares exactly seventeen input
        // fields, transformation Rule 6 fixes the body to those seventeen plus the sealed snapshot and the
        // submitted group, and a nineteen-component request record is what that rule means here. The
        // reference is a transport concern - it identifies WHICH card the body applies to, not what it
        // contains - so it stays a parameter while the sealed snapshot stays in the body.
        final CardDto updated = applyCardUpdate(withIdentityFrom(request, cardKey), subject);

        LOG.debug("Served transaction {} program {}: one card row updated",
                CARD_UPDATE_TRANSACTION_ID, CARD_UPDATE_PROGRAM);

        return ResponseEntity.ok(CardResponse.writeOf(updated));
    }

    /**
     * Maps a rejected card request onto {@code 400 Bad Request}.
     *
     * <p>This is not a {@code @ControllerAdvice} method and this class is not an advice type: status
     * selection is contextual, so each controller performs its own. The exception types carry no
     * {@code @ResponseStatus}, which is why the mapping is stated here rather than on them.</p>
     *
     * <p>The message is relayed <strong>byte for byte</strong>, which is what lets a legacy literal such as
     * {@code 'Card expiry month must be between 1 and 12'} from {@code app/cbl/COCRDUPC.cbl:L198} reach a
     * client exactly as the 3270 screen displayed it. Nothing is fabricated: when the exception carries no
     * message the response carries no detail member either. These literals are screen captions and
     * disclose no credential, no identifier and no protected field value, so relaying them is consistent
     * with the secret-hygiene standard.</p>
     *
     * <p>The failure kind is always reported, and that is the point: it is the two-state discriminator
     * transcribed from {@code app/cpy/CSSETATY.cpy:L18-L19}, and it is what keeps a filter that was left
     * blank distinguishable from one that was supplied and wrong. Collapsing the two would erase the
     * distinction the three-state filter model exists to preserve. The field <em>name</em> is reported; the
     * field <em>value</em> never is, because on these operations it may be a card number.</p>
     *
     * @param rejection the validation failure raised by a card service, never null when Spring MVC
     * dispatches here.
     * @return {@code 400 Bad Request} carrying a problem detail, the rejected field name when the exception
     * named one, the failure kind, the stable error code and the correlation identifier
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

        LOG.warn("Refused a card request with 400 for field {} and failure kind {}",
                rejection.getFieldName(), rejection.getFailureKind());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps an absent card or account onto {@code 404 Not Found}.
     *
     * <p>The translation of the {@code DFHRESP(NOTFND)} arm of {@code 9100-GETCARD-BYACCTCARD.} at
     * {@code app/cbl/COCRDSLC.cbl:L756} and of file status {@code '23'}. The detail is
     * {@value #NOT_FOUND_PROBLEM_DETAIL} rather than the exception's message: this type is one of the five
     * {@code FileStatusMapper} composes, and although no card service reaches that mapper today, relying on
     * that would be relying on a property of three other files rather than of this one. The screen caption
     * travels on the screen result of a successful exchange, where the source put it.</p>
     *
     * <p><strong>The record key is deliberately not reported.</strong> The exception can carry a record
     * type and a record key, but on this resource group the key is a card number, so the throwing sites use
     * the message-only form precisely so that none can be attached, and this handler reads neither. A
     * client that needs to know which card it asked about already knows: it supplied it.</p>
     *
     * @param absent the not-found failure raised by a card service, never null when Spring MVC dispatches
     * here.
     * @return {@code 404 Not Found} carrying the fixed detail, the stable error code and the correlation
     * identifier, and no record type and no record key
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absent) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);

        problem.setDetail(NOT_FOUND_PROBLEM_DETAIL);

        LOG.warn("Refused a card request with 404: no matching record. No key is logged or returned");

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(withPublicEnvelope(problem, ERROR_CODE_NOT_FOUND));
    }

    /**
     * Maps an abandoned card update onto {@code 409 Conflict}, {@code 412 Precondition Failed} or
     * {@code 428 Precondition Required}, according to which of the five outcomes the service recorded.
     *
     * <p>The five outcomes are kept distinct rather than merged, because the source distinguishes them and a
     * client can act on the difference. Three of them are genuine conflicts on a record that exists:
     * {@code COULD_NOT_LOCK_ACCOUNT} and {@code COULD_NOT_LOCK_CUSTOMER} are lock refusals and
     * {@code LOCKED_BUT_UPDATE_FAILED} is a write that failed after the lock was taken. All three answer
     * {@code 409}, and the outcome is reported as a problem-detail member so that they stay separable.</p>
     *
     * <p>{@code DATA_CHANGED_BEFORE_UPDATE} answers {@code 412}. It is either the change-detection guard of
     * {@code 9300-CHECK-CHANGE-IN-REC} firing - the group the sealed snapshot carries no longer matches the
     * stored row - or the sealed snapshot itself failing to open, which is the same class of answer: the
     * precondition the caller presented cannot be honoured. A failed precondition is answered with the status
     * defined for exactly that, and
     * it is the status the account update already answers for the same outcome, so one outcome produces one
     * status across both update surfaces.</p>
     *
     * <p>{@code CHANGES_NOT_CONFIRMED} is different in kind again and answers {@code 428}. It means no
     * {@code snapshot} member was presented at all, or one was presented that opened onto nothing, so the
     * lost-update guard had nothing to compare against and the write was never attempted - an absent
     * precondition rather than a failed one. That is exactly the condition {@code 428} exists for, and the distinct status tells a
     * client to fetch the detail and resend rather than to re-read and merge.</p>
     *
     * <p>The message is relayed byte for byte where the exception carries one, which is how the legacy
     * caption {@code 'Record changed by some one else. Please review'} reaches a client intact. One
     * outcome carries an empty legacy message by design, and an empty message is relayed as empty rather
     * than replaced with an invented one.</p>
     *
     * <p>The affected-record reference the exception can carry is deliberately neither returned nor logged.
     * On this resource group the card-update service populates it with a tail-masked card number rather
     * than with the dataset name the field is documented for, so surfacing it would place a card reference
     * on the wire and in the log while also labelling it wrongly. The outcome is the discriminator a client
     * needs, and the correlation identifier is what ties a report back to its request.</p>
     *
     * @param conflict the concurrency failure raised by the card-update service, never null when Spring MVC
     * dispatches here.
     * @return {@code 409 Conflict} for a lock refusal or a failed write, {@code 412 Precondition Failed}
     * for a snapshot that did not verify, and {@code 428 Precondition Required} for an absent one, in every
     * case carrying the outcome when the exception recorded one
     */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(final ConcurrentUpdateException conflict) {

        final ConcurrentUpdateException.Outcome outcome = conflict.getOutcome();
        final HttpStatus status = statusFor(outcome);

        final ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(CONFLICT_PROBLEM_TITLE);

        final String conflictMessage = conflict.getMessage();
        if (conflictMessage != null) {
            problem.setDetail(conflictMessage);
        }
        if (outcome != null) {
            problem.setProperty(OUTCOME_PROPERTY, outcome);
        }

        // The exception also carries an affected-record reference, and it is deliberately neither logged
        // nor returned. On this resource group the card-update service supplies a tail-masked card number
        // there rather than the dataset name the field is documented for, so emitting it would put a card
        // reference on a log line and mislabel it at the same time. The outcome and the status are what
        // diagnose this failure; the correlation identifier ties the record back to the request.
        LOG.warn("Refused a card update with {} for outcome {}", status.value(), outcome);

        return ResponseEntity.status(status).body(withPublicEnvelope(problem, ERROR_CODE_UPDATE_CONFLICT));
    }

    /**
     * Selects the status for a recorded concurrency outcome.
     *
     * <p>Extracted so the three-way choice is stated once and can be read without the surrounding
     * problem-detail construction. A null outcome resolves to {@code 409}: the exception can be raised
     * without one, and a conflict is the safest reading of "abandoned for a reason the service did not
     * record".</p>
     *
     * @param outcome the outcome the service recorded, or null when it recorded none.
     * @return the status this resource answers for that outcome, never null
     */
    private static HttpStatus statusFor(final ConcurrentUpdateException.Outcome outcome) {

        if (outcome == null) {
            return HttpStatus.CONFLICT;
        }
        return switch (outcome) {
            case CHANGES_NOT_CONFIRMED -> HttpStatus.PRECONDITION_REQUIRED;
            case DATA_CHANGED_BEFORE_UPDATE -> HttpStatus.PRECONDITION_FAILED;
            case COULD_NOT_LOCK_ACCOUNT, COULD_NOT_LOCK_CUSTOMER, LOCKED_BUT_UPDATE_FAILED ->
                    HttpStatus.CONFLICT;
        };
    }

    /**
     * Maps an unavailable card file onto {@code 503 Service Unavailable}.
     *
     * <p>The translation of file status {@code '35'} and of {@code DFHRESP(NOTOPEN)}: the dataset the
     * operation needs is not open. It is a condition of the environment rather than of the request, and it
     * is expected to clear without the request changing, which is what separates {@code 503} from the
     * {@code 500} an I/O failure receives. The detail is fixed and names no cause; the cause stays attached
     * to the exception and is logged.</p>
     *
     * <p><strong>The resource name is no longer returned.</strong> It named the dataset behind the failure,
     * which a caller cannot act on and an attacker can map. It is logged at {@code ERROR} and reachable
     * through the correlation identifier the body carries.</p>
     *
     * @param unavailable the unavailable-file failure, never null when Spring MVC dispatches here.
     * @return {@code 503 Service Unavailable} carrying a fixed problem detail, the stable error code and the
     * correlation identifier
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);

        // The logical file name is logged, not returned: naming the dataset tells a caller nothing it can
        // act on and tells an attacker the internal topology.
        LOG.error("A card operation could not reach file {}", unavailable.resourceName().orElse("unnamed"),
                unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNAVAILABLE));
    }

    /**
     * Maps a card-file I/O failure onto {@code 502 Bad Gateway}.
     *
     * <p>The translation of the {@code '9x'} file-status family and of the {@code WHEN OTHER} arms of the
     * read paragraphs. A physical or logical input-output failure is a fault in the store behind this
     * service rather than in the request or in this service, which is exactly what a gateway status
     * describes - and it is the status the account, transaction, billing and report surfaces already answer
     * for the identical exception, so one file status now produces one status code across the whole API
     * rather than {@code 502} on four surfaces and {@code 500} on this one.</p>
     *
     * <p>The four-character expanded status that the legacy status renderer produced, the logical file and the
     * operation are all <strong>logged rather than returned</strong>. Reporting all three in
     * the body is tempting on the grounds that each identifies the failure without disclosing any data;
     * individually that
     * is true, but together they tell a caller which internal dataset failed which verb with which status,
     * which is the disclosure {@code CWE-209} names. No key and no field value is reported either. The detail
     * is fixed, the body carries {@value #ERROR_CODE_IO_FAILURE} and the correlation identifier that joins it
     * to the log record holding the three withheld values, and the cause is logged rather than returned.</p>
     *
     * @param failure the I/O failure, never null when Spring MVC dispatches here.
     * @return {@code 502 Bad Gateway} carrying a fixed problem detail, the stable error code and the
     * correlation identifier
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, IO_PROBLEM_DETAIL);
        problem.setTitle(IO_PROBLEM_TITLE);

        // The expanded status, the logical file and the operation are logged, not returned. Together they
        // describe which internal dataset failed which verb and how, which is precisely the disclosure
        // CWE-209 names; separately, none of the three is actionable by a caller. The status stays 502 rather
        // than 500 because the fault is in the store behind this service, which is what every other
        // controller in this package answers for this exception.
        LOG.error("Answered a card request with 502: status {} on file {} during {}",
                failure.getExpandedStatus(), failure.getLogicalFileName(), failure.getOperation(), failure);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(withPublicEnvelope(problem, ERROR_CODE_IO_FAILURE));
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract on the
     * diagnostic log.
     *
     * <p>The abend routine moved {@code 999} into {@code ABEND-CODE} and called the language-environment
     * abend service, and the batch stream reported return code {@code 12}. <strong>Both are logged and
     * neither is returned:</strong> they describe an internal termination path, so a caller can act on
     * neither while a caller who can read them learns which path a crafted request reached. The detail is
     * fixed, the body carries {@value #ERROR_CODE_ABEND} and the correlation identifier, and the cause
     * remains attached to the exception and is logged at {@code ERROR}.</p>
     *
     * @param abend the fatal failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and
     * the correlation identifier
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        // Logged, not returned: 999 and 12 are internals of the terminating path.
        LOG.error("A card operation abended: code {} returnCode {} culprit {} reason {}",
                abend.getAbendCode() == null ? ABEND_CODE : abend.getAbendCode(),
                FatalProcessingException.BATCH_RETURN_CODE, abend.getAbendCulprit(), abend.getAbendReason(),
                abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_ABEND));
    }

    /**
     * Maps every remaining typed CardDemo failure onto {@code 500 Internal Server Error}.
     *
     * <p>Spring MVC resolves the most specific handler, so none of the six types mapped above reaches this
     * method. It covers the rest of the nine-type hierarchy, each of which stands for a legacy file status
     * or response code. Two of those, the duplicate-key translation of file status {@code '22'} and the
     * referential-integrity translation, are not raised by any of these three operations - the two reads
     * insert nothing and the update rewrites an existing row - and neither type is part of this file's
     * dependency contract, so both resolve here rather than through a mapping of their own. The detail is
     * the fixed string used for an abend, for the same reason, and the cause is logged rather than
     * returned.</p>
     *
     * @param failure the typed failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail, the stable error code and the
     * correlation identifier
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("A card operation failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPublicEnvelope(problem, ERROR_CODE_INTERNAL));
    }

    /**
     * Maps a bean-validation failure the framework raised on the request body onto {@code 400 Bad Request}
     * with this controller's own envelope.
     *
     * <p><strong>Why this mapper exists.</strong> {@code @Valid} on the update body is enforced by the
     * framework <em>before</em> the mapped method is entered, so a violation never reaches the service and
     * never becomes the {@link ValidationException} the mapper above claims. Without this method the refusal
     * escaped to the framework's default handling and answered with a body of an entirely different shape - no
     * {@code title}, no {@code errorCode} and no {@code correlationId} - so one logical outcome, a rejected
     * input, looked like two unrelated failures depending on which layer noticed it. A review recorded that
     * inconsistency as a High-severity finding on this seam, and it is the same defect on every route in this
     * package that binds a body.
     *
     * <p><strong>Why it is declared here rather than centrally.</strong> Stated for the whole package in
     * {@code com.cardemo.controller}'s package documentation; this handler keeps that property, carrying
     * {@code @ExceptionHandler} only and answering for this controller alone.
     *
     * <p><strong>What the body does not contain.</strong> No rejected value, no field name, no constraint
     * message, no exception class and no discriminator; {@link #BIND_FAILURE_PROBLEM_DETAIL} records why each
     * is withheld. The discriminator in particular is omitted rather than invented: the framework supplies no
     * counterpart to the two-state marker of {@code app/cpy/CSSETATY.cpy}, so publishing one would misreport
     * it.
     *
     * <p>This method is not request-mapped and is none of the three operations.
     *
     * @param rejection the framework's binding result, read for the log line only - its field errors retain
     *                  the submitted values, so nothing is copied out of them into the body
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the rejection
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyBindFailure(
            final MethodArgumentNotValidException rejection) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, BIND_FAILURE_PROBLEM_DETAIL);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        LOG.warn("Refused a card request with 400: the framework rejected {} field(s) of the request body "
                        + "before the operation was entered. Fields {}",
                rejection.getBindingResult().getFieldErrorCount(),
                rejection.getBindingResult().getFieldErrors().stream()
                        .map(FieldError::getField).distinct().sorted().toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_VALIDATION));
    }

    /**
     * Maps a request body the framework could not read onto {@code 400 Bad Request} with this controller's
     * own envelope.
     *
     * <p>Which three framework conditions fold into this one exception, why the status is {@code 400}
     * rather than {@code 415} or {@code 422}, and why the handler is declared per controller rather than
     * centrally are stated for the whole package in {@code com.cardemo.controller}'s package
     * documentation. The two refusals are told apart by {@link #ERROR_CODE_UNREADABLE_BODY} rather than
     * by the status line, and this method is not request-mapped, so it is none of the three operations.
     *
     * @param unreadable the framework's read failure, whose message and cause chain are deliberately kept out
     *                   of the body and emitted at {@code DEBUG} only
     * @return {@code 400 Bad Request} carrying the fixed envelope and nothing drawn from the parser
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            final HttpMessageNotReadableException unreadable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNREADABLE_BODY_PROBLEM_DETAIL);
        problem.setTitle(VALIDATION_PROBLEM_TITLE);

        LOG.warn("Refused a card request with 400: the request body could not be read. Read failure {}",
                unreadable.getClass().getSimpleName());
        // The cause chain is preserved rather than dropped, but it is emitted only at DEBUG. A parser message
        // can quote the fragment of the payload it stopped on, and this operation's payload carries a card
        // number and an embossed name, so it must not be written at a level that is enabled in every
        // environment. At DEBUG an operator who deliberately asks for the chain gets all of it.
        LOG.debug("Cause chain of the unreadable card body", unreadable);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withPublicEnvelope(problem, ERROR_CODE_UNREADABLE_BODY));
    }

    /**
     * Delegates to the card-list service exactly once and returns its paging payload.
     *
     * <p>Extracted from {@link #listCards} so that the handler reads as a sequence of decisions and the
     * abend translation is stated once. Both catch clauses are load bearing and neither swallows anything:
     * {@code CardDemoException} is rethrown unchanged so that the declared status mapping applies to it,
     * and because that type extends {@code RuntimeException} the first clause is what stops the second from
     * re-wrapping an already-typed failure. Every other runtime failure becomes an abend with the original
     * throwable preserved as the cause.</p>
     *
     * <p><b>The turn's status messages are surfaced, and that is a correction rather than an addition.</b>
     * {@code WS-INFO-MSG} at {@code app/cbl/COCRDLIC.cbl:L112} and {@code WS-ERROR-MSG} at {@code :L117} are
     * the only place several outcomes are reported at all - reaching the last page, an empty result, a
     * refused row selection - and {@code :L431-L435} deliberately re-reads the list whenever the failure is
     * not a filter failure, so an error-bearing turn still has rows to show. Returning the rows alone would
     * discard everything the screen told the operator.</p>
     *
     * <p>What is still not surfaced is 3270 furniture with no place on a JSON contract: the screen header,
     * the seven row-selection and highlight attribute flags, the four filter attribute flags, the cursor
     * field, and the navigation intent naming the program a transfer of control would have gone to. The one
     * selection-related value that <em>is</em> surfaced is whether row selection is available at all, which
     * is business state rather than an attribute: a client that offers a per-row action needs to know that
     * correcting the filter comes first.</p>
     *
     * <p><b>The two page cursors are sealed here.</b> The service's own keys are card numbers, so they are
     * replaced by opaque values before the envelope is built and reopened on the next request. A paging
     * payload is absent only on a transfer of control, which the three modelled actions cannot cause, so its
     * absence is a broken service contract rather than a request outcome and is reported as an abend.</p>
     *
     * @param request the rebuilt commarea halves and the received filters.
     * @return the list envelope, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns no paging payload
     */
    private CardListResponse retrieveCardList(final CardListService.CardListRequest request) {

        final CardListService.CardListResult result;
        try {
            result = this.cardListService.listCards(request);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, CARD_LIST_PROGRAM, CARD_LIST_ABEND_REASON,
                    CARD_LIST_ABEND_MESSAGE, unexpected);
        }

        if (result.page == null) {
            throw new FatalProcessingException(ABEND_CODE, CARD_LIST_PROGRAM, CARD_LIST_ABEND_REASON,
                    CARD_LIST_ABEND_MESSAGE);
        }
        final PageResponse<CardDto.CardListRow> page = result.page;
        // Each cursor is sealed for the page it describes, so the pair a client hands back cannot silently
        // address a different page than the page number it hands back with it. See openPageCursor.
        final String cursorKind = cursorKindFor(page.getPageNumber());
        return CardListResponse.of(page,
                this.snapshotTokenService.sealCursor(cursorKind, page.getFirstKey()),
                this.snapshotTokenService.sealCursor(cursorKind, page.getLastKey()),
                result.informationMessage,
                result.errorMessage,
                !result.rowSelectionProtected,
                this::sealCardHandle);
    }

    /**
     * Delegates to the card-detail service exactly once and returns the card projection.
     *
     * <p>Extracted from {@link #getCardDetail} on the same terms as the card-list delegate, and with the
     * same two load-bearing catch clauses. The service method called is the documented primary retrieval
     * entry point, which validates the account filter and then the card filter before reading, so the
     * source's order of work is preserved by choosing it rather than by reproducing it here.</p>
     *
     * @param accountFilter the account filter as received, relayed verbatim.
     * @param cardFilter the card filter as received, relayed verbatim. Never logged.
     * @return the card projection, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private CardDto retrieveCardDetail(final String accountFilter, final String cardFilter) {

        try {
            return this.cardDetailService.viewCardDetail(accountFilter, cardFilter);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, CARD_DETAIL_PROGRAM, CARD_DETAIL_ABEND_REASON,
                    CARD_DETAIL_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Obtains the {@code CCUP-OLD-DETAILS} group for one card.
     *
     * <p>Delegates once to the update service, which owns the group. The catch clauses behave exactly as on
     * the other delegates: a typed failure is rethrown so the declared status mapping applies, and anything
     * else becomes an abend with its cause preserved.</p>
     *
     * <p>The failure is <em>not</em> suppressed. Returning a null group would answer {@code 200} with a
     * response a client cannot update from, and both services read the same cluster, so a filter this one
     * refuses is a filter the detail read would have refused as well.</p>
     *
     * @param accountFilter the account filter as resolved, relayed verbatim.
     * @param cardFilter the card filter as resolved, relayed verbatim.
     * @param subject the authenticated principal the snapshot is issued to, taken from the resolved
     * {@code Authentication} and never from a request member.
     * @return the sealed as-displayed snapshot, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private String acquireUpdateSnapshot(final String accountFilter, final String cardFilter,
                                         final String subject) {

        try {
            return this.cardUpdateService.sealSnapshotForUpdate(accountFilter, cardFilter, subject);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, CARD_DETAIL_PROGRAM, CARD_DETAIL_ABEND_REASON,
                    CARD_DETAIL_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Delegates to the card-update service exactly once and returns the refreshed card projection.
     *
     * <p>Extracted from {@link #updateCard} on the same terms as the other two delegates. The body is
     * handed over exactly as bound - no field is trimmed, case-converted, defaulted or reordered on the way
     * through - because the change detection compares the two snapshot groups as they arrive and any
     * normalisation here would change its verdict.</p>
     *
     * @param request the received map, the edited group and the sealed snapshot, relayed verbatim.
     * @param subject the authenticated principal the sealed snapshot must have been issued to.
     * @return the refreshed card projection, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private CardDto applyCardUpdate(final CardUpdateRequest request, final String subject) {

        try {
            return this.cardUpdateService.updateCard(request, subject);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, CARD_UPDATE_PROGRAM, CARD_UPDATE_ABEND_REASON,
                    CARD_UPDATE_ABEND_MESSAGE, unexpected);
        }
    }

    /**
     * Returns the request with its two identifiers taken from an opaque row reference, when one was sent.
     *
     * <p>A record has no wither, so a new one is constructed with all nineteen components: the seventeen map
     * fields relayed unchanged, the sealed snapshot and the edited group relayed unchanged, and only the
     * account identifier and the card number substituted. Copying the components explicitly rather than mutating is what keeps
     * the "passed through verbatim" property of this operation true - nothing is trimmed, folded or
     * reformatted on the way past.
     *
     * <p>Side effects: none. Returns the argument itself when no reference was sent, so the ordinary path
     * allocates nothing.
     *
     * @param request the received body; must not be null
     * @param cardKey the raw row reference, or null when the body carries the identifiers
     * @return the request to hand to the service; never null
     * @throws ValidationException with failure kind {@code INVALID} when the body also carries an identifier,
     * or when the reference does not verify
     */
    private CardUpdateRequest withIdentityFrom(final CardUpdateRequest request, final String cardKey) {

        final CardHandle handle = openCardHandle(cardKey, request.accountId(), request.cardNumber());
        if (handle == null) {
            return request;
        }

        return new CardUpdateRequest(
                request.transactionName(),
                request.title01(),
                request.currentDate(),
                request.programName(),
                request.title02(),
                request.currentTime(),
                handle.accountId(),
                handle.cardNumber(),
                request.cardholderName(),
                request.cardStatusCode(),
                request.expiryMonth(),
                request.expiryYear(),
                request.expiryDay(),
                request.informationMessage(),
                request.errorMessage(),
                request.functionKeys(),
                request.functionKeysContinued(),
                request.snapshot(),
                request.newDetails());
    }

    /**
     * Resolves the enumerated navigation action from its raw token, accepting nothing but an exact match.
     *
     * <p>The framework's default enum binding trims its input before resolving, so {@code " SUBMIT "} would
     * bind as {@code SUBMIT}. That is coercion of a control token: the value decides which browse direction
     * runs, so accepting a spelling the operation never declared is accepting an instruction it never
     * declared. Comparison here is by {@link String#equals(Object)} against the constant names, with no trim
     * and no case fold.</p>
     *
     * @param token the raw request-parameter value, or null when the parameter was absent - which defaults to
     * {@link CardListAction#SUBMIT}, so a bare request returns the first page exactly as before.
     * @return the resolved action, never null
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is not one of the three declared tokens. Neither message repeats the
     * rejected value
     */
    private static CardListAction resolveAction(final String token) {

        if (token == null) {
            return CardListAction.SUBMIT;
        }
        if (token.isEmpty()) {
            throw new ValidationException(WebConfig.ACTION_BLANK_MESSAGE,
                    WebConfig.NAVIGATION_ACTION_PARAMETER, ValidationException.FailureKind.BLANK);
        }
        for (final CardListAction candidate : CardListAction.values()) {
            if (candidate.name().equals(token)) {
                return candidate;
            }
        }
        throw new ValidationException(WebConfig.ACTION_UNKNOWN_MESSAGE,
                WebConfig.NAVIGATION_ACTION_PARAMETER, ValidationException.FailureKind.INVALID);
    }

    /**
     * Resolves the page cursor from its raw token, accepting ASCII digits and nothing else.
     *
     * <p>The framework's default {@code int} binding accepts a leading sign and surrounding whitespace, so
     * {@code " +3 "} would bind as three. {@code WS-CA-SCREEN-NUM} at {@code app/cbl/COCRDLIC.cbl:L237} is
     * {@code PIC 9(1)} - unsigned, one digit - and the COBOL {@code IS NUMERIC} class test that guards every
     * such field admits only the characters zero through nine. This reproduces that test rather than the
     * framework's. The single-digit <em>domain</em> is a separate rule and stays where it was, in
     * {@code requirePageWithinScreenNumberDomain}; this method owns the character class only.</p>
     *
     * <p>The digit range is written out rather than delegated to {@link Character#isDigit(char)} on purpose:
     * that method is true for every decimal digit in Unicode, so a guard written with it would admit an
     * Arabic-Indic digit string that no 3270 terminal could have sent.</p>
     *
     * @param token the raw request-parameter value, or null when the parameter was absent - which defaults to
     * zero, the field's own initial value on a first entry.
     * @return the page cursor
     * @throws ValidationException with failure kind {@code BLANK} when the parameter was present and empty,
     * and {@code INVALID} when it is not a run of ASCII digits
     */
    private static int requirePageToken(final String token) {

        if (token == null) {
            return LOWEST_SCREEN_NUMBER;
        }
        if (token.isEmpty()) {
            throw ValidationException.missingField(PAGE_FIELD,
                    "page must be supplied as a digit when the parameter is present at all");
        }
        for (int index = 0; index < token.length(); index++) {
            final char character = token.charAt(index);
            if (character < '0' || character > '9') {
                throw ValidationException.invalidField(PAGE_FIELD,
                        "page must consist of ASCII digits only, the domain of the COBOL IS NUMERIC class"
                                + " test that guards the single-digit screen number");
            }
        }
        // Parsed rather than range-checked here: the domain check belongs to
        // requirePageWithinScreenNumberDomain, which states the single-digit rule once and cites the field
        // it comes from. What this method owns is the character class - the COBOL IS NUMERIC test - and a
        // value too long for the field is refused by the range check rather than duplicated here. A digit
        // run of any length parses without overflow because the length itself is bounded first.
        if (token.length() > MAXIMUM_PAGE_TOKEN_DIGITS) {
            throw ValidationException.invalidField(PAGE_FIELD,
                    "page must be at most " + MAXIMUM_PAGE_TOKEN_DIGITS + " digits; a longer value was never"
                            + " representable in the screen number the card list browse maintains");
        }
        return Integer.parseInt(token);
    }

    /**
     * Resolves the page-state flag from its raw token, accepting only {@code true} and {@code false}.
     *
     * <p>The framework's default {@code boolean} binding additionally accepts {@code on}, {@code off},
     * {@code yes}, {@code no}, {@code 1} and {@code 0}. This flag decides whether a page-forward is attempted
     * at all, so admitting six aliases for two values admits five spellings of an instruction the operation
     * never declared.</p>
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
     * Rejects a page cursor outside the domain the source declares for it.
     *
     * <p>{@code WS-CA-SCREEN-NUM} is {@code PIC 9(1)} at {@code app/cbl/COCRDLIC.cbl:L237} - a single
     * unsigned digit, which is why the source's own increment at {@code :L492} wraps rather than
     * overflowing and why that wrap is preserved. A value outside that domain has no meaning to the browse
     * and would corrupt the cursor arithmetic silently, so it is refused at the boundary where a client can
     * see it named.</p>
     *
     * <p>This is a check on the <em>declared domain of a wire parameter</em>, not a re-implementation of an
     * edit paragraph. The edits belong to the service: the two filters are relayed untouched and it is
     * {@code 2210-EDIT-ACCOUNT.} and {@code 2220-EDIT-CARD.} that judge them. Nothing about a card number,
     * a name, a status or an expiry component is judged here.</p>
     *
     * @param page the page number received.
     * @throws ValidationException with failure kind {@code INVALID} when {@code page} is outside the
     * single-digit domain
     */
    private static void requirePageWithinScreenNumberDomain(final int page) {

        if (page < LOWEST_SCREEN_NUMBER || page > HIGHEST_SCREEN_NUMBER) {
            throw ValidationException.invalidField(PAGE_FIELD,
                    "page must be between " + LOWEST_SCREEN_NUMBER + " and " + HIGHEST_SCREEN_NUMBER
                            + " inclusive, the domain of the single-digit screen number the card list"
                            + " browse maintains");
        }
    }

    /**
     * Refuses a backward-paging request that does not carry the position it is asking to move back from.
     *
     * <p><strong>Finding, severity High - remediated here.</strong>
     * {@code GET /api/cards?action=PAGE_BACKWARD} with no cursors answered
     * {@code 502 CARDDEMO-IO-FAILURE}, titled "Card data store input-output failure", and logged
     * {@code status 032 on file CARDDAT during READ}. Nothing had failed. The store was healthy and the
     * read did exactly what it was asked to do; the request was incomplete.
     *
     * <p>Why it surfaced as a store failure is worth stating, because the browse itself is correct.
     * {@code 9100-READ-BACKWARDS.} at {@code app/cbl/COCRDLIC.cbl:L1264} has <em>no</em> end-of-file arm:
     * its {@code EVALUATE WS-RESP-CD} at {@code :L1304-L1318} carries {@code DFHRESP(NORMAL)},
     * {@code DFHRESP(DUPREC)} and {@code WHEN OTHER}, so exhausting the file at the priming
     * {@code READPREV} of {@code :L1294-L1302} is a file error by construction. That is not an oversight in
     * the source - in the source it is <em>unreachable</em>. {@code 1000-SEND-MAP.} writes
     * {@code WS-CA-FIRST-CARDKEY} and {@code WS-CA-SCREEN-NUM} into the COMMAREA together, so a task that
     * held a screen number past the first page necessarily held a first key with records before it. The two
     * could not disagree.
     *
     * <p>A stateless request supplies them independently, so they can. Transformation Rule 7 moves that
     * paging state onto the wire, and moving state onto the wire is exactly what turns a COMMAREA invariant
     * into a request precondition that has to be <em>checked</em>. Checking it here keeps
     * {@code 9100-READ-BACKWARDS} transcribed exactly as written - no end-of-file arm is invented, no
     * {@code WHEN OTHER} behaviour is altered - and answers the incomplete request as the client error it
     * is.
     *
     * <p>The first page is not affected and must not be. {@code WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE} at
     * {@code app/cbl/COCRDLIC.cbl:L439-L445} re-reads <em>forward</em> from the first key and redisplays
     * the current page with {@code 'NO PREVIOUS PAGES TO DISPLAY'} from {@code :L901-L904}. That path needs
     * no cursor - the space-filled key positions the browse at the start of the file - so a backward
     * request on page one is admitted exactly as before.
     *
     * @param action the resolved navigation action; never null
     * @param pageToken the raw page parameter, or null when it was absent - the raw form matters, because
     * an absent page defaults to zero and zero is not the first page
     * @param page the resolved page number
     * @param firstKey the raw sealed first-page cursor, or null when it was absent
     * @throws ValidationException with failure kind {@code BLANK}, naming {@code page} when a backward
     * request omitted the page it is moving back from, and naming {@code firstKey} when it omitted the
     * cursor that addresses it
     */
    private static void requireBackwardPagingState(final CardListAction action, final String pageToken,
            final int page, final String firstKey) {

        if (action != CardListAction.PAGE_BACKWARD) {
            return;
        }

        if (pageToken == null) {
            throw ValidationException.missingField(PAGE_FIELD,
                    "page must be supplied on a backward request, because it is the page being moved back"
                            + " from. Return the pageNumber the previous response reported");
        }

        if (page == FIRST_SCREEN_NUMBER) {
            // The first page needs no cursor: the source re-reads forward from a space-filled key and
            // reports that there are no previous pages.
            return;
        }

        if (firstKey == null || firstKey.isBlank()) {
            throw ValidationException.missingField(FIRST_KEY_FIELD,
                    "firstKey must be supplied on a backward request past the first page, because it is the"
                            + " position being moved back from. Return the firstCursor the previous response"
                            + " reported, alongside its pageNumber");
        }
    }

    /**
     * Refuses any request that names a page past the first without carrying the cursor that addresses it.
     *
     * <p><strong>Finding, severity Medium - remediated here.</strong>
     * {@code GET /api/cards?action=PAGE_FORWARD&page=8} with no cursor answered {@code 200} reporting
     * {@code pageNumber=8} while serving <em>page one's seven rows</em>, and
     * {@code action=SUBMIT&page=8} did the same. A response that reports a page number it did not serve is
     * unusable: a client cannot tell whether it advanced, and a client that keys its next request off the
     * reported number never terminates.
     *
     * <p><strong>Why the browse cannot position from a page number alone.</strong> The page number is a
     * display counter, not an address. {@code WS-CA-SCREEN-NUM} at {@code app/cbl/COCRDLIC.cbl} is a
     * {@code PIC 9(1)} the program increments for the heading; what positions the browse is
     * {@code WS-CA-FIRST-CARDKEY} or {@code WS-CA-LAST-CARDKEY}, which
     * {@code 9000-READ-FORWARD.} and {@code 9100-READ-BACKWARDS.} move into the record key before the
     * {@code STARTBR}. With no key the space-filled field positions at the start of the file, which is why
     * the browse served page one. The source cannot exhibit the disagreement because
     * {@code 1000-SEND-MAP.} writes the counter and both keys into the COMMAREA together, so a task
     * holding a screen number past the first necessarily held the key that addresses it.
     *
     * <p>Transformation Rule 7 moves that state onto the wire, where the parts arrive independently and can
     * therefore disagree. This is the same COMMAREA-invariant-becomes-request-precondition shape as
     * {@link #requireBackwardPagingState}, and it is answered the same way: the incomplete request is
     * refused, naming the cursor that would complete it, rather than served with a number that is not true.
     * Nothing in the browse changes.
     *
     * <p><strong>Which cursor completes which action.</strong> A forward request advances past the position
     * it is showing, so it needs {@code lastKey}. Every other action redisplays or moves back from that
     * position, so it needs {@code firstKey}. The first page needs neither, because a space-filled key
     * positions at the start of the file exactly as the source intends - so first-page requests, and
     * requests that name no page at all, are admitted untouched.
     *
     * @param action the resolved navigation action; never null
     * @param page the resolved page number
     * @param firstKey the raw sealed first-page cursor, or null when it was absent
     * @param lastKey the raw sealed last-page cursor, or null when it was absent
     * @throws ValidationException with failure kind {@code BLANK}, naming the cursor the request omitted
     */
    private static void requirePositioningCursor(final CardListAction action, final int page,
            final String firstKey, final String lastKey) {

        if (page <= FIRST_SCREEN_NUMBER) {
            // The first page, and an absent page, position at the start of the file with no cursor at all.
            return;
        }

        if (action == CardListAction.PAGE_FORWARD) {
            if (lastKey == null || lastKey.isBlank()) {
                throw ValidationException.missingField(LAST_KEY_FIELD,
                        "lastKey must be supplied on a forward request past the first page, because it is"
                                + " the position being advanced from. Return the lastCursor the previous"
                                + " response reported, alongside its pageNumber");
            }
            return;
        }

        if (firstKey == null || firstKey.isBlank()) {
            throw ValidationException.missingField(FIRST_KEY_FIELD,
                    "firstKey must be supplied when a page past the first is named, because it is the"
                            + " position being redisplayed. Return the firstCursor the previous response"
                            + " reported, alongside its pageNumber");
        }
    }

    /**
     * Opens one sealed page cursor, bound to the page number the request presented it with.
     *
     * <p>The cursor is sealed for a page and reopened for a page, so a cursor minted for page one cannot be
     * presented as page five's. Without that binding the pair is independently forgeable in the only way
     * that matters: page five plus page one's first key sends the backward browse to a key with nothing
     * before it, which lands in the same {@code WHEN OTHER} arm
     * {@link #requireBackwardPagingState} exists to keep out of reach - and reports a datastore failure
     * that did not happen. Binding them makes the mismatch a refusal at the boundary instead.
     *
     * <p>A verification failure is reported as a rejected <em>request</em> rather than as a concurrency
     * conflict. {@code SnapshotTokenService} raises
     * {@link ConcurrentUpdateException} for a snapshot that cannot be verified, and for a record snapshot
     * that is the right answer - the remedy is to read the record again. For a page cursor it is not: a
     * cursor that does not verify against the page it arrived with was never issued for that page, so the
     * remedy is to page again from a response, and the field that is wrong has a name.
     *
     * @param page the page number the request presented
     * @param field the request-parameter name to report if verification fails
     * @param sealedCursor the raw parameter value, or null when it was absent - an absent cursor is a
     * legitimate first request and yields null
     * @return the recovered browse key, or null when no cursor was presented
     * @throws ValidationException with failure kind {@code INVALID} when a cursor was presented and does
     * not verify for that page
     */
    private String openPageCursor(final int page, final String field, final String sealedCursor) {

        try {
            return this.snapshotTokenService.openCursor(cursorKindFor(page), sealedCursor);
        } catch (final ConcurrentUpdateException unverifiable) {
            LOG.warn("Refused transaction {} program {}: the {} cursor does not verify for page {}",
                    CARD_LIST_TRANSACTION_ID, CARD_LIST_PROGRAM, field, page);
            LOG.debug("Cursor verification failure for transaction {}", CARD_LIST_TRANSACTION_ID,
                    unverifiable);
            throw ValidationException.invalidField(field,
                    "the paging cursor was not issued for the page it was presented with. Page again from a"
                            + " response, returning its firstCursor, lastCursor and pageNumber together");
        }
    }

    /**
     * Returns the seal kind for the cursors of one page.
     *
     * <p>One kind per page, because the kind is authenticated additional data: a token sealed under one kind
     * cannot open under another, which is what makes the page number and the cursor inseparable.
     *
     * @param page the page the cursor describes
     * @return the kind; never null and never blank
     */
    private static String cursorKindFor(final int page) {
        return CARD_LIST_CURSOR_KIND + CURSOR_KIND_PAGE_SEPARATOR + page;
    }

    /**
     * Seals one row's account identifier and card number into an opaque reference.
     *
     * <p><strong>Finding, severity High - remediated by this method and its opener.</strong> The list, the
     * detail and the update operations were individually correct and collectively unusable. The source's
     * list map carries the full card number in every row and a terminal operator typed {@code S} or
     * {@code U} beside one to reach the next screen; this projection masks it, which is right for an HTTP
     * surface and is now a labelled deviation rather than a silent one. But detail and update both require
     * all sixteen digits, and no operation ever disclosed them, so an API-only client could see a list and
     * go nowhere from it.
     *
     * <p>The reference restores the navigation without restoring the disclosure. It is the Rule 7
     * substitution for row selection - the {@code SELn} flag was screen state, and a stateless client needs
     * a value it can send back instead of a cursor position. What it is <em>not</em> is a second way to
     * identify a card: a client that holds the card number keeps using it, unchanged, and the two forms are
     * mutually exclusive on a request rather than merged.
     *
     * <p>Both components are written at their declared map widths, so the reference has a fixed length and
     * the opener splits it by offset rather than by scanning for a separator - which also means no value
     * inside it can be confused for a delimiter.
     *
     * <p>Side effects: none. Performs no input or output.
     *
     * @param accountId the row's account identifier, or null on a blank filler row
     * @param cardNumber the row's card number, or null on a blank filler row
     * @return the sealed reference, or null when either component is absent or blank - a row with no card
     * has nothing to navigate to, and a reference that cannot round trip must not be issued
     */
    private String sealCardHandle(final String accountId, final String cardNumber) {

        if (accountId == null || accountId.isBlank() || cardNumber == null || cardNumber.isBlank()) {
            return null;
        }

        final String payload = pad(accountId, HANDLE_ACCOUNT_ID_WIDTH)
                + pad(cardNumber, HANDLE_CARD_NUMBER_WIDTH);
        return this.snapshotTokenService.seal(CARD_HANDLE_KIND, CARD_HANDLE_RECORD_KEY, payload);
    }

    /**
     * Opens an opaque row reference, refusing a request that supplies a filter alongside it.
     *
     * <p>The two forms are mutually exclusive on purpose. A reference already determines both filters, so a
     * request carrying a reference <em>and</em> a filter is stating the same thing twice and is ambiguous
     * about which the caller meant when they differ - and silently preferring one would let a caller pair
     * somebody else's reference with their own account number.
     *
     * <p>Side effects: none. Performs no input or output.
     *
     * @param cardKey the raw reference, or null when the caller supplied filters instead
     * @param accountFilter the account filter as received, used only to detect the ambiguous shape
     * @param cardFilter the card filter as received, used only to detect the ambiguous shape
     * @return the recovered pair, or null when no reference was presented
     * @throws ValidationException with failure kind {@code INVALID} when a filter accompanies the reference,
     * or when the reference does not verify - a reference is server-issued, so one that fails to open was
     * altered, expired or minted for something else, and in every case the remedy is to read the list again
     */
    private CardHandle openCardHandle(final String cardKey, final String accountFilter,
            final String cardFilter) {

        if (cardKey == null || cardKey.isBlank()) {
            return null;
        }

        if (accountFilter != null || cardFilter != null) {
            throw ValidationException.invalidField(CARD_KEY_FIELD,
                    "cardKey supplies both the account number and the card number, so accountFilter and"
                            + " cardFilter must not accompany it. Send the reference alone, or send the two"
                            + " filters without it");
        }

        final String payload;
        try {
            payload = this.snapshotTokenService.open(cardKey, CARD_HANDLE_KIND, CARD_HANDLE_RECORD_KEY,
                    String.class);
        } catch (final ConcurrentUpdateException unverifiable) {
            LOG.warn("Refused transaction {} program {}: the row reference does not verify",
                    CARD_DETAIL_TRANSACTION_ID, CARD_DETAIL_PROGRAM);
            LOG.debug("Row reference verification failure for transaction {}",
                    CARD_DETAIL_TRANSACTION_ID, unverifiable);
            throw ValidationException.invalidField(CARD_KEY_FIELD,
                    "the row reference could not be verified. Read the card list again and use a reference"
                            + " from the current response");
        }

        if (payload == null || payload.length() != HANDLE_ACCOUNT_ID_WIDTH + HANDLE_CARD_NUMBER_WIDTH) {
            // Only reachable if a reference sealed by an incompatible version of this class were presented;
            // the seal itself guarantees the bytes are ours, not that they are the shape we now expect.
            LOG.warn("Refused transaction {} program {}: a verified row reference carried an unexpected shape",
                    CARD_DETAIL_TRANSACTION_ID, CARD_DETAIL_PROGRAM);
            throw ValidationException.invalidField(CARD_KEY_FIELD,
                    "the row reference could not be interpreted. Read the card list again and use a"
                            + " reference from the current response");
        }

        return new CardHandle(payload.substring(0, HANDLE_ACCOUNT_ID_WIDTH),
                payload.substring(HANDLE_ACCOUNT_ID_WIDTH));
    }

    /**
     * Right-pads a value to a declared map width, so a sealed reference has a fixed length.
     *
     * @param value the value to pad; must not be null
     * @param width the declared width
     * @return the value at exactly {@code width} characters, truncated if it was longer
     */
    private static String pad(final String value, final int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * The two identifiers a sealed row reference carries.
     *
     * <p>A private nested record rather than a type under {@code model.dto}: it never crosses the wire in
     * either direction - what crosses is the sealed string - so it is an implementation detail of this
     * class and publishing it would enlarge the documented surface for no caller's benefit.
     *
     * @param accountId the account identifier, at its declared width
     * @param cardNumber the card number, at its declared width
     */
    private record CardHandle(String accountId, String cardNumber) {
    }

    /**
     * Resolves the subject the as-displayed snapshot is bound to, from the authenticated principal and from
     * nothing else.
     *
     * <p>This is the transcription of {@code CDEMO-USER-ID} at {@code app/cpy/COCOM01Y.cpy:L25}: the token's
     * subject claim, never a request parameter, a body member or a session attribute, so a caller cannot
     * assert whose snapshot they hold. Both routes that call it are declared authenticated in
     * {@code SecurityConfig}, so an absent or anonymous principal here is a wiring defect rather than a
     * request a client made - and it is refused as such rather than being silently sealed under a placeholder,
     * which would make every snapshot transferable between callers.</p>
     *
     * @param authentication the principal the security filter chain resolved, possibly null when the request
     * bypassed the chain.
     * @return the principal name, never null and never blank
     * @throws FatalProcessingException when no usable principal reached the operation, carrying the
     * {@code app/cpy/CSMSG02Y.cpy} abend payload with this program as the culprit
     */
    private static String subjectOf(final Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            LOG.warn("Refused a card operation: no authenticated principal reached it, so the as-displayed"
                    + " snapshot could be bound to no subject. Only sign-on CC00 is unauthenticated.");
            throw new FatalProcessingException(ABEND_CODE, CARD_DETAIL_PROGRAM, SUBJECT_ABEND_REASON,
                    SUBJECT_ABEND_MESSAGE);
        }
        return authentication.getName();
    }

    /**
     * Resolves the administrator flag from the authenticated principal, and from nothing else.
     *
     * <p>The transcription of {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy:L26} with its two
     * condition names at {@code :L27} and {@code :L28}. It is read from the role authority the token
     * carries and never from a request parameter, a request body or a session, so a client cannot assert
     * its own user class. An absent principal resolves to non-administrator, which is the least-privilege
     * answer and is also the value the source itself moves in when it hands control on, at
     * {@code app/cbl/COCRDLIC.cbl:L1013} and {@code :L1040}. Authentication itself is enforced centrally in
     * {@code SecurityConfig}, so this method neither repeats nor contradicts it.</p>
     *
     * <p>Worth recording so that its absence from the response is not read as a loss: the card-list program
     * stores this flag into its commarea and <em>never tests it</em>. It is carried for fidelity, and it
     * changes no row, no attribute and no message on this transaction.</p>
     *
     * @param authentication the authenticated principal, or null when none reached the operation.
     * @return true when the principal carries the administrator authority
     */
    private static boolean isAdministrator(final Authentication authentication) {

        if (authentication == null) {
            return false;
        }
        final Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null) {
            return false;
        }
        for (final GrantedAuthority authority : authorities) {
            if (authority != null && ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The three actions the card list accepts, and the only three it accepts.
     *
     * <p>This is the controller-level action mapping that replaces the terminal attention identifier.
     * {@code app/cpy/CSSTRPFY.cpy} is a {@code PROCEDURE DIVISION} copybook whose paragraph
     * {@code YYYY-STORE-PFKEY.} at {@code :L17} evaluates {@code EIBAID} over a twenty-six arm
     * {@code EVALUATE TRUE} and stores the result into the action state of {@code app/cpy/CVCRD01Y.cpy};
     * it declares no data of its own, so it has no entity, no data transfer object and no import. What
     * replaces it is an <em>explicit, enumerated request parameter</em> - not a generic attention-identifier
     * string and not a reimplemented function-key dispatcher. An unrecognised token is refused by the
     * framework's enum conversion before this class is entered, which is what makes the accepted set
     * closed and the behaviour deterministic.</p>
     *
     * <p>Only the three arms {@code app/cbl/COCRDLIC.cbl} actually dispatches on are modelled. The
     * program's own validity gate at {@code :L370-L376} admits {@code ENTER}, {@code PF03}, {@code PF07}
     * and {@code PF08} and coerces anything else to {@code ENTER}; of those, {@code PF03} is the exit to
     * the main menu, which under transformation rule 7 is client-side URL navigation and therefore not an
     * operation of this resource group. Several fields of {@code app/cpy/CVCRD01Y.cpy} -
     * {@code CCARD-LAST-PROG}, {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG} and
     * {@code CCARD-FUNCTION} - are commented out in the source and have no counterpart at all;
     * {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are supplied by the transaction monitor, are
     * absent from this repository, and are imported by nothing.</p>
     *
     * <p>The token each constant carries is the form the card-list service parses, which is the literal
     * {@code PF} followed by the key digits. It is deliberately <em>not</em> the five-character
     * {@code PFK}-prefixed form the copybook stores internally: the service derives that form itself, and
     * feeding it in would leave the action unset and silently degrade every paged request to a
     * redisplay.</p>
     */
    public enum CardListAction {

        /**
         * Show the page addressed by the supplied cursor. The transcription of the enter key at
         * {@code app/cpy/CSSTRPFY.cpy:L22-L23}, which reaches the {@code WHEN OTHER} arm of
         * {@code 0000-MAIN.} at {@code app/cbl/COCRDLIC.cbl:L572-L582} whenever no row was selected -
         * and in a stateless request no row ever is, because row selection is replaced by navigation to
         * the detail and update operations. This is the default, so a client that supplies no action at
         * all gets the first page.
         */
        SUBMIT("ENTER"),

        /**
         * Page backward. The transcription of {@code WHEN CCARD-AID-PFK07 AND NOT CA-FIRST-PAGE} at
         * {@code app/cbl/COCRDLIC.cbl:L501-L513}, which restarts the browse from the saved first key and
         * reads backwards. On the first page the source instead reports "no previous pages" at
         * {@code :L901-L904}, and so does this action.
         */
        PAGE_BACKWARD("PF07"),

        /**
         * Page forward. The transcription of {@code WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS} at
         * {@code app/cbl/COCRDLIC.cbl:L486-L497}, which restarts the browse from the saved last key. With
         * no further record the source reports the informational message at {@code :L910-L916} and
         * returns the same page, and so does this action.
         */
        PAGE_FORWARD("PF08");

        /** The attention identifier this action presents to the card-list service. */
        private final String aidToken;

        /**
         * Binds a constant to the attention identifier the service parses.
         *
         * @param attentionIdentifier the token, {@code ENTER} or {@code PF} followed by the key digits
         */
        CardListAction(final String attentionIdentifier) {
            this.aidToken = attentionIdentifier;
        }

        /**
         * Returns the attention identifier for this action.
         *
         * @return the token the card-list service parses; never null and never blank
         */
        public String aidToken() {
            return this.aidToken;
        }
    }

    /**
     * Stamps the two properties every error body carries, and returns the same instance for chaining.
     *
     * <p>Called by each {@code @ExceptionHandler} above as the last thing it does to the body, so the
     * envelope cannot be omitted by accident.
     *
     * <p>What the envelope discloses and what it withholds is the {@code CWE-209} posture stated for the
     * whole package in {@code com.cardemo.controller}'s package documentation.
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
