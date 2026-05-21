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

import com.aws.carddemo.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Account} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('ACCTDAT')
 * RIDFLD(WS-CARD-RID-ACCT-ID-X)} from {@code app/cbl/COACTVWC.cbl}
 * {@code 9300-GETACCTDATA-BYACCT} paragraph (lines 774–820).
 *
 * <h2>COBOL Provenance — COACTVWC.cbl §9300</h2>
 *
 * <p>The original COBOL {@code 9300-GETACCTDATA-BYACCT} paragraph performs
 * an {@code EXEC CICS READ} against the {@code ACCTDAT} VSAM KSDS file by
 * the 11-character {@code WS-CARD-RID-ACCT-ID-X} primary key. The CICS
 * response code {@code WS-RESP-CD} carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       {@code SET FOUND-ACCT-IN-MASTER TO TRUE}</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — account not found in
 *       master file → {@code SET INPUT-ERROR TO TRUE} with the
 *       {@code 'Account:nnn not found in Acct Master file'} reject
 *       message</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error → {@code MOVE
 *       WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG}</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: returns
 * {@code Optional.of(account)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case. I/O errors surface
 * as {@link org.springframework.dao.DataAccessException} subclasses and
 * propagate up to the caller (the production
 * {@link com.aws.carddemo.service.AccountViewService} could translate
 * these into the COBOL {@code WS-FILE-ERROR-MESSAGE}-equivalent reject
 * message; the stub at this stage lets the exception propagate to the
 * controller layer for translation, mirroring the COBOL
 * {@code HANDLE ABEND} fallback).
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Account} primary key {@link Account#getAccountId()} is a
 * {@link String} (11-character zero-padded numeric) rather than a
 * {@link Long} so that the byte-for-byte VSAM key format is preserved
 * across the migration. This avoids any concern about leading-zero
 * stripping during numeric-to-string conversions at the controller
 * boundary.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy {@link com.aws.carddemo.service.AccountViewService}
 * compilation and the account-view test suite. Subsequent migration agents
 * (REFACTOR flavor) will add custom query methods (e.g.,
 * {@code findByCustomerId(String customerId)} for the COCRDLIC card-list
 * query, {@code findAllByActiveStatus(String status)} for reporting), a
 * {@code @Repository} stereotype annotation (if the project policy
 * requires explicit stereotype marking — Spring Data infers the bean
 * from the {@code JpaRepository} extension and a stereotype is not
 * strictly necessary), and a {@code @Modifying @Query} update for the
 * COACTUPC dual-write flow.
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see Account
 */
public interface AccountRepository extends JpaRepository<Account, String> {
    // All required methods (findById, save, deleteById, ...) are inherited
    // from JpaRepository. Custom query methods will be added by subsequent
    // migration agents as additional COBOL programs (COACTUPC, COCRDLIC,
    // CBACT01C) are migrated.
}
