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

import java.util.List;

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
 * <h2>Custom Query Methods</h2>
 *
 * <p>This interface declares two Spring Data derived query methods on
 * top of the inherited {@link JpaRepository} contract:
 *
 * <ul>
 *   <li>{@link #findByCustomerId(String)} — replaces the COCRDLIC.cbl
 *       account-by-customer-id lookup pattern. Spring Data derives the
 *       JPQL {@code SELECT a FROM Account a WHERE a.customerId = :customerId}
 *       from the method name and binds the {@code customer_id CHAR(9)}
 *       column declared on {@link Account}. Returns a {@link List}
 *       (never {@code null}, empty when no rows match) ordered by
 *       insertion (no explicit {@code ORDER BY}).</li>
 *   <li>{@link #findByActiveStatus(String)} — replaces the reporting
 *       and batch active/inactive filtering pattern. Spring Data
 *       derives {@code SELECT a FROM Account a WHERE a.activeStatus =
 *       :activeStatus} from the method name. The COBOL
 *       {@code ACCT-ACTIVE-STATUS PIC X(01)} field carries the
 *       single-character flag {@code 'Y'} (active) or {@code 'N'}
 *       (inactive); callers pass the literal flag value.</li>
 * </ul>
 *
 * <p>All other operations ({@code findById}, {@code save},
 * {@code deleteById}, etc.) are inherited from {@link JpaRepository} as
 * the COBOL CICS READ / WRITE / REWRITE / DELETE primitives map directly
 * onto them without further customisation.
 *
 * @see com.aws.carddemo.service.AccountViewService
 * @see Account
 */
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Finds every {@link Account} whose
     * {@link Account#getCustomerId() customerId} matches the supplied
     * 9-character customer foreign-key value. The Spring Data derived
     * query is equivalent to the COBOL {@code STARTBR / READNEXT}
     * pattern over the {@code ACCTDAT} alternate-index by
     * {@code CUST-ID} that COCRDLIC.cbl performs when displaying every
     * account a customer holds.
     *
     * <p>The method is intentionally typed {@link List} (not
     * {@code Page} or {@link java.util.Optional}) because:
     *
     * <ul>
     *   <li>Typical customers hold a small number of accounts
     *       (single-digit), so paging is unnecessary.</li>
     *   <li>An unknown customer ID yields an empty list — the
     *       Spring Data contract for {@code List}-typed derived queries
     *       guarantees a non-{@code null} return.</li>
     * </ul>
     *
     * @param customerId the 9-character zero-padded customer ID — must
     *                   match {@code customer_id CHAR(9)} verbatim
     *                   (callers are responsible for padding short
     *                   numeric strings; the column comparison is
     *                   width-sensitive).
     * @return every {@link Account} whose {@code customerId} equals the
     *         supplied value; the list may be empty but is never
     *         {@code null}.
     */
    List<Account> findByCustomerId(String customerId);

    /**
     * Finds every {@link Account} whose
     * {@link Account#getActiveStatus() activeStatus} matches the
     * supplied single-character flag. The Spring Data derived query is
     * equivalent to the COBOL {@code STARTBR / READNEXT WHEN
     * ACCT-ACTIVE-STATUS = 'Y'} reporting pattern used by the CORPT00C
     * report-submission program and the batch transaction-validation
     * cascade in CBTRN02C (reject code 102 fires when the looked-up
     * account is {@code 'N'}).
     *
     * @param activeStatus the single-character flag — {@code "Y"} for
     *                     active accounts, {@code "N"} for inactive.
     * @return every {@link Account} whose {@code activeStatus} equals
     *         the supplied flag; the list may be empty but is never
     *         {@code null}.
     */
    List<Account> findByActiveStatus(String activeStatus);
}
