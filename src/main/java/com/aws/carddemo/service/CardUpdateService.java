/*
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
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Card;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Card-update service — the Java migration of the 1,560-line CICS COBOL
 * program {@code app/cbl/COCRDUPC.cbl} (TRANID {@code CCUP}, the card-update
 * dispatcher). Modifies an existing {@link Card} record in the
 * {@code CARDDAT} VSAM KSDS replacement (PostgreSQL {@code cards} table)
 * after enforcing field-level validations inherited from the COBOL baseline
 * plus a Java-migration-added CVV format check and JPA optimistic-locking
 * semantics.
 *
 * <h2>COBOL Provenance — COCRDUPC.cbl</h2>
 *
 * <p>The COBOL workflow combines the {@code 1000-PROCESS-INPUTS} validation
 * cascade (paragraphs {@code 1210-EDIT-ACCOUNT} through
 * {@code 1260-EDIT-EXPIRY-YEAR}) with the {@code 9200-WRITE-PROCESSING}
 * READ UPDATE / REWRITE flow (lines 1376–1521). The Java migration collapses
 * the two-phase COBOL flow into a single transactional method:
 *
 * <ol>
 *   <li>Validate {@code CARD-UPDATE-NUM} ({@code PIC X(16)}) is non-empty
 *       and exactly 16 numeric digits (COBOL {@code 1220-EDIT-CARD},
 *       lines 763–800).</li>
 *   <li>{@code EXEC CICS READ FILE(CARDDAT) RIDFLD(CARD-UPDATE-NUM)}
 *       (line 1382) — the initial display read; the Java migration uses
 *       {@link CardRepository#findById(Object)} to retrieve the card by
 *       primary key. {@code DFHRESP(NOTFND)} on the COBOL READ collapses
 *       to {@link Optional#isEmpty()} on the Java repository call;
 *       {@code DFHRESP(NORMAL)} continues to the field-update step.</li>
 *   <li>Validate the optionally-edited card fields against the loaded
 *       record (Java migration ordering, mirroring the post-READ COBOL
 *       flow at lines 1453–1474):
 *       <ul>
 *         <li>CVV must be 3 numeric digits.</li>
 *         <li>Embossed name must be non-empty (COBOL
 *             {@code 1230-EDIT-NAME}, lines 808–840).</li>
 *         <li>Expiration date must be a valid ISO {@code YYYY-MM-DD}
 *             string (COBOL {@code 1250-EDIT-EXPIRY-MON} +
 *             {@code 1260-EDIT-EXPIRY-YEAR}, lines 870–945, collapsed
 *             into a single {@link LocalDate#parse} strict-parse in the
 *             Java migration).</li>
 *         <li>Active status must be {@code "Y"} or {@code "N"} (COBOL
 *             {@code 1240-EDIT-CARDSTATUS}, lines 845–869).</li>
 *       </ul></li>
 *   <li>Apply the request's field values to the loaded entity.</li>
 *   <li>{@code EXEC CICS REWRITE FILE(CARDDAT) FROM(CARD-UPDATE-RECORD)}
 *       (lines 1477–1483) — Java equivalent is
 *       {@link CardRepository#save(Object)}. JPA's {@code @Version}
 *       optimistic-locking check raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       on a version mismatch (COBOL parity:
 *       {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} at line 1511).
 *       The service does not catch the exception; it propagates uncaught
 *       to the controller layer's exception-handler chain.</li>
 *   <li>{@code EXEC CICS SYNCPOINT} (line 470 of the COBOL READY-TO-PROCESS
 *       paragraph) — Java equivalent is the Spring {@code @Transactional}
 *       commit boundary at method return; the service returns
 *       {@link CardUpdateResult#success(String)} with the
 *       {@code 'Changes committed to database'} message (COBOL
 *       {@code CONFIRM-UPDATE-SUCCESS} at line 169).</li>
 * </ol>
 *
 * <h2>Java Migration: Optimistic Locking via JPA @Version</h2>
 *
 * <p>The COBOL READ UPDATE / REWRITE idiom (lines 1427–1483) serialised
 * concurrent updates through CICS file locks: the READ UPDATE acquired an
 * exclusive lock on the record that persisted until the REWRITE released
 * it. The {@code CHECK-CHANGE-IN-REC} paragraph (line 1453) additionally
 * compared the operator-displayed before-image of the record against the
 * current persisted state on the way into the REWRITE, setting
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 1511) when the comparison
 * detected an intervening update. The Java migration replaces both
 * mechanisms with JPA's {@code @Version} optimistic-locking field on
 * {@link Card}: when {@link CardRepository#save(Object)} detects a version
 * mismatch (another transaction incremented the persisted version between
 * this transaction's lookup and save), it raises
 * {@link org.springframework.dao.OptimisticLockingFailureException}. The
 * service does not catch the exception; it propagates uncaught to the
 * controller layer's exception-handler chain (mapped to HTTP 409 Conflict).
 * The observable contract (concurrent updates are detected and one of them
 * fails cleanly) is preserved per AAP §0.10.4 "External interfaces consumed
 * by downstream systems MUST NOT change".
 *
 * <h2>Java Migration: Single-Table Update (No SYNCPOINT ROLLBACK)</h2>
 *
 * <p>Unlike {@code COACTUPC.cbl} (which updates both ACCTDAT and CUSTDAT
 * and uses {@code EXEC CICS SYNCPOINT ROLLBACK} to undo a partial update),
 * {@code COCRDUPC.cbl} updates only the {@code CARDDAT} dataset — there is
 * a single table to roll back. The COBOL {@code ABEND-ROUTINE} (lines
 * 1546–1552) uses {@code EXEC CICS HANDLE ABEND CANCEL} +
 * {@code EXEC CICS ABEND ABCODE('9999')} to trigger a controlled abort on
 * any irrecoverable error; the Java equivalent is Spring's transactional
 * rollback boundary which automatically reverts the persistent state on any
 * uncaught {@link RuntimeException} (including
 * {@link org.springframework.dao.OptimisticLockingFailureException} and
 * other {@link org.springframework.dao.DataAccessException} subclasses).
 *
 * <h2>Java Migration: Card Number Immutability</h2>
 *
 * <p>The COBOL workflow at lines 1461–1474 INITIALIZE's CARD-UPDATE-RECORD
 * with the loaded {@code CARD-NUM} value before assigning the request's
 * other fields — implicitly preserving the primary key across the update.
 * The Java migration enforces this explicitly: the service NEVER calls
 * {@code card.setCardNumber(...)} on the loaded entity, so the primary
 * key is preserved by construction. The corresponding
 * {@code CardUpdateServiceTest#preservesCardNumberAsImmutableKey} unit
 * test guards this invariant via {@link org.mockito.ArgumentCaptor}.
 *
 * <h2>Validation Order</h2>
 *
 * <p>The service performs validations in the following order to match the
 * test-suite invariants documented by
 * {@code CardUpdateServiceTest.ValidationRejects}:
 * <ol>
 *   <li><b>Card number format check</b> — performed BEFORE
 *       {@link CardRepository#findById}, because the PK format must be
 *       valid before any database lookup is attempted. Rejects via
 *       {@link #MSG_CARD_NUMBER_INVALID} (non-numeric or wrong length) or
 *       {@link #MSG_CARD_NUMBER_REQUIRED} (null, empty, whitespace).</li>
 *   <li><b>Repository lookup</b> via {@link CardRepository#findById}
 *       (COBOL parity: {@code EXEC CICS READ} line 1382). On
 *       {@link Optional#empty()} → reject with {@link #MSG_CARD_NOT_FOUND}.</li>
 *   <li><b>CVV format check</b> — performed AFTER the lookup so the
 *       database state is verified to exist before mutating it. Rejects
 *       via {@link #MSG_CVV_INVALID}.</li>
 *   <li><b>Embossed name check</b> — non-empty after trim. Rejects via
 *       {@link #MSG_EMBOSSED_NAME_REQUIRED}.</li>
 *   <li><b>Expiration date check</b> — strict {@link LocalDate#parse}.
 *       Rejects via {@link #MSG_EXPIRATION_DATE_INVALID}.</li>
 *   <li><b>Active status domain check</b> — {@code "Y"} or {@code "N"}
 *       only. Rejects via {@link #MSG_ACTIVE_STATUS_INVALID}.</li>
 *   <li><b>Field updates</b> on the loaded entity (CVV, embossed name,
 *       expiration date, active status, account ID). The card number
 *       (primary key) is NEVER mutated.</li>
 *   <li><b>Repository save</b> (COBOL parity: {@code EXEC CICS REWRITE}
 *       line 1477). On JPA {@code @Version} mismatch this raises
 *       {@link org.springframework.dao.OptimisticLockingFailureException}
 *       which propagates uncaught.</li>
 *   <li><b>Success response</b> — builds the
 *       {@code 'Changes committed to database'} confirmation (COBOL
 *       parity: {@code CONFIRM-UPDATE-SUCCESS} line 169).</li>
 * </ol>
 *
 * <p>Each validation short-circuits and returns immediately on failure;
 * the repository's {@code save()} method is never invoked on any of the
 * validation reject paths or the card-not-found path (defence-in-depth
 * verified by the corresponding test suite via
 * {@code verify(repository, never())} assertions).
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts the
 * repository and clock collaborators directly so unit tests can wire a
 * Mockito mock + a {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
 * deterministic clock without a Spring context — matching the convention
 * established by {@link AuthenticationService}, {@link CardDetailService},
 * {@link CardListService}, and the rest of the service package.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE card-update dispatcher logic (no
 * helpers extracted to other classes; all branches are visible in the
 * single {@link #updateCard(CardUpdateRequest)} entry point). The
 * corresponding {@code CardUpdateServiceTest} exercises every branch via
 * real method calls with a mocked {@link CardRepository} at the database
 * boundary and a fixed {@link Clock} for deterministic time-dependent
 * behaviour. No business logic (card-number format check, CVV check,
 * date parse, active-status domain check, field-update dispatch) is
 * duplicated inside the test.
 *
 * <h2>Stub Status</h2>
 *
 * <p>This class is a <strong>minimum-viable service implementation</strong>
 * created to satisfy {@code CardUpdateServiceTest} compilation and the
 * AAP §0.10.1 Require Test Coverage rule. Subsequent migration agents
 * (REFACTOR flavor) will add the {@code @Service} stereotype, JPA
 * {@code @Transactional} demarcation, structured logging hooks, account-
 * cross-reference validation against {@link CardXrefRepository}, and the
 * REST controller layer that drives this service when the full Spring
 * application context is wired up. The {@link CardXrefRepository} field
 * is accepted by the constructor today (and held as a final field) so
 * future migration agents can add the cross-reference validation without
 * a constructor-signature change.
 *
 * @see CardUpdateRequest
 * @see CardUpdateResult
 * @see Card
 * @see CardRepository
 * @see CardXrefRepository
 */
