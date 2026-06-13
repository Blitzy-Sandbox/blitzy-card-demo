package com.cardemo.batch.readers;

import com.cardemo.config.AwsConfig;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Sort;

/**
 * Spring Batch {@code ItemReader} provider that reproduces the sequential
 * daily-transaction read cycle of the legacy AWS CardDemo batch program
 * <strong>{@code CBTRN01C}</strong> ("Post the records from daily transaction
 * file") in the greenfield Java&nbsp;25 LTS + Spring&nbsp;Boot&nbsp;3.5.x
 * migration. It streams the daily, pre-posting transaction feed into the
 * <em>Daily Transaction Posting</em> job ({@code POSTTRAN}), whose processor
 * ({@code com.cardemo.batch.processors.TransactionPostingProcessor}) and writers
 * live in sibling {@code batch} packages.
 *
 * <h2>Scope &mdash; ONLY the daily-transaction read cycle</h2>
 * <p>{@code CBTRN01C} is the daily-transaction driver. This reader reproduces
 * <strong>only</strong> its sequential read cycle &mdash; the
 * {@code 1000-DALYTRAN-GET-NEXT} paragraph that walks the {@code DALYTRAN} file
 * front-to-back. The cross-reference and account look-ups in the program's
 * {@code MAIN-PARA} ({@code 2000-LOOKUP-XREF}, {@code 3000-READ-ACCOUNT}) are
 * <strong>downstream validation/processing</strong> and are deliberately
 * <strong>not</strong> performed here; they are owned by the posting job's
 * {@code ItemProcessor}. This class produces typed {@link DailyTransaction} items
 * and nothing else.</p>
 *
 * <h2>The COBOL behaviour this reader reproduces ({@code CBTRN01C})</h2>
 * <p>The source declares its input with {@code SELECT DALYTRAN-FILE ASSIGN TO
 * DALYTRAN ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS
 * DALYTRAN-STATUS}. {@code DALYTRAN} is a flat <strong>physical-sequential (PS)</strong>
 * dataset &mdash; <em>not</em> a VSAM/indexed cluster &mdash; so records are
 * returned in <strong>physical (insertion) order</strong>, never keyed or sorted.
 * The {@code FD-TRAN-RECORD} is a fixed <strong>350-byte</strong> record
 * ({@code COPY CVTRA06Y}, {@code 01 DALYTRAN-RECORD}). The procedure division
 * drives three I/O paragraphs in a read-until-EOF loop:</p>
 * <ul>
 *   <li>{@code 0000-DALYTRAN-OPEN} &mdash; {@code OPEN INPUT DALYTRAN-FILE}; file
 *       status {@code '00'} means OK, anything else displays the status and abends.</li>
 *   <li>{@code 1000-DALYTRAN-GET-NEXT} &mdash;
 *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} once per iteration.</li>
 *   <li>{@code 9000-DALYTRAN-CLOSE} &mdash; {@code CLOSE DALYTRAN-FILE} at end.</li>
 * </ul>
 *
 * <h2>Technology substitution (Minimal Change Clause &mdash; AAP &sect;0.7.1,
 * documented at the point of change)</h2>
 * <dl>
 *   <dt>Sequential PS staging dataset / GDG generations &rarr; S3-staged
 *       {@link FlatFileItemReader} (decision <strong>D-003</strong>, AAP
 *       transformation rules GDG&rarr;S3 and "CICS/VSAM/PS sequential read &rarr;
 *       Spring Batch reader")</dt>
 *   <dd>The {@code DALYTRAN} PS feed is staged as a single object in the S3
 *       <em>batch-input</em> bucket ({@code carddemo-batch-input}). The COBOL
 *       {@code OPEN}/{@code READ&nbsp;NEXT}/{@code CLOSE} browse becomes a
 *       {@link FlatFileItemReader} over that S3 {@link Resource}, resolved through
 *       the Spring Cloud AWS {@code s3://} protocol resolver. The fixed 350-byte
 *       layout is parsed by a {@link FixedLengthTokenizer}; physical order is
 *       preserved because the file is read front-to-back with no sort.</dd>
 *
 *   <dt>{@code READ} {@code FILE STATUS '00'} (record available) &rarr; {@code read()} item</dt>
 *   <dd>Each successful COBOL {@code READ} (status {@code '00'}, {@code APPL-RESULT 0})
 *       corresponds to one non-null {@link DailyTransaction} returned from
 *       {@code read()}.</dd>
 *
 *   <dt>{@code FILE STATUS '10'} (end-of-file) &rarr; {@code read()} returns {@code null}</dt>
 *   <dd>The COBOL EOF status {@code '10'} (which sets
 *       {@code END-OF-DAILY-TRANS-FILE = 'Y'} and ends the loop) maps to the
 *       Spring&nbsp;Batch contract that {@code read()} returns {@code null} once the
 *       input is exhausted, signalling the step to stop.</dd>
 *
 *   <dt>{@code Z-DISPLAY-IO-STATUS} + {@code Z-ABEND-PROGRAM} ({@code CALL 'CEE3ABD'},
 *       code&nbsp;999) &rarr; propagated exception (AAP &sect;0.7.5)</dt>
 *   <dd>Any other file status drove the COBOL program to display the I/O status and
 *       abend. The equivalent here is a hard failure: a malformed record or an S3/IO
 *       error surfaces as a {@code FlatFileParseException} / I/O exception that
 *       <strong>propagates</strong> out of {@code read()} and fails the step. Records
 *       are never silently skipped.</dd>
 * </dl>
 *
 * <h2>Record layout &mdash; {@code CVTRA06Y} (RECLN 350; verified against
 * {@code app/data/ASCII/dailytran.txt})</h2>
 * <p>The fixed 350-byte record maps onto the {@link DailyTransaction} entity by the
 * 1-indexed byte ranges below (the trailing 20-byte {@code FILLER} carries no
 * business data and is not mapped):</p>
 * <pre>{@code
 *   1- 16  DALYTRAN-ID            X(16)        -> dalytranId            String  (trimmed)
 *  17- 18  DALYTRAN-TYPE-CD       X(02)        -> dalytranTypeCd        String
 *  19- 22  DALYTRAN-CAT-CD        9(04)        -> dalytranCatCd         Integer
 *  23- 32  DALYTRAN-SOURCE        X(10)        -> dalytranSource        String  (trimmed)
 *  33-132  DALYTRAN-DESC          X(100)       -> dalytranDesc          String  (trimmed)
 * 133-143  DALYTRAN-AMT           S9(09)V99    -> dalytranAmt           BigDecimal scale 2 (overpunch)
 * 144-152  DALYTRAN-MERCHANT-ID   9(09)        -> dalytranMerchantId    Long
 * 153-202  DALYTRAN-MERCHANT-NAME X(50)        -> dalytranMerchantName  String  (trimmed)
 * 203-252  DALYTRAN-MERCHANT-CITY X(50)        -> dalytranMerchantCity  String  (trimmed)
 * 253-262  DALYTRAN-MERCHANT-ZIP  X(10)        -> dalytranMerchantZip   String  (trimmed)
 * 263-278  DALYTRAN-CARD-NUM      X(16)        -> dalytranCardNum       String
 * 279-304  DALYTRAN-ORIG-TS       X(26)        -> dalytranOrigTs        LocalDateTime
 * 305-330  DALYTRAN-PROC-TS       X(26)        -> dalytranProcTs        LocalDateTime (blank -> null)
 * 331-350  FILLER                 X(20)        -> (ignored)
 * }</pre>
 *
 * <h2>Decimal fidelity for {@code DALYTRAN-AMT} (AAP &sect;0.7.3)</h2>
 * <p>{@code DALYTRAN-AMT PIC S9(09)V99} is an 11-character zoned-decimal field
 * whose trailing byte carries an <strong>overpunch sign</strong>, with an implied
 * decimal point before the last two digits. It is decoded to a {@link BigDecimal}
 * with <strong>scale exactly 2</strong> by {@link #parseSignedOverpunch(String)};
 * <strong>no {@code float} or {@code double} is used anywhere</strong>. The value is
 * neither rounded nor altered. Compare amounts with
 * {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}.</p>
 *
 * <h2>Timestamp fidelity</h2>
 * <p>The two {@code PIC X(26)} timestamp fields hold text in the form
 * {@code yyyy-MM-dd HH:mm:ss.SSSSSS} and map to {@link LocalDateTime} (matching the
 * entity's {@code TIMESTAMP} columns). A pre-posting daily row has not yet been
 * processed, so {@code DALYTRAN-PROC-TS} is blank in the feed; a blank timestamp is
 * mapped to {@code null} (see {@link #parseTimestamp(String)}).</p>
 *
 * <h2>Primary design vs. documented alternative</h2>
 * <p>Two interchangeable, mutually-exclusive reader beans are provided and selected
 * by the {@code carddemo.batch.daily-transaction.reader} property:</p>
 * <ul>
 *   <li><strong>Default ({@code s3}, also when the property is absent)</strong> &mdash;
 *       {@link #dailyTransactionItemReader(String)} reads the raw {@code DALYTRAN}
 *       object staged in S3 (the folder spec's "S3 file reader" and the governing
 *       GDG/PS&rarr;S3 rule). This is the primary design.</li>
 *   <li><strong>Alternative ({@code repository})</strong> &mdash;
 *       {@link #dailyTransactionRepositoryItemReader(DailyTransactionRepository)}
 *       streams the {@code daily_transactions} table (seeded by Flyway {@code V3}
 *       from {@code dailytran.txt}) in ascending {@code dalytranId} order. Provided
 *       for the case where the {@code DailyTransactionPostingJob} wiring chooses the
 *       DB-staging path instead of raw-S3-file staging.</li>
 * </ul>
 * <p>In both paths each item is a fully-typed {@link DailyTransaction} and
 * {@code dalytranAmt} stays a {@link BigDecimal} (the repository path delegates
 * decoding to JPA / the {@code V3} seed). This class declares only reader beans; it
 * owns no {@code Job}/{@code Step} wiring (the {@code batch/jobs} layer's concern),
 * relies on Spring&nbsp;Boot batch auto-configuration for the {@code step} scope
 * (so {@code @EnableBatchProcessing} is intentionally absent here and on
 * {@code com.cardemo.config.BatchConfig}), reads its S3 bucket name from
 * {@link AwsConfig.AwsResourceProperties}, and imports no sibling {@code batch}
 * package.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}; the COBOL/JCL sources are read-only reference material and are
 * never copied into this repository.</p>
 *
 * @see DailyTransaction
 * @see DailyTransactionRepository
 * @see AwsConfig.AwsResourceProperties
 * @see FlatFileItemReader
 * @see RepositoryItemReader
 */
