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

import com.aws.carddemo.entity.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CardXref} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('CXACAIX')
 * RIDFLD(WS-CARD-RID-ACCT-ID-X)} from {@code app/cbl/COACTVWC.cbl}
 * {@code 9200-GETCARDXREF-BYACCT} paragraph (lines 723–769).
 *
 * <h2>COBOL Provenance — COACTVWC.cbl §9200</h2>
 *
 * <p>The original COBOL {@code 9200-GETCARDXREF-BYACCT} paragraph performs
 * an {@code EXEC CICS READ} against the {@code CXACAIX} VSAM alternate
 * index (the {@code CARDAIX} alternate index keyed on {@code XREF-ACCT-ID})
 * by the 11-character {@code WS-CARD-RID-ACCT-ID-X} key. The CICS response
 * code {@code WS-RESP-CD} carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       {@code MOVE XREF-CUST-ID TO CDEMO-CUST-ID} and
 *       {@code MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM}</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — no card cross-reference for
 *       this account ID → {@code SET INPUT-ERROR TO TRUE} with the
 *       {@code 'Account:nnn not found in Cross ref file'} reject message</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error → {@code MOVE
 *       WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG}</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent of the alternate-index read is the
 * derived query method {@link #findByAccountId(String)}: returns
 * {@code Optional.of(xref)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case.
 *
 * <h2>Derived Query Method — findByAccountId</h2>
 *
 * <p>Spring Data JPA's
 * <a href="https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.query-creation">derived query method</a>
 * machinery synthesises the JPQL {@code SELECT x FROM CardXref x WHERE
 * x.accountId = ?1} from the method name at proxy-creation time. The
 * 1:1 cardinality of the {@code CARDAIX} alternate index in the CardDemo
 * dataset guarantees that the result fits in an {@link Optional} — a
 * given account ID maps to at most one card cross-reference row.
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code CardXref} primary key {@link CardXref#getCardNumber()} is
 * a {@link String} (16-character PAN) for the same byte-for-byte
 * VSAM-key-preservation rationale documented on {@link AccountRepository}.
 *
 * <h2>Customer Alternate-Index Lookup — findByCustomerId</h2>
 *
 * <p>{@link #findByCustomerId(String)} replaces the COBOL alternate-index
 * read by {@code XREF-CUST-ID}, used by COCRDLIC.cbl to list every card
 * a customer holds. Spring Data derives
 * {@code SELECT x FROM CardXref x WHERE x.customerId = :customerId} from
 * the method name. The cardinality is 1-to-many — a customer typically
 * holds several cards — so the return type is {@link List}.
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see CardXref
 */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Look up the single card cross-reference row for the supplied
     * 11-character zero-padded account identifier.
     *
     * <p>Java replacement for COBOL {@code EXEC CICS READ DATASET('CXACAIX')
     * RIDFLD(WS-CARD-RID-ACCT-ID-X)} (the {@code CARDAIX} alternate-index
     * read) from {@code app/cbl/COACTVWC.cbl} §9200.
     *
     * @param accountId 11-character zero-padded numeric account identifier
     *                  (e.g. {@code "00000000010"})
     * @return {@code Optional.of(xref)} when a cross-reference row exists
     *         for this account ID; {@code Optional.empty()} when no row
     *         exists (COBOL: {@code DFHRESP(NOTFND)})
     */
    Optional<CardXref> findByAccountId(String accountId);

    /**
     * Look up every card cross-reference row whose
     * {@link CardXref#getCustomerId() customerId} matches the supplied
     * 9-character zero-padded customer identifier.
     *
     * <p>Java replacement for the COBOL alternate-index read pattern by
     * {@code XREF-CUST-ID} used by {@code app/cbl/COCRDLIC.cbl} when
     * listing all cards held by a single customer. Spring Data derives
     * the equivalent JPQL {@code SELECT x FROM CardXref x WHERE
     * x.customerId = :customerId} from the method name.
     *
     * <p>The cardinality of the customer→card mapping is 1-to-many in
     * the CardDemo dataset (one customer may hold several cards across
     * different account groups), so the return type is {@link List}
     * rather than {@link Optional}. Spring Data guarantees the returned
     * list is non-{@code null}; an unknown customer ID yields an empty
     * list.
     *
     * @param customerId 9-character zero-padded numeric customer
     *                   identifier (e.g. {@code "000000010"})
     * @return every card cross-reference row whose {@code customerId}
     *         equals the supplied value; the list may be empty but is
     *         never {@code null}.
     */
    List<CardXref> findByCustomerId(String customerId);
}
