package com.carddemo.batch;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.StepScopeTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the CardDemo batch pipeline's <strong>five item readers</strong>, exercised
 * against a <strong>real, V3-seeded PostgreSQL&nbsp;16</strong> database (no mocks) inherited from
 * {@link AbstractBatchIntegrationTest}. Executed by the Maven <strong>Failsafe</strong> plugin (the
 * {@code *IT} suffix), it verifies, for every reader, the two contracts that let a Spring Batch
 * chunk-step faithfully replace the legacy COBOL {@code PERFORM UNTIL END-OF-FILE} read loop:
 *
 * <ol>
 *   <li><strong>Ordering parity</strong> &mdash; each reader emits rows in the ascending key order of
 *       its COBOL {@code SORT}/keyed-access lineage. Ordering is not cosmetic: the statement and
 *       report writers are control-break / stateful and assume ascending input (they group and total
 *       on a key change), so a reader that returned rows out of order would silently corrupt the
 *       migrated output.</li>
 *   <li><strong>End-of-input returns {@code null}, never an exception</strong> &mdash; the idiomatic
 *       Spring Batch signal that terminates a chunk step. This is the exact counterpart of the COBOL
 *       {@code FILE STATUS '10'} branch (which sets {@code END-OF-FILE = 'Y'} and ends the
 *       {@code PERFORM UNTIL} loop) rather than routing to an ABEND paragraph &mdash; AAP&nbsp;&sect;0.8.3:
 *       the EOF code {@code '10'} is normal loop termination, not an error.</li>
 * </ol>
 *
 * <h2>Readers under test (all in {@code com.carddemo.batch})</h2>
 * <table border="1">
 *   <caption>Reader &rarr; COBOL/JCL lineage (source SHA {@code 27d6c6f}) &rarr; ascending key</caption>
 *   <tr><th>Reader</th><th>Item</th><th>Legacy source</th><th>Ascending key</th></tr>
 *   <tr><td>{@link DailyTransactionItemReader}</td><td>{@code DailyTransaction}</td>
 *       <td>CBTRN02C {@code 1000-DALYTRAN-GET-NEXT}</td><td>{@code dalytranId} (DALYTRAN-ID)</td></tr>
 *   <tr><td>{@link InterestAccountItemReader}</td><td>{@code Long}</td>
 *       <td>CBACT04C {@code TCATBAL} control break</td><td>DISTINCT {@code trancat_acct_id}</td></tr>
 *   <tr><td>{@link CombineTransactionItemReader}</td><td>{@code Transaction}</td>
 *       <td>COMBTRAN.jcl {@code SORT FIELDS=(TRAN-ID,A)}</td><td>{@code tranId} (TRAN-ID)</td></tr>
 *   <tr><td>{@link StatementCardXrefItemReader}</td><td>{@code CardXref}</td>
 *       <td>CBSTM03A {@code 1000-XREFFILE-GET-NEXT}</td><td>{@code xrefCardNum} (XREF-CARD-NUM)</td></tr>
 *   <tr><td>{@link TransactionReportItemReader}</td><td>{@code Transaction}</td>
 *       <td>CBTRN03C / TRANREPT DFSORT {@code TRAN-CARD-NUM,A}</td>
 *       <td>{@code tranCardNum} then {@code tranId}</td></tr>
 * </table>
 *
 * <h2>Step-scope draining</h2>
 * <p>Although every reader is a plain singleton {@link org.springframework.stereotype.Component}
 * (none is {@code @StepScope}), each is a {@link ItemStream} whose {@code read()} cursor only becomes
 * live after {@code open(ExecutionContext)}. The {@link #drain(ItemReader)} /
 * {@link #drainPastEof(ItemReader)} helpers therefore run inside a Spring Batch step scope
 * &mdash; established with {@link StepScopeTestUtils#doInStepScope(org.springframework.batch.core.StepExecution,
 * java.util.concurrent.Callable) StepScopeTestUtils.doInStepScope} over a
 * {@link MetaDataInstanceFactory#createStepExecution()} &mdash; and perform the full
 * {@code open} &rarr; {@code read}-until-{@code null} &rarr; {@code close} lifecycle. The class is
 * annotated {@link SpringBatchTest @SpringBatchTest} so the batch step/job scopes are registered in
 * the test context. (In spring-batch-test 5.2.x, {@code @SpringBatchTest}'s auto-registered
 * {@code JobLauncherTestUtils} resolves its {@code Job} via {@code ObjectProvider.ifUnique(...)},
 * which is a no-op when several {@code Job} beans exist &mdash; as they do here &mdash; so the context
 * still starts cleanly and no {@code Job} needs to be {@code @Primary}.)</p>
 *
 * <h2>Counts are cross-checked against the live database (shared-container safe)</h2>
 * <p>{@link AbstractBatchIntegrationTest} uses the Testcontainers <em>singleton-container</em> pattern:
 * one PostgreSQL instance is shared across every {@code *IT} in a {@code mvn verify} run. Sibling
 * integration tests (for example a transaction-posting {@code IT}) may therefore populate the
 * runtime-filled {@code transaction} table before this test runs. To stay green regardless of that
 * ordering, each reader's item count is asserted against a <strong>live {@code COUNT(*)} of the same
 * table</strong> (via {@link JdbcTemplate}) rather than a hard-coded literal: the invariant verified is
 * "the reader emits <em>every</em> matching row". For the tables that no pipeline job mutates &mdash;
 * the input {@code daily_transaction} fixture (Gate&nbsp;1/4 {@code dailytran.txt}) and the reference
 * {@code card_xref} table &mdash; the stable seeded counts are additionally asserted as parity anchors.
 * This test is strictly <strong>read-only</strong> (it drains, never writes), so the shared container
 * stays pristine for sibling {@code *IT}s.</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}
 * (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}); it is not copied here. Longer-form design
 * rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see AbstractBatchIntegrationTest
 * @see DailyTransactionItemReader
 * @see InterestAccountItemReader
 * @see CombineTransactionItemReader
 * @see StatementCardXrefItemReader
 * @see TransactionReportItemReader
 */
