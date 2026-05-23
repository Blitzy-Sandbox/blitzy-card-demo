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
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardRepository;
import com.awsm2.carddemo.validation.DateValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Card-update service &mdash; the Java target for the COBOL/CICS program
 * {@code app/cbl/COCRDUPC.cbl} (CICS transaction id {@code CCUP}).
 *
 * <p>This service performs the optimistically-locked update of an existing
 * card record. In the COBOL source, {@code COCRDUPC.cbl} issues an
 * {@code EXEC CICS READ UPDATE} against the {@code CARDDAT} VSAM KSDS,
 * compares the before-image to a snapshot stored in COMMAREA (the original
 * COBOL "optimistic locking" pattern implemented as manual field-by-field
 * snapshot comparison &mdash; see {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 * paragraph), and finally issues an {@code EXEC CICS REWRITE} when no
 * other process has changed the record. In the Java target, the
 * equivalent is JPA {@code @Version} optimistic locking: the caller
 * supplies the previously-loaded {@code version} value in
 * {@link CardUpdateDto#version()}, and Hibernate detects a stale
 * snapshot by issuing {@code UPDATE cards SET ... WHERE card_num = ?
 * AND version = ?} and matching 0 rows when the version is stale.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COCRDUPC.cbl} (CICS TRANID
 *       {@code 'CCUP'}, file {@code 'CARDDAT'}; AAP &sect;0.4.1 online
 *       programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COCRDUP.bms} (mapset
 *       {@code COCRDUP}, map {@code CCRDUPA}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COCRDUP.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes) &mdash; mapped to JPA entity
 *       {@link Card}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}
 *       (KEYS(16 0), RECORDSIZE(150 150)).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COCRDUPC.cbl &harr; CardUpdateService.updateCard(...)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS} (field-level edit validation)</td>
 *       <td>{@link #validate(CardUpdateDto)}</td></tr>
 *   <tr><td>{@code 9100-READ-CARD-UPDATE} &mdash; {@code EXEC CICS READ
 *       UPDATE FILE('CARDDAT')}</td>
 *       <td>{@link CardRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code DATA-WAS-CHANGED-BEFORE-UPDATE} (manual snapshot
 *       comparison against COMMAREA before-image)</td>
 *       <td>JPA {@code @Version} optimistic locking; the version supplied
 *       on {@link CardUpdateDto#version()} is compared by Hibernate
 *       against the current row at SAVE time; mismatch raises
 *       {@link OptimisticLockingFailureException} &rarr; HTTP 409 by
 *       {@code GlobalExceptionHandler}</td></tr>
 *   <tr><td>{@code 9200-WRITE-CARD-UPDATE} &mdash; {@code EXEC CICS
 *       REWRITE FILE('CARDDAT')}</td>
 *       <td>{@link CardRepository#save(Object)} (after
 *       {@link #applyEdits(Card, CardUpdateDto)})</td></tr>
 *   <tr><td>{@code SYNCPOINT} (CICS task commit)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)} on this method &mdash; commit at successful
 *       return, automatic rollback on any thrown exception</td></tr>
 *   <tr><td>Cache invalidation after rewrite</td>
 *       <td>{@link CacheService#evict(String, String)} on the
 *       {@code cardView} and account-derived namespaces &mdash; ensures
 *       subsequent {@code AccountViewService} reads observe the new
 *       card state</td></tr>
 * </table>
 *
 * <h2>Optimistic locking guarantee</h2>
 * <p>The supplied {@link CardUpdateDto#version()} is set onto the loaded
 * {@link Card} entity via {@link Card#setVersion(Long)} before the
 * {@code save} call. Hibernate's UPDATE then carries
 * {@code WHERE card_num = ? AND version = ?} and a 0-row match results
 * in {@code OptimisticLockException} (wrapped by Spring as
 * {@link OptimisticLockingFailureException}). The
 * {@code GlobalExceptionHandler} maps that to HTTP 409 Conflict with the
 * standardized error envelope. This replicates the COBOL source's
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} branch which set
 * {@code WS-EDIT-ERROR-FLG} and re-displayed the screen with a
 * "Data was changed before update" message.</p>
 *
 * <h2>PCI-DSS PAN handling</h2>
 * <p>The card-number path parameter on
 * {@code PUT /api/cards/{cardNumber}} is the primary key &mdash; full
 * PAN must travel over the HTTPS-protected request line. The Card
 * entity's {@code toString()} masks the PAN; this service emits log
 * messages only via the entity's masked {@code toString()} or via the
 * account-id surrogate where possible. CVV ({@code cardCvvCd}) is
 * <b>NEVER</b> mutated by this service or carried on the
 * {@link CardUpdateDto} contract (the DTO does not declare a CVV
 * component) &mdash; sensitive authentication data is protected per
 * AAP &sect;0.6.6 PCI-DSS Requirement 3.2.</p>
 *
 * @see CardRepository
 * @see CardUpdateDto
 * @see Card
 */
@Service
public class CardUpdateService {

    /**
     * Cache namespace for the {@link CardDetailDto} cache-aside path.
     * Eviction here ensures subsequent {@code CardDetailService} reads
     * observe the updated card state. The matching namespace is used by
     * {@code CardDetailService} (and by {@code AccountViewService} for
     * the broader account/cards composite view).
     */
    private static final String CACHE_NS_CARD = "cardView";

    /**
     * Cache namespace for the {@code AccountViewService} cache-aside
     * path. Card status changes (e.g., activation flag) affect the
     * card list materialized in account views, so we evict the
     * upstream {@code accountView} namespace as well.
     */
    private static final String CACHE_NS_ACCOUNT = "accountView";

    /** Audit event name (CloudTrail / OpenSearch indexer). */
    private static final String EVENT_CARD_UPDATED = "card.updated";

    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    private final CardRepository cardRepository;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;
    private final DateValidationService dateValidationService;

    /**
     * Constructor injection &mdash; Spring supplies the collaborators per
     * AAP &sect;0.7.1 "Dependency injection for loose coupling".
     */
    public CardUpdateService(CardRepository cardRepository,
                             CacheService cacheService,
                             AuditLogService auditLogService,
                             DateValidationService dateValidationService) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "cardRepository");
        this.cacheService = Objects.requireNonNull(cacheService,
                "cacheService");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService");
    }

    /**
     * Apply the supplied edits to the card identified by
     * {@link CardUpdateDto#cardNumber()}.
     *
     * <p>This is the Java equivalent of the COBOL paragraph chain
     * {@code 0000-MAIN} &rarr; {@code 1000-PROCESS-INPUTS} &rarr;
     * {@code 9100-READ-CARD-UPDATE} &rarr;
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} &rarr;
     * {@code 9200-WRITE-CARD-UPDATE} in {@code COCRDUPC.cbl}, with JPA
     * {@code @Version} replacing the manual COMMAREA snapshot
     * comparison.</p>
     *
     * @param request the validated update request (must carry the
     *                version value previously returned by GET)
     * @return the updated {@link CardUpdateDto} with the new
     *         {@code version} value
     * @throws ValidationException                  if any field fails
     *                                              syntactic or semantic
     *                                              validation (HTTP 400)
     * @throws RecordNotFoundException              if no card row exists
     *                                              for the supplied card
     *                                              number (HTTP 404)
     * @throws OptimisticLockingFailureException    if another transaction
     *                                              has modified the row
     *                                              since the version was
     *                                              loaded (HTTP 409 via
     *                                              {@code GlobalExceptionHandler})
     */
    @Transactional(rollbackFor = Exception.class)
    public CardUpdateDto updateCard(CardUpdateDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        String cardNumber = request.cardNumber();

        // ---- COBOL 9100-READ-CARD-UPDATE -----------------------------
        //     EXEC CICS READ UPDATE FILE('CARDDAT') INTO(CARD-RECORD)
        //                          RIDFLD(WS-CARD-NUM)
        // FILE STATUS 23 (NOTFND) → RecordNotFoundException → HTTP 404
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(
                        "CARD_NOT_FOUND", "Card not found"));

        // ---- DATA-WAS-CHANGED-BEFORE-UPDATE (optimistic lock) --------
        //     Replaces the COBOL field-by-field before-image snapshot
        //     comparison in COMMAREA with JPA @Version. Hibernate's
        //     UPDATE adds WHERE version = ? and detects a 0-row match
        //     as OptimisticLockException → HTTP 409.
        if (request.version() == null) {
            throw new ValidationException(
                    "MISSING_VERSION",
                    "Caller must supply the previously-loaded version "
                            + "to prevent lost-update conflicts",
                    List.of(new ValidationException.FieldError(
                            "version", "version is required")));
        }
        card.setVersion(request.version());

        // ---- COBOL 9200-WRITE-CARD-UPDATE (REWRITE) -------------------
        applyEdits(card, request);
        Card saved = cardRepository.save(card);

        // ---- Cache eviction (cache-aside invalidation) ----------------
        // Both the cardView (this card) and accountView (the owning
        // account's composite view, which carries the card list) must be
        // invalidated. Eviction is best-effort and never blocks the
        // commit (the CacheService swallows transient ElastiCache faults
        // and logs them).
        String cardCacheKey = saved.getCardNum();
        String acctCacheKey = String.format("%011d", saved.getCardAcctId());
        try {
            cacheService.evict(CACHE_NS_CARD, cardCacheKey);
            cacheService.evict(CACHE_NS_ACCOUNT, acctCacheKey);
        } catch (RuntimeException rex) {
            // Defensive: cache failures should not roll back the commit.
            LOG.warn("CardUpdateService: cache eviction failed; tx commit will proceed: {}",
                    rex.getMessage());
        }

        // ---- Audit ----------------------------------------------------
        // CloudTrail / OpenSearch event; replaces COBOL DISPLAY of the
        // updated card. Payload deliberately omits the full PAN — only
        // the entity's masked toString() form is acceptable for logs.
        Map<String, Object> payload = new HashMap<>();
        payload.put("cardLast4", lastFour(saved.getCardNum()));
        payload.put("accountId", saved.getCardAcctId());
        payload.put("activeStatus", saved.getCardActiveStatus());
        payload.put("version", saved.getVersion());
        auditLogService.auditEvent(EVENT_CARD_UPDATED, "system", payload);

        LOG.info("Card updated cardLast4={} accountId={} version={}",
                lastFour(saved.getCardNum()), saved.getCardAcctId(), saved.getVersion());

        return toDto(saved);
    }

    // -----------------------------------------------------------------
    // Validation — preserves COBOL 1000-PROCESS-INPUTS semantics
    // -----------------------------------------------------------------

    /**
     * Field-level validation; throws {@link ValidationException}
     * carrying every field-level error discovered. Mirrors the
     * COBOL {@code 1000-PROCESS-INPUTS} paragraph which sets
     * {@code WS-EDIT-ERROR-FLG} per-field and re-displays the screen
     * with the first error highlighted.
     */
    private void validate(CardUpdateDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        // COBOL 88-level FLG-CARDNUM-ISVALID: 16-digit numeric required.
        String cardNumber = request.cardNumber();
        if (cardNumber == null || cardNumber.isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "cardNumber", "cardNumber is required"));
        } else if (!cardNumber.matches("^\\d{16}$")) {
            errors.add(new ValidationException.FieldError(
                    "cardNumber",
                    "cardNumber must be exactly 16 digits"));
        }

        // COBOL 88-level FLG-ACCT-ID-ISVALID.
        if (request.accountId() == null) {
            errors.add(new ValidationException.FieldError(
                    "accountId", "accountId is required"));
        }

        // COBOL 88-level FLG-CARDNAME-ISVALID.
        if (request.embossedName() == null || request.embossedName().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "embossedName", "embossedName is required"));
        } else if (request.embossedName().length() > 50) {
            errors.add(new ValidationException.FieldError(
                    "embossedName",
                    "embossedName must be at most 50 characters"));
        }

        // COBOL 88-level FLG-YES-NO-VALID.
        String status = request.activeStatus();
        if (status == null || status.isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "activeStatus", "activeStatus is required"));
        } else if (!status.equals("Y") && !status.equals("N")) {
            errors.add(new ValidationException.FieldError(
                    "activeStatus",
                    "activeStatus must be 'Y' or 'N'"));
        }

        // COBOL CARD-MONTH-CHECK / CARD-YEAR-CHECK / CARD-DAY-CHECK +
        // DateValidationService (port of CSUTLDPY.cpy + CSUTLDTC.cbl).
        LocalDate expirationDate = request.expirationDate();
        if (expirationDate == null) {
            errors.add(new ValidationException.FieldError(
                    "expirationDate", "expirationDate is required"));
        } else {
            DateValidationService.DateValidationResult result =
                    dateValidationService.validate(expirationDate.toString());
            if (!result.isValid()) {
                errors.add(new ValidationException.FieldError(
                        "expirationDate", result.errorMessage()));
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "VALIDATION_FAILED",
                    "Card update request contains invalid fields",
                    errors);
        }
    }

    /**
     * Copies the validated DTO fields onto the loaded {@link Card}
     * entity. Mirrors the COBOL {@code 9700-UPDATE-CARD-INFO}
     * paragraph which moves the BMS map fields into the {@code
     * CARD-RECORD} structure before the REWRITE.
     */
    private void applyEdits(Card card, CardUpdateDto request) {
        // card.cardNum is the primary key and is therefore NOT changed
        // by this service — the COBOL source also rejects any attempt
        // to change the card number on the update screen (it's a
        // protected display field in the BMS map).
        card.setCardAcctId(request.accountId());
        card.setCardEmbossedName(request.embossedName());
        card.setCardExpirationDate(request.expirationDate());
        card.setCardActiveStatus(request.activeStatus());
    }

    /**
     * Maps the persisted {@link Card} back to a {@link CardUpdateDto}
     * response carrying the newly-incremented {@code version} value.
     */
    private CardUpdateDto toDto(Card card) {
        return new CardUpdateDto(
                card.getCardNum(),
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus(),
                card.getVersion());
    }

    /**
     * Returns the last 4 digits of a PAN for PCI-DSS-safe logging.
     * Defensive against null and short inputs.
     */
    private String lastFour(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return pan.substring(pan.length() - 4);
    }
}
