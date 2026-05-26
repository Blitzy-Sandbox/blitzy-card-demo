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
import com.awsm2.carddemo.exception.ValidationException;
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
 * Account view service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COACTVWC.cbl} (CICS transaction id
 * {@code 'CAVW'}).
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COACTVWC.cbl} &mdash; the
 *       online "Account View" CICS pseudo-conversational transaction.
 *       Reads the {@code CXACAIX} alternate-index, then the
 *       {@code ACCTDAT} primary key, then the {@code CUSTDAT} primary
 *       key, and assembles a denormalised view for the
 *       {@code COACTVW.bms} 3270 screen.</li>
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
 *       secondary index per AAP &sect;0.6.2.</li>
 * </ul>
 *
 * <h2>Flow (matches COACTVWC paragraph
 *      {@code 9000-READ-ACCT} ordering)</h2>
 * <ol>
 *   <li><b>Validate acctId.</b> COACTVWC paragraph
 *       {@code 2210-EDIT-ACCOUNT} (lines 649-684) rejects null /
 *       zero / non-numeric account identifiers with the working-
 *       storage condition {@code SEARCHED-ACCT-ZEROES} /
 *       {@code SEARCHED-ACCT-NOT-NUMERIC} bearing the verbatim message
 *       <em>"Account number must be a non zero 11 digit number"</em>.
 *       The Java target maps this to {@link ValidationException}
 *       (HTTP 400 via {@code GlobalExceptionHandler}).</li>
 *   <li><b>Try cache (cache-aside).</b> ElastiCache Redis lookup via
 *       {@link CacheService#get(String, String, Class)} in the
 *       {@code "account-view"} namespace. On hit, return the cached
 *       DTO and bypass all three DB reads. Per AAP &sect;0.7.1
 *       <em>"ElastiCache (Redis) used for account balance caching
 *       &mdash; cache-aside pattern with TTL aligned to transaction
 *       frequency"</em>.</li>
 *   <li><b>READ XREF (COBOL {@code 9200-GETCARDXREF-BYACCT}).</b>
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *       performs the AIX lookup. The COBOL source raises
 *       {@code DID-NOT-FIND-ACCT-IN-CARDXREF} on AIX miss; the Java
 *       target throws {@link RecordNotFoundException} carrying the
 *       verbatim COBOL message
 *       <em>"Did not find this account in account card xref file"</em>
 *       (HTTP 404).</li>
 *   <li><b>READ ACCOUNT (COBOL {@code 9300-GETACCTDATA-BYACCT}).</b>
 *       {@link AccountRepository#findById(Object)} fetches the
 *       300-byte {@link Account} row. COBOL raises
 *       {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} on NOTFND; the Java
 *       target throws {@link RecordNotFoundException} with the
 *       verbatim message
 *       <em>"Did not find this account in account master file"</em>.</li>
 *   <li><b>READ CUSTOMER (COBOL {@code 9400-GETCUSTDATA-BYCUST}).</b>
 *       {@link CustomerRepository#findById(Object)} fetches the
 *       500-byte {@link Customer} row keyed by
 *       {@code xref.getXrefCustId()}. COBOL raises
 *       {@code DID-NOT-FIND-CUST-IN-CUSTDAT} on NOTFND; the Java
 *       target throws {@link RecordNotFoundException} with the
 *       verbatim message
 *       <em>"Did not find associated customer in master file"</em>.</li>
 *   <li><b>Assemble DTO.</b> Project Account + Customer + first
 *       {@link CardCrossReference} into the 30-field
 *       {@link AccountViewDto} record. The DTO is responsible for
 *       PII masking (SSN as {@code ***-**-XXXX} in
 *       {@link AccountViewDto#toString()}) per AAP &sect;0.6.6
 *       PCI-DSS handling.</li>
 *   <li><b>Cache write-through.</b> Populate the cache via
 *       {@link CacheService#put(String, String, Object, Duration)} so
 *       subsequent reads hit the cache. Failure to cache is non-fatal
 *       (CacheService implements fail-open semantics).</li>
 *   <li><b>Return DTO.</b></li>
 * </ol>
 *
 * <h2>Read order preserved (AAP &sect;0.7.1 minimal-change rule)</h2>
 * <p>The reads happen in the order XREF &rarr; ACCT &rarr; CUST &mdash;
 * the same order as COACTVWC paragraph {@code 9000-READ-ACCT} (lines
 * 687-718). This ordering is semantically meaningful: each failure
 * surfaces a distinct error message tied to the dataset that was
 * missing. Re-ordering the reads would produce the wrong error
 * message for any given missing-row scenario, violating the AAP
 * mandate to preserve behaviour exactly.</p>
 *
 * <h2>Cache-aside design</h2>
 * <p>The cache namespace is {@code "account-view"} (AAP &sect;0.7.1)
 * and the key is the 11-digit zero-padded account ID
 * ({@code String.format("%011d", acctId)}). The key format mirrors
 * the partition key used by {@code KafkaEventPublisher} for the
 * {@code account.updated} MSK topic, ensuring consistent ID
 * representation across services.</p>
 *
 * <p>{@link #CACHE_NS} is exposed as a {@code public static} constant
 * so writers ({@code AccountUpdateService}, {@code BillPaymentService},
 * {@code InterestCalculationService}) can evict the corresponding key
 * after a write &mdash; the cache-aside invalidation pattern.</p>
 *
 * <h2>Thread-safety, transactions, exception translation</h2>
 * <p>Stateless and thread-safe. The
 * {@link Transactional &#64;Transactional(readOnly = true)}
 * annotation declares the three-way join as a single read-only
 * transactional unit; Hibernate skips dirty-checking and PostgreSQL
 * uses a read-only snapshot for slightly lower latency per AAP
 * &sect;0.7.1.</p>
 *
 * <p>All domain exceptions thrown from this service are translated
 * to HTTP responses by {@code GlobalExceptionHandler}:</p>
 * <ul>
 *   <li>{@link ValidationException} &rarr; HTTP 400 Bad Request</li>
 *   <li>{@link RecordNotFoundException} &rarr; HTTP 404 Not Found</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.repository.AccountRepository
 * @see com.awsm2.carddemo.repository.CustomerRepository
 * @see com.awsm2.carddemo.repository.CardCrossReferenceRepository
 * @see com.awsm2.carddemo.adapter.CacheService
 * @see com.awsm2.carddemo.dto.AccountViewDto
 */
@Service
public class AccountViewService {

    /**
     * Class-level SLF4J logger. Structured JSON output is configured
     * in {@code src/main/resources/logback-spring.xml} per AAP
     * &sect;0.7.2 observability requirements
     * (logstash-logback-encoder &rarr; CloudWatch Logs &rarr;
     * OpenSearch).
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountViewService.class);

    /**
     * Cache namespace for account-view DTOs (AAP &sect;0.7.1
     * cache-aside).
     *
     * <p><b>Why {@code public static final}:</b> exposed so writers
     * &mdash; {@code AccountUpdateService} (COBOL {@code COACTUPC}),
     * {@code BillPaymentService} (COBOL {@code COBIL00C}),
     * {@code InterestCalculationService} (COBOL {@code CBACT04C}),
     * and {@code TransactionPostingService} (COBOL
     * {@code CBTRN02C}) &mdash; can evict the corresponding key
     * after mutating the underlying account / customer row. This is
     * the cache-aside invalidation contract: writers are responsible
     * for invalidating stale entries before the next read pulls them
     * back from RDS.</p>
     */
    public static final String CACHE_NS = "account-view";

    /**
     * Cache TTL for account-view rows. 5 minutes balances freshness
     * against RDS-read-load reduction; the AAP directive specifies
     * "TTL aligned to transaction frequency", and for the CardDemo
     * workload the median per-account update interval comfortably
     * exceeds 5 minutes outside of EOD batch windows. Stale entries
     * are an acceptable trade-off because writers issue an explicit
     * {@code cacheService.evict(...)} after every mutation.
     */
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final CacheService cacheService;

    /**
     * Constructor &mdash; constructor injection only per AAP
     * &sect;0.7.1 layered architecture rule (no field injection, no
     * setter injection). Spring resolves the four collaborator beans
     * at startup and provides them as constructor arguments.
     *
     * @param accountRepository            JPA repository for the
     *                                     {@code accounts} table
     *                                     (replaces VSAM {@code ACCTDAT}
     *                                     per AAP &sect;0.6.2);
     *                                     must not be {@code null}.
     * @param customerRepository           JPA repository for the
     *                                     {@code customers} table
     *                                     (replaces VSAM {@code CUSTDAT}
     *                                     per AAP &sect;0.6.2);
     *                                     must not be {@code null}.
     * @param cardCrossReferenceRepository JPA repository for the
     *                                     {@code card_xref} table
     *                                     (replaces VSAM {@code CARDXREF}
     *                                     + {@code CXACAIX} AIX per AAP
     *                                     &sect;0.6.2);
     *                                     must not be {@code null}.
     * @param cacheService                 ElastiCache Redis adapter
     *                                     (cache-aside per AAP
     *                                     &sect;0.7.1);
     *                                     must not be {@code null}.
     */
    public AccountViewService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            CacheService cacheService) {
        // Constructor injection only (AAP §0.7.1 — no @Autowired,
        // no setter injection). null guards protect against
        // misconfigured @SpringBootTest contexts that wire mock
        // collaborators ad hoc.
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
     * Retrieves a fully-denormalised account view, joining the
     * {@code accounts}, {@code customers}, and {@code card_xref}
     * tables in a single read-only transaction. This is the Java
     * target for the COBOL COACTVWC paragraph {@code 9000-READ-ACCT}
     * (lines 687-718).
     *
     * <h4>Flow (preserves COACTVWC read ordering)</h4>
     * <ol>
     *   <li>Validate {@code acctId} &mdash; non-null and strictly
     *       positive.</li>
     *   <li>Look up the {@code "account-view"} cache. On hit, return
     *       the cached DTO and skip the database reads.</li>
     *   <li>READ XREF (COBOL {@code 9200-GETCARDXREF-BYACCT}) &mdash;
     *       find any cross-reference row whose
     *       {@code xref_acct_id} matches the requested account.</li>
     *   <li>READ ACCT (COBOL {@code 9300-GETACCTDATA-BYACCT}) &mdash;
     *       fetch the 300-byte account record by primary key.</li>
     *   <li>READ CUST (COBOL {@code 9400-GETCUSTDATA-BYCUST}) &mdash;
     *       fetch the 500-byte customer record by primary key
     *       derived from the XREF row.</li>
     *   <li>Project the three rows into a 30-field
     *       {@link AccountViewDto} record.</li>
     *   <li>Populate the cache (fail-open).</li>
     *   <li>Return the DTO.</li>
     * </ol>
     *
     * @param acctId the 11-digit account identifier
     *               ({@code ACCT-ID PIC 9(11)} in
     *               {@code app/cpy/CVACT01Y.cpy})
     * @return a fully-populated {@link AccountViewDto}; never
     *         {@code null}
     * @throws ValidationException     when {@code acctId} is
     *                                 {@code null} or non-positive
     *                                 &mdash; COBOL
     *                                 {@code 2210-EDIT-ACCOUNT}
     *                                 {@code SEARCHED-ACCT-ZEROES} /
     *                                 {@code SEARCHED-ACCT-NOT-NUMERIC}
     *                                 condition (HTTP 400)
     * @throws RecordNotFoundException when no cross-reference row
     *                                 exists for the account
     *                                 ({@code DID-NOT-FIND-ACCT-IN-CARDXREF}),
     *                                 when no account row exists
     *                                 ({@code DID-NOT-FIND-ACCT-IN-ACCTDAT}),
     *                                 or when no customer row exists
     *                                 for the cross-referenced ID
     *                                 ({@code DID-NOT-FIND-CUST-IN-CUSTDAT})
     *                                 (HTTP 404)
     */
    @Transactional(readOnly = true)
    public AccountViewDto getAccountView(Long acctId) {
        // -------------------------------------------------------------
        // Step 1 — Validate acctId.
        // COBOL: COACTVWC:2210-EDIT-ACCOUNT (lines 649-684)
        //   88 SEARCHED-ACCT-ZEROES VALUE
        //      'Account number must be a non zero 11 digit number'.
        //   88 SEARCHED-ACCT-NOT-NUMERIC VALUE
        //      'Account number must be a non zero 11 digit number'.
        // -------------------------------------------------------------
        if (acctId == null || acctId <= 0L) {
            // Verbatim COBOL working-storage message per AAP §0.7.2
            // "Error codes and condition handling surfaced to
            //  downstream consumers must be preserved verbatim".
            throw new ValidationException(
                    "Account number must be a non zero 11 digit number");
        }

        // -------------------------------------------------------------
        // Step 2 — Cache-aside lookup (AAP §0.7.1).
        // The cache namespace and zero-padded key format mirror
        // KafkaEventPublisher partition keys for the
        // account.updated MSK topic, keeping a consistent
        // 11-digit string identifier across all event-driven
        // services.
        // -------------------------------------------------------------
        final String cacheKey = acctIdKey(acctId);
        final Optional<AccountViewDto> cached =
                cacheService.get(CACHE_NS, cacheKey, AccountViewDto.class);
        if (cached.isPresent()) {
            LOG.debug("Account view cache hit acctId={}", cacheKey);
            return cached.get();
        }
        LOG.debug("Account view cache miss acctId={} — proceeding to RDS join", cacheKey);

        // -------------------------------------------------------------
        // Step 3 — READ XREF (CXACAIX alternate index by acctId).
        // COBOL: COACTVWC:READ-CXACAIX
        //   EXEC CICS READ DATASET ('CXACAIX')
        //        RIDFLD (WS-CARD-RID-ACCT-ID-X) ...
        //   On NOTFND → DID-NOT-FIND-ACCT-IN-CARDXREF:
        //       'Did not find this account in account card xref file'
        // The JPA replacement is findByXrefAcctId(Long), which backs
        // the V004 idx_cardxref_acct_id secondary index (AAP §0.6.2)
        // and preserves the NONUNIQUEKEY semantics (0/1/N rows per
        // account). The COBOL READ on an AIX returns the first
        // matching record in AIX key order; we mirror that by taking
        // the first element of the returned list.
        // -------------------------------------------------------------
        final List<CardCrossReference> xrefs =
                cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            // Verbatim COBOL working-storage message
            // (COACTVWC 88 DID-NOT-FIND-ACCT-IN-CARDXREF, line 129-130).
            // QA Final-CP6 Finding M6: use a structured error code
            // (XREF_NOT_FOUND) rather than the entity-class name so the
            // wire envelope's `code` field matches the pattern used by
            // CardDetailService (CARD_NOT_FOUND) and the rest of the
            // post-fix exception surface.
            throw new RecordNotFoundException(
                    "XREF_NOT_FOUND",
                    "Did not find this account in account card xref file: acctId=" + acctId);
        }
        final CardCrossReference xref = xrefs.get(0);

        // -------------------------------------------------------------
        // Step 4 — READ ACCTDAT by ACCT-ID (primary key).
        // COBOL: COACTVWC:READ-ACCTDAT
        //   EXEC CICS READ DATASET ('ACCTDAT')
        //        RIDFLD (WS-CARD-RID-ACCT-ID-X) ...
        //   On NOTFND → DID-NOT-FIND-ACCT-IN-ACCTDAT:
        //       'Did not find this account in account master file'
        // -------------------------------------------------------------
        final Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        // QA Final-CP6 Finding M6: structured error code
                        // matching the post-fix exception envelope pattern.
                        "ACCOUNT_NOT_FOUND",
                        // Verbatim COBOL working-storage message
                        // (COACTVWC 88 DID-NOT-FIND-ACCT-IN-ACCTDAT, line 131-132).
                        "Did not find this account in account master file: acctId=" + acctId));

        // -------------------------------------------------------------
        // Step 5 — READ CUSTDAT by ACCT-CUST-ID (primary key,
        // derived from the XREF row).
        // COBOL: COACTVWC:READ-CUSTDAT
        //   EXEC CICS READ DATASET ('CUSTDAT')
        //        RIDFLD (WS-CARD-RID-CUST-ID-X) ...
        //   On NOTFND → DID-NOT-FIND-CUST-IN-CUSTDAT:
        //       'Did not find associated customer in master file'
        // The customer-ID for the read is sourced from the
        // CardCrossReference row's XREF-CUST-ID field
        // (CVACT03Y.cpy line 9, PIC 9(09)).
        // -------------------------------------------------------------
        final Long custId = xref.getXrefCustId();
        final Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException(
                        // QA Final-CP6 Finding M6: structured error code
                        // matching the post-fix exception envelope pattern.
                        "CUSTOMER_NOT_FOUND",
                        // Verbatim COBOL working-storage message
                        // (COACTVWC 88 DID-NOT-FIND-CUST-IN-CUSTDAT, line 133-134).
                        "Did not find associated customer in master file: custId=" + custId));

        // -------------------------------------------------------------
        // Step 6 — Assemble the 30-field AccountViewDto.
        // COBOL: COACTVWC:1200-SETUP-SCREEN-VARS (lines 460-535)
        // moves every ACCOUNT-RECORD / CUSTOMER-RECORD field into the
        // CACTVWAO symbolic-map output buffer. The Java equivalent is
        // a positional record constructor whose argument order
        // matches the AccountViewDto canonical constructor exactly.
        // The DTO is responsible for PII masking (SSN as
        // ***-**-XXXX in toString()) per AAP §0.6.6 PCI-DSS handling;
        // this service trusts that contract.
        // -------------------------------------------------------------
        final AccountViewDto dto = new AccountViewDto(
                // ===== Account fields (CVACT01Y.cpy) =====
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
                // ===== Customer fields (CVCUS01Y.cpy) =====
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
                customer.getCustFicoCreditScore(),
                // ===== Optimistic-lock version (accounts.version) =====
                // QA Final-CP6 Finding M1 (MAJOR): expose the @Version
                // token so clients performing a subsequent
                // PUT /api/accounts/{id} can populate
                // AccountUpdateDto.version (which is annotated @NotNull
                // for optimistic-lock enforcement). The token originates
                // from the JPA @Version annotation on the Account
                // entity; Hibernate increments it on every UPDATE. The
                // value is null-safe — for a detached/projected Account
                // it would render as null in the DTO rather than
                // silently emitting 0.
                // COBOL: no direct equivalent. The CICS pseudo-
                // conversational pattern used READ UPDATE / REWRITE for
                // implicit single-task locking; the Java target uses
                // JPA optimistic locking with a monotonically
                // increasing version token (see AAP §0.4.1 and §0.6.x
                // for Card/Account version semantics).
                account.getVersion());

        // -------------------------------------------------------------
        // Step 7 — Cache the assembled DTO (write-through populate).
        // CacheService.put is fail-open: a Redis driver fault is
        // logged at WARN and swallowed, so the business flow is
        // never blocked by a cache outage (AAP §0.7.1 cache-aside
        // resilience semantics). The TTL is the 5-minute CACHE_TTL
        // constant declared at class scope; subsequent writes by
        // AccountUpdateService / BillPaymentService /
        // InterestCalculationService explicitly evict this key.
        // -------------------------------------------------------------
        cacheService.put(CACHE_NS, cacheKey, dto, CACHE_TTL);

        LOG.info("Account view returned acctId={} custId={}",
                cacheKey, customer.getCustId());

        // -------------------------------------------------------------
        // Step 8 — Return the DTO to the controller, which will wrap
        // it in an ApiResponse<AccountViewDto> envelope and emit it
        // as the HTTP 200 body. The PII discipline is enforced by
        // AccountViewDto.toString() which masks the SSN before any
        // logger consumes the value.
        // -------------------------------------------------------------
        return dto;
    }

    /**
     * Formats an account identifier as an 11-digit zero-padded
     * string &mdash; the canonical cache-key and Kafka-partition-key
     * representation for an {@code ACCT-ID PIC 9(11)} value.
     *
     * <p>Consistency across {@code AccountViewService},
     * {@code AccountUpdateService}, {@code BillPaymentService},
     * {@code KafkaEventPublisher}, and the JWT subject claim ensures
     * the same string identifies the same account everywhere &mdash;
     * cache hits, partition assignment, and audit-log correlation
     * all key off the same 11-character zero-padded form.</p>
     *
     * @param acctId the raw account identifier; assumed non-null and
     *               positive (caller is responsible for validation)
     * @return the 11-digit zero-padded string representation
     */
    private static String acctIdKey(Long acctId) {
        return String.format("%011d", acctId);
    }
}
