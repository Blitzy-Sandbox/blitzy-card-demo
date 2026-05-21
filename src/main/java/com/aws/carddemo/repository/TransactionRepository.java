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

import com.aws.carddemo.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Transaction} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('TRANSACT')
 * RIDFLD(TRAN-ID)} from {@code app/cbl/COTRN01C.cbl}
 * {@code READ-TRANSACT-FILE} paragraph (lines 267–296).
 *
 * <h2>COBOL Provenance — COTRN01C.cbl §READ-TRANSACT-FILE</h2>
 *
 * <p>The original COBOL paragraph performs an {@code EXEC CICS READ}
 * against the {@code TRANSACT} VSAM KSDS file by the 16-character
 * {@code TRAN-ID} primary key. The CICS response code {@code WS-RESP-CD}
 * carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       continue to populate the {@code COTRN1AO} BMS map</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — transaction not found →
 *       {@code MOVE 'Transaction ID NOT found...' TO WS-MESSAGE} and
 *       fall through to {@code SEND-TRNVIEW-SCREEN} (line 285–288)</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error →
 *       {@code MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE}
 *       (line 289–295)</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: returns
 * {@code Optional.of(transaction)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case. I/O errors surface
 * as {@link org.springframework.dao.DataAccessException} subclasses and
 * propagate up to the caller (the production
 * {@link com.aws.carddemo.service.TransactionDetailService} could translate
 * these into the COBOL {@code 'Unable to lookup Transaction...'}
 * equivalent reject message; the stub at this stage lets the exception
 * propagate to the controller layer for translation, mirroring the COBOL
 * {@code HANDLE ABEND} fallback).
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Transaction} primary key {@link Transaction#getTransactionId()}
 * is a {@link String} (16-character) rather than a {@link Long} so that the
 * byte-for-byte VSAM key format is preserved across the migration. The COBOL
 * {@code TRAN-ID} field is {@code PIC X(16)} — a fixed-width alphanumeric
 * key whose values are not always pure numerics (interest transactions are
 * a 10-character PARM prefix concatenated with a 6-digit sequential suffix).
 * Using {@link String} avoids any conversion ambiguity.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy {@link com.aws.carddemo.service.TransactionDetailService}
 * compilation and the transaction-detail test suite. Subsequent migration
 * agents (REFACTOR flavor) will add custom query methods (e.g.,
 * {@code findByCardNumberOrderByOriginTimestampDesc(String)} for the
 * COTRN00C transaction-list pagination query, {@code findAllByOriginTimestampBetween(...)}
 * for the TRANREPT date-range report), a {@code @Repository} stereotype
 * annotation (if the project policy requires explicit stereotype marking —
 * Spring Data infers the bean from the {@code JpaRepository} extension
 * and a stereotype is not strictly necessary), and any custom
 * {@code @Modifying @Query} insert that the COTRN02C transaction-add
 * workflow requires.
 *
 * @see com.aws.carddemo.service.TransactionDetailService
 * @see Transaction
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    // All required methods (findById, save, deleteById, ...) are inherited
    // from JpaRepository. Custom query methods will be added by subsequent
    // migration agents as additional COBOL programs (COTRN00C, COTRN02C,
    // CBTRN02C, CBTRN03C) are migrated.
}
