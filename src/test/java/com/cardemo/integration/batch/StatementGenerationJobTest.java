/*
 * ******************************************************************
 * Component   : StatementGenerationJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11,
 *               Testcontainers 2.0.3, PostgreSQL 16, LocalStack)
 * Function    : Proves the migrated statement generator reproduces
 *               the five-step CREASTMT pipeline where only an
 *               assembled job over a real database and object store
 *               can show it: the OUTREC projection that writes 328
 *               of 350 bytes and silently drops the last two
 *               processing-timestamp characters and the whole
 *               twenty-byte filler, the two-key ascending sort the
 *               downstream scan depends on, the 32-byte work-cluster
 *               key, the two different output widths of 80 and 100
 *               bytes emitted by one step, the COND=(0,NE) gating
 *               this member alone justifies, the lenient '00' or
 *               '04' file-service success pair, and the removed
 *               510-transaction resident-table ceiling.
 * Source      : app/jcl/CREASTMT.JCL:22, :29-39, :44, :50, :53, :54,
 *               :56, :61, :66, :69, :73, :79, :89, :90, :94,
 *               app/cbl/CBSTM03A.CBL:148-149, :225-230, :316-329,
 *               :815-816, :921-923, app/cbl/CBSTM03B.CBL:128-131,
 *               app/cpy/COSTM01.CPY:20-36, app/cpy/CVTRA05Y.cpy,
 *               app/cpy/CVACT03Y.cpy, app/cpy/CVCUS01Y.cpy,
 *               app/cpy/CVACT01Y.cpy, app/catlg/LISTCAT.txt,
 *               app/data/ASCII/cardxref.txt,
 *               app/data/ASCII/custdata.txt,
 *               app/data/ASCII/acctdata.txt, CONTRIBUTING.md:33-34
 *               @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileService;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Integration test for the assembled statement-generation job: the five steps of
 * {@code app/jcl/CREASTMT.JCL} driving {@code app/cbl/CBSTM03A.CBL}, which reaches its four datasets through
 * {@code app/cbl/CBSTM03B.CBL}.
 *
 * <h2>What it does</h2>
 *
 * <p>Every assertion here is a property of the <em>assembled</em> topology - the job, its five steps, its
 * three condition-code gates, its ordered reader, its processor and its two fixed-width object-store sinks -
 * measured against a real PostgreSQL 16 schema and a real object store. Nothing here re-tests
 * {@code com.cardemo.batch.processors.StatementProcessor} or {@code com.cardemo.service.shared.FileService}
 * in isolation; that is the sibling unit tier's contract. What is pinned here is only what an assembled run
 * can show, and each item carries its verified locator.
 *
 * <dl>
 *   <dt>The five steps, in the order the member declares them</dt>
 *   <dd>{@code DELDEF01} at {@code app/jcl/CREASTMT.JCL:22}, {@code STEP010} at {@code :44},
 *       {@code STEP020} at {@code :56}, {@code STEP030} at {@code :66} and {@code STEP040} at {@code :79}.
 *       A step order that differed would still produce output, so the order is asserted rather than
 *       assumed.</dd>
 *
 *   <dt>The work-cluster geometry</dt>
 *   <dd>{@code KEYS(32 0)} and {@code RECORDSIZE(350 350)} of the {@code DEFINE CLUSTER} at
 *       {@code app/jcl/CREASTMT.JCL:29-39}, corroborated independently by {@code app/cpy/COSTM01.CPY:20-36},
 *       whose {@code TRNX-KEY} is 16 plus 16 and whose {@code TRNX-REST} sums to 318 for a 350-byte
 *       total.</dd>
 *
 *   <dt>The two-key ascending sort</dt>
 *   <dd>{@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:53} - card number then
 *       transaction identifier, both ascending. This is not cosmetic: the resident-table scan at
 *       {@code app/cbl/CBSTM03A.CBL:419} exits on the first card number greater than the one sought, and that
 *       early exit is correct <em>only</em> because this sort guarantees the ordering.</dd>
 *
 *   <dt>The projection that writes 328 of 350 bytes - the headline preserved defect</dt>
 *   <dd>{@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code app/jcl/CREASTMT.JCL:54} moves the
 *       16-byte card number to the front, follows it with the original head at bytes 1-262, and then copies
 *       fifty bytes from offset 279. Sixteen plus 262 plus 50 is
 *       {@value com.cardemo.model.dto.StatementTransaction#PROJECTION_LAST_WRITTEN_POSITION}. Because the
 *       originating timestamp occupies input 279-304 and the processing timestamp 305-330, fifty bytes from
 *       279 carries the whole originating timestamp but only the <strong>first
 *       {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} of the
 *       {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_LENGTH}</strong> processing
 *       timestamp bytes, and the {@value com.cardemo.model.dto.StatementTransaction#FILLER_LENGTH}-byte
 *       trailing filler is never written at all. This is asserted explicitly, with a probe timestamp whose
 *       final two characters are distinctive, so that their loss is demonstrated rather than inferred.
 *       <strong>It is a preserved legacy behaviour and must never be "corrected"</strong> - correcting it
 *       diverges statement output from the legacy baseline in a way that looks like a Java defect and is
 *       not.</dd>
 *
 *   <dt>Two different record widths from one step</dt>
 *   <dd>{@code STMTFILE} is {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL:89} and {@code HTMLFILE} is
 *       {@code LRECL=100} at {@code :94}. Both objects are unblocked and undelimited, so a consumer finds
 *       boundaries by counting bytes and by nothing else, which is why each object's size is asserted to be
 *       an exact multiple of its width.</dd>
 *
 *   <dt>{@code COND=(0,NE)} gating, which this member alone justifies</dt>
 *   <dd>The parameter occurs corpus-wide at exactly three sites, all of them here:
 *       {@code app/jcl/CREASTMT.JCL:56}, {@code :66} and {@code :79}. On a zero return code all three gates
 *       admit their step, which is what this test measures. The gates are inline plain objects rather than
 *       container beans, and that too is asserted, because reaching one through the bean factory would
 *       throw.</dd>
 *
 *   <dt>The scoped file-status leniency, and the asymmetry that is its substance</dt>
 *   <dd>{@code app/cbl/CBSTM03A.CBL} accepts either {@code '00'} or {@code '04'} as success at nine open and
 *       read sites - a verified count, not an estimate - and does <em>not</em> recognise end of file at any of
 *       them. The one exception is the get-next loop at {@code :837}, whose guard is {@code '00'} alone and
 *       for which {@code '10'} is the normal terminator. Status {@code '10'} therefore reaches both guards and
 *       means opposite things: fatal at the priming read of {@code :744-754}, end of data at the get-next.
 *       This tier asserts that contrast, which is the part only an assembled run can show; observing a
 *       literal {@code '04'} needs a substituted dataset and belongs to the unit tier, the more so because
 *       {@code com.cardemo.service.shared.FileService} is job scoped and has no instance outside a running
 *       job.</dd>
 *
 *   <dt>An empty transaction cluster is fatal, and that is parity rather than fragility</dt>
 *   <dd>A consequence of the guard above, and the single most counter-intuitive behaviour this class pins.
 *       Four steps succeed on an empty input - an empty {@code SORTIN} yields an empty {@code SORTOUT} and a
 *       {@code REPRO} of nothing succeeds - and then {@code STEP040} abends, because its priming read meets
 *       end of file at a guard that does not accept it. Asserting a completed run here would have been a
 *       behaviour change dressed as robustness.</dd>
 *
 *   <dt>The removed resident-table ceiling - a labelled deviation, never parity</dt>
 *   <dd>{@code app/cbl/CBSTM03A.CBL:225-230} declares {@code WS-CARD-TBL OCCURS 51 TIMES} each holding
 *       {@code WS-TRAN-TBL OCCURS 10 TIMES}, so the legacy program holds at most
 *       {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions per
 *       run and performs no bounds check whatsoever - a latent storage overrun. The Java tier streams
 *       instead. That is more efficient <em>and</em> safer, but it changes behaviour at scale, so this test
 *       drives more than the legacy ceiling through the pipeline and records the outcome as a <strong>
 *       deliberate, justified deviation</strong>. Claiming parity here would be false; leaving the removal
 *       unasserted would be worse.</dd>
 * </dl>
 *
 * <h2>What it deliberately does not assert</h2>
 *
 * <p><strong>No abend code and no return code.</strong> {@code app/cbl/CBSTM03A.CBL:921-923} is
 * {@code 9999-ABEND-PROGRAM}, {@code DISPLAY 'ABENDING PROGRAM'} and {@code CALL 'CEE3ABD'} with <em>no</em>
 * {@code ABCODE} and <em>no</em> {@code TIMING}, so it abends on the language environment's default and sits
 * outside the 999-and-return-code-12 contract that eight other batch programs share.
 * {@code app/cbl/CBSTM03B.CBL:128-131} is a bare {@code GOBACK} with no abend at all, and
 * {@code CBSTM03A} assigns no {@code RETURN-CODE} anywhere. Asserting 999, or 12, or a fabricated exit-code
 * locator for this job would be inventing a contract the source does not have.
 *
 * <p><strong>No expected-output baseline for statement output.</strong> The Gate 1 posting expectation is
 * committed under {@code src/test/resources/parity/gate1/} and is derived from
 * {@code app/cbl/CBTRN02C.cbl}, so it covers no statement output at all. A search of the repository for
 * captured {@code STMTFILE} or {@code HTMLFILE} data returns only dataset-definition job control and zero
 * captured data, so a statement baseline is <em>Not available</em>. What is needed is a {@code STMTFILE} at 80 bytes
 * per line and an {@code HTMLFILE} at 100 bytes per line taken from a real {@code CBSTM03A} run at a known
 * input state. Until such an artefact exists this class creates no baseline file and fabricates no expected
 * bytes; a baseline produced by running the Java implementation would be circular and is forbidden.
 *
 * <p><strong>No test for file status {@code '35'}.</strong> That status and its file-unavailable response
 * code occur nowhere in the COBOL corpus, so its behaviour here is <em>Not available</em> and no test for it
 * is invented.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. This class lives under {@code src/test/java/com/cardemo/integration}, which
 * Failsafe binds at the {@code integration-test} and {@code verify} phases even though the class name ends in
 * {@code Test}; Surefire binds only the {@code unit} tree and excludes this one. <strong>The location is
 * load-bearing.</strong> A class moved up to the {@code integration} folder itself, or to any package above
 * it, matches neither plugin's include set and is then collected by neither: the build stays green, both
 * plugins report success, and the class silently never runs.
 *
 * <p>A reachable Docker socket is a prerequisite, because the parent harness starts one PostgreSQL 16
 * container and one LocalStack container per JVM. Compilation is at release 25 with {@code -Xlint:all} and
 * {@code -Werror}, so an unused import or a malformed Javadoc reference fails the build outright.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The {@code test} profile is active, contributed by the parent along with the container images, the fixed
 * UTC clock every time-dependent bean receives, and {@code spring.batch.job.enabled} set false so that no job
 * runs at context refresh. Bucket names and key prefixes are bound from the same property keys the job binds -
 * {@code carddemo.aws.s3.batch-output-bucket}, {@code carddemo.aws.s3.statements-bucket} and
 * {@code carddemo.aws.s3.work-prefixes.trxfl} - so no address, bucket or endpoint literal appears in this
 * file. The emit step's window is {@code carddemo.batch.creastmt.chunk-size}. All three Flyway migrations are
 * a precondition: they own the schema and the seed, and this class neither truncates nor deletes nor seeds
 * through SQL of its own.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Every test errors before its body runs</dt>
 *   <dd>No Docker socket. The parent's two containers cannot start, so the context never refreshes.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}, or a launch rejected outright</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. Every
 *       method here that launches carries it, and the parent refuses the launch without it.</dd>
 *
 *   <dt>An artefact cannot be resolved at 2.0.3</dt>
 *   <dd>The container library's 2.x line renamed every module: only the prefixed coordinates exist, and the
 *       managed version must be overridden by property rather than by importing a second bill of materials.
 *       Both remedies are required together; either alone still fails. Severity: <strong>Blocker</strong>.
 *       It is resolved in the build descriptor and nothing here may change it.</dd>
 *
 *   <dt>A case-sensitive glob drops this job's sources</dt>
 *   <dd>{@code CREASTMT.JCL}, {@code CBSTM03A.CBL}, {@code CBSTM03B.CBL} and {@code COSTM01.CPY} are all
 *       spelled in upper case on disk. A {@code *.jcl} or {@code *.cbl} pattern silently omits them and this
 *       job loses its only source. Severity: <strong>Blocker</strong>. All four are also carriage-return
 *       delimited and must never be normalised.</dd>
 *
 *   <dt>Statement output no longer matches the legacy shape</dt>
 *   <dd>Almost always because the 328-of-350 truncation was "fixed". It is deliberate. See the projection
 *       entry above and the test that pins it.</dd>
 * </dl>
 *
 * <h2>Findings this class carries, by severity</h2>
 *
 * <ul>
 *   <li><strong>Medium</strong> - the {@code HTMLFILE} record-length conflict: 80 in the pre-delete at
 *       {@code app/jcl/CREASTMT.JCL:69} against 100 in the execution step at {@code :94}. The 100-byte width
 *       governs, confirmed independently by {@code 05 HTML-FIXED-LN PIC X(100)} at
 *       {@code app/cbl/CBSTM03A.CBL:148-149}. That confirmation is precisely why the mismatch is a legacy
 *       defect to log rather than a signal to change the width. Remediation: none in code - it is held as
 *       {@code DL-LD-02} in the root-owned {@code DECISION_LOG.md} and the emitted width is asserted
 *       here.</li>
 *   <li><strong>Medium</strong> - a physically corrupted {@code STMTFILE} data-definition line at
 *       {@code app/jcl/CREASTMT.JCL:90}, whose text is fragments of three different statements run together.
 *       Remediation: log it; no reconstruction of the intended text is attempted, because any reconstruction
 *       would be a guess presented as a source fact.</li>
 *   <li><strong>Low</strong> - two retained intentional no-ops originate in this job's source,
 *       {@code app/cbl/CBSTM03A.CBL:324} and the unreachable {@code EXIT} at {@code :816} that follows the
 *       unconditional branch at {@code :815}. Both are retained in
 *       {@code com.cardemo.batch.processors.StatementProcessor}, not here; this class cites them as context,
 *       never asserts that either produces an effect, and never treats either as a defect. This package
 *       carries no dead-code exemption of its own.</li>
 * </ul>
 */
