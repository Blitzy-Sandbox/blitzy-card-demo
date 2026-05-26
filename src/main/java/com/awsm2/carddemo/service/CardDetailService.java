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

import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * Card detail / inquiry service &mdash; the Java target for the
 * COBOL/CICS program {@code app/cbl/COCRDSLC.cbl} (CICS transaction id
 * {@code CCDL}, mapset {@code COCRDSL}, map {@code CCRDSLA}).
 *
 * <p>This service returns the read-only detail view for a single card
 * identified by its 16-digit Primary Account Number (PAN). In the COBOL
 * source, {@code COCRDSLC.cbl} executes a single
 * {@code EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
 * (read-only &mdash; <em>not</em> {@code READ UPDATE}) against the
 * {@code CARDDAT} VSAM KSDS cluster, populates the {@code CCRDSLAO} output
 * redefine of the {@code CCRDSLA} BMS symbolic map, and issues an
 * {@code EXEC CICS SEND MAP} to render the record as a read-only display
 * to a 3270 terminal. In the Java target, this service performs:</p>
 * <ol>
 *   <li>field-level validation of the supplied card number (16-digit
 *       numeric edit, mirroring COBOL paragraph {@code 2220-EDIT-CARD}
 *       at lines 685&ndash;724);</li>
 *   <li>cache-aside lookup against ElastiCache Redis via
 *       {@link CacheService} (net-new capability per AAP &sect;0.7.1
 *       &mdash; the COBOL source has no caching);</li>
 *   <li>JPA {@code findById} lookup against {@link CardRepository}
 *       (replaces COBOL paragraph {@code 9100-GETCARD-BYACCTCARD} at
 *       lines 736&ndash;777);</li>
 *   <li>assembly of a {@link CardDetailDto} response built from the
 *       {@link Card} entity (replaces COBOL paragraph
 *       {@code 1200-SETUP-SCREEN-VARS});</li>
 *   <li>cache populate-on-miss (cache-aside contract);</li>
 *   <li>return of the DTO &mdash; consumed by the REST controller layer
 *       and serialized to JSON as the response body of
 *       {@code GET /api/cards/{cardNumber}}.</li>
 * </ol>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COCRDSLC.cbl} (CICS TRANID
 *       {@code 'CCDL'}, file {@code 'CARDDAT'}; AAP &sect;0.4.1 online
 *       programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COCRDSL.bms} (mapset
 *       {@code COCRDSL}, map {@code CCRDSLA}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COCRDSL.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes) &mdash; mapped to JPA entity
 *       {@link Card}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}
 *       (KEYS(16 0), RECORDSIZE(150 150)) &mdash; replaced by the
 *       {@code cards} PostgreSQL table per V002 migration.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COCRDSLC.cbl &harr; CardDetailService.getCardDetail(...)</caption>
 *   <tr><th>COBOL paragraph (line range)</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN} (247&ndash;392)</td>
 *       <td>{@link #getCardDetail(String)} entry point</td></tr>
 *   <tr><td>{@code 2220-EDIT-CARD} (685&ndash;724) &mdash;
 *       16-digit numeric edit on {@code CC-CARD-NUM}</td>
 *       <td>The validation cascade at the top of
 *       {@link #getCardDetail(String)} (null/blank check, length-16
 *       check, numeric-only check). Throws {@link ValidationException}
 *       on failure &mdash; mirrors COBOL {@code SET INPUT-ERROR TO TRUE}
 *       and the {@code SEARCHED-CARD-NOT-NUMERIC} message.</td></tr>
 *   <tr><td>{@code 9000-READ-DATA} / {@code 9100-GETCARD-BYACCTCARD}
 *       (726&ndash;777) &mdash; {@code EXEC CICS READ FILE('CARDDAT')
 *       INTO(CARD-RECORD) RIDFLD(WS-CARD-RID-CARDNUM)}</td>
 *       <td>{@code cardRepository.findById(trimmed)} preceded by a
 *       Redis cache-aside lookup via {@link CacheService}</td></tr>
 *   <tr><td>{@code WHEN DFHRESP(NOTFND)} branch (lines 755&ndash;761)
 *       returning {@code DID-NOT-FIND-ACCTCARD-COMBO} message via
 *       FILE STATUS 23</td>
 *       <td>{@link RecordNotFoundException} (translated to HTTP 404 by
 *       {@code GlobalExceptionHandler})</td></tr>
 *   <tr><td>{@code 1200-SETUP-SCREEN-VARS} (457&ndash;500) /
 *       {@code SEND-MAP CCRDSLA}</td>
 *       <td>{@code new CardDetailDto(...)} canonical record constructor
 *       returning the {@link CardDetailDto} JSON envelope</td></tr>
 * </table>
 *
 * <h2>Read-only semantics</h2>
 * <p>This service declares {@code @Transactional(readOnly = true)} on
 * its public method per AAP &sect;0.7.1. The underlying
 * {@link CardRepository#findById} call holds a {@code READ COMMITTED}
 * transaction without locking &mdash; matching the COBOL source which
 * uses {@code EXEC CICS READ} (not {@code READ UPDATE}). The optimistic-
 * lock {@code @Version} value is intentionally <em>not</em> propagated
 * through the read-only {@link CardDetailDto} contract; mutating clients
 * must use the separate {@code CardUpdateDto} contract handled by
 * {@code CardUpdateService} (which DOES carry the version field).</p>
 *
 * <h2>Cache-aside pattern (AAP &sect;0.3.3, &sect;0.7.1)</h2>
 * <p>This service implements the cache-aside read protocol:</p>
 * <ol>
 *   <li>On every {@code getCardDetail} invocation, after validation,
 *       attempt to fetch the previously-cached {@link CardDetailDto}
 *       via {@link CacheService#get(String, String, Class)}.</li>
 *   <li>On a cache hit, return the cached DTO immediately &mdash;
 *       database round trip is avoided.</li>
 *   <li>On a cache miss (including any Redis driver failure, which the
 *       cache-aside contract treats as a miss per AAP &sect;0.7.1
 *       fail-open semantics), load the entity from RDS via
 *       {@link CardRepository#findById(Object)}.</li>
 *   <li>Build the DTO via the {@link CardDetailDto} canonical record
 *       constructor.</li>
 *   <li>Populate the cache via {@link CacheService#put} with TTL
 *       {@link #CACHE_TTL} (5 minutes, consistent with
 *       {@code AccountViewService} per AAP &sect;0.7.1
 *       "TTL aligned to transaction frequency").</li>
 *   <li>Return the freshly-built DTO.</li>
 * </ol>
 *
 * <p>Cache invalidation is performed by writers (e.g.,
 * {@code CardUpdateService}, {@code TransactionPostingService}) which
 * call {@link CacheService#evict} after a successful database write.
 * This service performs only reads and never evicts.</p>
 *
 * <h2>PCI-DSS guard rails (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>The {@link CardDetailDto} carries the <b>full 16-digit PAN</b> in
 * its {@link CardDetailDto#cardNumber()} component because the legacy
 * 3270 detail screen renders the full card number in the
 * {@code CARDSID} field. The DTO's overridden {@link CardDetailDto#toString()}
 * masks the PAN to the last 4 digits ({@code ************XXXX}) for
 * log-safe output; the unmasked PAN exists only in memory for the
 * duration of a single REST request. The CVV is not stored on the
 * {@link Card} entity at all (QA finding DB1; PCI-DSS v4.0
 * Requirement 3.2 prohibits CVV persistence post-authorization) and
 * therefore cannot be exposed by this DTO.</p>
 *
 * <p>This service NEVER logs the unmasked PAN. Every diagnostic log
 * statement uses the private {@link #maskPan(String)} helper to render
 * the PAN as {@code ************XXXX}. Cache keys use the unmasked PAN
 * (the PAN is required for keyed lookup); the cache stores the DTO
 * (which itself contains the unmasked PAN component); both the on-the-
 * wire Redis traffic (TLS 1.2+) and the at-rest Redis storage (KMS
 * CMK) are encrypted per AAP &sect;0.6.6.</p>
 *
 * <h2>Layered architecture and dependency injection</h2>
 * <p>Per AAP &sect;0.3.3 (Layered Architecture) and &sect;0.7.1
 * (Dependency Injection), this service uses constructor injection only
 * &mdash; no field-level {@code @Autowired}. Collaborators are
 * {@link CardRepository} (the Spring Data JPA repository for the
 * {@code cards} table) and {@link CacheService} (the ElastiCache Redis
 * adapter). No AWS SDK calls are inlined in this service per AAP
 * &sect;0.7.3 (AWS-adapter isolation); cache operations are delegated
 * to {@link CacheService}.</p>
 *
 * <h2>Exception translation</h2>
 * <ul>
 *   <li>{@link ValidationException} &rarr; HTTP 400 Bad Request via
 *       {@code GlobalExceptionHandler}. Thrown when the supplied card
 *       number fails the 16-digit numeric edit (mirrors COBOL
 *       {@code 2220-EDIT-CARD} paragraph). Replaces the COBOL
 *       {@code SET INPUT-ERROR TO TRUE} state plus the
 *       {@code SEARCHED-CARD-NOT-NUMERIC} return message.</li>
 *   <li>{@link RecordNotFoundException} &rarr; HTTP 404 Not Found via
 *       {@code GlobalExceptionHandler}. Thrown when no card row exists
 *       for the supplied card number (mirrors COBOL
 *       {@code WHEN DFHRESP(NOTFND)} branch in paragraph
 *       {@code 9100-GETCARD-BYACCTCARD} at lines 755&ndash;761; COBOL
 *       FILE STATUS '23'). The exception message contains the masked
 *       PAN only &mdash; the full PAN is never logged or returned in
 *       the JSON error envelope.</li>
 * </ul>
 *
 * @see CardRepository
 * @see CardDetailDto
 * @see CacheService
 * @see Card
 * @see RecordNotFoundException
 * @see ValidationException
 */
@Service
public class CardDetailService {

    /**
     * SLF4J logger for trace-level cache hit/miss tracking, info-level
     * operational logging, and warn-level fallback diagnostics.
     *
     * <p>All log statements emitted by this class are PCI-DSS-safe per
     * AAP &sect;0.6.6: the unmasked Primary Account Number (PAN) is
     * NEVER logged. The private {@link #maskPan(String)} helper renders
     * the PAN as {@code ************XXXX} (last 4 digits visible) for
     * every diagnostic message.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardDetailService.class);

    /**
     * Cache namespace for the {@link CardDetailDto} cache-aside entries
     * stored in ElastiCache Redis via {@link CacheService}.
     *
     * <p>Per AAP &sect;0.7.1 ("ElastiCache (Redis) used for account
     * balance caching &mdash; cache-aside pattern with TTL aligned to
     * transaction frequency"), the namespace partitions card-detail
     * DTOs from other cached entity views (account, customer,
     * disclosure-group, transaction-type). The effective Redis key
     * is {@code "carddemo:card-detail:<cardNum>"} (the
     * {@code "carddemo:"} prefix is applied by {@link CacheService}).</p>
     */
    private static final String CACHE_NAMESPACE = "card-detail";

    /**
     * Cache time-to-live for {@link CardDetailDto} entries.
     *
     * <p>Set to 5 minutes per AAP &sect;0.7.1 ("TTL aligned to
     * transaction frequency"). Card details change relatively
     * infrequently (status flips, expiration renewals, embossed-name
     * updates), so 5 minutes balances staleness against database load.
     * Writers ({@code CardUpdateService}) invalidate the cache
     * immediately on successful save, so the TTL is only the fallback
     * for indirectly-stale entries.</p>
     *
     * <p>This value matches {@code AccountViewService.CACHE_TTL} for
     * consistency across the card/account view cache-aside services.</p>
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * The required length of a valid card number Primary Account
     * Number (PAN) in characters.
     *
     * <p>16 characters per the COBOL source layout
     * {@code app/cpy/CVACT02Y.cpy:L5} (
     * {@code CARD-NUM PIC X(16)}) and the COBOL edit paragraph
     * {@code 2220-EDIT-CARD} in {@code app/cbl/COCRDSLC.cbl} which
     * requires the card filter to be "a 16 digit number".</p>
     */
    private static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Spring Data JPA repository for the {@code cards} table &mdash;
     * the primary source-of-record for card data. Replaces the
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} VSAM cluster per AAP
     * &sect;0.6.2.
     */
    private final CardRepository cardRepository;

    /**
     * ElastiCache Redis cache-aside adapter (AAP &sect;0.7.1). Used
     * to cache {@link CardDetailDto} results keyed by card number,
     * with TTL {@link #CACHE_TTL}. Fail-open per AAP &sect;0.7.1
     * &mdash; any Redis failure degrades to a database miss without
     * propagating to the caller.
     */
    private final CacheService cacheService;

    /**
     * Constructor injection of collaborators per AAP &sect;0.7.1
     * "Dependency injection for loose coupling" and the no-
     * {@code @Autowired} field-injection rule.
     *
     * @param cardRepository Spring Data JPA repository for the
     *                       {@code cards} table; never {@code null}.
     * @param cacheService   ElastiCache Redis cache-aside adapter;
     *                       never {@code null}.
     */
    public CardDetailService(CardRepository cardRepository, CacheService cacheService) {
        // Constructor injection only (AAP §0.7.1 / §0.7.3 — adapter
        // isolation rule requires AWS service integrations to be
        // accessed via dedicated adapter beans, never inlined here).
        this.cardRepository = cardRepository;
        this.cacheService = cacheService;
    }

    /**
     * Returns the detail view for the card identified by
     * {@code cardNumber}. Equivalent to {@code COCRDSLC.cbl}'s
     * {@code 0000-MAIN} paragraph after the {@code 2220-EDIT-CARD}
     * edit-validation: a single
     * {@code EXEC CICS READ FILE('CARDDAT')} keyed by
     * {@code CARD-NUM}, returning the record for display or signalling
     * the {@code DFHRESP(NOTFND)} branch.
     *
     * <h4>Flow</h4>
     * <ol>
     *   <li><b>Validate</b> &mdash; null/blank check, length-16 check,
     *       numeric-only check. Throws {@link ValidationException} on
     *       any failure (mirrors COBOL paragraph
     *       {@code 2220-EDIT-CARD} at lines 685&ndash;724).</li>
     *   <li><b>Cache-aside lookup</b> &mdash; query Redis for a
     *       previously-cached DTO under namespace
     *       {@link #CACHE_NAMESPACE} keyed by the trimmed PAN. On hit,
     *       return immediately. On miss, proceed to the database
     *       lookup. Cache failures are treated as misses per AAP
     *       &sect;0.7.1.</li>
     *   <li><b>Database read</b> &mdash; invoke
     *       {@link CardRepository#findById(Object)} keyed by the
     *       16-character {@code cardNum} primary key (mirrors COBOL
     *       {@code 9100-GETCARD-BYACCTCARD} paragraph). On
     *       {@link Optional#empty()}, throw
     *       {@link RecordNotFoundException} (mirrors COBOL
     *       {@code WHEN DFHRESP(NOTFND)} branch).</li>
     *   <li><b>Build DTO</b> &mdash; instantiate {@link CardDetailDto}
     *       via the canonical record constructor (no {@code of(...)}
     *       factory is exposed on the record type).</li>
     *   <li><b>Cache populate</b> &mdash; store the freshly-built DTO
     *       in Redis under the same namespace+key for subsequent
     *       reads, with TTL {@link #CACHE_TTL}.</li>
     *   <li><b>Return</b> &mdash; return the DTO.</li>
     * </ol>
     *
     * @param cardNumber the 16-digit card number (PAN); leading and
     *                   trailing whitespace is trimmed. Must not be
     *                   {@code null}, blank, or non-numeric, and must
     *                   be exactly 16 characters long after trimming.
     * @return the {@link CardDetailDto} read-only view for the
     *         requested card; never {@code null}.
     * @throws ValidationException if {@code cardNumber} is null/blank,
     *         is not exactly 16 characters after trimming, or contains
     *         any non-digit character &mdash; mirrors COBOL paragraph
     *         {@code 2220-EDIT-CARD} (HTTP 400 via
     *         {@code GlobalExceptionHandler}).
     * @throws RecordNotFoundException if no card row exists for
     *         {@code cardNumber} &mdash; mirrors COBOL FILE STATUS 23
     *         {@code DFHRESP(NOTFND)} handling at COCRDSLC paragraph
     *         {@code 9100-GETCARD-BYACCTCARD} lines 755&ndash;761
     *         (HTTP 404 via {@code GlobalExceptionHandler}).
     */
    @Transactional(readOnly = true)
    public CardDetailDto getCardDetail(String cardNumber) {
        // COBOL: COCRDSLC:2220-EDIT-CARD — 16-digit numeric edit on the
        // card filter. The COBOL source sets INPUT-ERROR + FLG-CARDFILTER-
        // NOT-OK and emits "CARD ID FILTER, IF SUPPLIED MUST BE A 16 DIGIT
        // NUMBER" to WS-RETURN-MSG; the Java target raises
        // ValidationException which GlobalExceptionHandler translates to
        // HTTP 400 Bad Request.
        if (cardNumber == null || cardNumber.isBlank()) {
            // COBOL: 2220-EDIT-CARD — IF CC-CARD-NUM EQUAL LOW-VALUES OR
            // EQUAL SPACES OR CC-CARD-NUM-N EQUAL ZEROS → SET WS-PROMPT-
            // FOR-CARD TO TRUE ("Card number not provided").
            throw new ValidationException("Card number must not be empty");
        }
        String trimmed = cardNumber.trim();
        if (trimmed.length() != CARD_NUMBER_LENGTH) {
            // COBOL: 2220-EDIT-CARD — "Card number if supplied must be a
            // 16 digit number" (length check; the COBOL field is
            // CC-CARD-NUM PIC X(16) — strictly 16 characters).
            throw new ValidationException("Card number must be 16 digits");
        }
        for (char c : trimmed.toCharArray()) {
            if (c < '0' || c > '9') {
                // COBOL: 2220-EDIT-CARD — IF CC-CARD-NUM IS NOT NUMERIC →
                // SET FLG-CARDFILTER-NOT-OK TO TRUE; "CARD ID FILTER, IF
                // SUPPLIED MUST BE A 16 DIGIT NUMBER".
                throw new ValidationException("Card number must be numeric");
            }
        }

        // Cache-aside (AAP §0.3.3, §0.7.1): READ path = check cache first.
        // CardDetailDto serialises cleanly via the Redis Jackson JSON
        // value serializer wired in RedisConfig. Failures fall through as
        // a miss per CacheService fail-open contract — the business flow
        // proceeds to the database read.
        Optional<CardDetailDto> cached =
                cacheService.get(CACHE_NAMESPACE, trimmed, CardDetailDto.class);
        if (cached.isPresent()) {
            // Trace-level log only — the cardNumber is masked. The full
            // PAN is intentionally NEVER logged per AAP §0.6.6.
            LOG.debug("CardDetailService cache hit cardNumber={}", maskPan(trimmed));
            return cached.get();
        }

        // COBOL: COCRDSLC:9100-GETCARD-BYACCTCARD — EXEC CICS READ
        // FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD).
        // Spring Data JPA findById returns Optional.empty() on NOTFND;
        // Optional.orElseThrow bridges that to the typed domain exception
        // RecordNotFoundException — the GlobalExceptionHandler maps it to
        // HTTP 404 Not Found with COBOL FILE STATUS 23 semantics.
        Card card = cardRepository.findById(trimmed)
                .orElseThrow(() -> {
                    // PAN-masked diagnostic: the unmasked PAN is NEVER
                    // emitted (AAP §0.6.6 PCI-DSS guard rail). The masked
                    // form preserves enough information for support to
                    // correlate with downstream events while remaining
                    // safe to ship to CloudWatch Logs / OpenSearch.
                    LOG.debug("CardDetailService card not found cardNumber={}",
                            maskPan(trimmed));
                    // QA finding D3: use the standardized reason code
                    // "CARD_NOT_FOUND" (matching CardUpdateService's
                    // not-found error code) rather than the bare entity
                    // name "Card" which produced the misleading
                    // {"code":"Card",...} envelope flagged in CP2 QA
                    // testing. The error message is unchanged.
                    return new RecordNotFoundException("CARD_NOT_FOUND",
                            "cardNumber=" + maskPan(trimmed));
                });

        // COBOL: COCRDSLC:1200-SETUP-SCREEN-VARS — move CARD-EMBOSSED-
        // NAME / CARD-EXPIRAION-DATE / CARD-ACTIVE-STATUS into the
        // CCRDSLAO output map fields. Java target builds the DTO via the
        // canonical record constructor (no static of(Card) factory is
        // exposed on the CardDetailDto record type). The constructor
        // argument order matches the record component declaration order.
        //
        // QA finding D2 (PCI-DSS): mask the PAN at the producer so the
        // GET /api/cards/{cardNumber} response body never carries the
        // unmasked 16-digit PAN. This matches the masking that the list
        // endpoint already applies in CardListService.toRow().
        //
        // QA finding U2 (API contract): expose the JPA @Version token
        // on the response so clients can issue PUT requests carrying a
        // known-good version (the value previously had no REST-visible
        // surface and clients were forced to guess version=0).
        CardDetailDto dto = new CardDetailDto(
                maskPan(card.getCardNum()),
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus(),
                card.getVersion());

        // Cache-aside (AAP §0.3.3, §0.7.1): on miss, populate the cache
        // for subsequent reads. TTL = 5 minutes (consistent with
        // AccountViewService); writers (CardUpdateService) invalidate the
        // cache on successful save so the TTL is only the fallback for
        // indirectly-stale entries. Failures are absorbed by CacheService
        // per the fail-open contract — no exception propagates here.
        cacheService.put(CACHE_NAMESPACE, trimmed, dto, CACHE_TTL);

        // PCI-DSS-safe info log: dto.toString() masks the PAN per the
        // CardDetailDto.toString() override (AAP §0.6.6). The raw
        // cardNumber on the DTO is the unmasked PAN — it MUST NOT be
        // logged directly; only the masked toString() form is safe to
        // emit. Logging at INFO level so production observability sees
        // every card-detail read.
        LOG.info("CardDetailService returning detail cardNumber={}",
                maskPan(trimmed));
        return dto;
    }

    /**
     * Masks a card number Primary Account Number (PAN) for PCI-DSS-safe
     * logging and error-message rendering.
     *
     * <p>Returns a 16-character string of the form
     * {@code "************XXXX"} where {@code XXXX} are the last 4
     * characters of the input. The PAN-masking pattern matches PCI-DSS
     * v4.0 Requirement 3.4.1 (a maximum of the first 6 and last 4
     * digits may be displayed; CardDemo's policy is to display only
     * the last 4 to be conservative) per AAP &sect;0.6.6. For
     * {@code null} or short inputs (length &lt; 4), returns the
     * 12-asterisk literal {@code "************"} as a defensive
     * fallback &mdash; never returning the raw value.</p>
     *
     * <p>This helper is the SOLE PAN-rendering path inside this service.
     * The unmasked {@code cardNumber} parameter exists in memory only as
     * a local variable inside {@link #getCardDetail(String)}; every log
     * statement, exception message, and externally-visible diagnostic
     * uses this helper. The
     * {@link CardDetailDto#toString()} override applies an equivalent
     * masking pattern for any DTO-level logging.</p>
     *
     * @param pan the card number Primary Account Number; may be
     *            {@code null}.
     * @return a masked representation showing only the last 4
     *         characters; never {@code null}, never the unmasked
     *         input.
     */
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            // Defensive fallback: a short or null input is rendered as
            // the 12-asterisk literal so downstream log processors see a
            // well-defined fixed-width token regardless of input shape.
            // This also prevents accidental disclosure of any partial-
            // length PAN-like value.
            return "************";
        }
        // PCI-DSS v4.0 Requirement 3.4.1 (AAP §0.6.6): mask all but the
        // last four digits of the PAN. The 12-asterisk prefix matches
        // the CardDetailDto.toString() masking pattern for consistency
        // across log statements and DTO-level rendering.
        return "************" + pan.substring(pan.length() - 4);
    }
}
