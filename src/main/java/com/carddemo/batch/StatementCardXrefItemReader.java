package com.carddemo.batch;

import com.carddemo.entity.CardXref;
import com.carddemo.repository.CardXrefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Chunk-step {@link org.springframework.batch.item.ItemReader ItemReader} for the CardDemo
 * statement-generation stage &mdash; <strong>reader&nbsp;#4 of 5</strong> in the batch pipeline,
 * wired into {@code StatementJob} (JCL {@code CREASTMT}).
 *
 * <p><strong>COBOL lineage (REFERENCE-only, source commit SHA {@code 27d6c6f}).</strong> This
 * reader reproduces the <em>driving loop</em> of the legacy batch program {@code CBSTM03A.CBL}.
 * In the mainframe application the {@code 1000-MAINLINE} paragraph runs
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'}, and on every iteration calls
 * {@code 1000-XREFFILE-GET-NEXT} to read the card cross-reference file (DD {@code XREFFILE},
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} per {@code CREASTMT.JCL} STEP040
 * {@code EXEC PGM=CBSTM03A}) <strong>sequentially, one card at a time, in ascending
 * card-number order</strong>. For each card returned, {@code CBSTM03A} joins the customer,
 * account, card and its transactions and emits exactly one statement &mdash; hence
 * <strong>one item&nbsp;==&nbsp;one card&nbsp;==&nbsp;one statement</strong>.</p>
 *
 * <pre>
 *   CBSTM03A 1000-XREFFILE-GET-NEXT (excerpt, translated intent):
 *       SET M03B-READ TO TRUE
 *       CALL 'CBSTM03B' USING WS-M03B-AREA      &rarr; read next XREF record
 *       EVALUATE WS-M03B-RC
 *         WHEN '00'  CONTINUE                    &rarr; item produced
 *         WHEN '10'  MOVE 'Y' TO END-OF-FILE     &rarr; EOF: loop terminates (reader returns null)
 *         WHEN OTHER PERFORM 9999-ABEND-PROGRAM  &rarr; hard error (framework-raised exception)
 *       END-EVALUATE
 * </pre>
 *
 * <h2>Translation strategy</h2>
 * <p>The COBOL {@code PERFORM UNTIL END-OF-FILE} read loop is realized by extending
 * {@link RepositoryItemReader} over {@link CardXref} (AAP &sect;0.4.3: <em>chunk-oriented batch
 * processing replaces {@code PERFORM UNTIL EOF} read loops</em>). The reader is configured to
 * page through {@link CardXrefRepository#findAll(org.springframework.data.domain.Pageable)}
 * ordered ascending by the {@code xrefCardNum} property (COBOL {@code XREF-CARD-NUM PIC X(16)}),
 * which makes iteration <em>sequential, deterministic and restartable</em> &mdash; the same
 * guarantees the legacy VSAM KSDS keyed read provided. Each call to
 * {@link RepositoryItemReader#read()} yields the next {@link CardXref} row and, on exhaustion,
 * returns {@code null}. That {@code null} is the idiomatic Spring Batch end-of-stream signal and
 * corresponds precisely to the COBOL {@code FILE STATUS '10'} branch that sets
 * {@code END-OF-FILE = 'Y'}; <strong>EOF is never surfaced as an exception</strong> (AAP
 * &sect;0.8.3: the EOF code {@code '10'} is normal loop termination, not an error).</p>
 *
 * <h2>Separation of concerns &mdash; this reader is I/O source only</h2>
 * <p>This component is deliberately limited to being the pipeline's <em>input source</em>. It
 * performs no business logic and holds no service collaborators. The heavy per-card assembly
 * that {@code CBSTM03A} performs after each successful read &mdash; joining
 * {@code CUSTFILE}/{@code ACCTFILE}/{@code TRNXFILE} and formatting the text and HTML statements
 * &mdash; is delegated <em>downstream</em> to {@code StatementProcessor}, which constructor-injects
 * {@code com.carddemo.service.StatementFileService}. Per the constructor-injection mandate (AAP
 * &sect;0.4.3), the legacy {@code CALL 'CBSTM03B'} file-access subprogram becomes that injected
 * {@code StatementFileService} bean, and that injection happens in {@code StatementProcessor},
 * <strong>not here</strong>. {@code StatementFileService} is intentionally not referenced by this
 * class.</p>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><strong>Repository</strong>: {@link CardXrefRepository} (constructor-injected), a
 *       {@link org.springframework.data.repository.PagingAndSortingRepository
 *       PagingAndSortingRepository} via its {@code JpaRepository} super-interface.</li>
 *   <li><strong>Method</strong>: {@value #REPOSITORY_METHOD_FIND_ALL} &mdash; the reader invokes
 *       {@code findAll(Pageable)} to page through the cross-reference table.</li>
 *   <li><strong>Sort</strong>: {@value #SORT_PROPERTY_CARD_NUMBER}
 *       {@link Sort.Direction#ASC ASC} &mdash; the entity {@code @Id} (String, length 16),
 *       reproducing the ascending card-number order of the legacy KSDS.</li>
 *   <li><strong>Page size</strong>: {@code carddemo.batch.chunk-size} (default {@code 100}),
 *       controlling the fetch page size for restart-safe, memory-bounded paging.</li>
 *   <li><strong>Name</strong>: {@value #READER_NAME} &mdash; the key under which read progress is
 *       saved to the Spring Batch {@code ExecutionContext} for restartability.</li>
 * </ul>
 *
 * <h2>Design and quality notes</h2>
 * <ul>
 *   <li>Declared {@code final}: this is a plain {@link Component} (no {@code @Transactional},
 *       {@code @Async}, {@code @Scheduled} or other proxied advice), so it never needs a CGLIB
 *       subclass proxy. Making it {@code final} lets the constructor safely invoke the inherited
 *       configuration setters without triggering the {@code -Xlint:this-escape}
 *       warning, keeping the build warning-free (AAP Gate&nbsp;2 &mdash; zero-warning under
 *       {@code -Xlint:all}).</li>
 *   <li>Constructor injection only; no field/setter injection of collaborators.</li>
 *   <li>No {@code float}/{@code double} anywhere &mdash; this reader carries no monetary values
 *       (AAP &sect;0.8.2); financial fields on downstream statement DTOs use
 *       {@link java.math.BigDecimal}.</li>
 *   <li>Configuration validity (non-null repository/sort/method, {@code pageSize > 0}) is enforced
 *       by the inherited {@link RepositoryItemReader#afterPropertiesSet()}, which Spring invokes
 *       automatically after construction because {@link RepositoryItemReader} implements
 *       {@link org.springframework.beans.factory.InitializingBean InitializingBean}. A null
 *       repository is additionally rejected eagerly in the constructor for a clearer failure
 *       message.</li>
 * </ul>
 *
 * @see CardXref
 * @see CardXrefRepository
 * @see RepositoryItemReader
 */
@Component
public final class StatementCardXrefItemReader extends RepositoryItemReader<CardXref> {

    /** SLF4J logger for one-time, DEBUG-level configuration diagnostics of this reader. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementCardXrefItemReader.class);

    /**
     * Name of the {@link CardXref} property used as the ascending sort key.
     *
     * <p>Maps to the COBOL {@code XREF-CARD-NUM PIC X(16)} field and is the entity's
     * {@code @Id}; sorting on it reproduces the sequential, ascending card-number iteration of the
     * legacy {@code CARDXREF} VSAM KSDS.</p>
     */
    private static final String SORT_PROPERTY_CARD_NUMBER = "xrefCardNum";

    /**
     * The {@link CardXrefRepository} method the reader pages through.
     *
     * <p>{@code findAll} is inherited from
     * {@link org.springframework.data.repository.PagingAndSortingRepository} and is invoked by
     * {@link RepositoryItemReader} with a {@code Pageable} argument built from the configured sort
     * and page size.</p>
     */
    private static final String REPOSITORY_METHOD_FIND_ALL = "findAll";

    /**
     * Stable reader name registered with the Spring Batch {@code ExecutionContext}.
     *
     * <p>Used as the state-key prefix under which the current read count is persisted, enabling
     * a restarted {@code StatementJob} to resume where it left off.</p>
     */
    private static final String READER_NAME = "statementCardXrefItemReader";

    /**
     * Builds and fully configures the statement card cross-reference reader.
     *
     * <p>All configuration is performed here, in the constructor, per the AAP implementation
     * checklist: the injected {@link CardXrefRepository} is set as the paging source, the
     * {@code findAll(Pageable)} method is selected, an ascending sort on
     * {@link #SORT_PROPERTY_CARD_NUMBER} is applied (reproducing the legacy ascending KSDS read),
     * the fetch page size is taken from {@code carddemo.batch.chunk-size} (default {@code 100}),
     * and the reader is named {@value #READER_NAME} for restartable execution-context bookkeeping.
     * Property validation (including {@code pageSize > 0}) is subsequently performed by the
     * inherited {@link RepositoryItemReader#afterPropertiesSet()} that Spring invokes after
     * construction.</p>
     *
     * @param cardXrefRepository the Spring Data repository over the {@code card_xref} table; the
     *                           paging source for the reader. Must not be {@code null}.
     * @param chunkSize          the page size used when fetching cross-reference rows, bound from
     *                           the {@code carddemo.batch.chunk-size} property and defaulting to
     *                           {@code 100} when the property is absent. Must be greater than zero
     *                           (validated by {@link RepositoryItemReader#afterPropertiesSet()}).
     * @throws NullPointerException if {@code cardXrefRepository} is {@code null}
     */
    public StatementCardXrefItemReader(
            CardXrefRepository cardXrefRepository,
            @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        super();
        Objects.requireNonNull(cardXrefRepository, "cardXrefRepository must not be null");

        setRepository(cardXrefRepository);
        setMethodName(REPOSITORY_METHOD_FIND_ALL);
        setSort(Map.of(SORT_PROPERTY_CARD_NUMBER, Sort.Direction.ASC));
        setPageSize(chunkSize);
        setName(READER_NAME);

        LOGGER.debug(
                "Initialized '{}' as RepositoryItemReader<CardXref> [method={}, sort={} ASC, "
                        + "pageSize={}]; reproduces CBSTM03A 1000-XREFFILE-GET-NEXT "
                        + "(one card == one statement)",
                READER_NAME, REPOSITORY_METHOD_FIND_ALL, SORT_PROPERTY_CARD_NUMBER, chunkSize);
    }
}