@DisplayName("Statement generation job: the 328-of-350 OUTREC truncation, the two output widths, the "
        + "COND=(0,NE) gates, and the removed 510-transaction ceiling")
class StatementGenerationJobTest extends AbstractBatchIntegrationTest {

    /** The job under test, bound by bean name so no other assignable job can be injected in its place. */
    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    /** The relation the sort step reads; used to commit the synthetic posted transactions each test needs. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** The driving read of the emit step, and the source of the seeded card numbers used for the probes. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Commits the additional card that finding F-01's regression needs, and removes it again.
     *
     * <p>A transaction carries a foreign key to the card relation - {@code fk04_transaction_card} - so a probe
     * transaction on a second card of one account needs that card to exist. The cross-reference relation
     * carries no such key to it, which is why the two rows are inserted separately rather than through one.
     */
    @Autowired
    private CardRepository cardRepository;

    /** Confirms the seeded customer census the statement path joins through. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Confirms the seeded account census that fixes the expected statement count. */
    @Autowired
    private AccountRepository accountRepository;

    /** Used only to prove the condition-code gates are not container beans. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Reads the emitted work and statement objects back byte for byte. */
    @Autowired
    private S3Client s3Client;

    /** The projected work object's bucket, bound from the key the job binds rather than named here. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** The statement bucket, bound from the key the job binds rather than named here. */
    @Value("${carddemo.aws.s3.statements-bucket}")
    private String statementsBucket;

    /** The work key prefix, bound from the key the job binds, carrying the job's own default. */
    @Value("${carddemo.aws.s3.work-prefixes.trxfl:work/trxfl}")
    private String workPrefix;

    // Contract values. Every one is an instance field, never static: the parent harness documents a hard
    // budget of exactly two static fields for this whole package, both of them containers, and a constant
    // here would widen a rule that exists to keep global mutable state out of the tier.

    /** {@code DELDEF01} at {@code app/jcl/CREASTMT.JCL:22}, the first of the five steps. */
    private final String defineStepName = "statementGenerationDefineStep";

    /** {@code STEP010} at {@code app/jcl/CREASTMT.JCL:44}: the sort and the projection. */
    private final String sortStepName = "statementGenerationSortStep";

    /** {@code STEP020} at {@code app/jcl/CREASTMT.JCL:56}: the gated {@code REPRO}. */
    private final String loadStepName = "statementGenerationLoadStep";

    /** {@code STEP030} at {@code app/jcl/CREASTMT.JCL:66}: the gated pre-delete of both outputs. */
    private final String preDeleteStepName = "statementGenerationPreDeleteStep";

    /** {@code STEP040} at {@code app/jcl/CREASTMT.JCL:79}: the gated {@code CBSTM03A} emission. */
    private final String emitStepName = "statementGenerationEmitStep";

    /** Job-context entry carrying the concrete work object key the sort step created. */
    private final String workObjectKeyContextEntry = "carddemo.creastmt.work.objectKey";

    /** Job-context entry carrying the record count the sort step wrote. */
    private final String workRecordCountContextEntry = "carddemo.creastmt.work.recordCount";

    /** Job-context entry carrying the record count the load step consumed. */
    private final String loadedRecordCountContextEntry = "carddemo.creastmt.load.recordCount";

    /** Job-context entry carrying the rendered {@code DEFINE CLUSTER} geometry. */
    private final String workGeometryContextEntry = "carddemo.creastmt.work.geometry";

    /** Job-context entry carrying the generation ordinal reserved once for the run. */
    private final String workGenerationContextEntry = "carddemo.creastmt.work.generation";

    /** Job-context entry carrying the number of statements the emit step produced. */
    private final String statementsEmittedContextEntry = "carddemo.creastmt.emit.statementCount";

    /** The five steps in the order {@code app/jcl/CREASTMT.JCL} declares them. */
    private final List<String> expectedStepOrder = List.of(defineStepName, sortStepName, loadStepName,
            preDeleteStepName, emitStepName);

    /** Cross-reference rows {@code app/data/ASCII/cardxref.txt} seeds, and so the statement count. */
    private final int seededCrossReferenceCount = 50;

    /** A transaction type the seed guarantees exists in {@code transaction_type}. */
    private final String seededTypeCode = "01";

    /** A category the seed guarantees exists in {@code transaction_category} paired with the type above. */
    private final Integer seededCategoryCode = Integer.valueOf(1);

    /** {@code TRAN-SOURCE}, plain ten-byte text and never an enum constant. */
    private final String probeSource = "SYNTHETIC ";

    /** {@code TRAN-DESC}: distinctive, so its appearance in a statement line is unambiguous. */
    private final String probeDescription = "PROJECTION PROBE DEBIT";

    /** {@code TRAN-MERCHANT-ID}, inside the nine digits {@code PIC 9(09)} permits. */
    private final Long probeMerchantId = Long.valueOf(123_456_789L);

    /** {@code TRAN-MERCHANT-NAME}, within the fifty bytes the layout declares. */
    private final String probeMerchantName = "PARITY PROBE MERCHANT";

    /** {@code TRAN-MERCHANT-CITY}, within the fifty bytes the layout declares. */
    private final String probeMerchantCity = "PROBE CITY";

    /** {@code TRAN-MERCHANT-ZIP}, within the ten bytes the layout declares. */
    private final String probeMerchantZip = "0000000000";

    /** {@code TRAN-AMT} at {@code NUMERIC(11,2)}, decimal throughout and never a binary floating type. */
    private final BigDecimal probeAmount = new BigDecimal("1234.56");

    /**
     * Positions 25 and 26 of the probe <em>originating</em> timestamp.
     *
     * <p>Distinctive on purpose. The projection copies this field whole, so these two characters must
     * survive; the processing timestamp's own suffix must not. Two different suffixes are what make the
     * asymmetry provable rather than merely stated.
     */
    private final String originatingTimestampProbeSuffix = "77";

    /**
     * Positions 25 and 26 of the probe <em>processing</em> timestamp - the two bytes
     * {@code OUTREC FIELDS} at {@code app/jcl/CREASTMT.JCL:54} silently discards.
     *
     * <p><strong>Why a probe suffix is necessary rather than decorative.</strong> The batch timestamp
     * generator emits four literal zeros in positions 23-26, so over a naturally generated value the loss of
     * positions 25 and 26 is invisible: the truncated field and the whole field are byte-identical. A
     * distinctive suffix is the only way to demonstrate that the two bytes are genuinely dropped rather than
     * coincidentally equal. The column is {@code CHAR(26)} with no format constraint, so this is a legal
     * stored value, and the run never depends on it parsing as a timestamp.
     */
    private final String processingTimestampProbeSuffix = "99";

    /** Zero-padding width of the generation segment of an object key, so lexical order is creation order. */
    private final int generationSegmentWidth = 19;

    /** File name of the {@value com.cardemo.batch.writers.StatementWriter#STMTFILE_DD_NAME} object. */
    private final String statementTextObjectName = "STATEMNT.PS";

    /** File name of the {@value com.cardemo.batch.writers.StatementWriter#HTMLFILE_DD_NAME} object. */
    private final String statementMarkupObjectName = "STATEMNT.HTML";

    /** Root key segment under which both statement objects are written. */
    private final String statementKeyRoot = "statements/";

    /**
     * The card number finding F-01's regression adds as a second card on one seeded account.
     *
     * <p>Sixteen digits, as {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:5} requires, and chosen
     * above every seeded card number so that it sorts last: the projection's primary sort key is the card
     * number, so a value at the end of the sequence exercises the two-card account at the far end of the
     * stream from its first card rather than adjacent to it, which is the arrangement the card-ordered driving
     * read actually produces.
     */
    private final String additionalCardNumber = "9900000000000001";

    /**
     * Transactions driven through the pipeline by the ceiling test: comfortably above the legacy
     * {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}, and spread over
     * the fifty seeded cards so that the per-card limit of
     * {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_CARD} is exceeded too.
     */
    private final int aboveCeilingTransactionCount = 560;

    // The five steps and the work-cluster contract.

    /**
     * The five steps of {@code app/jcl/CREASTMT.JCL} run, once each, in the order the member declares them.
     *
     * <p>Purpose: pin the step sequence itself, because a pipeline whose steps ran in a different order would
     * still produce output and would still pass every assertion about that output's shape. Inputs: three
     * probe transactions on three distinct seeded cards, committed before the launch. Output: none. Side
     * effects: the run commits business rows, batch metadata and object-store keys; the parent's reset hook
     * restores all three afterwards. Error modes: a failure here means either a step was renamed, a step was
     * dropped, or the flow's transitions were re-ordered.
     *
     * <p>The order is asserted by name rather than by count, so that dropping one step and duplicating
     * another cannot pass.
     */
    @Test
    @DisplayName("1. the five steps run once each in CREASTMT order: DELDEF01 :22, STEP010 :44, STEP020 :56, "
            + "STEP030 :66, STEP040 :79")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theFiveStepsRunOnceEachInTheOrderCreastmtDeclares() {
        seedProbeTransactionsOnDistinctCards(3);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        assertThat(stepNamesInExecutionOrder(execution))
                .as("app/jcl/CREASTMT.JCL declares DELDEF01 at :22, STEP010 at :44, STEP020 at :56, "
                        + "STEP030 at :66 and STEP040 at :79; the flow must reproduce that sequence exactly, "
                        + "with no step repeated and none suppressed on a zero return code")
                .containsExactlyElementsOf(expectedStepOrder);
    }