@SpringBatchTest
@DisplayName("Batch readers IT — ordering parity and EOF→null against real V3-seeded PostgreSQL")
class BatchReadersIT extends AbstractBatchIntegrationTest {

    // -----------------------------------------------------------------------------------------------
    // Stable seed anchors (V3__seed_data.sql). These tables are never mutated by any pipeline job:
    // daily_transaction is read-only batch input; card_xref is read-only reference data; the
    // distinct-account set of transaction_category_balance is fixed (posting/interest never introduce
    // a new account id). They are safe to assert as exact parity anchors even under the shared
    // singleton container.
    // -----------------------------------------------------------------------------------------------

    /** Rows seeded into {@code daily_transaction} (Gate 1/4 fixture {@code app/data/ASCII/dailytran.txt}). */
    private static final long SEEDED_DAILY_TRANSACTIONS = 300L;

    /** Rows seeded into {@code card_xref} (one card cross-reference per seeded card). */
    private static final long SEEDED_CARD_XREFS = 50L;

    /** Distinct {@code trancat_acct_id} values in {@code transaction_category_balance} (the accounts CBACT04C visits). */
    private static final long SEEDED_DISTINCT_INTEREST_ACCOUNTS = 50L;

    // -----------------------------------------------------------------------------------------------
    // Live COUNT queries used to derive each reader's expected item count from the current database
    // state. "transaction" is quoted because it is a SQL reserved word (the table is created unquoted
    // in V1__schema.sql; a lower-case quoted identifier resolves to that same relation in PostgreSQL).
    // -----------------------------------------------------------------------------------------------

    /** Live row count of the daily-transaction staging table (drives {@link DailyTransactionItemReader}). */
    private static final String SQL_COUNT_DAILY_TRANSACTIONS =
            "SELECT COUNT(*) FROM daily_transaction";

    /** Live count of DISTINCT accounts owning a category balance (drives {@link InterestAccountItemReader}). */
    private static final String SQL_COUNT_DISTINCT_INTEREST_ACCOUNTS =
            "SELECT COUNT(DISTINCT trancat_acct_id) FROM transaction_category_balance";