@Configuration
public class DailyTransactionReader {

    /**
     * Configuration property that selects which reader bean is active. Value
     * {@code s3} (default, also when unset) activates the S3-staged
     * {@link FlatFileItemReader}; value {@code repository} activates the
     * {@link RepositoryItemReader} alternative. The two beans are mutually exclusive,
     * so exactly one reader of type {@code ItemReader<DailyTransaction>} exists in the
     * context regardless of the selected value.
     */
    public static final String READER_SELECTION_PROPERTY = "carddemo.batch.daily-transaction.reader";

    /**
     * Bean name of the primary S3-staged reader and the Spring&nbsp;Batch
     * {@code ExecutionContext} key prefix it uses for restart state. Matches the
     * factory method name {@link #dailyTransactionItemReader(String)} so a step can
     * reference the bean unambiguously by name.
     */
    public static final String S3_READER_NAME = "dailyTransactionItemReader";

    /**
     * Bean name of the alternative repository-backed reader and its
     * {@code ExecutionContext} key prefix. Matches the factory method name
     * {@link #dailyTransactionRepositoryItemReader(DailyTransactionRepository)}.
     */
    public static final String REPOSITORY_READER_NAME = "dailyTransactionRepositoryItemReader";

    /**
     * Job-parameter name that supplies the S3 object key of the staged
     * {@code DALYTRAN} feed. When absent, {@link #DEFAULT_OBJECT_KEY} is used. Late-bound
     * per step execution via the step scope.
     */
    public static final String OBJECT_KEY_PARAMETER = "dalytranObjectKey";