    /**
     * The work cluster carries a 32-byte key and a 350-byte record, and the two facts corroborate each other.
     *
     * <p>Purpose: pin the geometry of the {@code DEFINE CLUSTER} at {@code app/jcl/CREASTMT.JCL:29-39}
     * against the record layout that must fit it. Inputs: two probe transactions. Output: none. Side effects:
     * the run commits and is reset by the parent hook. Error modes: a failure means either the rendered
     * geometry drifted from the control card, or the projected record layout stopped agreeing with it.
     *
     * <p>The key length is proven twice over, which is why both halves are asserted here rather than one
     * being taken on trust: {@code KEYS(32 0)} at {@code :30} states it, and
     * {@code app/cpy/COSTM01.CPY:21-23} derives it independently as {@code TRNX-CARD-NUM PIC X(16)} plus
     * {@code TRNX-ID PIC X(16)}. The record length is likewise stated by {@code RECORDSIZE(350 350)} at
     * {@code :32} and derived as the 32-byte key plus the 318-byte remainder of {@code :24-36}.
     */
    @Test
    @DisplayName("2. the work cluster is defined with KEYS(32 0) and RECORDSIZE(350 350), corroborated by "
            + "COSTM01.CPY's 32-byte key and 318-byte remainder")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theWorkClusterCarriesTheThirtyTwoByteKeyAndTheThreeHundredFiftyByteRecord() {
        seedProbeTransactionsOnDistinctCards(2);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final ExecutionContext jobContext = execution.getExecutionContext();
        assertThat(jobContext.containsKey(workGeometryContextEntry))
                .as("DELDEF01 publishes the geometry it defined, so a later step and this test read the same "
                        + "value rather than each restating the control card")
                .isTrue();
        assertThat(jobContext.getString(workGeometryContextEntry))
                .as("the DEFINE CLUSTER at app/jcl/CREASTMT.JCL:29-39 declares KEYS(32 0) at :30 and "
                        + "RECORDSIZE(350 350) at :32")
                .contains("KEYS(" + StatementTransaction.KEY_LENGTH + " 0)")
                .contains("RECORDSIZE(" + StatementTransaction.RECORD_LENGTH + " "
                        + StatementTransaction.RECORD_LENGTH + ")");

        assertThat(StatementTransaction.CARD_NUMBER_LENGTH + StatementTransaction.TRANSACTION_ID_LENGTH)
                .as("app/cpy/COSTM01.CPY:22-23 derives the key independently: TRNX-CARD-NUM PIC X(16) plus "
                        + "TRNX-ID PIC X(16). It must equal the KEYS(32 0) the control card declares")
                .isEqualTo(StatementTransaction.KEY_LENGTH);
        assertThat(StatementTransaction.KEY_LENGTH + StatementTransaction.REMAINDER_LENGTH)
                .as("app/cpy/COSTM01.CPY:24-36 sums TRNX-REST to 318, which with the 32-byte key is the "
                        + "RECORDSIZE(350 350) of app/jcl/CREASTMT.JCL:32")
                .isEqualTo(StatementTransaction.RECORD_LENGTH);

        for (final String record : projectedWorkRecords(execution)) {
            assertThat(record.length())
                    .as("every record written to SORTOUT carries the LRECL=350 its DCB declares at "
                            + "app/jcl/CREASTMT.JCL:50")
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
        }
    }

    // The projection. app/jcl/CREASTMT.JCL:54 - the headline preserved defect of this job.

    /**
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} writes
     * {@value com.cardemo.model.dto.StatementTransaction#PROJECTION_LAST_WRITTEN_POSITION} of 350 bytes, and
     * the {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_PAD_LENGTH} bytes it
     * discards really are discarded.
     *
     * <p>Purpose: pin every clause of the projection at {@code app/jcl/CREASTMT.JCL:54} - including the two
     * bytes of processing timestamp and the whole {@value
     * com.cardemo.model.dto.StatementTransaction#FILLER_LENGTH}-byte filler that it never writes - as a
     * <strong>preserved legacy behaviour</strong>. Inputs: one probe transaction whose two timestamps carry
     * <em>different</em> distinctive suffixes. Output: none. Side effects: the run commits and is reset by
     * the parent hook. Error modes: a failure almost always means the truncation was "corrected", which
     * diverges statement output from the legacy baseline.
     *
     * <p><strong>How each clause is verified independently.</strong> No expected record is rendered by
     * production code here - that would be circular. Instead every clause is located by a landmark whose
     * value this test chose:
     *
     * <ul>
     *   <li>{@code 1:263,16} - output 1-16 must be the card number, which came from input 263-278.</li>
     *   <li>{@code 17:1,262} - output 17-32 must be the transaction identifier, which came from input 1-16;
     *       and output 269-278 must be the merchant postal code, which came from input 253-262 and is the
     *       <em>last</em> ten bytes of the 262-byte head. That landmark is what proves the head clause is
     *       exactly 262 bytes rather than approximately so: were it shorter or longer, the postal code would
     *       not land there and the timestamps would not begin at 279.</li>
     *   <li>{@code 279:279,50} - output 279-304 must be the whole 26-byte originating timestamp, suffix
     *       included, while output 305-328 must be only the leading
     *       {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH}
     *       characters of the processing timestamp. Fifty bytes from offset 279 reaches 328 and stops.</li>
     *   <li>Everything after 328 must be blank: the two lost timestamp bytes and the twenty-byte filler.</li>
     * </ul>
     *
     * <p>The two suffixes differ deliberately. One field is copied whole and one is cut, so a single shared
     * suffix could not distinguish "the cut happened" from "the values happened to match".
     */
    @Test
    @DisplayName("3. the OUTREC projection at CREASTMT.JCL:54 writes 328 of 350 bytes: TRNX-PROC-TS keeps "
            + "only its first 24 characters and the 20-byte filler is never written - preserved, not fixed")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theOutrecProjectionWritesThreeHundredTwentyEightOfThreeHundredFiftyBytes() {
        final String cardNumber = seededCardNumbersAscending().getFirst();
        final String transactionId = probeTransactionId(1);
        seedTransaction(transactionId, cardNumber);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final List<String> records = projectedWorkRecords(execution);
        assertThat(records)
                .as("one committed transaction yields exactly one projected record in SORTOUT")
                .hasSize(1);
        final String projected = records.getFirst();

        // Clause 1:263,16 - the card number is lifted to the front.
        assertThat(projected.length())
                .as("the projection writes %d bytes and DFSORT pads the remainder to the LRECL=350 of "
                        + "app/jcl/CREASTMT.JCL:50",
                        Integer.valueOf(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION))
                .isEqualTo(StatementTransaction.RECORD_LENGTH);
        assertThat(projected.substring(0, StatementTransaction.CARD_NUMBER_LENGTH))
                .as("clause 1:263,16 moves TRAN-CARD-NUM from input 263-278 to output 1-16")
                .isEqualTo(cardNumber);

        // Clause 17:1,262 - the original head follows, and its final ten bytes fix its length.
        final int headStart = StatementTransaction.CARD_NUMBER_LENGTH;
        assertThat(projected.substring(headStart, headStart + StatementTransaction.TRANSACTION_ID_LENGTH))
                .as("clause 17:1,262 begins with TRAN-ID from input 1-16, so output 17-32 is the identifier")
                .isEqualTo(transactionId);

        final int postalCodeStart = StatementTransaction.BASE_TAIL_OFFSET - 1
                - StatementTransaction.MERCHANT_ZIP_LENGTH;
        assertThat(projected.substring(postalCodeStart, StatementTransaction.BASE_TAIL_OFFSET - 1))
                .as("TRAN-MERCHANT-ZIP is input 253-262, the last ten bytes of the 262-byte head clause, so "
                        + "it must land at output 269-278; a head clause of any other length would move it "
                        + "and would move the timestamps off offset 279")
                .isEqualTo(probeMerchantZip);

        // Clause 279:279,50 - fifty bytes from offset 279 reach 328 and stop mid-timestamp.
        final int tailStart = StatementTransaction.BASE_TAIL_OFFSET - 1;
        final int processingStart = tailStart + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH;
        assertThat(projected.substring(tailStart, processingStart))
                .as("TRNX-ORIG-TS at app/cpy/COSTM01.CPY:34 is inside the fifty bytes the third clause "
                        + "copies, so all %d characters survive - suffix included",
                        Integer.valueOf(StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH))
                .isEqualTo(probeOriginatingTimestamp())
                .endsWith(originatingTimestampProbeSuffix);

        final int significantEnd = processingStart
                + StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH;
        assertThat(significantEnd)
                .as("the third clause copies %d bytes from offset %d, so the last position it writes is %d",
                        Integer.valueOf(StatementTransaction.BASE_TAIL_LENGTH),
                        Integer.valueOf(StatementTransaction.BASE_TAIL_OFFSET),
                        Integer.valueOf(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION))
                .isEqualTo(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION);
        assertThat(projected.substring(processingStart, significantEnd))
                .as("TRNX-PROC-TS at app/cpy/COSTM01.CPY:35 receives only the leading %d of its %d "
                        + "characters, because fifty bytes from offset 279 run out at position 328",
                        Integer.valueOf(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH),
                        Integer.valueOf(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH))
                .isEqualTo(probeProcessingTimestamp()
                        .substring(0, StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH));

        final int fillerStart = processingStart + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH;
        assertThat(projected.substring(processingStart, fillerStart))
                .as("read as the whole PIC X(26) field, TRNX-PROC-TS is its first %d characters padded to "
                        + "%d with spaces - the stored suffix is gone, not merely displaced",
                        Integer.valueOf(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH),
                        Integer.valueOf(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH))
                .isEqualTo(probeProcessingTimestamp()
                        .substring(0, StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                        + " ".repeat(StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH));
        assertThat(projected.substring(fillerStart))
                .as("the FILLER PIC X(20) at app/cpy/COSTM01.CPY:36 is never written at all, because the "
                        + "projection stops at position %d",
                        Integer.valueOf(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION))
                .hasSize(StatementTransaction.FILLER_LENGTH)
                .isBlank();
        assertThat(projected.substring(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION))
                .as("positions %d-350 are the two discarded timestamp bytes plus the whole filler: %d "
                        + "positions that the projection leaves as spaces",
                        Integer.valueOf(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION + 1),
                        Integer.valueOf(StatementTransaction.RECORD_LENGTH
                                - StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION))
                .isBlank();

        // The stored suffix exists, so its absence above is a loss rather than a value that was never there.
        assertThat(probeProcessingTimestamp()
                .substring(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH))
                .as("the persisted CHAR(26) processing timestamp genuinely carries a distinctive suffix in "
                        + "positions 25-26; without it the truncation would be unobservable, because the "
                        + "batch generator's own trailing zeros make the cut byte-identical to the whole")
                .isEqualTo(processingTimestampProbeSuffix);
        assertThat(projected)
                .as("no part of the projected record carries the discarded processing-timestamp suffix "
                        + "while the originating suffix is still present, which is what distinguishes a real "
                        + "truncation from two values that happened to agree")
                .contains(probeTimestampPrefix() + originatingTimestampProbeSuffix)
                .doesNotContain(probeTimestampPrefix() + processingTimestampProbeSuffix);
    }

    /**
     * Output positions 1-32 form the composite key {@code KEYS(32 0)} names: card number then identifier.
     *
     * <p>Purpose: pin the consequence of the first two projection clauses taken together - that the
     * reshuffled record opens with exactly the key the work cluster is defined on, which is the whole reason
     * the projection reorders the record at all. Inputs: one probe transaction. Output: none. Side effects:
     * the run commits and is reset by the parent hook. Error modes: a failure means the key would not be
     * loadable into a cluster declared {@code KEYS(32 0)}.
     */
    @Test
    @DisplayName("4. output positions 1-32 are TRNX-CARD-NUM then TRNX-ID, matching KEYS(32 0) at "
            + "CREASTMT.JCL:30 and TRNX-KEY at COSTM01.CPY:21-23")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theProjectedRecordOpensWithTheThirtyTwoByteCompositeKey() {
        final String cardNumber = seededCardNumbersAscending().getFirst();
        final String transactionId = probeTransactionId(1);
        seedTransaction(transactionId, cardNumber);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final String projected = projectedWorkRecords(execution).getFirst();
        assertThat(projected.substring(0, StatementTransaction.KEY_LENGTH))
                .as("TRNX-KEY at app/cpy/COSTM01.CPY:21-23 is the 16-byte card number followed by the "
                        + "16-byte identifier, and the projection is what puts them in that order")
                .hasSize(StatementTransaction.KEY_LENGTH)
                .isEqualTo(cardNumber + transactionId);
    }

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} orders on both keys ascending, and the projection preserves
     * that order.
     *
     * <p>Purpose: pin the ordering the downstream scan depends on for its correctness, not merely for its
     * tidiness. {@code app/cbl/CBSTM03A.CBL:419} abandons its resident-table scan at the first stored card
     * number greater than the one sought, so a descending or unordered stream would make the scan miss
     * records that are present. Inputs: several transactions per card on three cards, committed in an order
     * that is deliberately <em>not</em> the sort order, so that a pipeline which merely preserved insertion
     * order would fail. Output: none. Side effects: the run commits and is reset by the parent hook. Error
     * modes: a failure means the ordering guarantee is gone and the scan's early exit is no longer sound.
     *
     * <p>Both keys are exercised: the primary on card number, and the secondary on identifier within one
     * card. Comparison is on the 32-byte composite key, character by character, which is exactly what
     * {@code CH} ordering on two adjacent character fields means.
     */
    @Test
    @DisplayName("5. the two-key ascending sort of CREASTMT.JCL:53 - card number then TRAN-ID - survives the "
            + "projection, which is what makes the scan's early exit at CBSTM03A.CBL:419 sound")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theTwoKeyAscendingSortSurvivesTheProjection() {
        final int cardsUsed = 3;
        final int perCard = 4;
        final List<String> cards = seededCardNumbersAscending().subList(0, cardsUsed);
        final List<Transaction> unordered = new ArrayList<>();
        // Deliberately reversed on both axes: the last card first, and the highest identifier first.
        for (int cardIndex = cardsUsed - 1; cardIndex >= 0; cardIndex--) {
            for (int sequence = perCard; sequence >= 1; sequence--) {
                unordered.add(probeTransaction(probeTransactionId(cardIndex * perCard + sequence),
                        cards.get(cardIndex)));
            }
        }
        transactionRepository.saveAll(unordered);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final List<String> records = projectedWorkRecords(execution);
        assertThat(records)
                .as("every committed transaction reaches SORTOUT; none is filtered by the sort")
                .hasSize(cardsUsed * perCard);

        String previousKey = null;
        for (final String record : records) {
            final String key = record.substring(0, StatementTransaction.KEY_LENGTH);
            if (previousKey != null) {
                assertThat(key)
                        .as("SORT FIELDS=(263,16,CH,A,1,16,CH,A) at app/jcl/CREASTMT.JCL:53 orders on card "
                                + "number then identifier, both ascending and both CH; the record for card "
                                + "%s broke that order",
                                maskedCardNumber(record.substring(0,
                                        StatementTransaction.CARD_NUMBER_LENGTH)))
                        .isGreaterThan(previousKey);
            }
            previousKey = key;
        }

        assertThat(records.getFirst().substring(0, StatementTransaction.CARD_NUMBER_LENGTH))
                .as("the primary key dominates: the lowest card number leads the stream even though it was "
                        + "committed last")
                .isEqualTo(cards.getFirst());
        assertThat(records.getFirst().substring(StatementTransaction.CARD_NUMBER_LENGTH,
                        StatementTransaction.KEY_LENGTH))
                .as("within that card the secondary key orders the identifiers ascending, so the lowest "
                        + "identifier of the lowest card is the very first record")
                .isEqualTo(probeTransactionId(1));
    }

