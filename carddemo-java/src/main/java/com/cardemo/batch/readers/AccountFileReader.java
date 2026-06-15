package com.cardemo.batch.readers;

import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

/**
 * Spring Batch {@link RepositoryItemReader} provider that reproduces the
 * sequential account-master scan of the legacy AWS CardDemo batch program
 * <strong>{@code CBACT01C}</strong> ("Read and print account data file") in the
 * greenfield Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x migration.
 *
 * <h2>Role &mdash; diagnostic / operational utility</h2>
 * <p>{@code CBACT01C} is an operational <em>verification</em> tool: it opens the
 * {@code ACCTDAT} account-master file, walks it from start to end, and prints
 * each record so an operator can eyeball the master data. In the migrated stack
 * that responsibility splits cleanly along Spring&nbsp;Batch lines &mdash; this
 * class supplies the <strong>reader</strong> half (streaming every persisted
 * {@link Account} to a paired diagnostic step), while the field-by-field display
 * (the COBOL {@code 1100-DISPLAY-ACCT-RECORD} paragraph) is the concern of the
 * paired diagnostic <em>writer</em>/step that lives in a sibling {@code batch}
 * package. This reader therefore performs no formatting and emits no output of
 * its own; it only produces typed {@link Account} items in key order.</p>
 *
 * <h2>Provenance &amp; Minimal Change Clause (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>Translated from COBOL {@code app/cbl/CBACT01C.cbl} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is <strong>read-only</strong>
 * reference material and is <strong>never copied</strong> into this repository;
 * traceability is by commit SHA only. Per the <strong>Minimal Change Clause</strong>
 * the observable behaviour is reproduced <em>exactly</em> &mdash; the full account
 * file is streamed once, in ascending primary-key order, with no filtering,
 * transformation, enrichment or feature addition &mdash; and every technology
 * substitution is documented at its point of use. The application base package is
 * {@code com.cardemo} (decision <strong>D-006</strong>, deliberately <em>not</em>
 * {@code com.carddemo}).</p>
 *
 * <h2>The COBOL behaviour this reader reproduces ({@code CBACT01C})</h2>
 * <p>The source declares its file with {@code ORGANIZATION IS INDEXED},
 * {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-ACCT-ID}
 * ({@code PIC 9(11)}). A sequential read of a VSAM KSDS through its record key
 * returns records in <strong>ascending key order</strong>; the procedure division
 * then drives three I/O paragraphs in a read-until-EOF loop:</p>
 * <ul>
 *   <li>{@code 0000-ACCTFILE-OPEN} &mdash; {@code OPEN INPUT ACCTFILE-FILE};</li>
 *   <li>{@code 1000-ACCTFILE-GET-NEXT} &mdash;
 *       {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} once per iteration; and</li>
 *   <li>{@code 9000-ACCTFILE-CLOSE} &mdash; {@code CLOSE ACCTFILE-FILE} at end.</li>
 * </ul>
 * <p>The {@code COPY CVACT01Y} record ({@code 01 ACCOUNT-RECORD}, the 300-byte
 * {@code ACCTDAT} layout) maps to the {@link Account} entity, so each item this
 * reader emits is one fully-typed {@code Account}.</p>
 *
 * <h2>Technology substitution (documented at the point of change)</h2>
 * <dl>
 *   <dt>VSAM KSDS indexed-sequential browse &rarr; {@link RepositoryItemReader}</dt>
 *   <dd>The COBOL {@code OPEN}/{@code READ&nbsp;NEXT}/{@code CLOSE} cycle over
 *       {@code ACCTDAT} becomes a paged repository scan. The reader invokes the
 *       inherited {@code AccountRepository.findAll(Pageable)} page by page (see
 *       {@code methodName = "findAll"}), exactly as the legacy program advanced
 *       through the cluster one record at a time. {@link AccountRepository} is the
 *       JPA replacement for the {@code ACCTDAT} cluster provisioned by
 *       {@code app/jcl/ACCTFILE.jcl}.</dd>
 *
 *   <dt>{@code RECORD KEY IS FD-ACCT-ID} ascending order &rarr; {@code sorts}</dt>
 *   <dd>Indexed-sequential access yields rows in ascending record-key order. That
 *       ordering is preserved exactly by sorting on the {@link Account} primary-key
 *       property {@code acctId} ascending ({@link #SORT_KEY_ACCT_ID} &rarr;
 *       {@link Sort.Direction#ASC}). The sort key is the JPA <em>property</em> name
 *       ({@code acctId}), not the physical column name ({@code account_id}).</dd>
 *
 *   <dt>{@code READ} {@code FILE STATUS '00'} (record available) &rarr; {@code read()}</dt>
 *   <dd>Each successful COBOL {@code READ} (status {@code '00'}) corresponds to one
 *       non-null {@link Account} returned from the reader's {@code read()}.</dd>
 *
 *   <dt>{@code FILE STATUS '10'} (end-of-file) &rarr; {@code read()} returns {@code null}</dt>
 *   <dd>The COBOL EOF status {@code '10'} (which sets {@code END-OF-FILE = 'Y'} and
 *       ends the loop) maps to the Spring&nbsp;Batch contract that {@code read()}
 *       returns {@code null} once every row has been exhausted, signalling the step
 *       to stop.</dd>
 *
 *   <dt>{@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} &rarr; propagated exception</dt>
 *   <dd>Any other status drove the COBOL program to display the I/O status and abend
 *       ({@code CALL 'CEE3ABD'}, code&nbsp;999). The equivalent here is a hard
 *       failure: an underlying data-access error (for example a lost connection)
 *       surfaces as an unchecked {@code org.springframework.dao.DataAccessException}
 *       that <strong>propagates</strong> out of {@code read()} and fails the step
 *       (AAP &sect;0.7.5). It is deliberately <strong>not</strong> swallowed,
 *       retried or wrapped here; the reader adds no custom read loop, so no
 *       {@code com.cardemo.exception} type is introduced at this layer (a typed
 *       wrapper, if ever wanted, belongs to the step/job orchestration, not to the
 *       reader factory).</dd>
 * </dl>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>The {@link Account} monetary fields ({@code current_balance},
 * {@code credit_limit}, {@code cash_credit_limit}, {@code current_cycle_credit},
 * {@code current_cycle_debit}) are already {@link java.math.BigDecimal} on the
 * entity. This reader surfaces {@code Account} instances verbatim and performs no
 * numeric conversion whatsoever; no {@code float} or {@code double} is introduced
 * anywhere in the read path.</p>
 *
 * <h2>Why a {@code @Bean @StepScope} factory</h2>
 * <p>The reader bean is declared {@link StepScope step-scoped} so a fresh instance
 * (with its own paging cursor and {@code ExecutionContext}-backed read count) is
 * created per step execution, enabling clean restartability and late binding of
 * any future step parameters. The {@code step} scope is supplied by Spring&nbsp;Boot's
 * batch auto-configuration ({@code SpringBootBatchConfiguration} extends
 * {@code DefaultBatchConfiguration}, which imports the scope registration), so it is
 * available <strong>without</strong> {@code @EnableBatchProcessing} &mdash;
 * consistent with {@code com.cardemo.config.BatchConfig}, which intentionally omits
 * that annotation to keep Boot's auto-configured {@code JobRepository}/
 * {@code JobLauncher} in force. This class declares only the reader bean; it owns
 * no {@code Job}/{@code Step} wiring (that is the {@code batch/jobs} layer's
 * concern) and imports no sibling {@code batch} package.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}; the COBOL/JCL sources are read-only reference material and are
 * never copied into this repository.</p>
 *
 * @see Account
 * @see AccountRepository
 * @see RepositoryItemReader
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Configuration
public class AccountFileReader {

    /**
     * Bean name and Spring&nbsp;Batch {@code ExecutionContext} key prefix for the
     * account-file reader. Distinct from the decapitalized configuration-class bean
     * name ({@code accountFileReader}) to avoid a definition collision; it matches
     * the factory method name {@link #accountFileItemReader(AccountRepository)} so
     * the bean can be referenced unambiguously by name from a step definition.
     */
    public static final String READER_NAME = "accountFileItemReader";

    /**
     * JPA fetch page size for the repository scan.
     *
     * <p>This governs how many {@link Account} rows the reader fetches per
     * {@code findAll(Pageable)} round-trip while streaming the full master file. It
     * is purely a read-efficiency knob and is independent of the step's commit/chunk
     * interval (that belongs to the step definition in the {@code batch/jobs} layer).
     * A page size of {@value} balances round-trips against heap for a sequential
     * diagnostic dump.</p>
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * JPA <em>property</em> name of the {@link Account} primary key used as the sort
     * key. This is the entity attribute {@code acctId} (mapped to the physical
     * column {@code account_id}); sorting on it ascending reproduces the COBOL
     * {@code RECORD KEY IS FD-ACCT-ID} indexed-sequential ordering.
     */
    static final String SORT_KEY_ACCT_ID = "acctId";

    /**
     * Provides the step-scoped {@link RepositoryItemReader} that streams every
     * {@link Account} in ascending {@code acctId} order, reproducing the
     * {@code CBACT01C} sequential account-master scan.
     *
     * <p>The reader is built over the supplied {@link AccountRepository} using its
     * inherited {@code findAll(Pageable)} operation ({@code methodName = "findAll"}),
     * sorted by {@link #SORT_KEY_ACCT_ID} ascending so records arrive in primary-key
     * order &mdash; the exact ordering a VSAM KSDS indexed-sequential read produces.
     * Reading proceeds page by page ({@link #DEFAULT_PAGE_SIZE} rows per page);
     * {@code read()} returns each {@code Account} in turn and {@code null} once the
     * file is exhausted (the COBOL {@code FILE STATUS '10'} end-of-file). A
     * {@link #READER_NAME name} is set so the reader can persist its read count to the
     * {@code ExecutionContext} for restartability.</p>
     *
     * <p>Underlying data-access failures are intentionally not caught here: they
     * propagate as unchecked {@code DataAccessException}s and fail the step,
     * mirroring the COBOL {@code 9999-ABEND-PROGRAM} hard-stop (AAP &sect;0.7.5).</p>
     *
     * @param accountRepository the JPA repository backing the {@code ACCTDAT}
     *                          account-master table; injected by type and must not be
     *                          {@code null}
     * @return a configured, step-scoped {@link RepositoryItemReader} over
     *         {@link Account}, ordered ascending by {@code acctId}
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Account> accountFileItemReader(final AccountRepository accountRepository) {
        return new RepositoryItemReaderBuilder<Account>()
                // Distinct, stable name -> ExecutionContext key prefix for restart state.
                .name(READER_NAME)
                // JPA replacement for the VSAM ACCTDAT cluster (CBACT01C's ACCTFILE).
                .repository(accountRepository)
                // Inherited PagingAndSortingRepository.findAll(Pageable): the paged
                // equivalent of the COBOL OPEN/READ-NEXT/CLOSE browse.
                .methodName("findAll")
                // RECORD KEY IS FD-ACCT-ID, ACCESS SEQUENTIAL -> ascending acctId order.
                .sorts(Map.of(SORT_KEY_ACCT_ID, Sort.Direction.ASC))
                // Rows fetched per round-trip while streaming the full master file.
                .pageSize(DEFAULT_PAGE_SIZE)
                .build();
    }
}
