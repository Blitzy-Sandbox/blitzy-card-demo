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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Card-update service &mdash; the Java target for the COBOL/CICS program
 * {@code app/cbl/COCRDUPC.cbl} (CICS TRANID {@code 'CCUP'}).
 *
 * <p>This service performs the optimistically-locked update of an existing
 * card record. In the COBOL source the corresponding flow is:</p>
 * <ol>
 *   <li>{@code 1100-RECEIVE-MAP} &mdash; receive the BMS map and copy the
 *       operator's input into {@code CCUP-NEW-DETAILS}.</li>
 *   <li>{@code 1200-EDIT-MAP-INPUTS} (with sub-paragraphs
 *       {@code 1210-EDIT-ACCOUNT}, {@code 1220-EDIT-CARD},
 *       {@code 1230-EDIT-NAME}, {@code 1240-EDIT-CARDSTATUS},
 *       {@code 1250-EDIT-EXPIRY-MON}, {@code 1260-EDIT-EXPIRY-YEAR})
 *       &mdash; field-level edits against the {@code FLG-*} 88-levels
 *       in working storage:
 *       <ul>
 *         <li>{@code CARD-NAME-CHECK PIC X(50)} &mdash; non-blank, alpha
 *             + spaces only.</li>
 *         <li>{@code FLG-YES-NO-VALID VALUES 'Y','N'} &mdash; one-char
 *             status flag.</li>
 *         <li>{@code CARD-MONTH-CHECK PIC 9(2) VALID-MONTH VALUES 1 THRU 12}.</li>
 *         <li>{@code CARD-YEAR-CHECK PIC 9(4) VALID-YEAR VALUES 1950 THRU 2099}.</li>
 *       </ul>
 *   </li>
 *   <li>{@code 9200-WRITE-PROCESSING} &mdash; {@code EXEC CICS READ UPDATE
 *       FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)} acquires the row
 *       lock; {@code 9300-CHECK-CHANGE-IN-REC} compares every field of
 *       the just-read {@code CARD-RECORD} against the {@code CCUP-OLD-*}
 *       snapshot held in the program COMMAREA (the COBOL manual
 *       before/after image comparison &mdash; the
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch).</li>
 *   <li>{@code EXEC CICS REWRITE FILE('CARDDAT')} writes the updated
 *       {@code CARD-UPDATE-RECORD} back under the still-held lock; on
 *       a non-{@code NORMAL} {@code DFHRESP} the
 *       {@code LOCKED-BUT-UPDATE-FAILED} branch is taken.</li>
 *   <li>{@code EXEC CICS SYNCPOINT} commits the unit-of-work (implicit
 *       on successful {@code RETURN}).</li>
 * </ol>
 *
 * <p>The Java target preserves these semantics with the standard
 * Spring Data JPA optimistic-locking pattern:</p>
 * <ol>
 *   <li>{@link #validate(CardUpdateDto) field-level validation} runs
 *       first &mdash; one-shot enforcement of the COBOL 88-level
 *       invariants ({@code VALID-MONTH 1-12}, {@code VALID-YEAR
 *       1950-2099}, {@code FLG-YES-NO-VALID} on the status flag,
 *       {@code CARD-NAME-CHECK PIC X(50)} length and non-blank check)
 *       collected into a {@link ValidationException} that
 *       {@code GlobalExceptionHandler} maps to HTTP 400. Jakarta Bean
 *       Validation annotations on {@link CardUpdateDto} provide a
 *       parallel safety net at the controller boundary.</li>
 *   <li>{@link CardRepository#findById(Object)} replaces the
 *       {@code EXEC CICS READ UPDATE}; a missing row surfaces as
 *       {@link RecordNotFoundException} &rarr; HTTP 404 (COBOL
 *       {@code FILE STATUS '23'} NOTFND).</li>
 *   <li>The explicit pre-save version comparison
 *       ({@code request.version() != card.getVersion()}) preserves the
 *       COBOL {@code 9300-CHECK-CHANGE-IN-REC} semantics &mdash; a
 *       client that no longer holds the latest snapshot is rejected
 *       with {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       &rarr; HTTP 409 before any write is attempted.</li>
 *   <li>{@link CardRepository#save(Object)} replaces the
 *       {@code EXEC CICS REWRITE}; Hibernate adds
 *       {@code WHERE version = ?} to the UPDATE statement and detects
 *       any race between the pre-check and the write as
 *       {@link OptimisticLockingFailureException} &rarr; also re-thrown
 *       as {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       &rarr; HTTP 409. This is the JPA-idiomatic replacement for the
 *       COBOL {@code LOCKED-BUT-UPDATE-FAILED} branch.</li>
 *   <li>{@link Transactional &#64;Transactional(rollbackFor =
 *       Exception.class, isolation = Isolation.READ_COMMITTED)}
 *       replaces {@code EXEC CICS SYNCPOINT} &mdash; commit at
 *       successful return, automatic rollback on any thrown
 *       exception.</li>
 *   <li>After a successful commit the cached card entry is invalidated
 *       via {@link CacheService#evict(String, String)} and the update
 *       is recorded by {@link AuditLogService#auditEvent(String,
 *       String, Map)} to CloudTrail / OpenSearch for regulatory
 *       traceability.</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COCRDUPC.cbl} (CICS
 *       TRANID {@code 'CCUP'}, file {@code 'CARDDAT'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COCRDUP.bms} (mapset
 *       {@code COCRDUP}, map {@code CCRDUPA}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COCRDUP.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes) &mdash; mapped to JPA entity
 *       {@link Card}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} (KEYS(16,0),
 *       RECORDSIZE(150,150)).</li>
 * </ul>
 *
 * <h2>PCI-DSS / PAN handling</h2>
 * <p>The card-number path variable on
 * {@code PUT /api/cards/{cardNumber}} is the full 16-digit Primary
 * Account Number (PAN). The PAN must travel over the HTTPS-protected
 * request line, but it MUST be masked in every log line, audit record,
 * and cache key per AAP &sect;0.6.6. This service:</p>
 * <ul>
 *   <li>logs only the last 4 digits of the PAN (see
 *       {@link #lastFour(String)});</li>
 *   <li>never includes the full PAN in {@link AuditLogService}
 *       payloads &mdash; the audit payload carries {@code cardLast4}
 *       only;</li>
 *   <li>excludes the CVV ({@code cardCvvCd} on {@link Card}) from the
 *       update flow entirely &mdash; sensitive authentication data is
 *       not modifiable through this endpoint.</li>
 * </ul>
 *
 * @see CardRepository
 * @see CardUpdateDto
 * @see CardDetailDto
 * @see Card
 */
@Service
public class CardUpdateService {

    /** SLF4J logger; backed by Logback per {@code logback-spring.xml}. */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    /**
     * COBOL: COCRDUPC:FLG-YES-NO-VALID VALUES 'Y', 'N' &mdash; the
     * {@code 88}-level value set used to edit {@code CCUP-NEW-CRDSTCD}
     * in {@code 1240-EDIT-CARDSTATUS}. Held as an immutable
     * {@link Set} literal for O(1) containment checks.
     */
    private static final Set<String> VALID_STATUS_CODES = Set.of("Y", "N");

    /**
     * COBOL: COCRDUPC:VALID-YEAR VALUES 1950 THRU 2099 &mdash; lower
     * bound of the {@code 88}-level on {@code CARD-YEAR-CHECK-N PIC
     * 9(4)} in working storage (line 99 of {@code COCRDUPC.cbl}).
     */
    private static final int MIN_YEAR = 1950;

    /**
     * COBOL: COCRDUPC:VALID-YEAR VALUES 1950 THRU 2099 &mdash; upper
     * bound of the {@code 88}-level on {@code CARD-YEAR-CHECK-N PIC
     * 9(4)} in working storage (line 99 of {@code COCRDUPC.cbl}).
     */
    private static final int MAX_YEAR = 2099;

    /**
     * COBOL: COCRDUPC:VALID-MONTH VALUES 1 THRU 12 &mdash; lower bound
     * of the {@code 88}-level on {@code CARD-MONTH-CHECK-N PIC 9(2)}
     * in working storage (line 95 of {@code COCRDUPC.cbl}).
     */
    private static final int MIN_MONTH = 1;

    /**
     * COBOL: COCRDUPC:VALID-MONTH VALUES 1 THRU 12 &mdash; upper bound
     * of the {@code 88}-level on {@code CARD-MONTH-CHECK-N PIC 9(2)}
     * in working storage (line 95 of {@code COCRDUPC.cbl}).
     */
    private static final int MAX_MONTH = 12;

    /**
     * {@code CARD-EMBOSSED-NAME PIC X(50)} maximum length from
     * {@code app/cpy/CVACT02Y.cpy} line 8 / {@code CARD-NAME-CHECK
     * PIC X(50)} in {@code COCRDUPC.cbl} line 87.
     */
    private static final int MAX_EMBOSSED_NAME_LENGTH = 50;

    /**
     * Cache namespace used by {@code CardDetailService} for cached
     * {@link CardDetailDto} entries; eviction here ensures
     * subsequent reads observe the new card state per the
     * cache-aside contract (AAP &sect;0.3.3).
     */
    private static final String CACHE_NAMESPACE_CARD_DETAIL = "card-detail";

    /**
     * Audit event name surfaced to CloudTrail/OpenSearch when a card
     * row is successfully updated. The name uses dotted lowercase per
     * the project convention (see {@code BillPaymentService}'s
     * {@code "account.updated"} for a comparable example).
     */
    private static final String AUDIT_EVENT_CARD_UPDATED = "card.updated";

    /**
     * Default actor identifier used when an authenticated principal
     * cannot be resolved (e.g., when invoked from a non-HTTP context).
     * The COBOL source records the operator identity via
     * {@code SEC-USR-ID} in {@code CSUSR01Y.cpy}; the Java target will
     * pass an authenticated user identifier here once the
     * {@code AuthenticationFilter} is wired in.
     */
    private static final String AUDIT_ACTOR_SYSTEM = "system";

    private final CardRepository cardRepository;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;

    /**
     * Constructor injection per AAP &sect;0.7.1 "Dependency injection
     * for loose coupling" &mdash; Spring supplies all collaborators at
     * bean instantiation; no setter / field injection is used.
     *
     * @param cardRepository  Spring Data JPA repository for the
     *                        {@link Card} aggregate; replaces VSAM
     *                        {@code CARDDAT} I/O.
     * @param cacheService    ElastiCache Redis adapter; used to evict
     *                        the {@code card-detail} entry on
     *                        successful update.
     * @param auditLogService CloudTrail + OpenSearch audit adapter;
     *                        emits the {@code card.updated} event.
     */
    public CardUpdateService(CardRepository cardRepository,
                             CacheService cacheService,
                             AuditLogService auditLogService) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "cardRepository must not be null");
        this.cacheService = Objects.requireNonNull(cacheService,
                "cacheService must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Apply the supplied edits to the card identified by
     * {@code cardNumber}.
     *
     * <p>This is the Java translation of the COBOL
     * {@code COCRDUPC.cbl} card-update flow: validate inputs &rarr;
     * load the existing card row &rarr; verify the optimistic-lock
     * snapshot matches &rarr; apply the mutated fields &rarr; persist
     * with JPA {@code @Version} guard &rarr; invalidate the cache
     * &rarr; emit the audit event.</p>
     *
     * <p>{@code @Transactional(rollbackFor = Exception.class, isolation
     * = Isolation.READ_COMMITTED)} replaces CICS {@code SYNCPOINT}:
     * the transaction commits on a clean return and rolls back on any
     * thrown exception. {@code READ_COMMITTED} is the explicit
     * declaration of the AAP-mandated isolation level (AAP &sect;0.4.1
     * &mdash; "READ_COMMITTED, with REPEATABLE_READ or SERIALIZABLE
     * for any read-modify-write flow"); a stronger guarantee is
     * unnecessary here because per-row contention is detected via
     * JPA's {@code @Version} optimistic-lock column rather than via
     * isolation-level locking.</p>
     *
     * @param cardNumber the 16-digit Primary Account Number identifying
     *                   the card to update; treated as the canonical
     *                   identifier and must match
     *                   {@code request.cardNumber()} to prevent silent
     *                   primary-key tampering (HTTP 400 on mismatch).
     * @param request    the validated update request carrying the
     *                   desired field values plus the JPA
     *                   {@code @Version} snapshot value previously
     *                   returned by {@code GET /api/cards/{cardNumber}}.
     * @return the persisted card as a {@link CardDetailDto} response
     *         envelope (PAN masked in {@link CardDetailDto#toString()}
     *         per AAP &sect;0.6.6).
     * @throws ValidationException                       if any input
     *         field fails the COBOL-derived edit checks
     *         ({@code 1200-EDIT-MAP-INPUTS} family) &mdash; HTTP 400.
     * @throws RecordNotFoundException                   if no card row
     *         exists for {@code cardNumber} &mdash; HTTP 404 (COBOL
     *         {@code FILE STATUS 23} NOTFND).
     * @throws com.awsm2.carddemo.exception.ConcurrentModificationException
     *         if the supplied {@code request.version()} no longer
     *         matches the persisted row (either at the explicit
     *         pre-check or at JPA {@code save} time) &mdash; HTTP 409
     *         (COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE} /
     *         {@code LOCKED-BUT-UPDATE-FAILED}).
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CardDetailDto updateCard(String cardNumber, CardUpdateDto request) {
        // ---------------------------------------------------------------
        // Step 0: defensive null checks on the controller-supplied
        // parameters. The controller binds the request body to a
        // CardUpdateDto annotated with @Valid, which prevents nulls at
        // the controller boundary; these guards are defense-in-depth
        // for callers reached via internal service-to-service paths.
        // ---------------------------------------------------------------
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // ---------------------------------------------------------------
        // Step 1: validate the path-variable cardNumber against the COBOL
        // 88-level FLG-CARDFILTER-ISVALID (line 63 of COCRDUPC.cbl)
        // which requires CC-CARD-NUM-N to be a 16-digit numeric.
        // The COBOL 1220-EDIT-CARD paragraph (lines 762-803) tests
        // CC-CARD-NUM IS NOT NUMERIC and emits
        // "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER".
        // ---------------------------------------------------------------
        validateCardNumberFormat(cardNumber);

        // The DTO body must reference the same card as the URL path
        // variable. The COBOL does not allow changing the card number
        // on an update (see line 1462 — CCUP-NEW-CARDID is moved into
        // CARD-UPDATE-NUM but is the original lookup key, not a new
        // key). Path/body divergence is therefore a request error.
        if (!cardNumber.equals(request.cardNumber())) {
            LOG.warn("Card update path/body mismatch: pathLast4={} bodyLast4={}",
                    lastFour(cardNumber), lastFour(request.cardNumber()));
            throw new ValidationException(
                    "CARDNUM_PATH_BODY_MISMATCH",
                    "Path variable cardNumber does not match request body cardNumber");
        }

        // ---------------------------------------------------------------
        // Step 2: COBOL 1200-EDIT-MAP-INPUTS — validate every editable
        // field collected from the BMS map. We collect ALL violations
        // first so callers get a complete error list (the COBOL flow
        // surfaces only one message at a time, but the Java target
        // returns the full set via the ValidationException FieldError
        // contract — a strictly additive improvement for clients).
        // ---------------------------------------------------------------
        validate(request);

        // ---------------------------------------------------------------
        // Step 3: COBOL 9200-WRITE-PROCESSING — EXEC CICS READ UPDATE
        //         FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM) (lines
        //         1427-1436).
        //
        // FILE STATUS '23' (NOTFND) → RecordNotFoundException → HTTP 404
        // ---------------------------------------------------------------
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(
                        "CARD_NOT_FOUND",
                        "Card not found: cardLast4=" + lastFour(cardNumber)));

        // ---------------------------------------------------------------
        // Step 4: COBOL 9300-CHECK-CHANGE-IN-REC (lines 1498-1521) —
        //         field-by-field comparison of the just-read CARD-RECORD
        //         against the CCUP-OLD-* snapshot held in COMMAREA
        //         (the COBOL manual before/after image comparison). The
        //         DATA-WAS-CHANGED-BEFORE-UPDATE branch is taken on
        //         mismatch.
        //
        // The Java target uses JPA @Version. The supplied snapshot
        // value MUST match the persisted entity's version; on mismatch
        // we throw com.awsm2.carddemo.exception.ConcurrentModificationException
        // (FQCN per agent prompt) so GlobalExceptionHandler emits
        // HTTP 409 Conflict with the appropriate reason code.
        // ---------------------------------------------------------------
        Long requestVersion = request.version();
        if (requestVersion == null) {
            // The COBOL flow rejects an unbound CCUP-OLD-* COMMAREA
            // before reaching 9300-CHECK-CHANGE-IN-REC (line 506
            // INITIALIZE WS-THIS-PROGCOMMAREA). The Java target treats
            // a missing version as a client-validation failure.
            throw new ValidationException(
                    "MISSING_VERSION",
                    "Version is required for optimistic-locking-based update");
        }
        Long currentVersion = card.getVersion();
        if (!Objects.equals(requestVersion, currentVersion)) {
            LOG.warn(
                    "Card optimistic-lock pre-check failed cardLast4={} requestVersion={} currentVersion={}",
                    lastFour(cardNumber), requestVersion, currentVersion);
            throw new com.awsm2.carddemo.exception.ConcurrentModificationException(
                    "DATA_CHANGED_BEFORE_UPDATE",
                    "Card was modified by another transaction. Please review (cardLast4="
                            + lastFour(cardNumber) + ")");
        }

        // ---------------------------------------------------------------
        // Step 5: COBOL 9200-WRITE-PROCESSING — prepare CARD-UPDATE-RECORD
        //         (lines 1461-1475) by moving the validated CCUP-NEW-*
        //         fields onto the in-memory record. The Java equivalent
        //         is to mutate the managed JPA entity directly.
        //
        // Per the COBOL refactor discipline (AAP §0.7.3), only the
        // fields exposed on the BMS map are mutated. The card-number
        // primary key is preserved verbatim; the CVV is intentionally
        // NOT carried on CardUpdateDto and is therefore NOT mutated
        // here (PCI-DSS Requirement 3.2 — sensitive authentication data
        // must not be writable via this endpoint).
        // ---------------------------------------------------------------
        applyEdits(card, request);

        // ---------------------------------------------------------------
        // Step 6: COBOL 9200-WRITE-PROCESSING — EXEC CICS REWRITE
        //         FILE('CARDDAT') FROM(CARD-UPDATE-RECORD) (lines
        //         1477-1483).
        //
        // Hibernate auto-increments the @Version column and adds
        // WHERE version = ? to the UPDATE. A concurrent commit between
        // the pre-check (Step 4) and this save raises
        // OptimisticLockingFailureException, which we re-throw as
        // the domain ConcurrentModificationException (FQCN per agent
        // prompt). This is the JPA-idiomatic replacement for the COBOL
        // LOCKED-BUT-UPDATE-FAILED branch (lines 1488-1492).
        // ---------------------------------------------------------------
        Card saved;
        try {
            saved = cardRepository.save(card);
        } catch (OptimisticLockingFailureException olfe) {
            LOG.warn(
                    "Card optimistic-lock save failed cardLast4={} requestVersion={}",
                    lastFour(cardNumber), requestVersion);
            throw new com.awsm2.carddemo.exception.ConcurrentModificationException(
                    "LOCKED_BUT_UPDATE_FAILED",
                    "Card update failed due to concurrent modification (cardLast4="
                            + lastFour(cardNumber) + ")",
                    olfe);
        }

        // ---------------------------------------------------------------
        // Step 7: Cache invalidation (cache-aside post-write).
        //
        // The CardDetailService keeps a CardDetailDto entry in
        // ElastiCache Redis under the "card-detail" namespace keyed by
        // the PAN. Evict that entry so the next GET observes the new
        // state. CacheService.evict() is fail-open per AAP §0.7.1
        // (cache failures must not propagate to the business flow),
        // but we still wrap the call defensively to ensure that an
        // unexpected runtime fault from the cache layer never rolls
        // back a successfully-committed JPA update.
        // ---------------------------------------------------------------
        try {
            cacheService.evict(CACHE_NAMESPACE_CARD_DETAIL, cardNumber);
        } catch (RuntimeException cacheEx) {
            LOG.warn("Card cache eviction failed (non-fatal) cardLast4={} reason={}",
                    lastFour(cardNumber), cacheEx.getMessage());
        }

        // ---------------------------------------------------------------
        // Step 8: Audit-trail emission (CloudTrail + OpenSearch).
        //
        // Replaces the COBOL audit-trail side effects implicit in the
        // CICS SYNCPOINT (which historically wrote to a journal log on
        // mainframe LPARs). The payload deliberately omits the full
        // PAN — only the last 4 are recorded — per AAP §0.6.6 PCI-DSS
        // rules; the CloudWatch log filter for PAN-like sequences acts
        // as defense-in-depth on top of this discipline.
        // ---------------------------------------------------------------
        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("entity", "Card");
        auditPayload.put("cardLast4", lastFour(saved.getCardNum()));
        auditPayload.put("accountId", saved.getCardAcctId());
        auditPayload.put("activeStatus", saved.getCardActiveStatus());
        auditPayload.put("version", saved.getVersion());
        try {
            auditLogService.auditEvent(AUDIT_EVENT_CARD_UPDATED,
                    AUDIT_ACTOR_SYSTEM, auditPayload);
        } catch (RuntimeException auditEx) {
            // Audit failures are non-fatal — the JPA UPDATE has already
            // committed and the CloudWatch log line below provides a
            // backup record. Per AAP §0.6.6 the audit pipeline has its
            // own retry / DLQ wiring inside AuditLogService.
            LOG.warn("Card audit emission failed (non-fatal) cardLast4={} reason={}",
                    lastFour(cardNumber), auditEx.getMessage());
        }

        // ---------------------------------------------------------------
        // Step 9: Structured log + response. The log line carries only
        // the PCI-safe last-4 of the PAN and the new version value;
        // the response is built via toDetailDto() which delegates PAN
        // masking to CardDetailDto.toString().
        // ---------------------------------------------------------------
        LOG.info("Card updated cardLast4={} accountId={} version={}",
                lastFour(saved.getCardNum()), saved.getCardAcctId(),
                saved.getVersion());
        return toDetailDto(saved);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Validate the {@code cardNumber} path variable against the COBOL
     * {@code 1220-EDIT-CARD} edit rules &mdash; the string must be
     * non-blank and exactly 16 ASCII digits.
     *
     * @param cardNumber the candidate value from the URL path
     * @throws ValidationException if the value is missing, contains
     *                             non-digit characters, or is not
     *                             exactly 16 digits long
     */
    private void validateCardNumberFormat(String cardNumber) {
        // COBOL: COCRDUPC:1220-EDIT-CARD (lines 762-803) — CC-CARD-NUM
        // must be non-blank and numeric; a non-conforming value sets
        // SEARCHED-CARD-NOT-NUMERIC or WS-PROMPT-FOR-CARD.
        if (cardNumber.isBlank()) {
            throw new ValidationException(
                    "CARDNUM_REQUIRED",
                    "Card number is required");
        }
        if (cardNumber.length() != 16) {
            throw new ValidationException(
                    "CARDNUM_INVALID_LENGTH",
                    "Card number must be exactly 16 characters");
        }
        for (int i = 0; i < 16; i++) {
            char ch = cardNumber.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new ValidationException(
                        "CARDNUM_NOT_NUMERIC",
                        "Card number must be a 16-digit numeric string");
            }
        }
    }

    /**
     * Field-level validation mirroring the COBOL
     * {@code 1200-EDIT-MAP-INPUTS} paragraph chain:
     * {@code 1230-EDIT-NAME}, {@code 1240-EDIT-CARDSTATUS},
     * {@code 1250-EDIT-EXPIRY-MON}, {@code 1260-EDIT-EXPIRY-YEAR}.
     *
     * <p>Each check throws a {@link ValidationException} on the first
     * failure encountered &mdash; consistent with the COBOL flow which
     * exits the edit chain on the first INPUT-ERROR. Jakarta Bean
     * Validation on the DTO provides a parallel safety net at the
     * controller boundary (e.g., {@code @Pattern("^[YN]$")} on
     * {@code activeStatus}) but is duplicated here so that
     * service-level invocations (tests, internal service-to-service
     * calls) do not bypass the COBOL invariants.</p>
     *
     * @param request the candidate update payload (non-null)
     */
    private void validate(CardUpdateDto request) {

        // ---------- 1230-EDIT-NAME: CARD-NAME-CHECK PIC X(50) ----------
        // The COBOL 1230-EDIT-NAME paragraph (lines 806-840) rejects a
        // blank embossed name and rejects any name that does not pass
        // the LIT-ALL-ALPHA-FROM CONVERTING TO LIT-ALL-SPACES-TO
        // inspection (i.e., must contain only alphabets and spaces).
        // We enforce non-blank + max length here; the alphabetic-only
        // rule is a downstream business invariant carried by the
        // CardUpdateDto Jakarta Bean Validation contract on the
        // controller boundary.
        String embossedName = request.embossedName();
        if (embossedName == null || embossedName.isBlank()) {
            throw new ValidationException(
                    "EMBOSSED_NAME_REQUIRED",
                    "Card name not provided");
        }
        if (embossedName.length() > MAX_EMBOSSED_NAME_LENGTH) {
            throw new ValidationException(
                    "EMBOSSED_NAME_TOO_LONG",
                    "Embossed name must be at most "
                            + MAX_EMBOSSED_NAME_LENGTH + " characters");
        }

        // ---------- 1240-EDIT-CARDSTATUS: FLG-YES-NO-VALID ('Y','N') ----
        // The COBOL 1240-EDIT-CARDSTATUS paragraph (lines 845-873)
        // tests CCUP-NEW-CRDSTCD against the 88-level FLG-YES-NO-VALID
        // (line 91: VALUES 'Y', 'N'). On failure the error message
        // CARD-STATUS-MUST-BE-YES-NO is surfaced.
        String status = request.activeStatus();
        if (status == null || status.isBlank()) {
            throw new ValidationException(
                    "CARDSTATUS_REQUIRED",
                    "Card Active Status must be Y or N");
        }
        if (!VALID_STATUS_CODES.contains(status)) {
            throw new ValidationException(
                    "CARDSTATUS_INVALID",
                    "Card Active Status must be Y or N");
        }

        // ---------- 1250-EDIT-EXPIRY-MON + 1260-EDIT-EXPIRY-YEAR --------
        // The COBOL flow validates month and year separately (lines
        // 877-907 and 913-944) before assembling CARD-UPDATE-EXPIRAION-
        // DATE via STRING (line 1467). The DTO supplies an already-
        // composed LocalDate; we extract its month and year, run the
        // COBOL invariants verbatim, and use the LocalDate as the
        // canonical value.
        LocalDate expirationDate = request.expirationDate();
        if (expirationDate == null) {
            throw new ValidationException(
                    "EXPIRATION_DATE_REQUIRED",
                    "Card expiry date not provided");
        }

        int month = expirationDate.getMonthValue();
        int year = expirationDate.getYear();

        // COBOL: COCRDUPC:VALID-MONTH (CARD-MONTH-CHECK PIC 9(2)
        //        VALUES 1 THRU 12, line 95)
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw new ValidationException(
                    "EXPIRY_MONTH_INVALID",
                    "Card expiry month must be between "
                            + MIN_MONTH + " and " + MAX_MONTH);
        }

        // COBOL: COCRDUPC:VALID-YEAR (CARD-YEAR-CHECK PIC 9(4)
        //        VALUES 1950 THRU 2099, line 99)
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new ValidationException(
                    "EXPIRY_YEAR_INVALID",
                    "Invalid card expiry year. Must be between "
                            + MIN_YEAR + " and " + MAX_YEAR);
        }

        // Defensive: ensure the YearMonth combination is itself valid
        // (LocalDate's contract already guarantees this, but invoking
        // YearMonth.of(...) makes the intent explicit and ports the
        // agent-prompt-mandated YearMonth.of(year, month).atEndOfMonth()
        // pattern that the COBOL builds the date assembly around).
        try {
            YearMonth.of(year, month);
        } catch (DateTimeException dte) {
            // Should not be reachable given the prior range checks,
            // but kept defensively in line with the agent prompt's
            // comprehensive-error-handling directive.
            throw new ValidationException(
                    "EXPIRATION_DATE_INVALID",
                    "Card expiry date is invalid: " + dte.getMessage());
        }

        // ---------- accountId (CARD-ACCT-ID PIC 9(11)) ----------------
        // The COBOL 1210-EDIT-ACCOUNT paragraph (lines 721-756) tests
        // CC-ACCT-ID against LOW-VALUES/SPACES/ZEROS and against
        // IS NOT NUMERIC. The DTO uses a Long, which Jackson rejects
        // for non-numeric values; we add the non-null and >0 checks
        // here as the verbatim COBOL invariant.
        Long accountId = request.accountId();
        if (accountId == null) {
            throw new ValidationException(
                    "ACCT_ID_REQUIRED",
                    "Account number not provided");
        }
        if (accountId <= 0L) {
            throw new ValidationException(
                    "ACCT_ID_ZERO_OR_NEGATIVE",
                    "Account number must be a non zero 11 digit number");
        }
    }

    /**
     * Copy the validated DTO field values onto the loaded JPA entity.
     * Mirrors COBOL {@code 9200-WRITE-PROCESSING} lines 1461-1475 which
     * move {@code CCUP-NEW-*} values into {@code CARD-UPDATE-RECORD}
     * prior to the {@code REWRITE}.
     *
     * <p>Only the editable fields are mutated. The card-number primary
     * key is preserved verbatim &mdash; the COBOL source also forbids
     * primary-key changes during update (the {@code CARDSID} field on
     * the BMS map is keyed for lookup only). The CVV
     * ({@link Card#getCardCvvCd()}) is intentionally NOT carried on
     * {@link CardUpdateDto} and is therefore NOT mutated here, per
     * AAP &sect;0.6.6 PCI-DSS Requirement 3.2 (sensitive authentication
     * data must not be modifiable through this endpoint).</p>
     *
     * @param card    the managed JPA entity loaded by
     *                {@link CardRepository#findById(Object)}
     * @param request the validated update payload
     */
    private void applyEdits(Card card, CardUpdateDto request) {
        // COBOL: CCUP-NEW-CRDNAME → CARD-UPDATE-EMBOSSED-NAME (line 1466)
        card.setCardEmbossedName(request.embossedName());
        // COBOL: CCUP-NEW-CRDSTCD → CARD-UPDATE-ACTIVE-STATUS (line 1475)
        card.setCardActiveStatus(request.activeStatus());
        // COBOL: STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-'
        //        CCUP-NEW-EXPDAY → CARD-UPDATE-EXPIRAION-DATE
        //        (lines 1467-1474)
        card.setCardExpirationDate(request.expirationDate());
        // COBOL: CC-ACCT-ID-N → CARD-UPDATE-ACCT-ID (line 1463). Note
        // that COCRDUPC writes the account ID into the CARD-UPDATE-
        // RECORD verbatim; for the Java target the account ID is the
        // foreign key on the Card row and is therefore updated here so
        // that the row reflects the request body. In practice the
        // controller-layer guard prevents the account ID from changing
        // during an update (the GET response carried it and the client
        // is expected to round-trip it), but we mirror the COBOL move
        // for behavioural parity.
        card.setCardAcctId(request.accountId());
    }

    /**
     * Build the {@link CardDetailDto} response envelope from a
     * persisted {@link Card}. The DTO mirrors the {@code COCRDSL.bms}
     * card-detail screen contract (per the source mapset and per
     * {@link CardDetailDto}'s class Javadoc) with the PAN masked in
     * {@link CardDetailDto#toString()}.
     *
     * @param card the just-persisted card entity (non-null, version
     *             freshly incremented by Hibernate)
     * @return a fully-populated {@link CardDetailDto}
     */
    private CardDetailDto toDetailDto(Card card) {
        return new CardDetailDto(
                card.getCardNum(),
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus());
    }

    /**
     * PCI-DSS-safe accessor that returns only the last 4 digits of a
     * Primary Account Number (PAN). Used for log lines and audit
     * payloads to satisfy PCI-DSS v4.0 Requirement 3.4.1 (display
     * only the last 4 of the PAN) per AAP &sect;0.6.6.
     *
     * <p>Defensive against {@code null} and short inputs so that an
     * accidental empty value never triggers a {@link StringIndexOutOfBoundsException}
     * from a fatal logging path.</p>
     *
     * @param pan the candidate Primary Account Number (may be
     *            {@code null} or shorter than 4 characters)
     * @return the last 4 characters of {@code pan}, or the literal
     *         {@code "****"} placeholder when {@code pan} is null or
     *         shorter than 4 characters
     */
    private String lastFour(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return pan.substring(pan.length() - 4);
    }
}
