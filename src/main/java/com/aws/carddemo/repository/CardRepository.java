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
package com.aws.carddemo.repository;

import com.aws.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Card} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('CARDDAT')
 * RIDFLD(WS-CARD-RID-CARDNUM)} from {@code app/cbl/COCRDSLC.cbl}
 * {@code 9100-GETCARD-BYACCTCARD} paragraph (lines 736–777) <em>and</em> for
 * the {@code STARTBR-CARDAIX} / {@code READNEXT-CARDAIX} browse loop in
 * {@code app/cbl/COCRDLIC.cbl} {@code 9000-READ-FORWARD} paragraph (lines
 * 1123–1267).
 *
 * <h2>COBOL Provenance — COCRDSLC.cbl §9100 (point read)</h2>
 *
 * <p>The original COBOL {@code 9100-GETCARD-BYACCTCARD} paragraph performs
 * an {@code EXEC CICS READ} against the {@code CARDDAT} VSAM KSDS file by
 * the 16-character {@code WS-CARD-RID-CARDNUM} primary key. The CICS
 * response code {@code WS-RESP-CD} carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       {@code SET FOUND-CARDS-FOR-ACCOUNT TO TRUE}</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — card not found in
 *       master file → {@code SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE}
 *       with the {@code 'Did not find cards for this search condition'}
 *       reject message (line 760)</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error → {@code MOVE
 *       WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} (lines 762–771)</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: returns
 * {@code Optional.of(card)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case. I/O errors surface
 * as {@link org.springframework.dao.DataAccessException} subclasses and
 * propagate up to the caller (the production
 * {@link com.aws.carddemo.service.CardDetailService} lets the exception
 * propagate to the controller layer for translation, mirroring the COBOL
 * {@code HANDLE ABEND} fallback).
 *
 * <h2>COBOL Provenance — COCRDLIC.cbl §9000-READ-FORWARD (paged list)</h2>
 *
 * <p>The Java migration of {@code COCRDLIC.cbl} uses
 * {@link #findAll(Pageable)} (inherited from {@link JpaRepository}) as the
 * direct Java replacement for the {@code STARTBR-CARDAIX} +
 * {@code PERFORM UNTIL READ-LOOP-EXIT} loop (lines 1129–1267 of
 * {@code COCRDLIC.cbl}). The fixed {@code WS-MAX-SCREEN-LINES VALUE 7}
 * page bound (lines 177–178) materialises as a Spring Data {@link Pageable}
 * with page size 7 — see
 * {@link com.aws.carddemo.service.CardListService#PAGE_SIZE}.
 *
 * <p>The COBOL workflow implements two declarative filters for the browse
 * via the {@code 9500-FILTER-RECORDS} paragraph (lines 1382–1410):
 * <ul>
 *   <li>{@code IF CARD-ACCT-ID = CC-ACCT-ID} (line 1386) — exact match on
 *       the 11-character zero-padded {@code CARD-ACCT-ID}. Migrated to
 *       {@link #findByAccountId(String, Pageable)} below.</li>
 *   <li>{@code IF CARD-NUM = CC-CARD-NUM-N} (line 1397) — exact match on
 *       the 16-character PAN. The Java migration generalises this to a
 *       prefix match via
 *       {@link #findByCardNumberStartsWith(String, Pageable)} below so
 *       the REST controller can support both full-PAN lookups and partial
 *       prefix narrowing (documented Java-migration enhancement per AAP
 *       §0.10.2); passing the full 16-digit PAN replicates the COBOL
 *       exact-match semantic.</li>
 * </ul>
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Card} primary key {@link Card#getCardNumber()} is a
 * {@link String} (16-character PAN) rather than a {@link Long} so that the
 * byte-for-byte VSAM key format is preserved across the migration. This
 * avoids any concern about leading-zero stripping during numeric-to-string
 * conversions at the controller boundary — and matches the convention
 * established by {@link CardXrefRepository} (which keys on the same PAN).
 *
 * <h2>Design Note — Incremental Stub Status</h2>
 *
 * <p>This interface is built up incrementally by successive migration
 * agents as additional COBOL programs are migrated. {@link #findById(Object)}
 * supports {@link com.aws.carddemo.service.CardDetailService} (COCRDSLC);
 * {@link #findAll(Pageable)}, {@link #findByAccountId(String, Pageable)},
 * and {@link #findByCardNumberStartsWith(String, Pageable)} support
 * {@link com.aws.carddemo.service.CardListService} (COCRDLIC). Subsequent
 * migration agents (REFACTOR flavor) will add a
 * {@code @Repository} stereotype annotation (if the project policy
 * requires explicit stereotype marking — Spring Data infers the bean from
 * the {@code JpaRepository} extension and a stereotype is not strictly
 * necessary), and a {@code @Modifying @Query} update for the COCRDUPC
 * dual-write flow.
 *
 * @see com.aws.carddemo.service.CardDetailService
 * @see com.aws.carddemo.service.CardListService
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Page through {@link Card} rows whose {@code CARD-ACCT-ID} matches
     * the supplied 11-character zero-padded account identifier.
     *
     * <p>Spring Data derives the JPQL {@code SELECT c FROM Card c WHERE
     * c.accountId = ?1} from this method name at proxy-creation time (the
     * property name {@code accountId} on {@link Card} matches the
     * {@code CARD-ACCT-ID PIC 9(11)} field from
     * {@code app/cpy/CVACT02Y.cpy}).
     *
     * <h3>COBOL Provenance — COCRDLIC.cbl §9500-FILTER-RECORDS</h3>
     *
     * <p>The COBOL {@code 9500-FILTER-RECORDS} paragraph at line 1386
     * performs an in-loop equality test ({@code IF CARD-ACCT-ID =
     * CC-ACCT-ID}) over the {@code STARTBR-CARDAIX} browse, excluding non-
     * matching records via {@code SET WS-EXCLUDE-THIS-RECORD TO TRUE}
     * (line 1389). The Java migration delegates this filter to a derived
     * Spring Data query, letting the database engine narrow the result set
     * before pagination — the natural Java replacement that avoids the
     * COBOL in-loop {@code GO TO} ({@code GO TO 9500-FILTER-RECORDS-EXIT}
     * at line 1390).
     *
     * <p>This method is also the load-bearing repository call when the
     * card-list dispatcher receives a non-admin operator: the COBOL
     * {@code 0000-MAIN} paragraph (lines 4–7 of the program header) routes
     * user-view operators to a browse narrowed to their own account.
     *
     * @param accountId 11-character zero-padded account identifier (e.g.
     *                  {@code "00000000010"}); must not be {@code null}.
     * @param pageable  pagination request (page index + page size). The
     *                  page size should be
     *                  {@link com.aws.carddemo.service.CardListService#PAGE_SIZE}
     *                  ({@code 7}) to match the COBOL
     *                  {@code WS-MAX-SCREEN-LINES} semantic.
     * @return a {@link Page} of {@link Card} rows belonging to the supplied
     *         account ID; never {@code null}. Empty page when no rows match.
     */
    Page<Card> findByAccountId(String accountId, Pageable pageable);

    /**
     * Page through {@link Card} rows whose {@code CARD-NUM} starts with the
     * supplied 16-character PAN (or shorter prefix for partial-match
     * narrowing).
     *
     * <p>Spring Data derives the JPQL {@code SELECT c FROM Card c WHERE
     * c.cardNumber LIKE ?1%} from this method name at proxy-creation time
     * (the property name {@code cardNumber} on {@link Card} matches the
     * {@code CARD-NUM PIC X(16)} field from {@code app/cpy/CVACT02Y.cpy}).
     *
     * <h3>COBOL Provenance — COCRDLIC.cbl §9500-FILTER-RECORDS</h3>
     *
     * <p>The COBOL {@code 9500-FILTER-RECORDS} paragraph at line 1397
     * performs an in-loop exact equality test ({@code IF CARD-NUM =
     * CC-CARD-NUM-N}) over the {@code STARTBR-CARDAIX} browse, excluding
     * non-matching records via {@code SET WS-EXCLUDE-THIS-RECORD TO TRUE}
     * (line 1400). The Java migration generalises the equality test to a
     * prefix match so the REST controller layer can support both:
     * <ol>
     *   <li>Full-16-digit-PAN lookups (replicating the COBOL exact-match
     *       semantic — a 16-character argument matches at most one card).</li>
     *   <li>Partial-prefix narrowing (a documented Java-migration
     *       enhancement per AAP §0.10.2; the REST controller can offer a
     *       "type a few digits" search affordance that the original 3270
     *       workflow did not provide).</li>
     * </ol>
     *
     * @param cardNumberPrefix 16-character Visa-format PAN, or a shorter
     *                         prefix for partial-match narrowing (e.g.
     *                         {@code "4111111111111101"} for an exact
     *                         match, or {@code "4111"} for prefix
     *                         narrowing); must not be {@code null}.
     * @param pageable         pagination request (page index + page size).
     *                         The page size should be
     *                         {@link com.aws.carddemo.service.CardListService#PAGE_SIZE}
     *                         ({@code 7}) to match the COBOL
     *                         {@code WS-MAX-SCREEN-LINES} semantic.
     * @return a {@link Page} of {@link Card} rows whose {@code cardNumber}
     *         starts with the supplied prefix; never {@code null}. Empty
     *         page when no rows match.
     */
    Page<Card> findByCardNumberStartsWith(String cardNumberPrefix, Pageable pageable);
}
