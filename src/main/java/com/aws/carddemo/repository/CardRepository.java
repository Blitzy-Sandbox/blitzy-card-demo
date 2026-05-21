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
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Card} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('CARDDAT')
 * RIDFLD(WS-CARD-RID-CARDNUM)} from {@code app/cbl/COCRDSLC.cbl}
 * {@code 9100-GETCARD-BYACCTCARD} paragraph (lines 736–777).
 *
 * <h2>COBOL Provenance — COCRDSLC.cbl §9100</h2>
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
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Card} primary key {@link Card#getCardNumber()} is a
 * {@link String} (16-character PAN) rather than a {@link Long} so that the
 * byte-for-byte VSAM key format is preserved across the migration. This
 * avoids any concern about leading-zero stripping during numeric-to-string
 * conversions at the controller boundary — and matches the convention
 * established by {@link CardXrefRepository} (which keys on the same PAN).
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy {@link com.aws.carddemo.service.CardDetailService}
 * compilation and the card-detail test suite. Subsequent migration agents
 * (REFACTOR flavor) will add custom query methods (e.g.,
 * {@code findByAccountId(String accountId)} for the COCRDLIC card-list
 * query, {@code findAllByActiveStatus(String status)} for reporting), a
 * {@code @Repository} stereotype annotation (if the project policy
 * requires explicit stereotype marking — Spring Data infers the bean
 * from the {@code JpaRepository} extension and a stereotype is not
 * strictly necessary), and a {@code @Modifying @Query} update for the
 * COCRDUPC dual-write flow.
 *
 * @see com.aws.carddemo.service.CardDetailService
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {
    // All required methods (findById, save, deleteById, ...) are inherited
    // from JpaRepository. Custom query methods will be added by subsequent
    // migration agents as additional COBOL programs (COCRDUPC, COCRDLIC,
    // CBACT02C) are migrated.
}