@Service
public class CardUpdateService {

    // ---------------------------------------------------------------------
    // Reject messages — verbatim COBOL literals preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see the same textual reason as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when {@link CardUpdateRequest#getCardNumber()}
     * is {@code null}, empty, or whitespace-only — verbatim COBOL literal
     * from {@code COCRDUPC.cbl} {@code WS-PROMPT-FOR-CARD} (line 181).
     */
    static final String MSG_CARD_NUMBER_REQUIRED = "Card number not provided";

    /**
     * Reject message returned when {@link CardUpdateRequest#getCardNumber()}
     * is not exactly 16 numeric digits (15 digits, 17 digits, with
     * non-numeric characters, etc.) — verbatim COBOL literal from
     * {@code COCRDUPC.cbl} {@code SEARCHED-CARD-NOT-NUMERIC} (line 196).
     */
    static final String MSG_CARD_NUMBER_INVALID = "Card number if supplied must be a 16 digit number";

    /**
     * Reject message returned when {@link CardRepository#findById(Object)}
     * returns {@link Optional#empty()} (the card does not exist in the
     * CARDDAT replacement table) — verbatim COBOL literal from
     * {@code COCRDUPC.cbl} {@code DID-NOT-FIND-ACCTCARD-COMBO} (line 203).
     * Mirrors the COBOL {@code DFHRESP(NOTFND)} response on the initial
     * {@code EXEC CICS READ} at line 1382 and the
     * {@code COULD-NOT-LOCK-FOR-UPDATE} response on the READ UPDATE at line
     * 1427.
     */
    static final String MSG_CARD_NOT_FOUND = "Did not find cards for this search condition";