    /**
     * Default S3 object key for the staged daily-transaction feed, used when the
     * {@link #OBJECT_KEY_PARAMETER} job parameter is not supplied. Mirrors the
     * canonical ASCII fixture name {@code dailytran.txt} that seeds the
     * {@code daily_transactions} table via Flyway {@code V3}.
     */
    public static final String DEFAULT_OBJECT_KEY = "dailytran.txt";

    /**
     * JPA fetch page size for the repository-backed alternative reader: the number of
     * {@link DailyTransaction} rows fetched per {@code findAll(Pageable)} round-trip
     * while streaming the staging table. A read-efficiency knob only, independent of
     * the step's commit/chunk interval. Matches the sibling readers' default.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * JPA <em>property</em> name of the {@link DailyTransaction} primary key used as
     * the sort key for the repository-backed alternative. This is the entity attribute
     * {@code dalytranId} (mapped to the physical column {@code transaction_id}); sorting
     * on it ascending yields a deterministic, restartable stream.
     */
    static final String SORT_KEY_DALYTRAN_ID = "dalytranId";

    /** Total fixed record length of the {@code DALYTRAN} feed ({@code CVTRA06Y}, RECLN 350). */
    static final int RECORD_LENGTH = 350;

    /** Length of the {@code DALYTRAN-AMT S9(09)V99} zoned-decimal field (10 digits + 1 sign/overpunch byte). */
    static final int AMOUNT_FIELD_LENGTH = 11;

