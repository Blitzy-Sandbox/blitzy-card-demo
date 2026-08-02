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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.dto.PageResponse;
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
 *       {@code :L358} - {@code app/cbl/COCRDLIC.cbl}, 1,459 lines and 42 paragraph labels, painting
 *       mapset {@code COCRDLI}. Reached by {@link #listCards}.</li>
 *   <li>{@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347} fronting {@code PROGRAM(COCRDSLC)} at
 *       {@code :L348} - {@code app/cbl/COCRDSLC.cbl}, 887 lines and 37 paragraph labels, painting mapset
 *       {@code COCRDSL}. Reached by {@link #getCardDetail}.</li>
 *   <li>{@code CCUP} at {@code app/csd/CARDDEMO.CSD:L367}, described
 *       {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} at {@code :L368}, fronting
 *       {@code PROGRAM(COCRDUPC)} at {@code :L369} - {@code app/cbl/COCRDUPC.cbl}, 1,560 lines and 48
 *       paragraph labels, painting mapset {@code COCRDUP}. Reached by {@link #updateCard}.</li>
 * </ul>
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
 * {@code PageResponse.getPageSize()}. Authentication and authorisation are configured centrally in
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
 *   <tr><td>Update lost a race, or was unconfirmed</td><td>{@code ConcurrentUpdateException}</td>
 *       <td>409, or 428 when unconfirmed</td></tr>
 *   <tr><td>File not open, status {@code '35'}</td><td>{@code FileUnavailableException}</td><td>503</td></tr>
 *   <tr><td>I/O failure, the {@code '9x'} family</td><td>{@code FileAccessException}</td><td>500</td></tr>
 *   <tr><td>Unexpected status, abend 999</td><td>{@code FatalProcessingException}</td><td>500</td></tr>
 * </table>
 *
 * <p>To troubleshoot, start from the {@code correlationId} on the response and the matching log line: the
 * filter that supplies it also supplies {@code traceId} and {@code spanId}, and this class neither adds
 * to nor removes from that context. A 400 carries the rejected field name and the two-state failure kind,
 * which is what distinguishes a blank filter from an invalid one; a 409 carries the concurrency outcome;
 * a 500 from an I/O failure carries the four-character expanded file status. No response and no log line
 * ever carries a card number, a cardholder name or any other protected field - the diagnostics below name
 * fields, operations and files only, never their values.</p>
 *
 * <p>Two boundary conditions are worth stating because they are answers rather than faults. A page beyond
 * the last one, and an empty result set, both return {@code 200} with an empty row list and
 * {@code nextPageAvailable} false; the source behaves the same way, reporting "no more pages" as a screen
 * message rather than as an error. And a total element count and a total page count are <b>Not
 * available</b> by design: {@code PageResponse} declares neither, because the source never computes
 * either - its browse learns only whether one further record exists. What would be needed to supply them
 * is a counting query the legacy program never issues, which would be new behaviour rather than parity.</p>
 *
 * <h2>Findings and deviations, with severities</h2>
 *
 * <p>Every finding this class carries is classified and tracked; each is also recorded in
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
 * </ul>
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
     * The largest value {@code WS-CA-SCREEN-NUM} can hold, {@code app/cbl/COCRDLIC.cbl:L237}.
     *
     * <p>A single unsigned digit, so nine is its ceiling. The source's own increment wraps at that ceiling
     * rather than overflowing, and the wrap is preserved rather than repaired; what is refused here is a
     * value that was never representable in the field at all.</p>
     */
    private static final int HIGHEST_SCREEN_NUMBER = 9;

    /** The name reported for a page cursor outside the domain the source declares for it. */
    private static final String PAGE_FIELD = "page";

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

    /** Problem-detail member carrying the rejected field name. */
    private static final String FIELD_PROPERTY = "field";

    /** Problem-detail member carrying {@code INVALID} or {@code BLANK}, which keeps the two distinct. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /** Problem-detail member carrying the concurrency outcome, which keeps the five outcomes distinct. */
    private static final String OUTCOME_PROPERTY = "outcome";

    /** Problem-detail member carrying the four-character expanded file status. */
    private static final String IO_STATUS_PROPERTY = "ioStatus";

    /** Problem-detail member carrying the logical file or DD name whose I/O failed. */
    private static final String FILE_PROPERTY = "file";

    /** Problem-detail member carrying the attempted file operation. */
    private static final String OPERATION_PROPERTY = "operation";

    /** Problem-detail member carrying the legacy abend code, {@code 999}. */
    private static final String ABEND_CODE_PROPERTY = "abendCode";

    /** Problem-detail member carrying the legacy batch return code, {@code 12}. */
    private static final String RETURN_CODE_PROPERTY = "returnCode";

    /** The abend code as text, for the abend that carries none of its own. */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** Abend reason for an unexpected card-list failure. */
    private static final String CARD_LIST_ABEND_REASON = "CARD LIST RETRIEVAL FAILED UNEXPECTEDLY";

    /** Abend reason for an unexpected card-detail failure. */
    private static final String CARD_DETAIL_ABEND_REASON = "CARD DETAIL RETRIEVAL FAILED UNEXPECTEDLY";

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
     * @throws IllegalArgumentException if any service is null, which is a bean-wiring defect rather than a
     * request-time condition
     */
    public CardController(final CardListService cardListService,
            final CardDetailService cardDetailService,
            final CardUpdateService cardUpdateService) {

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
    }

    /**
     * Operation 1 of 3 - one page of cards. Replaces CICS transaction {@code CCLI}
     * ({@code app/csd/CARDDEMO.CSD:L357}) and the program it fronts, {@code app/cbl/COCRDLIC.cbl} (1,459
     * lines, 42 paragraph labels), which painted mapset {@code COCRDLI}.
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
     * <p><strong>Outputs.</strong> {@code 200} with a {@code PageResponse} of {@code CardDto.CardListRow}
     * carrying the rows in browse order - ascending card number, the key order of the {@code CARDDAT}
     * cluster, so the ordering is deterministic and repeatable for a given cursor. The page also carries
     * the page number, the page size in force, whether a further record exists, and the first and last
     * keys to page from. The next-page indicator is reported as a boolean: the corpus has two mutually
     * incompatible sentinels for it - {@code app/cbl/COCRDLIC.cbl:L243} tests {@code LOW-VALUES} while
     * {@code app/cbl/COTRN00C.cbl} uses {@code 'N'} - and neither byte is transported, because a JSON
     * contract cannot carry both and need carry neither. A total element count and a total page count are
     * <b>Not available</b>: {@code PageResponse} declares neither member, because the browse never counts
     * - it looks ahead exactly one record. Supplying them would need a counting query the source never
     * issues.</p>
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
     * the field and the failure kind. {@code 500} when the browse itself fails, as
     * {@code FileAccessException} carrying the expanded file status, or as an abend carrying code
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
     * @param action the navigation intent; defaults to {@link CardListAction#SUBMIT}. An unrecognised
     * token is refused by request-parameter conversion before this method is entered.
     * @param page the page number being echoed back, the transcription of {@code WS-CA-SCREEN-NUM PIC 9(1)}
     * at {@code app/cbl/COCRDLIC.cbl:L237}; zero on a first request, which is what a first entry sends.
     * @param firstKey the saved first key of the page displayed, used to page backward and to redisplay;
     * null on a first request. Protected data, never logged.
     * @param lastKey the saved last key of the page displayed, used to page forward; null on a first
     * request. Protected data, never logged.
     * @param nextPageAvailable whether the previous response reported a further record; false on a first
     * request. The page-forward action needs it, exactly as {@code :L945} needs
     * {@code CA-NEXT-PAGE-EXISTS}.
     * @param authentication the authenticated principal Spring Security resolved for this request, from
     * which alone the administrator flag is derived; may be null, and then resolves to non-administrator.
     * @return {@code 200 OK} with one page of card rows and the cursor for the adjacent page; never null
     * @throws ValidationException if the page cursor is outside the declared single-digit domain, or if a
     * filter is present and not numeric
     * @throws FatalProcessingException if the browse fails for any reason other than a typed CardDemo
     * failure
     */
    @GetMapping
    public ResponseEntity<PageResponse<CardDto.CardListRow>> listCards(
            @RequestParam(name = "accountFilter", required = false) final String accountFilter,
            @RequestParam(name = "cardFilter", required = false) final String cardFilter,
            @RequestParam(name = "action", defaultValue = "SUBMIT") final CardListAction action,
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "firstKey", required = false) final String firstKey,
            @RequestParam(name = "lastKey", required = false) final String lastKey,
            @RequestParam(name = "nextPageAvailable", defaultValue = "false") final boolean nextPageAvailable,
            final Authentication authentication) {

        requirePageWithinScreenNumberDomain(page);

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
                firstKey,
                lastKey,
                List.of(),
                null,
                null);

        final PageResponse<CardDto.CardListRow> listing = retrieveCardList(request);

        // Presence, never content: a filter value is an account identifier or a card number, and neither
        // may reach a log line. The keys and the rows are omitted for the same reason.
        LOG.debug("Served transaction {} program {} action {}: page={}, size={}, rows={}, nextPage={}, "
                + "accountFilterPresent={}, cardFilterPresent={}",
                CARD_LIST_TRANSACTION_ID, CARD_LIST_PROGRAM, action, listing.getPageNumber(),
                listing.getPageSize(), listing.getRows().size(), listing.isNextPageAvailable(),
                accountFilter != null, cardFilter != null);

        return ResponseEntity.ok(listing);
    }

    /**
     * Operation 2 of 3 - one card's details. Replaces CICS transaction {@code CCDL}
     * ({@code app/csd/CARDDEMO.CSD:L347}) and the program it fronts,
     * <strong>{@code app/cbl/COCRDSLC.cbl}</strong> (887 lines, 37 paragraph labels), which painted mapset
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
     * map must stay complete and mechanically checkable; it is cited in {@code DECISION_LOG.md} and
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
     * account identifier, the card number, the cardholder name, the active status and the expiry month and
     * year, each at the width its symbolic map declares. Expiry components are relayed as text and never
     * as a date type, because the map declares them as characters and parsing them would invent a
     * validation the map does not express.</p>
     *
     * <p><strong>Side effects.</strong> None; a single read.</p>
     *
     * <p><strong>Configuration and defaults.</strong> None. This operation reads no property; it takes no
     * default beyond the absence of both parameters, which is itself a refusal rather than a default.</p>
     *
     * <p><strong>Failure modes and troubleshooting.</strong> {@code 400} when either filter is absent,
     * blank or not the required number of digits - the response names the field and the failure kind, so a
     * fifteen or seventeen digit card number is distinguishable from an omitted one. {@code 404} when no
     * card carries that number, the translation of the not-found arm at {@code :L756}. {@code 500} when
     * the read fails, carrying the expanded file status, or as an abend carrying code {@code 999} and
     * return code {@code 12}. If a request that looks correct returns {@code 400}, check the account
     * filter first: the source validates it before the card filter and stops at the first refusal, so the
     * reported field is the earlier one.</p>
     *
     * @param accountFilter the account filter as typed, or null when the parameter was absent; relayed
     * verbatim, with absent, blank and populated kept distinct.
     * @param cardFilter the card filter as typed, or null when the parameter was absent; relayed verbatim
     * on the same terms. Protected data: never logged and never echoed into a diagnostic.
     * @return {@code 200 OK} with the card detail projection; never null
     * @throws ValidationException if either filter is absent, blank or not exactly the required number of
     * digits
     * @throws RecordNotFoundException if no card carries that number
     * @throws FatalProcessingException if the read fails for any reason other than a typed CardDemo
     * failure
     */
    @GetMapping(DETAIL_PATH)
    public ResponseEntity<CardDto> getCardDetail(
            @RequestParam(name = "accountFilter", required = false) final String accountFilter,
            @RequestParam(name = "cardFilter", required = false) final String cardFilter) {

        final CardDto detail = retrieveCardDetail(accountFilter, cardFilter);

        LOG.debug("Served transaction {} program {}: detail returned for one card",
                CARD_DETAIL_TRANSACTION_ID, CARD_DETAIL_PROGRAM);

        return ResponseEntity.ok(detail);
    }

    /**
     * Operation 3 of 3 - apply a card update. Replaces CICS transaction {@code CCUP}
     * ({@code app/csd/CARDDEMO.CSD:L367}, described {@code DESCRIPTION(CREDIT CARD UPDATE TRANSACTION)} at
     * {@code :L368}) and the program it fronts, {@code app/cbl/COCRDUPC.cbl} (1,560 lines, 48 paragraph
     * labels), which painted mapset {@code COCRDUP}.
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
     * have - plus the two snapshot groups {@code CCUP-OLD-DETAILS} and {@code CCUP-NEW-DETAILS} of
     * {@code app/cbl/COCRDUPC.cbl:L291-L313}. The old group is mandatory in substance: a stateless request
     * cannot keep the as-displayed values on the server between turns, so the client must return them, and
     * deriving them from the live row instead would make the comparison tautologically true and destroy
     * the guard. {@code @Valid} is applied so that the width constraints the record declares, and those of
     * its nested groups, are enforced before the service is entered.</p>
     *
     * <p><strong>Outputs.</strong> {@code 200} with the refreshed card detail projection, so a client sees
     * what was actually stored rather than what it sent.</p>
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
     * lock that could not be taken and from a write that failed after locking. {@code 428} when the
     * as-displayed snapshot was not supplied and the update therefore could not be confirmed: supply
     * {@code oldDetails} from a preceding detail read and retry. {@code 500} for an I/O failure or an
     * abend.</p>
     *
     * @param request the received map plus the as-displayed and as-edited snapshot groups; must not be
     * null, and is relayed to the service exactly as bound.
     * @return {@code 200 OK} with the refreshed card detail projection; never null
     * @throws ValidationException if an edit paragraph refuses a field
     * @throws RecordNotFoundException if the card row is absent
     * @throws ConcurrentUpdateException if the update was abandoned for any of the recorded outcomes
     * @throws FatalProcessingException if the update fails for any reason other than a typed CardDemo
     * failure
     */
    @PutMapping
    public ResponseEntity<CardDto> updateCard(@Valid @RequestBody final CardUpdateRequest request) {

        final CardDto updated = applyCardUpdate(request);

        LOG.debug("Served transaction {} program {}: one card row updated",
                CARD_UPDATE_TRANSACTION_ID, CARD_UPDATE_PROGRAM);

        return ResponseEntity.ok(updated);
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
     * named one, and the failure kind
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

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Maps an absent card or account onto {@code 404 Not Found}.
     *
     * <p>The translation of the {@code DFHRESP(NOTFND)} arm of {@code 9100-GETCARD-BYACCTCARD.} at
     * {@code app/cbl/COCRDSLC.cbl:L756} and of file status {@code '23'}. The message is relayed byte for
     * byte for the same reason as a validation refusal - it is a screen caption.</p>
     *
     * <p><strong>The record key is deliberately not reported.</strong> The exception can carry a record
     * type and a record key, but on this resource group the key is a card number, so the throwing sites use
     * the message-only form precisely so that none can be attached, and this handler reads neither. A
     * client that needs to know which card it asked about already knows: it supplied it.</p>
     *
     * @param absent the not-found failure raised by a card service, never null when Spring MVC dispatches
     * here.
     * @return {@code 404 Not Found} carrying a problem detail and no record key
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(final RecordNotFoundException absent) {

        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle(NOT_FOUND_PROBLEM_TITLE);

        final String absentMessage = absent.getMessage();
        if (absentMessage != null) {
            problem.setDetail(absentMessage);
        }

        LOG.warn("Refused a card request with 404: no matching record. No key is logged or returned");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Maps an abandoned card update onto {@code 409 Conflict}, or onto {@code 428 Precondition Required}
     * when the update could not be confirmed at all.
     *
     * <p>The five outcomes are kept distinct rather than merged, because the source distinguishes them and a
     * client can act on the difference. Four of them are genuine conflicts on a record that exists:
     * {@code COULD_NOT_LOCK_ACCOUNT} and {@code COULD_NOT_LOCK_CUSTOMER} are lock refusals,
     * {@code DATA_CHANGED_BEFORE_UPDATE} is the change-detection guard of
     * {@code 9300-CHECK-CHANGE-IN-REC} firing, and {@code LOCKED_BUT_UPDATE_FAILED} is a write that failed
     * after the lock was taken. All four answer {@code 409}, and the outcome is reported as a problem-detail
     * member so that they stay separable.</p>
     *
     * <p>{@code CHANGES_NOT_CONFIRMED} is different in kind and answers {@code 428}. It means the
     * as-displayed snapshot was not supplied, so the lost-update guard had nothing to compare against and
     * the write was never attempted - a missing precondition on the request rather than a conflict with
     * another writer. That is exactly the condition {@code 428} exists for, and the distinct status tells a
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
     * @return {@code 409 Conflict}, or {@code 428 Precondition Required} for an unconfirmed update, in
     * either case carrying the outcome when the exception recorded one
     */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(final ConcurrentUpdateException conflict) {

        final ConcurrentUpdateException.Outcome outcome = conflict.getOutcome();
        final HttpStatus status = outcome == ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED
                ? HttpStatus.PRECONDITION_REQUIRED
                : HttpStatus.CONFLICT;

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

        return ResponseEntity.status(status).body(problem);
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
     * @param unavailable the unavailable-file failure, never null when Spring MVC dispatches here.
     * @return {@code 503 Service Unavailable} carrying a fixed problem detail and the resource name when
     * the throwing site identified one
     */
    @ExceptionHandler(FileUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleFileUnavailable(final FileUnavailableException unavailable) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_PROBLEM_DETAIL);
        problem.setTitle(UNAVAILABLE_PROBLEM_TITLE);
        unavailable.resourceName().ifPresent(name -> problem.setProperty(FILE_PROPERTY, name));

        LOG.error("A card operation could not reach its file", unavailable);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    /**
     * Maps a card-file I/O failure onto {@code 500 Internal Server Error}.
     *
     * <p>The translation of the {@code '9x'} file-status family and of the {@code WHEN OTHER} arms of the
     * read paragraphs. The four-character expanded status is reported, because it is the diagnostic the
     * legacy status renderer produced and it identifies the failure without disclosing anything about the
     * data; the file and the operation are reported for the same reason. No key and no field value is
     * reported. The detail itself is fixed, and the cause is logged rather than returned.</p>
     *
     * @param failure the I/O failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying the expanded file status, and the file and
     * operation when the throwing site identified them
     */
    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccessFailure(final FileAccessException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);
        problem.setProperty(IO_STATUS_PROPERTY, failure.getExpandedStatus());

        final String logicalFileName = failure.getLogicalFileName();
        if (logicalFileName != null) {
            problem.setProperty(FILE_PROPERTY, logicalFileName);
        }
        final String operation = failure.getOperation();
        if (operation != null) {
            problem.setProperty(OPERATION_PROPERTY, operation);
        }

        LOG.error("A card file operation failed: status {} file {} operation {}",
                failure.getExpandedStatus(), logicalFileName, operation, failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Maps an abend onto {@code 500 Internal Server Error}, preserving the legacy abend contract in the
     * response metadata.
     *
     * <p>The abend routine moved {@code 999} into {@code ABEND-CODE} and called the language-environment
     * abend service, and the batch stream reported return code {@code 12}. Both values are carried as
     * problem-detail members so that the contract stays observable, while the detail itself is fixed and
     * reveals nothing about the cause. The cause remains attached to the exception and is logged at
     * {@code ERROR}, so diagnosis proceeds from the correlation identifier rather than from the response
     * body.</p>
     *
     * @param abend the fatal failure, never null when Spring MVC dispatches here.
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail plus the abend code and the
     * batch return code
     */
    @ExceptionHandler(FatalProcessingException.class)
    public ResponseEntity<ProblemDetail> handleAbend(final FatalProcessingException abend) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        final String carriedAbendCode = abend.getAbendCode();
        problem.setProperty(ABEND_CODE_PROPERTY, carriedAbendCode == null ? ABEND_CODE : carriedAbendCode);
        problem.setProperty(RETURN_CODE_PROPERTY, FatalProcessingException.BATCH_RETURN_CODE);

        LOG.error("A card operation abended: code {} culprit {} reason {}", carriedAbendCode,
                abend.getAbendCulprit(), abend.getAbendReason(), abend);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
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
     * @return {@code 500 Internal Server Error} carrying a fixed problem detail
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleTypedFailure(final CardDemoException failure) {

        final ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, FAILURE_PROBLEM_DETAIL);
        problem.setTitle(FAILURE_PROBLEM_TITLE);

        LOG.error("A card operation failed with a typed CardDemo exception", failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
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
     * <p>Only the paging payload is surfaced. The rest of the turn outcome is 3270 furniture with no place
     * on a JSON contract - the screen header and its two message lines, the seven row-selection and
     * highlight attribute flags, the four filter attribute flags, the cursor field, and the navigation
     * intent naming the program a transfer of control would have gone to. A paging payload is absent only
     * on such a transfer, which the three modelled actions cannot cause, so its absence here is a broken
     * service contract rather than a request outcome and is reported as an abend rather than as an empty
     * page.</p>
     *
     * @param request the rebuilt commarea halves and the received filters.
     * @return the paging payload, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure, or returns no paging payload
     */
    private PageResponse<CardDto.CardListRow> retrieveCardList(
            final CardListService.CardListRequest request) {

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
        return result.page;
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
     * Delegates to the card-update service exactly once and returns the refreshed card projection.
     *
     * <p>Extracted from {@link #updateCard} on the same terms as the other two delegates. The body is
     * handed over exactly as bound - no field is trimmed, case-converted, defaulted or reordered on the way
     * through - because the change detection compares the two snapshot groups as they arrive and any
     * normalisation here would change its verdict.</p>
     *
     * @param request the received map plus the two snapshot groups, relayed verbatim.
     * @return the refreshed card projection, never null
     * @throws FatalProcessingException when the service fails for any reason other than a typed CardDemo
     * failure
     */
    private CardDto applyCardUpdate(final CardUpdateRequest request) {

        try {
            return this.cardUpdateService.updateCard(request);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw new FatalProcessingException(ABEND_CODE, CARD_UPDATE_PROGRAM, CARD_UPDATE_ABEND_REASON,
                    CARD_UPDATE_ABEND_MESSAGE, unexpected);
        }
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
}
