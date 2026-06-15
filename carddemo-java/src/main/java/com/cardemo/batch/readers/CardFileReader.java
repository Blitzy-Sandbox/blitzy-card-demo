package com.cardemo.batch.readers;

import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

/**
 * Spring Batch {@link RepositoryItemReader} provider that reproduces the
 * sequential card-master scan of the legacy AWS CardDemo batch program
 * <strong>{@code CBACT02C}</strong> ("Read and print card data file") in the
 * greenfield Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x migration.
 *
 * <h2>Role &mdash; diagnostic / operational utility</h2>
 * <p>{@code CBACT02C} is an operational <em>verification</em> tool: it opens the
 * {@code CARDDAT} card-master file, walks it from start to end, and prints each
 * record so an operator can eyeball the master data. In the migrated stack that
 * responsibility splits cleanly along Spring&nbsp;Batch lines &mdash; this class
 * supplies the <strong>reader</strong> half (streaming every persisted
 * {@link Card} to a paired diagnostic step), while the record display (the COBOL
 * {@code DISPLAY CARD-RECORD} statement driven by the program's {@code MAIN}
 * loop) is the concern of the paired diagnostic <em>writer</em>/step that lives
 * in a sibling {@code batch} package. This reader therefore performs no
 * formatting and emits no output of its own; it only produces typed {@link Card}
 * items in key order.</p>
 *
 * <p>Note that in {@code CBACT02C} the in-loop {@code DISPLAY CARD-RECORD} inside
 * {@code 1000-CARDFILE-GET-NEXT} is <em>commented out</em>; the display is
 * performed by the {@code PERFORM UNTIL END-OF-FILE} loop in the procedure
 * division ({@code MAIN}). Confirming that the display is the job's diagnostic
 * purpose and explicitly <strong>not</strong> a reader concern: the reader's only
 * obligation is to faithfully reproduce the read cursor (record availability,
 * ordering and end-of-file), not the printing.</p>
 *
 * <h2>Provenance &amp; Minimal Change Clause (AAP &sect;0.7.1 / &sect;0.7.2)</h2>
 * <p>Translated from COBOL {@code app/cbl/CBACT02C.cbl} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is <strong>read-only</strong>
 * reference material and is <strong>never copied</strong> into this repository;
 * traceability is by commit SHA only. Per the <strong>Minimal Change Clause</strong>
 * the observable behaviour is reproduced <em>exactly</em> &mdash; the full card
 * file is streamed once, in ascending primary-key order, with no filtering,
 * transformation, enrichment or feature addition &mdash; and every technology
 * substitution is documented at its point of use. The application base package is
 * {@code com.cardemo} (decision <strong>D-006</strong>, deliberately <em>not</em>
 * {@code com.carddemo}).</p>
 *
 * <h2>The COBOL behaviour this reader reproduces ({@code CBACT02C})</h2>
 * <p>The source declares its file with {@code ORGANIZATION IS INDEXED},
 * {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-CARD-NUM}
 * ({@code PIC X(16)} &mdash; the 16-digit Primary Account Number). A sequential
 * read of a VSAM KSDS through its record key returns records in
 * <strong>ascending key order</strong>; the procedure division then drives three
 * I/O paragraphs in a read-until-EOF loop:</p>
 * <ul>
 *   <li>{@code 0000-CARDFILE-OPEN} &mdash; {@code OPEN INPUT CARDFILE-FILE};</li>
 *   <li>{@code 1000-CARDFILE-GET-NEXT} &mdash;
 *       {@code READ CARDFILE-FILE INTO CARD-RECORD} once per iteration; and</li>
 *   <li>{@code 9000-CARDFILE-CLOSE} &mdash; {@code CLOSE CARDFILE-FILE} at end.</li>
 * </ul>
 * <p>The fixed record {@code FD-CARDFILE-REC} is 150&nbsp;bytes
 * ({@code FD-CARD-NUM PIC X(16)} + {@code FD-CARD-DATA PIC X(134)}); the
 * {@code COPY CVACT02Y} record ({@code 01 CARD-RECORD}, the 150-byte
 * {@code CARDDAT} layout) maps to the {@link Card} entity, so each item this
 * reader emits is one fully-typed {@code Card}.</p>
 *
 * <h2>Technology substitution (documented at the point of change)</h2>
 * <dl>
 *   <dt>VSAM KSDS indexed-sequential browse &rarr; {@link RepositoryItemReader}</dt>
 *   <dd>The COBOL {@code OPEN}/{@code READ&nbsp;NEXT}/{@code CLOSE} cycle over
 *       {@code CARDDAT} becomes a paged repository scan. The reader invokes the
 *       inherited {@code CardRepository.findAll(Pageable)} page by page (see
 *       {@code methodName = "findAll"}), exactly as the legacy program advanced
 *       through the cluster one record at a time. {@link CardRepository} is the
 *       JPA replacement for the {@code CARDDAT} cluster provisioned by
 *       {@code app/jcl/CARDFILE.jcl}. The {@code CARDAIX} alternate index
 *       ({@code findByCardAcctId}) is irrelevant to this full-file dump, which uses
 *       only the inherited primary-key-ordered {@code findAll}.</dd>
 *
 *   <dt>{@code RECORD KEY IS FD-CARD-NUM} ascending order &rarr; {@code sorts}</dt>
 *   <dd>Indexed-sequential access yields rows in ascending record-key order. That
 *       ordering is preserved exactly by sorting on the {@link Card} primary-key
 *       property {@code cardNum} ascending ({@link #SORT_KEY_CARD_NUM} &rarr;
 *       {@link Sort.Direction#ASC}). The sort key is the JPA <em>property</em> name
 *       ({@code cardNum}), not the physical column name ({@code card_number}).</dd>
 *
 *   <dt>{@code READ} {@code FILE STATUS '00'} (record available) &rarr; {@code read()}</dt>
 *   <dd>Each successful COBOL {@code READ} (status {@code '00'}) corresponds to one
 *       non-null {@link Card} returned from the reader's {@code read()}.</dd>
 *
 *   <dt>{@code FILE STATUS '10'} (end-of-file) &rarr; {@code read()} returns {@code null}</dt>
 *   <dd>The COBOL EOF status {@code '10'} (which sets {@code END-OF-FILE = 'Y'} and
 *       ends the {@code PERFORM UNTIL} loop) maps to the Spring&nbsp;Batch contract
 *       that {@code read()} returns {@code null} once every row has been exhausted,
 *       signalling the step to stop.</dd>
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
 * <p>The {@link Card} entity carries <strong>no</strong> COBOL {@code COMP-3}/
 * {@code COMP} or {@code PIC&nbsp;...V99} decimal fields (its columns are a
 * {@link String} card number, a {@link Long} account id, an {@link Integer} CVV, a
 * {@link String} embossed name, a {@link java.time.LocalDate} expiration date, a
 * single-character {@link String} status flag and a {@link Long} optimistic-lock
 * version), so there is no monetary precision to preserve at this layer. This
 * reader surfaces {@code Card} instances verbatim and performs no numeric
 * conversion whatsoever; consistent with the zero-floating-point rule, no
 * {@code float} or {@code double} is introduced anywhere in the read path.</p>
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
 * @see Card
 * @see CardRepository
 * @see RepositoryItemReader
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Configuration
public class CardFileReader {

    /**
     * Bean name and Spring&nbsp;Batch {@code ExecutionContext} key prefix for the
     * card-file reader. Distinct from the decapitalized configuration-class bean
     * name ({@code cardFileReader}) to avoid a definition collision; it matches the
     * factory method name {@link #cardFileItemReader(CardRepository)} so the bean
     * can be referenced unambiguously by name from a step definition.
     */
    public static final String READER_NAME = "cardFileItemReader";

    /**
     * JPA fetch page size for the repository scan.
     *
     * <p>This governs how many {@link Card} rows the reader fetches per
     * {@code findAll(Pageable)} round-trip while streaming the full master file. It
     * is purely a read-efficiency knob and is independent of the step's commit/chunk
     * interval (that belongs to the step definition in the {@code batch/jobs} layer).
     * A page size of {@value} balances round-trips against heap for a sequential
     * diagnostic dump.</p>
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * JPA <em>property</em> name of the {@link Card} primary key used as the sort
     * key. This is the entity attribute {@code cardNum} (mapped to the physical
     * column {@code card_number}); sorting on it ascending reproduces the COBOL
     * {@code RECORD KEY IS FD-CARD-NUM} indexed-sequential ordering.
     */
    static final String SORT_KEY_CARD_NUM = "cardNum";

    /**
     * Provides the step-scoped {@link RepositoryItemReader} that streams every
     * {@link Card} in ascending {@code cardNum} order, reproducing the
     * {@code CBACT02C} sequential card-master scan.
     *
     * <p>The reader is built over the supplied {@link CardRepository} using its
     * inherited {@code findAll(Pageable)} operation ({@code methodName = "findAll"}),
     * sorted by {@link #SORT_KEY_CARD_NUM} ascending so records arrive in primary-key
     * order &mdash; the exact ordering a VSAM KSDS indexed-sequential read produces.
     * Reading proceeds page by page ({@link #DEFAULT_PAGE_SIZE} rows per page);
     * {@code read()} returns each {@code Card} in turn and {@code null} once the file
     * is exhausted (the COBOL {@code FILE STATUS '10'} end-of-file). A
     * {@link #READER_NAME name} is set so the reader can persist its read count to the
     * {@code ExecutionContext} for restartability.</p>
     *
     * <p>Underlying data-access failures are intentionally not caught here: they
     * propagate as unchecked {@code DataAccessException}s and fail the step,
     * mirroring the COBOL {@code 9999-ABEND-PROGRAM} hard-stop (AAP &sect;0.7.5).</p>
     *
     * @param cardRepository the JPA repository backing the {@code CARDDAT}
     *                       card-master table; injected by type and must not be
     *                       {@code null}
     * @return a configured, step-scoped {@link RepositoryItemReader} over
     *         {@link Card}, ordered ascending by {@code cardNum}
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Card> cardFileItemReader(final CardRepository cardRepository) {
        return new RepositoryItemReaderBuilder<Card>()
                // Distinct, stable name -> ExecutionContext key prefix for restart state.
                .name(READER_NAME)
                // JPA replacement for the VSAM CARDDAT cluster (CBACT02C's CARDFILE).
                .repository(cardRepository)
                // Inherited PagingAndSortingRepository.findAll(Pageable): the paged
                // equivalent of the COBOL OPEN/READ-NEXT/CLOSE browse.
                .methodName("findAll")
                // RECORD KEY IS FD-CARD-NUM, ACCESS SEQUENTIAL -> ascending cardNum order.
                .sorts(Map.of(SORT_KEY_CARD_NUM, Sort.Direction.ASC))
                // Rows fetched per round-trip while streaming the full master file.
                .pageSize(DEFAULT_PAGE_SIZE)
                .build();
    }
}