    /** Implied decimal places of {@code DALYTRAN-AMT S9(09)V99} (the {@code V99} fraction). */
    static final int IMPLIED_DECIMAL_PLACES = 2;

    /**
     * Tokenizer field name for the trailing {@code FILLER X(20)} (bytes 331-350). The
     * filler is tokenized only so the {@link FixedLengthTokenizer} accounts for the full
     * {@value #RECORD_LENGTH}-byte record; it carries no business data and is
     * deliberately never read by {@link DailyTransactionFieldSetMapper}.
     */
    static final String FILLER_FIELD_NAME = "filler";

    /**
     * Tokenizer field names in physical byte order: the thirteen mapped
     * {@link DailyTransaction} fields followed by {@link #FILLER_FIELD_NAME}. Paired
     * positionally with {@link #COLUMN_RANGES} to drive the {@link FixedLengthTokenizer};
     * the resulting {@link FieldSet} is read by name in
     * {@link DailyTransactionFieldSetMapper}, which maps the thirteen business fields and
     * ignores the filler.
     */
    private static final String[] FIELD_NAMES = {
        "dalytranId",
        "dalytranTypeCd",
        "dalytranCatCd",
        "dalytranSource",
        "dalytranDesc",
        "dalytranAmt",
        "dalytranMerchantId",
        "dalytranMerchantName",
        "dalytranMerchantCity",
        "dalytranMerchantZip",
        "dalytranCardNum",
        "dalytranOrigTs",
        "dalytranProcTs",
        FILLER_FIELD_NAME
    };

    /**
     * Fixed-length column {@link Range ranges} (1-indexed, inclusive) for the
     * {@value #RECORD_LENGTH}-byte {@code DALYTRAN} record, verified against
     * {@code app/data/ASCII/dailytran.txt}. The thirteen mapped fields are followed by
     * the trailing {@code FILLER} (331-{@value #RECORD_LENGTH}); the filler range is
     * declared so the strict tokenizer accounts for the full fixed record length and
     * rejects any record that overruns it (the COBOL fixed 350-byte contract). The
     * filler token itself is not mapped to the entity.
     */
    private static final Range[] COLUMN_RANGES = {
        new Range(1, 16),     // DALYTRAN-ID            X(16)
        new Range(17, 18),    // DALYTRAN-TYPE-CD       X(02)
        new Range(19, 22),    // DALYTRAN-CAT-CD        9(04)
        new Range(23, 32),    // DALYTRAN-SOURCE        X(10)
        new Range(33, 132),   // DALYTRAN-DESC          X(100)
        new Range(133, 143),  // DALYTRAN-AMT           S9(09)V99 (11 bytes)
        new Range(144, 152),  // DALYTRAN-MERCHANT-ID   9(09)
        new Range(153, 202),  // DALYTRAN-MERCHANT-NAME X(50)
        new Range(203, 252),  // DALYTRAN-MERCHANT-CITY X(50)
        new Range(253, 262),  // DALYTRAN-MERCHANT-ZIP  X(10)
        new Range(263, 278),  // DALYTRAN-CARD-NUM      X(16)
        new Range(279, 304),  // DALYTRAN-ORIG-TS       X(26)
        new Range(305, 330),  // DALYTRAN-PROC-TS       X(26)
        new Range(331, RECORD_LENGTH)  // FILLER         X(20) -> enforces the 350-byte record length
    };

