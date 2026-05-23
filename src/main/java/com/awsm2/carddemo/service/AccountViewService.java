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

import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Account-view service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COACTVWC.cbl} (CICS transaction id
 * {@code CAVW}).
 *
 * <p>This service returns a denormalised account-view DTO assembled
 * from {@link Account}, {@link Customer}, and (optionally) the
 * first {@link CardCrossReference} owned by the account. In the COBOL
 * source the flow is:
 * <ol>
 *   <li>{@code EXEC CICS READ DATASET('CXACAIX')
 *       RIDFLD(WS-ACCT-ID)} &mdash; AIX/PATH lookup on the cross-
 *       reference cluster to find an associated card and customer ID;</li>
 *   <li>{@code EXEC CICS READ DATASET('ACCTDAT')
 *       RIDFLD(WS-ACCT-ID)} &mdash; primary-key read of the account
 *       record;</li>
 *   <li>{@code EXEC CICS READ DATASET('CUSTDAT')
 *       RIDFLD(WS-CUST-ID)} &mdash; primary-key read of the customer
 *       record;</li>
 *   <li>{@code MOVE} fields to the {@code COACTVW.bms} symbolic
 *       map and {@code SEND MAP}.</li>
 * </ol>
 *
 * <p>In the Java target:
 * <ol>
 *   <li>{@link CardCrossReferenceRepository#findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long)}
 *       performs the XREF AIX lookup;</li>
 *   <li>{@link AccountRepository#findById(Object)} performs the account
 *       read;</li>
 *   <li>{@link CustomerRepository#findById(Object)} performs the
 *       customer read;</li>
 *   <li>The three rows are combined into a single {@link AccountViewDto}
 *       returned to the controller.</li>
 * </ol>
 *
 * <h2>Cache-aside pattern (AAP &sect;0.7.1)</h2>
 *
 * <p>Per the AAP "ElastiCache (Redis) used for account balance caching
 * &mdash; cache-aside pattern with TTL aligned to transaction
 * frequency", every read first consults {@link CacheService}. On miss,
 * the database read is performed and the result is populated back to
 * the cache with a 5-minute TTL (a balance between freshness and
 * RDS-read-load reduction). {@code AccountUpdateService} is responsible
 * for evicting the cache key whenever the underlying account or
 * customer changes &mdash; this service is read-only and never writes
 * to the database.</p>
 *
 * <p>The cache namespace is {@code accountView}; the key is the
 * 11-digit account ID. The cached value is the same denormalised DTO
 * returned to callers, so subsequent hits avoid all three database
 * reads.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COACTVWC.cbl} (CICS TRANID
 *       {@code 'CAVW'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COACTVW.bms} (mapset
 *       {@code COACTVW}, map {@code COACTVWA}).</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD} 300 bytes), {@code app/cpy/CVCUS01Y.cpy}
 *       ({@code CUSTOMER-RECORD} 500 bytes), {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD} 50 bytes).</li>
 *   <li><b>VSAM clusters:</b> {@code ACCTDAT}, {@code CUSTDAT},
 *       {@code CARDXREF} + {@code CXACAIX} (AIX) &mdash; replaced by
 *       PostgreSQL tables {@code accounts}, {@code customers},
 *       {@code card_xref} with the {@code idx_cardxref_acct_id}
 *       secondary index.</li>
 * </ul>
 *
 * <h2>Thread-safety, transactions, exception translation</h2>
 *
 * <p>Stateless and thread-safe. The
 * {@link Transactional &#64;Transactional(readOnly = true)} annotation
 * enables read-only PostgreSQL connection mode for slightly lower
 * latency. A missing account or customer surfaces as
 * {@link RecordNotFoundException} (HTTP 404; COBOL {@code FILE STATUS
 * 23}).</p>
 *
 * @see com.awsm2.carddemo.repository.AccountRepository
 * @see com.awsm2.carddemo.repository.CustomerRepository
 * @see com.awsm2.carddemo.repository.CardCrossReferenceRepository
 * @see com.awsm2.carddemo.adapter.CacheService
 * @see com.awsm2.carddemo.dto.AccountViewDto
 */
@Service
public class AccountViewService {

    /** Class-level SLF4J logger &mdash; structured JSON output. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountViewService.class);

    /** Cache namespace for account-view DTOs (per AAP §0.7.1 cache-aside). */
    public static final String CACHE_NS = "accountView";

    /**
     * Cache TTL for account-view rows. 5 minutes balances freshness
     * against RDS-read-load reduction; the AAP directive specifies "TTL
     * aligned to transaction frequency", and for the CardDemo workload
     * the median per-account update interval comfortably exceeds 5
     * minutes outside of EOD batch windows.
     */
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final CacheService cacheService;

    /**
     * Constructor &mdash; Spring supplies the collaborators.
     */
    public AccountViewService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            CacheService cacheService) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.customerRepository = Objects.requireNonNull(
                customerRepository, "customerRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository");
        this.cacheService = Objects.requireNonNull(
                cacheService, "cacheService");
    }

    /**
     * Aggregate account + customer (+ optional card linkage) into a
     * single view DTO for the {@code COACTVW.bms} screen.
     *
     * <p>COBOL paragraph mapping:</p>
     * <table>
     *   <caption>COBOL COACTVWC.cbl &harr;
     *            AccountViewService.viewAccount(...)</caption>
     *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
     *   <tr><td>{@code 9000-READ-ACCT}</td>
     *       <td>{@link AccountRepository#findById(Object)} on
     *       {@code accounts}</td></tr>
     *   <tr><td>{@code 9100-GETCARDXREF-BYACCT}
     *       (CXACAIX AIX read)</td>
     *       <td>{@link
     *           CardCrossReferenceRepository#findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long)}
     *       on {@code card_xref}</td></tr>
     *   <tr><td>{@code 9200-READ-CUST}</td>
     *       <td>{@link CustomerRepository#findById(Object)} on
     *       {@code customers}</td></tr>
     *   <tr><td>NOTFND (FILE STATUS 23)</td>
     *       <td>{@link RecordNotFoundException} &rarr; HTTP 404</td></tr>
     * </table>
     *
     * @param accountId the 11-digit account identifier
     *                  ({@code ACCT-ID PIC 9(11)}); never {@code null}
     * @return a fully-populated {@link AccountViewDto}; never
     *         {@code null}
     * @throws RecordNotFoundException when either the account or its
     *                                 customer is missing (HTTP 404;
     *                                 COBOL {@code FILE STATUS 23})
     */
    @Transactional(readOnly = true)
    public AccountViewDto viewAccount(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");

        // ---- Cache-aside lookup (AAP §0.7.1) -----------------------
        String cacheKey = String.format("%011d", accountId);
        Optional<AccountViewDto> cached =
                cacheService.get(CACHE_NS, cacheKey, AccountViewDto.class);
        if (cached.isPresent()) {
            LOG.debug("Cache hit accountId={}", cacheKey);
            return cached.get();
        }

        // ---- COBOL 9000-READ-ACCT (EXEC CICS READ DATASET('ACCTDAT'))
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCT_NOT_FOUND",
                        "Account not found: " + cacheKey));

        // ---- COBOL 9100-GETCARDXREF-BYACCT
        // The COBOL source uses the CXACAIX alternate index to find the
        // owning customer ID by traversing from the account back to a
        // card record. In the Java target we go via card_xref directly.
        Optional<CardCrossReference> xrefOpt =
                cardCrossReferenceRepository
                        .findFirstByXrefAcctIdOrderByXrefCardNumAsc(accountId);
        Long customerId;
        if (xrefOpt.isPresent()) {
            customerId = xrefOpt.get().getXrefCustId();
        } else {
            // No card linked yet: surface a not-found per the COBOL
            // semantics (CXACAIX miss is propagated as FILE STATUS 23).
            LOG.info("No card_xref found for accountId={}", cacheKey);
            throw new RecordNotFoundException(
                    "XREF_NOT_FOUND",
                    "Cross-reference not found for account: " + cacheKey);
        }

        // ---- COBOL 9200-READ-CUST (EXEC CICS READ DATASET('CUSTDAT'))
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "CUST_NOT_FOUND",
                        "Customer not found: " + customerId));

        // ---- Combine into the view DTO -----------------------------
        AccountViewDto dto = new AccountViewDto(
                account.getAcctId(),
                account.getAcctActiveStatus(),
                account.getAcctCurrBal(),
                account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(),
                account.getAcctOpenDate(),
                account.getAcctExpirationDate(),
                account.getAcctReissueDate(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(),
                account.getAcctAddrZip(),
                account.getAcctGroupId(),
                customer.getCustId(),
                customer.getCustFirstName(),
                customer.getCustMiddleName(),
                customer.getCustLastName(),
                customer.getCustSsn(),
                customer.getCustPhoneNum1(),
                customer.getCustPhoneNum2(),
                customer.getCustAddrLine1(),
                customer.getCustAddrLine2(),
                customer.getCustAddrLine3(),
                customer.getCustAddrStateCd(),
                customer.getCustAddrCountryCd(),
                customer.getCustAddrZip(),
                customer.getCustDobYyyyMmDd(),
                customer.getCustGovtIssuedId(),
                customer.getCustEftAccountId(),
                customer.getCustPriCardHolderInd(),
                customer.getCustFicoCreditScore());

        // ---- Populate cache (cache-aside fill) ---------------------
        // Failure to cache is non-fatal: CacheService.put logs and
        // swallows any exception per the fail-open contract.
        cacheService.put(CACHE_NS, cacheKey, dto, CACHE_TTL);

        LOG.info("Account view returned accountId={} customerId={}",
                cacheKey, customer.getCustId());
        return dto;
    }

    /**
     * Returns the list of card numbers linked to the supplied account
     * &mdash; companion helper used by the account-view UI to populate
     * the "associated cards" sub-panel and by downstream services that
     * need to enumerate every card owned by an account.
     *
     * @param accountId the 11-digit account identifier; never
     *                  {@code null}
     * @return the list of card numbers; never {@code null} (empty list
     *         when the account owns no cards)
     */
    @Transactional(readOnly = true)
    public List<CardCrossReference> listCardsForAccount(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return cardCrossReferenceRepository.findByXrefAcctId(accountId);
    }
}