    /** Live row count of the transaction master (drives {@link CombineTransactionItemReader} / {@link TransactionReportItemReader}). */
    private static final String SQL_COUNT_TRANSACTIONS =
            "SELECT COUNT(*) FROM \"transaction\"";

    /** Live row count of the card cross-reference table (drives {@link StatementCardXrefItemReader}). */
    private static final String SQL_COUNT_CARD_XREFS =
            "SELECT COUNT(*) FROM card_xref";

    // -----------------------------------------------------------------------------------------------
    // Reader beans under test — autowired by concrete type (each is a unique singleton bean).
    // -----------------------------------------------------------------------------------------------

    /** Reader #1: {@code daily_transaction} ascending by {@code dalytranId} (CBTRN02C posting input). */
    @Autowired
    private DailyTransactionItemReader dailyTransactionItemReader;

    /** Reader #2: DISTINCT interest account ids ascending (CBACT04C {@code TCATBAL} control break). */
    @Autowired
    private InterestAccountItemReader interestAccountItemReader;

    /** Reader #3: transaction master ascending by {@code tranId} (COMBTRAN.jcl {@code SORT FIELDS=(TRAN-ID,A)}). */
    @Autowired
    private CombineTransactionItemReader combineTransactionItemReader;

    /** Reader #4: card cross-reference ascending by {@code xrefCardNum} (CBSTM03A statement driver). */
    @Autowired
    private StatementCardXrefItemReader statementCardXrefItemReader;

    /** Reader #5: transaction master ascending by {@code (tranCardNum, tranId)} (CBTRN03C / TRANREPT DFSORT). */
    @Autowired
    private TransactionReportItemReader transactionReportItemReader;

    /** JDBC access to the same live database, used to derive each reader's expected item count. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===============================================================================================
    // Draining helpers — run the full ItemStream lifecycle inside a Spring Batch step scope.
    // ===============================================================================================

    /**
     * Drains a reader completely inside a fresh step scope: opens it (if it is an {@link ItemStream}),
     * reads until {@code read()} returns {@code null}, then closes it. The terminating {@code null}
     * read is precisely the COBOL {@code FILE STATUS '10'} end-of-file signal; because the loop exits
     * on {@code null} rather than on a thrown exception, a successful return of this method already
     * demonstrates the EOF-is-not-an-error contract for the drained reader.
     *
     * @param reader the reader to drain; typically a {@link ItemStream}-backed batch reader
     * @param <T>    the reader's item type
     * @return every item the reader produced, in the exact order it produced them
     * @throws Exception if the reader raises a genuine I/O/mapping error (which must propagate and fail
     *                   the step, mirroring the COBOL ABEND branch — EOF is never such an error)
     */
    private <T> List<T> drain(final ItemReader<T> reader) throws Exception {
        return StepScopeTestUtils.doInStepScope(
                MetaDataInstanceFactory.createStepExecution(),
                () -> {
                    final List<T> items = new ArrayList<>();
                    final ItemStream stream = (reader instanceof ItemStream s) ? s : null;
                    if (stream != null) {
                        stream.open(new ExecutionContext());
                    }
                    try {
                        T item;
                        while ((item = reader.read()) != null) {
                            items.add(item);
                        }
                    } finally {
                        if (stream != null) {
                            stream.close();
                        }
                    }
                    return items;
                });
    }