    /**
     * Parser/formatter for the 26-character {@code DALYTRAN-ORIG-TS} /
     * {@code DALYTRAN-PROC-TS} text fields, of the form
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. The fractional second is optional (0-9
     * digits) so values written with or without a fraction both parse.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    /**
     * Binder for the application-owned AWS resource names; used to resolve the
     * S3 batch-input bucket that stages the {@code DALYTRAN} feed.
     */
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Spring {@link ResourceLoader} (the application context) used to resolve the
     * {@code s3://<bucket>/<key>} location into an S3-backed {@link Resource}. Spring
     * Cloud AWS registers the {@code s3} protocol resolver, so no {@code S3Client} is
     * hand-built here.
     */
    private final ResourceLoader resourceLoader;

    /**
     * Creates the reader factory.
     *
     * @param awsResourceProperties the bound AWS resource-name properties supplying the
     *                              S3 batch-input bucket; injected by type, never {@code null}
     * @param resourceLoader        the {@code s3://}-aware resource loader; injected by
     *                              type, never {@code null}
     */
    public DailyTransactionReader(final AwsConfig.AwsResourceProperties awsResourceProperties,
                                  final ResourceLoader resourceLoader) {
        this.awsResourceProperties = awsResourceProperties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Primary, step-scoped {@link FlatFileItemReader} that streams the {@code DALYTRAN}
     * feed staged as a single object in the S3 batch-input bucket, reproducing the
     * {@code CBTRN01C} sequential read cycle.
     *
     * <p>The S3 location is {@code s3://<batch-input-bucket>/<object-key>}, where the
     * bucket is resolved from {@link AwsConfig.AwsResourceProperties} (default
     * {@code carddemo-batch-input}) and the object key is the {@link #OBJECT_KEY_PARAMETER}
     * job parameter, defaulting to {@link #DEFAULT_OBJECT_KEY} when absent. The
     * {@code s3://} URI is resolved by the injected {@link ResourceLoader} (Spring Cloud
     * AWS registers the {@code s3} protocol resolver), so no {@code S3Client} is
     * hand-built. The object stream is opened lazily when the step starts, so context
     * load and steps that do not use this reader never touch S3 &mdash; and the same
     * resource resolves against LocalStack with zero live AWS credentials (AAP
     * &sect;0.7.7).</p>
     *
     * <p>The fixed 350-byte layout is parsed by {@link #dailyTransactionLineTokenizer()}
     * (a strict {@link FixedLengthTokenizer}) and mapped to {@link DailyTransaction} by
     * {@link #dailyTransactionFieldSetMapper()}. Records are read front-to-back so
     * physical/sequential order is preserved (no sort); {@code read()} returns each
     * record in turn and {@code null} at end-of-input (the COBOL {@code FILE STATUS '10'}).
     * The file is read as {@link StandardCharsets#ISO_8859_1 ISO-8859-1} so that one byte
     * maps to exactly one character, keeping the fixed-width column math byte-accurate.
     * Malformed lines or I/O errors raise a {@code FlatFileParseException}/I/O exception
     * that fails the step (the COBOL abend path); records are never silently skipped.</p>
     *
     * <p>Active by default and whenever {@link #READER_SELECTION_PROPERTY} is unset or set
     * to {@code s3}; mutually exclusive with
     * {@link #dailyTransactionRepositoryItemReader(DailyTransactionRepository)}.</p>
     *
     * @param objectKey the staged object key from the {@link #OBJECT_KEY_PARAMETER} job
     *                  parameter; {@code null}/blank falls back to {@link #DEFAULT_OBJECT_KEY}
     * @return a configured, step-scoped {@link FlatFileItemReader} over
     *         {@link DailyTransaction}, preserving physical record order
     */
    @Bean(name = S3_READER_NAME)
    @StepScope
    @ConditionalOnProperty(name = READER_SELECTION_PROPERTY, havingValue = "s3", matchIfMissing = true)
    public FlatFileItemReader<DailyTransaction> dailyTransactionItemReader(
            @Value("#{jobParameters['" + OBJECT_KEY_PARAMETER + "']}") final String objectKey) {

        final String key = (objectKey == null || objectKey.isBlank()) ? DEFAULT_OBJECT_KEY : objectKey;
        // GDG/sequential-PS -> S3: the DALYTRAN feed is one object in the batch-input bucket.
        final String location = "s3://" + awsResourceProperties.getS3().getBatchInputBucket() + "/" + key;
        final Resource resource = resourceLoader.getResource(location);

        return new FlatFileItemReaderBuilder<DailyTransaction>()
                // Stable name -> ExecutionContext key prefix for restart state.
                .name(S3_READER_NAME)
                // s3://<bucket>/<key>, resolved lazily at step start (LocalStack-friendly).
                .resource(resource)
                // 1 byte == 1 char so fixed-width column ranges stay byte-accurate.
                .encoding(StandardCharsets.ISO_8859_1.name())
                // Fixed 350-byte CVTRA06Y layout; strict length check (short lines fail the step).
                .lineTokenizer(dailyTransactionLineTokenizer())
                // FieldSet -> DailyTransaction, incl. zoned-decimal overpunch decode for the amount.
                .fieldSetMapper(dailyTransactionFieldSetMapper())
                .build();
    }

    /**
     * Documented alternative: a step-scoped {@link RepositoryItemReader} that streams the
     * {@code daily_transactions} table (seeded by Flyway {@code V3} from
     * {@code dailytran.txt}) in ascending {@code dalytranId} order.
     *
     * <p>Provided for the case where the {@code DailyTransactionPostingJob} wiring chooses
     * the DB-staging path instead of raw-S3-file staging. It invokes the inherited
     * {@code DailyTransactionRepository.findAll(Pageable)} ({@code methodName = "findAll"})
     * page by page ({@link #DEFAULT_PAGE_SIZE} rows per page), sorted by
     * {@link #SORT_KEY_DALYTRAN_ID} ascending for a deterministic, restartable stream;
     * {@code read()} returns {@code null} once every row has been read. Underlying
     * data-access failures propagate as unchecked {@code DataAccessException}s and fail the
     * step (AAP &sect;0.7.5). The amount stays a {@link BigDecimal}; decoding is delegated to
     * JPA / the {@code V3} seed.</p>
     *
     * <p>Active only when {@link #READER_SELECTION_PROPERTY} is set to {@code repository};
     * mutually exclusive with {@link #dailyTransactionItemReader(String)}.</p>
     *
     * @param dailyTransactionRepository the JPA repository backing the
     *                                   {@code daily_transactions} staging table; injected
     *                                   by type, never {@code null}
     * @return a configured, step-scoped {@link RepositoryItemReader} over
     *         {@link DailyTransaction}, ordered ascending by {@code dalytranId}
     */
    @Bean(name = REPOSITORY_READER_NAME)
    @StepScope
    @ConditionalOnProperty(name = READER_SELECTION_PROPERTY, havingValue = "repository")
    public RepositoryItemReader<DailyTransaction> dailyTransactionRepositoryItemReader(
            final DailyTransactionRepository dailyTransactionRepository) {
        return new RepositoryItemReaderBuilder<DailyTransaction>()
                .name(REPOSITORY_READER_NAME)
                .repository(dailyTransactionRepository)
                // Inherited PagingAndSortingRepository.findAll(Pageable): the paged
                // equivalent of the COBOL OPEN/READ-NEXT/CLOSE sequential browse.
                .methodName("findAll")
                // Deterministic, restartable order (Sort.by("dalytranId") ascending).
                .sorts(Map.of(SORT_KEY_DALYTRAN_ID, Sort.Direction.ASC))
                .pageSize(DEFAULT_PAGE_SIZE)
                .build();
    }

    /**
     * Builds a fresh, strict {@link FixedLengthTokenizer} for the 350-byte
     * {@code CVTRA06Y} {@code DALYTRAN} record, using the byte ranges in
     * {@link #COLUMN_RANGES} and the field names in {@link #FIELD_NAMES}.
     *
     * <p>Shared by {@link #dailyTransactionItemReader(String)} and the unit tests so the
     * column contract has a single source of truth. Strict mode means a line shorter
     * than the last declared column (330) fails fast rather than yielding a short
     * record &mdash; the COBOL non-{@code '00'}/{@code '10'} abend path.</p>
     *
     * @return a configured {@link FixedLengthTokenizer} (a new instance per call)
     */
    static FixedLengthTokenizer dailyTransactionLineTokenizer() {
        final FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(FIELD_NAMES);
        tokenizer.setColumns(COLUMN_RANGES);
        tokenizer.setStrict(true);
        return tokenizer;
    }

    /**
     * Builds the {@link FieldSetMapper} that converts a tokenized {@link FieldSet} into a
     * {@link DailyTransaction}, applying the zoned-decimal overpunch decode for the amount,
     * numeric parsing for the category and merchant id, and text-to-{@link LocalDateTime}
     * conversion for the timestamps.
     *
     * <p>Shared by {@link #dailyTransactionItemReader(String)} and the unit tests.</p>
     *
     * @return a stateless {@link FieldSetMapper} for {@link DailyTransaction} (a new
     *         instance per call)
     */
    static FieldSetMapper<DailyTransaction> dailyTransactionFieldSetMapper() {
        return new DailyTransactionFieldSetMapper();
    }

    /**
     * Decodes a COBOL zoned-decimal {@code PIC S9(09)V99} field (the 11-character
     * {@code DALYTRAN-AMT}) into a {@link BigDecimal} with scale exactly
     * {@value #IMPLIED_DECIMAL_PLACES}.
     *
     * <p>This reproduces the legacy zoned-decimal representation observed in the
     * fixture: the first ten characters are plain digits (the nine integer digits plus
     * the first fractional digit), and the eleventh character is an
     * <strong>overpunch</strong> byte that encodes <em>both</em> the last (second
     * fractional) digit and the sign:</p>
     * <ul>
     *   <li><strong>Positive:</strong> {@code '{'}=0, {@code 'A'}..{@code 'I'}=1..9
     *       (a plain trailing digit {@code '0'}..{@code '9'} is also positive).</li>
     *   <li><strong>Negative:</strong> {@code '}'}=0, {@code 'J'}..{@code 'R'}=1..9.</li>
     * </ul>
     * <p>Worked fixture examples: {@code "0000005047G"} &rarr; magnitude
     * {@code 00000050477}, sign {@code +} &rarr; {@code 504.77}; {@code "0000009190}"}
     * &rarr; magnitude {@code 00000091900}, sign {@code -} &rarr; {@code -919.00}. After
     * assembling the 11-digit signed magnitude the implied decimal point is applied with
     * {@link BigDecimal#movePointLeft(int)}; the value is neither rounded nor altered
     * (AAP &sect;0.7.3). A malformed field raises an unchecked exception that fails the
     * step (the COBOL abend path); no {@code float}/{@code double} is ever used.</p>
     *
     * @param raw the exact 11-character {@code DALYTRAN-AMT} field (untrimmed)
     * @return the decoded amount as a {@link BigDecimal} with scale
     *         {@value #IMPLIED_DECIMAL_PLACES}
     * @throws IllegalArgumentException if the field is null, not 11 characters, has a
     *                                  non-numeric leading run, or carries an unrecognized
     *                                  overpunch character
     */
    static BigDecimal parseSignedOverpunch(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "DALYTRAN-AMT (S9(09)V99 zoned decimal) is required but was null");
        }
        if (raw.length() != AMOUNT_FIELD_LENGTH) {
            throw new IllegalArgumentException(
                    "DALYTRAN-AMT must be exactly " + AMOUNT_FIELD_LENGTH
                            + " characters (S9(09)V99 zoned decimal) but was '" + raw
                            + "' (length " + raw.length() + ")");
        }

        final String leadingDigits = raw.substring(0, AMOUNT_FIELD_LENGTH - 1);
        for (int i = 0; i < leadingDigits.length(); i++) {
            final char digit = leadingDigits.charAt(i);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(
                        "DALYTRAN-AMT leading nine-integer + first-fractional digits must be "
                                + "numeric but found '" + raw + "'");
            }
        }

        final char overpunch = raw.charAt(AMOUNT_FIELD_LENGTH - 1);
        final boolean negative;
        final int lastDigit;
        // Zoned-decimal overpunch: the trailing byte encodes the last digit AND the sign.
        switch (overpunch) {
            case '{' -> { negative = false; lastDigit = 0; }
            case 'A' -> { negative = false; lastDigit = 1; }
            case 'B' -> { negative = false; lastDigit = 2; }
            case 'C' -> { negative = false; lastDigit = 3; }
            case 'D' -> { negative = false; lastDigit = 4; }
            case 'E' -> { negative = false; lastDigit = 5; }
            case 'F' -> { negative = false; lastDigit = 6; }
            case 'G' -> { negative = false; lastDigit = 7; }
            case 'H' -> { negative = false; lastDigit = 8; }
            case 'I' -> { negative = false; lastDigit = 9; }
            case '}' -> { negative = true; lastDigit = 0; }
            case 'J' -> { negative = true; lastDigit = 1; }
            case 'K' -> { negative = true; lastDigit = 2; }
            case 'L' -> { negative = true; lastDigit = 3; }
            case 'M' -> { negative = true; lastDigit = 4; }
            case 'N' -> { negative = true; lastDigit = 5; }
            case 'O' -> { negative = true; lastDigit = 6; }
            case 'P' -> { negative = true; lastDigit = 7; }
            case 'Q' -> { negative = true; lastDigit = 8; }
            case 'R' -> { negative = true; lastDigit = 9; }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                negative = false;
                lastDigit = overpunch - '0';
            }
            default -> throw new IllegalArgumentException(
                    "DALYTRAN-AMT has an invalid zoned-decimal overpunch sign character '"
                            + overpunch + "' in '" + raw + "'");
        }

        final String unscaledSigned = (negative ? "-" : "") + leadingDigits + lastDigit;
        // movePointLeft(2) applies the implied V99 decimal; setScale(2, UNNECESSARY)
        // asserts (and documents) scale 2 without ever rounding the value.
        return new BigDecimal(unscaledSigned)
                .movePointLeft(IMPLIED_DECIMAL_PLACES)
                .setScale(IMPLIED_DECIMAL_PLACES, RoundingMode.UNNECESSARY);
    }

    /**
     * Parses a 26-character {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS} timestamp
     * text ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}) into a {@link LocalDateTime}.
     *
     * <p>A {@code null} or all-blank field maps to {@code null}: a pre-posting daily row
     * has no processed timestamp yet, so {@code DALYTRAN-PROC-TS} is blank in the feed. A
     * present-but-unparseable value raises {@link java.time.format.DateTimeParseException},
     * which fails the step (the COBOL abend path).</p>
     *
     * @param raw the raw (untrimmed) timestamp field, or {@code null}
     * @return the parsed {@link LocalDateTime}, or {@code null} when the field is blank
     */
    private static LocalDateTime parseTimestamp(final String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(trimmed, TIMESTAMP_FORMATTER);
    }

    /**
     * Parses a numeric {@code PIC 9(n)} field into an {@link Integer}, treating a blank
     * field as {@code null}.
     *
     * @param raw the field text (already space-trimmed by the field set), or {@code null}
     * @return the parsed {@link Integer}, or {@code null} when blank
     * @throws NumberFormatException if a non-blank value is not a valid integer (fails the step)
     */
    private static Integer parseInteger(final String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : Integer.valueOf(trimmed);
    }

    /**
     * Parses a numeric {@code PIC 9(n)} field into a {@link Long}, treating a blank field
     * as {@code null}.
     *
     * @param raw the field text (already space-trimmed by the field set), or {@code null}
     * @return the parsed {@link Long}, or {@code null} when blank
     * @throws NumberFormatException if a non-blank value is not a valid long (fails the step)
     */
    private static Long parseLong(final String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : Long.valueOf(trimmed);
    }

    /**
     * Maps a fixed-length {@link FieldSet} (produced by
     * {@link #dailyTransactionLineTokenizer()}) onto a {@link DailyTransaction}.
     *
     * <p>String fields are read trimmed; the {@code DALYTRAN-AMT} field is read raw (its
     * exact 11 bytes) and decoded by {@link #parseSignedOverpunch(String)}; the category
     * and merchant id are parsed numerically; and the two timestamp fields are converted
     * by {@link #parseTimestamp(String)} (blank &rarr; {@code null}). The mapper is
     * stateless and adds no validation or business logic beyond faithful type conversion
     * (Minimal Change Clause, AAP &sect;0.7.1).</p>
     */
    static final class DailyTransactionFieldSetMapper implements FieldSetMapper<DailyTransaction> {

        /**
         * Converts one tokenized record into a {@link DailyTransaction}.
         *
         * @param fieldSet the tokenized fixed-length record; never {@code null}
         * @return the mapped {@link DailyTransaction}
         */
        @Override
        public DailyTransaction mapFieldSet(final FieldSet fieldSet) {
            final DailyTransaction transaction = new DailyTransaction();
            transaction.setDalytranId(fieldSet.readString("dalytranId"));
            transaction.setDalytranTypeCd(fieldSet.readString("dalytranTypeCd"));
            transaction.setDalytranCatCd(parseInteger(fieldSet.readString("dalytranCatCd")));
            transaction.setDalytranSource(fieldSet.readString("dalytranSource"));
            transaction.setDalytranDesc(fieldSet.readString("dalytranDesc"));
            // Raw read: exact 11 bytes, no trim, then zoned-decimal overpunch decode -> BigDecimal(scale 2).
            transaction.setDalytranAmt(parseSignedOverpunch(fieldSet.readRawString("dalytranAmt")));
            transaction.setDalytranMerchantId(parseLong(fieldSet.readString("dalytranMerchantId")));
            transaction.setDalytranMerchantName(fieldSet.readString("dalytranMerchantName"));
            transaction.setDalytranMerchantCity(fieldSet.readString("dalytranMerchantCity"));
            transaction.setDalytranMerchantZip(fieldSet.readString("dalytranMerchantZip"));
            transaction.setDalytranCardNum(fieldSet.readString("dalytranCardNum"));
            // 26-char timestamp text -> LocalDateTime; blank (e.g. unposted PROC-TS) -> null.
            transaction.setDalytranOrigTs(parseTimestamp(fieldSet.readRawString("dalytranOrigTs")));
            transaction.setDalytranProcTs(parseTimestamp(fieldSet.readRawString("dalytranProcTs")));
            return transaction;
        }
    }
}
