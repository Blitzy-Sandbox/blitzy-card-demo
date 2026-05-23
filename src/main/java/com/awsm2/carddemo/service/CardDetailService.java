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

import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Card-detail (read-only view) service &mdash; the Java target for the
 * COBOL/CICS program {@code app/cbl/COCRDSLC.cbl} (CICS transaction id
 * {@code CCDL}).
 *
 * <p>This service returns the detail view for a single card identified by
 * its 16-digit card number. In the COBOL source, {@code COCRDSLC.cbl}
 * issues an {@code EXEC CICS READ} (read-only, NOT {@code READ UPDATE})
 * against the {@code CARDDAT} VSAM KSDS, populates the {@code CCRDSLAO}
 * output redefine of the {@code CCRDSLA} BMS symbolic map, and issues an
 * {@code EXEC CICS SEND MAP} to render the record as a read-only display.
 * In the Java target, this service performs a JPA {@code findById}
 * lookup against the {@link CardRepository} and assembles a
 * {@link CardDetailDto} response.</p>
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
 *       (KEYS(16 0), RECORDSIZE(150 150)).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COCRDSLC.cbl &harr; CardDetailService.viewCard(...)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td>
 *       <td>{@link #viewCard(String)}</td></tr>
 *   <tr><td>{@code 9000-READ-DATA} / {@code EXEC CICS READ FILE('CARDDAT')
 *       INTO(CARD-RECORD) RIDFLD(WS-CARD-NUM)}</td>
 *       <td>{@code cardRepository.findById(cardNumber)}</td></tr>
 *   <tr><td>{@code 'WHEN DFHRESP(NOTFND)' branch returning
 *       {@code NOT-FOUND-MSG} via FILE STATUS 23}</td>
 *       <td>{@link RecordNotFoundException} (translated to HTTP 404 by
 *       {@code GlobalExceptionHandler})</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO} / {@code SEND-MAP CCRDSLA}</td>
 *       <td>{@code toDto(card)} returning the {@link CardDetailDto}
 *       record</td></tr>
 * </table>
 *
 * <h2>Read-only semantics</h2>
 * <p>This service declares {@code @Transactional(readOnly = true)} on
 * its public method. The underlying {@link CardRepository#findById}
 * call holds a {@code READ COMMITTED} transaction without locking
 * &mdash; matching the COBOL source which uses {@code EXEC CICS READ}
 * (not {@code READ UPDATE}). The optimistic-lock {@code @Version}
 * value is intentionally NOT propagated through the read-only
 * {@link CardDetailDto} contract; mutating clients must use the
 * separate {@code CardUpdateDto} contract handled by
 * {@code CardUpdateService} (which DOES carry the version field).</p>
 *
 * <h2>PCI-DSS guard rails</h2>
 * <p>This service returns the <b>full 16-digit PAN</b> in the
 * {@link CardDetailDto#cardNumber()} field of the detail response,
 * matching the legacy 3270 detail screen which renders the full card
 * number in the {@code CARDSID} field. CVV ({@code cardCvvCd} on the
 * entity) is <b>NEVER</b> returned in the DTO &mdash; the
 * {@link CardDetailDto} record does not declare a CVV component, which
 * is the spec-mandated PCI-DSS v4.0 Requirement 3.2 behavior (sensitive
 * authentication data must not be stored after authorization). Callers
 * MUST enforce TLS 1.2+ in transit (the ALB listener already does so
 * per AAP &sect;0.6.6) and log only via the entity's masked
 * {@code toString()} which renders the PAN as {@code ****-****-****-1234}.
 *
 * @see CardRepository
 * @see CardDetailDto
 * @see Card
 */
@Service
public class CardDetailService {

    private static final Logger LOG = LoggerFactory.getLogger(CardDetailService.class);

    private final CardRepository cardRepository;

    /**
     * Constructor injection of the {@link CardRepository} collaborator
     * per AAP &sect;0.7.1 "Dependency injection for loose coupling".
     */
    public CardDetailService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns the detail view for the card identified by
     * {@code cardNumber}. Equivalent to {@code COCRDSLC.cbl}'s
     * {@code 0000-MAIN} paragraph after edit-validation: a single
     * {@code EXEC CICS READ FILE('CARDDAT')} keyed by
     * {@code CARD-NUM}, returning the record for display or signalling
     * NOTFND.
     *
     * @param cardNumber the 16-digit card number (PAN)
     * @return the {@link CardDetailDto} read-only view
     * @throws RecordNotFoundException when no card row exists for
     *         {@code cardNumber} &mdash; mirrors COBOL FILE STATUS 23
     *         {@code DFHRESP(NOTFND)} handling (HTTP 404 via
     *         {@code GlobalExceptionHandler})
     */
    @Transactional(readOnly = true)
    public CardDetailDto viewCard(String cardNumber) {
        // COBOL: 9000-READ-DATA — EXEC CICS READ FILE('CARDDAT')
        //        INTO(CARD-RECORD) RIDFLD(WS-CARD-NUM)
        Card card = cardRepository.findById(cardNumber)
                .orElseThrow(() -> {
                    // COBOL: DFHRESP(NOTFND) branch → emits
                    // "Card Number NOT found..." to MAP MSG field.
                    // FILE STATUS 23 in batch source equivalents.
                    LOG.debug("CardDetailService: card not found");
                    return new RecordNotFoundException("CARD_NOT_FOUND",
                            "Card not found");
                });

        // COBOL: POPULATE-HEADER-INFO + SEND-MAP CCRDSLA — produce the
        // read-only detail view DTO. Logged only via the entity's
        // PCI-safe toString() (PAN masked).
        LOG.info("CardDetailService.viewCard returning detail view for card={}", card);
        return toDto(card);
    }

    /**
     * Maps a {@link Card} JPA entity to the {@link CardDetailDto}
     * read-only response. The {@link Card#getCardNum()} value is
     * emitted at full length (the legacy 3270 detail screen does so on
     * {@code CARDSID}); CVV is NOT included in the DTO type itself.
     */
    private CardDetailDto toDto(Card card) {
        return new CardDetailDto(
                card.getCardNum(),
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus());
    }
}
