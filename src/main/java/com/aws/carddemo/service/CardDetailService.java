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

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Card-detail service — the Java migration of the CICS card-detail
 * program {@code app/cbl/COCRDSLC.cbl} (TRANID {@code CCDL}). Performs a
 * read-only single-key lookup of a {@link Card} from the {@code CARDDAT}
 * VSAM KSDS replacement (PostgreSQL {@code cards} table) and returns
 * either a populated {@link CardDetailResponse} or a reject response
 * carrying the COBOL-equivalent message.
 *
 * <h2>COBOL Provenance — COCRDSLC.cbl</h2>
 *
 * <p>The original {@code 9100-GETCARD-BYACCTCARD} paragraph (lines
 * 736–777) orchestrates the single-key read against the {@code CARDDAT}
 * VSAM KSDS:
 *
 * <ol>
 *   <li>{@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
 *       by the 16-character {@code CARD-NUM} primary key (lines 742–750).</li>
 *   <li>{@code WHEN DFHRESP(NORMAL)} — record found, the field-mapping
 *       block populates the {@code CCRDSLA} BMS map (replaced in the
 *       Java migration by
 *       {@link CardDetailResponse#success(Card, boolean)}).</li>
 *   <li>{@code WHEN DFHRESP(NOTFND)} — reject with the COBOL message
 *       {@code 'Did not find cards for this search condition'} (line
 *       760), surfaced in the Java migration as a normalised
 *       {@link #MSG_CARD_NOT_FOUND} that still anchors on the "not
 *       found" phrase for downstream-consumer compatibility.</li>
 *   <li>{@code WHEN OTHER} — I/O error, lines 762–771; surfaces in
 *       production as a {@link org.springframework.dao.DataAccessException}
 *       propagated to the controller layer's exception-handler chain
 *       rather than producing a {@link CardDetailResponse#failure(String)}
 *       value.</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>BMS-screen population removed.</b> The COBOL field-mapping block
 *       writes directly to the {@code CCRDSLA} BMS map fields; the Java
 *       migration returns a {@link CardDetailResponse} DTO that the REST
 *       controller layer serialises to JSON. The field mapping is
 *       identical (see {@link CardDetailResponse#success(Card, boolean)}).</li>
 *   <li><b>CICS error handling replaced.</b> The COBOL
 *       {@code HANDLE ABEND LABEL(ABEND-ROUTINE)} and the
 *       {@code WHEN OTHER} branch are collapsed into the standard Spring
 *       {@link org.springframework.dao.DataAccessException} propagation
 *       pattern: on infrastructure failures the exception bubbles up to
 *       the controller layer's exception handler. The NOTFND branch
 *       remains in this class because it is a business-logic reject path,
 *       not an infrastructure failure.</li>
 *   <li><b>{@code expired} display flag added.</b> The COBOL workflow
 *       displays the {@code CARD-EXPIRAION-DATE} field verbatim and lets
 *       the operator interpret it. The Java migration derives a boolean
 *       {@code expired} flag (computed by {@link #isExpired(String)}
 *       against the injected {@link Clock}) on every successful lookup
 *       and carries it on the response DTO — a display-only enhancement
 *       that did not exist in COBOL but is needed for the REST API
 *       contract. The {@link Card#getExpirationDate()} string value
 *       itself is untouched (preserving AAP §0.10.4 immutable
 *       boundaries).</li>
 * </ul>
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full
 * Spring application context is wired up. For now, the constructor
 * accepts collaborators directly so unit tests can wire mocks (and a
 * fixed clock) without a Spring context — matching the convention
 * established by {@link AccountViewService}, {@link AuthenticationService},
 * and {@link TransactionDetailService}.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service contains the COMPLETE business logic for the
 * card-detail workflow (the NOTFND reject path and the {@code expired}
 * derivation are visible in the single {@link #getCard(String)} entry
 * point plus the {@link #isExpired(String)} helper that the entry point
 * delegates to). The corresponding {@code CardDetailServiceTest}
 * exercises every branch via real method calls — no business logic is
 * duplicated in the test.
 *
 * @see CardDetailResponse
 * @see Card
 * @see CardRepository
 */
@Service
public class CardDetailService {

    /**
     * Reject message returned when the {@code CARDDAT} lookup yields
     * {@code Optional.empty()} (COBOL {@code DFHRESP(NOTFND)} branch).
     *
     * <p>This is a Java-migration normalisation of the original COBOL
     * messages — the COBOL workflow uses two slightly different
     * phrasings ({@code 'Did not find cards for this search condition'}
     * on the card-number path at line 760 and
     * {@code 'Did not find this account in cards database'} on the
     * account-number alternate-index path at line 799). The Java
     * migration surfaces a single normalised message anchored on the
     * "not found" phrase that downstream consumers and tests look for
     * (per AAP §0.10.4 immutable-boundaries clause). The trailing
     * ellipsis matches the convention established by
     * {@link AuthenticationService#MSG_USER_NOT_FOUND} and
     * {@link TransactionDetailService#MSG_TRANSACTION_NOT_FOUND}.
     */
    static final String MSG_CARD_NOT_FOUND = "Card number not found...";

    private final CardRepository cardRepository;
    private final Clock clock;

    /**
     * Constructs a new {@code CardDetailService}.
     *
     * @param cardRepository JPA repository for {@link Card} lookups (Java
     *                       replacement for COBOL
     *                       {@code EXEC CICS READ DATASET('CARDDAT')})
     * @param clock          {@link Clock} used to compute the
     *                       {@code expired} display flag; injectable so
     *                       tests can replace with
     *                       {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
     *                       for deterministic behaviour
     */
    public CardDetailService(CardRepository cardRepository, Clock clock) {
        this.cardRepository = cardRepository;
        this.clock = clock;
    }

    /**
     * Look up a card by its 16-character {@code CARD-NUM} primary key.
     * Returns either a populated success response carrying all
     * {@code CARD-*} fields (plus the derived {@code expired} display
     * flag) or a reject response carrying the COBOL-equivalent
     * {@link #MSG_CARD_NOT_FOUND} message when the card does not exist.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Look up the card via
     *       {@link CardRepository#findById(Object)}. On
     *       {@code Optional.empty()} → reject with
     *       {@link #MSG_CARD_NOT_FOUND} (COBOL
     *       {@code DFHRESP(NOTFND)} branch).</li>
     *   <li>Derive the {@code expired} display flag by comparing the
     *       card's {@code CARD-EXPIRAION-DATE} against
     *       {@code LocalDate.now(clock)} via
     *       {@link #isExpired(String)}.</li>
     *   <li>Build and return a populated {@link CardDetailResponse}
     *       carrying all card fields and the derived {@code expired}
     *       flag via the
     *       {@link CardDetailResponse#success(Card, boolean)} factory.</li>
     * </ol>
     *
     * <p>Infrastructure errors (database unreachable, network failure,
     * etc.) surface as {@link org.springframework.dao.DataAccessException}
     * subclasses thrown by the repository; the service does not catch
     * them, letting the controller layer's exception-handler chain produce
     * the Java equivalent of the COBOL {@code 'File Error: READ on
     * CARDDAT ...'} response.
     *
     * @param cardNumber 16-character {@code CARD-NUM} primary key
     *                   (e.g. {@code "4111111111111101"})
     * @return a {@link CardDetailResponse} encoding success (with all
     *         hydrated fields plus the derived {@code expired} flag) or
     *         failure (with the NOTFND reject message)
     */
    public CardDetailResponse getCard(String cardNumber) {
        // Step 1 — CARDDAT read (COBOL §9100-GETCARD-BYACCTCARD, lines
        // 736–777). The repository .findById(...) returns Optional.empty()
        // for the DFHRESP(NOTFND) case; Optional.of(card) for
        // DFHRESP(NORMAL). I/O errors (the COBOL WHEN OTHER branch)
        // propagate as DataAccessException subclasses and are handled by
        // the controller layer's exception-handler chain, mirroring the
        // COBOL HANDLE ABEND fallback.
        Optional<Card> cardOpt = cardRepository.findById(cardNumber);
        if (cardOpt.isEmpty()) {
            // COBOL: WHEN DFHRESP(NOTFND) → 'Did not find cards for this search condition'
            // (line 760), normalised in the Java migration to MSG_CARD_NOT_FOUND.
            return CardDetailResponse.failure(MSG_CARD_NOT_FOUND);
        }

        // Step 2 — derive the Java-migration expired display flag from
        // the card's expiration date vs. the injected clock. The flag is
        // a display-only enhancement that did not exist in the COBOL
        // source; the {@link Card#getExpirationDate()} string value
        // itself is untouched (preserving AAP §0.10.4 immutable
        // boundaries — downstream consumers reading the string form
        // continue to see the byte-for-byte COBOL baseline).
        Card card = cardOpt.get();
        boolean expired = isExpired(card.getExpirationDate());

        // Step 3 — build and return the success response with the
        // hydrated card and the derived expired flag (COBOL field-mapping
        // block populating CCRDSLA).
        return CardDetailResponse.success(card, expired);
    }

    /**
     * Determine whether the supplied ISO-{@code YYYY-MM-DD} expiration
     * date string represents a date strictly earlier than "today" as
     * resolved via the injected {@link #clock}.
     *
     * <p>The comparison is intentionally <em>strict</em>: a card whose
     * expiration date equals today's date is NOT considered expired
     * (the card remains usable through the end of the calendar day on
     * its expiration date). This matches the conventional payment-network
     * behaviour where a card expiring on {@code 2024-01-15} can be
     * authorised at {@code 2024-01-15T23:59:59Z} but not at
     * {@code 2024-01-16T00:00:00Z}.
     *
     * <p>An unparseable expiration date string (null, blank, malformed)
     * returns {@code false} (the card is treated as not expired). This
     * is deliberately permissive — the COBOL workflow displays the
     * raw string verbatim without any date validation, and the Java
     * migration's {@code expired} flag is a display-only enhancement
     * that should not promote an unparseable string into a reject path.
     * The downstream consumer can re-validate the string if strict
     * date semantics are required.
     *
     * @param expirationDate the ISO-style {@code YYYY-MM-DD} date string
     *                       from {@link Card#getExpirationDate()}; may be
     *                       {@code null} or malformed
     * @return {@code true} when the date parses successfully and is
     *         strictly before {@code LocalDate.now(clock)}; {@code false}
     *         otherwise (including null/malformed input)
     */
    private boolean isExpired(String expirationDate) {
        if (expirationDate == null || expirationDate.isBlank()) {
            return false;
        }
        try {
            LocalDate exp = LocalDate.parse(expirationDate.trim());
            return exp.isBefore(LocalDate.now(clock));
        } catch (DateTimeParseException ex) {
            // Unparseable expiration date — treat as not expired to avoid
            // promoting a display-only flag derivation into a reject path
            // (see method javadoc).
            return false;
        }
    }
}