    /**
     * Exhausts a reader in a fresh step scope and then performs <strong>one further</strong>
     * {@code read()} strictly past end-of-input, returning that result. The value MUST be {@code null}
     * and the extra read MUST NOT throw: reading past EOF is idempotent {@code null}, never a wrap-around
     * or an exception (COBOL {@code FILE STATUS '10'} remains {@code END-OF-FILE = 'Y'}). Any exception
     * escaping this method (from the trailing read or from the drain) fails the calling test, proving the
     * negative — that EOF is not surfaced as an error.
     *
     * @param reader the reader to exhaust and then read once more; may be a wildcard-typed reader
     * @param <T>    the reader's item type
     * @return the result of the read taken one position past end-of-input (expected {@code null})
     * @throws Exception if any read raises a genuine error (which must propagate and fail the test)
     */
    private <T> T drainPastEof(final ItemReader<T> reader) throws Exception {
        return StepScopeTestUtils.doInStepScope(
                MetaDataInstanceFactory.createStepExecution(),
                () -> {
                    final ItemStream stream = (reader instanceof ItemStream s) ? s : null;
                    if (stream != null) {
                        stream.open(new ExecutionContext());
                    }
                    try {
                        while (reader.read() != null) {
                            // Exhaust every item so the next read() sits strictly past end-of-input.
                        }
                        // One read PAST EOF — must be null again, never an exception.
                        return reader.read();
                    } finally {
                        if (stream != null) {
                            stream.close();
                        }
                    }
                });
    }

    /**
     * Executes a scalar {@code COUNT} query against the live database and returns the result as a
     * primitive {@code long}. Used to derive each reader's expected item count from the current table
     * state, so the count assertions hold whether or not a sibling {@code *IT} has populated the
     * runtime-filled {@code transaction} table in the shared singleton container.
     *
     * @param countSql a query returning a single non-null {@code COUNT} scalar
     * @return the count as a {@code long}
     */
    private long countInDatabase(final String countSql) {
        final Long count = jdbcTemplate.queryForObject(countSql, Long.class);
        assertThat(count).as("COUNT query must return a non-null scalar: %s", countSql).isNotNull();
        return count;
    }

    // ===============================================================================================
    // Per-reader ordering + count (COBOL SORT / keyed-access parity).
    // ===============================================================================================

    @Test
    @DisplayName("DailyTransactionItemReader → every daily_transaction row, ascending by dalytranId")
    void dailyTransactionReader_emitsAllRowsAscendingByDalytranId() throws Exception {
        final long expected = countInDatabase(SQL_COUNT_DAILY_TRANSACTIONS);
        // Parity anchor: daily_transaction is read-only batch input and is never mutated by a job.
        assertThat(expected)
                .as("seeded daily_transaction rows (Gate 1/4 fixture dailytran.txt)")
                .isEqualTo(SEEDED_DAILY_TRANSACTIONS);

        final var rows = drain(dailyTransactionItemReader);
        assertThat(rows)
                .as("DailyTransactionItemReader must emit every daily_transaction row")
                .hasSize(Math.toIntExact(expected));

        // Ascending DALYTRAN-ID: CBTRN02C reads the pre-sorted daily-transaction file sequentially.
        final var keys = rows.stream().map(r -> r.getDalytranId()).toList();
        assertThat(keys)
                .as("dalytranId must be in ascending (non-decreasing) order")
                .isSorted();
    }

    @Test
    @DisplayName("InterestAccountItemReader → DISTINCT account ids, unique and ascending")
    void interestAccountReader_emitsDistinctAccountIdsAscending() throws Exception {
        final long expected = countInDatabase(SQL_COUNT_DISTINCT_INTEREST_ACCOUNTS);
        // Parity anchor: the distinct-account set of transaction_category_balance is fixed; posting and
        // interest jobs update balances but never introduce a new account id.
        assertThat(expected)
                .as("seeded DISTINCT interest accounts (CBACT04C control-break set)")
                .isEqualTo(SEEDED_DISTINCT_INTEREST_ACCOUNTS);

        final var accountIds = drain(interestAccountItemReader);
        assertThat(accountIds)
                .as("InterestAccountItemReader must emit exactly the distinct account ids")
                .hasSize(Math.toIntExact(expected));

        // CBACT04C performs its per-account work once per DISTINCT account — assert both uniqueness and
        // ascending order (the reader projects DISTINCT trancat_acct_id ORDER BY trancat_acct_id ASC).
        assertThat(accountIds)
                .as("each interest account id must appear exactly once (DISTINCT)")
                .doesNotHaveDuplicates();
        assertThat(accountIds)
                .as("interest account ids must be in ascending (non-decreasing) order")
                .isSorted();
    }

