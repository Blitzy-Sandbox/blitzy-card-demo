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

import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Account-file reader batch service &mdash; the Java target for the
 * COBOL batch program {@code app/cbl/CBACT01C.cbl}.
 *
 * <p>This service implements the diagnostic/inspection scan of the
 * {@code ACCTFILE} VSAM cluster. The COBOL source opens the cluster,
 * sequentially reads every record, displays each field via
 * {@code DISPLAY} statements (paragraph
 * {@code 1100-DISPLAY-ACCT-RECORD} at L120-L132), and closes the
 * cluster. The Java target streams every {@link Account} JPA entity
 * via paged repository reads to avoid loading the whole table into
 * memory.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT01C.cbl} &mdash;
 *       diagnostic account-file reader invoked by JCL job
 *       {@code app/jcl/READACCT.jcl}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}, 300 bytes; {@link Account}).</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBACT01C.cbl &harr; AccountFileReaderService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L70-L88)</td>
 *       <td>{@link #scan(Consumer)} or {@link #count()}</td></tr>
 *   <tr><td>{@code 0000-ACCTFILE-OPEN}</td>
 *       <td>Spring Data JPA implicit DB connection acquisition</td></tr>
 *   <tr><td>{@code 1000-ACCTFILE-GET-NEXT}</td>
 *       <td>{@link AccountRepository#findAll(org.springframework.data.domain.Pageable)} (paged)</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-ACCT-RECORD} (L120-L132)</td>
 *       <td>{@link #logAccountRecord(Account)} (SLF4J INFO)</td></tr>
 *   <tr><td>{@code 9000-ACCTFILE-CLOSE}</td>
 *       <td>(automatic by Spring) &mdash; JDBC connection returned to
 *       pool</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} (L169-L173)</td>
 *       <td>(unreachable in JPA) &mdash; the COBOL ABEND on file
 *       error becomes a propagated unchecked exception</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Paged reads:</b> The COBOL source can stream a large
 *       cluster because VSAM provides sequential cursor I/O. JPA's
 *       {@link AccountRepository#findAll()} would materialize the
 *       entire table; instead, this service uses page-by-page reads
 *       with {@code PageRequest.of(page, PAGE_SIZE,
 *       Sort.by("acctId"))} to bound memory while preserving the
 *       in-key-order traversal of the COBOL source.</li>
 *   <li><b>Read-only transaction:</b> Annotated
 *       {@link Transactional @Transactional(readOnly = true)} to
 *       enable read-optimization in PostgreSQL.</li>
 *   <li><b>Default action:</b> {@link #scan()} (no-arg) logs each
 *       record at INFO level &mdash; matching the COBOL
 *       {@code DISPLAY} behavior. Programmatic callers can supply
 *       their own {@link Consumer} to override (for example, a
 *       Spring Batch ItemWriter).</li>
 *   <li><b>PCI-safe logging:</b> The Account entity has no
 *       sensitive fields (no full card numbers in this entity);
 *       however, the {@link Account#toString()} implementation
 *       still masks the FICO and SSN fields per AAP &sect;0.6.6.</li>
 * </ul>
 */
@Service
public class AccountFileReaderService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AccountFileReaderService.class);

    /**
     * Page size used by {@link #scan(Consumer)}. Chosen large enough
     * to amortize per-page query overhead and small enough to keep
     * peak heap usage bounded.
     */
    static final int PAGE_SIZE = 500;

    private final AccountRepository accountRepository;

    public AccountFileReaderService(AccountRepository accountRepository) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
    }

    /**
     * Result summary returned to the caller.
     *
     * @param recordsRead total number of {@link Account} rows
     *                    encountered by the scan
     */
    public record Result(long recordsRead) {
    }

    /**
     * Scans every {@link Account} record and logs it at INFO level,
     * exactly mirroring the COBOL paragraph
     * {@code 1100-DISPLAY-ACCT-RECORD}.
     *
     * @return a {@link Result} with the total number of records read
     */
    @Transactional(readOnly = true)
    public Result scan() {
        return scan(this::logAccountRecord);
    }

    /**
     * Scans every {@link Account} record and applies a caller-supplied
     * action to each. Used by Spring Batch wrappers that need to do
     * something other than log (e.g., serialize to S3 backup).
     *
     * @param action callback applied to each {@link Account} in order
     *               of ascending {@link Account#getAcctId()}
     * @return a {@link Result} with the total number of records read
     */
    @Transactional(readOnly = true)
    public Result scan(Consumer<Account> action) {
        Objects.requireNonNull(action, "action");
        LOG.info("CBACT01C: START OF EXECUTION OF PROGRAM CBACT01C");

        long total = 0L;
        int page = 0;
        Page<Account> currentPage;
        do {
            currentPage = accountRepository.findAll(
                    PageRequest.of(page, PAGE_SIZE, Sort.by("acctId")));
            for (Account account : currentPage.getContent()) {
                action.accept(account);
                total++;
            }
            page++;
        } while (currentPage.hasNext());

        LOG.info("CBACT01C: END OF EXECUTION OF PROGRAM CBACT01C; recordsRead={}",
                total);
        return new Result(total);
    }

    /**
     * Returns the number of {@link Account} rows in the underlying
     * table &mdash; useful for sanity checks before/after seed loads.
     */
    @Transactional(readOnly = true)
    public long count() {
        return accountRepository.count();
    }

    /**
     * COBOL: 1100-DISPLAY-ACCT-RECORD (L120-L132). Logs each field
     * with the original 25-character label prefix.
     */
    void logAccountRecord(Account account) {
        if (account == null) {
            return;
        }
        if (LOG.isInfoEnabled()) {
            LOG.info("ACCT-ID                 :{}", account.getAcctId());
            LOG.info("ACCT-ACTIVE-STATUS      :{}", account.getAcctActiveStatus());
            LOG.info("ACCT-CURR-BAL           :{}", account.getAcctCurrBal());
            LOG.info("ACCT-CREDIT-LIMIT       :{}", account.getAcctCreditLimit());
            LOG.info("ACCT-CASH-CREDIT-LIMIT  :{}", account.getAcctCashCreditLimit());
            LOG.info("ACCT-OPEN-DATE          :{}", account.getAcctOpenDate());
            LOG.info("ACCT-EXPIRAION-DATE     :{}", account.getAcctExpirationDate());
            LOG.info("ACCT-REISSUE-DATE       :{}", account.getAcctReissueDate());
            LOG.info("ACCT-CURR-CYC-CREDIT    :{}", account.getAcctCurrCycCredit());
            LOG.info("ACCT-CURR-CYC-DEBIT     :{}", account.getAcctCurrCycDebit());
            LOG.info("ACCT-GROUP-ID           :{}", account.getAcctGroupId());
            LOG.info("-------------------------------------------------");
        }
    }
}