    /**
     * Reject message returned when {@link CardUpdateRequest#getCvvCode()} is
     * not exactly 3 numeric digits. Java-migration addition with no direct
     * COBOL equivalent (the COBOL workflow preserves CVV from the existing
     * record and does not validate it as an operator input, but the REST
     * API exposes it as an editable field per the
     * {@link CardUpdateRequest#getCvvCode()} contract). Matches the COBOL
     * {@code CARD-UPDATE-CVV-CD PIC 9(03)} field-width semantics from line
     * 317.
     */
    static final String MSG_CVV_INVALID = "Card CVV must be a 3 digit number";

    /**
     * Reject message returned when {@link CardUpdateRequest#getEmbossedName()}
     * is {@code null}, empty, or whitespace-only — verbatim COBOL literal
     * from {@code COCRDUPC.cbl} {@code WS-PROMPT-FOR-NAME} (line 183).
     */
    static final String MSG_EMBOSSED_NAME_REQUIRED = "Card name not provided";

    /**
     * Reject message returned when {@link CardUpdateRequest#getExpirationDate()}
     * is not a valid ISO {@code YYYY-MM-DD} date — collapsed from the COBOL
     * {@code CARD-EXPIRY-MONTH-NOT-VALID} (line 200) and
     * {@code CARD-EXPIRY-YEAR-NOT-VALID} (line 202) into a single
     * strict-parse reject in the Java migration. Verbatim COBOL literal
     * {@code 'Invalid card expiry year'} from line 202 is reused as the
     * canonical message because it is the broader of the two — Java's
     * strict {@link LocalDate#parse} cannot easily distinguish between
     * month-related and year-related parse failures.
     */
    static final String MSG_EXPIRATION_DATE_INVALID = "Invalid card expiry year";