    // The two output widths. app/jcl/CREASTMT.JCL:89 and :94.

    /**
     * One step emits two objects at two different widths: 80 bytes for the text and 100 for the markup.
     *
     * <p>Purpose: pin both widths, which is the whole point of {@code STEP040} - the same program writes
     * {@code STMTFILE} at {@code LRECL=80} ({@code app/jcl/CREASTMT.JCL:89}) and {@code HTMLFILE} at
     * {@code LRECL=100} ({@code :94}). Inputs: two probe transactions, so that both fixed and per-transaction
     * lines are emitted. Output: none. Side effects: the run commits 50 statement pairs and is reset by the
     * parent hook. Error modes: a size that is not an exact multiple of its width means a consumer counting
     * bytes would slice records apart, because both objects are unblocked and undelimited.
     *
     * <p><strong>The 80-versus-100 conflict is logged, not repaired.</strong> {@code STEP030}'s pre-delete
     * declares {@code HTMLFILE} at {@code LRECL=80} ({@code :69}) while {@code STEP040} allocates the same
     * dataset at {@code LRECL=100} ({@code :94}). The 100-byte width governs, and the reason it governs is
     * independent of the job control: {@code 05 HTML-FIXED-LN PIC X(100)} at
     * {@code app/cbl/CBSTM03A.CBL:148-149} is the field the program actually writes from. That independent
     * confirmation is precisely why the mismatch is a legacy defect to record rather than a signal to change
     * the width, and it is held as {@code DL-LD-02} in the root-owned {@code DECISION_LOG.md} rather than
     * here. Severity: Medium.
     */
    @Test
    @DisplayName("6. STMTFILE objects are exact multiples of 80 bytes and HTMLFILE objects of 100, per "
            + "CREASTMT.JCL:89 and :94; the 80-vs-100 conflict at :69 is logged, not repaired")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void statementTextObjectsAreEightyBytesWideAndMarkupObjectsAreOneHundred() {
        seedProbeTransactionsOnDistinctCards(2);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final List<S3Object> emitted = objectsUnder(statementsBucket, statementKeyRoot);
        assertThat(emitted)
                .as("STEP040 writes one text object and one markup object for every cross-reference the "
                        + "driving read returns, and app/data/ASCII/cardxref.txt seeds %d of them",
                        Integer.valueOf(seededCrossReferenceCount))
                .hasSize(seededCrossReferenceCount * 2);

        int textObjects = 0;
        int markupObjects = 0;
        for (final S3Object object : emitted) {
            if (object.key().endsWith(statementTextObjectName)) {
                textObjects++;
                assertThat(object.size().longValue()
                        % StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                        .as("%s is unblocked and undelimited at LRECL=%d per app/jcl/CREASTMT.JCL:89, so its "
                                + "size must be an exact multiple of the record length",
                                StatementWriter.STMTFILE_DD_NAME,
                                Integer.valueOf(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH))
                        .isZero();
                assertThat(object.size().longValue())
                        .as("a statement always carries its fixed heading and trailer lines, so no text "
                                + "object is empty")
                        .isPositive();
            } else {
                markupObjects++;
                assertThat(object.key())
                        .as("the only other object a statement run writes is the markup file")
                        .endsWith(statementMarkupObjectName);
                assertThat(object.size().longValue()
                        % StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                        .as("%s is allocated at LRECL=%d by app/jcl/CREASTMT.JCL:94 and written from "
                                + "05 HTML-FIXED-LN PIC X(100) at app/cbl/CBSTM03A.CBL:148-149; the "
                                + "LRECL=80 the pre-delete declares at :69 is a legacy defect, logged and "
                                + "not repaired",
                                StatementWriter.HTMLFILE_DD_NAME,
                                Integer.valueOf(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH))
                        .isZero();
                assertThat(object.size().longValue()).isPositive();
            }
        }

        assertThat(textObjects)
                .as("one text object per statement")
                .isEqualTo(seededCrossReferenceCount);
        assertThat(markupObjects)
                .as("one markup object per statement, written at a different width by the same step - which "
                        + "is the whole point of STEP040")
                .isEqualTo(seededCrossReferenceCount);
        assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                .as("the two widths must differ, or the conflict this test documents could not arise")
                .isNotEqualTo(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH);
    }

    /**
     * Object keys carry the account and month prefixes, and the month comes from the injected fixed clock.
     *
     * <p>Purpose: pin the object-key shape that replaces the generation data group, and pin its determinism.
     * A key built from the wall clock would differ between runs and between machines, so no assertion about
     * emitted output could be reproducible. Inputs: one probe transaction. Output: none. Side effects: the
     * run commits and is reset by the parent hook. Error modes: a failure means either a key segment changed
     * or a time source other than the injected clock leaked in.
     *
     * <p>The generation segment is zero padded, which is what makes lexical order equal creation order: a
     * relative {@code (+1)} reference becomes a new object under a monotonically increasing prefix, and a
     * {@code (0)} reference becomes the lexicographically greatest existing prefix. Nothing here re-resolves
     * a relative generation; the concrete key is read from the execution context the run published.
     */
    @Test
    @DisplayName("7. statement object keys carry the account and month segments, with the month derived from "
            + "the injected fixed clock and the generation zero-padded so lexical order is creation order")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void statementObjectKeysCarryTheAccountAndMonthSegmentsFromTheFixedClock() {
        seedProbeTransactionsOnDistinctCards(1);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final String expectedMonth = YearMonth.now(clock()).toString();
        final String expectedGeneration = String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                execution.getJobInstance().getInstanceId());

        final List<S3Object> emitted = objectsUnder(statementsBucket, statementKeyRoot);
        assertThat(emitted).isNotEmpty();
        for (final S3Object object : emitted) {
            assertThat(object.key())
                    .as("every statement object is keyed by account, then statement month, then generation, "
                            + "then the statement's own ordinal, and the month is formatted from the fixed "
                            + "clock rather than from wall time")
                    .startsWith(statementKeyRoot + "account=")
                    .contains("/month=" + expectedMonth + "/")
                    .contains("/generation=" + expectedGeneration + "/")
                    .containsPattern("/statement=\\d{" + generationSegmentWidth + "}/");
        }

        assertThat(emitted.stream().map(S3Object::key).toList())
                .as("both objects of the account whose card carries the probe transaction are present under "
                        + "the account's own prefix")
                .contains(soleStatementObjectKey(probeAccountId(), expectedMonth, expectedGeneration,
                                statementTextObjectName),
                        soleStatementObjectKey(probeAccountId(), expectedMonth, expectedGeneration,
                                statementMarkupObjectName));

        assertThat(emitted.stream().map(S3Object::key).toList())
                .as("finding F-01: the key identifies a statement, so no two objects of a run can share one "
                        + "and no statement can be written over another")
                .doesNotHaveDuplicates()
                .hasSize(seededCrossReferenceCount * 2);
    }

    // The removed resident-table ceiling. app/cbl/CBSTM03A.CBL:225-230 - a labelled deviation.

    /**
     * More than the legacy {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN}
     * transactions are processed correctly - <strong>a deliberate deviation, not parity</strong>.
     *
     * <p>Purpose: pin, and label, the one place where this job is knowingly <em>not</em> byte-for-byte
     * equivalent to its source. Inputs: {@value #aboveCeilingTransactionCount} committed transactions spread
     * over the fifty seeded cards. Output: none. Side effects: the run commits and is reset by the parent
     * hook. Error modes: a failure means the streaming replacement regressed to a bounded buffer.
     *
     * <p><strong>What the legacy program does.</strong> {@code app/cbl/CBSTM03A.CBL:225-230} declares
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} each containing {@code 10 WS-TRAN-TBL OCCURS 10 TIMES}, so the
     * resident table holds at most {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_CARDS_PER_RUN}
     * cards times {@value com.cardemo.model.dto.StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_CARD}
     * transactions. The building loop increments both subscripts with <strong>no bounds check anywhere</strong>,
     * so exceeding either limit is a storage overrun rather than a diagnosed failure.
     *
     * <p><strong>Why the deviation is taken, and what justifies it.</strong> Streaming is both more efficient
     * and safer: it removes a silent corruption path entirely. But it changes behaviour at scale, and Rule 1
     * Clause A permits a tradeoff only when it is justified rather than assumed - so this is recorded as a
     * labelled deviation with the legacy ceiling preserved as the historical capacity limit, held as
     * {@code DL-DV-03} in the root-owned {@code DECISION_LOG.md}. Pretending the ceiling was preserved would be false; leaving its
     * removal unasserted would be worse. The input here exceeds <em>both</em> legacy limits: the run total,
     * and the per-card limit, because {@value #aboveCeilingTransactionCount} spread over
     * {@value #seededCrossReferenceCount} cards puts more than ten on some of them.
     *
     * <p><strong>And no authored ceiling replaces the legacy one.</strong> This test used to close by asserting
     * that {@code StatementProcessor} carried "a diagnosed bound of its own"; finding BAT-002 removed that
     * bound and its per-run twin, because a refusal at an invented threshold is a business rule the corpus
     * does not contain. The closing assertion is now the absence of both, which is what the review asked for:
     * bounded-memory streaming, and no record-count refusal.
     */
    @Test
    @DisplayName("8. more than the legacy 51x10 = 510-transaction ceiling of CBSTM03A.CBL:225-230 is "
            + "processed correctly - a labelled, justified deviation and never parity")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void moreThanTheLegacyFiveHundredAndTenTransactionCeilingIsProcessedCorrectly() {
        final Map<String, Integer> perCard = seedTransactionsAcrossEverySeededCard(aboveCeilingTransactionCount);
        assertThat(aboveCeilingTransactionCount)
                .as("the input must exceed the legacy run ceiling, or this test would prove nothing")
                .isGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN);
        assertThat(perCard.values().stream().mapToInt(Integer::intValue).max().orElse(0))
                .as("and it must exceed the legacy per-card ceiling of %d as well, so both bounds of the "
                        + "51x10 table are passed rather than only the total",
                        Integer.valueOf(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD))
                .isGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final ExecutionContext jobContext = execution.getExecutionContext();
        assertThat(jobContext.getInt(workRecordCountContextEntry))
                .as("STEP010 projects every one of the %d committed transactions; the legacy program would "
                        + "have overrun its resident table at %d",
                        Integer.valueOf(aboveCeilingTransactionCount),
                        Integer.valueOf(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN))
                .isEqualTo(aboveCeilingTransactionCount);
        assertThat(projectedWorkRecords(execution))
                .as("and every one of them reaches SORTOUT as a full-width record")
                .hasSize(aboveCeilingTransactionCount);
        assertThat(jobContext.getLong(statementsEmittedContextEntry))
                .as("STEP040 still emits one statement per cross-reference; the removed ceiling changes "
                        + "capacity, not the statement census")
                .isEqualTo(seededCrossReferenceCount);

        assertThat(Arrays.stream(StatementProcessor.class.getDeclaredFields()).map(Field::getName))
                .as("finding BAT-002: the streaming replacement carries NO record ceiling of its own, so the "
                        + "legacy %d is a recorded historical capacity and not a threshold this system "
                        + "enforces under another name",
                        Integer.valueOf(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN))
                .doesNotContain("MAX_TRANSACTIONS_PER_RUN", "MAX_TRANSACTIONS_PER_CARD_GROUP",
                        "maxTransactionsPerRun");
    }