    @Test
    @DisplayName("CombineTransactionItemReader → every transaction, ascending by tranId (SORT FIELDS=(TRAN-ID,A))")
    void combineTransactionReader_emitsAllTransactionsAscendingByTranId() throws Exception {
        // Live count (0 at seed; may be > 0 if a sibling posting IT ran first in the shared container).
        final long expected = countInDatabase(SQL_COUNT_TRANSACTIONS);

        final var transactions = drain(combineTransactionItemReader);
        assertThat(transactions)
                .as("CombineTransactionItemReader must emit every row of the transaction master")
                .hasSize(Math.toIntExact(expected));

        // DFSORT SORT FIELDS=(TRAN-ID,A): ascending by tranId (empty when the master has not been posted).
        final var keys = transactions.stream().map(t -> t.getTranId()).toList();
        assertThat(keys)
                .as("tranId must be in ascending (non-decreasing) order")
                .isSorted();
    }

    @Test
    @DisplayName("StatementCardXrefItemReader → every card_xref row, ascending by xrefCardNum")
    void statementCardXrefReader_emitsAllRowsAscendingByCardNumber() throws Exception {
        final long expected = countInDatabase(SQL_COUNT_CARD_XREFS);
        // Parity anchor: card_xref is read-only reference data and is never mutated by a job.
        assertThat(expected)
                .as("seeded card_xref rows")
                .isEqualTo(SEEDED_CARD_XREFS);

        final var xrefs = drain(statementCardXrefItemReader);
        assertThat(xrefs)
                .as("StatementCardXrefItemReader must emit every card_xref row")
                .hasSize(Math.toIntExact(expected));

        // Ascending XREF-CARD-NUM: CBSTM03A reads the cross-reference file one card at a time in key order.
        final var keys = xrefs.stream().map(x -> x.getXrefCardNum()).toList();
        assertThat(keys)
                .as("xrefCardNum must be in ascending (non-decreasing) order")
                .isSorted();
    }

    @Test
    @DisplayName("TransactionReportItemReader → every transaction, ascending by (tranCardNum, tranId)")
    void transactionReportReader_emitsAllTransactionsAscendingByCardThenTranId() throws Exception {
        // Same runtime-filled master as the combine reader; live count keeps this shared-container safe.
        final long expected = countInDatabase(SQL_COUNT_TRANSACTIONS);

        final var transactions = drain(transactionReportItemReader);
        assertThat(transactions)
                .as("TransactionReportItemReader must emit every row of the transaction master")
                .hasSize(Math.toIntExact(expected));

        // DFSORT TRAN-CARD-NUM,A then a deterministic tranId tie-breaker. A composite string key with a
        // low-ordinal separator ('\u0001', below any digit/letter) makes lexical comparison reproduce the
        // (card number, then transaction id) total order the report writer's control break relies on.
        final var compositeKeys = transactions.stream()
                .map(t -> t.getTranCardNum() + '\u0001' + t.getTranId())
                .toList();
        assertThat(compositeKeys)
                .as("transactions must be ascending by (tranCardNum, tranId)")
                .isSorted();
    }

    // ===============================================================================================
    // EOF → null for every reader (never an exception): AAP §0.8.3, COBOL FILE STATUS '10'.
    // ===============================================================================================

    @Test
    @DisplayName("All five readers return null at end-of-input and never throw (FILE STATUS '10' parity)")
    void allReaders_returnNullAtEof_withoutThrowing() throws Exception {
        final List<ItemReader<?>> readers = List.<ItemReader<?>>of(
                dailyTransactionItemReader,
                interestAccountItemReader,
                combineTransactionItemReader,
                statementCardXrefItemReader,
                transactionReportItemReader);

        for (final ItemReader<?> reader : readers) {
            // drainPastEof exhausts the reader and reads once more past end-of-input; that trailing read
            // must be null. Any exception (from EOF or the drain) propagates and fails this test.
            assertThat(drainPastEof(reader))
                    .as("read() past end-of-input for %s must be null (COBOL FILE STATUS '10' → normal termination)",
                            reader.getClass().getSimpleName())
                    .isNull();
        }
    }
}