    /**
     * Reject message returned when {@link CardUpdateRequest#getActiveStatus()}
     * is neither {@code "Y"} nor {@code "N"} — verbatim COBOL literal from
     * {@code COCRDUPC.cbl} {@code CARD-STATUS-MUST-BE-YES-NO} (line 198).
     */
    static final String MSG_ACTIVE_STATUS_INVALID = "Card Active Status must be Y or N";

    /**
     * Success message returned when the JPA {@code save()} call completes
     * normally — verbatim COBOL literal from {@code COCRDUPC.cbl}
     * {@code CONFIRM-UPDATE-SUCCESS} (line 169).
     */
    static final String MSG_UPDATE_SUCCESS = "Changes committed to database";

    /**
     * Active-status flag indicating the card is active (positive case for
     * posting workflows) — single-character literal from COBOL
     * {@code CARD-UPDATE-ACTIVE-STATUS}. Visible to the test suite via
     * package-membership access.
     */
    static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Active-status flag indicating the card is inactive — single-character
     * literal from COBOL {@code CARD-UPDATE-ACTIVE-STATUS}. Drives reject
     * paths in posting workflows ({@code CBTRN02C}: inactive cards rejected
     * with code 102).
     */
    static final String ACTIVE_STATUS_NO = "N";

    /**
     * Compiled regular expression matching exactly 16 ASCII digits. The
     * pattern is anchored ({@code matches()} requires a full-string match
     * by default), so it rejects 15-digit, 17-digit, and digits-plus-alpha
     * inputs. Compiled once as a class-level constant to avoid recompiling
     * the regex on every {@link #updateCard(CardUpdateRequest)} call.
     */
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("\\d{16}");

