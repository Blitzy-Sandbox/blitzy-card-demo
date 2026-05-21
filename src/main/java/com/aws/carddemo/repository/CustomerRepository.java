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

import com.aws.carddemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Customer} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('CUSTDAT')
 * RIDFLD(WS-CARD-RID-CUST-ID-X)} from {@code app/cbl/COACTVWC.cbl}
 * {@code 9400-GETCUSTDATA-BYCUST} paragraph (lines 825–869).
 *
 * <h2>COBOL Provenance — COACTVWC.cbl §9400</h2>
 *
 * <p>The original COBOL {@code 9400-GETCUSTDATA-BYCUST} paragraph performs
 * an {@code EXEC CICS READ} against the {@code CUSTDAT} VSAM KSDS file by
 * the 9-character {@code WS-CARD-RID-CUST-ID-X} primary key. The CICS
 * response code {@code WS-RESP-CD} carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       {@code SET FOUND-CUST-IN-MASTER TO TRUE}</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — customer not found →
 *       {@code SET INPUT-ERROR TO TRUE} with the
 *       {@code 'CustId:nnn not found in customer master'} reject
 *       message</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error → {@code MOVE
 *       WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG}</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: returns
 * {@code Optional.of(customer)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case.
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Customer} primary key {@link Customer#getCustomerId()} is
 * a {@link String} (9-character zero-padded numeric) for the same
 * byte-for-byte VSAM-key-preservation rationale that {@link AccountRepository}
 * documents.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy {@link com.aws.carddemo.service.AccountViewService}
 * compilation and the account-view test suite. Subsequent migration agents
 * (REFACTOR flavor) will add custom query methods (e.g.,
 * {@code findByFicoCreditScoreGreaterThan(int score)} for reporting,
 * {@code findByAddressStateCode(String state)} for analytics) and the
 * AES-GCM JPA attribute converter for the SSN column.
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see Customer
 */
public interface CustomerRepository extends JpaRepository<Customer, String> {
    // All required methods (findById, save, deleteById, ...) are inherited
    // from JpaRepository. Custom query methods will be added by subsequent
    // migration agents as additional COBOL programs (CBCUS01C customer
    // file processor, CUST-related batch and analytics flows) are migrated.
}