    // The five-stage initialisation. app/cbl/CBSTM03A.CBL:296-314, :760-761, :851-852, :779-780,
    // :797-798, :815-816.

    /**
     * The transaction stream is primed and its groups are available <em>before</em> the first cross-reference
     * is read.
     *
     * <p>Purpose: pin the observable consequence of the initialisation order, which is the only part of the
     * eliminated self-modifying dispatch that survives as behaviour. Inputs: one probe transaction placed on
     * the <strong>first</strong> card in ascending cross-reference order, so it is consumed by the very first
     * driving read. Output: none. Side effects: the run commits and is reset by the parent hook. Error modes:
     * a failure means the transaction stream was opened after, or not before, the cross-reference stream, and
     * the earliest statement would then be missing its transactions.
     *
     * <p><strong>Why this is the right assertion, and why no dispatch table is asserted.</strong>
     * {@code app/cbl/CBSTM03A.CBL} alters a paragraph's branch target at run time, which looks like a
     * data-driven dispatch table and is not one: every transition is hard-coded in its own handler's tail, so
     * the machine is deterministic and admits exactly one path - open and prime the transaction file, build
     * the resident table, open the cross-reference file, open the customer file, open the account file, then
     * {@code GO TO 1000-MAINLINE} at {@code :815} and leave the machine permanently, followed by an
     * unreachable {@code EXIT} at {@code :816}. The Java equivalent is therefore an ordered sequence of five
     * calls, and what is testable about it is the <em>order</em>, not the existence of a table. A
     * data-definition-keyed strategy map does exist in the migration, but it belongs to
     * {@code com.cardemo.service.shared.FileService}, where {@code app/cbl/CBSTM03B.CBL}'s dataset-by-operation
     * matrix genuinely varies; asserting one here would test a variability this level does not have.
     *
     * <p>Placing the probe on the lowest card number is what makes the order observable: that card is the
     * first group the resident table holds and the first cross-reference the driving read returns, so its
     * statement can only carry the transaction if stages one and two completed before stage three began.
     */
    @Test
    @DisplayName("9. the transaction stream is primed and its table built before the first cross-reference is "
            + "read, preserving the observable order of CBSTM03A.CBL:296-314 through to :815")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theTransactionStreamIsPrimedBeforeTheFirstCrossReferenceIsRead() {
        final CardCrossReference firstCrossReference = firstSeededCrossReference();
        final String transactionId = probeTransactionId(1);
        seedTransaction(transactionId, firstCrossReference.getCardNumber());

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final String accountSegment = accountKeySegment(firstCrossReference.getAccountId());
        final List<String> statementLines = statementTextRecords(accountSegment,
                execution.getJobInstance().getInstanceId());
        assertThat(statementLines)
                .as("the first cross-reference in ascending card order produces a statement of its own")
                .isNotEmpty();
        assertThat(statementLines)
                .as("app/cbl/CBSTM03A.CBL:676-679 writes one detail line per transaction of the card, so the "
                        + "probe identifier must open one of this statement's %d-byte records; it can only "
                        + "do so if TRNXFILE was opened, primed and grouped before the first XREFFILE read",
                        Integer.valueOf(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH))
                .anySatisfy(line -> assertThat(line).startsWith(transactionId));
        assertThat(statementLines)
                .as("the detail line carries the transaction description as well, which comes from the same "
                        + "resident group and so is further evidence the group was available")
                .anySatisfy(line -> assertThat(line).contains(probeDescription));
    }

    // The file-service read guards. app/cbl/CBSTM03A.CBL - nine '00' or '04' sites, one strict get-next.