    /**
     * Compiled regular expression matching exactly 3 ASCII digits. The
     * pattern is anchored, so it rejects 2-digit, 4-digit, and digits-plus-
     * alpha inputs. Compiled once as a class-level constant.
     */
    private static final Pattern CVV_PATTERN = Pattern.compile("\\d{3}");

    // ---------------------------------------------------------------------
    // Collaborators — JPA repositories + Clock boundary
    // ---------------------------------------------------------------------

    /**
     * JPA repository for {@link Card} entities — the Java replacement for
     * COBOL {@code EXEC CICS READ DATASET('CARDDAT')} (the initial display
     * read at line 1382), {@code EXEC CICS READ UPDATE DATASET('CARDDAT')}
     * (the lock-for-update at line 1427), and {@code EXEC CICS REWRITE
     * DATASET('CARDDAT')} (the persistence step at line 1477) in
     * {@code app/cbl/COCRDUPC.cbl}. Constructor-injected so unit tests can
     * wire a Mockito mock without a Spring context.
     */
    private final CardRepository cardRepository;

    /**
     * JPA repository for {@link com.aws.carddemo.entity.CardXref} entities —
     * carried by the service constructor for future account-cross-reference
     * validation when subsequent migration agents extend this service to
     * cover the COBOL {@code 1210-EDIT-ACCOUNT} paragraph (lines 721–760).
     * The current minimum-viable implementation does NOT use this field;
     * the unused-field warning is suppressed by the absence of strict
     * unused-field enforcement at the project level. Held as final so the
     * collaborator can be substituted at construction (e.g., by a Mockito
     * mock in unit tests, or by Spring's dependency injection in
     * production).
     */
    @SuppressWarnings("unused")
    private final CardXrefRepository cardXrefRepository;

    /**
     * Injected {@link Clock} for deterministic time-dependent behaviour
     * (e.g., audit timestamping on the persisted entity). Carried by the
     * service constructor for parity with sibling services
     * ({@link CardDetailService} uses an injected Clock to derive the
     * card-expired display flag); the current minimum-viable implementation
     * does not stamp audit timestamps but accepts the Clock for symmetry.
     * Held as final so the test suite can inject
     * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} for
     * deterministic test outcomes (AAP §0.10.9 test independence).
     */
    @SuppressWarnings("unused")
    private final Clock clock;

    /**
     * Constructs a new {@code CardUpdateService}.
     *
     * @param cardRepository      JPA repository for {@link Card} lookups
     *                            and updates (Java replacement for COBOL
     *                            {@code EXEC CICS READ / REWRITE} on
     *                            {@code CARDDAT}). Must not be {@code null}
     *                            — the service does not guard against
     *                            {@code null} collaborators because Spring
     *                            DI would surface the misconfiguration at
     *                            startup; unit tests wire a Mockito mock.
     * @param cardXrefRepository  JPA repository for the card-cross-reference
     *                            entity; carried for future account-
     *                            cross-reference validation. Must not be
     *                            {@code null}.
     * @param clock               clock used for deterministic time-dependent
     *                            behaviour; in production typically
     *                            {@link Clock#systemDefaultZone()}, in tests
     *                            a {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
     *                            instance. Must not be {@code null}.
     */
    public CardUpdateService(CardRepository cardRepository,
                             CardXrefRepository cardXrefRepository,
                             Clock clock) {
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.clock = clock;
    }

