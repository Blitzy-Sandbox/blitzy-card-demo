package com.carddemo.batch;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Chunk-step account driver for the CardDemo interest-calculation stage — reader #2 of 5 in the
 * batch pipeline, wired into {@code InterestCalculationJob} (JCL step {@code INTCALC}).
 *
 * <p><strong>COBOL lineage (reference-only, source commit SHA {@code 27d6c6f}).</strong> This reader
 * is the idiomatic Spring Batch replacement for the sequential {@code TCATBAL} (transaction-category
 * balance) scan performed by the batch interest calculator {@code app/cbl/CBACT04C.cbl}, launched by
 * {@code app/jcl/INTCALC.jcl} (input DD {@code TCATBALF}; supporting DDs {@code DISCGRP},
 * {@code ACCTFILE}; output DD {@code SYSTRAN}). In the mainframe program the balance file is opened
 * {@code ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL} on the composite key
 * {@code FD-TRAN-CAT-KEY} (whose leading component is the 11-digit account id), so records arrive in
 * ascending account-id order. The main loop drives a classic <em>control break</em> on the account
 * id:</p>
 *
 * <pre>
 *   PERFORM 1000-TCATBALF-GET-NEXT
 *   ...
 *   IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM        (control break — new account)
 *       IF WS-FIRST-TIME NOT = 'Y'
 *           PERFORM 1050-UPDATE-ACCOUNT              (post accumulated interest to the prior account)
 *       END-IF
 *       MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
 *       PERFORM 1100-GET-ACCT-DATA / 1110-GET-XREF-DATA / 1200-GET-INTEREST-RATE ...
 *   END-IF
 * </pre>
 *
 * <p>The net effect of that control break is that {@code CBACT04C} performs its per-account interest
 * work exactly once for <em>each distinct account that owns at least one {@code TCATBAL} row</em>.
 * This reader reproduces precisely that set: it emits the <strong>distinct account ids present in the
 * transaction-category-balance table, ascending, one {@link Long} per item</strong> — no more, no
 * fewer.</p>
 *
 * <p><strong>Parity note (recorded for traceability, AAP §0.7.3).</strong> The reader emits only
 * accounts that appear in {@code TCATBAL}, <em>not</em> every {@code account} row. Iterating the full
 * account master would process accounts the COBOL control break never visits and would break
 * 100% behavioral parity with {@code CBACT04C}. The equivalent SQL semantics are:</p>
 *
 * <pre>
 *   SELECT DISTINCT trancat_acct_id
 *   FROM   transaction_category_balance
 *   ORDER  BY trancat_acct_id ASC
 * </pre>
 *
 * <p><strong>Per-account business logic is delegated, not implemented here.</strong> Following the
 * COBOL {@code CALL} &rarr; Spring bean mandate (AAP §0.4.3), the interest/fee computation that
 * {@code CBACT04C} performs per account (paragraphs {@code 1200-GET-INTEREST-RATE},
 * {@code 1300-COMPUTE-INTEREST}, {@code 1400-COMPUTE-FEES}, {@code 1050-UPDATE-ACCOUNT}) lives in
 * {@code com.carddemo.service.InterestCalculationService#applyInterestToAccount(Long)}, invoked by the
 * step's {@code ItemProcessor}/{@code ItemWriter}. This reader's sole responsibility is to drive that
 * processor with the correct, ordered set of account ids.</p>
 *
 * <h2>Implementation</h2>
 * <p>The reader extends {@link JdbcPagingItemReader} of {@link Long} configured with a
 * {@link PostgresPagingQueryProvider}. Paging (keyset pagination on {@code trancat_acct_id}) keeps the
 * scan memory-bounded and the step restartable, mirroring the streaming, record-at-a-time nature of the
 * original sequential COBOL read rather than materializing every account id in memory. Because the sort
 * key ({@code trancat_acct_id}) is exactly the projected {@code DISTINCT} column, each page boundary is
 * unambiguous and every distinct account is emitted exactly once in ascending order.</p>
 *
 * <p>Configuration is performed in {@link #afterPropertiesSet()} (invoked by the container through the
 * {@link org.springframework.beans.factory.InitializingBean} contract inherited from
 * {@link JdbcPagingItemReader}) rather than in the constructor; the constructor performs only field
 * assignment so the instance never escapes to an overridable method during construction (keeping the
 * build clean under {@code -Xlint:all}, including {@code this-escape}).</p>
 *
 * <p><strong>End-of-input.</strong> On exhaustion the inherited {@code read()} returns {@code null},
 * which Spring Batch treats as normal end-of-chunk — never an exception — matching the COBOL
 * {@code FILE STATUS '10'} (EOF) handling that terminates the {@code PERFORM UNTIL END-OF-FILE} loop.</p>
 *
 * <p>The bean is a singleton, constructed once and configured once; the underlying reader manages its
 * own paging cursor state per step execution.</p>
 *
 * @see JdbcPagingItemReader
 * @see PostgresPagingQueryProvider
 */
@Component
public class InterestAccountItemReader extends JdbcPagingItemReader<Long> {

    /** SLF4J logger for one-time configuration diagnostics of the interest-calculation account driver. */
    private static final Logger log = LoggerFactory.getLogger(InterestAccountItemReader.class);

    /**
     * Stable reader name registered with the Spring Batch {@code ExecutionContext}. It scopes this
     * reader's restart/paging state and appears in batch metadata, so it is fixed rather than derived.
     */
    private static final String READER_NAME = "interestAccountItemReader";

    /**
     * Physical account-id column of the {@code transaction_category_balance} table (COBOL
     * {@code TRANCAT-ACCT-ID PIC 9(11)}). Used verbatim as the query provider's sort key and as the
     * {@link RowMapper} column label, so the two always agree.
     */
    private static final String ACCOUNT_ID_COLUMN = "trancat_acct_id";

    /**
     * SELECT clause emitting the distinct account ids. The {@code DISTINCT} keyword is part of the
     * clause (not a separate flag) because the paging provider concatenates it directly after
     * {@code SELECT}, yielding {@code SELECT DISTINCT trancat_acct_id ...}.
     */
    private static final String SELECT_CLAUSE = "DISTINCT " + ACCOUNT_ID_COLUMN;

    /** FROM clause — the Flyway-managed transaction-category-balance table (the {@code TCATBAL} KSDS). */
    private static final String FROM_CLAUSE = "transaction_category_balance";

    /**
     * Data source used to run the paging queries. Held so it can be applied in
     * {@link #afterPropertiesSet()} rather than the constructor (see class documentation on
     * {@code this-escape}). Constructor-injected — the only collaborator this reader requires.
     */
    private final DataSource dataSource;

    /**
     * Page size for keyset pagination, sourced from the shared batch chunk size
     * ({@code carddemo.batch.chunk-size}, default {@code 100}). Aligning the page size with the chunk
     * size means each database round-trip fills roughly one chunk, keeping the account scan
     * memory-bounded.
     */
    private final int pageSize;

    /**
     * Creates the reader with its sole collaborator (a JDBC {@link DataSource}) and the configured page
     * size. No configuration methods are invoked here by design: the constructor performs only field
     * assignment so {@code this} is never exposed to an overridable superclass method mid-construction,
     * keeping the {@code -Xlint:all} build warning-free ({@code this-escape}). All wiring happens in
     * {@link #afterPropertiesSet()}.
     *
     * @param dataSource the application {@link DataSource} that backs the paging queries against the
     *                   {@code transaction_category_balance} table; injected by type, must not be
     *                   {@code null}
     * @param pageSize   the keyset-pagination page size, bound from {@code carddemo.batch.chunk-size}
     *                   (falling back to {@code 100} when the property is absent); must be greater than
     *                   zero, validated in {@link #afterPropertiesSet()}
     */
    public InterestAccountItemReader(DataSource dataSource,
            @Value("${carddemo.batch.chunk-size:100}") int pageSize) {
        this.dataSource = dataSource;
        this.pageSize = pageSize;
    }

    /**
     * Builds and validates the underlying {@link JdbcPagingItemReader} configuration, then delegates to
     * the superclass initializer.
     *
     * <p>A {@link PostgresPagingQueryProvider} is assembled to emit the distinct, ascending account ids
     * from {@code transaction_category_balance} (see the class documentation for the equivalent SQL and
     * the {@code CBACT04C} control-break rationale). The account-id column is used both as the sort key
     * and as the {@link RowMapper} column label. The superclass validates the data source, query
     * provider, row mapper, and page size and initializes the query provider against the data source, so
     * no further manual initialization is required.</p>
     *
     * @throws IllegalStateException if the configured page size is not greater than zero (an invalid
     *                               {@code carddemo.batch.chunk-size}), surfaced with a domain-specific
     *                               message before the generic framework assertion would fire
     * @throws Exception             if the superclass initialization fails (for example, an invalid data
     *                               source or query-provider state), as declared by
     *                               {@link JdbcPagingItemReader#afterPropertiesSet()}
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        if (pageSize <= 0) {
            throw new IllegalStateException(
                    "carddemo.batch.chunk-size must be greater than zero but was " + pageSize);
        }

        // Distinct, ascending account ids from the transaction-category-balance table. The sort key is
        // the same column that is projected DISTINCT, so keyset pagination emits each account exactly
        // once in ascending order — matching the CBACT04C control break on TRANCAT-ACCT-ID.
        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause(SELECT_CLAUSE);
        queryProvider.setFromClause(FROM_CLAUSE);
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put(ACCOUNT_ID_COLUMN, Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        setName(READER_NAME);
        setDataSource(dataSource);
        setQueryProvider(queryProvider);
        // trancat_acct_id is BIGINT NOT NULL (a primary-key component), so getLong never observes SQL
        // NULL; the primitive long is autoboxed to the Long item type.
        setRowMapper((rs, rowNum) -> rs.getLong(ACCOUNT_ID_COLUMN));
        setPageSize(pageSize);

        super.afterPropertiesSet();

        log.debug("Configured batch reader '{}' emitting DISTINCT {} FROM {} ORDER BY {} ASC (pageSize={})",
                READER_NAME, ACCOUNT_ID_COLUMN, FROM_CLAUSE, ACCOUNT_ID_COLUMN, pageSize);
    }
}
