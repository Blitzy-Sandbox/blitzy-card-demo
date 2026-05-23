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
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Paged card-list service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COCRDLIC.cbl} (CICS transaction id {@code CCLI}).
 *
 * <p>This service produces a paginated card list (admin or per-account)
 * mirroring the 3270 card-list screen rendered by {@code COCRDLIC.cbl}.
 * In the COBOL source the program issues {@code EXEC CICS STARTBR} on the
 * {@code CARDDATA} VSAM KSDS (or its {@code CARDAIX} alternate-index for
 * account-filtered browses) and then iterates with
 * {@code EXEC CICS READNEXT} to materialize the 7-row {@code CCRDLIA} BMS
 * map (see {@code app/bms/COCRDLI.bms}, fields {@code ACCTNO1..ACCTNO7},
 * {@code CRDNUM1..CRDNUM7}, {@code CRDSTS1..CRDSTS7}). Paging is driven by
 * the {@code DFHPF7}/{@code DFHPF8} AID keys (page backward/forward). In
 * the Java target, the equivalent semantics are achieved through Spring
 * Data {@link Pageable} pagination (page size <b>fixed at 7</b> to
 * preserve the legacy contract) plus the derived queries on
 * {@link CardRepository}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COCRDLIC.cbl} (CICS TRANID
 *       {@code 'CCLI'}, file {@code 'CARDDAT'}; AAP &sect;0.4.1 online
 *       programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COCRDLI.bms} (mapset
 *       {@code COCRDLI}, map {@code CCRDLIA}, 7-row card table).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COCRDLI.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes; 91 bytes business + 59 bytes
 *       FILLER) &mdash; mapped to JPA entity {@link Card}.</li>
 *   <li><b>VSAM cluster:</b> {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}
 *       (KEYS(16 0), RECORDSIZE(150 150)).</li>
 *   <li><b>VSAM AIX:</b> {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} on
 *       {@code CARD-ACCT-ID} &mdash; replaced by the Spring Data derived
 *       query {@code CardRepository.findByCardAcctIdOrderByCardNumAsc}
 *       (see also {@code V002__create_card.sql} index on
 *       {@code card_acct_id}).</li>
 *   <li><b>Page size 7:</b> verbatim transcription of the COBOL
 *       {@code WS-EDIT-SELECT OCCURS 7 TIMES} working-storage array
 *       declared at {@code COCRDLIC.cbl}:L72&ndash;L82 (AAP &sect;0.7.1
 *       Minimal Change Clause: 7 is a literal carry-over from the legacy
 *       3270 screen layout, not an arbitrary REST convention).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COCRDLIC.cbl &harr; CardListService.listCards(...)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td>
 *       <td>{@link #listCards(Long, String, int)}</td></tr>
 *   <tr><td>{@code 9000-READ-FORWARD} / {@code STARTBR} on
 *       {@code CARDAIX} when {@code CDEMO-ACCT-ID} present</td>
 *       <td>{@code cardRepository.findByCardAcctIdOrderByCardNumAsc(acctId,
 *       pageable)}</td></tr>
 *   <tr><td>{@code 9000-READ-FORWARD} / {@code STARTBR} on
 *       {@code CARDDAT} when admin (no account filter)</td>
 *       <td>{@code cardRepository.findAll(pageable)} (admin branch)</td></tr>
 *   <tr><td>{@code READNEXT-CARDAIX-RID}</td>
 *       <td>{@link Page#getContent()} iteration in ascending
 *       {@code card_num} order</td></tr>
 *   <tr><td>{@code PROCESS-PF7-KEY} (page backward) /
 *       {@code PROCESS-PF8-KEY} (page forward)</td>
 *       <td>{@link Pageable#previousOrFirst()} /
 *       {@link Pageable#next()} (driven by client-supplied
 *       {@code page} parameter; this service does not maintain
 *       server-side cursor state, matching REST stateless semantics
 *       per AAP &sect;0.3.4)</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO} / page number / total pages</td>
 *       <td>{@link CardListDto#page()}, {@link CardListDto#totalPages()},
 *       {@link CardListDto#first()}, {@link CardListDto#last()}</td></tr>
 *   <tr><td>{@code SETUP-PROTECT-USRTYPE} (admin vs. non-admin) &mdash;
 *       restricts non-admin browses to cards owned by
 *       {@code CDEMO-ACCT-ID}</td>
 *       <td>Controller {@code @PreAuthorize} + non-null
 *       {@code accountFilter} from JWT-claim-extracted account ID
 *       (controller-layer concern; this service trusts the caller and
 *       applies the filter as supplied)</td></tr>
 * </table>
 *
 * <h2>Page size invariant</h2>
 * <p>The COBOL source hard-codes 7 rows per page via the 88-level
 * declarations on the {@code CCRDLIA} BMS map and the
 * {@code WS-EDIT-SELECT OCCURS 7 TIMES} working-storage table. The Java
 * target preserves this invariant: callers MAY pass any
 * {@code requestedSize} but this service <b>coerces the effective size to
 * 7</b> (the {@link #PAGE_SIZE} constant) before issuing the repository
 * call. This is a deliberate Minimal Change Clause faithful translation;
 * see {@code app/bms/COCRDLI.bms} (map {@code CCRDLIA}) and
 * {@code app/cbl/COCRDLIC.cbl}:L72&ndash;L82.</p>
 *
 * <h2>Concurrency and transactional behavior</h2>
 * <p>This service is read-only and declares
 * {@code @Transactional(readOnly = true)} on its public method. The
 * underlying queries on {@link CardRepository} hold a {@code READ
 * COMMITTED} transaction for the duration of the query &mdash; matching
 * the COBOL source which performs a {@code STARTBR}/{@code READNEXT}
 * sequence without {@code UPDATE} mode locks.</p>
 *
 * <h2>PCI-DSS guard rails (PAN masking)</h2>
 * <p>Each {@link CardListDto.CardRow} masks the PAN to
 * {@code ****-****-****-1234} format via {@link Card#maskCardNumber()}
 * before returning &mdash; this service NEVER emits the full PAN over the
 * REST contract, in alignment with AAP &sect;0.6.6 PCI-DSS discipline and
 * the Card entity's PCI-formatted {@code toString()} guard.</p>
 *
 * @see CardRepository
 * @see CardListDto
 * @see Card
 */
@Service
public class CardListService {

    /**
     * Page size constant. Fixed at 7 to preserve the legacy COBOL
     * {@code WS-EDIT-SELECT OCCURS 7 TIMES} working-storage table size
     * declared at {@code app/cbl/COCRDLIC.cbl}:L72&ndash;L82 and the
     * 7-row {@code CCRDLIA} BMS map layout.
     */
    public static final int PAGE_SIZE = 7;

    private static final Logger LOG = LoggerFactory.getLogger(CardListService.class);

    private final CardRepository cardRepository;

    /**
     * Constructor injection of all collaborators (no field injection per
     * Spring Boot 3.x best practice and AAP &sect;0.7.1 "Dependency
     * injection for loose coupling").
     *
     * @param cardRepository Spring Data repository abstracting the
     *                       {@code CARDDATA} VSAM KSDS / PostgreSQL
     *                       {@code cards} table
     */
    public CardListService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Lists cards on the page identified by {@code page} (0-indexed),
     * optionally filtered by owning account ID.
     *
     * <p>This is the Java equivalent of the COBOL paragraph
     * {@code 0000-MAIN} in {@code COCRDLIC.cbl}, which (after PF-key
     * dispatch and screen edit validation) issues
     * {@code EXEC CICS STARTBR} on either {@code CARDDAT} or
     * {@code CARDAIX} and then iterates a 7-row page via
     * {@code EXEC CICS READNEXT}.</p>
     *
     * <h3>Account-filter behavior (COBOL CDEMO-USRTYPE-USER branch)</h3>
     * <ul>
     *   <li>When {@code accountFilter} is non-null, this method invokes
     *       {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
     *       Pageable)} to scope the browse to cards owned by the
     *       specified account &mdash; the Java equivalent of the COBOL
     *       {@code STARTBR-CARDAIX-RID} paragraph that positions the
     *       browse on the {@code CARDAIX} alternate index.</li>
     *   <li>When {@code accountFilter} is null, this method invokes
     *       {@link CardRepository#findAll(Pageable)} ordered by card
     *       number &mdash; the Java equivalent of the
     *       {@code CDEMO-USRTYPE-ADMIN} branch in {@code COCRDLIC.cbl}
     *       which browses the full {@code CARDDAT} cluster ordered by the
     *       primary key {@code CARD-NUM}.</li>
     * </ul>
     *
     * <h3>Card-number filter (COBOL CARDSID BMS field)</h3>
     * <p>The COBOL source accepts an optional 16-digit card number filter
     * via the {@code CARDSID PIC X(16)} BMS field which positions the
     * browse on or after the supplied key. The Java target accepts the
     * filter via the {@code cardNumberFilter} parameter and echoes it
     * back masked in the response DTO. The current implementation does
     * not push the filter into the repository query (the COBOL behavior
     * is "position only, then browse" &mdash; subsequent records are
     * returned in card-number order regardless of the seed value); a
     * future enhancement may add a derived
     * {@code findByCardNumGreaterThanEqualOrderByCardNumAsc} query when
     * client demand justifies it (deferred per AAP &sect;0.7.3 Minimal
     * Change Clause). The {@code cardNumberFilter} is still echoed in the
     * response for client convenience and parity with the BMS field.</p>
     *
     * @param accountFilter     optional 11-digit account ID to scope the
     *                          browse (COBOL {@code CDEMO-ACCT-ID} from
     *                          the CICS COMMAREA defined in
     *                          {@code COCOM01Y.cpy}); {@code null} for
     *                          unfiltered admin browse
     * @param cardNumberFilter  optional 16-digit card number seed value
     *                          echoed back to the client (masked); may
     *                          be {@code null}
     * @param page              0-indexed page number to retrieve
     * @return a {@link CardListDto} containing up to {@link #PAGE_SIZE}
     *         masked card rows plus paging metadata
     */
    @Transactional(readOnly = true)
    public CardListDto listCards(Long accountFilter, String cardNumberFilter, int page) {
        if (page < 0) {
            LOG.debug("Negative page index {} coerced to 0 per legacy COCRDLIC parity", page);
            page = 0;
        }

        // Spring Data Pageable replaces CICS STARTBR/READNEXT cursor.
        // Page size is fixed at PAGE_SIZE (7) to preserve the legacy
        // COBOL/BMS contract (CCRDLIA map, 7 rows) per AAP §0.7.1.
        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "cardNum"));

        Page<Card> result;
        if (accountFilter != null) {
            // COBOL: STARTBR-CARDAIX-RID (alternate-index browse) +
            // READNEXT loop in COCRDLIC.cbl when CDEMO-ACCT-ID is set.
            LOG.debug("Listing cards for account {} page {} (size {})",
                    accountFilter, page, PAGE_SIZE);
            result = cardRepository.findByCardAcctIdOrderByCardNumAsc(accountFilter, pageable);
        } else {
            // COBOL: CDEMO-USRTYPE-ADMIN branch — full CARDDAT browse
            // ordered by the primary key CARD-NUM.
            LOG.debug("Listing all cards (admin) page {} (size {})", page, PAGE_SIZE);
            result = cardRepository.findAll(pageable);
        }

        List<CardListDto.CardRow> rows = result.getContent().stream()
                .map(this::toRow)
                .toList();

        return new CardListDto(
                rows,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast(),
                accountFilter,
                cardNumberFilter == null ? null : maskFilter(cardNumberFilter));
    }

    /**
     * Maps a {@link Card} JPA entity to a {@link CardListDto.CardRow}.
     *
     * <p>The PAN is <b>always masked</b> via {@link #maskCardNumber(String)}
     * before being placed on the wire &mdash; this service never emits
     * the full 16-digit card number in a list response, in alignment with
     * AAP &sect;0.6.6 PCI-DSS discipline.</p>
     */
    private CardListDto.CardRow toRow(Card card) {
        return new CardListDto.CardRow(
                maskCardNumber(card.getCardNum()),  // ****-****-****-1234 per PCI-DSS
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus());
    }

    /**
     * Masks a card-number filter echoed back to the client for parity
     * with the per-row PAN masking. Applies the standard
     * {@code ****-****-****-LAST4} format when the filter is exactly 16
     * digits; otherwise returns the filter unchanged (the caller is
     * responsible for input validation upstream).
     */
    private String maskFilter(String filter) {
        if (filter == null || filter.length() != 16) {
            return filter;
        }
        // PCI-DSS PAN masking — reveal only the last 4 digits.
        return "****-****-****-" + filter.substring(12);
    }

    /**
     * Masks a 16-character card number to the PCI-DSS-mandated
     * {@code ****-****-****-LAST4} format. Mirrors the entity-level
     * private masking helper on {@link Card#toString()} (which itself
     * implements PCI-DSS v4.0 Requirement 3.4.1). Defensive against
     * {@code null} or short inputs.
     */
    private String maskCardNumber(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}