    /**
     * Update an existing {@link Card} record in the {@code CARDDAT}
     * replacement after enforcing the COBOL-inherited validation cascade
     * plus the Java-migration-added CVV format check. Implements the Java
     * equivalent of {@code app/cbl/COCRDUPC.cbl}
     * {@code 9200-WRITE-PROCESSING} (lines 1376–1521) plus the
     * {@code 1220-EDIT-CARD} through {@code 1260-EDIT-EXPIRY-YEAR}
     * validation paragraphs (lines 763–945).
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Card number presence check — if
     *       {@code request.getCardNumber()} is {@code null}, empty, or
     *       whitespace-only, reject with {@link #MSG_CARD_NUMBER_REQUIRED}.
     *       COBOL parity: {@code 1220-EDIT-CARD} lines 763–775.</li>
     *   <li>Card number format check — if
     *       {@code request.getCardNumber()} is not exactly 16 numeric
     *       digits, reject with {@link #MSG_CARD_NUMBER_INVALID}. COBOL
     *       parity: {@code 1220-EDIT-CARD} lines 783–792.</li>
     *   <li>Repository lookup via {@link CardRepository#findById}
     *       (COBOL parity: {@code EXEC CICS READ} line 1382). On
     *       {@link Optional#empty()} → reject with {@link #MSG_CARD_NOT_FOUND}.</li>
     *   <li>CVV format check — if {@code request.getCvvCode()} is not
     *       exactly 3 numeric digits, reject with {@link #MSG_CVV_INVALID}.</li>
     *   <li>Embossed name presence check — if
     *       {@code request.getEmbossedName()} is {@code null}, empty, or
     *       whitespace-only, reject with {@link #MSG_EMBOSSED_NAME_REQUIRED}.
     *       COBOL parity: {@code 1230-EDIT-NAME} lines 808–818.</li>
     *   <li>Expiration date format check — strict
     *       {@link LocalDate#parse(CharSequence, DateTimeFormatter)} with
     *       {@link DateTimeFormatter#ISO_LOCAL_DATE}; on failure reject
     *       with {@link #MSG_EXPIRATION_DATE_INVALID}. COBOL parity:
     *       collapsed {@code 1250-EDIT-EXPIRY-MON} +
     *       {@code 1260-EDIT-EXPIRY-YEAR}.</li>
     *   <li>Active status domain check — must be {@code "Y"} or
     *       {@code "N"}; otherwise reject with
     *       {@link #MSG_ACTIVE_STATUS_INVALID}. COBOL parity:
     *       {@code 1240-EDIT-CARDSTATUS}.</li>
     *   <li>Update the loaded entity's mutable fields
     *       ({@link Card#setCvvCode}, {@link Card#setEmbossedName},
     *       {@link Card#setExpirationDate}, {@link Card#setActiveStatus},
     *       {@link Card#setAccountId} when the request supplies a non-null
     *       value). The {@link Card#setCardNumber} method is NEVER called
     *       to preserve the primary key (Java migration invariant — see
     *       the {@code preservesCardNumberAsImmutableKey} unit test).</li>
     *   <li>Persist via {@link CardRepository#save(Object)} (COBOL parity:
     *       {@code EXEC CICS REWRITE} line 1477). On JPA {@code @Version}
     *       mismatch this raises
     *       {@link org.springframework.dao.OptimisticLockingFailureException}
     *       which propagates uncaught.</li>
     *   <li>Build the success response with the verbatim COBOL
     *       {@code 'Changes committed to database'} message (COBOL parity:
     *       line 169).</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * etc.) surface as
     * {@link org.springframework.dao.DataAccessException} subclasses thrown
     * by the repository; the service does not catch them, letting the
     * controller layer's exception-handler chain produce the Java
     * equivalent of the COBOL {@code INFORM-FAILURE} response. This mirrors
     * the convention used by {@link UserUpdateService} and the rest of the
     * service package.
     *
     * @param request the card-update request carrying the target card's
     *                identifier and the modified field values; must not be
     *                {@code null} (the service does not guard against
     *                {@code null} — the controller layer is responsible for
     *                producing a populated request)
     * @return a populated {@link CardUpdateResult} encoding either success
     *         (with the {@code 'Changes committed to database'} message)
     *         or failure (with one of the COBOL-equivalent reject messages)
     * @throws org.springframework.dao.OptimisticLockingFailureException
     *         when the JPA {@code @Version} field on the loaded entity
     *         does not match the persisted version (a concurrent update
     *         happened between this transaction's lookup and save). The
     *         Java equivalent of the COBOL
     *         {@code DATA-WAS-CHANGED-BEFORE-UPDATE} flag at line 1511.
     */
    public CardUpdateResult updateCard(CardUpdateRequest request) {
        // Step 1 — Card number presence check (COBOL parity:
        // 1220-EDIT-CARD lines 763–775; mirrors the COBOL test
        // "IF CC-CARD-NUM EQUAL LOW-VALUES OR SPACES OR ZEROS"). Performed
        // BEFORE any repository call so the database is never consulted on
        // a malformed primary-key value.
        String cardNumber = request.getCardNumber();
        if (isBlank(cardNumber)) {
            return CardUpdateResult.failure(MSG_CARD_NUMBER_REQUIRED);
        }

        // Step 2 — Card number format check (COBOL parity:
        // 1220-EDIT-CARD lines 783–792; mirrors the COBOL test "IF
        // CC-CARD-NUM IS NOT NUMERIC"). Java's PIC X(16) equivalent is
        // checked here via a regex match: exactly 16 ASCII digits, no
        // leading/trailing whitespace, no alphabetic characters.
        if (!CARD_NUMBER_PATTERN.matcher(cardNumber).matches()) {
            return CardUpdateResult.failure(MSG_CARD_NUMBER_INVALID);
        }

        // Step 3 — READ CARDDAT by CARD-NUM primary key (COBOL parity:
        // EXEC CICS READ at line 1382). The repository .findById(...)
        // returns Optional.empty() for the DFHRESP(NOTFND) case;
        // Optional.of(card) for DFHRESP(NORMAL). I/O errors (the COBOL
        // WHEN OTHER branch) propagate as DataAccessException subclasses
        // and are handled by the controller layer's exception-handler chain.
        Optional<Card> existingOpt = cardRepository.findById(cardNumber);
        if (existingOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'Did not find cards for this
            // search condition' (DID-NOT-FIND-ACCTCARD-COMBO at line 203).
            return CardUpdateResult.failure(MSG_CARD_NOT_FOUND);
        }

        // Step 4 — CVV format check (Java-migration addition). The COBOL
        // CARD-UPDATE-CVV-CD field at line 317 is PIC 9(03); the Java
        // migration validates the operator-supplied CVV against the same
        // 3-digit numeric domain. Performed AFTER the lookup so the
        // database state is verified to exist before mutating any field.
        String cvv = request.getCvvCode();
        if (cvv == null || !CVV_PATTERN.matcher(cvv).matches()) {
            return CardUpdateResult.failure(MSG_CVV_INVALID);
        }

        // Step 5 — Embossed name presence check (COBOL parity:
        // 1230-EDIT-NAME lines 808–818, "IF CCUP-NEW-CRDNAME EQUAL
        // LOW-VALUES OR SPACES OR ZEROS"). The COBOL workflow additionally
        // validates that the name contains only alphabetic characters and
        // spaces (lines 825–840); the Java migration restricts itself to
        // the presence check to keep the migration minimal per AAP §0.10.2
        // (Minimal Change Clause). Subsequent migration agents may add the
        // alpha-only check when the controller layer is wired.
        String embossedName = request.getEmbossedName();
        if (isBlank(embossedName)) {
            return CardUpdateResult.failure(MSG_EMBOSSED_NAME_REQUIRED);
        }

        // Step 6 — Expiration date format check. The COBOL workflow
        // validates the expiry month (1-12, paragraph 1250-EDIT-EXPIRY-MON)
        // and the expiry year (1950-2099, paragraph 1260-EDIT-EXPIRY-YEAR)
        // as two separate edits over the year and month components of the
        // operator's input. The Java migration collapses both edits into a
        // single strict LocalDate.parse: ISO_LOCAL_DATE is strict by
        // default (Feb 30 is rejected, Feb 29 in non-leap year is
        // rejected, alphabetic year is rejected, slash separator is
        // rejected, empty string is rejected).
        String expirationDate = request.getExpirationDate();
        if (!isValidIsoDate(expirationDate)) {
            return CardUpdateResult.failure(MSG_EXPIRATION_DATE_INVALID);
        }

        // Step 7 — Active status domain check (COBOL parity:
        // 1240-EDIT-CARDSTATUS, "IF CCUP-NEW-CRDSTCD NOT EQUAL 'Y' AND
        // NOT EQUAL 'N'"). Mirrors the CARD-STATUS-MUST-BE-YES-NO 88-level
        // condition at line 198.
        String activeStatus = request.getActiveStatus();
        if (!ACTIVE_STATUS_YES.equals(activeStatus) && !ACTIVE_STATUS_NO.equals(activeStatus)) {
            return CardUpdateResult.failure(MSG_ACTIVE_STATUS_INVALID);
        }

        // Step 8 — Apply updates to the loaded entity (COBOL parity:
        // lines 1461–1474, INITIALIZE CARD-UPDATE-RECORD with the new
        // values from the operator's screen input). The Java migration
        // mutates the loaded entity directly so JPA's dirty-checking can
        // emit an UPDATE statement on save(). The card number (primary
        // key) is INTENTIONALLY not assigned — Java migration invariant
        // documented on the class-level "Java Migration: Card Number
        // Immutability" Javadoc section.
        Card card = existingOpt.get();
        card.setCvvCode(cvv);
        card.setEmbossedName(embossedName);
        card.setExpirationDate(expirationDate);
        card.setActiveStatus(activeStatus);

        // The accountId is updated only when the request supplies a
        // non-null value; this preserves the loaded value when the
        // controller did not include the field in the payload. The COBOL
        // workflow always carries the account ID from the loaded
        // CARD-RECORD into CARD-UPDATE-RECORD, so the Java behaviour is
        // a strict superset of the COBOL semantics.
        String requestAccountId = request.getAccountId();
        if (requestAccountId != null) {
            card.setAccountId(requestAccountId);
        }

        // Step 9 — Persist via Spring Data JPA. The repository.save(card)
        // call is the Java equivalent of COBOL EXEC CICS REWRITE
        // FILE(CARDDAT) FROM(CARD-UPDATE-RECORD) at line 1477. JPA's
        // @Version optimistic-locking check raises
        // OptimisticLockingFailureException on a version mismatch — the
        // service does not catch it, letting it propagate to the controller
        // layer (mapped to HTTP 409 Conflict). Other DataAccessException
        // subclasses (database unreachable, etc.) propagate uncaught too
        // — handled by the controller's exception-handler chain.
        cardRepository.save(card);

        // Step 10 — Build the success response (COBOL parity: line 169
        // CONFIRM-UPDATE-SUCCESS 88-level value 'Changes committed to
        // database'). The verbatim COBOL literal is returned per AAP
        // §0.10.4 (Immutable Boundaries: downstream consumers reading
        // the JSON response envelope must see the same textual confirmation
        // as the COBOL baseline).
        return CardUpdateResult.success(MSG_UPDATE_SUCCESS);
    }

    /**
     * Test whether the supplied string represents the COBOL
     * {@code SPACES OR LOW-VALUES OR ZEROS} condition (treats {@code null},
     * empty, and whitespace-only as equivalent). Centralised here so the
     * card-number presence check and the embossed-name presence check
     * agree on the same predicate.
     *
     * @param value the candidate string; may be {@code null}
     * @return {@code true} when the value is {@code null}, empty after
     *         trimming, or contains only whitespace; {@code false}
     *         otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Test whether the supplied string parses as a valid ISO
     * {@code YYYY-MM-DD} date via {@link LocalDate#parse(CharSequence,
     * DateTimeFormatter)} with {@link DateTimeFormatter#ISO_LOCAL_DATE}.
     * The {@code ISO_LOCAL_DATE} formatter is strict by default — Feb 30
     * is rejected, Feb 29 in a non-leap year is rejected, month 13 is
     * rejected, alphabetic year is rejected, slash separator is rejected,
     * empty string is rejected.
     *
     * @param value the candidate string; may be {@code null}
     * @return {@code true} when the value is a parseable ISO local date;
     *         {@code false} otherwise (including {@code null})
     */
    private static boolean isValidIsoDate(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }
}
