package com.carddemo.integration.batch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code combineTransactionsJob} Spring Batch job, proving behavioral
 * parity with the mainframe combine pipeline {@code COMBTRAN.jcl}
 * (lineage: source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is copied).
 *
 * <p>The JCL job had two steps that are re-platformed and verified here:</p>
 * <ul>
 *   <li><strong>{@code STEP05R EXEC PGM=SORT}</strong> &mdash; {@code SORT FIELDS=(TRAN-ID,A)} is
 *       reproduced by the in-JVM comparator
 *       {@link CombineTransactionsProcessor#BY_TRAN_ID} (ascending by the 16-byte transaction id);
 *       the sorted union of {@code TRANSACT.BKUP} and {@code SYSTRAN} is written to the
 *       {@code COMBINED} S3 object.</li>
 *   <li><strong>{@code STEP10 EXEC PGM=IDCAMS} ({@code REPRO})</strong> &mdash; reproduced by a
 *       bulk JPA {@code saveAll} merge keyed on {@code tranId}; because {@code tranId} is the JPA
 *       {@code @Id}, the load is idempotent (upsert, never duplicate-key).</li>
 * </ul>
 *
 * <p>This test extends {@link AbstractBatchIntegrationTest} and reuses ALL of its scaffolding
 * (singleton Testcontainers PostgreSQL + LocalStack, the {@code @DynamicPropertySource} wiring and
 * AWS self-provisioning, the fixture locator, the S3 helpers, the {@link org.springframework.batch.core.launch.JobLauncher},
 * the per-test {@code @BeforeEach} S3 cleanup, and the shared constants). No container, property,
 * {@code @SpringBootTest}, {@code @ActiveProfiles}, {@code @Testcontainers}, or {@code @Tag}
 * scaffolding is re-declared here.</p>
 *
 * <p>Test data is derived from a real {@code dailytran.txt} fixture slice (the CVTRA06Y daily
 * layout is byte-identical to the CVTRA05Y transaction layout, so every field already parses
 * cleanly); only the 16-byte {@code tranId} is overwritten with a collision-free {@code ZZIT}
 * prefix so that database delta assertions are deterministic against the shared singleton
 * database.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CombineTransactionsJobIT extends AbstractBatchIntegrationTest {

    /**
     * Exact S3 key of the combined output object. This matches the production job's default
     * combined key ({@code carddemo.batch.combine.combined-key}, default {@code TRANSACT.COMBINED}),
     * which the {@code test} profile does not override, so {@link #sortFidelityIsPreserved()}
     * asserts the object exists at this exact key rather than scanning the bucket for it.
     */
    private static final String COMBINED_KEY = "TRANSACT.COMBINED";

    /** Number of bytes of a transaction id ({@code TRAN-ID PIC X(16)}). */
    private static final int TRAN_ID_LENGTH = 16;

    /** Collision-free id prefix guaranteeing the test rows cannot pre-exist in the shared database. */
    private static final String IT_ID_PREFIX = "ZZIT";

    /** The combine job under test, injected by its bean name. */
    @Autowired
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    /** Repository used for existence, delta, and idempotency assertions against the loaded master. */
    @Autowired
    private TransactionRepository transactionRepository;

    // ------------------------------------------------------------------------------------------
    // Test-data helpers (Phase A): derive valid records from a real fixture slice, overwriting
    // only the 16-byte tranId so the strict production parser never fails on numeric/overpunch
    // fields, while keeping the ids collision-free and deterministic.
    // ------------------------------------------------------------------------------------------

    /**
     * Mints a 350-byte record by cloning a valid base record and overwriting only its 16-byte
     * transaction id.
     *
     * @param base     a valid 350-byte CVTRA05Y record (every non-id field already parseable)
     * @param tranId16 the replacement transaction id, exactly 16 characters
     * @return a new 350-byte record carrying {@code tranId16}
     */
    private static byte[] recordWithTranId(final byte[] base, final String tranId16) {
        final byte[] record = base.clone();
        final byte[] id = tranId16.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(id, 0, record, 0, TRAN_ID_LENGTH);
        return record;
    }

    /**
     * Builds a 16-character, collision-free transaction id for the given sequence number
     * ({@code "ZZIT"} + 12 zero-padded digits).
     *
     * @param seq the sequence number (1..N)
     * @return the 16-character test transaction id
     */
    private static String itTranId(final int seq) {
        return String.format("%s%012d", IT_ID_PREFIX, seq);
    }

    /**
     * Concatenates fixed 350-byte records (no delimiter) for the given sequence numbers, each minted
     * from {@code base} via {@link #recordWithTranId(byte[], String)}.
     *
     * @param base the valid base record to clone
     * @param seqs the sequence numbers whose records to concatenate, in the given (scrambled) order
     * @return the concatenated record stream ({@code seqs.length * 350} bytes)
     */
    private static byte[] concatRecords(final byte[] base, final int[] seqs) {
        final byte[] out = new byte[seqs.length * DAILY_TRAN_RECORD_LENGTH];
        for (int i = 0; i < seqs.length; i++) {
            final byte[] record = recordWithTranId(base, itTranId(seqs[i]));
            System.arraycopy(record, 0, out, i * DAILY_TRAN_RECORD_LENGTH, DAILY_TRAN_RECORD_LENGTH);
        }
        return out;
    }

    /**
     * Uploads the standard scrambled two-source input set and returns the sorted list of the five
     * distinct test ids.
     *
     * <p>The five ids ({@code ZZIT...0001..0005}) are split across the two combine inputs in
     * scrambled order &mdash; {@code TRANSACT.BKUP} carries seqs {@code {3,1,5}} and {@code SYSTRAN}
     * carries seqs {@code {2,4}} &mdash; so the two-source read and the sort are both exercised.
     * This helper is deterministic (identical bytes and ids on every call) so the idempotency test
     * can re-seed identical inputs.</p>
     *
     * @return the five test ids in ascending order
     * @throws IOException if the {@code dailytran.txt} fixture is located but cannot be read
     */
    private List<String> seedScrambledInputs() throws IOException {
        final byte[] fixture = requireFixtureBytes(DAILY_TRAN_KEY);
        final byte[] base = Arrays.copyOfRange(fixture, 0, DAILY_TRAN_RECORD_LENGTH);
        final byte[] bkupBytes = concatRecords(base, new int[] {3, 1, 5});
        final byte[] sysBytes = concatRecords(base, new int[] {2, 4});
        // The production combine reader consumes ONE configured bucket/key (the output bucket);
        // uploading the same objects to both buckets makes the test robust to that configuration
        // without ever double-counting, since the redundant copy is simply never read.
        putS3Object(BUCKET_OUTPUT, "TRANSACT.BKUP", bkupBytes);
        putS3Object(BUCKET_OUTPUT, "SYSTRAN", sysBytes);
        putS3Object(BUCKET_INPUT, "TRANSACT.BKUP", bkupBytes);
        putS3Object(BUCKET_INPUT, "SYSTRAN", sysBytes);
        return IntStream.of(1, 2, 3, 4, 5)
                .mapToObj(CombineTransactionsJobIT::itTranId)
                .sorted()
                .toList();
    }

    /**
     * Launches the combine job with only the unique {@code run.id} parameter (the combine stage
     * needs no business parameters), yielding a distinct {@code JobInstance} per launch.
     *
     * @return the resulting job execution
     * @throws Exception if the launch fails (e.g. an already-running or already-complete instance)
     */
    private JobExecution launchCombine() throws Exception {
        return jobLauncher.run(combineTransactionsJob, baseParams().toJobParameters());
    }

    /**
     * Creates a {@link Transaction} carrying only the given id (the comparator under test reads only
     * {@code getTranId()}).
     *
     * @param tranId the transaction id to set
     * @return a transaction with only its id populated
     */
    private static Transaction transactionWithId(final String tranId) {
        final Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    /**
     * Splits the combined S3 payload into its constituent 350-character records, mirroring the
     * production record parser: when line terminators are present (the combined object this job
     * writes is one newline-terminated record per line) the payload is split on line breaks;
     * otherwise it is sliced into fixed 350-character records (raw fixed-width concatenation).
     *
     * @param data the raw combined-object bytes
     * @return the decoded records in file order
     */
    private static List<String> splitCombinedRecords(final byte[] data) {
        final String content = new String(data, StandardCharsets.ISO_8859_1);
        final List<String> records = new ArrayList<>();
        if (content.indexOf('\n') >= 0 || content.indexOf('\r') >= 0) {
            for (final String line : content.split("\\R", -1)) {
                if (!line.isEmpty()) {
                    records.add(line);
                }
            }
        } else {
            for (int off = 0; off + DAILY_TRAN_RECORD_LENGTH <= content.length();
                    off += DAILY_TRAN_RECORD_LENGTH) {
                records.add(content.substring(off, off + DAILY_TRAN_RECORD_LENGTH));
            }
        }
        return records;
    }

    // ------------------------------------------------------------------------------------------
    // Test 1 (Phase C): the job and both named steps complete.
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the two-step combine job runs to {@code COMPLETED} and that both named steps
     * ({@code combineSortStep} &rarr; {@code combineLoadStep}) execute and complete.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(1)
    void jobAndBothStepsComplete() throws Exception {
        seedScrambledInputs();

        final JobExecution execution = launchCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final List<String> stepNames = execution.getStepExecutions().stream()
                .map(se -> se.getStepName())
                .toList();
        assertThat(stepNames).contains("combineSortStep", "combineLoadStep");
        execution.getStepExecutions().forEach(se ->
                assertThat(se.getStatus()).isEqualTo(BatchStatus.COMPLETED));
    }

    // ------------------------------------------------------------------------------------------
    // Test 2 (Phase D): SORT fidelity — the DFSORT SORT FIELDS=(TRAN-ID,A) replacement.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the DFSORT replacement two ways: (D.1) the production comparator
     * {@link CombineTransactionsProcessor#BY_TRAN_ID} always orders ascending by {@code tranId};
     * and (D.2, guarded) the {@code COMBINED} S3 object the job produces is itself byte-sorted
     * ascending by {@code tranId} and contains every seeded id.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(2)
    void sortFidelityIsPreserved() throws Exception {
        // D.1 — always-on: the comparator pins SORT FIELDS=(TRAN-ID,A) (ascending by id).
        final List<Transaction> unsorted = new ArrayList<>();
        unsorted.add(transactionWithId("C"));
        unsorted.add(transactionWithId("A"));
        unsorted.add(transactionWithId("B"));
        unsorted.sort(CombineTransactionsProcessor.BY_TRAN_ID);
        final List<String> orderedIds = unsorted.stream().map(Transaction::getTranId).toList();
        assertThat(orderedIds).containsExactly("A", "B", "C");

        // D.2 — best-effort: the COMBINED object is itself sorted ascending by tranId.
        final List<String> sortedIds = seedScrambledInputs();
        launchCombine();

        final byte[] combined = getS3ObjectOrNull(BUCKET_OUTPUT, COMBINED_KEY);
        assertThat(combined)
                .as("combine job must write the combined output at the exact configured key %s", COMBINED_KEY)
                .isNotNull();
        assertThat(combined).isNotEmpty();

        final List<String> records = splitCombinedRecords(combined);
        assertThat(records).isNotEmpty();
        records.forEach(record -> assertThat(record.length()).isEqualTo(DAILY_TRAN_RECORD_LENGTH));

        final List<String> combinedIds = records.stream()
                .map(record -> record.substring(0, TRAN_ID_LENGTH))
                .filter(id -> id.startsWith(IT_ID_PREFIX))
                .toList();
        assertThat(combinedIds).isSorted();
        assertThat(combinedIds).containsAll(sortedIds);
    }

    // ------------------------------------------------------------------------------------------
    // Test 3 (Phase E): bulk-load merge — the IDCAMS REPRO replacement.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the IDCAMS REPRO replacement: every seeded id is loaded into the transaction master and
     * the load adds exactly N rows. The singleton database persists across the ordered tests (only
     * S3 is emptied per test), so this test first removes its exclusive {@code ZZIT} ids to make the
     * load delta deterministic and to make "newly loaded by {@code combineLoadStep}" literally true.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @Order(3)
    void bulkLoadMergesEachIdExactlyOnce() throws Exception {
        final List<String> ids = seedScrambledInputs();
        transactionRepository.deleteAllById(ids);
        final long preCount = transactionRepository.count();

        final JobExecution execution = launchCombine();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        for (final String id : ids) {
            assertThat(transactionRepository.findById(id)).as("merged tranId %s", id).isPresent();
        }
        assertThat(transactionRepository.count() - preCount).isEqualTo((long) ids.size());
    }

    // ------------------------------------------------------------------------------------------
    // Test 4 (Phase F): idempotency — re-running the combine never duplicates rows.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the combine can be safely re-run: the JPA {@code saveAll} merge keyed on {@code tranId}
     * upserts rather than duplicating, so a second run completes without a duplicate-key violation
     * and adds zero net new rows.
     *
     * @throws Exception if either job launch fails
     */
    @Test
    @Order(4)
    void rerunIsIdempotentWithNoDuplicateRows() throws Exception {
        seedScrambledInputs();
        launchCombine();
        final long afterFirstLoad = transactionRepository.count();

        // The base @BeforeEach only empties the buckets between tests, not within one, so re-seed
        // the identical inputs explicitly before the second run.
        seedScrambledInputs();
        final JobExecution second = launchCombine();

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(afterFirstLoad);
    }
}