    /**
     * The same file status is fatal on one transaction read path and normal on the other, so no blanket
     * "any status but {@code '00'} is an error" rule - and no blanket "{@code '10'} is always end of file"
     * rule - is in force.
     *
     * <p>Purpose: pin the asymmetry between the two read guards {@code app/cbl/CBSTM03A.CBL} applies to the
     * same dataset, which is the whole substance of the scoped leniency and the thing a unified rule would
     * destroy in either direction. Inputs: two launches - the first over the deliberately empty seed state,
     * the second over two committed transactions. Output: none. Side effects: both runs commit, and the
     * parent's reset hook restores everything afterwards. Error modes: a completed first run would mean the
     * priming guard had been widened to treat end of file as success; a failed second run would mean the
     * get-next guard had been narrowed to treat it as an error.
     *
     * <p><strong>The two guards.</strong> The priming read that immediately follows the open at
     * {@code app/cbl/CBSTM03A.CBL:744-754} accepts either {@code '00'} or {@code '04'} and does
     * <em>not</em> recognise end of file - its {@code ELSE} at {@code :750-753} displays
     * {@code 'ERROR READING TRNXFILE'} and performs {@code 9999-ABEND-PROGRAM}, because an empty dataset is
     * not an anticipated outcome there. The repeated get-next at {@code :837} accepts {@code '00'} alone and
     * treats {@code '10'} as the end of the data, which is how the resident-table build terminates on every
     * successful run. This pair is the third and last of exactly three scoped file-status leniencies in the
     * corpus; the other two are the record-not-found paths of the posting and interest programs.
     *
     * <p><strong>Why the contrast is the assertion.</strong> Status {@code '10'} arrives at both guards. On
     * the priming path it is fatal; on the get-next path it is the normal terminator. Observing both in one
     * method is what proves the two guards are genuinely distinct rather than one guard reached twice - a
     * single launch could not distinguish "the guards differ" from "this status never reached the other
     * guard".
     *
     * <p><strong>What is deliberately not attempted here, and where it is covered.</strong> Observing a
     * literal {@code '04'} come back from the binding requires a substituted dataset that returns one, which
     * is a stubbed collaborator and therefore the sibling unit tier's business;
     * {@code com.cardemo.service.shared.FileService} is moreover job scoped, so it has no instance outside a
     * running job and cannot be driven directly from a test thread at all. The unit tier's own file-service
     * test exercises {@code readAcceptingSecondaryStatus} against substituted statuses. This tier asserts
     * what only an assembled run can.
     */
    @Test
    @DisplayName("10. status '10' is fatal at the priming read of CBSTM03A.CBL:744-754 and is end of file at "
            + "the get-next of :837, so neither a blanket error rule nor a blanket end-of-file rule applies")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theTwoTransactionReadGuardsAreDistinctSoNoBlanketStatusRuleApplies() {
        assertThat(transactionRepository.count())
                .as("the first launch runs over the deliberate zero-row seed, so the priming read meets end "
                        + "of file immediately")
                .isZero();

        final JobExecution onEmptyStream = launchStatementGeneration();
        assertThat(onEmptyStream.getStatus())
                .as("the priming read's guard accepts only '00' or '04', so end of file falls to the ELSE at "
                        + "app/cbl/CBSTM03A.CBL:750-753 and abends the step - a widened guard that treated "
                        + "'10' as success here would silently produce statements with no transactions")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecutionNamed(onEmptyStream, emitStepName).getStatus())
                .as("it is STEP040 that fails, because the priming read happens in its initialisation")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessagesOf(onEmptyStream))
                .as("the failure names the data-definition name and the status it refused, preserving the "
                        + "root cause rather than reporting a bare step failure")
                .anySatisfy(message -> assertThat(message)
                        .contains(FileService.Dd.TRNXFILE.ddName())
                        .contains("10"));

        // Second launch, over a populated stream. Spring Batch derives the instance from the identifying
        // parameters, so this launch needs a discriminator of its own rather than reusing runId().
        seedProbeTransactionsOnDistinctCards(2);
        final JobExecution onPopulatedStream = launchJob(statementGenerationJob,
                jobParameters(Map.of(RUN_ID_PARAMETER, runId() + "-populated")));
        assertRunCompleted(onPopulatedStream);
        assertThat(stepExecutionNamed(onPopulatedStream, emitStepName).getStatus())
                .as("with records present the priming read succeeds and the resident-table build then runs "
                        + "the get-next loop to exhaustion; that loop can only terminate on '10', so a "
                        + "completed step is proof the same status is treated as end of file there")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(failureMessagesOf(onPopulatedStream))
                .as("and nothing was raised on the way, so no status encountered by either guard was "
                        + "misclassified")
                .isEmpty();
    }

    // COND=(0,NE). app/jcl/CREASTMT.JCL:56, :66, :79 - the corpus's only three sites.

    /**
     * On a zero return code all three condition-code gates admit their step, and none of the gates is a
     * container bean.
     *
     * <p>Purpose: pin both halves of the gating contract that this member alone justifies. Inputs: two probe
     * transactions, so every step has work to do. Output: none. Side effects: the run commits and is reset by
     * the parent hook. Error modes: a suppressed step on a clean run means a gate inverted its sense; a
     * resolvable decider bean means a decider escaped into the container, where it could collide with another
     * configuration's decider or be injected somewhere it does not belong.
     *
     * <p>{@code COND=(0,NE)} occurs corpus-wide at exactly three sites and all three are in this member:
     * {@code app/jcl/CREASTMT.JCL:56}, {@code :66} and {@code :79}. The four sibling batch members carry no
     * {@code COND} parameter at all, which is why deciders belong here and nowhere else. The first two steps
     * are ungated, so the transition into {@code STEP010} is unconditional and only then does the first gate
     * look at the return code.
     *
     * <p>The gates are reached through flow execution and the resulting step census - never through the bean
     * factory. That is the second assertion here, and it is made by <em>enumerating</em> the bean names of the
     * decider type rather than by attempting a resolution: an empty enumeration proves no definition exists,
     * whereas attempting to resolve one would be performing the very access the design forbids.
     */
    @Test
    @DisplayName("11. all three COND=(0,NE) gates at CREASTMT.JCL:56, :66 and :79 admit their step on a zero "
            + "return code, and no JobExecutionDecider is registered as a bean")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theConditionCodeGatesAdmitEveryStepOnAZeroReturnCodeAndAreNotBeans() {
        seedProbeTransactionsOnDistinctCards(2);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        assertThat(stepNamesInExecutionOrder(execution))
                .as("a zero return code from each predecessor means COND=(0,NE) suppresses nothing, so all "
                        + "three gated steps - STEP020 at :56, STEP030 at :66 and STEP040 at :79 - run")
                .containsExactlyElementsOf(expectedStepOrder);
        for (final StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getStatus())
                    .as("step %s completed, so the gate after it sees return code zero and admits its "
                            + "successor", step.getStepName())
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(step.getExitStatus().getExitCode())
                    .as("and it exits clean, which is the condition COND=(0,NE) tests")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }

        // Enumerating the bean names of the type is conclusive and needs no resolution attempt: a scoped
        // bean would contribute both its proxy name and its scopedTarget name, so an empty result means no
        // definition of this type exists at all. Resolving one instead would be the very thing the design
        // forbids - the supported way to reach a decider is flow execution and the observed outcome, which
        // is what the step census above uses.
        assertThat(applicationContext.getBeanNamesForType(JobExecutionDecider.class))
                .as("the deciders are inline plain objects passed straight to the flow builder, so none is "
                        + "registered in the container and none can collide with a decider another "
                        + "configuration declares")
                .isEmpty();

        for (final String stepName : expectedStepOrder) {
            assertThat(applicationContext.containsBean(stepName))
                    .as("each of the five steps is a bean, because a step is one of the three bean kinds the "
                            + "job configuration contributes: %s", stepName)
                    .isTrue();
        }
    }

    // The step handoff. app/jcl/CREASTMT.JCL:58-59 - STEP020's INFILE is STEP010's SORTOUT.

    /**
     * {@code STEP020} consumes exactly the object {@code STEP010} created, and the generation is reserved
     * once for the whole run.
     *
     * <p>Purpose: pin the handoff that {@code app/jcl/CREASTMT.JCL:58-59} expresses by naming one dataset in
     * two steps - {@code INFILE} of the {@code REPRO} is the {@code SORTOUT} of the sort. Inputs: three probe
     * transactions on distinct cards. Output: none. Side effects: the run commits and is reset by the parent
     * hook. Error modes: a mismatch means the load read a different generation from the one just written,
     * which on a versioned bucket is a silently stale read rather than a failure.
     *
     * <p><strong>Why the concrete key is carried forward rather than re-resolved.</strong> A relative
     * generation reference resolved a second time mid-job can name a different object - that is what makes
     * {@code (+1)} and {@code (0)} references hazardous once they are keys in a versioned store. The
     * migration therefore reserves the generation once, publishes the concrete key into the job execution
     * context, and has the load step read that entry. This test asserts all three: that the entry exists,
     * that the object it names is present at exactly the expected size, and that the key embeds the one
     * reserved generation.
     */
    @Test
    @DisplayName("12. STEP020 consumes exactly the work object key STEP010 published, at the one generation "
            + "reserved for the run - no relative generation is re-resolved mid-job")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theLoadStepConsumesExactlyTheWorkObjectKeyTheSortStepPublished() {
        final int committed = 3;
        seedProbeTransactionsOnDistinctCards(committed);

        final JobExecution execution = launchStatementGeneration();
        assertRunCompleted(execution);

        final ExecutionContext jobContext = execution.getExecutionContext();
        assertThat(jobContext.containsKey(workObjectKeyContextEntry))
                .as("STEP010 publishes the concrete key it created, which is how STEP020 reaches the same "
                        + "object that app/jcl/CREASTMT.JCL:58-59 names in both steps")
                .isTrue();
        assertThat(jobContext.containsKey(workGenerationContextEntry))
                .as("and the generation ordinal is published too, so it is reserved rather than recomputed")
                .isTrue();

        final long generation = jobContext.getLong(workGenerationContextEntry);
        final String publishedKey = jobContext.getString(workObjectKeyContextEntry);
        assertThat(publishedKey)
                .as("the key sits under the prefix bound from carddemo.aws.s3.work-prefixes.trxfl and embeds "
                        + "the reserved generation zero padded to %d digits, so lexical order is creation "
                        + "order and a (0) reference is a plain descending listing",
                        Integer.valueOf(generationSegmentWidth))
                .startsWith(workPrefix)
                .contains("/generation="
                        + String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                                Long.valueOf(generation))
                        + "/");

        assertThat(jobContext.getInt(workRecordCountContextEntry))
                .as("STEP010 wrote one record per committed transaction")
                .isEqualTo(committed);
        assertThat(jobContext.getInt(loadedRecordCountContextEntry))
                .as("REPRO INFILE(INFILE) OUTFILE(OUTFILE) at app/jcl/CREASTMT.JCL:61 copies the whole "
                        + "dataset, so the load consumes exactly what the sort produced - no filtering, no "
                        + "deduplication and no partial read")
                .isEqualTo(jobContext.getInt(workRecordCountContextEntry));

        final List<S3Object> workObjects = objectsUnder(batchOutputBucket, workPrefix);
        assertThat(workObjects.stream().map(S3Object::key).toList())
                .as("exactly the published key exists under the work prefix; a second object would mean a "
                        + "generation was resolved twice within one run")
                .containsExactly(publishedKey);
        assertThat(workObjects.getFirst().size().longValue())
                .as("the object is unblocked and undelimited at LRECL=350 per app/jcl/CREASTMT.JCL:50, so "
                        + "its size is exactly the record count times the record length")
                .isEqualTo((long) committed * StatementTransaction.RECORD_LENGTH);
    }

    // Boundary conditions.

    /**
     * An empty transaction relation: the first four steps succeed on nothing, and {@code STEP040} then abends
     * - which is what the source does and is therefore what parity requires.
     *
     * <p>Purpose: pin the null case explicitly, as Rule 1 Clause B requires, <em>with the outcome the source
     * actually has</em> rather than the outcome a reasonable reading would expect. Inputs: none at all - the
     * three migrations seed <em>zero</em> transaction rows deliberately, because the posting job is what
     * fills the relation, so the untouched seed state <strong>is</strong> the empty case and nothing needs to
     * be deleted to reach it. Output: none. Side effects: the run commits an empty work object and batch
     * metadata; the parent's reset hook restores both. Error modes: a completed run would mean the priming
     * guard was widened, which would silently emit fifty statements carrying no transactions at all.
     *
     * <p><strong>Why the run fails, and why that is correct.</strong> The two halves of the pipeline treat an
     * empty input differently, and both behaviours are the source's.
     *
     * <ul>
     *   <li>{@code SORT} with an empty {@code SORTIN} allocates an empty {@code SORTOUT} and returns zero, so
     *       {@code STEP010} completes, the {@code REPRO} at {@code app/jcl/CREASTMT.JCL:61} successfully
     *       copies nothing, and all three {@code COND=(0,NE)} gates admit their step. Four steps therefore
     *       succeed.</li>
     *   <li>{@code STEP040} then abends. {@code 8100-TRNXFILE-OPEN} primes the stream with a read whose guard
     *       at {@code app/cbl/CBSTM03A.CBL:748} accepts only {@code '00'} or {@code '04'}; end of file falls
     *       to the {@code ELSE} at {@code :750-753}, which displays {@code 'ERROR READING TRNXFILE'} and
     *       performs {@code 9999-ABEND-PROGRAM}. An empty transaction cluster is fatal to
     *       {@code CBSTM03A}.</li>
     * </ul>
     *
     * <p>So "it completed" would be the wrong assertion here and "it did not crash" would be the wrong
     * summary: the correct claim is that it produced the right nothing for four steps and then failed exactly
     * where the source fails. No abend code and no return code is asserted, for the reason given on
     * {@link #assertRunCompleted(org.springframework.batch.core.JobExecution)}.
     */
    @Test
    @DisplayName("13. an empty transaction relation - the deliberate seed state - completes the first four "
            + "steps on an empty work object and then abends STEP040, exactly as CBSTM03A.CBL:748-753 does")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anEmptyTransactionRelationCompletesFourStepsAndThenAbendsTheEmitStep() {
        assertThat(transactionRepository.count())
                .as("V3 seeds zero transaction rows on purpose, so the untouched state is the empty case and "
                        + "no deletion of seeded data is needed to reach it")
                .isZero();

        final JobExecution execution = launchStatementGeneration();

        assertThat(execution.getStatus())
                .as("app/cbl/CBSTM03A.CBL:748 accepts only '00' or '04' on its priming read, so an empty "
                        + "transaction cluster reaches the ELSE at :750-753 and abends; a completed run here "
                        + "would be a behaviour change dressed as robustness")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(stepNamesInExecutionOrder(execution))
                .as("all five steps are still reached: an empty sort returns zero, so COND=(0,NE) at :56, "
                        + ":66 and :79 suppresses nothing and STEP040 does run - and then fails")
                .containsExactlyElementsOf(expectedStepOrder);

        for (final String completedStep : List.of(defineStepName, sortStepName, loadStepName,
                preDeleteStepName)) {
            assertThat(stepExecutionNamed(execution, completedStep).getStatus())
                    .as("step %s succeeds on an empty input, because SORT with an empty SORTIN and REPRO of "
                            + "an empty dataset are both successful operations on nothing", completedStep)
                    .isEqualTo(BatchStatus.COMPLETED);
        }
        assertThat(stepExecutionNamed(execution, emitStepName).getStatus())
                .as("only STEP040 fails, and it fails in its initialisation rather than part way through "
                        + "emitting, so no partial statement is written")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessagesOf(execution))
                .as("the failure preserves its root cause and names the dataset that was empty")
                .anySatisfy(message -> assertThat(message)
                        .contains(FileService.Dd.TRNXFILE.ddName()));

        final ExecutionContext jobContext = execution.getExecutionContext();
        assertThat(jobContext.getInt(workRecordCountContextEntry))
                .as("an empty SORTIN yields an empty SORTOUT rather than a failed sort step")
                .isZero();
        assertThat(jobContext.getInt(loadedRecordCountContextEntry))
                .as("and the REPRO at app/jcl/CREASTMT.JCL:61 copies nothing, which is a successful copy of "
                        + "nothing")
                .isZero();
        assertThat(projectedWorkRecords(execution))
                .as("the work object exists and holds no records; it is empty, not absent")
                .isEmpty();
        assertThat(jobContext.containsKey(statementsEmittedContextEntry)
                        ? jobContext.getLong(statementsEmittedContextEntry) : 0L)
                .as("no statement is emitted, because the step failed before its first read")
                .isZero();
    }

    /**
     * The seeded census the statement path joins through is exactly what the fixtures declare.
     *
     * <p>Purpose: guard the premise every other test in this class rests on. Several assertions here are
     * stated as "one statement per cross-reference" and "fifty of them"; if the seed census ever changed,
     * those assertions would start measuring the seed rather than the job, and would do so silently. Inputs:
     * none. Output: none. Side effects: none - this method reads only, and runs inside the class-level
     * transaction. Error modes: a failure means a fixture or a migration changed and the counts elsewhere in
     * this class need revisiting rather than the job.
     *
     * <p>The statement path joins transaction to cross-reference to customer to account. Note what is
     * <em>not</em> asserted: there is no foreign key from the cross-reference's card number to the card
     * relation, so no such relationship is claimed here.
     */
    @Test
    @DisplayName("14. the seeded census the statement path joins through is 50 cross-references, 50 customers "
            + "and 50 accounts, which is the premise every count in this class rests on")
    void theSeededCensusTheStatementPathJoinsThroughIsFiftyOfEach() {
        assertThat(cardCrossReferenceRepository.count())
                .as("app/data/ASCII/cardxref.txt seeds fifty 36-byte rows, and the driving read of "
                        + "app/cbl/CBSTM03A.CBL:345 returns one statement per row")
                .isEqualTo(seededCrossReferenceCount);
        assertThat(customerRepository.count())
                .as("app/data/ASCII/custdata.txt seeds fifty 500-byte rows, reached through the "
                        + "cross-reference's customer identifier")
                .isEqualTo(seededCrossReferenceCount);
        assertThat(accountRepository.count())
                .as("app/data/ASCII/acctdata.txt seeds fifty 300-byte rows, reached through the "
                        + "cross-reference's account identifier, and each becomes one statement's account "
                        + "key segment")
                .isEqualTo(seededCrossReferenceCount);
        assertThat(seededCardNumbersAscending())
                .as("the ordered finder returns every seeded card number exactly once, in ascending order, "
                        + "which is the ordering the projection's primary sort key reproduces")
                .hasSize(seededCrossReferenceCount)
                .isSorted();
    }

    /**
     * Finding F-01: an account with two cross-reference rows keeps both statements.
     *
     * <p>Purpose: reproduce, and then close, a silent data loss. The emitted object key used to be a function
     * of the account, the statement month and the generation only, so it identified an account-month rather
     * than a statement - and an account-month holds as many statements as the account holds cards. Object
     * storage accepts a write to an existing key, reports success and keeps only the last, so the second
     * statement of an account destroyed the first with no warning, no error and a {@code COMPLETED} run. This
     * test fails on that implementation and passes only when the key carries the statement's own ordinal.
     *
     * <p>Inputs: one additional card on the account of the lowest-keyed seeded cross-reference, one additional
     * cross-reference row pointing at it, and one probe transaction on each of the account's two cards, with
     * distinct identifiers so that each statement's content is attributable to exactly one card. Output: none.
     * Side effects: the extra card and cross-reference rows are committed and are removed again in the
     * {@code finally} block, because the parent harness resets transactions, batch metadata, money columns and
     * the buckets but deliberately does not touch the card or cross-reference relations - test 14 asserts
     * their census, so a leak here would fail a different test and name the wrong cause.
     *
     * <p>Error modes: a failure of the count assertion means statements are being overwritten again; a failure
     * of the content assertions means the two statements exist but carry the wrong transactions, which would
     * point at the emission rather than at the key.
     *
     * <p>Several cards on one account is a designed state and not a contrivance: {@code CARDXREF.VSAM.AIX} and
     * {@code CARDDATA.VSAM.AIX} are <strong>non-unique</strong> alternate indexes on the account identifier
     * ({@code app/catlg/LISTCAT.txt:254-270}), {@code app/cbl/COCRDLIC.cbl} exists to list the several cards
     * of one account, and {@code app/cbl/CBSTM03A.CBL} opens its output once at {@code :293} and closes it
     * once at {@code :339}, appending one statement per cross-reference row into a single sequential dataset -
     * so the source retained every statement.
     */
    @Test
    @DisplayName("15. F-01: an account carrying two cross-reference rows keeps both statements - neither "
            + "object pair is written over the other, as CBSTM03A.CBL:293-339 retains both")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAccountWithTwoCrossReferenceRowsKeepsBothStatements() {
        final CardCrossReference first = firstSeededCrossReference();
        final String secondCardNumber = additionalCardNumber;
        final String firstTransactionId = probeTransactionId(1);
        final String secondTransactionId = probeTransactionId(2);
        try {
            cardRepository.save(new Card(secondCardNumber, first.getAccountId(), "123",
                    "QA SECOND CARD HOLDER", "2030-12-31", "Y"));
            cardCrossReferenceRepository.save(new CardCrossReference(secondCardNumber,
                    first.getCustomerId(), first.getAccountId()));
            seedTransaction(firstTransactionId, first.getCardNumber());
            seedTransaction(secondTransactionId, secondCardNumber);

            final JobExecution execution = launchStatementGeneration();
            assertRunCompleted(execution);

            final String month = YearMonth.now(clock()).toString();
            final String generation = String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                    execution.getJobInstance().getInstanceId());
            final String accountSegment = accountKeySegment(first.getAccountId());

            final long expectedStatements = seededCrossReferenceCount + 1L;
            assertThat(execution.getExecutionContext().getLong(statementsEmittedContextEntry))
                    .as("one statement per cross-reference row, and there are now %d of them",
                            Long.valueOf(expectedStatements))
                    .isEqualTo(expectedStatements);

            final List<String> emitted = objectsUnder(statementsBucket, statementKeyRoot).stream()
                    .map(S3Object::key)
                    .toList();
            assertThat(emitted)
                    .as("every statement emitted still exists: %d statements times the two output streams. "
                            + "A count of %d would mean the second statement of each two-card account had "
                            + "been silently overwritten", Long.valueOf(expectedStatements),
                            Long.valueOf(seededCrossReferenceCount * 2L))
                    .doesNotHaveDuplicates()
                    .hasSize((int) (expectedStatements * 2L));

            final List<String> textKeys = statementObjectKeys(accountSegment, month, generation,
                    statementTextObjectName);
            final List<String> markupKeys = statementObjectKeys(accountSegment, month, generation,
                    statementMarkupObjectName);
            assertThat(textKeys)
                    .as("the account now holds two cards, %s and %s, so its prefix holds two text objects",
                            maskedCardNumber(first.getCardNumber()), maskedCardNumber(secondCardNumber))
                    .hasSize(2);
            assertThat(markupKeys)
                    .as("and two markup objects, written at the other width by the same step")
                    .hasSize(2);

            final List<String> firstCardText = new ArrayList<>();
            final List<String> secondCardText = new ArrayList<>();
            for (final String key : textKeys) {
                final List<String> records = fixedWidthRecords(objectBytes(statementsBucket, key),
                        StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
                if (records.stream().anyMatch(record -> record.startsWith(firstTransactionId))) {
                    firstCardText.addAll(records);
                }
                if (records.stream().anyMatch(record -> record.startsWith(secondTransactionId))) {
                    secondCardText.addAll(records);
                }
            }
            assertThat(firstCardText)
                    .as("the statement of card %s survives and carries its own transaction",
                            maskedCardNumber(first.getCardNumber()))
                    .isNotEmpty();
            assertThat(secondCardText)
                    .as("so does the statement of card %s - this is the assertion that failed before F-01 was "
                            + "closed, because one of the two objects had replaced the other",
                            maskedCardNumber(secondCardNumber))
                    .isNotEmpty();
            assertThat(firstCardText)
                    .as("and neither statement carries the other card's transaction, so the two objects are "
                            + "two statements rather than one statement written twice")
                    .noneMatch(record -> record.startsWith(secondTransactionId));
            assertThat(secondCardText)
                    .noneMatch(record -> record.startsWith(firstTransactionId));

            for (final String key : markupKeys) {
                assertThat(objectBytes(statementsBucket, key).length
                        % StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                        .as("both markup objects keep the LRECL=100 geometry of app/jcl/CREASTMT.JCL:94")
                        .isZero();
            }
        } finally {
            // In this order, and each one a no-op when the row was never committed: the transaction refers to
            // the card through fk04_transaction_card, so it goes first. The parent harness empties the
            // transaction relation after every test but does not touch card or card_cross_reference, so these
            // two rows would otherwise outlive the test and fail test 14's census while naming the wrong cause.
            transactionRepository.deleteById(secondTransactionId);
            cardCrossReferenceRepository.deleteById(secondCardNumber);
            cardRepository.deleteById(secondCardNumber);
        }
    }

    // Launching and outcome helpers.

    /**
     * Launches the statement job with the run identifier the parent harness requires and nothing else.
     *
     * <p>{@code EXEC PGM=CBSTM03A} at {@code app/jcl/CREASTMT.JCL:79} passes no {@code PARM}, so unlike the
     * interest calculator this job has no parameter contract; the only parameter supplied is the per-test
     * discriminator without which two tests launching this job would be the same instance.
     *
     * @return the completed execution, never {@code null}
     */
    private JobExecution launchStatementGeneration() {
        return launchJob(statementGenerationJob, runIdParameters(Map.of()));
    }

    /**
     * Asserts the run finished cleanly, which every test here needs before it can assert anything else.
     *
     * <p>Deliberately says nothing about an abend code or a return code. {@code app/cbl/CBSTM03A.CBL:921-923}
     * abends on the language environment's default with no {@code ABCODE} and no {@code TIMING}, and the
     * program assigns no {@code RETURN-CODE} anywhere, so this job has no such contract to assert.
     *
     * @param execution the execution to check, never {@code null}
     */
    private void assertRunCompleted(final JobExecution execution) {
        assertThat(execution.getStatus())
                .as("the whole five-step flow of app/jcl/CREASTMT.JCL must complete; a failure here makes "
                        + "every later assertion in the test meaningless rather than merely wrong")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("and it must end on the clean exit code, which is the outcome return code zero maps to")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Returns the one step execution recorded under a name, failing the test if it is absent.
     *
     * @param execution the execution to inspect, never {@code null}
     * @param stepName the bean name of the step, never {@code null}
     * @return that step's execution, never {@code null}
     */
    private StepExecution stepExecutionNamed(final JobExecution execution, final String stepName) {
        final List<StepExecution> matching = new ArrayList<>();
        for (final StepExecution step : execution.getStepExecutions()) {
            if (stepName.equals(step.getStepName())) {
                matching.add(step);
            }
        }
        assertThat(matching)
                .as("step %s must have exactly one execution in this run; none means it was suppressed or "
                        + "never reached, and more than one means the flow re-entered it", stepName)
                .hasSize(1);
        return matching.getFirst();
    }

    /**
     * Collects the message of every failure the run recorded, and of every cause beneath it.
     *
     * <p>Walking the cause chain is what lets an assertion check the <em>root</em> reason rather than the
     * framework wrapper that carried it, which is the difference between proving why a step failed and merely
     * proving that it did. The walk stops at a self-referential cause so a malformed chain cannot loop.
     *
     * @param execution the execution to inspect, never {@code null}
     * @return every non-null message from every failure and cause, never {@code null}
     */
    private List<String> failureMessagesOf(final JobExecution execution) {
        final List<String> messages = new ArrayList<>();
        for (final Throwable failure : execution.getAllFailureExceptions()) {
            Throwable current = failure;
            while (current != null) {
                if (current.getMessage() != null) {
                    messages.add(current.getMessage());
                }
                current = current.getCause() == current ? null : current.getCause();
            }
        }
        return messages;
    }

    /**
     * Returns the names of the steps that ran, in the order they started.
     *
     * <p>Sorted by start time and then by identifier, so that two steps recorded in the same clock tick still
     * order deterministically rather than by whatever sequence the metadata query happened to return.
     *
     * @param execution the execution to inspect, never {@code null}
     * @return the step names in execution order, never {@code null}
     */
    private List<String> stepNamesInExecutionOrder(final JobExecution execution) {
        final List<StepExecution> ordered = new ArrayList<>(execution.getStepExecutions());
        ordered.sort(Comparator
                .comparing(StepExecution::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StepExecution::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        final List<String> names = new ArrayList<>(ordered.size());
        for (final StepExecution step : ordered) {
            names.add(step.getStepName());
        }
        return names;
    }

    // Object-store readers.

    /**
     * Reads the projected work object this run created and splits it into fixed-width records.
     *
     * <p>The concrete key comes from the job execution context entry the sort step published, never from a
     * re-resolved relative generation. The stream is unblocked and undelimited, so boundaries are found by
     * counting bytes; decoding uses a single-byte charset so one byte is one character and an offset in the
     * decoded string is the same offset as in the payload.
     *
     * @param execution the completed execution, never {@code null}
     * @return the projected records in sort order, each exactly 350 characters, never {@code null}
     */
    private List<String> projectedWorkRecords(final JobExecution execution) {
        final ExecutionContext jobContext = execution.getExecutionContext();
        assertThat(jobContext.containsKey(workObjectKeyContextEntry))
                .as("STEP010 publishes the key of the object it wrote; without it there is nothing to read "
                        + "and re-resolving a generation here would defeat the handoff being measured")
                .isTrue();
        return fixedWidthRecords(objectBytes(batchOutputBucket,
                jobContext.getString(workObjectKeyContextEntry)), StatementTransaction.RECORD_LENGTH);
    }

    /**
     * Reads one account's statement text object and splits it into its
     * {@value com.cardemo.model.dto.StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH}-byte records.
     *
     * @param accountSegment the eleven-digit account key segment, never {@code null}
     * @param generation the job instance ordinal the statement keys embed
     * @return the statement's text records in write order, never {@code null}
     */
    private List<String> statementTextRecords(final String accountSegment, final long generation) {
        final String key = soleStatementObjectKey(accountSegment, YearMonth.now(clock()).toString(),
                String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d", Long.valueOf(generation)),
                statementTextObjectName);
        return fixedWidthRecords(objectBytes(statementsBucket, key),
                StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
    }

    /**
     * The prefix under which every statement object of one account, month and generation is filed.
     *
     * @param accountSegment the eleven-digit account identifier, never {@code null}
     * @param month the {@code uuuu-MM} statement month, never {@code null}
     * @param generation the zero-padded generation ordinal, never {@code null}
     * @return the prefix, ending in the key separator, never {@code null}
     */
    private String statementObjectPrefix(final String accountSegment, final String month,
            final String generation) {

        return statementKeyRoot + "account=" + accountSegment + "/month=" + month
                + "/generation=" + generation + "/";
    }

    /**
     * Resolves the keys of one output stream for one account, month and generation, in ascending key order.
     *
     * <p><strong>Resolved by enumeration rather than composed here, and that is the point of finding
     * F-01.</strong> A key identifies a statement, not an account-month: it carries the statement's ordinal
     * within the run beneath the generation, because the driving read returns one cross-reference row per card
     * and {@code CARDXREF.VSAM.AIX} is a <em>non-unique</em> alternate index on the account identifier
     * ({@code app/catlg/LISTCAT.txt:254-270}). A test that rebuilt the whole key from business data alone
     * would therefore be asserting the very assumption that lost twelve statements - that an account-month has
     * exactly one - so this method asks the bucket instead, and the account, month and generation prefix is
     * what it asks with.
     *
     * @param accountSegment the eleven-digit account identifier, never {@code null}
     * @param month the {@code uuuu-MM} statement month, never {@code null}
     * @param generation the zero-padded generation ordinal, never {@code null}
     * @param objectName the file name, either the text object or the markup object, never {@code null}
     * @return every matching key, ascending, never {@code null} and possibly empty
     */
    private List<String> statementObjectKeys(final String accountSegment, final String month,
            final String generation, final String objectName) {

        return objectsUnder(statementsBucket, statementObjectPrefix(accountSegment, month, generation))
                .stream()
                .map(S3Object::key)
                .filter(key -> key.endsWith(objectName))
                .toList();
    }

    /**
     * Resolves the single key of one output stream for one account, month and generation.
     *
     * @param accountSegment the eleven-digit account identifier, never {@code null}
     * @param month the {@code uuuu-MM} statement month, never {@code null}
     * @param generation the zero-padded generation ordinal, never {@code null}
     * @param objectName the file name, either the text object or the markup object, never {@code null}
     * @return the one matching key, never {@code null}
     */
    private String soleStatementObjectKey(final String accountSegment, final String month,
            final String generation, final String objectName) {

        final List<String> keys = statementObjectKeys(accountSegment, month, generation, objectName);
        assertThat(keys)
                .as("account %s holds one cross-reference row in the seeded state, so exactly one %s object "
                        + "must exist under its month and generation prefix", accountSegment, objectName)
                .hasSize(1);
        return keys.getFirst();
    }

    /**
     * Lists every object under one prefix, in ascending key order.
     *
     * <p>Sorted explicitly rather than trusting the listing order, and collected into a list rather than a
     * hash-ordered collection, so that an ordering assertion is never mediated by iteration order.
     *
     * @param bucket the bucket to list, never {@code null}
     * @param prefix the key prefix to restrict the listing to, never {@code null}
     * @return the matching objects in ascending key order, never {@code null}
     */
    private List<S3Object> objectsUnder(final String bucket, final String prefix) {
        final List<S3Object> found = new ArrayList<>();
        s3Client.listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents()
                .forEach(found::add);
        found.sort(Comparator.comparing(S3Object::key));
        return found;
    }

    /**
     * Reads one object whole.
     *
     * @param bucket the bucket holding it, never {@code null}
     * @param key the object key, never {@code null}
     * @return the object's bytes, never {@code null}
     */
    private byte[] objectBytes(final String bucket, final String key) {
        return s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asByteArray();
    }

    /**
     * Splits an undelimited fixed-width payload into records of one width.
     *
     * <p>Trailing bytes that do not complete a record are not silently absorbed: they simply do not form a
     * record, so a truncated object shows up as a short record count rather than as a malformed last record.
     *
     * @param payload the object's bytes, never {@code null}
     * @param width the record length in bytes, positive
     * @return the complete records in payload order, never {@code null}
     */
    private List<String> fixedWidthRecords(final byte[] payload, final int width) {
        final String decoded = new String(payload, StandardCharsets.ISO_8859_1);
        final List<String> records = new ArrayList<>(decoded.length() / width);
        for (int offset = 0; offset + width <= decoded.length(); offset += width) {
            records.add(decoded.substring(offset, offset + width));
        }
        return records;
    }

    // Seeded-state readers.

    /**
     * Returns every seeded cross-reference card number in ascending order.
     *
     * <p>The ordering comes from the repository's own ordered finder rather than from a comparator here,
     * because it is the database's ordering that the projection's primary sort key has to reproduce. The empty
     * string is the exclusive lower bound that starts at the beginning of the sequence.
     *
     * @return the fifty seeded card numbers, ascending, never {@code null}
     */
    private List<String> seededCardNumbersAscending() {
        final List<String> cardNumbers = new ArrayList<>(seededCrossReferenceCount);
        for (final CardCrossReference crossReference : cardCrossReferenceRepository
                .findByCardNumberGreaterThanOrderByCardNumberAsc("",
                        PageRequest.of(0, seededCrossReferenceCount))) {
            cardNumbers.add(crossReference.getCardNumber());
        }
        return cardNumbers;
    }

    /**
     * Returns the cross-reference the driving read of {@code app/cbl/CBSTM03A.CBL:345} returns first.
     *
     * @return the lowest-keyed seeded cross-reference, never {@code null}
     */
    private CardCrossReference firstSeededCrossReference() {
        final List<CardCrossReference> first = cardCrossReferenceRepository
                .findByCardNumberGreaterThanOrderByCardNumberAsc("", PageRequest.of(0, 1));
        assertThat(first)
                .as("the three migrations seed the cross-reference relation, so an ordered read of it must "
                        + "return a first row")
                .hasSize(1);
        return first.getFirst();
    }

    /**
     * Renders an account identifier as the eleven-digit object-key segment the statement writer requires.
     *
     * @param accountId the account identifier, never {@code null}
     * @return eleven ASCII digits, never {@code null}
     */
    private String accountKeySegment(final Long accountId) {
        return String.format(Locale.ROOT, "%011d", accountId);
    }

    /**
     * The account key segment of the card the single-probe tests place their transaction on.
     *
     * @return eleven ASCII digits, never {@code null}
     */
    private String probeAccountId() {
        return accountKeySegment(firstSeededCrossReference().getAccountId());
    }

    /**
     * Masks a card number down to its last four digits for use in an assertion description.
     *
     * <p>A sixteen-digit card number is exactly the kind of value Rule 1 Clause D keeps out of logs, messages
     * and test output, so no description in this class carries one whole.
     *
     * @param cardNumber the card number to mask, never {@code null}
     * @return four asterisks followed by at most the last four digits, never {@code null}
     */
    private String maskedCardNumber(final String cardNumber) {
        final int visible = 4;
        if (cardNumber.length() <= visible) {
            return "****";
        }
        return "****" + cardNumber.substring(cardNumber.length() - visible);
    }

    // Probe fixtures. Every value is deterministic and every timestamp derives from the injected clock.

    /**
     * The leading {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH}
     * characters shared by both probe timestamps, in the batch timestamp shape and derived from the fixed
     * clock.
     *
     * <p>The batch generator renders {@code uuuu-MM-dd-HH.mm.ss.SS} and then four literal zeros for a
     * twenty-six character total; the first twenty-four of those are reproduced here. Nothing is read from
     * wall time, so both timestamps - and therefore every projected record - are byte-identical on every run
     * and on every machine.
     *
     * @return twenty-four characters, never {@code null}
     */
    private String probeTimestampPrefix() {
        return LocalDateTime.ofInstant(fixedInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SS", Locale.ROOT)) + "00";
    }

    /**
     * The probe {@code TRAN-ORIG-TS}: the shared prefix plus a suffix the projection must preserve.
     *
     * @return exactly {@value com.cardemo.model.dto.StatementTransaction#ORIGINATING_TIMESTAMP_LENGTH}
     *     characters, never {@code null}
     */
    private String probeOriginatingTimestamp() {
        return probeTimestampPrefix() + originatingTimestampProbeSuffix;
    }

    /**
     * The probe {@code TRAN-PROC-TS}: the shared prefix plus a suffix the projection must discard.
     *
     * @return exactly {@value com.cardemo.model.dto.StatementTransaction#PROCESSING_TIMESTAMP_LENGTH}
     *     characters, never {@code null}
     */
    private String probeProcessingTimestamp() {
        return probeTimestampPrefix() + processingTimestampProbeSuffix;
    }

    /**
     * Renders a sixteen-character {@code TRAN-ID} from a sequence number, zero padded so that numeric order
     * and the character ordering of {@code SORT FIELDS} agree.
     *
     * @param sequence a positive sequence number
     * @return exactly sixteen digits, never {@code null}
     */
    private String probeTransactionId(final int sequence) {
        return String.format(Locale.ROOT, "%016d", Integer.valueOf(sequence));
    }

    /**
     * Builds one transient probe transaction that satisfies every constraint the schema declares.
     *
     * <p>The type code and category are values the seed guarantees exist, so the three foreign keys the
     * relation carries on those columns are satisfied; the card number must be one the seed carries, which
     * satisfies the fourth. The amount is a {@code BigDecimal} - no binary floating type appears anywhere in
     * this class - and both timestamps are full-width and derived from the fixed clock.
     *
     * @param transactionId the sixteen-character identifier, never {@code null}
     * @param cardNumber an existing sixteen-character card number, never {@code null}
     * @return a transient entity, never {@code null}
     */
    private Transaction probeTransaction(final String transactionId, final String cardNumber) {
        return new Transaction(transactionId, seededTypeCode, seededCategoryCode, probeSource,
                probeDescription, probeAmount, probeMerchantId, probeMerchantName, probeMerchantCity,
                probeMerchantZip, cardNumber, probeOriginatingTimestamp(), probeProcessingTimestamp());
    }

    /**
     * Commits one probe transaction, so that a job launched afterwards can see it.
     *
     * <p>A launched job reads only committed state, and a launching test method runs outside the class-level
     * transaction, so this write is committed by the repository call itself.
     *
     * @param transactionId the sixteen-character identifier, never {@code null}
     * @param cardNumber an existing sixteen-character card number, never {@code null}
     */
    private void seedTransaction(final String transactionId, final String cardNumber) {
        transactionRepository.save(probeTransaction(transactionId, cardNumber));
    }

    /**
     * Commits one probe transaction on each of the first {@code count} seeded cards, in ascending card order.
     *
     * @param count how many distinct cards to use, between one and the seeded cross-reference count
     */
    private void seedProbeTransactionsOnDistinctCards(final int count) {
        final List<String> cards = seededCardNumbersAscending();
        assertThat(count)
                .as("a probe cannot use more cards than the fixtures seed")
                .isBetween(1, cards.size());
        final List<Transaction> probes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            probes.add(probeTransaction(probeTransactionId(index + 1), cards.get(index)));
        }
        transactionRepository.saveAll(probes);
    }

    /**
     * Commits {@code total} probe transactions spread round robin over every seeded card.
     *
     * <p>Round robin rather than clustered, so that the per-card population is as even as the total allows and
     * the returned census can be checked against the legacy per-card ceiling.
     *
     * @param total how many transactions to commit, at least one per card
     * @return how many transactions each card received, keyed by card number in insertion order
     */
    private Map<String, Integer> seedTransactionsAcrossEverySeededCard(final int total) {
        final List<String> cards = seededCardNumbersAscending();
        final Map<String, Integer> census = new LinkedHashMap<>();
        for (final String cardNumber : cards) {
            census.put(cardNumber, Integer.valueOf(0));
        }
        final List<Transaction> probes = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            final String cardNumber = cards.get(index % cards.size());
            probes.add(probeTransaction(probeTransactionId(index + 1), cardNumber));
            census.put(cardNumber, Integer.valueOf(census.get(cardNumber).intValue() + 1));
        }
        transactionRepository.saveAll(probes);
        return census;
    }
}
